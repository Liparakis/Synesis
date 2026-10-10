package org.synesis.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayForwardingFrame;
import org.synesis.link.overlay.OverlayKeyAgreement;
import org.synesis.link.overlay.OverlayRelay;
import org.synesis.link.overlay.OverlayRelayException;
import org.synesis.link.overlay.OverlayRelayPolicy;

/**
 * Pins the exact SLR1 bytes of every relay record and the frame-ack status the server produces.
 * Vectors were produced once; hello nonces and logical-record keys are random, so the bytes are
 * embedded rather than regenerated.
 */
final class RelayWireCompatibilityTest {

  private static final String RELAY_NODE = "sl1-5fadb08c8e589c2cfc223d0c5430208821e14623ca02fd091b4cb629c6e57021";
  private static final String CLIENT_NODE = "sl1-30f776e24cae6f0d002c0ddf40f7744046d86a02b04f241115d4fc57b3543221";
  private static final String RELAY_PUBLIC = "302a300506032b65700321003739791a1641c352f000b7e6397299e7702191524311a07b54605568"
      + "3944ce30";
  private static final String RELAY_PRIVATE = "302e020100300506032b6570042204201d337e6a1dbcb180d221f4b519c4672018cfeed213684292"
      + "50333181c4cf5960";
  private static final String HELLO = "534c5231010030f776e24cae6f0d002c0ddf40f7744046d86a02b04f241115d4fc57b3543221002c"
      + "302a300506032b6570032100df1cab1ae80d2e97b15306e90a01df1c4c4d9c36c429bc80032ebc7f"
      + "21ae7912ebfac65053770ac38396501affd4c6f70918063e0daed4be992677f305013bcb0040c547"
      + "d02cb712dfd7aa0e1a537ffa237268d155ffdf38943b77ccac397844ca3d00ef34b997ca2631e403"
      + "63ff270210227646747f6f34095f3d14aa48e7dbf90b";
  private static final String HELLO_ACK = "534c523101015fadb08c8e589c2cfc223d0c5430208821e14623ca02fd091b4cb629c6e57021002c"
      + "302a300506032b65700321003739791a1641c352f000b7e6397299e7702191524311a07b54605568"
      + "3944ce3030f776e24cae6f0d002c0ddf40f7744046d86a02b04f241115d4fc57b3543221ebfac650"
      + "53770ac38396501affd4c6f70918063e0daed4be992677f305013bcb0040175c7743a5362d004fff"
      + "83fcfdc516edebfb6b2ca82e329ceb8127d08e695559a90a5c78d2b0b3079eae937d9a0e8bebfc56"
      + "a86f48ed0fdcc01d1728cbcd9a0c";
  private static final String FORWARDING_FRAME = "534c463101111111111111111111111111111111115fadb08c8e589c2cfc223d0c5430208821e146"
      + "23ca02fd091b4cb629c6e57021333333333333333333333333333333330200f0534c4b3101001111"
      + "111111111111111111111111111130f776e24cae6f0d002c0ddf40f7744046d86a02b04f241115d4"
      + "fc57b35432215fadb08c8e589c2cfc223d0c5430208821e14623ca02fd091b4cb629c6e570212222"
      + "222222222222222222222222222200000000000000013c3f06fa6c733078978bf7193245477b9eac"
      + "46a0fc23c5eaeae0a2a18d4461d30aaf809cb1749395edf93b97d6b04006e5ff5546046d1665236d"
      + "52b8c87c9b7d0040f476a9b5b7464792122c7d63c4f6540be3e7735c49dbdb3c0d361a0e7c1cd921"
      + "07b058959aebb35ca73371e9b58f32eaa96c30780d0b31b2573f4c0d5167ca06";
  private static final String FRAME = "534c523101020138534c463101111111111111111111111111111111115fadb08c8e589c2cfc223d"
      + "0c5430208821e14623ca02fd091b4cb629c6e57021333333333333333333333333333333330200f0"
      + "534c4b3101001111111111111111111111111111111130f776e24cae6f0d002c0ddf40f7744046d8"
      + "6a02b04f241115d4fc57b35432215fadb08c8e589c2cfc223d0c5430208821e14623ca02fd091b4c"
      + "b629c6e570212222222222222222222222222222222200000000000000013c3f06fa6c733078978b"
      + "f7193245477b9eac46a0fc23c5eaeae0a2a18d4461d30aaf809cb1749395edf93b97d6b04006e5ff"
      + "5546046d1665236d52b8c87c9b7d0040f476a9b5b7464792122c7d63c4f6540be3e7735c49dbdb3c"
      + "0d361a0e7c1cd92107b058959aebb35ca73371e9b58f32eaa96c30780d0b31b2573f4c0d5167ca06";
  private static final String INBOUND_FRAME = "534c523101030138534c463101111111111111111111111111111111115fadb08c8e589c2cfc223d"
      + "0c5430208821e14623ca02fd091b4cb629c6e57021333333333333333333333333333333330100f0"
      + "534c4b3101001111111111111111111111111111111130f776e24cae6f0d002c0ddf40f7744046d8"
      + "6a02b04f241115d4fc57b35432215fadb08c8e589c2cfc223d0c5430208821e14623ca02fd091b4c"
      + "b629c6e570212222222222222222222222222222222200000000000000013c3f06fa6c733078978b"
      + "f7193245477b9eac46a0fc23c5eaeae0a2a18d4461d30aaf809cb1749395edf93b97d6b04006e5ff"
      + "5546046d1665236d52b8c87c9b7d0040f476a9b5b7464792122c7d63c4f6540be3e7735c49dbdb3c"
      + "0d361a0e7c1cd92107b058959aebb35ca73371e9b58f32eaa96c30780d0b31b2573f4c0d5167ca06";
  private static final String FRAME_ACK = "534c523101043333333333333333333333333333333306";
  private static final UUID MESSAGE = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private static byte[] bytes(String hex) {
    return HexFormat.of().parseHex(hex);
  }

