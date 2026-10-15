package org.synesis.link.protocol;

import java.io.IOException;
import java.io.Serial;

/**
 * A link carries a compatibility number other than this build's, so nothing else in it is read.
 *
 * @since 1.0
 */
public final class IncompatibleVersionException extends IOException {

  @Serial
  private static final long serialVersionUID = 1L;
  /**
   * Both compatibility numbers, or {@code null} when the failure is not a mismatch.
   */
  private final LinkCompatibility.Mismatch mismatch;

  IncompatibleVersionException(String subject, int remote) {
    super(subject + " compatibility " + remote + " differs from " + LinkCompatibility.CURRENT);
    this.mismatch = new LinkCompatibility.Mismatch(subject, LinkCompatibility.CURRENT, remote);
  }

  /**
   * Returns both numbers.
   *
   * @return the mismatch
   */
  public LinkCompatibility.Mismatch mismatch() {
    return mismatch;
  }

  /**
   * Reads the leading compatibility number of a link payload and refuses a different one.
   *
   * @param payload decoded link payload
   * @param subject {@code invitation} or {@code answer}
   * @throws IncompatibleVersionException if the number differs
   * @throws IOException                  if the payload is too short to carry one
   */
  static void requireCurrent(byte[] payload, String subject) throws IOException {
    if (payload.length < Short.BYTES) {
      throw new IOException("truncated " + subject);
    }
    int remote = ((payload[0] & 255) << 8) | (payload[1] & 255);
    if (remote != LinkCompatibility.CURRENT) {
      throw new IncompatibleVersionException(subject, remote);
    }
  }
}
