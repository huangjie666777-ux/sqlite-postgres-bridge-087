package com.example.bridge.demo;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

/** Builds the small demo database: products, orders, aggregates, types (NULL/BLOB/UTF-8). */
public final class SampleDatabase {

    private SampleDatabase() {
    }

    public static void create(String path) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = c.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS orders");
            st.executeUpdate("DROP TABLE IF EXISTS products");
            st.executeUpdate("CREATE TABLE products (" 
                    + "id INTEGER PRIMARY KEY, name TEXT NOT NULL, note TEXT)");
            st.executeUpdate("CREATE TABLE orders (" 
                    + "id INTEGER PRIMARY KEY, product_id INTEGER, qty INTEGER, price REAL)");
            st.executeUpdate("INSERT INTO products (id, name, note) VALUES "
                    + "(1, '键盘 Keyboard', NULL),"
                    + "(2, '数据线 cable', ''),"
                    + "(3, 'BLOB 样例', x'0a1bff')");
            st.executeUpdate("INSERT INTO orders (id, product_id, qty, price) VALUES "
                    + "(1,1,2,79.5),(2,1,1,79.5),(3,2,10,3.25),(4,3,1,19.9)");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO products (id, name, note) VALUES (4, ?, ?)")) {
                ps.setString(1, "中文 name テスト");
                ps.setBytes(2, "héllo".getBytes(StandardCharsets.UTF_8));
                ps.executeUpdate();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "demo.db";
        create(path);
        System.out.println("Sample SQLite database written to: " + path);
    }
}
