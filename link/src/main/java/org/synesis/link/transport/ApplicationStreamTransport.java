package org.synesis.link.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.synesis.link.session.PeerSession;

/** Internal Netty transport for one bounded opaque application-stream exchange. */
final class ApplicationStreamTransport {
    private static final int MAX_STREAMS = 4;
    private static final int OPERATION_TIMEOUT_MILLIS = 5_000;

    private ApplicationStreamTransport() { }

    static CompletionStage<byte[]> open(ChannelHandlerContext controlContext, byte[] payload,
            AtomicInteger active) {
        if (payload.length > ApplicationStreamCodec.MAX_PAYLOAD_BYTES) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "application payload exceeds supported bound"));
        }
        if (active.incrementAndGet() > MAX_STREAMS) {
            active.decrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("application stream limit exceeded"));
        }
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) active.decrementAndGet(); };
        if (!(controlContext.channel().parent() instanceof QuicChannel connection)) {
            release.run();
            return CompletableFuture.failedFuture(new IllegalStateException("QUIC connection is unavailable"));
        }
        io.netty.util.concurrent.Future<QuicStreamChannel> created = connection.createStream(QuicStreamType.BIDIRECTIONAL,
                clientInitializer(payload, result, release));
        created.addListener(future -> {
            if (!future.isSuccess()) {
                release.run();
                result.completeExceptionally(future.cause());
            }
        });
        return result;
    }

    static void accept(ChannelHandlerContext context, ChannelHandler oldHandler, PeerSession session,
            PeerSession.ApplicationStreamHandler handler, AtomicInteger active, byte[] firstFrame) {
        if (handler == null || active.incrementAndGet() > MAX_STREAMS) {
            if (handler != null) active.decrementAndGet();
            context.close();
            return;
        }
        ServerHandler server = new ServerHandler(session, handler, active);
        context.pipeline().replace(oldHandler, "synesis-application", server);
        server.acceptFirst(context, firstFrame);
    }

    private static ChannelHandler clientInitializer(byte[] payload, CompletableFuture<byte[]> result,
            Runnable release) {
        return new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel channel) {
                channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                        ApplicationStreamCodec.MAX_FRAME_BYTES + Integer.BYTES, 0, Integer.BYTES, 0,
                        Integer.BYTES));
                channel.pipeline().addLast(new LengthFieldPrepender(Integer.BYTES));
                channel.pipeline().addLast(new ClientHandler(payload, result, release));
            }
        };
    }

    private static final class ClientHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final byte[] payload;
        private final CompletableFuture<byte[]> result;
        private final Runnable release;
        private ScheduledFuture<?> timeout;

        private ClientHandler(byte[] payload, CompletableFuture<byte[]> result, Runnable release) {
            this.payload = payload.clone();
            this.result = result;
            this.release = release;
        }

        @Override
        public void channelActive(ChannelHandlerContext context) {
            timeout = context.executor().schedule(() -> {
                if (result.completeExceptionally(new IllegalStateException("application stream timed out"))) {
                    context.close();
                }
            }, OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            context.writeAndFlush(Unpooled.wrappedBuffer(ApplicationStreamCodec.encode(payload)));
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
            try {
                byte[] frame = read(message);
                if (result.complete(ApplicationStreamCodec.decode(frame))) context.close();
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
                context.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            cancelTimeout();
            release.run();
            if (!result.isDone()) result.completeExceptionally(new IllegalStateException("application stream closed"));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            result.completeExceptionally(cause);
            context.close();
        }

        private void cancelTimeout() {
            if (timeout != null) timeout.cancel(false);
        }
    }

    private static final class ServerHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final PeerSession session;
        private final PeerSession.ApplicationStreamHandler handler;
        private final AtomicInteger active;
        private boolean responded;
        private ScheduledFuture<?> timeout;

        private ServerHandler(PeerSession session, PeerSession.ApplicationStreamHandler handler,
                AtomicInteger active) {
            this.session = session;
            this.handler = handler;
            this.active = active;
        }

        private void acceptFirst(ChannelHandlerContext context, byte[] frame) { respond(context, frame); }

        @Override
        protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
            respond(context, read(message));
        }

        private void respond(ChannelHandlerContext context, byte[] frame) {
            if (responded || !session.isUsable()) {
                context.close();
                return;
            }
            responded = true;
            final byte[] payload;
            try {
                payload = ApplicationStreamCodec.decode(frame);
            } catch (RuntimeException exception) {
                context.close();
                return;
            }
            CompletionStage<byte[]> response;
            try {
                response = handler.handle(session.remoteNodeId(), payload);
            } catch (RuntimeException exception) {
                context.close();
                return;
            }
            if (response == null) {
                context.close();
                return;
            }
            timeout = context.executor().schedule(() -> context.close(), OPERATION_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
            response.whenComplete((value, failure) -> context.executor().execute(() -> {
                if (failure != null || value == null || value.length > ApplicationStreamCodec.MAX_PAYLOAD_BYTES) {
                    context.close();
                    return;
                }
                if (timeout != null) timeout.cancel(false);
                context.writeAndFlush(Unpooled.wrappedBuffer(ApplicationStreamCodec.encode(value)))
                        .addListener(ChannelFutureListener.CLOSE);
            }));
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            if (timeout != null) timeout.cancel(false);
            active.decrementAndGet();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) { context.close(); }
    }

    private static byte[] read(ByteBuf message) {
        if (message.readableBytes() > ApplicationStreamCodec.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("application frame is oversized");
        }
        byte[] bytes = new byte[message.readableBytes()];
        message.readBytes(bytes);
        return bytes;
    }
}
