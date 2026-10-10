package org.synesis.link.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;

/**
 * A compact, signed, one-way first-contact invitation.
 *
 * <p>The only wire revision is the minimized {@code SLB2} layout. It intentionally
 * carries one endpoint hint instead of a complete
 * {@link org.synesis.link.candidate.CandidateDescriptor}.
 * The inviter's compact Ed25519 public key and signature remain pre-contact
 * trust material: the existing Link handshake authenticates the key supplied
 * by the invitation, so learning an arbitrary key from the peer would be TOFU.
 * Complete candidate state is exchanged after control readiness over the
 * bounded application stream.
 *
 * <p>The binary representation is canonical and bounded. Its URI is suitable
 * for copy/paste and QR rendering, but its endpoint is only a signed routing
 * hint; it does not prove reachability or promise NAT traversal.
 *
 * @since 1.0
 */
public final class CompactBootstrapInvitation {

  /** Maximum compact invitation payload in bytes. */
  static final int MAX_BYTES = 256;
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
  private static final int MAX_ADDRESS_BYTES = 16;
  private static final long MAX_EPOCH_SECONDS = 0xFFFF_FFFFL;
  private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of()
      .parseHex("302a300506032b6570032100");
  private static final byte[] MINIMIZED_SESSION_DOMAIN = "synesis-link/SLB2/session"
      .getBytes(StandardCharsets.US_ASCII);
  private static final Comparator<Candidate> ENDPOINT_ORDER = Comparator
      .comparingInt((Candidate candidate) -> endpointRank(candidate.type()))
      .thenComparingInt(Candidate::priority)
      .thenComparingInt(candidate -> candidate.address()
          .getAddress().length)
      .thenComparing(candidate -> HexFormat.of()
          .formatHex(candidate.address()
              .getAddress()))
      .thenComparingInt(Candidate::port);

  private final ProtocolVersion protocolVersion;
  private final Instant issuedAt;
  private final Instant expiresAt;
  private final byte[] capability;
  private final byte[] hostPublicKey;
  private final Candidate endpoint;
  private final byte[] unsigned;
  private final byte[] signature;
  private final UUID sessionId;

  private CompactBootstrapInvitation(ProtocolVersion protocolVersion,
      Instant issuedAt, Instant expiresAt, byte[] capability, byte[] hostPublicKey,
      Candidate endpoint, byte[] signature) {
    this.protocolVersion = Objects.requireNonNull(protocolVersion, "protocol version");
    this.issuedAt = Objects.requireNonNull(issuedAt, "issued at");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expires at");
    validateInterval(issuedAt, expiresAt);
    this.capability = copyMinimizedCapability(capability);
    this.hostPublicKey = copyCompactPublicKey(hostPublicKey);
    this.endpoint = validateEndpoint(endpoint);
    this.unsigned = encodeUnsigned();
    this.signature = Objects.requireNonNull(signature, "signature").clone();
    if (this.signature.length != SIGNATURE_BYTES || this.unsigned.length + SIGNATURE_BYTES > MAX_BYTES) {
      throw new IllegalArgumentException("compact invitation exceeds supported bound");
    }
    this.sessionId = sessionIdForMinimizedKey(this.hostPublicKey, this.capability,
        this.issuedAt, this.expiresAt);
  }

  /**
   * Creates and signs the minimized SLB2 invitation.
   *
   * <p>SLB2 is fixed to Link protocol v1. Its URI revision supplies the wire
   * version, the payload length supplies the IPv4/IPv6 choice, and the
   * lifetime is encoded as a bounded unsigned second count. The 96-bit
   * capability remains a random online-admission guard; it is not a project
   * credential or a replacement for the host signature.
   *
   * @param host       durable inviter identity
   * @param version    Link protocol version; only v1 is representable in SLB2
   * @param issuedAt   inclusive issue instant
   * @param expiresAt  exclusive expiry instant; no later than ten minutes after issue
   * @param capability fresh 96-bit single-use capability
   * @param endpoint   one non-relay UDP endpoint hint
   * @return canonical signed minimized invitation
   * @throws GeneralSecurityException if signing fails
   * @throws IllegalArgumentException if a field exceeds the minimized bounds
   */
  public static CompactBootstrapInvitation createMinimized(NodeIdentity host,
      ProtocolVersion version, Instant issuedAt, Instant expiresAt, byte[] capability,
      Candidate endpoint) throws GeneralSecurityException {
    Objects.requireNonNull(host, "host identity");
    if (!ProtocolVersion.V1.equals(version)) {
      throw new IllegalArgumentException("SLB2 supports only Link protocol v1");
    }
    byte[] compactKey = compactPublicKey(host.publicKeyEncoded());
    CompactBootstrapInvitation unsigned = new CompactBootstrapInvitation(version,
        issuedAt.truncatedTo(ChronoUnit.SECONDS), expiresAt.truncatedTo(ChronoUnit.SECONDS),
        capability, compactKey, endpoint, new byte[SIGNATURE_BYTES]);
    return new CompactBootstrapInvitation(version,
        unsigned.issuedAt, unsigned.expiresAt, unsigned.capability, compactKey, endpoint,
        host.sign(unsigned.unsigned));
  }

