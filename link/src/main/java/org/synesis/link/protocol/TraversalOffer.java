package org.synesis.link.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.identity.NodeIdentity;

/**
 * A bounded, signed first leg of a bilateral direct-traversal exchange.
 *
 * <p>The offer carries routing candidates but does not make them an identity
 * assertion. The durable initiator identity, expected responder, invitation binding, session,
 * expiry, and fresh attempt nonce are all covered by the Ed25519 signature.
 *
 * @since 1.0
 */
public final class TraversalOffer {

  /**
   * Maximum encoded offer size.
   */
  static final int MAX_BYTES = 24_576;
  /**
   * Required attempt nonce size.
   */
  public static final int NONCE_BYTES = 32;
  /**
   * Required invitation digest size.
   */
  public static final int DIGEST_BYTES = 32;
  /**
   * Default clock-skew allowance for exchange validation.
   */
  public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofMinutes(2);

  private static final int MAGIC = 0x534C4F31;
  /** The legacy signed offer format. */
  static final int FORMAT_VERSION_V1 = 1;
  /** The signed offer format carrying a mandatory return target. */
  static final int FORMAT_VERSION_V2 = 2;
  private static final int MAX_NODE_ID_BYTES = 128;
  private static final int MAX_KEY_BYTES = 256;
  private static final int MAX_SIGNATURE_BYTES = 128;
  private static final String FIELD_MESSAGE = "invalid bounded traversal field";

  private final ProtocolVersion protocolVersion;
  private final int formatVersion;
  private final ReturnTarget returnTarget;
  private final UUID sessionId;
  private final String initiatorNodeId;
  private final byte[] initiatorPublicKey;
  private final String expectedResponderNodeId;
  private final byte[] invitationDigest;
  private final Instant issuedAt;
  private final Instant expiresAt;
  private final byte[] attemptNonce;
  private final byte[] descriptor;
  private final byte[] unsigned;
  private final byte[] signature;

  private TraversalOffer(int formatVersion, ProtocolVersion protocolVersion, ReturnTarget returnTarget,
      UUID sessionId, String initiatorNodeId,
      byte[] initiatorPublicKey, String expectedResponderNodeId, byte[] invitationDigest,
      Instant issuedAt, Instant expiresAt, byte[] attemptNonce, byte[] descriptor,
      byte[] signature) {
    if (formatVersion != FORMAT_VERSION_V1 && formatVersion != FORMAT_VERSION_V2) {
      throw new IllegalArgumentException("unsupported traversal offer format");
    }
    if (formatVersion == FORMAT_VERSION_V2 && returnTarget == null) {
      throw new IllegalArgumentException("v2 traversal offer requires a return target");
    }
    if (formatVersion == FORMAT_VERSION_V1 && returnTarget != null) {
      throw new IllegalArgumentException("v1 traversal offer cannot carry a return target");
    }
    this.formatVersion = formatVersion;
    this.returnTarget = returnTarget;
    this.protocolVersion = Objects.requireNonNull(protocolVersion, "protocol version");
    this.sessionId = Objects.requireNonNull(sessionId, "session ID");
    this.initiatorNodeId = CodecSupport.boundedText(initiatorNodeId, MAX_NODE_ID_BYTES, "initiator node ID");
    this.initiatorPublicKey = CodecSupport.boundedBytes(initiatorPublicKey, MAX_KEY_BYTES,
        "initiator public key");
    this.expectedResponderNodeId = boundedOptionalText(expectedResponderNodeId, MAX_NODE_ID_BYTES,
        "expected responder node ID");
    this.invitationDigest = CodecSupport.exactBytes(invitationDigest, DIGEST_BYTES, "invitation digest");
    this.issuedAt = Objects.requireNonNull(issuedAt, "issued at");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expires at");
    if (!expiresAt.isAfter(issuedAt)) {
      throw new IllegalArgumentException("offer validity interval is empty");
    }
    this.attemptNonce = CodecSupport.exactBytes(attemptNonce, NONCE_BYTES, "attempt nonce");
    this.descriptor = CodecSupport.boundedBytes(descriptor, CandidateDescriptor.MAX_BYTES,
        "candidate descriptor");
    this.unsigned = encodeUnsigned();
    this.signature = CodecSupport.boundedBytes(signature, MAX_SIGNATURE_BYTES, "signature");
    if (unsigned.length + 2 + signature.length > MAX_BYTES) {
      throw new IllegalArgumentException("offer exceeds supported bound");
    }
    if (!NodeIdentity.deriveNodeId(this.initiatorPublicKey).equals(this.initiatorNodeId)) {
      throw new IllegalArgumentException("initiator node ID does not match public key");
    }
  }

