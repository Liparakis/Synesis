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
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.identity.NodeIdentity;

/**
 * A compact, signed, one-way first-contact invitation.
 *
 * <p>The only wire revision is the minimized {@code SLB3} layout. It intentionally
 * carries a few endpoint hints instead of a complete
 * {@link org.synesis.link.candidate.CandidateDescriptor}. One hint is not enough: the inviter
 * cannot know which of its addresses the joiner's network reaches.
 * The inviter's compact Ed25519 public key and signature remain pre-contact
 * trust material: the existing Link handshake authenticates the key supplied
 * by the invitation, so learning an arbitrary key from the peer would be TOFU.
 * Complete candidate state is exchanged after control readiness over the
 * bounded application stream.
 *
 * <p>The binary representation is canonical and bounded. Its URI is suitable
 * for copy and paste, but its endpoints are only signed routing
 * hints; they do not prove reachability or promise NAT traversal.
 *
 * @since 1.0
 */
public final class CompactBootstrapInvitation {

  /** Maximum number of endpoint hints one invitation carries. */
  public static final int MAX_ENDPOINTS = CompactEndpoints.MAX;
  /** Maximum compact invitation URI length accepted by the parser. */
  public static final int MAX_LINK_CHARS = 512;
  /** Size of the minimized single-use admission capability. */
  public static final int MINIMIZED_CAPABILITY_BYTES = 12;
  /** Maximum compact invitation lifetime. */
  public static final Duration MAX_LIFETIME = Duration.ofMinutes(10);
  /** Clock skew tolerated when checking the issue time. */
  public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofMinutes(2);

  private static final int SIGNATURE_BYTES = 64;
  private static final int RAW_PUBLIC_KEY_BYTES = 32;
  /** Maximum compact invitation payload in bytes: every endpoint an IPv6 address. */
  static final int MAX_BYTES = Integer.BYTES + Short.BYTES + MINIMIZED_CAPABILITY_BYTES
      + RAW_PUBLIC_KEY_BYTES + Byte.BYTES
      + MAX_ENDPOINTS * CompactEndpoints.MAX_ENCODED_BYTES_EACH + SIGNATURE_BYTES;
  private static final long MAX_EPOCH_SECONDS = 0xFFFF_FFFFL;
  private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of()
      .parseHex("302a300506032b6570032100");
  private static final byte[] MINIMIZED_SESSION_DOMAIN = "synesis-link/SLB3/session"
      .getBytes(StandardCharsets.US_ASCII);
  private final ProtocolVersion protocolVersion;
  private final Instant issuedAt;
  private final Instant expiresAt;
  private final byte[] capability;
  private final byte[] hostPublicKey;
  private final List<Candidate> endpoints;
  private final byte[] unsigned;
  private final byte[] signature;
  private final UUID sessionId;

  private CompactBootstrapInvitation(ProtocolVersion protocolVersion,
      Instant issuedAt, Instant expiresAt, byte[] capability, byte[] hostPublicKey,
      List<Candidate> endpoints, byte[] signature) {
    this.protocolVersion = Objects.requireNonNull(protocolVersion, "protocol version");
    this.issuedAt = Objects.requireNonNull(issuedAt, "issued at");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expires at");
    validateInterval(issuedAt, expiresAt);
    this.capability = copyMinimizedCapability(capability);
    this.hostPublicKey = copyCompactPublicKey(hostPublicKey);
    this.endpoints = CompactEndpoints.validate(endpoints);
    this.unsigned = encodeUnsigned();
    this.signature = Objects.requireNonNull(signature, "signature").clone();
    if (this.signature.length != SIGNATURE_BYTES) {
      throw new IllegalArgumentException("compact invitation signature must contain 64 bytes");
    }
    this.sessionId = sessionIdForMinimizedKey(this.hostPublicKey, this.capability,
        this.issuedAt, this.expiresAt);
  }

