package com.example.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Raw-socket protocol tests: startup, SSL refusal, framing, illegal lengths. */
class ProtocolTest extends BridgeTestBase {

    private static byte[] startupBody(int code, String... kv) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);
        d.writeInt(code);
        for (String s : kv) {
            d.write(s.getBytes(StandardCharsets.UTF_8));
            d.writeByte(0);
        }
        d.writeByte(0);
        byte[] body = bos.toByteArray();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(out);
        o.writeInt(body.length + 4);
        o.write(body);
        return out.toByteArray();
    }

    @Test
    void sslRequestRefusedThenStartupSucceeds() throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.getPort())) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            out.write(startupBody(80877103));
            out.flush();
            assertEquals('N', in.readByte());
            out.write(startupBody(196608, "user", "tester"));
            out.flush();
            // expect AuthenticationOk, some ParameterStatus, BackendKeyData, ReadyForQuery
            boolean sawAuth = false, sawKey = false, sawReady = false;
            for (int i = 0; i < 10 && !(sawReady); i++) {
                char type = (char) in.readByte();
                int len = in.readInt();
                byte[] body = in.readNBytes(len - 4);
                assertEquals(len - 4, body.length);
                if (type == 'R') sawAuth = true;
                if (type == 'K') {
                    sawKey = true;
                    assertEquals(8, body.length);
                }
                if (type == 'Z') sawReady = true;
            }
            assertTrue(sawAuth && sawKey && sawReady);
        }
    }

    @Test
    void fragmentedAndCoalescedPackets() throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.getPort())) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] startup = startupBody(196608, "user", "tester");
            // send startup byte-by-byte (half packets)
            for (byte b : startup) {
                out.write(b);
                out.flush();
            }
            // read until ReadyForQuery
            while (true) {
                char type = (char) in.readByte();
                int len = in.readInt();
                in.readNBytes(len - 4);
                if (type == 'Z') break;
            }
            // sticky: two queries in one TCP write
            byte[] q1 = queryMsg("SELECT 1");
            byte[] q2 = queryMsg("SELECT 2");
            byte[] both = new byte[q1.length + q2.length];
            System.arraycopy(q1, 0, both, 0, q1.length);
            System.arraycopy(q2, 0, both, q1.length, q2.length);
            out.write(both);
            out.flush();
            int readyCount = 0, rowCount = 0;
            while (readyCount < 2) {
                char type = (char) in.readByte();
                int len = in.readInt();
                in.readNBytes(len - 4);
                if (type == 'Z') readyCount++;
                if (type == 'D') rowCount++;
            }
            assertEquals(2, rowCount);
        }
    }

    @Test
    void illegalLengthClosesConnection() throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.getPort())) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            out.writeInt(3); // invalid startup length (< 8)
            out.flush();
            char type = (char) in.readByte();
            assertEquals('E', type);
            int len = in.readInt();
            in.readNBytes(len - 4);
            assertEquals(-1, in.read()); // closed
        }
    }

    @Test
    void cancelWithWrongSecretIsNoOp() throws Exception {
        try (Socket a = new Socket("127.0.0.1", server.getPort());
             Socket b = new Socket("127.0.0.1", server.getPort())) {
            DataOutputStream outA = new DataOutputStream(a.getOutputStream());
            DataInputStream inA = new DataInputStream(a.getInputStream());
            outA.write(startupBody(196608, "user", "tester"));
            outA.flush();
            int pid = -1;
            while (true) {
                char type = (char) inA.readByte();
                int len = inA.readInt();
                byte[] body = inA.readNBytes(len - 4);
                if (type == 'K') {
                    pid = java.nio.ByteBuffer.wrap(body).getInt();
                }
                if (type == 'Z') break;
            }
            assertTrue(pid > 0);
            DataOutputStream outB = new DataOutputStream(b.getOutputStream());
            DataOutputStream tmp = new DataOutputStream(new java.io.ByteArrayOutputStream());
            outB.writeInt(16);
            outB.writeInt(80877102);
            outB.writeInt(pid);
            outB.writeInt(123456789); // wrong secret
            outB.flush();
            // connection b is closed by server; connection a still works
            outA.write(queryMsg("SELECT 'still-here'"));
            outA.flush();
            boolean ok = false;
            while (!ok) {
                char type = (char) inA.readByte();
                int len = inA.readInt();
                byte[] body = inA.readNBytes(len - 4);
                if (type == 'D') ok = new String(body, 3, body.length - 3, StandardCharsets.UTF_8).contains("still-here");
                if (type == 'Z') break;
            }
            assertTrue(ok);
        }
    }

    private static byte[] queryMsg(String sql) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);
        d.writeByte('Q');
        byte[] b = sql.getBytes(StandardCharsets.UTF_8);
        d.writeInt(b.length + 5);
        d.write(b);
        d.writeByte(0);
        return bos.toByteArray();
    }
}