  private static NodeIdentity relayIdentity() throws Exception {
    return NodeIdentity.fromEncoded(bytes(RELAY_PUBLIC), bytes(RELAY_PRIVATE));
  }

  private static OverlayForwardingFrame frame() throws IOException {
    return OverlayForwardingFrame.decode(bytes(FORWARDING_FRAME));
  }

  @Test
  void helloDecodesAndVerifies() throws Exception {
    RelayWireCodec.Hello hello = RelayWireCodec.decodeHello(bytes(HELLO));

    RelayWireCodec.verifyHello(hello);
    assertEquals(CLIENT_NODE, hello.nodeId());
    assertEquals(0, RelayWireCodec.typeOf(bytes(HELLO)));
  }

  @Test
  void helloAckIsReproducedByTheRelayIdentityAndVerifiesAgainstTheHello() throws Exception {
    RelayWireCodec.Hello hello = RelayWireCodec.decodeHello(bytes(HELLO));

    assertEquals(RELAY_NODE, relayIdentity().nodeId());
    assertArrayEquals(bytes(HELLO_ACK), RelayWireCodec.encodeHelloAck(relayIdentity(), hello));
    RelayWireCodec.verifyHelloAck(RelayWireCodec.decodeHelloAck(bytes(HELLO_ACK)), hello,
        RELAY_NODE, bytes(RELAY_PUBLIC));
    assertEquals(1, RelayWireCodec.typeOf(bytes(HELLO_ACK)));
  }

  @Test
  void frameAndInboundFrameCarryTheForwardingFrameBytes() throws Exception {
    assertArrayEquals(bytes(FRAME), RelayWireCodec.encodeFrame(frame()));
    assertArrayEquals(bytes(FORWARDING_FRAME), RelayWireCodec.decodeFrame(bytes(FRAME), 2));
    assertEquals(2, RelayWireCodec.typeOf(bytes(FRAME)));

    assertArrayEquals(bytes(INBOUND_FRAME),
        RelayWireCodec.encodeInboundFrame(frame().forwardOneHop()));
    assertArrayEquals(frame().forwardOneHop().encoded(),
        RelayWireCodec.decodeFrame(bytes(INBOUND_FRAME), 3));
    assertEquals(3, RelayWireCodec.typeOf(bytes(INBOUND_FRAME)));
  }

  @Test
  void frameAckVector() throws Exception {
    assertArrayEquals(bytes(FRAME_ACK), RelayWireCodec.encodeFrameAck(MESSAGE, 6));
    RelayWireCodec.FrameAck ack = RelayWireCodec.decodeFrameAck(bytes(FRAME_ACK));
    assertEquals(MESSAGE, ack.messageId);
    assertEquals(6, ack.status);
    assertEquals(4, RelayWireCodec.typeOf(bytes(FRAME_ACK)));
  }