  /**
   * Creates and signs the minimized SLB3 invitation.
   *
   * <p>SLB3 is fixed to Link protocol v1. Its URI revision supplies the wire
   * version, each endpoint states its own address width, and the
   * lifetime is encoded as a bounded unsigned second count. The 96-bit
   * capability remains a random online-admission guard; it is not a project
   * credential or a replacement for the host signature.
   *
   * @param host       durable inviter identity
   * @param version    Link protocol version; only v1 is representable in SLB3
   * @param issuedAt   inclusive issue instant
   * @param expiresAt  exclusive expiry instant; no later than ten minutes after issue
   * @param capability fresh 96-bit single-use capability
   * @param endpoints  one to {@link #MAX_ENDPOINTS} distinct non-relay UDP endpoint hints, in
   *                   the order the joiner should prefer them
   * @return canonical signed minimized invitation
   * @throws GeneralSecurityException if signing fails
   * @throws IllegalArgumentException if a field exceeds the minimized bounds
   */
  public static CompactBootstrapInvitation createMinimized(NodeIdentity host,
      ProtocolVersion version, Instant issuedAt, Instant expiresAt, byte[] capability,
      List<Candidate> endpoints) throws GeneralSecurityException {
    Objects.requireNonNull(host, "host identity");
    if (!ProtocolVersion.V1.equals(version)) {
      throw new IllegalArgumentException("SLB3 supports only Link protocol v1");
    }
    byte[] compactKey = compactPublicKey(host.publicKeyEncoded());
    CompactBootstrapInvitation unsigned = new CompactBootstrapInvitation(version,
        issuedAt.truncatedTo(ChronoUnit.SECONDS), expiresAt.truncatedTo(ChronoUnit.SECONDS),
        capability, compactKey, endpoints, new byte[SIGNATURE_BYTES]);
    return new CompactBootstrapInvitation(version,
        unsigned.issuedAt, unsigned.expiresAt, unsigned.capability, compactKey, endpoints,
        host.sign(unsigned.unsigned));
  }

  /**
   * Decodes a bounded canonical SLB3 invitation without trusting its signature.
   *
   * <p>The payload has no inner magic: the URI revision selects this parser. After the fixed
   * fields come an endpoint count, then for each endpoint its address width (4 or 16), address,
   * and port, then the signature. A future layout must use another URI revision rather than
   * changing this one in place.
   *
   * @param encoded complete minimized invitation bytes
   * @return decoded invitation requiring {@link #verifyAt(Instant, Duration)} before use
   * @throws IOException if framing, bounds, or canonical values are invalid
   */
  static CompactBootstrapInvitation decodeMinimized(byte[] encoded) throws IOException {
    Objects.requireNonNull(encoded, "minimized compact invitation");
    if (encoded.length > MAX_BYTES) {
      throw new IOException("invalid minimized compact invitation length");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
      Instant issued = readEpoch(input);
      int lifetimeSeconds = input.readUnsignedShort();
      if (lifetimeSeconds == 0 || lifetimeSeconds > MAX_LIFETIME.toSeconds()) {
        throw new IOException("invalid minimized compact invitation lifetime");
      }
      byte[] capability = readFixed(input, MINIMIZED_CAPABILITY_BYTES);
      byte[] publicKey = readFixed(input, RAW_PUBLIC_KEY_BYTES);
      List<Candidate> endpoints = CompactEndpoints.read(input);
      byte[] signature = readFixed(input, SIGNATURE_BYTES);
      if (input.available() != 0) {
        throw new IOException("trailing minimized compact invitation bytes");
      }
      CompactBootstrapInvitation value = new CompactBootstrapInvitation(
          ProtocolVersion.V1, issued, issued.plusSeconds(lifetimeSeconds), capability, publicKey,
          endpoints, signature);
      CodecSupport.requireCanonical(encoded, value.encoded(),
          "non-canonical minimized compact invitation");
      return value;
    } catch (EOFException | IllegalArgumentException | java.time.DateTimeException exception) {
      throw new IOException("malformed minimized compact invitation", exception);
    }
  }

  /**
   * Parses a compact copyable join URI.
   *
   * @param link compact join URI
   * @return decoded invitation requiring signature and time verification
   * @throws IOException if the URI or payload is malformed or oversized
   */
  public static CompactBootstrapInvitation fromShareLink(String link) throws IOException {
    Objects.requireNonNull(link, "compact invitation link");
    return ShareLinks.parse(link, "join", "SLB3-", MAX_LINK_CHARS, "compact invitation",
        "unsupported compact invitation link format", CompactBootstrapInvitation::decodeMinimized);
  }

  /**
   * Checks whether a URI selects the compact bootstrap revision.
   *
   * @param link candidate URI
   * @return {@code true} for the strict SLB3 join prefix
   */
  public static boolean isShareLink(String link) {
    return link != null && link.startsWith("synesis://join/SLB3-");
  }

