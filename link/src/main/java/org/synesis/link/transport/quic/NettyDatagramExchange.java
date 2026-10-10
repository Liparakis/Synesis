package org.synesis.link.transport.quic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One pending datagram exchange on a UDP channel that Link's QUIC codec also uses.
 *
 * <p>The handler sits before the QUIC codec. A datagram it does not recognize is passed on
 * untouched, so QUIC keeps ownership of its own traffic. At most one exchange is pending; the slot
 * is cleared by completion, timeout, close, or channel inactivity. The handler owns no socket and
 * never closes the parent channel.
 *
 * @param <S> per-exchange state a subclass needs to match a reply
 * @param <R> value the exchange completes with
 */
abstract class NettyDatagramExchange<S, R> extends SimpleChannelInboundHandler<DatagramPacket>
    implements AutoCloseable {

  final Channel channel;
  private final Duration timeout;
  private final String closeMessage;
  private final AtomicReference<Pending<S, R>> pending = new AtomicReference<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  NettyDatagramExchange(Channel channel, Duration timeout, String kind, String closeMessage) {
    this.channel = Objects.requireNonNull(channel, "channel");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException(kind + " timeout must be positive");
    }
    this.closeMessage = closeMessage;
  }

  /**
   * Installs this handler at the front of the pipeline. Subclasses call it last, once their own
   * fields are set, because the handler can receive a datagram as soon as it is installed.
   *
   * <p>The handler is added on the event loop and this method waits for it. Added from another
   * thread, Netty only queues the activation, and it reads sockets before it runs queued tasks:
   * a datagram already waiting would skip the handler, reach the QUIC codec, and be dropped.
   */
  final void install(String handlerName) {
    Runnable add = () -> channel.pipeline().addFirst(handlerName, this);
    if (channel.eventLoop().inEventLoop()) {
      add.run();
    } else {
      channel.eventLoop().submit(add).syncUninterruptibly();
    }
  }

  /**
   * Whether a datagram belongs to this exchange; anything else is forwarded.
   */
  abstract boolean recognizes(ByteBuf content);

  /**
   * Handles a recognized datagram: match it against {@link #current()} and complete it, answer it,
   * or drop it.
   */
  abstract void onRecognized(DatagramPacket packet);

  final boolean unavailable() {
    return closed.get() || !channel.isOpen();
  }

  final Pending<S, R> current() {
    return pending.get();
  }

  /**
   * Claims the pending slot and runs {@code send} on the event loop.
   */
  final CompletionStage<R> begin(S state, String busyMessage, Consumer<Pending<S, R>> send) {
    Pending<S, R> value = new Pending<>(state);
    if (!pending.compareAndSet(null, value)) {
      return CompletableFuture.failedFuture(new IllegalStateException(busyMessage));
    }
    try {
      channel.eventLoop().execute(() -> send.accept(value));
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

  /**
   * Arms the timeout and writes {@code payload} to every target. The exchange fails at once only
   * when every write failed; a target that accepted the write can still answer.
   */
  final void dispatch(Pending<S, R> value, String timeoutMessage, byte[] payload,
      List<InetSocketAddress> targets) {
    value.timer = channel.eventLoop().schedule(
        () -> fail(value, new TimeoutException(timeoutMessage)),
        timeout.toNanos(), TimeUnit.NANOSECONDS);
    AtomicInteger failedWrites = new AtomicInteger();
    for (InetSocketAddress target : targets) {
      channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(payload.clone()), target))
          .addListener(future -> {
            if (!future.isSuccess() && failedWrites.incrementAndGet() == targets.size()) {
              fail(value, future.cause());
            }
          });
    }
  }

  final void complete(Pending<S, R> value, R result) {
    if (pending.compareAndSet(value, null)) {
      value.result.complete(result);
    }
  }

  final void fail(Pending<S, R> value, Throwable failure) {
    if (pending.compareAndSet(value, null)) {
      value.result.completeExceptionally(failure);
    }
  }

  @Override
  protected final void channelRead0(ChannelHandlerContext context, DatagramPacket packet) {
    if (!recognizes(packet.content())) {
      context.fireChannelRead(packet.retain());
      return;
    }
    onRecognized(packet);
  }

  /**
   * Removes the handler and fails any pending exchange without closing the parent channel.
   */
  @Override
  public final void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    Pending<S, R> value = pending.getAndSet(null);
    if (value != null) {
      value.result.completeExceptionally(new CancellationException(closeMessage));
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
  public final void channelInactive(ChannelHandlerContext context) throws Exception {
    Pending<S, R> value = pending.getAndSet(null);
    if (value != null) {
      value.result.completeExceptionally(
          new IllegalStateException("parent UDP channel is inactive"));
    }
    super.channelInactive(context);
  }

  static final class Pending<S, R> {

    final S state;
    private final CompletableFuture<R> result = new CompletableFuture<>();
    private volatile ScheduledFuture<?> timer;

    private Pending(S state) {
      this.state = state;
    }
  }
}
