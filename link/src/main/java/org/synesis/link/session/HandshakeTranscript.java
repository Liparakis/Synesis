package org.synesis.link.session;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import org.synesis.link.SynesisLink;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.ProtocolVersion;

/**
 * Canonical, bounded data shared by both sides of an authenticated session.
 *
 * <p>The transcript is immutable. Its role-specific challenge is signed by a
 * node proof, so a proof cannot be moved to another session, role, ALPN, epoch,
 * nonce, or identity pair without invalidating the signature.
 *
 * @since 1.0
 */
public final class HandshakeTranscript {

    /** Maximum nonce size. */
    public static final int MAX_NONCE_BYTES = 64;
    /** Maximum encoded transcript size. */
    public static final int MAX_BYTES = 4_096;

    private static final int MAGIC = 0x534C5431;
    private static final int MAX_ALPN_BYTES = 64;
    private static final int MAX_NODE_ID_BYTES = 128;
    private static final int MAX_KEY_BYTES = 256;

    private final ProtocolVersion version;
    private final String alpn;
    private final UUID sessionId;
    private final long initiatorEpoch;
    private final long responderEpoch;
    private final byte[] initiatorNonce;
    private final byte[] responderNonce;
    private final String initiatorNodeId;
    private final byte[] initiatorPublicKey;
    private final String responderNodeId;
    private final byte[] responderPublicKey;
    private final byte[] encoded;

    private HandshakeTranscript(ProtocolVersion version, String alpn, UUID sessionId,
            long initiatorEpoch, long responderEpoch, byte[] initiatorNonce, byte[] responderNonce,
            String initiatorNodeId, byte[] initiatorPublicKey, String responderNodeId,
            byte[] responderPublicKey) {
        this.version = Objects.requireNonNull(version, "version");
        this.alpn = requireText(alpn, MAX_ALPN_BYTES, "ALPN");
        this.sessionId = Objects.requireNonNull(sessionId, "session ID");
        if (initiatorEpoch < 0 || responderEpoch < 0) {
            throw new IllegalArgumentException("session epochs must be non-negative");
        }
        this.initiatorEpoch = initiatorEpoch;
        this.responderEpoch = responderEpoch;
        this.initiatorNonce = copyNonce(initiatorNonce);
        this.responderNonce = copyNonce(responderNonce);
        this.initiatorNodeId = requireText(initiatorNodeId, MAX_NODE_ID_BYTES, "initiator node ID");
        this.initiatorPublicKey = copyBounded(initiatorPublicKey, MAX_KEY_BYTES, "initiator public key");
        this.responderNodeId = requireText(responderNodeId, MAX_NODE_ID_BYTES, "responder node ID");
        this.responderPublicKey = copyBounded(responderPublicKey, MAX_KEY_BYTES, "responder public key");
        if (!NodeIdentity.deriveNodeId(this.initiatorPublicKey).equals(this.initiatorNodeId)
                || !NodeIdentity.deriveNodeId(this.responderPublicKey).equals(this.responderNodeId)) {
            throw new IllegalArgumentException("node ID does not match public key");
        }
        this.encoded = encode();
    }

    /**
     * Creates a transcript from both canonical public identities.
     *
     * @param version protocol version
     * @param alpn negotiated application protocol
     * @param sessionId fresh session ID
     * @param initiatorEpoch initiator session epoch
     * @param responderEpoch responder session epoch
     * @param initiatorNonce fresh initiator nonce
     * @param responderNonce fresh responder nonce
     * @param initiatorNodeId initiator node ID
     * @param initiatorPublicKey initiator X.509 public key
     * @param responderNodeId responder node ID
     * @param responderPublicKey responder X.509 public key
     * @return immutable canonical transcript
     */
    public static HandshakeTranscript create(ProtocolVersion version, String alpn, UUID sessionId,
            long initiatorEpoch, long responderEpoch, byte[] initiatorNonce, byte[] responderNonce,
            String initiatorNodeId, byte[] initiatorPublicKey, String responderNodeId,
            byte[] responderPublicKey) {
        return new HandshakeTranscript(version, alpn, sessionId, initiatorEpoch, responderEpoch,
                initiatorNonce, responderNonce, initiatorNodeId, initiatorPublicKey, responderNodeId,
                responderPublicKey);
    }

    /**
     * Creates a transcript using two identities and the Synesis Link ALPN.
     *
     * @param version protocol version
     * @param sessionId fresh session ID
     * @param initiatorEpoch initiator session epoch
     * @param responderEpoch responder session epoch
     * @param initiatorNonce fresh initiator nonce
     * @param responderNonce fresh responder nonce
     * @param initiator initiator identity
     * @param responder responder identity
     * @return immutable canonical transcript
     */
    public static HandshakeTranscript forIdentities(ProtocolVersion version, UUID sessionId,
            long initiatorEpoch, long responderEpoch, byte[] initiatorNonce, byte[] responderNonce,
            NodeIdentity initiator, NodeIdentity responder) {
        Objects.requireNonNull(initiator, "initiator identity");
        Objects.requireNonNull(responder, "responder identity");
        return create(version, SynesisLink.ALPN, sessionId, initiatorEpoch, responderEpoch,
                initiatorNonce, responderNonce, initiator.nodeId(), initiator.publicKeyEncoded(),
                responder.nodeId(), responder.publicKeyEncoded());
    }

