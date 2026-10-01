package com.example.bridge.sql;

import com.example.bridge.protocol.PgMessages;
import com.example.bridge.session.Session;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

/**
 * Executes validated SELECT queries against SQLite opened read-only and
 * streams rows back with bounded in-flight data: when the channel becomes
 * unwritable the worker pauses instead of buffering the whole result set.
 */
public class QueryEngine {
    private final String jdbcUrl;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sqlite-query-worker");
        t.setDaemon(true);
        return t;
    });
    private final Map<Session, Connection> connections = new ConcurrentHashMap<>();

    public QueryEngine(String sqliteFile) {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setOpenMode(SQLiteOpenMode.READONLY);
        this.jdbcUrl = "jdbc:sqlite:file:" + sqliteFile + "?mode=ro";
        this.properties = config.toProperties();
    }

    private final java.util.Properties properties;

    private Connection connectionFor(Session session) throws SQLException {
        Connection c = connections.get(session);
        if (c == null || c.isClosed()) {
            c = DriverManager.getConnection(jdbcUrl, properties);
            connections.put(session, c);
        }
        return c;
    }

    public void execute(Session session, ChannelHandlerContext ctx, String sql) {
        executor.submit(() -> runQuery(session, ctx, sql));
    }

    private void runQuery(Session session, ChannelHandlerContext ctx, String sql) {
        try {
            if (sql.trim().isEmpty()) {
                ctx.writeAndFlush(PgMessages.emptyQueryResponse(ctx));
                return;
            }
            if (isSessionSetCommand(sql)) {
                // Tolerate client session setup (e.g. pgjdbc's SET extra_float_digits).
                ctx.writeAndFlush(PgMessages.commandComplete(ctx, "SET"));
                return;
            }
            try {
                SqlGuard.validate(sql);
            } catch (SqlGuard.SqlRejectedException e) {
                ctx.writeAndFlush(PgMessages.error(ctx, "ERROR", "0A000", e.getMessage()));
                return;
            }
            Connection conn = connectionFor(session);
            try (Statement stmt = conn.createStatement()) {
                RunningQuery running = new RunningQuery(stmt, ctx.channel());
                session.setCurrentQuery(running);
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    sendRows(session, ctx, rs, running);
                }
            }
        } catch (SQLException e) {
            String state = isInterrupt(e) ? "57014" : "42601";
            String msg = isInterrupt(e) ? "canceling statement due to user request" : e.getMessage();
            writeSafely(ctx, PgMessages.error(ctx, "ERROR", state, msg));
        } catch (QueryCancelledException e) {
            writeSafely(ctx, PgMessages.error(ctx, "ERROR", "57014", "canceling statement due to user request"));
        } catch (Exception e) {
            writeSafely(ctx, PgMessages.error(ctx, "ERROR", "XX000", String.valueOf(e.getMessage())));
        } finally {
            session.queryFinished();
        }
    }

    private void sendRows(Session session, ChannelHandlerContext ctx, ResultSet rs, RunningQuery running)
            throws SQLException, QueryCancelledException {
        Channel channel = ctx.channel();
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        List<String> names = new ArrayList<>(cols);
        int[] types = new int[cols];
        for (int i = 1; i <= cols; i++) {
            names.add(md.getColumnLabel(i));
            types[i - 1] = md.getColumnType(i);
        }
        ctx.write(PgMessages.rowDescription(ctx, names));
        long count = 0;
        while (rs.next()) {
            running.throwIfCancelled();
            awaitWritable(channel, running);
            List<String> values = new ArrayList<>(cols);
            for (int i = 1; i <= cols; i++) {
                if (types[i - 1] == Types.BLOB || types[i - 1] == Types.BINARY || types[i - 1] == Types.VARBINARY) {
                    byte[] b = rs.getBytes(i);
                    values.add(b == null ? null : PgMessages.hex(b));
                } else {
                    values.add(rs.getString(i)); // null stays null, "" stays ""
                }
            }
            ctx.write(PgMessages.dataRow(ctx, values));
            if (++count % 256 == 0) ctx.flush();
        }
        ctx.write(PgMessages.commandComplete(ctx, "SELECT " + count));
        ctx.flush();
    }

    /** Bounded sending: pause while the channel's write buffer is full. */
    private static void awaitWritable(Channel channel, RunningQuery running) throws QueryCancelledException {
        while (!channel.isWritable()) {
            running.throwIfCancelled();
            if (!channel.isOpen()) throw new QueryCancelledException();
            running.awaitWritableSignal();
        }
    }

    private static boolean isInterrupt(SQLException e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return m.contains("interrupted") || m.contains("cancel");
    }

    private static boolean isSessionSetCommand(String sql) {
        String t = sql.trim();
        return t.length() > 4 && t.regionMatches(true, 0, "SET ", 0, 4);
    }

    private static void writeSafely(ChannelHandlerContext ctx, Object msg) {
        if (ctx.channel().isOpen()) ctx.writeAndFlush(msg);
    }

    public void closeSessionResources(Session session) {
        Connection c = connections.remove(session);
        if (c != null) {
            try { c.close(); } catch (SQLException ignored) {}
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public static class QueryCancelledException extends Exception {
        QueryCancelledException() {}
    }

    /** Handle to a running query so CancelRequest can interrupt it. */
    public static class RunningQuery {
        private final Statement stmt;
        private final Channel channel;
        private volatile boolean cancelled;

        RunningQuery(Statement stmt, Channel channel) {
            this.stmt = stmt;
            this.channel = channel;
        }

        public void cancel() {
            cancelled = true;
            try { stmt.cancel(); } catch (SQLException ignored) {}
            synchronized (this) { notifyAll(); }
        }

        void throwIfCancelled() throws QueryCancelledException {
            if (cancelled) throw new QueryCancelledException();
        }

        void awaitWritableSignal() throws QueryCancelledException {
            synchronized (this) {
                try { wait(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new QueryCancelledException();
                }
            }
        }

        public void signalWritable() {
            synchronized (this) { notifyAll(); }
        }
    }
}
