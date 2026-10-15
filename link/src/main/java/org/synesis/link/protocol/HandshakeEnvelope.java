package org.synesis.link.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Bounded control-stream envelope carrying one role proof and its transcript.
 *
 * <p>The envelope is transport-neutral. QUIC adapters must add their own
 * stream framing and must not publish a session from an envelope until the proof has been passed to
 * the session authenticator.
 *
 * @since 1.0
 */
public final class HandshakeEnvelope {

  /**
   * Maximum complete envelope size.
   */
  public static final int MAX_BYTES = 8_192;

  private static final int MAGIC = 0x534C4831;
  private static final int VERSION = 1;
  private static final String FIELD_MESSAGE = "invalid bounded envelope field";
  private final HandshakeRole role;
  private final int compatibility;
  private final List<ProtocolVersion> supportedVersions;
  private final HandshakeTranscript transcript;
  private final HandshakeProof proof;

  private HandshakeEnvelope(HandshakeRole role, int compatibility,
      List<ProtocolVersion> supportedVersions, HandshakeTranscript transcript,
      HandshakeProof proof) {
    this.role = Objects.requireNonNull(role, "role");
    if (compatibility < 0 || compatibility > 0xFFFF) {
      throw new IllegalArgumentException("compatibility number must fit two bytes");
    }
    this.compatibility = compatibility;
    this.supportedVersions = normalizeVersions(supportedVersions);
    this.transcript = Objects.requireNonNull(transcript, "transcript");
    this.proof = Objects.requireNonNull(proof, "proof");
    if (encodedLength() > MAX_BYTES) {
      throw new IllegalArgumentException("handshake envelope exceeds supported bound");
    }
  }

  /**
   * Creates a control envelope.
   *
   * @param role       proof role
   * @param transcript canonical transcript
   * @param proof      signed role proof
   * @return immutable envelope
   */
  public static HandshakeEnvelope create(HandshakeRole role, HandshakeTranscript transcript,
      HandshakeProof proof) {
    return create(role, List.of(transcript.version()), transcript, proof);
  }

  /**
   * Creates an envelope with an explicit supported-version offer.
   *
   * @param role              proof role
   * @param supportedVersions versions offered by this endpoint
   * @param transcript        canonical transcript using the selected version
   * @param proof             signed role proof
   * @return immutable envelope
   */
  public static HandshakeEnvelope create(HandshakeRole role,
      List<ProtocolVersion> supportedVersions,
      HandshakeTranscript transcript, HandshakeProof proof) {
    return create(role, LinkCompatibility.CURRENT, supportedVersions, transcript, proof);
  }

  /**
   * Creates an envelope that states a compatibility number other than this build's. Only the
   * handshake's own tests need this.
   *
   * @param role              proof role
   * @param compatibility     the compatibility number to state
   * @param supportedVersions versions offered by this endpoint
   * @param transcript        canonical transcript using the selected version
   * @param proof             signed role proof
   * @return immutable envelope
   */
  public static HandshakeEnvelope create(HandshakeRole role, int compatibility,
      List<ProtocolVersion> supportedVersions, HandshakeTranscript transcript,
      HandshakeProof proof) {
    return new HandshakeEnvelope(role, compatibility, supportedVersions, transcript, proof);
  }

