package org.synesis.link.transport.quic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.TraversalAnswer;
import org.synesis.link.protocol.TraversalOffer;

/**
 * Verifies offer/answer probes traverse the supplied Netty parent channels.
 */
final class NettyTraversalExchangeTest {

  private static CandidateDescriptor descriptor(NodeIdentity identity, int port, Instant issued,
      Instant expires)
      throws Exception {
    return CandidateDescriptor.create(identity, issued, expires,
        List.of(
            new Candidate(CandidateType.MANUAL, InetAddress.getByName("198.51.100.1"), port, 1)));
  }

  private static byte[] bytes(DatagramPacket packet) {
    ByteBuf content = packet.content();
    return io.netty.buffer.ByteBufUtil.getBytes(content, content.readerIndex(),
        content.readableBytes());
  }

  @Test
  void failsThePendingOfferWhenEveryWriteFailsWithoutWaitingForTheTimeout() throws Exception {
    NodeIdentity initiator = NodeIdentity.generate();
    Instant issued = Instant.parse("2026-09-08T08:00:00Z");
    TraversalOffer offer = TraversalOffer.create(initiator, NodeIdentity.generate().nodeId(),
        UUID.randomUUID(), new byte[TraversalOffer.DIGEST_BYTES], issued, issued.plusSeconds(30),
        new byte[TraversalOffer.NONCE_BYTES], descriptor(initiator, 4401, issued,
            issued.plusSeconds(30)));
    EmbeddedChannel channel = new EmbeddedChannel();
    NettyTraversalExchange exchange = new NettyTraversalExchange(channel, Duration.ofHours(1),
        null);
    IOException writeFailure = new IOException("send failed");
    channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
      @Override
      public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        ReferenceCountUtil.release(message);
        promise.setFailure(writeFailure);
      }
    });

    CompletableFuture<TraversalAnswer> result = exchange.sendOffer(offer,
        List.of(new Candidate(CandidateType.MANUAL, InetAddress.getByName("192.0.2.21"), 44_002,
            1))).toCompletableFuture();
    channel.runPendingTasks();

    ExecutionException failure = assertThrows(ExecutionException.class,
        () -> result.get(1, TimeUnit.SECONDS));
    assertSame(writeFailure, failure.getCause());
    exchange.close();
    channel.finishAndReleaseAll();
  }

  @Test
  void exchangesSignedRecordsBetweenIndependentParentChannels() throws Exception {
    NodeIdentity initiator = NodeIdentity.generate();
    NodeIdentity responder = NodeIdentity.generate();
    Instant issued = Instant.parse("2026-09-08T08:00:00Z");
    Instant expires = issued.plusSeconds(30);
    CandidateDescriptor initiatorDescriptor = descriptor(initiator, 4401, issued, expires);
    CandidateDescriptor responderDescriptor = descriptor(responder, 4402, issued, expires);
    TraversalOffer offer = TraversalOffer.create(initiator, responder.nodeId(), UUID.randomUUID(),
        new byte[TraversalOffer.DIGEST_BYTES], issued, expires,
        new byte[TraversalOffer.NONCE_BYTES],
        initiatorDescriptor);
    InetSocketAddress initiatorAddress = new InetSocketAddress("192.0.2.20", 44_001);
    InetSocketAddress responderAddress = new InetSocketAddress("192.0.2.21", 44_002);

    EmbeddedChannel initiatorChannel = new EmbeddedChannel();
    EmbeddedChannel responderChannel = new EmbeddedChannel();
    NettyTraversalExchange initiatorExchange = new NettyTraversalExchange(initiatorChannel,
        Duration.ofSeconds(1), null);
    NettyTraversalExchange responderExchange = new NettyTraversalExchange(responderChannel,
        Duration.ofSeconds(1), received -> {
      try {
        if (!received.verifyAt(issued.plusSeconds(1), TraversalOffer.DEFAULT_CLOCK_SKEW,
            responder.nodeId())) {
          return null;
        }
        return TraversalAnswer.create(responder, received, responderDescriptor,
            new byte[TraversalAnswer.NONCE_BYTES]);
      } catch (Exception failure) {
        return null;
      }
    });

    CompletableFuture<TraversalAnswer> result = initiatorExchange.sendOffer(offer,
            List.of(new Candidate(CandidateType.MANUAL, responderAddress.getAddress(),
                responderAddress.getPort(), 1)))
        .toCompletableFuture();
    initiatorChannel.runPendingTasks();
    DatagramPacket outboundOffer = initiatorChannel.readOutbound();
    assertNotNull(outboundOffer);
    assertEquals(responderAddress, outboundOffer.recipient());
    byte[] offerBytes = bytes(outboundOffer);
    outboundOffer.release();
    responderChannel.writeInbound(new DatagramPacket(Unpooled.wrappedBuffer(offerBytes),
        responderAddress, initiatorAddress));
    DatagramPacket outboundAnswer = responderChannel.readOutbound();
    assertNotNull(outboundAnswer);
    byte[] answerBytes = bytes(outboundAnswer);
    outboundAnswer.release();
    initiatorChannel.writeInbound(new DatagramPacket(Unpooled.wrappedBuffer(answerBytes),
        initiatorAddress, responderAddress));

    assertEquals(offer.digest().length, result.get().offerDigest().length);
    assertArrayEquals(offer.digest(), result.get().offerDigest());
    initiatorExchange.close();
    responderExchange.close();
    initiatorChannel.finishAndReleaseAll();
    responderChannel.finishAndReleaseAll();
  }
}
