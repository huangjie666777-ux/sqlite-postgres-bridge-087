package com.example.bridge.session;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns all client sessions and the independent read-only SQLite connections behind them. */
public final class SessionRegistry implements AutoCloseable {

    private final String jdbcUrl;
    private final AtomicInteger pidSequence = new AtomicInteger(1);
    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, ClientSession> sessions = new ConcurrentHashMap<>();

    public SessionRegistry(String sqliteFilePath) {
        this.jdbcUrl = "jdbc:sqlite:" + sqliteFilePath + "?open_mode=1";
    }

    /** Opens a fresh session with an independent read-only SQLite connection. */
    public ClientSession create() throws SQLException {
        int pid = pidSequence.getAndIncrement();
        int secret = random.nextInt();
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try {
            // Defense in depth: even a guard bug cannot turn into a write through this link.
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA query_only = ON");
            }
            ClientSession session = new ClientSession(new BackendKey(pid, secret), connection);
            sessions.put(pid, session);
            return session;
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }

    /** Deliver a CancelRequest; returns false if pid/secret do not match any live query. */
    public boolean cancel(int pid, int secret) {
        ClientSession session = sessions.get(pid);
        return session != null && session.requestCancel(secret);
    }

    public void dispose(ClientSession session) {
        if (session != null) {
            sessions.remove(session.key().pid());
            session.close();
        }
    }

    @Override
    public void close() {
        sessions.values().forEach(ClientSession::close);
        sessions.clear();
    }
}
