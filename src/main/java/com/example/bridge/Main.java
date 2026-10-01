package com.example.bridge;

/** Entry point: java com.example.bridge.Main <sqlite-file> <port> */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: Main <sqlite-file> <port>");
            System.exit(2);
        }
        String file = args[0];
        int port = Integer.parseInt(args[1]);
        BridgeServer server = new BridgeServer(file, port);
        server.start();
        System.out.println("SQLite PostgreSQL bridge listening on 127.0.0.1:" + server.getPort()
                + " serving " + file + " (read-only)");
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Thread.currentThread().join();
    }
}

