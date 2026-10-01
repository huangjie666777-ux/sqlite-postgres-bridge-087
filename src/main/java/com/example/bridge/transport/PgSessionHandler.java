package com.example.bridge.transport;

import com.example.bridge.protocol.FrontendMessage;
import com.example.bridge.protocol.PgFrameDecoder;
import com.example.bridge.protocol.PgProtocol;
import com.example.bridge.session.BackendKey;
import com.example.bridge.session.CancelToken;
import com.example.bridge.session.ClientSession;
import com.example.bridge.session.SessionRegistry;
import com.example.bridge.sql.QueryCursor;
import com.example.bridge.sql.QueryExecutor;
import com.example.bridge.sql.SqlGuard;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Drives one PostgreSQL client through startup and serial simple-query execution. */
public final class PgSessionHandler extends SimpleChannelInboundHandler<FrontendMessage> {

    private static final int MAX_PENDING_QUERIES = 32;

    private final SessionRegistry registry;
    private final SqlGuard guard = new SqlGuard();

    private ClientSession session;
    private ExecutorService worker;
    private int pendingQueries;
    private boolean terminated;

    private final Object writabilityLock = new Object();
    private CountDownLatch writabilityGate;

    public PgSessionHandler(SessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.channel().config().setWriteBufferWaterMark(
                new io.netty.channel.WriteBufferWaterMark(64 * 1024, 256 * 1024));
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sqlite-query-" + ctx.channel().id().asShortText());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FrontendMessage msg) {
        if (msg instanceof FrontendMessage.SslProbe) {
            ctx.writeAndFlush(PgProtocol.sslReply(false));
        } else if (msg instanceof FrontendMessage.Startup startup) {
            handleStartup(ctx, startup);
        } else if (msg instanceof FrontendMessage.Cancel cancel) {
            handleCancel(ctx, cancel);
        } else if (msg instanceof FrontendMessage.Terminate) {
            handleTerminate(ctx);
        } else if (msg instanceof FrontendMessage.ProtocolViolation violation) {
            ctx.writeAndFlush(PgProtocol.errorResponse("08P01", violation.detail()));
            ctx.close();
        } else if (msg instanceof FrontendMessage.Unsupported unsupported) {
            ctx.writeAndFlush(PgProtocol.errorResponse("0A000",
                    "extended protocol is not supported (" + unsupported.type() + "); use simple Query"));
            ctx.writeAndFlush(PgProtocol.readyForQuery('I'));
        } else if (msg instanceof FrontendMessage.Query query) {
            handleQuery(ctx, query.sql());
        }
    }

    private void handleStartup(ChannelHandlerContext ctx, FrontendMessage.Startup startup) {
        // Every real client speaks 3.0; version enforcement happens in the decoder.
        if (session != null) {
            ctx.writeAndFlush(PgProtocol.errorResponse("08P01", "duplicate startup packet"));
            ctx.close();
            return;
        }
        try {
            session = registry.create();
        } catch (SQLException e) {
            ctx.writeAndFlush(PgProtocol.errorResponse("58000",
                    "cannot open SQLite database: " + e.getMessage()));
            ctx.close();
            return;
        }
        PgFrameDecoder decoder = ctx.pipeline().get(PgFrameDecoder.class);
        if (decoder != null) {
            decoder.markStartupReceived();
        }
        BackendKey key = session.key();
        ctx.writeAndFlush(PgProtocol.authenticationOk());
        for (Map.Entry<String, String> p : PgProtocol.defaultParameters().entrySet()) {
            ctx.write(PgProtocol.parameterStatus(p.getKey(), p.getValue()));
        }
        ctx.write(PgProtocol.backendKeyData(key.pid(), key.secret()));
        ctx.writeAndFlush(PgProtocol.readyForQuery('I'));
    }

    private void handleCancel(ChannelHandlerContext ctx, FrontendMessage.Cancel cancel) {
        // CancelRequests arrive on a fresh connection carrying no session; check the
        // pid/secret, deliver the interrupt, and close this connection without replying.
        registry.cancel(cancel.pid(), cancel.secret());
        ctx.close();
    }

    private void handleTerminate(ChannelHandlerContext ctx) {
        terminated = true;
        ctx.close();
    }

    private void handleQuery(ChannelHandlerContext ctx, String sql) {
        if (session == null) {
            ctx.writeAndFlush(PgProtocol.errorResponse("08P01", "startup packet expected"));
            ctx.close();
            return;
        }
        if (pendingQueries >= MAX_PENDING_QUERIES) {
            ctx.writeAndFlush(PgProtocol.errorResponse("53300", "too many queued queries; drain pending results"));
            ctx.writeAndFlush(PgProtocol.readyForQuery('I'));
            return;
        }
        pendingQueries++;
        ctx.channel().config().setAutoRead(pendingQueries < MAX_PENDING_QUERIES);
        final ClientSession current = session;
        worker.execute(() -> runQuery(ctx, current, sql));
    }

