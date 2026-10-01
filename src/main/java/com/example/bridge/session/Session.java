package com.example.bridge.session;

import com.example.bridge.protocol.PgMessages;
import com.example.bridge.sql.QueryEngine;
import io.netty.channel.ChannelHandlerContext;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-connection state: identity, backend key, JDBC handle, query queue. */
public class Session {
    private static final AtomicInteger PID_SOURCE = new AtomicInteger(1000);

    private final int pid = PID_SOURCE.getAndIncrement();
    private final int secret = new SecureRandom().nextInt();
    private final SessionRegistry registry;
    private final QueryEngine engine;

    private ChannelHandlerContext ctx;
    private final Queue<String> pendingQueries = new ArrayDeque<>();
    private boolean queryRunning;
    private volatile QueryEngine.RunningQuery currentQuery;

    public Session(SessionRegistry registry, QueryEngine engine) {
        this.registry = registry;
        this.engine = engine;
    }

    public int getPid() { return pid; }
    public int getSecret() { return secret; }
    public SessionRegistry getRegistry() { return registry; }

    public void onStartupComplete(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        registry.register(this);
        ctx.write(PgMessages.authenticationOk(ctx));
        ctx.write(PgMessages.parameterStatus(ctx, "server_version", "15.0"));
        ctx.write(PgMessages.parameterStatus(ctx, "server_encoding", "UTF8"));
        ctx.write(PgMessages.parameterStatus(ctx, "client_encoding", "UTF8"));
        ctx.write(PgMessages.parameterStatus(ctx, "integer_datetimes", "on"));
        ctx.write(PgMessages.backendKeyData(ctx, pid, secret));
        ctx.writeAndFlush(PgMessages.readyForQuery(ctx));
    }

    /** Simple-protocol queries on one connection run strictly in order. */
    public synchronized void submitQuery(String sql) {
        pendingQueries.add(sql);
        if (!queryRunning) {
            queryRunning = true;
            engine.execute(this, ctx, pendingQueries.poll());
        }
    }

    /** Called by the engine when a query finished (rows sent or error sent). */
    public synchronized void queryFinished() {
        currentQuery = null;
        // Every Query message gets its own ReadyForQuery, even when pipelined.
        ctx.writeAndFlush(PgMessages.readyForQuery(ctx));
        String next = pendingQueries.poll();
        if (next != null) {
            engine.execute(this, ctx, next);
        } else {
            queryRunning = false;
        }
    }

    public void setCurrentQuery(QueryEngine.RunningQuery q) {
        this.currentQuery = q;
    }

    public void cancelCurrentQuery() {
        QueryEngine.RunningQuery q = currentQuery;
        if (q != null) q.cancel();
    }

    /** Wake the streaming worker when the channel becomes writable again. */
    public void signalWritable() {
        QueryEngine.RunningQuery q = currentQuery;
        if (q != null) q.signalWritable();
    }

    public void close() {
        registry.unregister(this);
        engine.closeSessionResources(this);
    }
}
