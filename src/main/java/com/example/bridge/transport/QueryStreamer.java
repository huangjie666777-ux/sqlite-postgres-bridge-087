package com.example.bridge.transport;

import com.example.bridge.protocol.PgProtocol;
import com.example.bridge.sql.QueryCursor;
import io.netty.channel.Channel;

import java.io.IOException;
import java.sql.SQLException;
/**
 * Streams one result cursor to a channel one row at a time with bounded outbound buffering:
 * when the channel is not writable the SQLite reader blocks (on a per-connection worker),
 * so a slow client never accumulates the whole result in memory and never blocks others.
 */
final class QueryStreamer {

    private final Channel channel;
    private final PgSessionHandler handler;

    QueryStreamer(Channel channel, PgSessionHandler handler) {
        this.channel = channel;
        this.handler = handler;
    }

    /** @return number of data rows sent. */
    long stream(QueryCursor cursor) throws SQLException, IOException, InterruptedException {
        channel.writeAndFlush(PgProtocol.rowDescription(cursor.columnNames()));
        long rows = 0;
        while (cursor.next()) {
            if (!channel.isActive()) {
                throw new SQLException("client disconnected");
            }
            channel.write(PgProtocol.dataRow(cursor.currentRow()));
            rows++;
            if (rows % 32 == 0) {
                channel.flush();
            }
            handler.awaitWritable();
        }
        channel.flush();
        return rows;
    }
}
