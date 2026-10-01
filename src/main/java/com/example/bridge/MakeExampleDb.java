package com.example.bridge;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Creates example.db with demo data (run once before starting the bridge). */
public final class MakeExampleDb {
    private MakeExampleDb() {}

    public static void main(String[] args) throws Exception {
        String file = args.length > 0 ? args[0] : "example.db";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS users");
            s.execute("DROP TABLE IF EXISTS orders");
            s.execute("CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT, note TEXT, avatar BLOB)");
            s.execute("CREATE TABLE orders(id INTEGER PRIMARY KEY, user_id INTEGER, amount REAL, created TEXT)");
            s.execute("INSERT INTO users(name, note, avatar) VALUES"
                    + "('Alice', '', x'00ff10'),"
                    + "('张三', NULL, x'cafe'),"
                    + "('Bob', 'héllo wörld', NULL)");
            s.execute("INSERT INTO orders(user_id, amount, created) VALUES"
                    + "(1, 9.99, '2026-01-01'),(1, 3.5, '2026-01-02'),"
                    + "(2, 100.0, '2026-02-01'),(3, 0.0, '2026-03-01')");
        }
        System.out.println("created " + file);
    }
}