    private void runQuery(ChannelHandlerContext ctx, ClientSession currentSession, String rawSql) {
        try {
            SqlGuard.Decision decision = guard.inspect(rawSql);
            if (decision instanceof SqlGuard.Empty) {
                ctx.writeAndFlush(PgProtocol.emptyQueryResponse());
            } else if (decision instanceof SqlGuard.SessionNoOp noOp) {
                ctx.writeAndFlush(PgProtocol.commandComplete(noOp.tag()));
            } else if (decision instanceof SqlGuard.Rejected rejected) {
                ctx.writeAndFlush(PgProtocol.errorResponse("42000", rejected.reason()));
            } else if (decision instanceof SqlGuard.Allowed allowed) {
                executeAndStream(ctx, currentSession, allowed.sql());
            }
        } catch (QueryCancelled e) {
            ctx.writeAndFlush(PgProtocol.errorResponse("57014", "canceling statement due to user request"));
        } catch (SQLException e) {
            ctx.writeAndFlush(PgProtocol.errorResponse(sqlState(e), e.getMessage() == null ? "SQLite error" : e.getMessage()));
        } catch (IOException e) {
            // client went away mid-stream; nothing to deliver
        } catch (Exception e) {
            ctx.writeAndFlush(PgProtocol.errorResponse("58000", String.valueOf(e.getMessage())));
        } finally {
            ctx.writeAndFlush(PgProtocol.readyForQuery('I'));
            ctx.executor().execute(() -> {
                pendingQueries--;
                if (pendingQueries < MAX_PENDING_QUERIES) {
                    ctx.channel().config().setAutoRead(true);
                }
            });
        }
    }

    private void executeAndStream(ChannelHandlerContext ctx, ClientSession currentSession, String sql)
            throws QueryCancelled, SQLException, IOException, InterruptedException {
        CancelToken cancellable = new CancelToken(currentSession.key().secret(), () -> { });
        currentSession.setActiveQuery(cancellable);
        QueryExecutor executor = new QueryExecutor(currentSession.sqliteConnection(), currentSession::beginQuery);
        try (QueryCursor cursor = executor.execute(sql)) {
            try {
                long rows = new QueryStreamer(ctx.channel(), this).stream(cursor);
                ctx.writeAndFlush(PgProtocol.commandComplete("SELECT " + rows));
            } finally {
                currentSession.endQuery();
                currentSession.clearActiveQuery(cancellable);
            }
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("interrupt") || message.contains("cancel")
                    || message.contains("terminated") || "57014".equals(e.getSQLState())) {
                throw new QueryCancelled();
            }
            throw e;
        }
    }

    private static String sqlState(SQLException e) {
        if ("57014".equals(e.getSQLState())) {
            return "57014";
        }
        return e.getSQLState() != null ? e.getSQLState() : "58000";
    }

    /** Blocks a per-connection query worker while the outbound buffer is full. */
    void awaitWritable() throws IOException, InterruptedException {
        ChannelHandlerContext ctx = pendingContext;
        if (ctx.channel().isWritable()) {
            return;
        }
        CountDownLatch gate;
        synchronized (writabilityLock) {
            if (ctx.channel().isWritable()) {
                return;
            }
            gate = new CountDownLatch(1);
            writabilityGate = gate;
        }
        boolean opened = gate.await(30, TimeUnit.SECONDS);
        synchronized (writabilityLock) {
            writabilityGate = null;
        }
        if (!opened || !ctx.channel().isActive()) {
            throw new IOException("client not reading");
        }
    }

    private volatile ChannelHandlerContext pendingContext;

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        super.channelWritabilityChanged(ctx);
        if (ctx.channel().isWritable()) {
            CountDownLatch gate;
            synchronized (writabilityLock) {
                gate = writabilityGate;
            }
            if (gate != null) {
                gate.countDown();
            }
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        pendingContext = ctx;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        CountDownLatch gate;
        synchronized (writabilityLock) {
            gate = writabilityGate;
        }
        if (gate != null) {
            gate.countDown();
        }
        ClientSession s = session;
        session = null;
        if (s != null) {
            registry.dispose(s);
        }
        if (worker != null) {
            worker.shutdownNow();
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!terminated) {
            ctx.writeAndFlush(PgProtocol.errorResponse("08P01", String.valueOf(cause.getMessage())));
        }
        ctx.close();
    }
}

/** Internal marker for cancellation detected after statement.cancel interrupts the cursor. */
class QueryCancelled extends RuntimeException {
}
