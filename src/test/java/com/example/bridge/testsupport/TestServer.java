package com.example.bridge.testsupport;

import com.example.bridge.BridgeServer;
import com.example.bridge.demo.SampleDatabase;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

/** Starts a bridge over an ephemeral port backed by a freshly generated demo database. */
public final class TestServer implements AutoCloseable {

    private final Path dbFile;
    private final BridgeServer server;
    private final int port;

    private TestServer(Path dbFile, BridgeServer server, int port) {
        this.dbFile = dbFile;
        this.server = server;
        this.port = port;
    }

    public static TestServer start() throws Exception {
        Path db = Files.createTempFile("bridge", ".db");
        Files.delete(db);
        SampleDatabase.create(db.toString());
        int port = freePort();
        BridgeServer server = new BridgeServer(db.toString(), port);
        server.start();
        return new TestServer(db, server, port);
    }

    public static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    public int port() {
        return port;
    }

    public Path dbFile() {
        return dbFile;
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://127.0.0.1:" + port
                + "/main?sslmode=disable&preferQueryMode=simple";
    }

    @Override
    public void close() {
        server.close();
        try {
            Files.deleteIfExists(dbFile);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