  /**
   * Decodes a bounded canonical SLB2 invitation without trusting its signature.
   *
   * <p>The payload has no inner magic or length field. The URI revision selects
   * this parser, and the complete bounded payload length selects the IPv4 or
   * IPv6 address width. A future layout must use another URI revision rather
   * than changing these lengths in place.
   *
   * @param encoded complete minimized invitation bytes
   * @return decoded invitation requiring {@link #verifyAt(Instant, Duration)} before use
   * @throws IOException if framing, bounds, or canonical values are invalid
   */
  static CompactBootstrapInvitation decodeMinimized(byte[] encoded) throws IOException {
    Objects.requireNonNull(encoded, "minimized compact invitation");
    if (encoded.length != minimizedEncodedLength(4)
        && encoded.length != minimizedEncodedLength(MAX_ADDRESS_BYTES)) {
      throw new IOException("invalid minimized compact invitation length");
    }
    int addressLength = encoded.length == minimizedEncodedLength(4) ? 4 : MAX_ADDRESS_BYTES;
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
      Instant issued = readEpoch(input);
      int lifetimeSeconds = input.readUnsignedShort();
      if (lifetimeSeconds == 0 || lifetimeSeconds > MAX_LIFETIME.toSeconds()) {
        throw new IOException("invalid minimized compact invitation lifetime");
      }
      byte[] capability = readFixed(input, MINIMIZED_CAPABILITY_BYTES);
      byte[] publicKey = readFixed(input, RAW_PUBLIC_KEY_BYTES);
      byte[] address = readFixed(input, addressLength);
      int port = input.readUnsignedShort();
      if (port == 0) {
        throw new IOException("compact endpoint port is zero");
      }
      byte[] signature = readFixed(input, SIGNATURE_BYTES);
      if (input.available() != 0) {
        throw new IOException("trailing minimized compact invitation bytes");
      }
      Instant expires = issued.plusSeconds(lifetimeSeconds);
      CandidateType type = addressLength == MAX_ADDRESS_BYTES
          ? CandidateType.IPV6 : CandidateType.MANUAL;
      Candidate endpoint = new Candidate(type, InetAddress.getByAddress(address), port, 0);
      CompactBootstrapInvitation value = new CompactBootstrapInvitation(
          ProtocolVersion.V1, issued, expires, capability, publicKey,
          endpoint, signature);
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
    return ShareLinks.parse(link, "join", "SLB2-", MAX_LINK_CHARS, "compact invitation",
        "unsupported compact invitation link format", CompactBootstrapInvitation::decodeMinimized);
  }

  /**
   * Checks whether a URI selects the compact bootstrap revision.
   *
   * @param link candidate URI
   * @return {@code true} for the strict SLB2 join prefix
   */
  public static boolean isShareLink(String link) {
    return link != null && link.startsWith("synesis://join/SLB2-");
  }

  /**
   * Selects one deterministic non-relay endpoint for compact first contact.
   *
   * @param candidates complete local candidate collection
   * @return the preferred endpoint hint
   * @throws IllegalArgumentException if no usable endpoint exists
   */
  public static Candidate selectEndpoint(Collection<Candidate> candidates) {
    Objects.requireNonNull(candidates, "candidates");
    return candidates.stream()
        .filter(Objects::nonNull)
        .filter(candidate -> candidate.type() != CandidateType.RELAY)
        .filter(candidate -> candidate.address()
            .getAddress().length == 4 || candidate.address()
            .getAddress().length == MAX_ADDRESS_BYTES)
        .sorted(ENDPOINT_ORDER)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no usable compact endpoint"));
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
    return "synesis://join/SLB2-" + Base64.getUrlEncoder()
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
   * Returns the single signed endpoint hint.
   *
   * @return immutable endpoint value
   */
  public Candidate endpoint() {
    return endpoint;
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

  private static Candidate validateEndpoint(Candidate value) {
    Objects.requireNonNull(value, "endpoint");
    if (value.type() == CandidateType.RELAY || value.address()
        .isAnyLocalAddress() || value.address()
        .isMulticastAddress()
        || value.address() instanceof Inet6Address ipv6 && ipv6.isLinkLocalAddress()) {
      throw new IllegalArgumentException("compact endpoint is not directly usable");
    }
    int length = value.address()
        .getAddress().length;
    if (length != 4 && length != MAX_ADDRESS_BYTES) {
      throw new IllegalArgumentException("compact endpoint must be IPv4 or IPv6");
    }
    return new Candidate(value.type(), value.address(), value.port(), 0);
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
        byte[] address = endpoint.address().getAddress();
        output.writeInt((int) issuedAt.getEpochSecond());
        output.writeShort(Math.toIntExact(Duration.between(issuedAt, expiresAt).getSeconds()));
        output.write(capability);
        output.write(hostPublicKey);
        output.write(address);
        output.writeShort(endpoint.port());
      }
      if (bytes.size() + SIGNATURE_BYTES > MAX_BYTES) {
        throw new IllegalArgumentException("compact invitation exceeds supported bound");
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError("byte array output cannot fail", impossible);
    }
  }

  private static int minimizedEncodedLength(int addressLength) {
    return Integer.BYTES + Short.BYTES + MINIMIZED_CAPABILITY_BYTES + RAW_PUBLIC_KEY_BYTES
        + addressLength + Short.BYTES + SIGNATURE_BYTES;
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

  private static int endpointRank(CandidateType type) {
    return switch (type) {
      case SERVER_REFLEXIVE -> 0;
      case IPV6 -> 1;
      case MANUAL -> 2;
      case MAPPED_IPV4 -> 3;
      case LAN -> 4;
      case RELAY -> 5;
    };
  }
}
