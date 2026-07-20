package org.synesis.link.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.identity.NodeIdentity;

/**
 * A bounded, signed, single-session invitation for terminal onboarding.
 *
 * <p>The invitation contains no private material. Its bearer capability is
 * bound to the host descriptor, session ID, protocol version, and validity
 * interval by the host Ed25519 signature. Possession authorizes one admission
 * attempt only; mutual Synesis identity proofs remain mandatory.
 *
 * @since 1.0
 */
public final class SessionInvitation {
    /** Invitation format version. */
    public static final int FORMAT_VERSION = 1;
    /** Maximum raw invitation bytes accepted before parsing. */
    public static final int MAX_BYTES = 12_288;
    /** Maximum share-link characters accepted by the parser. */
    public static final int MAX_LINK_CHARS = 16_384;
    /** Default invitation validity. */
    public static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(10);
    /** Capability size in bytes. */
    public static final int CAPABILITY_BYTES = 32;

    private static final int MAGIC = 0x53494E31;
    private static final int MAX_SIGNATURE_BYTES = 128;
    private final ProtocolVersion protocolVersion;
    private final UUID sessionId;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final byte[] capability;
    private final byte[] descriptor;
    private final byte[] signature;
    private final byte[] unsigned;

    private SessionInvitation(ProtocolVersion protocolVersion, UUID sessionId, Instant issuedAt,
            Instant expiresAt, byte[] capability, byte[] descriptor, byte[] signature) {
        this.protocolVersion = Objects.requireNonNull(protocolVersion, "protocol version");
        this.sessionId = Objects.requireNonNull(sessionId, "session ID");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issued at");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expires at");
        if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("invitation interval is empty");
        if (capability.length != CAPABILITY_BYTES) throw new IllegalArgumentException("invalid capability size");
        this.capability = capability.clone();
        if (descriptor.length == 0 || descriptor.length > CandidateDescriptor.MAX_BYTES) {
            throw new IllegalArgumentException("descriptor exceeds invitation bound");
        }
        this.descriptor = descriptor.clone();
        this.unsigned = encodeUnsigned();
        if (signature.length == 0 || signature.length > MAX_SIGNATURE_BYTES
                || unsigned.length + 2 + signature.length > MAX_BYTES) {
            throw new IllegalArgumentException("invitation exceeds supported bound");
        }
        this.signature = signature.clone();
    }

    /** Creates and signs an invitation for one host session.
     * @param host signing host identity
     * @param sessionId fresh session ID
     * @param version supported protocol version
     * @param issuedAt issue time
     * @param expiresAt exclusive expiry time
     * @param capability random bearer capability
     * @param descriptor signed host candidate descriptor
     * @return signed invitation
     * @throws GeneralSecurityException if signing fails
     */
    public static SessionInvitation create(NodeIdentity host, UUID sessionId, ProtocolVersion version,
            Instant issuedAt, Instant expiresAt, byte[] capability, CandidateDescriptor descriptor)
            throws GeneralSecurityException {
        Objects.requireNonNull(host, "host identity");
        Objects.requireNonNull(descriptor, "descriptor");
        if (!host.nodeId().equals(descriptor.nodeId())
                || !Arrays.equals(host.publicKeyEncoded(), descriptor.publicKeyEncoded())) {
            throw new IllegalArgumentException("descriptor is not bound to host identity");
        }
        byte[] cap = Objects.requireNonNull(capability, "capability").clone();
        SessionInvitation unsigned = new SessionInvitation(version, sessionId, issuedAt, expiresAt, cap,
                descriptor.encoded(), new byte[] {1});
        return new SessionInvitation(version, sessionId, issuedAt, expiresAt, cap, descriptor.encoded(),
                host.sign(unsigned.unsigned));
    }

    /** Decodes a share link and validates its bounded URI shape.
     * @param link terminal share link
     * @return decoded invitation
     * @throws IOException if the link is malformed or oversized
     */
    public static SessionInvitation fromShareLink(String link) throws IOException {
        Objects.requireNonNull(link, "share link");
        if (link.length() > MAX_LINK_CHARS) throw new IOException("invitation link is oversized");
        try {
            URI uri = new URI(link);
            if (!"synesis".equals(uri.getScheme()) || !"join".equals(uri.getHost())
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IOException("invalid invitation URI");
            }
            String path = uri.getPath();
            if (path == null || !path.startsWith("/SYN1-") || path.length() <= 6) {
                throw new IOException("invalid invitation link format");
            }
            byte[] encoded = Base64.getUrlDecoder().decode(path.substring(6));
            return decode(encoded);
        } catch (IllegalArgumentException | URISyntaxException exception) {
            throw new IOException("invalid invitation link", exception);
        }
    }

    /** Decodes a bounded canonical invitation without trusting its signature.
     * @param encoded invitation bytes
     * @return decoded invitation
     * @throws IOException if framing or bounds are invalid
     */
    public static SessionInvitation decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "invitation");
        if (encoded.length == 0 || encoded.length > MAX_BYTES) throw new IOException("invitation is oversized");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != FORMAT_VERSION) {
                throw new IOException("unsupported invitation format");
            }
            ProtocolVersion version = new ProtocolVersion(input.readUnsignedByte(), input.readUnsignedByte());
            UUID session = new UUID(input.readLong(), input.readLong());
            Instant issued = Instant.ofEpochSecond(input.readLong());
            Instant expires = Instant.ofEpochSecond(input.readLong());
            byte[] capability = readBytes(input, CAPABILITY_BYTES);
            byte[] descriptor = readBytes(input, CandidateDescriptor.MAX_BYTES);
            byte[] signature = readBytes(input, MAX_SIGNATURE_BYTES);
            if (input.available() != 0) throw new IOException("trailing invitation bytes");
            SessionInvitation value = new SessionInvitation(version, session, issued, expires, capability,
                    descriptor, signature);
            if (!Arrays.equals(encoded, value.encoded())) throw new IOException("non-canonical invitation");
            return value;
        } catch (EOFException | IllegalArgumentException exception) {
            throw new IOException("malformed invitation", exception);
        }
    }

    /** Verifies the host descriptor, invitation signature, and validity interval.
     * @param now verification time
     * @param allowedClockSkew tolerated issue-time skew
     * @return whether the invitation is authentic and current
     * @throws GeneralSecurityException if key verification fails
     * @throws IOException if the embedded descriptor is malformed
     */
    public boolean verifyAt(Instant now, Duration allowedClockSkew) throws GeneralSecurityException, IOException {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(allowedClockSkew, "allowed clock skew");
        if (allowedClockSkew.isNegative()) throw new IllegalArgumentException("clock skew must be non-negative");
        CandidateDescriptor host = CandidateDescriptor.decode(descriptor);
        if (!host.isValidAt(now, allowedClockSkew) || !host.expiresAt().equals(expiresAt)
                || !host.issuedAt().equals(issuedAt) || !verifySignature(host.publicKeyEncoded())) return false;
        return !now.isBefore(issuedAt.minus(allowedClockSkew)) && now.isBefore(expiresAt);
    }

    /** Returns the terminal share link.
     * @return versioned copyable share link
     */
    public String shareLink() {
        return "synesis://join/SYN1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded());
    }

    /** Returns the signed invitation bytes.
     * @return canonical signed bytes
     */
    public byte[] encoded() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(unsigned.length + 2 + signature.length);
            output.write(unsigned);
            try (DataOutputStream data = new DataOutputStream(output)) {
                data.writeShort(signature.length);
                data.write(signature);
            }
            return output.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /** Returns the protocol version carried by the invitation.
     * @return protocol version
     */
    public ProtocolVersion protocolVersion() { return protocolVersion; }
    /** Returns the session ID carried by the invitation.
     * @return session ID
     */
    public UUID sessionId() { return sessionId; }
    /** Returns the inclusive issue instant.
     * @return issue instant
     */
    public Instant issuedAt() { return issuedAt; }
    /** Returns the exclusive expiry instant.
     * @return expiry instant
     */
    public Instant expiresAt() { return expiresAt; }
    /** Returns a copy of the bearer capability.
     * @return capability bytes
     */
    public byte[] capability() { return capability.clone(); }
    /** Returns a copy of the signed host descriptor bytes.
     * @return descriptor bytes
     */
    public byte[] descriptorEncoded() { return descriptor.clone(); }

    private boolean verifySignature(byte[] publicKeyBytes) throws GeneralSecurityException {
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(unsigned);
        return verifier.verify(signature);
    }

    private byte[] encodeUnsigned() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(FORMAT_VERSION);
                output.writeByte(protocolVersion.major());
                output.writeByte(protocolVersion.minor());
                output.writeLong(sessionId.getMostSignificantBits());
                output.writeLong(sessionId.getLeastSignificantBits());
                output.writeLong(issuedAt.getEpochSecond());
                output.writeLong(expiresAt.getEpochSecond());
                writeBytes(output, capability);
                writeBytes(output, descriptor);
            }
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("invitation exceeds supported bound");
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    private static byte[] readBytes(DataInputStream input, int max) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > max || length > input.available()) throw new IOException("invalid invitation field");
        return input.readNBytes(length);
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        if (bytes.length > 65_535) throw new IllegalArgumentException("invitation field is oversized");
        output.writeShort(bytes.length);
        output.write(bytes);
    }
}
