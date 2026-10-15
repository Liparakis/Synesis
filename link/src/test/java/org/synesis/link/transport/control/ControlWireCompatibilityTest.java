package org.synesis.link.transport.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.demo.DemoWorkStatus;
import org.synesis.link.protocol.HandshakeFailureCode;
import org.synesis.link.protocol.HandshakeRole;
import org.synesis.link.session.SessionCloseReason;

/**
 * Pins the control frame header, the application stream frame, the heartbeat length rules, and
 * the numeric values of every enum that crosses the wire.
 */
final class ControlWireCompatibilityTest {

  private static final String MAGIC_AND_VERSION = "534c483101";

  private static byte[] unhex(String value) {
    return HexFormat.of().parseHex(value);
  }

  private static String hex(byte[] value) {
    return HexFormat.of().formatHex(value);
  }

  @Test
  void controlFrameIsMagicVersionTypeCodeMessageVersionFlagsAndBigEndianLength() throws Exception {
    Map<ControlMessageType, String> typeBytes = Map.of(
        ControlMessageType.CONTROL_READY, "01",
        ControlMessageType.GOODBYE, "02",
        ControlMessageType.GOODBYE_ACK, "03",
        ControlMessageType.PROTOCOL_ERROR, "04",
        ControlMessageType.HEARTBEAT, "05",
        ControlMessageType.HEARTBEAT_ACK, "06");
    assertEquals(ControlMessageType.values().length, typeBytes.size());

    for (ControlMessageType type : ControlMessageType.values()) {
      String expected = MAGIC_AND_VERSION + typeBytes.get(type) + "0100" + "00000002" + "aabb";
      ControlFrame frame = ControlFrame.of(type, unhex("aabb"));

      assertEquals(expected, hex(frame.encoded()), type.name());
      ControlFrame decoded = ControlFrame.decode(unhex(expected));
      assertEquals(type, decoded.type);
      assertArrayEquals(unhex("aabb"), decoded.payload);
    }
    assertEquals(MAGIC_AND_VERSION + "02" + "0100" + "00000000",
        hex(ControlFrame.of(ControlMessageType.GOODBYE, new byte[0]).encoded()));
  }

  @Test
  void controlFrameRejectsTrailingAndTruncatedInput() {
    byte[] frame = unhex(MAGIC_AND_VERSION + "05" + "0100" + "00000002" + "aabb");

    assertThrows(IOException.class, () -> ControlFrame.decode(Arrays.copyOf(frame, frame.length + 1)));
    assertThrows(IOException.class, () -> ControlFrame.decode(Arrays.copyOf(frame, frame.length - 1)));
  }

  @Test
  void heartbeatRejectsTrailingAndTruncatedInput() {
    byte[] encoded = HeartbeatMessage.heartbeat(
        UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), 7, 6, 1).encoded();

    assertEquals(41, encoded.length);
    assertThrows(IllegalArgumentException.class,
        () -> HeartbeatMessage.decode(Arrays.copyOf(encoded, encoded.length + 1), false));
    assertThrows(IllegalArgumentException.class,
        () -> HeartbeatMessage.decode(Arrays.copyOf(encoded, encoded.length - 1), false));
  }

  @Test
  void applicationStreamFrameIsSla1MagicVersionThenPayload() {
    assertEquals("534c413101010203", hex(ApplicationStreamCodec.encode(unhex("010203"))));
    assertEquals("534c413101", hex(ApplicationStreamCodec.encode(new byte[0])));
    assertArrayEquals(unhex("010203"), ApplicationStreamCodec.decode(unhex("534c413101010203")));
    assertTrue(ApplicationStreamCodec.looksLike(unhex("534c413101")));
  }

  @Test
  void applicationStreamFrameTreatsExtraBytesAsPayloadAndRejectsAShortHeader() {
    assertArrayEquals(unhex("01020300"), ApplicationStreamCodec.decode(unhex("534c41310101020300")));
    assertArrayEquals(unhex("0102"), ApplicationStreamCodec.decode(unhex("534c4131010102")));
    assertThrows(IllegalArgumentException.class,
        () -> ApplicationStreamCodec.decode(unhex("534c4131")));
  }

  @Test
  void enumsThatCrossTheWireKeepTheirNumericValues() {
    assertEquals(table("LOCAL_REQUEST", 0, "REMOTE_REQUEST", 1, "PROTOCOL_ERROR", 2,
        "TRANSPORT_CLOSED", 3, "INTERNAL_FAILURE", 4, "LIVENESS_EXPIRED", 5),
        ordinals(SessionCloseReason.values()));
    assertEquals(table("INITIATOR", 0, "RESPONDER", 1), ordinals(HandshakeRole.values()));
    assertEquals(table("IDENTITY_MISMATCH", 0, "IDENTITY_PROOF_INVALID", 1,
        "UNSUPPORTED_PROTOCOL_VERSION", 2, "HANDSHAKE_REPLAY_REJECTED", 3,
        "MALFORMED_HANDSHAKE", 4, "DUPLICATE_CONTROL_STREAM", 5, "INTERNAL_FAILURE", 6,
        "INCOMPATIBLE_VERSION", 7),
        ordinals(HandshakeFailureCode.values()));
    assertEquals(table("LAN", 0, "IPV6", 1, "MAPPED_IPV4", 2, "SERVER_REFLEXIVE", 3, "MANUAL", 4,
        "RELAY", 5), ordinals(CandidateType.values()));
    assertEquals(table("OK", 0, "DUPLICATE_REQUEST", 1, "UNSUPPORTED_OPERATION", 2, "MALFORMED", 3),
        ordinals(DemoWorkStatus.values()));

    Map<String, Integer> messageCodes = new LinkedHashMap<>();
    for (ControlMessageType type : ControlMessageType.values()) {
      messageCodes.put(type.name(), type.code);
    }
    assertEquals(table("CONTROL_READY", 1, "GOODBYE", 2, "GOODBYE_ACK", 3, "PROTOCOL_ERROR", 4,
        "HEARTBEAT", 5, "HEARTBEAT_ACK", 6), messageCodes);

    Map<String, Integer> errorCodes = new LinkedHashMap<>();
    for (ControlErrorCode code : ControlErrorCode.values()) {
      errorCodes.put(code.name(), code.code);
    }
    assertEquals(table("MALFORMED_FRAME", 1, "OVERSIZED_FRAME", 2, "INVALID_SESSION", 3,
        "ILLEGAL_STATE", 4, "DUPLICATE_CONTROL_STREAM", 5, "UNSUPPORTED_MESSAGE", 6,
        "DUPLICATE_TERMINAL_MESSAGE", 7, "UNEXPECTED_STREAM_END", 8, "INVALID_HEARTBEAT", 9,
        "HEARTBEAT_SEQUENCE_EXHAUSTED", 10), errorCodes);
  }

  private static Map<String, Integer> ordinals(Enum<?>[] values) {
    Map<String, Integer> result = new LinkedHashMap<>();
    for (Enum<?> value : values) {
      result.put(value.name(), value.ordinal());
    }
    return result;
  }

  private static Map<String, Integer> table(Object... namesAndValues) {
    Map<String, Integer> result = new LinkedHashMap<>();
    for (int index = 0; index < namesAndValues.length; index += 2) {
      result.put((String) namesAndValues[index], (Integer) namesAndValues[index + 1]);
    }
    return result;
  }
}
