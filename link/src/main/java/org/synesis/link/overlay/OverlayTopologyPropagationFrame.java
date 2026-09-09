package org.synesis.link.overlay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Bounded hop-local wrapper for forwarding one signed {@code SLT1} topology advertisement through
 * authenticated direct peers.
 *
 * <p>The signed advertisement is preserved byte-for-byte. Only the
 * propagation budget is changed at each hop.
 */
public final class OverlayTopologyPropagationFrame {

  /**
   * Maximum number of direct peers through which an advertisement travels.
   */
  public static final int MAX_HOPS = 8;
  /**
   * Maximum encoded propagation frame size.
   */
  public static final int MAX_BYTES = 2_048;

  private static final int MAGIC = 0x534C5031;
  private static final int VERSION = 1;
  private final OverlayTopologyAdvertisement advertisement;
  private final int remainingHops;
  private final byte[] encoded;

  private OverlayTopologyPropagationFrame(OverlayTopologyAdvertisement advertisement,
      int remainingHops) {
    this.advertisement = Objects.requireNonNull(advertisement, "advertisement");
    if (remainingHops < 0 || remainingHops > MAX_HOPS) {
      throw new IllegalArgumentException("topology propagation hops outside supported bound");
    }
    this.remainingHops = remainingHops;
    this.encoded = encodeComplete();
    if (encoded.length > MAX_BYTES) {
      throw new IllegalArgumentException("topology propagation frame exceeds supported bound");
    }
  }

  /**
   * Creates a new propagation frame with a positive bounded budget.
   *
   * @param advertisement signed topology advertisement
   * @param remainingHops propagation budget
   * @return new propagation frame
   */
  public static OverlayTopologyPropagationFrame create(OverlayTopologyAdvertisement advertisement,
      int remainingHops) {
    if (remainingHops <= 0) {
      throw new IllegalArgumentException("new topology propagation requires a positive hop budget");
    }
    return new OverlayTopologyPropagationFrame(advertisement, remainingHops);
  }

  /**
   * Decodes one canonical bounded propagation frame.
   *
   * @param bytes encoded propagation frame
   * @return decoded frame
   * @throws IOException if framing, bounds, or canonical encoding is invalid
   */
  public static OverlayTopologyPropagationFrame decode(byte[] bytes) throws IOException {
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IOException("topology propagation frame exceeds supported bound");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
        throw new IOException("unsupported topology propagation frame");
      }
      java.util.UUID projectId = OverlayCodecSupport.readUuid(input);
      String originNodeId = OverlayCodecSupport.nodeId(
          input.readNBytes(OverlayCodecSupport.NODE_ID_BYTES));
      long sequence = input.readLong();
      int remainingHops = input.readUnsignedByte();
      int length = input.readUnsignedShort();
      if (length <= 0 || length > OverlayTopologyAdvertisement.MAX_BYTES
          || length > input.available()) {
        throw new IOException("invalid topology advertisement length");
      }
      byte[] advertisementBytes = input.readNBytes(length);
      OverlayCodecSupport.requireNoTrailing(input);
      OverlayTopologyAdvertisement advertisement = OverlayTopologyAdvertisement.decode(
          advertisementBytes);
      if (!projectId.equals(advertisement.projectId())
          || !originNodeId.equals(advertisement.originNodeId())
          || sequence != advertisement.sequence()) {
        throw new IOException("topology propagation binding mismatch");
      }
      OverlayTopologyPropagationFrame result = new OverlayTopologyPropagationFrame(advertisement,
          remainingHops);
      if (!Arrays.equals(result.encoded, bytes)) {
        throw new IOException("non-canonical topology propagation frame");
      }
      return result;
    } catch (java.io.EOFException | IllegalArgumentException failure) {
      throw new IOException("malformed topology propagation frame", failure);
    }
  }

  /**
   * Returns whether bytes begin with the topology propagation magic.
   *
   * @param bytes candidate wire bytes
   * @return true when the candidate is intended for this decoder
   */
  public static boolean hasMagic(byte[] bytes) {
    return bytes != null && bytes.length >= Integer.BYTES
        && bytes[0] == (byte) (MAGIC >>> 24)
        && bytes[1] == (byte) (MAGIC >>> 16)
        && bytes[2] == (byte) (MAGIC >>> 8)
        && bytes[3] == (byte) MAGIC;
  }

  /**
   * Returns a copy with one propagation hop consumed.
   *
   * @return decremented propagation frame
   * @throws IllegalStateException when no propagation budget remains
   */
  public OverlayTopologyPropagationFrame forwardOneHop() {
    if (remainingHops <= 0) {
      throw new IllegalStateException("topology propagation hop budget exhausted");
    }
    return new OverlayTopologyPropagationFrame(advertisement, remainingHops - 1);
  }

  /**
   * Returns the signed topology advertisement.
   *
   * @return topology advertisement
   */
  public OverlayTopologyAdvertisement advertisement() {
    return advertisement;
  }

  /**
   * Returns the remaining propagation budget.
   *
   * @return remaining hops
   */
  public int remainingHops() {
    return remainingHops;
  }

  /**
   * Returns the canonical encoded frame.
   *
   * @return encoded frame bytes
   */
  public byte[] encoded() {
    return encoded.clone();
  }

  private byte[] encodeComplete() {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeInt(MAGIC);
        output.writeByte(VERSION);
        OverlayCodecSupport.writeUuid(output, advertisement.projectId());
        output.write(OverlayCodecSupport.nodeIdBytes(advertisement.originNodeId()));
        output.writeLong(advertisement.sequence());
        output.writeByte(remainingHops);
        byte[] advertisementBytes = advertisement.encoded();
        output.writeShort(advertisementBytes.length);
        output.write(advertisementBytes);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
