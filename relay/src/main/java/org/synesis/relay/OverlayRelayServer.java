package org.synesis.relay;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayForwardingFrame;
import org.synesis.link.overlay.OverlayRelay;
import org.synesis.link.overlay.OverlayRelayException;

/**
 * One vertically scalable Netty TCP relay endpoint for bounded Synesis frames.
 *
 * <p>The relay connection authenticates node identities with signed Ed25519
 * hello records and then forwards only `SLF1` frames. It does not expose a
 * generic socket proxy, durable queue, project authority, or plaintext API.
 */
public final class OverlayRelayServer implements AutoCloseable {

    private static final int IDLE_TIMEOUT_SECONDS = 60;
    private final InetSocketAddress bindAddress;
    private final NodeIdentity relayIdentity;
    private final OverlayRelay relay;
    private final int workerThreads;
    private final Clock clock;
    private MultiThreadIoEventLoopGroup boss;
    private MultiThreadIoEventLoopGroup workers;
    private Channel serverChannel;

    /**
     * Creates a relay server.
     *
     * @param bindAddress local bind address
     * @param relayIdentity durable relay identity used for mutual authentication
     * @param relay bounded live relay core
     * @param workerThreads Netty worker/event-loop count
     */
    public OverlayRelayServer(InetSocketAddress bindAddress, NodeIdentity relayIdentity, OverlayRelay relay,
            int workerThreads) {
        this(bindAddress, relayIdentity, relay, workerThreads, Clock.systemUTC());
    }

    OverlayRelayServer(InetSocketAddress bindAddress, NodeIdentity relayIdentity, OverlayRelay relay,
            int workerThreads, Clock clock) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bind address");
        this.relayIdentity = Objects.requireNonNull(relayIdentity, "relay identity");
        this.relay = Objects.requireNonNull(relay, "relay");
        if (workerThreads < 1) {
            throw new IllegalArgumentException("relay worker count must be positive");
        }
        this.workerThreads = workerThreads;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Starts the event-loop relay and returns its bound address.
     *
     * @return bound local address
     * @throws InterruptedException if binding is interrupted
     */
    public synchronized InetSocketAddress start() throws InterruptedException {
        if (serverChannel != null) {
            return (InetSocketAddress) serverChannel.localAddress();
        }
        boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workers = new MultiThreadIoEventLoopGroup(workerThreads, NioIoHandler.newFactory());
        try {
            serverChannel = new ServerBootstrap().group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new IdleStateHandler(IDLE_TIMEOUT_SECONDS, 0, 0));
                            channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                                    RelayWireCodec.MAX_PAYLOAD_BYTES + Integer.BYTES, 0, Integer.BYTES, 0,
                                    Integer.BYTES));
                            channel.pipeline().addLast(new LengthFieldPrepender(Integer.BYTES));
                            channel.pipeline().addLast(new RelayServerHandler());
                        }
                    })
                    .bind(bindAddress)
                    .sync()
                    .channel();
            return (InetSocketAddress) serverChannel.localAddress();
        } catch (InterruptedException failure) {
            close();
            throw failure;
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    /**
     * Returns the bound address.
     *
     * @return bound address
     * @throws IllegalStateException if the server has not started
     */
    public synchronized InetSocketAddress localAddress() {
        if (serverChannel == null) {
            throw new IllegalStateException("relay server is not started");
        }
        return (InetSocketAddress) serverChannel.localAddress();
    }

    /**
     * Closes the server and its event-loop groups.
     */
    @Override
    public synchronized void close() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (boss != null) {
            boss.shutdownGracefully().syncUninterruptibly();
            boss = null;
        }
        if (workers != null) {
            workers.shutdownGracefully().syncUninterruptibly();
            workers = null;
        }
    }

    private final class RelayServerHandler extends SimpleChannelInboundHandler<ByteBuf> {

        private OverlayRelay.Registration registration;
        private RelayWireCodec.Hello hello;
        private ScheduledFuture<?> handshakeDeadline;

        @Override
        public void channelActive(ChannelHandlerContext context) {
            handshakeDeadline = context.executor().schedule(() -> context.close(), 10, TimeUnit.SECONDS);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
            byte[] bytes = new byte[message.readableBytes()];
            message.readBytes(bytes);
            if (registration == null) {
                acceptHello(context, bytes);
            } else {
                acceptFrame(context, bytes);
            }
        }

        private void acceptHello(ChannelHandlerContext context, byte[] bytes) {
            try {
                hello = RelayWireCodec.decodeHello(bytes);
                RelayWireCodec.verifyHello(hello);
                registration = relay.registerAuthenticated(hello.nodeId(), frame -> deliver(context, frame));
                if (handshakeDeadline != null) {
                    handshakeDeadline.cancel(false);
                }
                context.writeAndFlush(Unpooled.wrappedBuffer(RelayWireCodec.encodeHelloAck(relayIdentity, hello)));
            } catch (Exception failure) {
                context.close();
            }
        }

        private void acceptFrame(ChannelHandlerContext context, byte[] bytes) {
            final OverlayForwardingFrame frame;
            try {
                frame = OverlayForwardingFrame.decode(RelayWireCodec.decodeFrame(bytes, 2));
            } catch (Exception failure) {
                context.close();
                return;
            }
            CompletionStage<Void> completion = registration.send(frame, Instant.now(clock));
            completion.whenComplete((ignored, failure) -> context.executor().execute(() -> {
                if (!context.channel().isActive()) {
                    return;
                }
                int status = failure == null ? 0 : failureStatus(failure);
                context.writeAndFlush(Unpooled.wrappedBuffer(RelayWireCodec.encodeFrameAck(frame.messageId(), status)));
            }));
        }

        private CompletionStage<Void> deliver(ChannelHandlerContext context, OverlayForwardingFrame frame) {
            if (!context.channel().isActive()) {
                return CompletableFuture.failedFuture(new IllegalStateException("relay client is closed"));
            }
            CompletableFuture<Void> result = new CompletableFuture<>();
            context.channel().writeAndFlush(Unpooled.wrappedBuffer(RelayWireCodec.encodeInboundFrame(frame)))
                    .addListener(future -> {
                        if (future.isSuccess()) {
                            result.complete(null);
                        } else {
                            result.completeExceptionally(future.cause());
                        }
                    });
            return result;
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            if (handshakeDeadline != null) {
                handshakeDeadline.cancel(false);
            }
            if (registration != null) {
                registration.close();
                registration = null;
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext context, Object event) {
            if (event instanceof IdleStateEvent) {
                context.close();
            } else {
                context.fireUserEventTriggered(event);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            context.close();
        }
    }

    private static int failureStatus(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && !(current instanceof OverlayRelayException)) {
            current = current.getCause();
        }
        if (current instanceof OverlayRelayException relayFailure) {
            return relayFailure.failure().ordinal() + 1;
        }
        return 255;
    }
}
