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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.protocol.TraversalAnswer;
import org.synesis.link.protocol.TraversalOffer;

/**
 * Same-socket bounded transport for signed traversal offer/answer probes.
 *
 * <p>The exchange is intentionally small and rendezvous-free: an initiator
 * sends its signed offer to advertised candidates, and a responder answers to the source endpoint
 * observed on the same UDP channel. Non-traversal datagrams are forwarded to the existing QUIC
 * codec. The class owns no socket.
 */
public final class NettyTraversalExchange extends SimpleChannelInboundHandler<DatagramPacket>
    implements AutoCloseable {

  private static final String HANDLER_NAME = "synesis-traversal-exchange";
  private static final int MAX_TARGETS = 32;
  private final Channel channel;
  private final Duration timeout;
  private final Function<TraversalOffer, TraversalAnswer> responder;
  private final AtomicReference<Pending> pending = new AtomicReference<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * Installs a traversal exchange handler on an existing parent UDP channel.
   *
   * @param channel   parent channel also used by Link QUIC
   * @param timeout   bounded probe timeout
   * @param responder optional responder callback; {@code null} makes this an initiator-only
   *                  exchange
   */
  public NettyTraversalExchange(Channel channel, Duration timeout,
      Function<TraversalOffer, TraversalAnswer> responder) {
    this.channel = Objects.requireNonNull(channel, "channel");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("traversal timeout must be positive");
    }
    this.responder = responder;
    channel.pipeline().addFirst(HANDLER_NAME, this);
  }

  private static boolean looksLikeTraversal(ByteBuf content) {
    if (content.readableBytes() < 4) {
      return false;
    }
    int magic = content.getInt(content.readerIndex());
    return magic == 0x534C4F31 || magic == 0x534C4132;
  }

  private static boolean isAnswer(ByteBuf content) {
    return content.getInt(content.readerIndex()) == 0x534C4132;
  }

  /**
   * Sends one offer to a bounded set of candidate endpoints.
   *
   * @param offer      signed traversal offer
   * @param candidates remote candidate endpoints
   * @return first answer bound to the exact offer
   */
  public java.util.concurrent.CompletionStage<TraversalAnswer> sendOffer(TraversalOffer offer,
      List<Candidate> candidates) {
    Objects.requireNonNull(offer, "offer");
    Objects.requireNonNull(candidates, "candidates");
    if (closed.get() || !channel.isOpen()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("traversal channel is closed"));
    }
    if (candidates.isEmpty() || candidates.size() > MAX_TARGETS) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("invalid traversal target bound"));
    }
    Pending value = new Pending(offer);
    if (!pending.compareAndSet(null, value)) {
      return CompletableFuture.failedFuture(new IllegalStateException(
          "one traversal offer is already active"));
    }
    try {
      channel.eventLoop().execute(() -> send(value, candidates));
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

  private void send(Pending value, List<Candidate> candidates) {
    if (pending.get() != value) {
      return;
    }
    value.timer = channel.eventLoop().schedule(
        () -> fail(value, new java.util.concurrent.TimeoutException("traversal answer timed out")),
        timeout.toNanos(), TimeUnit.NANOSECONDS);
    byte[] encoded = value.offer.encoded();
    for (Candidate candidate : candidates) {
      channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(encoded.clone()),
          new InetSocketAddress(candidate.address(), candidate.port())));
    }
  }

  @Override
  protected void channelRead0(ChannelHandlerContext context, DatagramPacket packet) {
    ByteBuf content = packet.content();
    if (!looksLikeTraversal(content)) {
      context.fireChannelRead(packet.retain());
      return;
    }
    byte[] encoded = ByteBufUtil.getBytes(content, content.readerIndex(), content.readableBytes());
    if (isAnswer(content)) {
      Pending value = pending.get();
      if (value == null) {
        return;
      }
      try {
        TraversalAnswer answer = TraversalAnswer.decode(encoded);
        if (java.util.Arrays.equals(answer.offerDigest(), value.offer.digest())) {
          if (pending.compareAndSet(value, null)) {
            value.result.complete(answer);
          }
        }
      } catch (java.io.IOException ignored) {
        // A malformed traversal datagram is untrusted input and is discarded.
      }
      return;
    }
    if (responder == null || packet.sender() == null) {
      return;
    }
    try {
      TraversalOffer offer = TraversalOffer.decode(encoded);
      TraversalAnswer answer = responder.apply(offer);
      if (answer != null) {
        channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(answer.encoded()),
            packet.sender()));
      }
    } catch (RuntimeException | java.io.IOException ignored) {
      // Invalid or unauthorized offers do not reach the QUIC pipeline.
    }
  }

  private void fail(Pending value, Throwable failure) {
    if (pending.compareAndSet(value, null)) {
      value.result.completeExceptionally(failure);
    }
  }

  /**
   * Removes this handler and fails any pending exchange without closing the parent channel.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    Pending value = pending.getAndSet(null);
    if (value != null) {
      value.result.completeExceptionally(new CancellationException("traversal exchange closed"));
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
      value.result.completeExceptionally(
          new IllegalStateException("parent UDP channel is inactive"));
    }
    super.channelInactive(context);
  }

  private static final class Pending {

    private final TraversalOffer offer;
    private final CompletableFuture<TraversalAnswer> result = new CompletableFuture<>();
    private volatile ScheduledFuture<?> timer;

    private Pending(TraversalOffer offer) {
      this.offer = offer;
    }
  }
}
