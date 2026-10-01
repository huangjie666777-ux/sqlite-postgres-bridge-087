package com.example.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class QueryTest extends BridgeTestBase {

    @Test
    void joinAggregateSubquery() throws Exception {
        try (Connection c = DriverManager.getConnection(pgUrl(), pgProps());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT u.name, t.total FROM users u JOIN "
                             + "(SELECT user_id, sum(amount) AS total FROM orders GROUP BY user_id) t "
                             + "ON t.user_id = u.id ORDER BY u.name")) {
            assertTrue(rs.next());
            assertEquals("Alice", rs.getString(1));
            assertEquals("13.49", rs.getString(2));
            assertTrue(rs.next());
            assertEquals("张三", rs.getString(1));
            assertFalse(rs.next());
        }
    }

    @Test
    void withQueryAndColumnNames() throws Exception {
        try (Connection c = DriverManager.getConnection(pgUrl(), pgProps());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "WITH big AS (SELECT * FROM orders WHERE amount > 5) SELECT count(*) AS n FROM big")) {
            rs.next();
            assertEquals(2, rs.getInt("n"));
            assertEquals("n", rs.getMetaData().getColumnLabel(1));
        }
    }

    @Test
    void blobHexNullVsEmptyUtf8() throws Exception {
        try (Connection c = DriverManager.getConnection(pgUrl(), pgProps());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, note, avatar FROM users ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("", rs.getString(2));
            assertFalse(rs.wasNull());
            assertEquals("00ff10", rs.getString(3));
            assertTrue(rs.next());
            assertEquals("张三", rs.getString(1));
            assertNull(rs.getString(2));
            assertTrue(rs.wasNull());
            assertEquals("cafe", rs.getString(3));
            assertTrue(rs.next());
            assertNull(rs.getString(3));
        }
    }

    @Test
    void rejectsWritesPragmaAttachTransactionsMultiStatement() throws Exception {
        try (Connection c = DriverManager.getConnection(pgUrl(), pgProps());
             Statement st = c.createStatement()) {
            for (String sql : new String[]{
                    "DELETE FROM users", "INSERT INTO users(name) VALUES('x')",
                    "UPDATE users SET name='y'", "DROP TABLE users",
                    "PRAGMA journal_mode=WAL", "ATTACH DATABASE 'x.db' AS x",
                    "BEGIN", "COMMIT", "VACUUM",
                    "SELECT 1; SELECT 2"}) {
                SQLException e = assertThrows(SQLException.class, () -> st.executeQuery(sql), sql);
                assertTrue(e.getMessage().contains("SELECT"), sql + " -> " + e.getMessage());
            }
            // connection still usable after errors
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM users")) {
                rs.next();
                assertEquals(3, rs.getInt(1));
            }
        }
    }

    @Test
    void sourceFileNotModified() throws Exception {
        long sizeBefore = java.nio.file.Files.size(dbFile);
        try (Connection c = DriverManager.getConnection(pgUrl(), pgProps());
             Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.executeQuery("CREATE TABLE t(x)"));
            try (ResultSet rs = st.executeQuery("SELECT * FROM users")) {
                while (rs.next()) { }
            }
        }
        assertEquals(sizeBefore, java.nio.file.Files.size(dbFile));
    }

    @Test
    void sqlErrorThenRecovery() throws Exception {
        try (Connection c = DriverManager.getConnection(pgUrl(), pgProps());
             Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.executeQuery("SELECT * FROM no_such_table"));
            try (ResultSet rs = st.executeQuery("SELECT 1 + 1")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void emptyQuery() throws Exception {
        try (Connection c = DriverManager.getConnection(pgUrl(), pgProps());
             Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.executeQuery("   "));
            try (ResultSet rs = st.executeQuery("SELECT 42")) {
                rs.next();
                assertEquals(42, rs.getInt(1));
            }
        }
    }

    @Test
    void independentClientsAndSequentialQueries() throws Exception {
        try (Connection c1 = DriverManager.getConnection(pgUrl(), pgProps());
             Connection c2 = DriverManager.getConnection(pgUrl(), pgProps());
             Statement s1 = c1.createStatement();
             Statement s2 = c2.createStatement()) {
            try (ResultSet rs = s1.executeQuery("SELECT 'c1-' || x FROM (SELECT 1 AS x)")) {
                rs.next();
                assertEquals("c1-1", rs.getString(1));
            }
            try (ResultSet rs = s2.executeQuery("SELECT 'c2'")) {
                rs.next();
                assertEquals("c2", rs.getString(1));
            }
            // sequential on same connection
            for (int i = 0; i < 5; i++) {
                try (ResultSet rs = s1.executeQuery("SELECT " + i)) {
                    rs.next();
                    assertEquals(i, rs.getInt(1));
                }
            }
        }
    }

    @Test
    void cancelInterruptsQueryAndConnectionSurvives() throws Exception {
        try (Connection c = DriverManager.getConnection(pgUrl(), pgProps());
             Statement st = c.createStatement()) {
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(300);
                    st.cancel();
                } catch (Exception ignored) {}
            });
            canceller.start();
            long t0 = System.currentTimeMillis();
            assertThrows(SQLException.class, () -> st.executeQuery(
                    "WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM cnt LIMIT 200000000)"
                            + " SELECT sum(x) FROM cnt"));
            assertTrue(System.currentTimeMillis() - t0 < 30_000, "cancel took too long");
            canceller.join();
            try (ResultSet rs = st.executeQuery("SELECT 'alive'")) {
                rs.next();
                assertEquals("alive", rs.getString(1));
            }
        }
    }
}

