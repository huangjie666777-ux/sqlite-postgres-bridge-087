package com.example.bridge;

import com.example.bridge.testsupport.TestServer;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-level CancelRequest checks (independent connection, pid + secret). */
class CancelRequestTest {

    private static Object[] readFrame(DataInputStream in) throws Exception {
        int type = in.readUnsignedByte();
        int len = in.readInt();
        return new Object[]{(char) type, in.readNBytes(len - 4)};
    }

    private static byte[] startup() {
        byte[] body = "user\0tester\0database\0main\0\0".getBytes(StandardCharsets.UTF_8);
        int len = 8 + body.length;
        byte[] buf = new byte[len];
        buf[0] = (byte) (len >>> 24);
        buf[1] = (byte) (len >>> 16);
        buf[2] = (byte) (len >>> 8);
        buf[3] = (byte) len;
        buf[4] = 0;
        buf[5] = 3;
        System.arraycopy(body, 0, buf, 8, body.length);
        return buf;
    }

    private static byte[] query(String sql) {
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

    private static byte[] cancelPacket(int pid, int secret) {
        byte[] buf = new byte[16];
        int p = 0;
        buf[p++] = 0; buf[p++] = 0; buf[p++] = 0; buf[p++] = 16;
        buf[p++] = 4; buf[p++] = (byte) 210; buf[p++] = 22; buf[p++] = 46;
        buf[p++] = (byte) (pid >>> 24); buf[p++] = (byte) (pid >>> 16);
        buf[p++] = (byte) (pid >>> 8); buf[p++] = (byte) pid;
        buf[p++] = (byte) (secret >>> 24); buf[p++] = (byte) (secret >>> 16);
        buf[p++] = (byte) (secret >>> 8); buf[p] = (byte) secret;
        return buf;
    }

    private static int[] readKeyAndDrainToReady(DataInputStream in) throws Exception {
        int[] key = null;
        while (true) {
            Object[] frame = readFrame(in);
            char t = (Character) frame[0];
            byte[] payload = (byte[]) frame[1];
            if (t == 'K') {
                int pid = ((payload[0] & 0xff) << 24) | ((payload[1] & 0xff) << 16)
                        | ((payload[2] & 0xff) << 8) | (payload[3] & 0xff);
                int secret = ((payload[4] & 0xff) << 24) | ((payload[5] & 0xff) << 16)
                        | ((payload[6] & 0xff) << 8) | (payload[7] & 0xff);
                key = new int[]{pid, secret};
            }
            if (t == 'Z') {
                return key;
            }
        }
    }

    private static void sendCancel(int port, int pid, int secret) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.getOutputStream().write(cancelPacket(pid, secret));
            s.getOutputStream().flush();
            // The cancel channel is closed by the server after processing.
            s.setSoTimeout(2000);
            int b = s.getInputStream().read();
            assertEquals(-1, b);
        }
    }

    @Test
    void correctKeyCancelsWrongKeyIsIgnoredConnectionStaysUsable() throws Exception {
        try (TestServer server = TestServer.start();
             Socket sock = new Socket("127.0.0.1", server.port())) {
            sock.setSoTimeout(15000);
            DataInputStream in = new DataInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            out.write(startup());
            out.flush();
            int[] key = readKeyAndDrainToReady(in);
            assertEquals(1, key[0]);

            // A wrong-secret cancel must have no effect.
            sendCancel(server.port(), key[0], key[1] ^ 0xdeadbeef);

            // Long-running query on its own connection.
            String heavy = "WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM cnt "
                    + "WHERE x < 50000000) SELECT count(*) FROM cnt a, cnt b WHERE b.x < 2000000";
            out.write(query(heavy));
            out.flush();
            Thread.sleep(200);

            long t0 = System.currentTimeMillis();
            sendCancel(server.port(), key[0], key[1]);

            // Expect ErrorResponse with SQLSTATE 57014, then ReadyForQuery.
            char errorType = 0;
            String state = null;
            while (true) {
                Object[] frame = readFrame(in);
                char t = (Character) frame[0];
                if (t == 'E') {
                    errorType = 'E';
                    String body = new String((byte[]) frame[1], StandardCharsets.UTF_8);
                    state = extractField(body, 'C');
                }
                if (t == 'Z') {
                    assertEquals('I', (char) ((byte[]) frame[1])[0]);
                    break;
                }
            }
            assertEquals('E', errorType);
            assertEquals("57014", state);
            assertTrue(System.currentTimeMillis() - t0 < 10000, "cancel should interrupt promptly");

            // Original connection remains usable: a normal query completes.
            out.write(query("SELECT 3+4 AS seven"));
            out.flush();
            String sequence = "";
            while (true) {
                Object[] frame = readFrame(in);
                char t = (Character) frame[0];
                sequence += t;
                if (t == 'Z') {
                    break;
                }
            }
            assertTrue(sequence.contains("TDCZ"), "got " + sequence);
            assertFalse(sequence.contains("E"), "no error after cancellation: " + sequence);
        }
    }

    private static String extractField(String body, char field) {
        for (int i = 0; i + 1 < body.length(); i++) {
            if (body.charAt(i) == field) {
                int end = body.indexOf('\0', i + 1);
                return body.substring(i + 1, end < 0 ? body.length() : end);
            }
        }
        return null;
    }
}
