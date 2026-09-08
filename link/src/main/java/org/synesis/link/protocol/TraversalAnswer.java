package org.synesis.link.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
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
 * A bounded, signed second leg of a bilateral direct-traversal exchange.
 *
 * <p>The answer is cryptographically bound to the exact offer bytes and
 * carries the responder's signed candidate descriptor. It is only usable
 * after the offer and the existing Link identity handshake are verified.
 *
 * @since 1.0
 */
public final class TraversalAnswer {

    /** Maximum encoded answer size. */
    public static final int MAX_BYTES = 24_576;
    /** Required answer nonce size. */
    public static final int NONCE_BYTES = 32;
    /** Default clock-skew allowance for exchange validation. */
    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofMinutes(2);
    /** Maximum answer URI length accepted by the parser. */
    public static final int MAX_LINK_CHARS = 49_152;

    private static final int MAGIC = 0x534C4132;
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_NODE_ID_BYTES = 128;
    private static final int MAX_KEY_BYTES = 256;
    private static final int MAX_SIGNATURE_BYTES = 128;

    private final ProtocolVersion protocolVersion;
    private final UUID sessionId;
    private final byte[] offerDigest;
    private final String initiatorNodeId;
    private final String responderNodeId;
    private final byte[] responderPublicKey;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final byte[] answerNonce;
    private final byte[] descriptor;
    private final byte[] unsigned;
    private final byte[] signature;

