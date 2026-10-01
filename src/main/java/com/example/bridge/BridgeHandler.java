package com.example.bridge;

import com.example.bridge.protocol.PgMessages;
import com.example.bridge.protocol.PgProtocolDecoder;
import com.example.bridge.session.Session;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/** Dispatches decoded messages: query strings or unsupported-message markers. */
public class BridgeHandler extends SimpleChannelInboundHandler<Object> {
    private final Session session;

    public BridgeHandler(Session session) {
        this.session = session;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof String sql) {
            session.submitQuery(sql);
        } else if (msg instanceof PgProtocolDecoder.UnsupportedMessage u) {
            // Extended query protocol and anything else is rejected politely.
            ctx.writeAndFlush(PgMessages.error(ctx, "ERROR", "0A000",
                    "unsupported message type: '" + u.type() + "' (extended protocol not supported)"));
            ctx.writeAndFlush(PgMessages.readyForQuery(ctx));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        session.close();
        ctx.close();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable()) session.signalWritable();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        session.close();
        ctx.close();
    }
}
