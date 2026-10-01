package com.example.bridge.sql;

import java.util.function.Consumer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Opens streaming, read-only result cursors on a session's SQLite connection. */
public final class QueryExecutor {

    private final Connection connection;
    private final Consumer<Statement> statementHook;

    public QueryExecutor(Connection connection) {
        this(connection, s -> { });
    }

    public QueryExecutor(Connection connection, Consumer<Statement> statementHook) {
        this.connection = connection;
        this.statementHook = statementHook;
    }

    /** Must only be called after {@link SqlGuard} approved the SQL. */
    public QueryCursor execute(String sql) throws SQLException {
        Statement statement = connection.createStatement(
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        statement.setFetchSize(128);
        statementHook.accept(statement);
        boolean success = false;
        try {
            ResultSet rs = statement.executeQuery(sql);
            QueryCursor cursor = new QueryCursor(statement, rs);
            success = true;
            return cursor;
        } finally {
            if (!success) {
                try {
                    statement.close();
                } catch (SQLException ignored) {
                    // best effort
                }
            }
        }
    }
}