  /**
   * Creates a signed offer.
   *
   * @param initiator               signing durable initiator identity
   * @param expectedResponderNodeId optional expected durable responder ID; {@code null} permits any
   *                                authenticated responder
   * @param sessionId               fresh Link session ID
   * @param invitationDigest        SHA-256 digest binding the admission invitation
   * @param issuedAt                inclusive validity start
   * @param expiresAt               exclusive validity end
   * @param attemptNonce            fresh exchange nonce
   * @param descriptor              signed initiator candidate descriptor
   * @return signed offer
   * @throws GeneralSecurityException if signing or descriptor verification fails
   */
  public static TraversalOffer create(NodeIdentity initiator, String expectedResponderNodeId,
      UUID sessionId, byte[] invitationDigest, Instant issuedAt, Instant expiresAt,
      byte[] attemptNonce, CandidateDescriptor descriptor) throws GeneralSecurityException {
    return sign(FORMAT_VERSION_V1, null, initiator, expectedResponderNodeId, sessionId,
        invitationDigest, issuedAt, expiresAt, attemptNonce, descriptor);
  }

  /**
   * Creates a signed v2 offer with a mandatory stable host project target.
   *
   * @param initiator               signing durable initiator identity
   * @param expectedResponderNodeId optional expected durable responder ID; {@code null} permits any
   * @param sessionId               fresh Link session ID
   * @param invitationDigest        SHA-256 digest binding the admission invitation
   * @param issuedAt                inclusive validity start
   * @param expiresAt               exclusive validity end
   * @param attemptNonce            fresh exchange nonce
   * @param descriptor              signed initiator candidate descriptor
   * @param returnTarget            stable host project routing target
   * @return signed v2 offer
   * @throws GeneralSecurityException if signing or descriptor verification fails
   */
  public static TraversalOffer createV2(NodeIdentity initiator, String expectedResponderNodeId,
      UUID sessionId, byte[] invitationDigest, Instant issuedAt, Instant expiresAt,
      byte[] attemptNonce, CandidateDescriptor descriptor, ReturnTarget returnTarget)
      throws GeneralSecurityException {
    return sign(FORMAT_VERSION_V2, returnTarget, initiator, expectedResponderNodeId, sessionId,
        invitationDigest, issuedAt, expiresAt, attemptNonce, descriptor);
  }

  private static TraversalOffer sign(int formatVersion, ReturnTarget returnTarget,
      NodeIdentity initiator, String expectedResponderNodeId, UUID sessionId,
      byte[] invitationDigest, Instant issuedAt, Instant expiresAt, byte[] attemptNonce,
      CandidateDescriptor descriptor) throws GeneralSecurityException {
    Objects.requireNonNull(initiator, "initiator identity");
    Objects.requireNonNull(descriptor, "candidate descriptor");
    if (formatVersion == FORMAT_VERSION_V2) {
      Objects.requireNonNull(returnTarget, "return target");
    }
    if (!initiator.nodeId().equals(descriptor.nodeId())
        || !Arrays.equals(initiator.publicKeyEncoded(), descriptor.publicKeyEncoded())
        || !descriptor.verify()) {
      throw new IllegalArgumentException("descriptor is not bound to initiator identity");
    }
    Instant canonicalIssuedAt = Objects.requireNonNull(issuedAt, "issued at")
        .truncatedTo(ChronoUnit.SECONDS);
    Instant canonicalExpiresAt = Objects.requireNonNull(expiresAt, "expires at")
        .truncatedTo(ChronoUnit.SECONDS);
    TraversalOffer unsigned = new TraversalOffer(formatVersion, ProtocolVersion.V1, returnTarget,
        sessionId, initiator.nodeId(), initiator.publicKeyEncoded(), expectedResponderNodeId,
        invitationDigest, canonicalIssuedAt, canonicalExpiresAt, attemptNonce,
        descriptor.encoded(), new byte[]{1});
    return new TraversalOffer(formatVersion, ProtocolVersion.V1, returnTarget, sessionId,
        initiator.nodeId(), initiator.publicKeyEncoded(), expectedResponderNodeId, invitationDigest,
        canonicalIssuedAt, canonicalExpiresAt, attemptNonce, descriptor.encoded(),
        initiator.sign(unsigned.unsigned));
  }

