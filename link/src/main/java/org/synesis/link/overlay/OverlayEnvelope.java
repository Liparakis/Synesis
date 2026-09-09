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
 * Immutable `SLE1` end-to-end ciphertext envelope.
 *
 * <p>The envelope contains no route decision. Its origin and destination are
 * authenticated data for the AEAD operation and remain unchanged across physical hops.
 */
public final class OverlayEnvelope {

  /**
   * Maximum encoded envelope size accepted by the v1 Link stream bound.
   */
  public static final int MAX_BYTES = 4_096;
  /**
   * Maximum plaintext size before the AEAD tag and envelope framing.
   */
  public static final int MAX_PLAINTEXT_BYTES = OverlayCodecSupport.MAX_PLAINTEXT_BYTES;
  /**
   * Maximum ciphertext size including the AEAD tag.
   */
  public static final int MAX_CIPHERTEXT_BYTES = OverlayCodecSupport.MAX_CIPHERTEXT_BYTES;

  private static final int MAGIC = 0x534C4531;
  private static final int VERSION = 1;
  private final UUID projectId;
  private final UUID messageId;
  private final String originNodeId;
  private final String destinationNodeId;
  private final UUID sessionId;
  private final long sequence;
  private final byte[] ciphertext;
  private final byte[] encoded;

  private OverlayEnvelope(UUID projectId, UUID messageId, String originNodeId,
      String destinationNodeId,
      UUID sessionId, long sequence, byte[] ciphertext) {
    this.projectId = Objects.requireNonNull(projectId, "project ID");
    this.messageId = Objects.requireNonNull(messageId, "message ID");
    OverlayCodecSupport.requireNodeId(originNodeId);
    OverlayCodecSupport.requireNodeId(destinationNodeId);
    this.originNodeId = originNodeId;
    this.destinationNodeId = destinationNodeId;
    this.sessionId = Objects.requireNonNull(sessionId, "session ID");
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must not be negative");
    }
    this.sequence = sequence;
    Objects.requireNonNull(ciphertext, "ciphertext");
    if (ciphertext.length < OverlayCodecSupport.AEAD_TAG_BYTES
        || ciphertext.length > MAX_CIPHERTEXT_BYTES) {
      throw new IllegalArgumentException("ciphertext exceeds supported bound");
    }
    this.ciphertext = ciphertext.clone();
    this.encoded = encodeComplete();
    if (encoded.length > MAX_BYTES) {
      throw new IllegalArgumentException("envelope exceeds supported bound");
    }
  }

  static OverlayEnvelope sealed(UUID projectId, UUID messageId, String originNodeId,
      String destinationNodeId,
      UUID sessionId, long sequence, byte[] ciphertext) {
    return new OverlayEnvelope(projectId, messageId, originNodeId, destinationNodeId, sessionId,
        sequence,
        ciphertext);
  }

  /**
   * Decodes a bounded immutable E2E envelope.
   *
   * @param bytes encoded envelope
   * @return decoded envelope
   * @throws IOException if framing or bounds are invalid
   */
  public static OverlayEnvelope decode(byte[] bytes) throws IOException {
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IOException("envelope exceeds supported bound");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
        throw new IOException("unsupported overlay envelope");
      }
      UUID project = OverlayCodecSupport.readUuid(input);
      UUID message = OverlayCodecSupport.readUuid(input);
      String origin = OverlayCodecSupport.nodeId(
          input.readNBytes(OverlayCodecSupport.NODE_ID_BYTES));
      String destination = OverlayCodecSupport.nodeId(
          input.readNBytes(OverlayCodecSupport.NODE_ID_BYTES));
      UUID session = OverlayCodecSupport.readUuid(input);
      long sequence = input.readLong();
      int length = input.readUnsignedShort();
      if (length < OverlayCodecSupport.AEAD_TAG_BYTES || length > MAX_CIPHERTEXT_BYTES
          || length > input.available()) {
        throw new IOException("invalid envelope ciphertext length");
      }
      byte[] ciphertext = input.readNBytes(length);
      OverlayCodecSupport.requireNoTrailing(input);
      OverlayEnvelope result = new OverlayEnvelope(project, message, origin, destination, session,
          sequence,
          ciphertext);
      if (!Arrays.equals(result.encoded, bytes)) {
        throw new IOException("non-canonical overlay envelope");
      }
      return result;
    } catch (java.io.EOFException | IllegalArgumentException failure) {
      throw new IOException("malformed overlay envelope", failure);
    }
  }

  static byte[] associatedData(UUID projectId, UUID messageId, String originNodeId,
      String destinationNodeId,
      UUID sessionId, long sequence, int ciphertextLength) {
    Objects.requireNonNull(projectId, "project ID");
    Objects.requireNonNull(messageId, "message ID");
    OverlayCodecSupport.requireNodeId(originNodeId);
    OverlayCodecSupport.requireNodeId(destinationNodeId);
    Objects.requireNonNull(sessionId, "session ID");
    if (sequence < 0 || ciphertextLength < OverlayCodecSupport.AEAD_TAG_BYTES
        || ciphertextLength > MAX_CIPHERTEXT_BYTES) {
      throw new IllegalArgumentException("invalid envelope associated-data field");
    }
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(160);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeInt(MAGIC);
        output.writeByte(VERSION);
        OverlayCodecSupport.writeUuid(output, projectId);
        OverlayCodecSupport.writeUuid(output, messageId);
        output.write(OverlayCodecSupport.nodeIdBytes(originNodeId));
        output.write(OverlayCodecSupport.nodeIdBytes(destinationNodeId));
        OverlayCodecSupport.writeUuid(output, sessionId);
        output.writeLong(sequence);
        output.writeShort(ciphertextLength);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private byte[] encodeComplete() {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.write(
            associatedData(projectId, messageId, originNodeId, destinationNodeId, sessionId,
                sequence, ciphertext.length));
        output.write(ciphertext);
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
   * Returns the logical message ID.
   *
   * @return message ID
   */
  public UUID messageId() {
    return messageId;
  }

  /**
   * Returns the authenticated origin node ID.
   *
   * @return origin node ID
   */
  public String originNodeId() {
    return originNodeId;
  }

  /**
   * Returns the authenticated destination node ID.
   *
   * @return destination node ID
   */
  public String destinationNodeId() {
    return destinationNodeId;
  }

  /**
   * Returns the E2E session ID.
   *
   * @return session ID
   */
  public UUID sessionId() {
    return sessionId;
  }

  /**
   * Returns the E2E sequence number.
   *
   * @return sequence number
   */
  public long sequence() {
    return sequence;
  }

  /**
   * Returns a defensive copy of the ciphertext and AEAD tag.
   *
   * @return ciphertext bytes
   */
  public byte[] ciphertext() {
    return ciphertext.clone();
  }

  /**
   * Returns the canonical bytes authenticated as AEAD associated data.
   *
   * @return associated-data bytes
   */
  public byte[] associatedData() {
    return associatedData(projectId, messageId, originNodeId, destinationNodeId, sessionId,
        sequence,
        ciphertext.length);
  }

  /**
   * Returns the complete encoded envelope.
   *
   * @return encoded bytes
   */
  public byte[] encoded() {
    return encoded.clone();
  }
}
