package org.synesis.link.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.synesis.link.demo.DemoWorkCodec;
import org.synesis.link.demo.DemoWorkRequest;
import org.synesis.link.demo.DemoWorkResult;
import org.synesis.link.demo.DemoWorkStatus;
import org.synesis.link.session.PeerSession;

/** Internal Netty adapter for one bounded demo request/result stream. */
final class DemoWorkTransport {
    private static final int MAX_STREAMS = 4;

    private DemoWorkTransport() { }

    static CompletionStage<DemoWorkResult> open(ChannelHandlerContext controlContext,
            DemoWorkRequest request, AtomicInteger active) {
        if (active.incrementAndGet() > MAX_STREAMS) {
            active.decrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("demo stream limit exceeded"));
        }
        CompletableFuture<DemoWorkResult> result = new CompletableFuture<>();
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) active.decrementAndGet(); };
        if (!(controlContext.channel().parent() instanceof QuicChannel connection)) {
            release.run();
            return CompletableFuture.failedFuture(new IllegalStateException("QUIC connection is unavailable"));
        }
        io.netty.util.concurrent.Future<QuicStreamChannel> created = connection.createStream(QuicStreamType.BIDIRECTIONAL,
                clientInitializer(request, result, release));
        created.addListener(future -> {
            if (!future.isSuccess()) {
                release.run();
                result.completeExceptionally(future.cause());
            }
        });
        return result;
    }

    static void accept(ChannelHandlerContext context, io.netty.channel.ChannelHandler oldHandler,
            PeerSession session, Set<UUID> seen, AtomicInteger active, byte[] firstFrame) {
        if (active.incrementAndGet() > MAX_STREAMS) {
            active.decrementAndGet();
            context.close();
            return;
        }
        ServerHandler server = new ServerHandler(session, seen, active);
        context.pipeline().replace(oldHandler, "demo-work", server);
        server.acceptFirst(context, firstFrame);
    }

    private static ChannelHandler clientInitializer(DemoWorkRequest request,
            CompletableFuture<DemoWorkResult> result, Runnable release) {
        return new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel channel) {
                channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                        DemoWorkCodec.MAX_FRAME_BYTES + Integer.BYTES, 0, Integer.BYTES, 0,
                        Integer.BYTES));
                channel.pipeline().addLast(new LengthFieldPrepender(Integer.BYTES));
                channel.pipeline().addLast(new ClientHandler(request, result, release));
            }
        };
    }

    private static final class ClientHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final DemoWorkRequest request;
        private final CompletableFuture<DemoWorkResult> result;
        private final Runnable release;

        private ClientHandler(DemoWorkRequest request, CompletableFuture<DemoWorkResult> result, Runnable release) {
            this.request = request;
            this.result = result;
            this.release = release;
        }

        @Override
        public void channelActive(ChannelHandlerContext context) {
            context.writeAndFlush(Unpooled.wrappedBuffer(DemoWorkCodec.encodeRequest(request)));
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
            try {
                DemoWorkResult value = DemoWorkCodec.decodeResult(read(message));
                if (!request.requestId().equals(value.requestId())) {
                    throw new IllegalArgumentException("demo result correlation mismatch");
                }
                result.complete(value);
                context.close();
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
                context.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            release.run();
            if (!result.isDone()) result.completeExceptionally(new IllegalStateException("demo stream closed"));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            result.completeExceptionally(cause);
            context.close();
        }
    }

    private static final class ServerHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final PeerSession session;
        private final Set<UUID> seen;
        private final AtomicInteger active;

        private ServerHandler(PeerSession session, Set<UUID> seen, AtomicInteger active) {
            this.session = session;
            this.seen = seen;
            this.active = active;
        }

        private void acceptFirst(ChannelHandlerContext context, byte[] frame) { respond(context, frame); }

        @Override
        protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
            respond(context, read(message));
        }

        private void respond(ChannelHandlerContext context, byte[] frame) {
            try {
                if (!session.isUsable()) throw new IllegalStateException("demo stream before control readiness");
                DemoWorkRequest request = DemoWorkCodec.decodeRequest(frame);
                boolean fresh = seen.add(request.requestId());
                DemoWorkStatus status = fresh ? DemoWorkStatus.OK : DemoWorkStatus.DUPLICATE_REQUEST;
                String message = fresh ? "accepted" : "duplicate-request";
                context.writeAndFlush(Unpooled.wrappedBuffer(DemoWorkCodec.encodeResult(
                        new DemoWorkResult(request.requestId(), status, message))))
                        .addListener(ChannelFutureListener.CLOSE);
            } catch (RuntimeException exception) {
                context.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) { active.decrementAndGet(); }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) { context.close(); }
    }

    private static byte[] read(ByteBuf message) {
        if (message.readableBytes() > DemoWorkCodec.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("demo frame is oversized");
        }
        byte[] bytes = new byte[message.readableBytes()];
        message.readBytes(bytes);
        return bytes;
    }

}
