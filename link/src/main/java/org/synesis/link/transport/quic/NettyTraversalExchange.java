package org.synesis.link.transport.quic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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
public final class NettyTraversalExchange
    extends NettyDatagramExchange<TraversalOffer, TraversalAnswer>
    implements AutoCloseable {

  private static final String HANDLER_NAME = "synesis-traversal-exchange";
  private static final int MAX_TARGETS = 32;
  private final Function<TraversalOffer, TraversalAnswer> responder;

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
    super(channel, timeout, "traversal", "traversal exchange closed");
    this.responder = responder;
    install(HANDLER_NAME);
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
    if (unavailable()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("traversal channel is closed"));
    }
    if (candidates.isEmpty() || candidates.size() > MAX_TARGETS) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("invalid traversal target bound"));
    }
    return begin(offer, "one traversal offer is already active", value -> send(value, candidates));
  }

  private void send(Pending<TraversalOffer, TraversalAnswer> value, List<Candidate> candidates) {
    if (current() != value) {
      return;
    }
    dispatch(value, "traversal answer timed out", value.state.encoded(),
        candidates.stream()
            .map(candidate -> new InetSocketAddress(candidate.address(), candidate.port()))
            .toList());
  }

  private static boolean isAnswer(ByteBuf content) {
    return content.getInt(content.readerIndex()) == 0x534C4132;
  }

  @Override
  boolean recognizes(ByteBuf content) {
    if (content.readableBytes() < 4) {
      return false;
    }
    int magic = content.getInt(content.readerIndex());
    return magic == 0x534C4F31 || magic == 0x534C4132;
  }

  @Override
  void onRecognized(DatagramPacket packet) {
    ByteBuf content = packet.content();
    byte[] encoded = ByteBufUtil.getBytes(content, content.readerIndex(), content.readableBytes());
    if (isAnswer(content)) {
      Pending<TraversalOffer, TraversalAnswer> value = current();
      if (value == null) {
        return;
      }
      try {
        TraversalAnswer answer = TraversalAnswer.decode(encoded);
        if (java.util.Arrays.equals(answer.offerDigest(), value.state.digest())) {
          complete(value, answer);
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
}
