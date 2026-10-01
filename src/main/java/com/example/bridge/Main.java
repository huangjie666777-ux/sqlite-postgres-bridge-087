package com.example.bridge;

import java.io.File;

/** Usage: java com.example.bridge.Main <sqlite-file> <port> */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: sqlite-postgres-bridge <sqlite-file> <port>");
            System.exit(2);
        }
        File dbFile = new File(args[0]);
        if (!dbFile.isFile()) {
            System.err.println("SQLite file not found: " + dbFile.getAbsolutePath());
            System.exit(2);
        }
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid port: " + args[1]);
            System.exit(2);
            return;
        }

        BridgeServer server = new BridgeServer(dbFile.getAbsolutePath(), port);
        int bound = server.start();
        System.out.println("SQLite-PostgreSQL read-only bridge listening on 127.0.0.1:" + bound);
        System.out.println("Database: " + dbFile.getAbsolutePath());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.close(), "bridge-shutdown"));
        Thread.currentThread().join();
    }
}
