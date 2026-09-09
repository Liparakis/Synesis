package org.synesis.link.overlay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable `SLF1` hop-local wrapper for one opaque logical record.
 */
public final class OverlayForwardingFrame {

  /**
   * Maximum hop count permitted by the v1 contract.
   */
  public static final int MAX_HOPS = 32;
  /**
   * Maximum encoded forwarding frame size.
   */
  public static final int MAX_BYTES = 4_096;

  private static final int MAGIC = 0x534C4631;
  private static final int VERSION = 1;
  private final UUID projectId;
  private final String destinationNodeId;
  private final UUID messageId;
  private final int remainingHops;
  private final byte[] innerRecord;
  private final byte[] encoded;

  private OverlayForwardingFrame(UUID projectId, String destinationNodeId, UUID messageId,
      int remainingHops,
      byte[] innerRecord) {
    this.projectId = Objects.requireNonNull(projectId, "project ID");
    OverlayCodecSupport.requireNodeId(destinationNodeId);
    this.destinationNodeId = destinationNodeId;
    this.messageId = Objects.requireNonNull(messageId, "message ID");
    if (remainingHops < 0 || remainingHops > MAX_HOPS) {
      throw new IllegalArgumentException("remaining hops outside supported bound");
    }
    this.remainingHops = remainingHops;
    Objects.requireNonNull(innerRecord, "inner record");
    if (innerRecord.length == 0 || innerRecord.length > OverlayEnvelope.MAX_BYTES) {
      throw new IllegalArgumentException("inner record exceeds supported bound");
    }
    OverlayCodecSupport.requireLogicalRecord(innerRecord);
    this.innerRecord = innerRecord.clone();
    this.encoded = encodeComplete();
    if (encoded.length > MAX_BYTES) {
      throw new IllegalArgumentException("forwarding frame exceeds supported bound");
    }
  }

  /**
   * Creates one bounded hop-local forwarding frame.
   *
   * @param projectId         project namespace
   * @param destinationNodeId final logical destination
   * @param messageId         logical message or key-agreement session ID
   * @param remainingHops     positive hop budget
   * @param innerRecord       opaque `SLE1` or `SLK1` bytes
   * @return forwarding frame
   */
  public static OverlayForwardingFrame create(UUID projectId, String destinationNodeId,
      UUID messageId,
      int remainingHops, byte[] innerRecord) {
    if (remainingHops <= 0) {
      throw new IllegalArgumentException("new forwarding frame requires a positive hop budget");
    }
    return new OverlayForwardingFrame(projectId, destinationNodeId, messageId, remainingHops,
        innerRecord);
  }

  /**
   * Decodes one bounded forwarding frame.
   *
   * @param bytes encoded frame
   * @return decoded frame
   * @throws IOException if framing or bounds are invalid
   */
  public static OverlayForwardingFrame decode(byte[] bytes) throws IOException {
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IOException("forwarding frame exceeds supported bound");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
        throw new IOException("unsupported forwarding frame");
      }
      UUID project = OverlayCodecSupport.readUuid(input);
      String destination = OverlayCodecSupport.nodeId(
          input.readNBytes(OverlayCodecSupport.NODE_ID_BYTES));
      UUID message = OverlayCodecSupport.readUuid(input);
      int hops = input.readUnsignedByte();
      int length = input.readUnsignedShort();
      if (length <= 0 || length > OverlayEnvelope.MAX_BYTES || length > input.available()) {
        throw new IOException("invalid forwarding inner record length");
      }
      byte[] inner = input.readNBytes(length);
      OverlayCodecSupport.requireNoTrailing(input);
      OverlayForwardingFrame result = new OverlayForwardingFrame(project, destination, message,
          hops, inner);
      if (!Arrays.equals(result.encoded, bytes)) {
        throw new IOException("non-canonical forwarding frame");
      }
      return result;
    } catch (java.io.EOFException | IllegalArgumentException failure) {
      throw new IOException("malformed forwarding frame", failure);
    }
  }

  /**
   * Returns a copy with one hop consumed.
   *
   * @return decremented frame
   * @throws IllegalStateException if the hop budget is exhausted
   */
  public OverlayForwardingFrame forwardOneHop() {
    if (remainingHops <= 0) {
      throw new IllegalStateException("forwarding hop budget exhausted");
    }
    return new OverlayForwardingFrame(projectId, destinationNodeId, messageId, remainingHops - 1,
        innerRecord);
  }

  private byte[] encodeComplete() {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeInt(MAGIC);
        output.writeByte(VERSION);
        OverlayCodecSupport.writeUuid(output, projectId);
        output.write(OverlayCodecSupport.nodeIdBytes(destinationNodeId));
        OverlayCodecSupport.writeUuid(output, messageId);
        output.writeByte(remainingHops);
        output.writeShort(innerRecord.length);
        output.write(innerRecord);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /**
   * Returns the project ID.
   *
   * @return project namespace
   */
  public UUID projectId() {
    return projectId;
  }

  /**
   * Returns the final destination node ID.
   *
   * @return destination node ID
   */
  public String destinationNodeId() {
    return destinationNodeId;
  }

  /**
   * Returns the logical message or key-agreement session ID.
   *
   * @return message ID
   */
  public UUID messageId() {
    return messageId;
  }

  /**
   * Returns the remaining hop budget.
   *
   * @return remaining hops
   */
  public int remainingHops() {
    return remainingHops;
  }

  /**
   * Returns a defensive copy of the opaque inner record.
   *
   * @return inner record bytes
   */
  public byte[] innerRecord() {
    return innerRecord.clone();
  }

  /**
   * Returns the complete encoded frame.
   *
   * @return encoded bytes
   */
  public byte[] encoded() {
    return encoded.clone();
  }
}
