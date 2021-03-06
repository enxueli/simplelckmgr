package com.paul.lckmgr.client;

import com.paul.lckmgr.common.Request;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Created by paul on 5/13/16.
 */
public class Client {
    /**
     * The client id
     */
    private String id;

    /**
     * The client channels and one client can have multiple channels
     */
    private EventLoopGroup clientGroup;

    /**
     * The client channel future
     */
    private ChannelFuture channelFuture;

    /**
     * The server address to connect
     */
    private SocketAddress serverAddress;

    private static final Logger _logger = LogManager.getFormatterLogger(Client.class);

    public Client(SocketAddress serverAddress) {
        this.id = UUID.randomUUID().toString();
        this.serverAddress = serverAddress;
    }

    /**
     * Start client
     */
    public void start() throws Exception {
        int clientNumbers = 10;

        // one client has one channel
        clientGroup = new NioEventLoopGroup(1);

        // start multiple clients
        ExecutorService service = Executors.newFixedThreadPool(clientNumbers);

        try {
            Bootstrap bootstrap = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel channel) throws Exception {
                            channel.pipeline()
                                    .addLast(new ObjectDecoder(ClassResolvers.softCachingConcurrentResolver(Client.class.getClassLoader())))
                                    .addLast(new ObjectEncoder())
                                    .addLast(new ClientHandler());
                        }
                    });

            channelFuture = bootstrap.connect(serverAddress).syncUninterruptibly();

            List<Future<Void>> futures = new ArrayList<>(clientNumbers);

            _logger.info("Client starts sending request to server.");

            for (int i = 0; i < clientNumbers; i++) {
                futures.add(service.submit(new Callable<Void>() {
                    public Void call() throws Exception {
                        sendTrylockRequest("lock");
                        _logger.info(String.format("Client sends request, the request id is: [%s]", id + Thread.currentThread().getId()));
                        try {
                            TimeUnit.MILLISECONDS.sleep(500);
                        }
                        finally {
                            sendUnLockRequest("lock");
                            _logger.info(String.format("Client sends request, the request id is: [%s]", id + Thread.currentThread().getId()));
                        }
                        return null;
                    }
                }));
            }

            for (Future<Void> future : futures) {
                try {
                    future.get();
                }
                catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
        }
        finally {
            service.shutdownNow();
        }
    }

    /**
     * Stop client
     */
    public void stop() {
        if (channelFuture != null) {
            channelFuture.channel().close().syncUninterruptibly();
            channelFuture = null;
        }

        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
            clientGroup = null;
        }

        _logger.info("Client stop.");
    }

    /**
     * Send lock request to server
     * @param lockName
     */
    public void sendLockRequest(String lockName) {
        _sendRequest(new Request(lockName, id + Thread.currentThread().getId(), Request.RequestType.LOCK));
    }

    /**
     * Send try lock request to server
     * @param lockName
     */
    public void sendTrylockRequest(String lockName) {
        _sendRequest(new Request(lockName, id + Thread.currentThread().getId(), Request.RequestType.TRYLOCK));
    }

    /**
     * Send unlock request to server
     * @param lockName
     */
    public void sendUnLockRequest(String lockName) {
        _sendRequest(new Request(lockName, id + Thread.currentThread().getId(), Request.RequestType.UNLOCK));
    }

    /**
     * Common sending request method
     * @param request
     */
    private void _sendRequest(Request request) {
        ChannelFuture channelFuture1 = channelFuture.channel().writeAndFlush(request);
        channelFuture1.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    _logger.info("Client sends request success.");
                } else {
                    _logger.warn("Client sends request failed.");
                }
            }
        });
    }

    public static void main(String[] args) throws Exception {
        Client client = new Client(new InetSocketAddress(InetAddress.getLocalHost(), 8088));
        try {
            client.start();
        }
        finally {
            client.stop();
        }
    }
}