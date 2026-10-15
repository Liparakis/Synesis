package org.synesis.link.protocol;

import java.io.Serial;

/**
 * Bounded public failure for a handshake that never produced a session.
 *
 * <p>The message contains only the stable category; remote bytes and key
 * material are intentionally excluded.
 */
public final class HandshakeException extends Exception {

  @Serial
  private static final long serialVersionUID = 1L;
  /**
   * Stable category retained for callers.
   */
  private final HandshakeFailureCode code;
  /**
   * Both compatibility numbers, or {@code null} when the failure is not a mismatch.
   */
  private final LinkCompatibility.Mismatch mismatch;

  /**
   * Creates a categorized handshake failure.
   *
   * @param code stable failure category
   */
  public HandshakeException(HandshakeFailureCode code) {
    super(code.name());
    this.code = code;
    this.mismatch = null;
  }

  /**
   * Creates the failure for two peers whose compatibility numbers differ.
   *
   * @param local  this side's compatibility number
   * @param remote the other side's compatibility number
   */
  public HandshakeException(int local, int remote) {
    super(HandshakeFailureCode.INCOMPATIBLE_VERSION.name());
    this.code = HandshakeFailureCode.INCOMPATIBLE_VERSION;
    this.mismatch = new LinkCompatibility.Mismatch("connection", local, remote);
  }

  /**
   * Returns the stable failure category.
   *
   * @return failure code
   */
  public HandshakeFailureCode code() {
    return code;
  }

  /**
   * Returns both compatibility numbers for {@link HandshakeFailureCode#INCOMPATIBLE_VERSION}.
   *
   * @return the mismatch, or {@code null} for any other code
   */
  public LinkCompatibility.Mismatch mismatch() {
    return mismatch;
  }
}
