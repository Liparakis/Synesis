package org.synesis.link.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * The non-authoritative project identity used to route a v2 answer back to its host.
 *
 * <p>The value is deliberately only a routing identifier. It does not authorize Link
 * admission, membership, commands, replay decisions, or operation ownership.
 *
 * @since 1.0
 */
public final class ReturnTarget {

  /** The only currently supported return-target encoding. */
  public static final int FORMAT_VERSION = 1;
  /** The canonical encoded size: one format byte and two UUID words. */
  public static final int ENCODED_BYTES = 17;

  private final int formatVersion;
  private final UUID returnProjectId;

  private ReturnTarget(int formatVersion, UUID returnProjectId) {
    if (formatVersion != FORMAT_VERSION) {
      throw new IllegalArgumentException("unsupported return target format");
    }
    this.formatVersion = formatVersion;
    this.returnProjectId = Objects.requireNonNull(returnProjectId, "return project ID");
  }

  /**
   * Creates the supported return target for a stable host project identity.
   *
   * @param returnProjectId stable host project UUID
   * @return immutable return target
   */
  public static ReturnTarget of(UUID returnProjectId) {
    return new ReturnTarget(FORMAT_VERSION, returnProjectId);
  }

  static ReturnTarget read(DataInputStream input) throws IOException {
    try {
      int format = input.readUnsignedByte();
      if (format != FORMAT_VERSION) {
        throw new IOException("unsupported return target format");
      }
      return new ReturnTarget(format, new UUID(input.readLong(), input.readLong()));
    } catch (EOFException | IllegalArgumentException exception) {
      throw new IOException("malformed return target", exception);
    }
  }

  void write(DataOutputStream output) throws IOException {
    output.writeByte(formatVersion);
    output.writeLong(returnProjectId.getMostSignificantBits());
    output.writeLong(returnProjectId.getLeastSignificantBits());
  }

  /**
   * Returns the target format version.
   *
   * @return target format version
   */
  public int formatVersion() {
    return formatVersion;
  }

  /**
   * Returns the stable host project UUID.
   *
   * @return host project UUID
   */
  public UUID returnProjectId() {
    return returnProjectId;
  }

  /**
   * Returns the deterministic binary target encoding.
   *
   * @return canonical target bytes
   */
  public byte[] encoded() {
    return new byte[] {
      (byte) formatVersion,
      (byte) (returnProjectId.getMostSignificantBits() >>> 56),
      (byte) (returnProjectId.getMostSignificantBits() >>> 48),
      (byte) (returnProjectId.getMostSignificantBits() >>> 40),
      (byte) (returnProjectId.getMostSignificantBits() >>> 32),
      (byte) (returnProjectId.getMostSignificantBits() >>> 24),
      (byte) (returnProjectId.getMostSignificantBits() >>> 16),
      (byte) (returnProjectId.getMostSignificantBits() >>> 8),
      (byte) returnProjectId.getMostSignificantBits(),
      (byte) (returnProjectId.getLeastSignificantBits() >>> 56),
      (byte) (returnProjectId.getLeastSignificantBits() >>> 48),
      (byte) (returnProjectId.getLeastSignificantBits() >>> 40),
      (byte) (returnProjectId.getLeastSignificantBits() >>> 32),
      (byte) (returnProjectId.getLeastSignificantBits() >>> 24),
      (byte) (returnProjectId.getLeastSignificantBits() >>> 16),
      (byte) (returnProjectId.getLeastSignificantBits() >>> 8),
      (byte) returnProjectId.getLeastSignificantBits()
    };
  }

  /**
   * Compares target format and project identity.
   *
   * @param other comparison value
   * @return whether both values are equal
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof ReturnTarget target
        && formatVersion == target.formatVersion
        && returnProjectId.equals(target.returnProjectId);
  }

  /**
   * Returns a structural hash code.
   *
   * @return structural hash code
   */
  @Override
  public int hashCode() {
    return Objects.hash(formatVersion, returnProjectId);
  }

  /**
   * Returns a non-secret diagnostic representation.
   *
   * @return safe diagnostic text
   */
  @Override
  public String toString() {
    return "ReturnTarget{formatVersion=" + formatVersion
        + ", returnProjectId=" + returnProjectId + "}";
  }
}
