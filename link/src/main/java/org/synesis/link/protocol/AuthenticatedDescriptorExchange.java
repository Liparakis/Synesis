package org.synesis.link.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;
import org.synesis.link.candidate.CandidateDescriptor;

/**
 * Bounded application-stream codec for exchanging complete candidate
 * descriptors after Link authentication.
 *
 * <p>The envelope is not an identity proof. Each embedded descriptor remains
 * independently signed and the caller must compare its node ID with the
 * already authenticated {@code PeerSession} identity before using it.
 *
 * @since 1.0
 */
public final class AuthenticatedDescriptorExchange {

  /** Maximum encoded exchange payload accepted by the existing application stream. */
  public static final int MAX_BYTES = 4_096;
  private static final int ENVELOPE_BYTES = 8;

  private static final int MAGIC = 0x534C5831;
  private static final int VERSION = 1;
  private static final int REQUEST = 1;
  private static final int RESPONSE = 2;

  private AuthenticatedDescriptorExchange() {
  }

  /**
   * Encodes a joiner descriptor request.
   *
   * @param descriptor signed local descriptor
   * @return bounded request payload
   */
  public static byte[] request(CandidateDescriptor descriptor) {
    return encode(REQUEST, Objects.requireNonNull(descriptor, "descriptor"));
  }

  /**
   * Encodes a host descriptor response.
   *
   * @param descriptor signed host descriptor
   * @return bounded response payload
   */
  public static byte[] response(CandidateDescriptor descriptor) {
    return encode(RESPONSE, Objects.requireNonNull(descriptor, "descriptor"));
  }

  /**
   * Decodes a request and its signed descriptor.
   *
   * @param encoded request payload
   * @return decoded signed descriptor
   * @throws IOException if the envelope or descriptor is malformed
   */
  public static CandidateDescriptor decodeRequest(byte[] encoded) throws IOException {
    return decode(encoded, REQUEST);
  }

  /**
   * Decodes a response and its signed descriptor.
   *
   * @param encoded response payload
   * @return decoded signed descriptor
   * @throws IOException if the envelope or descriptor is malformed
   */
  public static CandidateDescriptor decodeResponse(byte[] encoded) throws IOException {
    return decode(encoded, RESPONSE);
  }

  /**
   * Reports whether a payload has the descriptor-exchange envelope marker.
   *
   * @param encoded candidate application payload
   * @return true for a descriptor-exchange payload marker
   */
  public static boolean looksLike(byte[] encoded) {
    return encoded != null && encoded.length >= Integer.BYTES
        && encoded[0] == 'S' && encoded[1] == 'L' && encoded[2] == 'X' && encoded[3] == '1';
  }

  private static byte[] encode(int kind, CandidateDescriptor descriptor) {
    byte[] descriptorBytes = descriptor.encoded();
    if (descriptorBytes.length > MAX_BYTES - ENVELOPE_BYTES) {
      throw new IllegalArgumentException("descriptor exceeds exchange bound");
    }
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(descriptorBytes.length + 8);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeInt(MAGIC);
        output.writeByte(VERSION);
        output.writeByte(kind);
        output.writeShort(descriptorBytes.length);
        output.write(descriptorBytes);
      }
      if (bytes.size() > MAX_BYTES) {
        throw new IllegalArgumentException("descriptor exchange exceeds supported bound");
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError("byte array output cannot fail", impossible);
    }
  }

  private static CandidateDescriptor decode(byte[] encoded, int expectedKind) throws IOException {
    Objects.requireNonNull(encoded, "descriptor exchange");
    if (encoded.length < 8 || encoded.length > MAX_BYTES) {
      throw new IOException("descriptor exchange exceeds supported bound");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
      if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION
          || input.readUnsignedByte() != expectedKind) {
        throw new IOException("unsupported descriptor exchange");
      }
      int length = input.readUnsignedShort();
      if (length == 0 || length > MAX_BYTES - ENVELOPE_BYTES || length > input.available()) {
        throw new IOException("invalid descriptor exchange length");
      }
      byte[] descriptor = input.readNBytes(length);
      if (input.available() != 0) {
        throw new IOException("trailing descriptor exchange bytes");
      }
      return CandidateDescriptor.decode(descriptor);
    } catch (EOFException | IllegalArgumentException exception) {
      throw new IOException("malformed descriptor exchange", exception);
    }
  }
}