  @Test
  void trailingBytesAndEveryTruncationAreRejectedAsIoException() {
    record Decoder(String vector, DecoderCall call) {
    }
    List<Decoder> decoders = List.of(
        new Decoder(HELLO, RelayWireCodec::decodeHello),
        new Decoder(HELLO_ACK, RelayWireCodec::decodeHelloAck),
        new Decoder(FRAME, payload -> RelayWireCodec.decodeFrame(payload, 2)),
        new Decoder(INBOUND_FRAME, payload -> RelayWireCodec.decodeFrame(payload, 3)),
        new Decoder(FRAME_ACK, RelayWireCodec::decodeFrameAck));
    for (Decoder decoder : decoders) {
      byte[] encoded = bytes(decoder.vector());
      assertThrows(IOException.class,
          () -> decoder.call().decode(Arrays.copyOf(encoded, encoded.length + 1)));
      for (int length = 0; length < encoded.length; length++) {
        byte[] truncated = Arrays.copyOf(encoded, length);
        assertThrows(IOException.class, () -> decoder.call().decode(truncated));
      }
    }
  }

  @Test
  void typeOfReadsOnlyTheHeader() throws Exception {
    byte[] header = Arrays.copyOf(bytes(FRAME_ACK), 6);

    assertEquals(4, RelayWireCodec.typeOf(Arrays.copyOf(header, 7)));
    assertEquals(4, RelayWireCodec.typeOf(header));
    assertThrows(IOException.class, () -> RelayWireCodec.typeOf(Arrays.copyOf(header, 5)));
  }

  @Test
  void frameAckStatusIsTheRelayFailureOrdinalPlusOne() throws Exception {
    assertEquals(List.of("UNAUTHORIZED_NODE", "UNAUTHORIZED_PROJECT", "CONNECTION_LIMIT",
        "NO_ROUTE", "HOP_LIMIT_EXCEEDED", "DUPLICATE", "QUEUE_FULL", "RATE_LIMITED"),
        Arrays.stream(OverlayRelayException.Failure.values()).map(Enum::name).toList());

    NodeIdentity origin = NodeIdentity.generate();
    NodeIdentity destination = NodeIdentity.generate();
    NodeIdentity outsider = NodeIdentity.generate();
    UUID project = UUID.randomUUID();
    OverlayRelay core = new OverlayRelay(new OverlayRelayPolicy(
        Map.of(project, Set.of(origin.nodeId(), destination.nodeId())), 8, 8, 4));
    NodeIdentity relayIdentity = NodeIdentity.generate();
    Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    try (OverlayRelayServer server = new OverlayRelayServer(new InetSocketAddress("127.0.0.1", 0),
        relayIdentity, core, 1, fixed)) {
      InetSocketAddress address = server.start();
      try (OverlayRelayClient originClient = new OverlayRelayClient(origin, address,
          relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), ignored -> {
          });
          OverlayRelayClient destinationClient = new OverlayRelayClient(destination, address,
              relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), ignored -> {
          })) {
        originClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(2, statusOf(originClient, frame(project, outsider)));
        assertEquals(4, statusOf(originClient, frame(project, destination)));
        assertEquals(5, statusOf(originClient, frame(project, destination).forwardOneHop()));

        destinationClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS);
        OverlayForwardingFrame delivered = frame(project, destination);
        assertEquals(0, statusOf(originClient, delivered));
        assertEquals(6, statusOf(originClient, delivered));
        assertEquals(8, statusOf(originClient, frame(project, destination)));
      }
    }
  }

  private static OverlayForwardingFrame frame(UUID project, NodeIdentity destination)
      throws Exception {
    byte[] inner = OverlayKeyAgreement.start(project, destination.nodeId(), UUID.randomUUID(), 1,
        NodeIdentity.generate()).init().encoded();
    return OverlayForwardingFrame.create(project, destination.nodeId(), UUID.randomUUID(), 1,
        inner);
  }

  private static int statusOf(OverlayRelayClient client, OverlayForwardingFrame frame)
      throws Exception {
    try {
      client.send(frame).toCompletableFuture().get(10, TimeUnit.SECONDS);
      return 0;
    } catch (ExecutionException failure) {
      String message = failure.getCause().getMessage();
      String prefix = "relay rejected frame with status ";
      assertEquals(prefix, message.substring(0, prefix.length()));
      return Integer.parseInt(message.substring(prefix.length()));
    }
  }

  @FunctionalInterface
  private interface DecoderCall {

    Object decode(byte[] payload) throws IOException;
  }
}
