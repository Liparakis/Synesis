package org.synesis.link.transport.control;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import org.synesis.link.protocol.HandshakeException;
import org.synesis.link.protocol.HandshakeFailureCode;
import org.synesis.link.session.SessionCloseReason;

/**
 * Internal codec for the session-scoped control payloads: a big-endian session id followed by
 * nothing (GOODBYE_ACK), the maximum payload (CONTROL_READY), or one byte (GOODBYE close reason,
 * PROTOCOL_ERROR code).
 */
final class SessionPayload {

  private static final int SESSION_BYTES = 16;
  private static final int READY_BYTES = SESSION_BYTES + Integer.BYTES;
  private static final int TAGGED_BYTES = SESSION_BYTES + 1;

  private SessionPayload() {
  }

  static byte[] ready(UUID sessionId) {
    return session(sessionId, READY_BYTES).putInt(ControlFrame.MAX_PAYLOAD)
        .array();
  }

  static byte[] acknowledgement(UUID sessionId) {
    return session(sessionId, SESSION_BYTES).array();
  }

  static byte[] goodbye(UUID sessionId, SessionCloseReason reason) {
    return session(sessionId, TAGGED_BYTES).put((byte) reason.ordinal())
        .array();
  }

  static byte[] protocolError(UUID sessionId, ControlErrorCode code) {
    return session(sessionId, TAGGED_BYTES).put((byte) code.code)
        .array();
  }

  /**
   * Returns the maximum payload the peer advertised.
   */
  static int readyMaximumPayload(byte[] payload, UUID sessionId) throws HandshakeException {
    return validate(payload, READY_BYTES, sessionId).getInt(SESSION_BYTES);
  }

  static void requireAcknowledgement(byte[] payload, UUID sessionId) throws HandshakeException {
    validate(payload, SESSION_BYTES, sessionId);
  }

  /**
   * Returns the close reason, or null when the byte is not a known reason.
   */
  static SessionCloseReason goodbyeReason(byte[] payload, UUID sessionId)
      throws HandshakeException {
    int value = validate(payload, TAGGED_BYTES, sessionId).get(SESSION_BYTES) & 255;
    SessionCloseReason[] values = SessionCloseReason.values();
    return value < values.length ? values[value] : null;
  }

  static void requireProtocolError(byte[] payload, UUID sessionId) throws HandshakeException {
    int code = validate(payload, TAGGED_BYTES, sessionId).get(SESSION_BYTES) & 255;
    if (code == 0 || code > ControlErrorCode.values().length) {
      throw new HandshakeException(HandshakeFailureCode.MALFORMED_HANDSHAKE);
    }
  }

  private static ByteBuffer session(UUID sessionId, int length) {
    return ByteBuffer.allocate(length)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(sessionId.getMostSignificantBits())
        .putLong(sessionId.getLeastSignificantBits());
  }

  private static ByteBuffer validate(byte[] payload, int expectedLength, UUID sessionId)
      throws HandshakeException {
    if (payload == null || payload.length != expectedLength) {
      throw new HandshakeException(HandshakeFailureCode.MALFORMED_HANDSHAKE);
    }
    ByteBuffer buffer = ByteBuffer.wrap(payload)
        .order(ByteOrder.BIG_ENDIAN);
    if (!new UUID(buffer.getLong(), buffer.getLong()).equals(sessionId)) {
      throw new HandshakeException(HandshakeFailureCode.IDENTITY_MISMATCH);
    }
    return buffer;
  }
}
