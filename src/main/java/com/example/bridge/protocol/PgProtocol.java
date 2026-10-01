package com.example.bridge.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Encoders for PostgreSQL frontend/backend protocol v3 messages. */
public final class PgProtocol {

    public static final int PROTOCOL_VERSION_3 = 196608;
    public static final int SSL_REQUEST_CODE = 80877103;
    public static final int CANCEL_REQUEST_CODE = 80877102;

    public static final int SSL_REPLY_NO = 'N';
    public static final int SSL_REPLY_YES = 'S';

    public static final char ERROR = 'E';
    public static final char NOTICE = 'N';

    private PgProtocol() {
    }

    public static void writeCString(ByteBuf buf, String value) {
        buf.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        buf.writeByte(0);
    }

    private static ByteBuf frame(char type) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(type);
        buf.writeInt(0);
        return buf;
    }

    private static ByteBuf finish(ByteBuf buf) {
        int len = buf.writerIndex() - 1;
        buf.setInt(1, len);
        return buf;
    }

    /** AuthenticationOk ('R', code 0). */
    public static ByteBuf authenticationOk() {
        ByteBuf buf = frame('R');
        buf.writeInt(0);
        return finish(buf);
    }

    /** ParameterStatus ('S') messages. */
    public static ByteBuf parameterStatus(String name, String value) {
        ByteBuf buf = frame('S');
        writeCString(buf, name);
        writeCString(buf, value);
        return finish(buf);
    }

    /** BackendKeyData ('K') carrying session id and cancel secret. */
    public static ByteBuf backendKeyData(int pid, int secret) {
        ByteBuf buf = frame('K');
        buf.writeInt(pid);
        buf.writeInt(secret);
        return finish(buf);
    }

    /** ReadyForQuery ('Z'); status: I idle, T in-transaction, E failed. */
    public static ByteBuf readyForQuery(char status) {
        ByteBuf buf = frame('Z');
        buf.writeByte(status);
        return finish(buf);
    }

    /** EmptyQueryResponse ('I'). */
    public static ByteBuf emptyQueryResponse() {
        return finish(frame('I'));
    }

    /** CommandComplete ('C'). */
    public static ByteBuf commandComplete(String tag) {
        ByteBuf buf = frame('C');
        writeCString(buf, tag);
        return finish(buf);
    }

    /** RowDescription ('T'); all columns are reported as text (type OID 25). */
    public static ByteBuf rowDescription(java.util.List<String> columnNames) {
        ByteBuf buf = frame('T');
        buf.writeShort(columnNames.size());
        for (int i = 0; i < columnNames.size(); i++) {
            String name = columnNames.get(i);
            writeCString(buf, name);
            buf.writeInt(0); // table oid
            buf.writeShort(0); // table attribute
            buf.writeInt(25); // TEXTOID
            buf.writeShort(-1); // type size (varlena)
            buf.writeInt(0); // type modifier
            buf.writeShort(0); // text format
        }
        return finish(buf);
    }

    /** DataRow ('D'); elements are already-encoded UTF-8 strings or null. */
    public static ByteBuf dataRow(java.util.List<byte[]> values) {
        ByteBuf buf = frame('D');
        buf.writeShort(values.size());
        for (byte[] value : values) {
            if (value == null) {
                buf.writeInt(-1);
            } else {
                buf.writeInt(value.length);
                buf.writeBytes(value);
            }
        }
        return finish(buf);
    }

    /** ErrorResponse ('E') using fields: S severity, C sqlstate, M message. */
    public static ByteBuf errorResponse(String sqlState, String message) {
        ByteBuf buf = frame(ERROR);
        buf.writeByte('S');
        writeCString(buf, sqlState.startsWith("57014") ? "FATAL" : "ERROR");
        buf.writeByte('C');
        writeCString(buf, sqlState);
        buf.writeByte('M');
        writeCString(buf, message);
        buf.writeByte(0);
        return finish(buf);
    }

    /** Negotiation response for the SSLRequest probe. */
    public static ByteBuf sslReply(boolean supported) {
        return Unpooled.buffer(1).writeByte(supported ? SSL_REPLY_YES : SSL_REPLY_NO);
    }

    public static Map<String, String> defaultParameters() {
        return Map.of(
                "server_version", "17.0 (sqlite-bridge)",
                "server_encoding", "UTF8",
                "client_encoding", "UTF8",
                "DateStyle", "ISO, YMD",
                "TimeZone", "UTC",
                "integer_datetimes", "on",
                "application_name", "",
                "is_superuser", "off",
                "session_authorization", "",
                "standard_conforming_strings", "on"
        );
    }
}