  /**
   * Decodes a bounded offer without trusting its signature.
   *
   * @param encoded complete canonical offer
   * @return decoded offer
   * @throws IOException if framing, bounds, or canonical encoding is invalid
   */
  public static TraversalOffer decode(byte[] encoded) throws IOException {
    Objects.requireNonNull(encoded, "offer");
    if (encoded.length == 0 || encoded.length > MAX_BYTES) {
      throw new IOException("offer exceeds supported bound");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
      if (input.readInt() != MAGIC) {
        throw new IOException("unsupported traversal offer format");
      }
      int format = input.readUnsignedByte();
      if (format != FORMAT_VERSION_V1 && format != FORMAT_VERSION_V2) {
        throw new IOException("unsupported traversal offer format");
      }
      ProtocolVersion version = new ProtocolVersion(input.readUnsignedByte(),
          input.readUnsignedByte());
      ReturnTarget target = format == FORMAT_VERSION_V2 ? ReturnTarget.read(input) : null;
      UUID session = new UUID(input.readLong(), input.readLong());
      String initiator = CodecSupport.readText(input, MAX_NODE_ID_BYTES, FIELD_MESSAGE);
      byte[] publicKey = CodecSupport.readBytes(input, MAX_KEY_BYTES, FIELD_MESSAGE);
      String responder = readOptionalText(input, MAX_NODE_ID_BYTES);
      byte[] invitation = readExactBytes(input, DIGEST_BYTES);
      Instant issued = Instant.ofEpochSecond(input.readLong());
      Instant expires = Instant.ofEpochSecond(input.readLong());
      byte[] nonce = readExactBytes(input, NONCE_BYTES);
      byte[] descriptor = CodecSupport.readBytes(input, CandidateDescriptor.MAX_BYTES,
          FIELD_MESSAGE);
      byte[] signature = CodecSupport.readBytes(input, MAX_SIGNATURE_BYTES, FIELD_MESSAGE);
      if (input.available() != 0) {
        throw new IOException("trailing offer bytes");
      }
      TraversalOffer value = new TraversalOffer(format, version, target, session, initiator,
          publicKey, responder, invitation, issued, expires, nonce, descriptor, signature);
      CodecSupport.requireCanonical(encoded, value.encoded(), "non-canonical traversal offer");
      return value;
    } catch (EOFException | IllegalArgumentException exception) {
      throw new IOException("malformed traversal offer", exception);
    }
  }

