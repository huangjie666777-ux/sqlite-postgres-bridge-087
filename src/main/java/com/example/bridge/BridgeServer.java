package com.example.bridge;

import com.example.bridge.protocol.PgFrameDecoder;
import com.example.bridge.session.SessionRegistry;
import com.example.bridge.transport.PgSessionHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;

/** Netty TCP server that speaks PostgreSQL wire protocol v3 over a read-only SQLite file. */
public final class BridgeServer implements AutoCloseable {

    private final String sqliteFile;
    private final int port;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private SessionRegistry registry;
    private Channel serverChannel;

    public BridgeServer(String sqliteFile, int port) {
        this.sqliteFile = sqliteFile;
        this.port = port;
    }

    public int start() throws InterruptedException {
        registry = new SessionRegistry(sqliteFile);
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .localAddress(new InetSocketAddress("127.0.0.1", port))
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new PgFrameDecoder())
                                .addLast(new PgSessionHandler(registry));
                    }
                });
        serverChannel = bootstrap.bind().sync().channel();
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (registry != null) {
            registry.close();
        }
    }
}
