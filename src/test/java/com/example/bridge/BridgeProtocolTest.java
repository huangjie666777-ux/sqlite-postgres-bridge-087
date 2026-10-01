package com.example.bridge;

import com.example.bridge.testsupport.TestServer;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Raw wire-level checks for startup, SSL probe, framing and protocol violations. */
class BridgeProtocolTest {

    private static void writeStartup(OutputStream out, int code) throws Exception {
        int payload = 8;
        out.write(new byte[]{
                (byte) (payload >>> 24), (byte) (payload >>> 16),
                (byte) (payload >>> 8), (byte) payload,
                (byte) (code >>> 24), (byte) (code >>> 16),
                (byte) (code >>> 8), (byte) code});
        out.flush();
    }

    private static Object[] readFrame(DataInputStream in) throws Exception {
        int type = in.readUnsignedByte();
        int len = in.readInt();
        return new Object[]{(char) type, in.readNBytes(len - 4)};
    }

    private static void sendStartupV3(OutputStream out) throws Exception {
        out.write(fullStartupPacket());
        out.flush();
    }

    private static byte[] fullStartupPacket() {
        byte[] body = "user\0tester\0database\0main\0\0".getBytes(StandardCharsets.UTF_8);
        int payload = 8 + body.length;
        byte[] buf = new byte[payload];
        buf[0] = (byte) (payload >>> 24);
        buf[1] = (byte) (payload >>> 16);
        buf[2] = (byte) (payload >>> 8);
        buf[3] = (byte) payload;
        buf[4] = 0;
        buf[5] = 3;
        System.arraycopy(body, 0, buf, 8, body.length);
        return buf;
    }

    private static byte[] queryFrame(String sql) {
        byte[] s = sql.getBytes(StandardCharsets.UTF_8);
        int len = 4 + s.length + 1;
        byte[] buf = new byte[1 + len];
        buf[0] = 'Q';
        buf[1] = (byte) (len >>> 24);
        buf[2] = (byte) (len >>> 16);
        buf[3] = (byte) (len >>> 8);
        buf[4] = (byte) len;
        System.arraycopy(s, 0, buf, 5, s.length);
        return buf;
    }

    private static void skipToReady(DataInputStream in) throws Exception {
        while (true) {
            Object[] frame = readFrame(in);
            if ((Character) frame[0] == 'Z') {
                return;
            }
        }
    }

    @Test
    void sslProbeIsRefusedThenStartupSucceeds() throws Exception {
        try (TestServer server = TestServer.start();
             Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            writeStartup(out, 80877103);
            assertEquals('N', (char) in.readUnsignedByte());

            sendStartupV3(out);
            Object[] auth = readFrame(in);
            assertEquals('R', auth[0]);
            assertArrayEquals(new byte[]{0, 0, 0, 0}, (byte[]) auth[1]);

            int params = 0;
            boolean sawKey = false;
            for (int i = 0; i < 30; i++) {
                Object[] frame = readFrame(in);
                char type = (Character) frame[0];
                if (type == 'S') {
                    params++;
                } else if (type == 'K') {
                    sawKey = true;
                    assertEquals(8, ((byte[]) frame[1]).length);
                } else if (type == 'Z') {
                    assertEquals('I', (char) ((byte[]) frame[1])[0]);
                    break;
                }
            }
            assertTrue(params >= 6, "expected ParameterStatus messages");
            assertTrue(sawKey, "expected BackendKeyData");
        }
    }

    @Test
    void illegalStartupLengthSendsErrorAndCloses() throws Exception {
        try (TestServer server = TestServer.start();
             Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            socket.getOutputStream().write(new byte[]{0, 0, 0, 3, 0, 3, 0, 0});
            socket.getOutputStream().flush();
            assertEquals('E', (char) in.readUnsignedByte());
            int len = in.readInt();
            in.readNBytes(len - 4);
            assertEquals(-1, in.read());
        }
    }

    @Test
    void partialAndGluedFramesAreReassembled() throws Exception {
        try (TestServer server = TestServer.start();
             Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            byte[] probe = {0, 0, 0, 8, 4, (byte) 210, 22, 47};
            for (byte b : probe) {
                out.write(b);
                out.flush();
                Thread.sleep(4);
            }
            assertEquals('N', (char) in.readUnsignedByte());

            byte[] startup = fullStartupPacket();
            for (int i = 0; i < startup.length; i++) {
                out.write(startup[i]);
                if (i % 7 == 0) {
                    out.flush();
                }
            }
            out.flush();
            skipToReady(in);

            byte[] empty = queryFrame("   ");
            byte[] select = queryFrame("SELECT 1 AS one");
            byte[] glued = new byte[empty.length + select.length];
            System.arraycopy(empty, 0, glued, 0, empty.length);
            System.arraycopy(select, 0, glued, empty.length, select.length);
            out.write(glued);
            out.flush();

            StringBuilder seq = new StringBuilder();
            int ready = 0;
            while (ready < 2) {
                Object[] frame = readFrame(in);
                char t = (Character) frame[0];
                seq.append(t);
                if (t == 'Z') {
                    ready++;
                }
            }
            assertEquals("IZTDCZ", seq.toString());
        }
    }

    @Test
    void unsupportedProtocolVersionIsRejected() throws Exception {
        try (TestServer server = TestServer.start();
             Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            writeStartup(socket.getOutputStream(), 196607);
            assertEquals('E', (char) in.readUnsignedByte());
            int len = in.readInt();
            in.readNBytes(len - 4);
            assertEquals(-1, in.read());
        }
    }

    @Test
    void illegalRegularMessageLengthSendsErrorAndCloses() throws Exception {
        try (TestServer server = TestServer.start();
             Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            sendStartupV3(socket.getOutputStream());
            skipToReady(in);
            // Query frame declaring length 2 (less than the minimum 4).
            socket.getOutputStream().write(new byte[]{'Q', 0, 0, 0, 2});
            socket.getOutputStream().flush();
            assertEquals('E', (char) in.readUnsignedByte());
            int len = in.readInt();
            in.readNBytes(len - 4);
            assertEquals(-1, in.read());
        }
    }
}