  private static String boundedOptionalText(String value, int maximum, String label) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    return CodecSupport.boundedText(value, maximum, label);
  }

  private static String readOptionalText(DataInputStream input, int maximum) throws IOException {
    int length = input.readUnsignedShort();
    if (length > maximum || length > input.available()) {
      throw new IOException(FIELD_MESSAGE);
    }
    return new String(input.readNBytes(length), StandardCharsets.UTF_8);
  }

  private static byte[] readExactBytes(DataInputStream input, int expected) throws IOException {
    return CodecSupport.readExactBytes(input, expected, FIELD_MESSAGE,
        "invalid fixed-size traversal field");
  }

  /**
   * Verifies the offer signature, embedded descriptor, peer binding, and interval.
   *
   * @param now                     current verification time
   * @param allowedClockSkew        accepted issue-time skew
   * @param expectedResponderNodeId local durable responder ID
   * @return whether the offer is authentic and current
   * @throws GeneralSecurityException if key verification fails
   * @throws IOException              if the embedded descriptor is malformed
   */
  public boolean verifyAt(Instant now, Duration allowedClockSkew, String expectedResponderNodeId)
      throws GeneralSecurityException, IOException {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(allowedClockSkew, "allowed clock skew");
    Objects.requireNonNull(expectedResponderNodeId, "expected responder node ID");
    if (allowedClockSkew.isNegative() || (!this.expectedResponderNodeId.isEmpty()
        && !this.expectedResponderNodeId.equals(expectedResponderNodeId))
        || now.isBefore(issuedAt.minus(allowedClockSkew)) || !now.isBefore(expiresAt)) {
      return false;
    }
    CandidateDescriptor candidateDescriptor = CandidateDescriptor.decode(descriptor);
    return candidateDescriptor.isValidAt(now, allowedClockSkew)
        && candidateDescriptor.nodeId().equals(initiatorNodeId)
        && Arrays.equals(candidateDescriptor.publicKeyEncoded(), initiatorPublicKey)
        && NodeIdentity.verifySignature(initiatorPublicKey, unsigned, signature);
  }

  /**
   * Returns the canonical signed offer bytes.
   *
   * @return canonical signed offer bytes
   */
  public byte[] encoded() {
    return CodecSupport.signed(unsigned, signature);
  }

  /**
   * Returns the protocol version.
   *
   * @return protocol version
   */
  public ProtocolVersion protocolVersion() {
    return protocolVersion;
  }

  /**
   * Returns the explicit outer signed-offer format.
   *
   * @return signed-offer format
   */
  int formatVersion() {
    return formatVersion;
  }

  /**
   * Returns the v2 return target, or empty for an explicit v1 offer.
   *
   * @return optional signed return target
   */
  Optional<ReturnTarget> returnTarget() {
    return Optional.ofNullable(returnTarget);
  }

  /**
   * Returns the Link session ID.
   *
   * @return Link session ID
   */
  public UUID sessionId() {
    return sessionId;
  }

  /**
   * Returns the durable initiator node ID.
   *
   * @return durable initiator node ID
   */
  public String initiatorNodeId() {
    return initiatorNodeId;
  }

  /**
   * Returns a copy of the initiator public key.
   *
   * @return initiator public key copy
   */
  byte[] initiatorPublicKeyEncoded() {
    return initiatorPublicKey.clone();
  }

  /**
   * Returns the expected durable responder node ID.
   *
   * @return expected durable responder node ID
   */
  String expectedResponderNodeId() {
    return expectedResponderNodeId;
  }

  /**
   * Returns a copy of the invitation binding digest.
   *
   * @return invitation binding digest copy
   */
  public byte[] invitationDigest() {
    return invitationDigest.clone();
  }

  /**
   * Returns the inclusive validity start.
   *
   * @return inclusive validity start
   */
  public Instant issuedAt() {
    return issuedAt;
  }

  /**
   * Returns the exclusive validity end.
   *
   * @return exclusive validity end
   */
  public Instant expiresAt() {
    return expiresAt;
  }

  /**
   * Returns a copy of the attempt nonce.
   *
   * @return attempt nonce copy
   */
  byte[] attemptNonce() {
    return attemptNonce.clone();
  }

  /**
   * Returns the signed initiator candidate descriptor bytes.
   *
   * @return signed initiator candidate descriptor bytes
   */
  public byte[] descriptorEncoded() {
    return descriptor.clone();
  }

  /**
   * Returns the SHA-256 digest used to bind an answer to this exact offer.
   *
   * @return offer digest
   */
  public byte[] digest() {
    return CodecSupport.sha256(encoded());
  }

  private byte[] encodeUnsigned() {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeInt(MAGIC);
        output.writeByte(formatVersion);
        output.writeByte(protocolVersion.major());
        output.writeByte(protocolVersion.minor());
        if (formatVersion == FORMAT_VERSION_V2) {
          returnTarget.write(output);
        }
        output.writeLong(sessionId.getMostSignificantBits());
        output.writeLong(sessionId.getLeastSignificantBits());
        CodecSupport.writeText(output, initiatorNodeId);
        CodecSupport.writeBytes(output, initiatorPublicKey);
        CodecSupport.writeText(output, expectedResponderNodeId);
        CodecSupport.writeBytes(output, invitationDigest);
        output.writeLong(issuedAt.getEpochSecond());
        output.writeLong(expiresAt.getEpochSecond());
        CodecSupport.writeBytes(output, attemptNonce);
        CodecSupport.writeBytes(output, descriptor);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
