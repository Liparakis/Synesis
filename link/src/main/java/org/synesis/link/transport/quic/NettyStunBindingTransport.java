package org.synesis.link.transport.quic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.synesis.link.candidate.CandidateCancellation;
import org.synesis.link.candidate.StunBindingMessage;
import org.synesis.link.candidate.StunBindingTransport;

/**
 * Same-socket Netty adapter for bounded STUN Binding transactions.
 *
 * <p>The adapter is inserted before Link's QUIC codec on the caller-provided
 * parent datagram channel. It consumes only STUN-shaped datagrams and forwards all other datagrams,
 * allowing the existing QUIC dispatcher to retain ownership of QUIC traffic. The adapter owns no
 * socket and does not close the parent channel.
 */
public final class NettyStunBindingTransport
    extends NettyDatagramExchange<NettyStunBindingTransport.Target, byte[]>
    implements StunBindingTransport, AutoCloseable {

  private static final String HANDLER_NAME = "synesis-stun-binding";

  /**
   * Installs a same-socket STUN adapter at the front of a Netty channel pipeline.
   *
   * @param channel parent UDP channel already used or reserved for QUIC
   * @param timeout per-request timeout
   */
  public NettyStunBindingTransport(Channel channel, Duration timeout) {
    super(channel, timeout, "STUN", "STUN transport closed");
    install(HANDLER_NAME);
  }

  private static boolean matchesTransaction(ByteBuf content, byte[] transactionId) {
    byte[] actual = new byte[StunBindingMessage.TRANSACTION_ID_BYTES];
    content.getBytes(content.readerIndex() + 8, actual);
    return Arrays.equals(actual, transactionId);
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
  public java.util.concurrent.CompletionStage<byte[]> request(InetSocketAddress server,
      byte[] request,
      CandidateCancellation cancellation) {
    Objects.requireNonNull(server, "STUN server");
    Objects.requireNonNull(request, "STUN request");
    Objects.requireNonNull(cancellation, "cancellation");
    if (request.length != 20 || cancellation.isCancelled()) {
      return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(
          "STUN request is cancelled or malformed"));
    }
    if (unavailable()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("parent UDP channel is closed"));
    }
    byte[] transactionId = Arrays.copyOfRange(request, 8, 20);
    byte[] datagram = request.clone();
    return begin(new Target(server, transactionId), "one STUN request is already active",
        value -> send(value, datagram, cancellation));
  }

  private void send(Pending<Target, byte[]> value, byte[] request,
      CandidateCancellation cancellation) {
    if (current() != value || cancellation.isCancelled()) {
      fail(value, new java.util.concurrent.CancellationException("STUN request cancelled"));
      return;
    }
    dispatch(value, "STUN Binding response timed out", request, List.of(value.state.server()));
  }

  @Override
  boolean recognizes(ByteBuf content) {
    return content.readableBytes() >= 20
        && (content.getUnsignedShort(content.readerIndex()) & 0xC000) == 0
        && content.getInt(content.readerIndex() + 4) == StunBindingMessage.MAGIC_COOKIE;
  }

  @Override
  void onRecognized(DatagramPacket packet) {
    ByteBuf content = packet.content();
    Pending<Target, byte[]> value = current();
    if (value == null || !value.state.server().equals(packet.sender())
        || !matchesTransaction(content, value.state.transactionId())) {
      return;
    }
    complete(value,
        ByteBufUtil.getBytes(content, content.readerIndex(), content.readableBytes()));
  }

  /**
   * The server a Binding request went to and the transaction id its response must echo.
   *
   * @param server        configured STUN server endpoint
   * @param transactionId 12-byte transaction id of the request
   */
  record Target(InetSocketAddress server, byte[] transactionId) {

  }
}