    /**
     * Decodes a bounded canonical transcript without trusting its identities.
     *
     * @param value encoded transcript
     * @return immutable transcript requiring proof verification
     * @throws IOException if framing, bounds, or identity bindings are invalid
     */
    public static HandshakeTranscript decode(byte[] value) throws IOException {
        Objects.requireNonNull(value, "transcript");
        if (value.length == 0 || value.length > MAX_BYTES) {
            throw new IOException("handshake transcript exceeds supported bound");
        }
        try (DataInputStream input = new DataInputStream(new java.io.ByteArrayInputStream(value))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("unknown handshake transcript");
            }
            ProtocolVersion version = new ProtocolVersion(input.readUnsignedByte(), input.readUnsignedByte());
            String alpn = readString(input, MAX_ALPN_BYTES);
            UUID sessionId = new UUID(input.readLong(), input.readLong());
            long initiatorEpoch = input.readLong();
            long responderEpoch = input.readLong();
            byte[] initiatorNonce = readBytes(input, MAX_NONCE_BYTES);
            byte[] responderNonce = readBytes(input, MAX_NONCE_BYTES);
            String initiatorNodeId = readString(input, MAX_NODE_ID_BYTES);
            byte[] initiatorPublicKey = readBytes(input, MAX_KEY_BYTES);
            String responderNodeId = readString(input, MAX_NODE_ID_BYTES);
            byte[] responderPublicKey = readBytes(input, MAX_KEY_BYTES);
            if (input.available() != 0) {
                throw new IOException("trailing handshake transcript bytes");
            }
            HandshakeTranscript transcript = create(version, alpn, sessionId, initiatorEpoch, responderEpoch,
                    initiatorNonce, responderNonce, initiatorNodeId, initiatorPublicKey, responderNodeId,
                    responderPublicKey);
            if (!Arrays.equals(value, transcript.encoded)) {
                throw new IOException("non-canonical handshake transcript");
            }
            return transcript;
        } catch (EOFException | IllegalArgumentException exception) {
            throw new IOException("malformed handshake transcript", exception);
        }
    }

    /**
     * Returns the canonical transcript bytes.
     *
     * @return fresh encoded bytes
     */
    public byte[] encoded() { return encoded.clone(); }

    /**
     * Returns the role-specific bytes to be challenged by a proof.
     *
     * @param role proof role
     * @return SHA-256 challenge bytes
     */
    public byte[] proofChallenge(HandshakeRole role) {
        Objects.requireNonNull(role, "role");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(encoded);
            digest.update((byte) role.ordinal());
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java platform", impossible);
        }
    }

    /**
     * Returns the node ID for a role.
     *
     * @param role role to inspect
     * @return role's node ID
     */
    public String nodeId(HandshakeRole role) {
        return role == HandshakeRole.INITIATOR ? initiatorNodeId : responderNodeId;
    }

    /**
     * Returns the public key for a role.
     *
     * @param role role to inspect
     * @return fresh X.509 public-key bytes
     */
    public byte[] publicKeyEncoded(HandshakeRole role) {
        return (role == HandshakeRole.INITIATOR ? initiatorPublicKey : responderPublicKey).clone();
    }

    /**
     * Returns the session ID.
     *
     * @return session ID
     */
    public UUID sessionId() { return sessionId; }

    /**
     * Returns the negotiated protocol version.
     *
     * @return protocol version
     */
    public ProtocolVersion version() { return version; }

    /**
     * Returns the ALPN value included in the transcript.
     *
     * @return ALPN
     */
    public String alpn() { return alpn; }

    /**
     * Returns the epoch for a role.
     *
     * @param role role to inspect
     * @return non-negative session epoch
     */
    public long epoch(HandshakeRole role) {
        return role == HandshakeRole.INITIATOR ? initiatorEpoch : responderEpoch;
    }

    private byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(version.major());
                output.writeByte(version.minor());
                writeString(output, alpn);
                output.writeLong(sessionId.getMostSignificantBits());
                output.writeLong(sessionId.getLeastSignificantBits());
                output.writeLong(initiatorEpoch);
                output.writeLong(responderEpoch);
                writeBytes(output, initiatorNonce);
                writeBytes(output, responderNonce);
                writeString(output, initiatorNodeId);
                writeBytes(output, initiatorPublicKey);
                writeString(output, responderNodeId);
                writeBytes(output, responderPublicKey);
            }
            if (bytes.size() > MAX_BYTES) {
                throw new IllegalArgumentException("handshake transcript exceeds supported bound");
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("byte array output cannot fail", impossible);
        }
    }

    private static String requireText(String value, int maxBytes, String label) {
        Objects.requireNonNull(value, label);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maxBytes) {
            throw new IllegalArgumentException(label + " exceeds supported bound");
        }
        return value;
    }

    private static byte[] copyNonce(byte[] value) {
        return copyBounded(value, MAX_NONCE_BYTES, "nonce");
    }

    private static byte[] copyBounded(byte[] value, int max, String label) {
        Objects.requireNonNull(value, label);
        if (value.length == 0 || value.length > max) {
            throw new IllegalArgumentException(label + " exceeds supported bound");
        }
        return Arrays.copyOf(value, value.length);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeShort(value.length);
        output.write(value);
    }

    private static String readString(DataInputStream input, int max) throws IOException {
        return new String(readBytes(input, max), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(DataInputStream input, int max) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > max || length > input.available()) {
            throw new IOException("invalid bounded transcript field");
        }
        return input.readNBytes(length);
    }
}
