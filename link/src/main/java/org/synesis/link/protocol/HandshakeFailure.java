package org.synesis.link.protocol;

import java.io.IOException;
import java.util.Objects;

/**
 * Bounded wire message used to close a rejected pre-session handshake.
 */
public final class HandshakeFailure {

  private static final int MAGIC = 0x534C4631;
  private static final int VERSION = 1;
  private final HandshakeFailureCode code;
  private final int senderCompatibility;

  private HandshakeFailure(HandshakeFailureCode code, int senderCompatibility) {
    this.code = Objects.requireNonNull(code, "failure code");
    this.senderCompatibility = senderCompatibility;
  }

  /**
   * Creates a failure message.
   *
   * @param code stable category
   * @return immutable failure message
   */
  public static HandshakeFailure create(HandshakeFailureCode code) {
    return new HandshakeFailure(code, 0);
  }

  /**
   * Creates the failure for peers whose compatibility numbers differ. It carries the sender's
   * number so the receiver can name both.
   *
   * @param senderCompatibility the number of the build sending the failure
   * @return immutable failure message of code {@link HandshakeFailureCode#INCOMPATIBLE_VERSION}
   */
  public static HandshakeFailure incompatible(int senderCompatibility) {
    return new HandshakeFailure(HandshakeFailureCode.INCOMPATIBLE_VERSION, senderCompatibility);
  }

  /**
   * Decodes a failure message.
   *
   * @param encoded exactly encoded failure bytes
   * @return decoded failure
   * @throws IOException if the message is malformed
   */
  public static HandshakeFailure decode(byte[] encoded) throws IOException {
    if (encoded == null || encoded.length < 6) {
      throw new IOException("malformed handshake failure");
    }
    int magic = ((encoded[0] & 255) << 24) | ((encoded[1] & 255) << 16)
        | ((encoded[2] & 255) << 8) | (encoded[3] & 255);
    if (magic != MAGIC || encoded[4] != VERSION
        || (encoded[5] & 255) >= HandshakeFailureCode.values().length) {
      throw new IOException("malformed handshake failure");
    }
    HandshakeFailureCode code = HandshakeFailureCode.values()[encoded[5] & 255];
    boolean carriesNumber = code == HandshakeFailureCode.INCOMPATIBLE_VERSION;
    if (encoded.length != (carriesNumber ? 8 : 6)) {
      throw new IOException("malformed handshake failure");
    }
    return carriesNumber ? incompatible(((encoded[6] & 255) << 8) | (encoded[7] & 255))
        : create(code);
  }

  /**
   * Identifies this message type without allocating parser state.
   *
   * @param encoded bounded candidate bytes
   * @return whether the bytes begin with the failure magic
   */
  public static boolean looksLike(byte[] encoded) {
    return encoded != null && encoded.length >= 4
        && (encoded[0] & 255) == (MAGIC >>> 24)
        && (encoded[1] & 255) == ((MAGIC >>> 16) & 255)
        && (encoded[2] & 255) == ((MAGIC >>> 8) & 255)
        && (encoded[3] & 255) == (MAGIC & 255);
  }

  /**
   * Returns the canonical failure encoding: six bytes, or eight for
   * {@link HandshakeFailureCode#INCOMPATIBLE_VERSION}, which adds the sender's compatibility
   * number.
   *
   * @return encoded failure
   */
  public byte[] encoded() {
    byte[] head = {(byte) (MAGIC >>> 24), (byte) (MAGIC >>> 16), (byte) (MAGIC >>> 8),
        (byte) MAGIC, VERSION, (byte) code.ordinal()};
    if (code != HandshakeFailureCode.INCOMPATIBLE_VERSION) {
      return head;
    }
    return new byte[]{head[0], head[1], head[2], head[3], head[4], head[5],
        (byte) (senderCompatibility >>> 8), (byte) senderCompatibility};
  }

  /**
   * Returns the failure category.
   *
   * @return failure code
   */
  public HandshakeFailureCode code() {
    return code;
  }

  /**
   * Converts this wire value to the public exception type.
   *
   * @param localCompatibility this build's compatibility number, named beside the sender's
   * @return categorized exception
   */
  public HandshakeException exception(int localCompatibility) {
    return code == HandshakeFailureCode.INCOMPATIBLE_VERSION
        ? new HandshakeException(localCompatibility, senderCompatibility)
        : new HandshakeException(code);
  }
}
