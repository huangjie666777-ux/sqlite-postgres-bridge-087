package com.example.bridge;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

abstract class BridgeTestBase {
    static BridgeServer server;
    static Path dbFile;

    @BeforeAll
    static void startServer() throws Exception {
        dbFile = Path.of(System.getProperty("java.io.tmpdir"),
                "bridge-test-" + System.nanoTime() + ".db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT, note TEXT, avatar BLOB)");
            s.execute("CREATE TABLE orders(id INTEGER PRIMARY KEY, user_id INTEGER, amount REAL)");
            s.execute("INSERT INTO users(name, note, avatar) VALUES"
                    + "('Alice', '', x'00ff10'),('张三', NULL, x'cafe'),('Bob', 'x', NULL)");
            s.execute("INSERT INTO orders(user_id, amount) VALUES (1, 9.99),(1, 3.5),(2, 100.0)");
        }
        server = new BridgeServer(dbFile.toString(), 0);
        server.start();
    }

    @AfterAll
    static void stopServer() throws Exception {
        server.close();
        java.nio.file.Files.deleteIfExists(dbFile);
    }

    static String pgUrl() {
        return "jdbc:postgresql://127.0.0.1:" + server.getPort() + "/test";
    }

    static java.util.Properties pgProps() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("user", "tester");
        p.setProperty("preferQueryMode", "simple");
        return p;
    }
}

