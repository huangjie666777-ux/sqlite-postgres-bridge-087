package com.example.bridge;

import com.example.bridge.testsupport.TestServer;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end checks with the real PostgreSQL JDBC driver. */
class BridgeIntegrationTest {

    private static Connection connect(TestServer server) throws SQLException {
        return DriverManager.getConnection(server.jdbcUrl(), "sa", "");
    }

    private static List<Object[]> query(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) {
                Object[] row = new Object[n];
                for (int i = 1; i <= n; i++) {
                    String v = rs.getString(i);
                    row[i - 1] = rs.wasNull() ? null : v;
                }
                rows.add(row);
            }
            return rows;
        }
    }

    @Test
    void joinsAggregatesSubqueriesAndWithWork() throws Exception {
        try (TestServer server = TestServer.start(); Connection c = connect(server)) {
            List<Object[]> rows = query(c,
                    "SELECT p.name, COUNT(o.id) AS n, ROUND(SUM(o.qty*o.price),2) AS revenue "
                            + "FROM products p LEFT JOIN orders o ON o.product_id=p.id "
                            + "GROUP BY p.name ORDER BY revenue DESC");
            assertEquals(4, rows.size());
            assertEquals("键盘 Keyboard", rows.get(0)[0]);
            assertEquals("2", rows.get(0)[1]);
            assertEquals("238.5", rows.get(0)[2]);

            List<Object[]> sub = query(c,
                    "SELECT (SELECT count(*) FROM orders) AS total, (SELECT max(qty) FROM orders) AS maxqty");
            assertEquals("4", sub.get(0)[0]);
            assertEquals("10", sub.get(0)[1]);

            List<Object[]> with = query(c,
                    "WITH per AS (SELECT product_id, SUM(qty) q FROM orders GROUP BY product_id) "
                            + "SELECT sum(q) FROM per");
            assertEquals("14", with.get(0)[0]);
        }
    }

    @Test
    void columnsAreTextWithUtf8NamesNullEmptyAndBlobHex() throws Exception {
        try (TestServer server = TestServer.start(); Connection c = connect(server)) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, name, note FROM products ORDER BY id")) {
                assertEquals("id", rs.getMetaData().getColumnLabel(1));
                assertEquals("name", rs.getMetaData().getColumnLabel(2));
                assertEquals("note", rs.getMetaData().getColumnLabel(3));
                assertEquals(java.sql.Types.VARCHAR, rs.getMetaData().getColumnType(3));

                rs.next(); // id 1, NULL note
                assertEquals("1", rs.getString(1));
                assertNull(rs.getString(3));
                assertTrue(rs.wasNull());

                rs.next(); // id 2, empty note
                assertEquals("", rs.getString(3));
                assertFalse(rs.wasNull());

                rs.next(); // id 3 blob
                assertEquals("0a1bff", rs.getString(3));

                rs.next(); // id 4 utf8 name and utf8 bytes
                assertEquals("中文 name テスト", rs.getString(2));
                assertEquals("68c3a96c6c6f", rs.getString(3));
            }
        }
    }

    @Test
    void writesPragmasAndMultiStatementsAreRejectedAndConnectionRecovers() throws Exception {
        try (TestServer server = TestServer.start(); Connection c = connect(server)) {
            String[] bad = {
                    "INSERT INTO products VALUES (9,'hack')",
                    "UPDATE products SET name='x'",
                    "DELETE FROM products",
                    "PRAGMA table_info(products)",
                    "ATTACH DATABASE '/tmp/x.db' AS x",
                    "BEGIN",
                    "SELECT 1; DROP TABLE products"
            };
            for (String sql : bad) {
                try (Statement st = c.createStatement()) {
                    st.execute(sql);
                    fail("expected rejection for: " + sql);
                } catch (SQLException e) {
                    assertNotNull(e.getSQLState());
                    assertNotEquals("00000", e.getSQLState());
                }
            }
            List<Object[]> rows = query(c, "SELECT count(*) AS n FROM products");
            assertEquals("4", rows.get(0)[0]);
        }
    }

    @Test
    void emptyQueryIsAllowed() throws Exception {
        try (TestServer server = TestServer.start(); Connection c = connect(server);
             Statement st = c.createStatement()) {
            // EmptyQueryResponse: no result set and no update count.
            assertFalse(st.execute("   "));
            assertNull(st.getResultSet());
            assertEquals(0, st.getUpdateCount());
        }
    }

    @Test
    void preparedStatementIsTransparentlyExecutedAndConnectionWorks() throws Exception {
        try (TestServer server = TestServer.start(); Connection c = connect(server);
             PreparedStatement ps = c.prepareStatement("SELECT 2 AS two")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("2", rs.getString(1));
            }
            List<Object[]> rows = query(c, "SELECT 2 AS two");
            assertEquals("2", rows.get(0)[0]);
        }
    }

    @Test
    void sslIsRefused() throws Exception {
        try (TestServer server = TestServer.start()) {
            String require = "jdbc:postgresql://127.0.0.1:" + server.port() + "/main?sslmode=require";
            assertThrows(SQLException.class, () -> DriverManager.getConnection(require, "sa", ""));
            try (Connection c = connect(server)) {
                assertEquals("1", query(c, "SELECT 1").get(0)[0]);
            }
        }
    }

    @Test
    void sourceFileIsNotModified() throws Exception {
        TestServer server = TestServer.start();
        byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(server.dbFile()));
        try (Connection c = connect(server)) {
            query(c, "SELECT * FROM products p JOIN orders o ON o.product_id = p.id GROUP BY p.id, p.name, p.note, o.id ORDER BY p.id");
            query(c, "WITH RECURSIVE r(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM r WHERE x<500) SELECT sum(x) FROM r");
            for (String bad : new String[]{"INSERT INTO products VALUES(1,'x')", "PRAGMA journal_mode",
                    "UPDATE products SET name='z'", "SELECT 1; SELECT 2"}) {
                try (Statement st = c.createStatement()) {
                    st.execute(bad);
                } catch (SQLException ignored) {
                    // expected
                }
            }
        }
        byte[] after = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(server.dbFile()));
        server.close();
        assertArrayEquals(before, after, "SQLite file must be byte-identical after reads/rejected writes");
    }

    @Test
    void cancelInterruptsLongQueryAndConnectionSurvives() throws Exception {
        try (TestServer server = TestServer.start(); Connection c = connect(server)) {
            Statement heavy = c.createStatement();
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(200);
                    heavy.cancel();
                } catch (Exception ignored) {
                    // best effort
                }
            });
            canceller.start();
            long t0 = System.currentTimeMillis();
            try (ResultSet rs = heavy.executeQuery(
                    "WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM cnt WHERE x < 50000000) "
                            + "SELECT count(*) FROM cnt a, cnt b WHERE b.x < 2000000")) {
                rs.next();
                fail("query should have been cancelled, got " + rs.getLong(1));
            } catch (SQLException e) {
                assertEquals("57014", e.getSQLState());
            }
            // pgjdbc first signals the OS process and only then opens the cancel
            // connection; the protocol-level latency is tested in CancelRequestTest.
            assertTrue(System.currentTimeMillis() - t0 < 20000, "cancel should return");
            // Same connection remains usable.
            assertEquals("7", query(c, "SELECT 3+4 AS x").get(0)[0]);
        }
    }

    @Test
    void cancellingOneConnectionDoesNotAffectAnother() throws Exception {
        try (TestServer server = TestServer.start();
             Connection victim = connect(server);
             Connection other = connect(server)) {
            Statement heavy = victim.createStatement();
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(200);
                    heavy.cancel();
                } catch (Exception ignored) {
                    // best effort
                }
            });
            canceller.start();
            try (ResultSet rs = heavy.executeQuery(
                    "WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM cnt WHERE x < 50000000) "
                            + "SELECT count(*) FROM cnt a, cnt b WHERE b.x < 2000000")) {
                rs.next();
                fail("expected cancellation");
            } catch (SQLException e) {
                assertEquals("57014", e.getSQLState());
            }
            assertEquals("4", query(other, "SELECT count(*) FROM products").get(0)[0]);
            assertEquals("4", query(victim, "SELECT count(*) FROM products").get(0)[0]);
        }
    }

    @Test
    void queriesOnOneConnectionRunSerially() throws Exception {
        try (TestServer server = TestServer.start(); Connection c = connect(server)) {
            List<Thread> threads = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                threads.add(new Thread(() -> {
                    try {
                        assertEquals("4", query(c, "SELECT count(*) FROM products").get(0)[0]);
                    } catch (Throwable t) {
                        synchronized (failures) {
                            failures.add(t);
                        }
                    }
                }));
            }
            threads.forEach(Thread::start);
            for (Thread t : threads) {
                t.join(5000);
            }
            assertTrue(failures.isEmpty(), () -> failures.toString());
        }
    }
}
