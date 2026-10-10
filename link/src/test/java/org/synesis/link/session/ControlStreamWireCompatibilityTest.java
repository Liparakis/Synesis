package org.synesis.link.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.transport.control.NettyControlStream;

/**
 * Pins the session-scoped control payloads that {@link NettyControlStream} builds itself, by
 * driving the stream through an in-memory channel and reading the frames it writes.
 */
final class ControlStreamWireCompatibilityTest {

  private static final UUID SESSION = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
  private static final String SESSION_BYTES = "00112233445566778899aabbccddeeff";
  private static final String HEADER = "534c483101";
  private static final String READY =
      HEADER + "01" + "0100" + "00000014" + SESSION_BYTES + "00001000";

  private static final class Open {

    private final CompletableFuture<PeerSession> established = new CompletableFuture<>();
    private final PeerSession session = new PeerSession("local", "remote", new byte[]{1}, SESSION,
        1, 1, ProtocolVersion.V1, Instant.EPOCH);
    private final NettyControlStream stream = NettyControlStream.create(session, established,
        () -> { }, LivenessConfiguration.DEFAULT);
    private final EmbeddedChannel channel = new EmbeddedChannel(stream);

    String outbound() {
      ByteBuf frame = channel.readOutbound();
      try {
        byte[] bytes = new byte[frame.readableBytes()];
        frame.readBytes(bytes);
        return HexFormat.of().formatHex(bytes);
      } finally {
        frame.release();
      }
    }

    void inbound(String hexFrame) {
      channel.writeInbound(Unpooled.wrappedBuffer(HexFormat.of().parseHex(hexFrame)));
    }

    Open ready() {
      assertEquals(READY, outbound());
      inbound(READY);
      assertTrue(established.isDone() && !established.isCompletedExceptionally());
      return this;
    }
  }

  @Test
  void controlReadyIsSessionIdThenMaxPayload() {
    Open open = new Open();
    try {
      assertEquals(READY, open.outbound());
    } finally {
      open.channel.finishAndReleaseAll();
    }
  }

  @Test
  void goodbyeIsSessionIdThenOneReasonByte() {
    Open open = new Open().ready();
    try {
      open.stream.closeGracefully(SessionCloseReason.LIVENESS_EXPIRED);
      assertEquals(HEADER + "02" + "0100" + "00000011" + SESSION_BYTES + "05", open.outbound());
    } finally {
      open.channel.finishAndReleaseAll();
    }
  }

  @Test
  void goodbyeAckIsSessionIdOnly() {
    Open open = new Open().ready();
    try {
      open.inbound(HEADER + "02" + "0100" + "00000011" + SESSION_BYTES + "03");
      assertEquals(HEADER + "03" + "0100" + "00000010" + SESSION_BYTES, open.outbound());
      assertEquals(SessionCloseReason.REMOTE_REQUEST, open.stream.closeReason());
    } finally {
      open.channel.finishAndReleaseAll();
    }
  }

  @Test
  void protocolErrorIsSessionIdThenOneErrorByte() {
    Open malformed = new Open();
    try {
      assertEquals(READY, malformed.outbound());
      malformed.inbound("00");
      assertEquals(HEADER + "04" + "0100" + "00000011" + SESSION_BYTES + "01",
          malformed.outbound());
      assertTrue(malformed.established.isCompletedExceptionally());
    } finally {
      malformed.channel.finishAndReleaseAll();
    }

    Open repeatedReady = new Open().ready();
    try {
      repeatedReady.inbound(READY);
      assertEquals(HEADER + "04" + "0100" + "00000011" + SESSION_BYTES + "04",
          repeatedReady.outbound());
    } finally {
      repeatedReady.channel.finishAndReleaseAll();
    }
  }
}