  /**
   * Decodes and bounds-checks an envelope.
   *
   * @param encoded complete envelope bytes
   * @return decoded envelope
   * @throws IOException if framing or nested values are invalid
   */
  public static HandshakeEnvelope decode(byte[] encoded) throws IOException {
    Objects.requireNonNull(encoded, "encoded");
    if (encoded.length == 0 || encoded.length > MAX_BYTES) {
      throw new IOException("handshake envelope exceeds supported bound");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
      if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
        throw new IOException("unsupported handshake envelope");
      }
      int compatibility = input.readUnsignedShort();
      int roleCode = input.readUnsignedByte();
      HandshakeRole[] roles = HandshakeRole.values();
      if (roleCode >= roles.length) {
        throw new IOException("unknown handshake role");
      }
      int versionCount = input.readUnsignedByte();
      if (versionCount == 0 || versionCount > 8) {
        throw new IOException("invalid supported-version count");
      }
      List<ProtocolVersion> supportedVersions = new ArrayList<>(versionCount);
      for (int index = 0; index < versionCount; index++) {
        supportedVersions.add(
            new ProtocolVersion(input.readUnsignedByte(), input.readUnsignedByte()));
      }
      byte[] transcriptBytes = CodecSupport.readBytes(input, HandshakeTranscript.MAX_BYTES,
          FIELD_MESSAGE);
      byte[] proofBytes = CodecSupport.readBytes(input, HandshakeProof.MAX_BYTES, FIELD_MESSAGE);
      if (input.available() != 0) {
        throw new IOException("trailing handshake envelope bytes");
      }
      HandshakeEnvelope value = create(roles[roleCode], compatibility, supportedVersions,
          HandshakeTranscript.decode(transcriptBytes), HandshakeProof.decode(proofBytes));
      CodecSupport.requireCanonical(encoded, value.encoded(), "non-canonical handshake envelope");
      return value;
    } catch (EOFException | IllegalArgumentException exception) {
      throw new IOException("malformed handshake envelope", exception);
    }
  }

  /**
   * Checks the fixed envelope magic before selecting the handshake parser.
   *
   * @param encoded bounded stream frame payload
   * @return whether the payload is an SLH1 handshake envelope candidate
   */
  public static boolean looksLike(byte[] encoded) {
    return encoded != null && encoded.length >= 4
        && (encoded[0] & 255) == 0x53 && (encoded[1] & 255) == 0x4C
        && (encoded[2] & 255) == 0x48 && (encoded[3] & 255) == 0x31;
  }

  /**
   * Reads the compatibility number of an envelope without parsing the rest. It sits right after
   * the magic and the format byte, a position no later layout may move.
   *
   * @param encoded bounded stream frame payload
   * @return the stated number, or -1 if the payload is not an envelope or is too short
   */
  public static int peekCompatibility(byte[] encoded) {
    return looksLike(encoded) && encoded.length >= 7
        ? ((encoded[5] & 255) << 8) | (encoded[6] & 255) : -1;
  }

  private static List<ProtocolVersion> normalizeVersions(List<ProtocolVersion> versions) {
    Objects.requireNonNull(versions, "supported versions");
    List<ProtocolVersion> normalized = versions.stream()
        .distinct()
        .sorted(Collections.reverseOrder())
        .toList();
    if (normalized.isEmpty() || normalized.size() > 8) {
      throw new IllegalArgumentException("supported-version list exceeds bounds");
    }
    return normalized;
  }

  /**
   * Returns canonical envelope bytes.
   *
   * @return fresh encoded bytes
   */
  public byte[] encoded() {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(encodedLength());
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeInt(MAGIC);
        output.writeByte(VERSION);
        output.writeShort(compatibility);
        output.writeByte(role.ordinal());
        output.writeByte(supportedVersions.size());
        for (ProtocolVersion version : supportedVersions) {
          output.writeByte(version.major());
          output.writeByte(version.minor());
        }
        CodecSupport.writeBytes(output, transcript.encoded());
        CodecSupport.writeBytes(output, proof.encoded());
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError("byte array output cannot fail", impossible);
    }
  }

  /**
   * Returns the envelope role.
   *
   * @return role
   */
  public HandshakeRole role() {
    return role;
  }

  /**
   * Returns the compatibility number the sender states.
   *
   * @return compatibility number
   */
  public int compatibility() {
    return compatibility;
  }

  /**
   * Returns the offered protocol versions in canonical order.
   *
   * @return immutable supported-version list
   */
  public List<ProtocolVersion> supportedVersions() {
    return supportedVersions;
  }

  /**
   * Returns the decoded transcript.
   *
   * @return transcript
   */
  public HandshakeTranscript transcript() {
    return transcript;
  }

  /**
   * Returns the decoded proof.
   *
   * @return proof
   */
  public HandshakeProof proof() {
    return proof;
  }

  private int encodedLength() {
    return 9 + 2 * supportedVersions.size() + transcript.encoded().length + 2
        + proof.encoded().length;
  }
}
