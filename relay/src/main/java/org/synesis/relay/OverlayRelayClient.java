package org.synesis.relay;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayForwardingFrame;

/**
 * Client binding for one authenticated live relay connection.
 */
public final class OverlayRelayClient implements AutoCloseable {

    private final NodeIdentity identity;
    private final InetSocketAddress serverAddress;
    private final String expectedRelayNodeId;
    private final byte[] expectedRelayPublicKey;
    private final Consumer<OverlayForwardingFrame> inboundHandler;
    private final Map<java.util.UUID, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();
    private final CompletableFuture<OverlayRelayClient> connected = new CompletableFuture<>();
    private MultiThreadIoEventLoopGroup group;
    private volatile Channel channel;
    private volatile boolean authenticated;
    private volatile boolean closed;

    /**
     * Creates a relay client with a pinned relay identity.
     *
     * @param identity local durable node identity
     * @param serverAddress relay endpoint
     * @param expectedRelayNodeId pinned relay node ID
     * @param expectedRelayPublicKey pinned relay public key
     * @param inboundHandler callback for opaque frames addressed to this node
     */
    public OverlayRelayClient(NodeIdentity identity, InetSocketAddress serverAddress, String expectedRelayNodeId,
            byte[] expectedRelayPublicKey, Consumer<OverlayForwardingFrame> inboundHandler) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.serverAddress = Objects.requireNonNull(serverAddress, "server address");
        this.expectedRelayNodeId = Objects.requireNonNull(expectedRelayNodeId, "expected relay node ID");
        this.expectedRelayPublicKey = Objects.requireNonNull(expectedRelayPublicKey, "expected relay public key")
                .clone();
        this.inboundHandler = Objects.requireNonNull(inboundHandler, "inbound handler");
    }

    /**
     * Connects and authenticates this client.
     *
     * @return completion of the pinned relay handshake
     */
    public CompletionStage<OverlayRelayClient> connect() {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("relay client is closed"));
        }
        if (group != null) {
            return connected;
        }
        group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            io.netty.channel.ChannelFuture connection = new Bootstrap().group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                                    RelayWireCodec.MAX_PAYLOAD_BYTES + Integer.BYTES, 0, Integer.BYTES, 0,
                                    Integer.BYTES));
                            channel.pipeline().addLast(new LengthFieldPrepender(Integer.BYTES));
                            channel.pipeline().addLast(new RelayClientHandler());
                        }
                    })
                    .connect(serverAddress);
            connection.addListener(future -> {
                        if (!future.isSuccess()) {
                            failAll(future.cause());
                        } else {
                            channel = connection.channel();
                        }
                    });
        } catch (RuntimeException failure) {
            failAll(failure);
        }
        return connected;
    }

    /**
     * Sends one frame and waits for the relay's live-forwarding acknowledgement.
     *
     * @param frame bounded frame
     * @return completion of relay forwarding
     */
    public CompletionStage<Void> send(OverlayForwardingFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (!authenticated || closed || channel == null || !channel.isActive()) {
            return CompletableFuture.failedFuture(new IllegalStateException("relay client is not connected"));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (pending.putIfAbsent(frame.messageId(), result) != null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("frame message is already pending"));
        }
        channel.writeAndFlush(Unpooled.wrappedBuffer(RelayWireCodec.encodeFrame(frame))).addListener(future -> {
            if (!future.isSuccess() && pending.remove(frame.messageId(), result)) {
                result.completeExceptionally(future.cause());
            }
        });
        return result;
    }

    /**
     * Closes the live connection and fails pending sends.
     */
    @Override
    public void close() {
        closed = true;
        failAll(new IllegalStateException("relay client closed"));
        Channel current = channel;
        if (current != null) {
            current.close().syncUninterruptibly();
        }
        MultiThreadIoEventLoopGroup currentGroup = group;
        if (currentGroup != null) {
            currentGroup.shutdownGracefully().syncUninterruptibly();
            group = null;
        }
    }

    private void failAll(Throwable failure) {
        connected.completeExceptionally(failure);
        pending.values().forEach(value -> value.completeExceptionally(failure));
        pending.clear();
    }

    private final class RelayClientHandler extends SimpleChannelInboundHandler<ByteBuf> {

        private RelayWireCodec.Hello hello;

        @Override
        public void channelActive(ChannelHandlerContext context) {
            channel = context.channel();
            try {
                byte[] encodedHello = RelayWireCodec.encodeHello(identity);
                hello = RelayWireCodec.decodeHello(encodedHello);
                context.writeAndFlush(Unpooled.wrappedBuffer(encodedHello));
            } catch (GeneralSecurityException | IOException failure) {
                failAll(failure);
                context.close();
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
            byte[] bytes = new byte[message.readableBytes()];
            message.readBytes(bytes);
            try {
                int type = RelayWireCodec.typeOf(bytes);
                if (!authenticated && type == 1) {
                    RelayWireCodec.HelloAck ack = RelayWireCodec.decodeHelloAck(bytes);
                    RelayWireCodec.verifyHelloAck(ack, hello, expectedRelayNodeId, expectedRelayPublicKey);
                    authenticated = true;
                    connected.complete(OverlayRelayClient.this);
                    return;
                }
                if (!authenticated) {
                    throw new IOException("relay frame arrived before authentication");
                }
                if (type == 3) {
                    OverlayForwardingFrame frame = OverlayForwardingFrame.decode(
                            RelayWireCodec.decodeFrame(bytes, 3));
                    inboundHandler.accept(frame);
                } else if (type == 4) {
                    RelayWireCodec.FrameAck ack = RelayWireCodec.decodeFrameAck(bytes);
                    CompletableFuture<Void> result = pending.remove(ack.messageId);
                    if (result != null) {
                        if (ack.status == 0) {
                            result.complete(null);
                        } else {
                            result.completeExceptionally(new IOException(
                                    "relay rejected frame with status " + ack.status));
                        }
                    }
                } else {
                    throw new IOException("unsupported relay client record");
                }
            } catch (Exception failure) {
                failAll(failure);
                context.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            failAll(new IllegalStateException("relay connection closed"));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            failAll(cause);
            context.close();
        }
    }
}
