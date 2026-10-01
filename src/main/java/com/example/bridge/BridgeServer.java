package com.example.bridge;

import com.example.bridge.protocol.PgProtocolDecoder;
import com.example.bridge.session.Session;
import com.example.bridge.session.SessionRegistry;
import com.example.bridge.sql.QueryEngine;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;

/** Netty server bound to 127.0.0.1 only. */
public class BridgeServer implements AutoCloseable {
    private final int port;
    private final QueryEngine engine;
    private final SessionRegistry registry = new SessionRegistry();
    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel channel;

    public BridgeServer(String sqliteFile, int port) {
        this.port = port;
        this.engine = new QueryEngine(sqliteFile);
    }

    public void start() throws InterruptedException {
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        Session session = new Session(registry, engine);
                        ch.pipeline().addLast(new PgProtocolDecoder(session), new BridgeHandler(session));
                    }
                });
        channel = b.bind(new InetSocketAddress("127.0.0.1", port)).sync().channel();
    }

    public int getPort() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    @Override
    public void close() {
        if (channel != null) channel.close().awaitUninterruptibly();
        engine.shutdown();
        if (boss != null) boss.shutdownGracefully();
        if (worker != null) worker.shutdownGracefully();
    }
}
