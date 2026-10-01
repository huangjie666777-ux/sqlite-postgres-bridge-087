package com.example.bridge.sql;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;

/**
 * Validates SQL <em>before</em> any execution: exactly one statement and it
 * must be a SELECT (WITH ... SELECT included). Writes, PRAGMA, ATTACH,
 * transaction control and multi-statements are rejected up front.
 */
public final class SqlGuard {
    private SqlGuard() {}

    public static void validate(String sql) throws SqlRejectedException {
        String trimmed = sql == null ? "" : sql.trim();
        if (trimmed.isEmpty()) return; // empty query handled by caller
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(trimmed);
        } catch (Exception e) {
            throw new SqlRejectedException("only a single SELECT/WITH query is allowed: " + e.getMessage());
        }
        if (statements.getStatements().size() != 1) {
            throw new SqlRejectedException("multiple statements are not allowed; only a single SELECT/WITH query is permitted");
        }
        Statement st = statements.getStatements().get(0);
        if (!(st instanceof Select)) {
            throw new SqlRejectedException("only SELECT/WITH queries are allowed, got: " + st.getClass().getSimpleName());
        }
    }

    public static class SqlRejectedException extends Exception {
        public SqlRejectedException(String message) { super(message); }
    }
}
