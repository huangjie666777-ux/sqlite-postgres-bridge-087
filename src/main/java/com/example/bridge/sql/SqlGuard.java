package com.example.bridge.sql;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a single SQL string is a read-only SELECT/WITH query.
 *
 * <p>Everything is validated <em>before</em> the database ever sees the string:
 * a SQLite-aware lexer rejects multi-statement input and non-query leading keywords
 * (INSERT/UPDATE/DELETE/DDL/PRAGMA/ATTACH/transaction control), and JSqlParser then
 * confirms the statement (including every CTE) is a SELECT.
 */
public final class SqlGuard {

    public sealed interface Decision permits Allowed, Empty, SessionNoOp, Rejected {
    }

    public record Allowed(String sql) implements Decision {
    }

    public record Empty() implements Decision {
    }

    /** A harmless client-side session command (e.g. SET extra_float_digits); no-op on SQLite. */
    public record SessionNoOp(String tag) implements Decision {
    }

    public record Rejected(String reason) implements Decision {
    }

    private static final List<String> FORBIDDEN_LEADERS = List.of(
            "insert", "update", "delete", "replace", "merge", "upsert",
            "create", "drop", "alter", "truncate", "vacuum", "reindex",
            "pragma", "attach", "detach",
            "begin", "commit", "end", "rollback", "savepoint", "release",
            "grant", "revoke", "explain", "call");

    public Decision inspect(String rawSql) {
        List<String> tokens;
        try {
            tokens = lex(rawSql);
        } catch (IllegalArgumentException e) {
            return new Rejected(e.getMessage());
        }
        if (tokens.isEmpty()) {
            return new Empty();
        }

        int lastSemicolon = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).equals(";")) {
                lastSemicolon = i;
            }
        }
        if (lastSemicolon >= 0 && lastSemicolon < tokens.size() - 1) {
            return new Rejected("multiple statements are not allowed");
        }
        List<String> significant = new ArrayList<>();
        for (String t : tokens) {
            if (!t.equals(";")) {
                significant.add(t);
            }
        }
        if (significant.isEmpty()) {
            return new Empty();
        }

        String first = significant.get(0);
        if (first.equals("set") && isAllowedSessionSet(significant)) {
            return new SessionNoOp("SET");
        }
        if (first.equals("show") && isAllowedSessionShow(significant)) {
            return new SessionNoOp("SHOW");
        }
        if (!first.equals("select") && !first.equals("with")) {
            if (FORBIDDEN_LEADERS.contains(first)) {
                return new Rejected("only SELECT/WITH queries are allowed: " + first.toUpperCase(Locale.ROOT) + " is forbidden");
            }
            return new Rejected("only SELECT/WITH queries are allowed");
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(rawSql);
        } catch (JSQLParserException e) {
            return new Rejected("SQL parse error: " + rootMessage(e));
        }
        if (!(statement instanceof Select select) || !isReadSelect(select)) {
            return new Rejected("only read-only SELECT/WITH queries are allowed");
        }
        return new Allowed(rawSql);
    }

    private boolean isReadSelect(Select body) {
        if (body instanceof PlainSelect) {
            return true;
        }
        if (body instanceof SetOperationList set) {
            return set.getSelects().stream().allMatch(this::isReadSelect);
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? "syntax error" : msg.lines().findFirst().orElse("syntax error");
    }

    private static boolean isAllowedSessionSet(List<String> tokens) {
        int i = 1;
        if (i < tokens.size() && (tokens.get(i).equals("session") || tokens.get(i).equals("local"))) {
            i++;
        }
        if (i >= tokens.size()) {
            return false;
        }
        String name = tokens.get(i);
        return name.equals("extra_float_digits") || name.equals("application_name")
                || name.equals("client_encoding") || name.equals("timezone")
                || name.equals("statement_timeout") || name.equals("lock_timeout")
                || name.equals("idle_in_transaction_session_timeout") || name.equals("search_path");
    }

    private static boolean isAllowedSessionShow(List<String> tokens) {
        if (tokens.size() != 2) {
            return false;
        }
        String name = tokens.get(1);
        return name.equals("extra_float_digits") || name.equals("application_name")
                || name.equals("client_encoding") || name.equals("search_path")
                || name.equals("timezone") || name.equals("server_version")
                || name.equals("standard_conforming_strings") || name.equals("integer_datetimes");
    }

    /**
     * Minimal SQLite lexer. Returns significant tokens (lowercased unquoted words, ";").
     * Skips whitespace and comments, and treats string/blob/identifier contents as opaque.
     */
    static List<String> lex(String sql) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
            } else if (c == ';') {
                tokens.add(";");
                i++;
            } else if (c == '\'' || c == '"' || c == '`' || c == '[') {
                i = skipQuoted(sql, i, c);
                tokens.add("?");
            } else if (isWordStart(c)) {
                int start = i++;
                while (i < n && isWordPart(sql.charAt(i))) {
                    i++;
                }
                tokens.add(sql.substring(start, i).toLowerCase(Locale.ROOT));
            } else {
                // operators, numbers, punctuation: irrelevant for our checks.
                i++;
            }
        }
        return tokens;
    }

    private static int skipQuoted(String sql, int start, char open) {
        int i = start + 1;
        int n = sql.length();
        if (open == '[') {
            while (i < n && sql.charAt(i) != ']') {
                i++;
            }
            return Math.min(n, i + 1);
        }
        while (i < n) {
            char c = sql.charAt(i);
            if (c == open) {
                if (i + 1 < n && sql.charAt(i + 1) == open) {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        throw new IllegalArgumentException("unterminated quoted literal or identifier");
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
