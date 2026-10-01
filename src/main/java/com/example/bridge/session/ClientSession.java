package com.example.bridge.session;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A single client connection: its own read-only SQLite connection, protocol key,
 * and the cancellation token of the query currently in flight (at most one per
 * connection because queries are executed serially).
 */
public final class ClientSession {

    private final BackendKey key;
    private final Connection sqliteConnection;
    private volatile CancelToken activeQuery;
    private volatile boolean closed;

    private final Object queryLock = new Object();
    private java.sql.Statement pendingStatement;
    private boolean cancelRequested;

    ClientSession(BackendKey key, Connection sqliteConnection) {
        this.key = key;
        this.sqliteConnection = sqliteConnection;
    }

    public BackendKey key() {
        return key;
    }

    public Connection sqliteConnection() {
        return sqliteConnection;
    }

    public void setActiveQuery(CancelToken token) {
        this.activeQuery = token;
    }

    public void clearActiveQuery(CancelToken expected) {
        if (activeQuery == expected) {
            activeQuery = null;
        }
    }

    /**
     * Installs the live statement before it starts executing. A cancel that arrives between
     * query start and statement creation is remembered and delivered immediately.
     */
    public void beginQuery(java.sql.Statement statement) {
        synchronized (queryLock) {
            this.pendingStatement = statement;
            if (cancelRequested) {
                try {
                    statement.cancel();
                } catch (SQLException ignored) {
                    // let the query run/finish; clear path handles the race
                }
            }
        }
    }

    public void endQuery() {
        synchronized (queryLock) {
            pendingStatement = null;
            cancelRequested = false;
        }
    }

    /**
     * Attempt to cancel the running query. Returns true only if the secret matches and
     * this call actually delivered the cancellation; wrong keys and already-finished
     * queries are no-ops.
     */
    public boolean requestCancel(int secret) {
        CancelToken token = activeQuery;
        if (token == null || !token.secretMatches(secret)) {
            return false;
        }
        synchronized (queryLock) {
            cancelRequested = true;
            if (pendingStatement != null) {
                try {
                    pendingStatement.cancel();
                } catch (SQLException ignored) {
                    // best effort; the interrupt may simply be too late
                }
            }
        }
        return true;
    }

    boolean isClosed() {
        return closed;
    }

    void close() {
        closed = true;
        activeQuery = null;
        try {
            sqliteConnection.close();
        } catch (Exception ignored) {
            // best effort
        }
    }
}