  /**
   * Selects the endpoint hints for compact first contact, most promising first.
   *
   * <p>Every kind of candidate is represented before any kind is repeated, so a machine with more
   * addresses than fit still offers a LAN address beside its public ones: which one works depends
   * on the joiner's network, which the inviter cannot see.
   *
   * @param candidates complete local candidate collection
   * @return one to {@link #MAX_ENDPOINTS} distinct endpoint hints in preference order
   * @throws IllegalArgumentException if no usable endpoint exists
   */
  public static List<Candidate> selectEndpoints(Collection<Candidate> candidates) {
    return CompactEndpoints.select(candidates);
  }

  /**
   * Checks the inviter signature, identity binding, and validity interval.
   *
   * @param now              local wall-clock instant
   * @param allowedClockSkew tolerated issue-time skew
   * @return true only for a currently authentic invitation
   * @throws GeneralSecurityException if key parsing or signature verification fails
   */
  public boolean verifyAt(Instant now, Duration allowedClockSkew)
      throws GeneralSecurityException {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(allowedClockSkew, "allowed clock skew");
    if (allowedClockSkew.isNegative()) {
      throw new IllegalArgumentException("clock skew must be non-negative");
    }
    if (now.isBefore(issuedAt.minus(allowedClockSkew)) || !now.isBefore(expiresAt)) {
      return false;
    }
    return NodeIdentity.verifySignature(publicKeyEncoded(), unsigned, signature)
        && NodeIdentity.deriveNodeId(publicKeyEncoded()).equals(hostNodeId());
  }

  /**
   * Returns the canonical compact URI.
   *
   * @return copyable signed compact invitation
   */
  public String shareLink() {
    return "synesis://join/SLB3-" + Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(encoded());
  }

  /**
   * Returns complete canonical invitation bytes.
   *
   * @return a fresh encoded payload
   */
  public byte[] encoded() {
    byte[] result = Arrays.copyOf(unsigned, unsigned.length + signature.length);
    System.arraycopy(signature, 0, result, unsigned.length, signature.length);
    return result;
  }

  /**
   * Returns the negotiated Link protocol version.
   *
   * @return protocol version
   */
  public ProtocolVersion protocolVersion() {
    return protocolVersion;
  }

  /**
   * Returns the derived session identifier bound to this invitation.
   *
   * @return deterministic invitation session identifier
   */
  public UUID sessionId() {
    return sessionId;
  }

  /**
   * Returns the issue instant.
   *
   * @return inclusive issue instant
   */
  public Instant issuedAt() {
    return issuedAt;
  }

  /**
   * Returns the expiry instant.
   *
   * @return exclusive expiry instant
   */
  public Instant expiresAt() {
    return expiresAt;
  }

  /**
   * Returns a copy of the single-use capability.
   *
   * @return admission capability bytes
   */
  public byte[] capability() {
    return capability.clone();
  }

  /**
   * Returns the durable inviter node ID derived from the compact public key.
   *
   * @return inviter node ID
   */
  public String hostNodeId() {
    return NodeIdentity.deriveNodeId(publicKeyEncoded());
  }

  /**
   * Returns the inviter's canonical X.509 public-key encoding.
   *
   * @return public-key bytes
   */
  public byte[] publicKeyEncoded() {
    byte[] result = Arrays.copyOf(ED25519_SPKI_PREFIX,
        ED25519_SPKI_PREFIX.length + RAW_PUBLIC_KEY_BYTES);
    System.arraycopy(hostPublicKey, 0, result, ED25519_SPKI_PREFIX.length, RAW_PUBLIC_KEY_BYTES);
    return result;
  }

  /**
   * Returns the signed endpoint hints in the inviter's preference order.
   *
   * @return immutable list of one to {@link #MAX_ENDPOINTS} endpoints
   */
  public List<Candidate> endpoints() {
    return endpoints;
  }

