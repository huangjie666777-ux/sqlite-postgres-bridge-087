package com.example.bridge.demo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Real PostgreSQL JDBC client demo: connects (SSL disabled, no password), runs a join with
 * aggregation, exercises error recovery, and issues a CancelRequest against a heavy query.
 */
public final class DemoClient {

    private DemoClient() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 55432;
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/main?sslmode=disable&preferQueryMode=simple";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            runQuery(c, "SELECT p.name, COUNT(o.id) AS orders, ROUND(SUM(o.qty * o.price), 2) AS revenue "
                    + "FROM products p LEFT JOIN orders o ON o.product_id = p.id "
                    + "GROUP BY p.name ORDER BY revenue DESC");
            runQuery(c, "SELECT name, note FROM products WHERE id IN (1,2,3,4) ORDER BY id");

            System.out.println("\n-- error recovery --");
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM products");
            } catch (SQLException e) {
                System.out.println("rejected write -> SQLState " + e.getSQLState() + ": " + e.getMessage());
            }
            runQuery(c, "SELECT 'still usable after error' AS status");

            System.out.println("\n-- cancellation --");
            try (Statement st = c.createStatement()) {
                Thread canceller = new Thread(() -> {
                    try {
                        Thread.sleep(300);
                        st.cancel();
                    } catch (Exception ignored) {
                        // best effort
                    }
                });
                canceller.start();
                st.execute("WITH RECURSIVE cnt(x) AS ("
                        + "SELECT 1 UNION ALL SELECT x + 1 FROM cnt WHERE x < 50000000) "
                        + "SELECT count(*) FROM cnt a, cnt b WHERE b.x < 2000000");
                System.out.println("ERROR: long query was not cancelled");
            } catch (SQLException e) {
                System.out.println("cancelled -> SQLState " + e.getSQLState() + ": " + e.getMessage());
            }
            runQuery(c, "SELECT 'connection alive after cancel' AS status");
        }
    }

    private static void runQuery(Connection c, String sql) throws SQLException {
        System.out.println("\n> " + sql);
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int n = meta.getColumnCount();
            StringBuilder header = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                header.append(meta.getColumnLabel(i)).append(i == n ? "" : " | ");
            }
            System.out.println(header);
            int rows = 0;
            while (rs.next()) {
                StringBuilder line = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    String v = rs.getString(i);
                    line.append(rs.wasNull() ? "<NULL>" : (v.isEmpty() ? "<EMPTY>" : v));
                    if (i < n) {
                        line.append(" | ");
                    }
                }
                System.out.println(line);
                rows++;
            }
            System.out.println("(" + rows + " row" + (rows == 1 ? ")" : "s)"));
        }
    }
}
