package com.example.bridge.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard();

    @Test
    void allowsReadOnlySelects() {
        String[] ok = {
                "SELECT 1",
                "select * from products p join orders o on o.product_id = p.id",
                "WITH t AS (SELECT max(price) m FROM orders) SELECT * FROM t",
                "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<5) SELECT * FROM c",
                "SELECT (SELECT count(*) FROM orders) AS n",
                "SELECT name FROM products WHERE name = 'INSERT literal; DROP TABLE x; --'",
                "  -- leading comment\n SELECT 1 AS a -- trailing\n",
                "SELECT ';' AS s",
                "SELECT 1;"
        };
        for (String sql : ok) {
            assertInstanceOf(SqlGuard.Allowed.class, guard.inspect(sql), () -> "should allow: " + sql);
        }
    }

    @Test
    void emptyInputIsEmpty() {
        assertInstanceOf(SqlGuard.Empty.class, guard.inspect(""));
        assertInstanceOf(SqlGuard.Empty.class, guard.inspect("   "));
        assertInstanceOf(SqlGuard.Empty.class, guard.inspect("-- just a comment\n"));
    }

    @Test
    void rejectsWritesAndDdlAndPragmas() {
        String[] bad = {
                "INSERT INTO products VALUES (9,'x')",
                "UPDATE products SET name='x' WHERE id=1",
                "DELETE FROM products",
                "CREATE TABLE x(a int)",
                "DROP TABLE products",
                "ALTER TABLE products ADD COLUMN z",
                "PRAGMA table_info(products)",
                "ATTACH DATABASE 'other.db' AS o",
                "BEGIN; SELECT 1",
                "COMMIT",
                "ROLLBACK",
                "REPLACE INTO products VALUES(1,'x')"
        };
        for (String sql : bad) {
            assertInstanceOf(SqlGuard.Rejected.class, guard.inspect(sql), () -> "should reject: " + sql);
        }
    }

    @Test
    void rejectsMultipleStatements() {
        String[] bad = {
                "SELECT 1; DROP TABLE products",
                "SELECT 1; SELECT 2",
                "SELECT 1; -- comment\n DELETE FROM products",
                "WITH t AS (SELECT 1) SELECT * FROM t; ATTACH 'x' AS y"
        };
        for (String sql : bad) {
            assertInstanceOf(SqlGuard.Rejected.class, guard.inspect(sql), () -> "should reject: " + sql);
        }
    }

    @Test
    void rejectsUnterminatedAndMalformedInput() {
        assertInstanceOf(SqlGuard.Rejected.class, guard.inspect("SELECT 'unterminated"));
        assertInstanceOf(SqlGuard.Rejected.class, guard.inspect("SELECT FROM WHERE GARBAGE ("));
    }
}
