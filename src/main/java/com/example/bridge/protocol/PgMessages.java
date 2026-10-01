package com.example.bridge.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Builders for PostgreSQL protocol 3.0 backend messages. */
public final class PgMessages {
    private PgMessages() {}

    public static ByteBuf authenticationOk(ChannelHandlerContext ctx) {
        ByteBuf body = ctx.alloc().buffer(8);
        body.writeInt(0);
        return frame(ctx, 'R', body);
    }

    public static ByteBuf parameterStatus(ChannelHandlerContext ctx, String key, String value) {
        ByteBuf body = ctx.alloc().buffer();
        writeCString(body, key);
        writeCString(body, value);
        return frame(ctx, 'S', body);
    }

    public static ByteBuf backendKeyData(ChannelHandlerContext ctx, int pid, int secret) {
        ByteBuf body = ctx.alloc().buffer(8);
        body.writeInt(pid);
        body.writeInt(secret);
        return frame(ctx, 'K', body);
    }

    public static ByteBuf readyForQuery(ChannelHandlerContext ctx) {
        ByteBuf body = ctx.alloc().buffer(1);
        body.writeByte('I');
        return frame(ctx, 'Z', body);
    }

    public static ByteBuf emptyQueryResponse(ChannelHandlerContext ctx) {
        return frame(ctx, 'I', ctx.alloc().buffer(0));
    }

    public static ByteBuf commandComplete(ChannelHandlerContext ctx, String tag) {
        ByteBuf body = ctx.alloc().buffer();
        writeCString(body, tag);
        return frame(ctx, 'C', body);
    }

    public static ByteBuf rowDescription(ChannelHandlerContext ctx, List<String> columns) {
        ByteBuf body = ctx.alloc().buffer();
        body.writeShort(columns.size());
        for (String name : columns) {
            writeCString(body, name);
            body.writeInt(0);       // table oid
            body.writeShort(0);     // column attnum
            body.writeInt(25);      // type oid: text
            body.writeShort(-1);    // typlen
            body.writeInt(-1);      // typmod
            body.writeShort(0);     // format: text
        }
        return frame(ctx, 'T', body);
    }

    /** values: null element means SQL NULL (distinguished from empty string). */
    public static ByteBuf dataRow(ChannelHandlerContext ctx, List<String> values) {
        ByteBuf body = ctx.alloc().buffer();
        body.writeShort(values.size());
        for (String v : values) {
            if (v == null) {
                body.writeInt(-1);
            } else {
                byte[] b = v.getBytes(StandardCharsets.UTF_8);
                body.writeInt(b.length);
                body.writeBytes(b);
            }
        }
        return frame(ctx, 'D', body);
    }

    public static ByteBuf error(ChannelHandlerContext ctx, String severity, String sqlState, String message) {
        ByteBuf body = ctx.alloc().buffer();
        body.writeByte('S');
        writeCString(body, severity);
        body.writeByte('V');
        writeCString(body, severity);
        body.writeByte('C');
        writeCString(body, sqlState);
        body.writeByte('M');
        writeCString(body, message);
        body.writeByte(0);
        return frame(ctx, 'E', body);
    }

    public static ByteBuf frame(ChannelHandlerContext ctx, char type, ByteBuf body) {
        ByteBuf out = ctx.alloc().buffer(5 + body.readableBytes());
        out.writeByte((byte) type);
        out.writeInt(4 + body.readableBytes());
        out.writeBytes(body);
        body.release();
        return out;
    }

    public static void writeCString(ByteBuf buf, String s) {
        buf.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        buf.writeByte(0);
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}

