package org.synesis.link.transport.control;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.synesis.link.session.PeerSession;

/**
 * One bounded request and one reply over a bidirectional QUIC stream, shared by the application
 * and demo-work streams. The client holds a stream slot until the stream ends and gives up after
 * {@link #OPERATION_TIMEOUT_MILLIS}; the server answers once, within the same deadline, and closes.
 */
@SuppressWarnings("resource")
final class StreamExchange {

  static final int MAX_STREAMS = 4;
  static final int OPERATION_TIMEOUT_MILLIS = 5_000;

  private StreamExchange() {
  }

  /**
   * Takes one of the {@link #MAX_STREAMS} slots.
   *
   * @return action releasing the slot at most once, or {@code null} when no slot is free
   */
  static Runnable acquire(AtomicInteger active) {
    if (active.incrementAndGet() > MAX_STREAMS) {
      active.decrementAndGet();
      return null;
    }
    AtomicBoolean released = new AtomicBoolean();
    return () -> {
      if (released.compareAndSet(false, true)) {
        active.decrementAndGet();
      }
    };
  }

  /**
   * Opens a stream, sends the request frame, and completes with the decoded reply.
   *
   * @param label   stream kind named in failure messages
   * @param request produces the unprefixed request frame once the stream is active
   * @param reply   decodes the unprefixed reply frame
   */
  static <R> CompletionStage<R> open(ChannelHandlerContext controlContext, AtomicInteger active,
      String label, int maxFrameBytes, Supplier<byte[]> request, Function<byte[], R> reply) {
    Runnable release = acquire(active);
    if (release == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException(label + " stream limit exceeded"));
    }
    CompletableFuture<R> result = new CompletableFuture<>();
    if (!(controlContext.channel()
        .parent() instanceof QuicChannel connection)) {
      release.run();
      return CompletableFuture.failedFuture(
          new IllegalStateException("QUIC connection is unavailable"));
    }
    io.netty.util.concurrent.Future<QuicStreamChannel> created = connection.createStream(
        QuicStreamType.BIDIRECTIONAL, new ChannelInitializer<QuicStreamChannel>() {
          @Override
          protected void initChannel(QuicStreamChannel channel) {
            LengthPrefixedFrames.install(channel.pipeline(), maxFrameBytes);
            channel.pipeline()
                .addLast(new ClientHandler<>(label, maxFrameBytes, request, reply, result,
                    release));
          }
        });
    created.addListener(future -> {
      if (!future.isSuccess()) {
        release.run();
        result.completeExceptionally(future.cause());
      }
    });
    return result;
  }

  /**
   * Takes a slot, replaces the bootstrap handler with the stream owner, and serves the first frame.
   *
   * @param serve maps the unprefixed request frame to the unprefixed reply frame
   */
  static void accept(ChannelHandlerContext context, ChannelHandler oldHandler, String handlerName,
      PeerSession session, AtomicInteger active, String label, int maxFrameBytes,
      Function<byte[], CompletionStage<byte[]>> serve, byte[] firstFrame) {
    Runnable release = acquire(active);
    if (release == null) {
      context.close();
      return;
    }
    ServerHandler server = new ServerHandler(label, maxFrameBytes, session, serve, release);
    context.pipeline()
        .replace(oldHandler, handlerName, server);
    server.respond(context, firstFrame);
  }

  static final class ClientHandler<R> extends SimpleChannelInboundHandler<ByteBuf> {

    private final String label;
    private final int maxFrameBytes;
    private final Supplier<byte[]> request;
    private final Function<byte[], R> reply;
    private final CompletableFuture<R> result;
    private final Runnable release;
    private ScheduledFuture<?> timeout;

    ClientHandler(String label, int maxFrameBytes, Supplier<byte[]> request,
        Function<byte[], R> reply, CompletableFuture<R> result, Runnable release) {
      this.label = label;
      this.maxFrameBytes = maxFrameBytes;
      this.request = request;
      this.reply = reply;
      this.result = result;
      this.release = release;
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
      timeout = context.executor()
          .schedule(() -> {
            if (result.completeExceptionally(
                new IllegalStateException(label + " stream timed out"))) {
              context.close();
            }
          }, OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      context.writeAndFlush(Unpooled.wrappedBuffer(request.get()));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
      try {
        result.complete(reply.apply(
            LengthPrefixedFrames.read(message, maxFrameBytes, label + " frame is oversized")));
      } catch (RuntimeException exception) {
        result.completeExceptionally(exception);
      }
      context.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
      if (timeout != null) {
        timeout.cancel(false);
      }
      release.run();
      if (!result.isDone()) {
        result.completeExceptionally(new IllegalStateException(label + " stream closed"));
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
      result.completeExceptionally(cause);
      context.close();
    }
  }

  private static final class ServerHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final String label;
    private final int maxFrameBytes;
    private final PeerSession session;
    private final Function<byte[], CompletionStage<byte[]>> serve;
    private final Runnable release;
    private boolean responded;
    private ScheduledFuture<?> timeout;

    private ServerHandler(String label, int maxFrameBytes, PeerSession session,
        Function<byte[], CompletionStage<byte[]>> serve, Runnable release) {
      this.label = label;
      this.maxFrameBytes = maxFrameBytes;
      this.session = session;
      this.serve = serve;
      this.release = release;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
      respond(context,
          LengthPrefixedFrames.read(message, maxFrameBytes, label + " frame is oversized"));
    }

    private void respond(ChannelHandlerContext context, byte[] frame) {
      if (responded || !session.isUsable()) {
        context.close();
        return;
      }
      responded = true;
      CompletionStage<byte[]> response;
      try {
        response = serve.apply(frame);
      } catch (RuntimeException exception) {
        context.close();
        return;
      }
      if (response == null) {
        context.close();
        return;
      }
      timeout = context.executor()
          .schedule(() -> context.close(), OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      response.whenComplete((value, failure) -> context.executor()
          .execute(() -> {
            if (failure != null || value == null) {
              context.close();
              return;
            }
            timeout.cancel(false);
            context.writeAndFlush(Unpooled.wrappedBuffer(value))
                .addListener(ChannelFutureListener.CLOSE);
          }));
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
      if (timeout != null) {
        timeout.cancel(false);
      }
      release.run();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
      context.close();
    }
  }
}