    private TraversalAnswer(ProtocolVersion protocolVersion, UUID sessionId, byte[] offerDigest,
            String initiatorNodeId, String responderNodeId, byte[] responderPublicKey, Instant issuedAt,
            Instant expiresAt, byte[] answerNonce, byte[] descriptor, byte[] signature) {
        this.protocolVersion = Objects.requireNonNull(protocolVersion, "protocol version");
        this.sessionId = Objects.requireNonNull(sessionId, "session ID");
        this.offerDigest = exactBytes(offerDigest, TraversalOffer.DIGEST_BYTES, "offer digest");
        this.initiatorNodeId = boundedText(initiatorNodeId, MAX_NODE_ID_BYTES, "initiator node ID");
        this.responderNodeId = boundedText(responderNodeId, MAX_NODE_ID_BYTES, "responder node ID");
        this.responderPublicKey = boundedBytes(responderPublicKey, MAX_KEY_BYTES, "responder public key");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issued at");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expires at");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("answer validity interval is empty");
        }
        this.answerNonce = exactBytes(answerNonce, NONCE_BYTES, "answer nonce");
        this.descriptor = boundedBytes(descriptor, CandidateDescriptor.MAX_BYTES, "candidate descriptor");
        this.unsigned = encodeUnsigned();
        this.signature = boundedBytes(signature, MAX_SIGNATURE_BYTES, "signature");
        if (unsigned.length + 2 + signature.length > MAX_BYTES) {
            throw new IllegalArgumentException("answer exceeds supported bound");
        }
        if (!NodeIdentity.deriveNodeId(this.responderPublicKey).equals(this.responderNodeId)) {
            throw new IllegalArgumentException("responder node ID does not match public key");
        }
    }

    /**
     * Creates a signed answer for an offer.
     *
     * @param responder    signing durable responder identity
     * @param offer        decoded or locally-created offer
     * @param descriptor   signed responder candidate descriptor
     * @param answerNonce  fresh response nonce
     * @return signed answer
     * @throws GeneralSecurityException if signing or descriptor verification fails
     */
    public static TraversalAnswer create(NodeIdentity responder, TraversalOffer offer,
            CandidateDescriptor descriptor, byte[] answerNonce) throws GeneralSecurityException {
        Objects.requireNonNull(responder, "responder identity");
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(descriptor, "candidate descriptor");
        if ((!offer.expectedResponderNodeId().isEmpty()
                && !responder.nodeId().equals(offer.expectedResponderNodeId()))
                || !responder.nodeId().equals(descriptor.nodeId())
                || !Arrays.equals(responder.publicKeyEncoded(), descriptor.publicKeyEncoded())
                || !descriptor.verify()) {
            throw new IllegalArgumentException("answer is not bound to the offer and responder identity");
        }
        TraversalAnswer unsigned = new TraversalAnswer(offer.protocolVersion(), offer.sessionId(), offer.digest(),
                offer.initiatorNodeId(), responder.nodeId(), responder.publicKeyEncoded(), offer.issuedAt(),
                offer.expiresAt(), answerNonce, descriptor.encoded(), new byte[] {1});
        return new TraversalAnswer(unsigned.protocolVersion, unsigned.sessionId, unsigned.offerDigest,
                unsigned.initiatorNodeId, unsigned.responderNodeId, unsigned.responderPublicKey,
                unsigned.issuedAt, unsigned.expiresAt, unsigned.answerNonce, unsigned.descriptor,
                responder.sign(unsigned.unsigned));
    }

    /**
     * Decodes a bounded answer without trusting its signature.
     *
     * @param encoded complete canonical answer
     * @return decoded answer
     * @throws IOException if framing, bounds, or canonical encoding is invalid
     */
    public static TraversalAnswer decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "answer");
        if (encoded.length == 0 || encoded.length > MAX_BYTES) {
            throw new IOException("answer exceeds supported bound");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != FORMAT_VERSION) {
                throw new IOException("unsupported traversal answer format");
            }
            ProtocolVersion version = new ProtocolVersion(input.readUnsignedByte(), input.readUnsignedByte());
            UUID session = new UUID(input.readLong(), input.readLong());
            byte[] offer = readExactBytes(input, TraversalOffer.DIGEST_BYTES);
            String initiator = readText(input, MAX_NODE_ID_BYTES);
            String responder = readText(input, MAX_NODE_ID_BYTES);
            byte[] publicKey = readBytes(input, MAX_KEY_BYTES);
            Instant issued = Instant.ofEpochSecond(input.readLong());
            Instant expires = Instant.ofEpochSecond(input.readLong());
            byte[] nonce = readExactBytes(input, NONCE_BYTES);
            byte[] descriptor = readBytes(input, CandidateDescriptor.MAX_BYTES);
            byte[] signature = readBytes(input, MAX_SIGNATURE_BYTES);
            if (input.available() != 0) {
                throw new IOException("trailing answer bytes");
            }
            TraversalAnswer value = new TraversalAnswer(version, session, offer, initiator, responder,
                    publicKey, issued, expires, nonce, descriptor, signature);
            if (!Arrays.equals(encoded, value.encoded())) {
                throw new IOException("non-canonical traversal answer");
            }
            return value;
        } catch (EOFException | IllegalArgumentException exception) {
            throw new IOException("malformed traversal answer", exception);
        }
    }

    /**
     * Parses a human-copyable {@code synesis://answer/SLA2-...} link.
     *
     * @param link exact SLA2 link
     * @return decoded answer
     * @throws IOException if the URI, encoding, or answer is invalid
     */
    public static TraversalAnswer fromShareLink(String link) throws IOException {
        Objects.requireNonNull(link, "SLA2 link");
        if (link.length() > MAX_LINK_CHARS) {
            throw new IOException("SLA2 link is oversized");
        }
        try {
            URI uri = new URI(link);
            if (!"synesis".equals(uri.getScheme()) || !"answer".equals(uri.getHost())
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IOException("invalid SLA2 URI");
            }
            String path = uri.getPath();
            if (path == null || path.length() <= 6 || !"/SLA2-".equals(path.substring(0, 6))) {
                throw new IOException("invalid SLA2 link format");
            }
            return decode(Base64.getUrlDecoder().decode(path.substring(6)));
        } catch (IllegalArgumentException | URISyntaxException exception) {
            throw new IOException("invalid SLA2 link", exception);
        }
    }

    /**
     * Verifies the answer against its offer and local durable identity.
     *
     * @param now current verification time
     * @param allowedClockSkew accepted issue-time skew
     * @param offer corresponding verified or decodable offer
     * @return whether the answer is authentic and current
     * @throws GeneralSecurityException if key verification fails
     * @throws IOException if either embedded descriptor is malformed
     */
    public boolean verifyAt(Instant now, Duration allowedClockSkew, TraversalOffer offer)
            throws GeneralSecurityException, IOException {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(allowedClockSkew, "allowed clock skew");
        Objects.requireNonNull(offer, "offer");
        if (allowedClockSkew.isNegative() || !sessionId.equals(offer.sessionId())
                || !initiatorNodeId.equals(offer.initiatorNodeId())
                || !Arrays.equals(offerDigest, offer.digest())
                || !offer.verifyAt(now, allowedClockSkew, responderNodeId)
                || now.isBefore(issuedAt.minus(allowedClockSkew)) || !now.isBefore(expiresAt)
                || issuedAt.isBefore(offer.issuedAt()) || expiresAt.isAfter(offer.expiresAt())) {
            return false;
        }
        CandidateDescriptor candidateDescriptor = CandidateDescriptor.decode(descriptor);
        return candidateDescriptor.isValidAt(now, allowedClockSkew)
                && candidateDescriptor.nodeId().equals(responderNodeId)
                && Arrays.equals(candidateDescriptor.publicKeyEncoded(), responderPublicKey)
                && verifySignature();
    }

    /** Returns the canonical signed answer bytes.
     * @return canonical signed answer bytes
     */
    public byte[] encoded() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(unsigned.length + 2 + signature.length);
            bytes.write(unsigned);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeShort(signature.length);
                output.write(signature);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Returns the canonical copyable SLA2 URI.
     * @return copyable SLA2 URI
     */
    public String shareLink() {
        return "synesis://answer/SLA2-" + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded());
    }

    /** Returns the protocol version.
     * @return protocol version
     */
    public ProtocolVersion protocolVersion() {
        return protocolVersion;
    }

    /** Returns the Link session ID.
     * @return Link session ID
     */
    public UUID sessionId() {
        return sessionId;
    }

    /** Returns the exact offer digest.
     * @return offer digest
     */
    public byte[] offerDigest() {
        return offerDigest.clone();
    }

    /** Returns the durable initiator node ID.
     * @return durable initiator node ID
     */
    public String initiatorNodeId() {
        return initiatorNodeId;
    }

    /** Returns the durable responder node ID.
     * @return durable responder node ID
     */
    public String responderNodeId() {
        return responderNodeId;
    }

    /** Returns a copy of the responder public key.
     * @return responder public key copy
     */
    public byte[] responderPublicKeyEncoded() {
        return responderPublicKey.clone();
    }

    /** Returns the inclusive validity start.
     * @return inclusive validity start
     */
    public Instant issuedAt() {
        return issuedAt;
    }

    /** Returns the exclusive validity end.
     * @return exclusive validity end
     */
    public Instant expiresAt() {
        return expiresAt;
    }

    /** Returns a copy of the answer nonce.
     * @return answer nonce copy
     */
    public byte[] answerNonce() {
        return answerNonce.clone();
    }

    /** Returns the signed responder candidate descriptor bytes.
     * @return signed responder candidate descriptor bytes
     */
    public byte[] descriptorEncoded() {
        return descriptor.clone();
    }

    private boolean verifySignature() throws GeneralSecurityException {
        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(responderPublicKey));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
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
                writeBytes(output, offerDigest);
                writeText(output, initiatorNodeId);
                writeText(output, responderNodeId);
                writeBytes(output, responderPublicKey);
                output.writeLong(issuedAt.getEpochSecond());
                output.writeLong(expiresAt.getEpochSecond());
                writeBytes(output, answerNonce);
                writeBytes(output, descriptor);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String boundedText(String value, int maximum, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IllegalArgumentException(label + " exceeds supported bound");
        }
        return value;
    }

    private static byte[] boundedBytes(byte[] value, int maximum, String label) {
        Objects.requireNonNull(value, label);
        if (value.length == 0 || value.length > maximum) {
            throw new IllegalArgumentException(label + " exceeds supported bound");
        }
        return value.clone();
    }

    private static byte[] exactBytes(byte[] value, int expected, String label) {
        byte[] copy = boundedBytes(value, expected, label);
        if (copy.length != expected) {
            throw new IllegalArgumentException(label + " has an invalid size");
        }
        return copy;
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeShort(value.length);
        output.write(value);
    }

    private static String readText(DataInputStream input, int maximum) throws IOException {
        return new String(readBytes(input, maximum), StandardCharsets.UTF_8);
    }

    private static byte[] readExactBytes(DataInputStream input, int expected) throws IOException {
        byte[] value = readBytes(input, expected);
        if (value.length != expected) {
            throw new IOException("invalid fixed-size traversal field");
        }
        return value;
    }

    private static byte[] readBytes(DataInputStream input, int maximum) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > maximum || length > input.available()) {
            throw new IOException("invalid bounded traversal field");
        }
        return input.readNBytes(length);
    }
}
