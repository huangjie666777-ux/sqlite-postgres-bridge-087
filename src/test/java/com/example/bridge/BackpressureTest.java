package com.example.bridge;

import com.example.bridge.testsupport.TestServer;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client that never reads must not cause the server to buffer an unbounded result set
 * or block other connections. The query worker stalls on the writability gate instead.
 */
class BackpressureTest {

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

    private static void skipToReady(DataInputStream in) throws Exception {
        while (true) {
            int type = in.readUnsignedByte();
            int len = in.readInt();
            in.readNBytes(len - 4);
            if ((char) type == 'Z') {
                return;
            }
        }
    }

    @Test
    void slowClientDoesNotBlockOtherConnections() throws Exception {
        try (TestServer server = TestServer.start();
             Socket slow = new Socket("127.0.0.1", server.port());
             Socket fast = new Socket("127.0.0.1", server.port())) {
            slow.setSendBufferSize(64 * 1024);
            slow.setReceiveBufferSize(64 * 1024);
            slow.setSoTimeout(20000);
            fast.setSoTimeout(5000);

            OutputStream slowOut = slow.getOutputStream();
            slowOut.write(startup());
            slowOut.flush();
            DataInputStream slowIn = new DataInputStream(slow.getInputStream());
            skipToReady(slowIn);

            DataInputStream fastIn = new DataInputStream(fast.getInputStream());
            fast.getOutputStream().write(startup());
            fast.getOutputStream().flush();
            skipToReady(fastIn);

            // Huge result toward the slow client; never drain its receive buffer.
            String huge = "WITH RECURSIVE seq(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM seq WHERE x < 2000000) "
                    + "SELECT x, printf('%064d', x) AS pad FROM seq";
            slowOut.write(query(huge));
            slowOut.flush();
            Thread.sleep(500);

            // The fast connection keeps getting full results promptly.
            fast.getOutputStream().write(query("SELECT count(*) AS n FROM products"));
            fast.getOutputStream().flush();
            String seq = "";
            long t0 = System.currentTimeMillis();
            while (true) {
                int type = fastIn.readUnsignedByte();
                int len = fastIn.readInt();
                byte[] payload = fastIn.readNBytes(len - 4);
                seq += (char) type;
                if ((char) type == 'D') {
                    int colCount = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
                    int valueLen = ((payload[2] & 0xff) << 24) | ((payload[3] & 0xff) << 16)
                            | ((payload[4] & 0xff) << 8) | (payload[5] & 0xff);
                    assertEquals(1, colCount);
                    String cell = new String(payload, 6, valueLen, StandardCharsets.UTF_8);
                    assertEquals("4", cell);
                }
                if ((char) type == 'Z') {
                    break;
                }
            }
            assertTrue(System.currentTimeMillis() - t0 < 5000, "other connection stayed responsive");
            assertEquals("TDCZ", seq);
        }
    }
}
