package com.example.bridge.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Frames PostgreSQL v3 traffic.
 *
 * <p>The decoder is stateful (one per connection): it first expects the startup packet
 * (or an SSL/Cancel probe), then ordinary type-prefixed messages. Netty's accumulating
 * decoder transparently handles partial frames and frames glued together in one read.
 */
public class PgFrameDecoder extends ByteToMessageDecoder {

    /** Startup packet cap (1 MiB); protects against bogus lengths. */
    public static final int MAX_STARTUP_LENGTH = 1 << 20;
    /** Regular message cap (16 MiB). */
    public static final int MAX_MESSAGE_LENGTH = 16 << 20;

    private boolean startupReceived;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!startupReceived) {
            decodeStartup(in, out);
        } else {
            decodeRegular(in, out);
        }
    }

    private void decodeStartup(ByteBuf in, List<Object> out) {
        in.markReaderIndex();
        if (in.readableBytes() < 4) {
            in.resetReaderIndex();
            return;
        }
        int len = in.readInt();
        if (len < 8 || len > MAX_STARTUP_LENGTH) {
            out.add(new FrontendMessage.ProtocolViolation(
                    "invalid startup packet length: " + len));
            in.skipBytes(in.readableBytes());
            return;
        }
        if (in.readableBytes() < len - 4) {
            in.resetReaderIndex();
            return;
        }
        // The remaining payload is exactly len-4 bytes: 4-byte code then parameters.
        ByteBuf payload = in.readRetainedSlice(len - 4);
        int code = payload.readInt();
        switch (code) {
            case PgProtocol.SSL_REQUEST_CODE -> {
                if (len != 8) {
                    payload.release();
                    out.add(new FrontendMessage.ProtocolViolation("malformed SSLRequest"));
                    return;
                }
                payload.release();
                out.add(new FrontendMessage.SslProbe());
            }
            case PgProtocol.CANCEL_REQUEST_CODE -> {
                if (len != 16) {
                    payload.release();
                    out.add(new FrontendMessage.ProtocolViolation("malformed CancelRequest"));
                    return;
                }
                int pid = payload.readInt();
                int secret = payload.readInt();
                payload.release();
                out.add(new FrontendMessage.Cancel(pid, secret));
            }
            case PgProtocol.PROTOCOL_VERSION_3 -> {
                startupReceived = true;
                try {
                    out.add(new FrontendMessage.Startup(parseParameters(payload)));
                } finally {
                    payload.release();
                }
            }
            default -> {
                payload.release();
                out.add(new FrontendMessage.ProtocolViolation(
                        "unsupported frontend protocol version code: " + code));
            }
        }
        // The decoder stays in startup mode until the handler accepts a v3 Startup;
        // SSL and Cancel probes must not advance the state.
    }

    private Map<String, String> parseParameters(ByteBuf payload) {
        Map<String, String> params = new LinkedHashMap<>();
        while (payload.isReadable()) {
            String key = readCString(payload);
            if (key.isEmpty()) {
                break;
            }
            if (!payload.isReadable()) {
                throw new IllegalArgumentException("unterminated startup parameter value");
            }
            String value = readCString(payload);
            params.put(key, value);
        }
        return params;
    }

    private static String readCString(ByteBuf buf) {
        int end = buf.indexOf(buf.readerIndex(), buf.writerIndex(), (byte) 0);
        if (end < 0) {
            throw new IllegalArgumentException("unterminated string in message");
        }
        String s = buf.toString(buf.readerIndex(), end - buf.readerIndex(), StandardCharsets.UTF_8);
        buf.readerIndex(end + 1);
        return s;
    }

    private void decodeRegular(ByteBuf in, List<Object> out) {
        in.markReaderIndex();
        if (in.readableBytes() < 5) {
            in.resetReaderIndex();
            return;
        }
        byte type = in.readByte();
        int len = in.readInt();
        if (len < 4 || len > MAX_MESSAGE_LENGTH) {
            out.add(new FrontendMessage.ProtocolViolation(
                    "invalid message length: " + len + " for type '" + (char) (type & 0xff) + "'"));
            in.skipBytes(in.readableBytes());
            return;
        }
        if (in.readableBytes() < len - 4) {
            in.resetReaderIndex();
            return;
        }
        ByteBuf payload = in.readRetainedSlice(len - 4);
        try {
            out.add(switch (type) {
                case 'Q' -> new FrontendMessage.Query(requireCString(payload, "Query"));
                case 'X' -> new FrontendMessage.Terminate();
                case 'P', 'B', 'E', 'D', 'S', 'H', 'C' -> new FrontendMessage.Unsupported((char) (type & 0xff));
                default -> new FrontendMessage.ProtocolViolation(
                        "unsupported message type: '" + (char) (type & 0xff) + "'");
            });
        } catch (IllegalArgumentException e) {
            out.add(new FrontendMessage.ProtocolViolation(e.getMessage()));
        } finally {
            payload.release();
        }
    }

    private static String requireCString(ByteBuf payload, String what) {
        int end = payload.indexOf(payload.readerIndex(), payload.writerIndex(), (byte) 0);
        if (end < 0) {
            throw new IllegalArgumentException("unterminated " + what + " string");
        }
        String sql = payload.toString(payload.readerIndex(), end - payload.readerIndex(), StandardCharsets.UTF_8);
        payload.readerIndex(end + 1);
        if (payload.isReadable()) {
            throw new IllegalArgumentException("trailing bytes after " + what + " string");
        }
        return sql;
    }

    /** Called by the session handler once Startup has been accepted. */
    public void markStartupReceived() {
        this.startupReceived = true;
    }
}