  /**
   * Derives the session identifier used by the minimized admission path.
   *
   * @param hostPublicKey canonical X.509 host public key
   * @param capability minimized 96-bit admission capability
   * @param issuedAt issue instant
   * @param expiresAt expiry instant
   * @return deterministic minimized session identifier
   */
  public static UUID sessionIdForMinimized(byte[] hostPublicKey, byte[] capability,
      Instant issuedAt, Instant expiresAt) {
    Objects.requireNonNull(hostPublicKey, "host public key");
    Objects.requireNonNull(capability, "capability");
    Objects.requireNonNull(issuedAt, "issued at");
    Objects.requireNonNull(expiresAt, "expires at");
    Instant canonicalIssuedAt = issuedAt.truncatedTo(ChronoUnit.SECONDS);
    Instant canonicalExpiresAt = expiresAt.truncatedTo(ChronoUnit.SECONDS);
    validateInterval(canonicalIssuedAt, canonicalExpiresAt);
    byte[] compactCapability = copyMinimizedCapability(capability);
    byte[] compactKey = compactPublicKey(hostPublicKey);
    return sessionIdForMinimizedKey(compactKey, compactCapability, canonicalIssuedAt,
        canonicalExpiresAt);
  }

  private static UUID sessionIdForMinimizedKey(byte[] compactKey, byte[] capability,
      Instant issuedAt, Instant expiresAt) {
    byte[] digest = CodecSupport.sha256(MINIMIZED_SESSION_DOMAIN, compactKey, capability,
        longBytes(issuedAt.getEpochSecond()), longBytes(expiresAt.getEpochSecond()));
    return uuidFromDigest(digest);
  }

  private static UUID uuidFromDigest(byte[] digest) {
    long most = 0;
    long least = 0;
    for (int index = 0; index < Long.BYTES; index++) {
      most = (most << 8) | (digest[index] & 0xFFL);
      least = (least << 8) | (digest[index + Long.BYTES] & 0xFFL);
    }
    return new UUID(most, least);
  }

  private static byte[] compactPublicKey(byte[] encoded) {
    if (encoded == null || encoded.length != ED25519_SPKI_PREFIX.length + RAW_PUBLIC_KEY_BYTES
        || !Arrays.equals(ED25519_SPKI_PREFIX,
        Arrays.copyOf(encoded, ED25519_SPKI_PREFIX.length))) {
      throw new IllegalArgumentException("unsupported Ed25519 public-key encoding");
    }
    return Arrays.copyOfRange(encoded, ED25519_SPKI_PREFIX.length, encoded.length);
  }

  private static byte[] copyCompactPublicKey(byte[] value) {
    if (value == null || value.length != RAW_PUBLIC_KEY_BYTES) {
      throw new IllegalArgumentException("compact public key must contain 32 bytes");
    }
    return value.clone();
  }

  private static byte[] copyMinimizedCapability(byte[] value) {
    if (value == null || value.length != MINIMIZED_CAPABILITY_BYTES) {
      throw new IllegalArgumentException("minimized capability must contain 12 bytes");
    }
    return value.clone();
  }

  private static void validateInterval(Instant issuedAt, Instant expiresAt) {
    if (!expiresAt.isAfter(issuedAt)) {
      throw new IllegalArgumentException("compact invitation interval is empty");
    }
    Duration lifetime = Duration.between(issuedAt, expiresAt);
    if (lifetime.isNegative() || lifetime.compareTo(MAX_LIFETIME) > 0
        || issuedAt.getEpochSecond() < 0 || expiresAt.getEpochSecond() < 0
        || issuedAt.getEpochSecond() > MAX_EPOCH_SECONDS
        || expiresAt.getEpochSecond() > MAX_EPOCH_SECONDS) {
      throw new IllegalArgumentException("compact invitation interval exceeds supported bound");
    }
  }

  private byte[] encodeUnsigned() {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeInt((int) issuedAt.getEpochSecond());
        output.writeShort(Math.toIntExact(Duration.between(issuedAt, expiresAt).getSeconds()));
        output.write(capability);
        output.write(hostPublicKey);
        CompactEndpoints.write(output, endpoints);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError("byte array output cannot fail", impossible);
    }
  }

  private static Instant readEpoch(DataInputStream input) throws IOException {
    return Instant.ofEpochSecond(Integer.toUnsignedLong(input.readInt()));
  }

  private static byte[] readFixed(DataInputStream input, int length) throws IOException {
    if (length < 0 || length > input.available()) {
      throw new IOException("truncated compact invitation field");
    }
    return input.readNBytes(length);
  }

  private static byte[] longBytes(long value) {
    byte[] bytes = new byte[Long.BYTES];
    for (int index = Long.BYTES - 1; index >= 0; index--) {
      bytes[index] = (byte) value;
      value >>>= Byte.SIZE;
    }
    return bytes;
  }
}
