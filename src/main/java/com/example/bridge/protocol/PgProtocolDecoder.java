package com.example.bridge.protocol;

import com.example.bridge.session.Session;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Decodes the PostgreSQL wire protocol. Handles the startup phase
 * (SSLRequest, CancelRequest, StartupMessage) and regular messages.
 * Cumulation from ByteToMessageDecoder naturally handles fragmented
 * (half) and coalesced (sticky) packets. Illegal lengths are rejected.
 */
public class PgProtocolDecoder extends ByteToMessageDecoder {
    public static final int MAX_STARTUP_PACKET = 10_000;
    public static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

    private static final int SSL_REQUEST_CODE = 80877103;
    private static final int CANCEL_REQUEST_CODE = 80877102;
    private static final int PROTOCOL_3_0 = 196608; // 3 << 16

    private final Session session;
    private boolean started;

    public PgProtocolDecoder(Session session) {
        this.session = session;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!started) {
            decodeStartup(ctx, in);
        } else {
            decodeMessage(ctx, in, out);
        }
    }

    private void decodeStartup(ChannelHandlerContext ctx, ByteBuf in) {
        if (in.readableBytes() < 4) return;
        in.markReaderIndex();
        int len = in.readInt();
        if (len < 8 || len > MAX_STARTUP_PACKET) {
            fail(ctx, "08P01", "invalid startup packet length: " + len);
            return;
        }
        if (in.readableBytes() < len - 4) {
            in.resetReaderIndex(); // wait for the rest of the packet
            return;
        }
        int code = in.readInt();
        if (code == SSL_REQUEST_CODE) {
            in.skipBytes(len - 8);
            ctx.writeAndFlush(ctx.alloc().buffer(1).writeByte('N')); // SSL refused
            return; // stay in startup phase
        }
        if (code == CANCEL_REQUEST_CODE) {
            if (len != 16) {
                fail(ctx, "08P01", "invalid cancel request length: " + len);
                return;
            }
            int pid = in.readInt();
            int secret = in.readInt();
            session.getRegistry().cancel(pid, secret);
            ctx.close();
            return;
        }
        if (code != PROTOCOL_3_0) {
            fail(ctx, "0A000", "unsupported protocol version: " + (code >> 16) + "." + (code & 0xFFFF));
            return;
        }
        // consume startup parameters (key/value cstrings terminated by a zero byte)
        int remaining = len - 8;
        ByteBuf params = in.readSlice(remaining);
        while (params.readableBytes() > 1) {
            readCString(params); // key
            readCString(params); // value
        }
        started = true;
        session.onStartupComplete(ctx);
    }

    private void decodeMessage(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 5) return;
        in.markReaderIndex();
        byte type = in.readByte();
        int len = in.readInt();
        if (len < 4 || len > MAX_MESSAGE_SIZE) {
            fail(ctx, "08P01", "invalid message length: " + len);
            return;
        }
        if (in.readableBytes() < len - 4) {
            in.resetReaderIndex(); // fragmented packet, wait for more bytes
            return;
        }
        ByteBuf body = in.readSlice(len - 4);
        switch (type) {
            case 'Q' -> out.add(readCString(body));
            case 'X' -> ctx.close();
            default -> out.add(new UnsupportedMessage((char) type));
        }
    }

    private void fail(ChannelHandlerContext ctx, String sqlState, String message) {
        ctx.writeAndFlush(PgMessages.error(ctx, "FATAL", sqlState, message))
                .addListener(f -> ctx.close());
    }

    private static String readCString(ByteBuf buf) {
        int end = buf.indexOf(buf.readerIndex(), buf.writerIndex(), (byte) 0);
        if (end < 0) {
            String s = buf.toString(StandardCharsets.UTF_8);
            buf.readerIndex(buf.writerIndex());
            return s;
        }
        String s = buf.toString(buf.readerIndex(), end - buf.readerIndex(), StandardCharsets.UTF_8);
        buf.readerIndex(end + 1);
        return s;
    }

    public record UnsupportedMessage(char type) {}
}

