package com.example.bridge;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * Real PostgreSQL client (pgjdbc) demo: normal query, error recovery and
 * query cancellation through the bridge.
 */
public final class DemoClient {
    private DemoClient() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args.length > 0 ? args[0] : "5544");
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/example";
        Properties props = new Properties();
        props.setProperty("user", "demo");
        props.setProperty("password", "");
        props.setProperty("preferQueryMode", "simple");

        try (Connection conn = DriverManager.getConnection(url, props);
             Statement st = conn.createStatement()) {

            System.out.println("== 1. join + aggregate ==");
            try (ResultSet rs = st.executeQuery(
                    "SELECT u.name, count(o.id) AS cnt, sum(o.amount) AS total"
                            + " FROM users u LEFT JOIN orders o ON o.user_id = u.id"
                            + " GROUP BY u.name ORDER BY u.name")) {
                while (rs.next()) {
                    System.out.println(rs.getString(1) + " | " + rs.getString(2) + " | " + rs.getString(3));
                }
            }

            System.out.println("== 2. blob / null / utf8 ==");
            try (ResultSet rs = st.executeQuery("SELECT name, note, avatar FROM users ORDER BY id")) {
                while (rs.next()) {
                    System.out.println(rs.getString(1) + " | note=" + rs.getString(2)
                            + (rs.wasNull() ? "(NULL)" : "") + " | avatar=" + rs.getString(3));
                }
            }

            System.out.println("== 3. rejected write, connection stays usable ==");
            try {
                st.executeQuery("DELETE FROM users");
            } catch (Exception e) {
                System.out.println("rejected as expected: " + e.getMessage().split("\n")[0]);
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM users")) {
                rs.next();
                System.out.println("still works, users=" + rs.getInt(1));
            }

            System.out.println("== 4. cancel a long query ==");
            Statement slow = conn.createStatement();
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(300);
                    slow.cancel(); // pgjdbc sends a CancelRequest on a new connection
                } catch (Exception ignored) {}
            });
            canceller.start();
            long t0 = System.currentTimeMillis();
            try {
                slow.executeQuery("WITH RECURSIVE cnt(x) AS ("
                        + "SELECT 1 UNION ALL SELECT x+1 FROM cnt LIMIT 200000000)"
                        + " SELECT sum(x) FROM cnt");
                System.out.println("query finished (unexpected)");
            } catch (Exception e) {
                System.out.println("cancelled after " + (System.currentTimeMillis() - t0) + "ms: "
                        + e.getMessage().split("\n")[0]);
            }
            canceller.join();
            try (ResultSet rs = st.executeQuery("SELECT 'connection still usable'")) {
                rs.next();
                System.out.println(rs.getString(1));
            }
        }
    }
}
