package org.synesis.link.transport.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.synesis.link.session.HandshakeException;
import org.synesis.link.session.HandshakeFailureCode;
import org.synesis.link.session.SessionCloseReason;

/**
 * Tests the session-scoped control payload codec against its pinned bytes.
 */
final class SessionPayloadTest {

  private static final UUID SESSION = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
  private static final UUID OTHER = UUID.fromString("ffeeddcc-bbaa-9988-7766-554433221100");
  private static final String SESSION_HEX = "00112233445566778899aabbccddeeff";

  private static byte[] bytes(String hex) {
    return HexFormat.of()
        .parseHex(hex);
  }

  private static HandshakeFailureCode failure(Executable call) {
    return assertThrows(HandshakeException.class, call).code();
  }

  @Test
  void readyRoundTripsWithMaximumPayload() throws HandshakeException {
    byte[] encoded = SessionPayload.ready(SESSION);

    assertArrayEquals(bytes(SESSION_HEX + String.format("%08x", ControlFrame.MAX_PAYLOAD)),
        encoded);
    assertEquals(ControlFrame.MAX_PAYLOAD, SessionPayload.readyMaximumPayload(encoded, SESSION));
  }

  @Test
  void acknowledgementRoundTrips() throws HandshakeException {
    byte[] encoded = SessionPayload.acknowledgement(SESSION);

    assertArrayEquals(bytes(SESSION_HEX), encoded);
    SessionPayload.requireAcknowledgement(encoded, SESSION);
  }

  @Test
  void goodbyeRoundTripsEveryReasonByOrdinal() throws HandshakeException {
    for (SessionCloseReason reason : SessionCloseReason.values()) {
      byte[] encoded = SessionPayload.goodbye(SESSION, reason);

      assertArrayEquals(bytes(SESSION_HEX + String.format("%02x", reason.ordinal())), encoded);
      assertEquals(reason, SessionPayload.goodbyeReason(encoded, SESSION));
    }
  }

  @Test
  void goodbyeWithUnknownReasonDecodesToNull() throws HandshakeException {
    assertNull(SessionPayload.goodbyeReason(bytes(SESSION_HEX + "ff"), SESSION));
  }

  @Test
  void protocolErrorRoundTripsEveryCodeAndRejectsOutOfRange() throws HandshakeException {
    for (ControlErrorCode code : ControlErrorCode.values()) {
      byte[] encoded = SessionPayload.protocolError(SESSION, code);

      assertArrayEquals(bytes(SESSION_HEX + String.format("%02x", code.code)), encoded);
      SessionPayload.requireProtocolError(encoded, SESSION);
    }
    assertEquals(HandshakeFailureCode.MALFORMED_HANDSHAKE,
        failure(() -> SessionPayload.requireProtocolError(bytes(SESSION_HEX + "00"), SESSION)));
    assertEquals(HandshakeFailureCode.MALFORMED_HANDSHAKE,
        failure(() -> SessionPayload.requireProtocolError(bytes(SESSION_HEX + "ff"), SESSION)));
  }

  @Test
  void wrongLengthIsMalformed() {
    assertEquals(HandshakeFailureCode.MALFORMED_HANDSHAKE,
        failure(() -> SessionPayload.readyMaximumPayload(SessionPayload.acknowledgement(SESSION),
            SESSION)));
    assertEquals(HandshakeFailureCode.MALFORMED_HANDSHAKE,
        failure(() -> SessionPayload.requireAcknowledgement(null, SESSION)));
    assertEquals(HandshakeFailureCode.MALFORMED_HANDSHAKE,
        failure(() -> SessionPayload.goodbyeReason(SessionPayload.acknowledgement(SESSION),
            SESSION)));
  }

  @Test
  void otherSessionIsIdentityMismatch() {
    assertEquals(HandshakeFailureCode.IDENTITY_MISMATCH,
        failure(() -> SessionPayload.readyMaximumPayload(SessionPayload.ready(OTHER), SESSION)));
    assertEquals(HandshakeFailureCode.IDENTITY_MISMATCH,
        failure(() -> SessionPayload.requireAcknowledgement(
            SessionPayload.acknowledgement(OTHER), SESSION)));
    assertEquals(HandshakeFailureCode.IDENTITY_MISMATCH,
        failure(() -> SessionPayload.goodbyeReason(
            SessionPayload.goodbye(OTHER, SessionCloseReason.LOCAL_REQUEST), SESSION)));
  }
}
