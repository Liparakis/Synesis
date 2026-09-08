package org.synesis.link.transport.quic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.synesis.link.candidate.CandidateCancellation;
import org.synesis.link.candidate.StunBindingMessage;
import org.synesis.link.candidate.StunBindingTransport;

/**
 * Same-socket Netty adapter for bounded STUN Binding transactions.
 *
 * <p>The adapter is inserted before Link's QUIC codec on the caller-provided
 * parent datagram channel. It consumes only STUN-shaped datagrams and forwards
 * all other datagrams, allowing the existing QUIC dispatcher to retain
 * ownership of QUIC traffic. The adapter owns no socket and does not close the
 * parent channel.
 */
public final class NettyStunBindingTransport extends SimpleChannelInboundHandler<DatagramPacket>
        implements StunBindingTransport, AutoCloseable {

    private static final String HANDLER_NAME = "synesis-stun-binding";
    private final Channel channel;
    private final Duration timeout;
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Installs a same-socket STUN adapter at the front of a Netty channel pipeline.
     *
     * @param channel parent UDP channel already used or reserved for QUIC
     * @param timeout per-request timeout
     */
    public NettyStunBindingTransport(Channel channel, Duration timeout) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("STUN timeout must be positive");
        }
        channel.pipeline().addFirst(HANDLER_NAME, this);
    }

    /**
     * Sends one Binding request through the provided parent channel.
     *
     * @param server       configured STUN server endpoint
     * @param request      canonical 20-byte Binding request
     * @param cancellation cooperative cancellation signal
     * @return complete response bytes
     */
    @Override
    public java.util.concurrent.CompletionStage<byte[]> request(InetSocketAddress server, byte[] request,
            CandidateCancellation cancellation) {
        Objects.requireNonNull(server, "STUN server");
        Objects.requireNonNull(request, "STUN request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (request.length != 20 || cancellation.isCancelled()) {
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(
                    "STUN request is cancelled or malformed"));
        }
        if (closed.get() || !channel.isOpen()) {
            return CompletableFuture.failedFuture(new IllegalStateException("parent UDP channel is closed"));
        }
        byte[] transactionId = Arrays.copyOfRange(request, 8, 20);
        Pending value = new Pending(server, transactionId);
        if (!pending.compareAndSet(null, value)) {
            return CompletableFuture.failedFuture(new IllegalStateException("one STUN request is already active"));
        }
        try {
            channel.eventLoop().execute(() -> send(value, request.clone(), cancellation));
        } catch (RuntimeException exception) {
            fail(value, exception);
        }
        value.result.whenComplete((ignored, ignoredFailure) -> {
            ScheduledFuture<?> timer = value.timer;
            if (timer != null) {
                timer.cancel(false);
            }
            pending.compareAndSet(value, null);
        });
        return value.result;
    }

    private void send(Pending value, byte[] request, CandidateCancellation cancellation) {
        if (pending.get() != value || cancellation.isCancelled()) {
            fail(value, new java.util.concurrent.CancellationException("STUN request cancelled"));
            return;
        }
        value.timer = channel.eventLoop().schedule(
                () -> fail(value, new TimeoutException("STUN Binding response timed out")),
                timeout.toNanos(), TimeUnit.NANOSECONDS);
        channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(request), value.server))
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        fail(value, future.cause());
                    }
                });
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, DatagramPacket packet) {
        ByteBuf content = packet.content();
        if (!looksLikeStun(content)) {
            context.fireChannelRead(packet.retain());
            return;
        }
        Pending value = pending.get();
        if (value == null || !value.server.equals(packet.sender())
                || !matchesTransaction(content, value.transactionId)) {
            return;
        }
        fail(value, ByteBufUtil.getBytes(content, content.readerIndex(), content.readableBytes()), null);
    }

    private static boolean looksLikeStun(ByteBuf content) {
        return content.readableBytes() >= 20
                && (content.getUnsignedShort(content.readerIndex()) & 0xC000) == 0
                && content.getInt(content.readerIndex() + 4) == StunBindingMessage.MAGIC_COOKIE;
    }

    private static boolean matchesTransaction(ByteBuf content, byte[] transactionId) {
        byte[] actual = new byte[StunBindingMessage.TRANSACTION_ID_BYTES];
        content.getBytes(content.readerIndex() + 8, actual);
        return Arrays.equals(actual, transactionId);
    }

    private void fail(Pending value, Throwable failure) {
        if (pending.compareAndSet(value, null)) {
            value.result.completeExceptionally(failure);
        }
    }

    private void fail(Pending value, byte[] response, Void ignored) {
        if (pending.compareAndSet(value, null)) {
            value.result.complete(response);
        }
    }

    /**
     * Removes the handler and fails any pending request without closing the parent channel.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Pending value = pending.getAndSet(null);
        if (value != null) {
            value.result.completeExceptionally(new java.util.concurrent.CancellationException(
                    "STUN transport closed"));
        }
        Runnable remove = () -> {
            if (channel.pipeline().context(this) != null) {
                channel.pipeline().remove(this);
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            remove.run();
        } else {
            channel.eventLoop().execute(remove);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        Pending value = pending.getAndSet(null);
        if (value != null) {
            value.result.completeExceptionally(new IllegalStateException("parent UDP channel is inactive"));
        }
        super.channelInactive(context);
    }

    private static final class Pending {

        private final InetSocketAddress server;
        private final byte[] transactionId;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private volatile ScheduledFuture<?> timer;

        private Pending(InetSocketAddress server, byte[] transactionId) {
            this.server = server;
            this.transactionId = transactionId;
        }
    }
}
