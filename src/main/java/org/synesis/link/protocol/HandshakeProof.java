package org.synesis.link.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import org.synesis.link.identity.NodeIdentity;

/**
 * A bounded Ed25519 proof binding a node identity to one handshake transcript.
 *
 * <p>The signature covers protocol version, session ID, challenge, node ID,
 * and public key. A verifier must compare all expected transcript fields; a
 * valid signature alone is not proof that the proof belongs to the current
 * connection. Instances are immutable and thread-safe. Challenge and
 * signature bytes are not safe to expose in default logs.
 *
 * @since 1.0
 */
public final class HandshakeProof {

    /** Maximum encoded proof size. */
    public static final int MAX_BYTES = 2_048;
    /** Maximum challenge size. */
    public static final int MAX_CHALLENGE_BYTES = 64;

    private static final int MAGIC = 0x534C4850;
    private static final int MAX_PUBLIC_KEY_BYTES = 256;
    private static final int MAX_NODE_ID_BYTES = 128;
    private static final int MAX_SIGNATURE_BYTES = 128;

    private final ProtocolVersion version;
    private final UUID sessionId;
    private final byte[] challenge;
    private final String nodeId;
    private final byte[] publicKey;
    private final byte[] signature;
    private final byte[] unsignedBytes;

    private HandshakeProof(ProtocolVersion version, UUID sessionId, byte[] challenge, String nodeId,
            byte[] publicKey, byte[] signature) {
        this.version = Objects.requireNonNull(version, "version");
        this.sessionId = Objects.requireNonNull(sessionId, "session ID");
        this.challenge = challenge.clone();
        this.nodeId = Objects.requireNonNull(nodeId, "node ID");
        this.publicKey = publicKey.clone();
        this.signature = signature.clone();
        this.unsignedBytes = encodeUnsigned(version, sessionId, challenge, nodeId, publicKey);
        if (signature.length == 0 || signature.length > MAX_SIGNATURE_BYTES
                || unsignedBytes.length + 2 + signature.length > MAX_BYTES) {
            throw new IllegalArgumentException("handshake proof exceeds supported bounds");
        }
    }

    /**
     * Creates a signed proof for a fresh or caller-controlled transcript.
     *
     * @param identity signing identity
     * @param version negotiated protocol version
     * @param sessionId new session identifier
     * @param challenge connection-specific challenge; 1 through 64 bytes
     * @return signed proof
     * @throws GeneralSecurityException if signing fails
     * @throws IllegalArgumentException if the challenge is out of bounds
     * @throws NullPointerException if an argument is {@code null}
     */
    public static HandshakeProof create(NodeIdentity identity, ProtocolVersion version, UUID sessionId,
            byte[] challenge) throws GeneralSecurityException {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sessionId, "session ID");
        validateChallenge(challenge);
        byte[] publicKey = identity.publicKeyEncoded();
        byte[] unsigned = encodeUnsigned(version, sessionId, challenge, identity.nodeId(), publicKey);
        return new HandshakeProof(version, sessionId, challenge, identity.nodeId(), publicKey,
                identity.sign(unsigned));
    }

    /**
     * Decodes a proof without trusting its signature or transcript binding.
     *
     * @param encoded bounded proof bytes
     * @return decoded proof
     * @throws IOException if framing or bounds are invalid
     * @throws NullPointerException if {@code encoded} is {@code null}
     */
    public static HandshakeProof decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > MAX_BYTES) {
            throw new IOException("handshake proof exceeds supported bound");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("unknown handshake proof");
            }
            ProtocolVersion version = new ProtocolVersion(input.readUnsignedByte(), input.readUnsignedByte());
            UUID sessionId = new UUID(input.readLong(), input.readLong());
            byte[] challenge = readBytes(input, MAX_CHALLENGE_BYTES);
            String nodeId = new String(readBytes(input, MAX_NODE_ID_BYTES), java.nio.charset.StandardCharsets.UTF_8);
            byte[] publicKey = readBytes(input, MAX_PUBLIC_KEY_BYTES);
            byte[] signature = readBytes(input, MAX_SIGNATURE_BYTES);
            if (input.available() != 0) {
                throw new IOException("trailing handshake proof bytes");
            }
            return new HandshakeProof(version, sessionId, challenge, nodeId, publicKey, signature);
        } catch (EOFException | IllegalArgumentException exception) {
            throw new IOException("malformed handshake proof", exception);
        }
    }

    /**
     * Verifies the signature and exact transcript expectations.
     *
     * @param expectedVersion negotiated version expected by the caller
     * @param expectedSessionId current session identifier
     * @param expectedChallenge current connection challenge
     * @param expectedNodeId required remote node identifier
     * @return {@code true} only if every binding and the signature are valid
     * @throws GeneralSecurityException if the public key or signature is invalid
     * @throws NullPointerException if an argument is {@code null}
     */
    public boolean verify(ProtocolVersion expectedVersion, UUID expectedSessionId, byte[] expectedChallenge,
            String expectedNodeId) throws GeneralSecurityException {
        Objects.requireNonNull(expectedVersion, "expected version");
        Objects.requireNonNull(expectedSessionId, "expected session ID");
        Objects.requireNonNull(expectedNodeId, "expected node ID");
        validateChallenge(expectedChallenge);
        if (!version.equals(expectedVersion) || !sessionId.equals(expectedSessionId)
                || !Arrays.equals(challenge, expectedChallenge) || !nodeId.equals(expectedNodeId)
                || !NodeIdentity.deriveNodeId(publicKey).equals(nodeId)) {
            return false;
        }
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKey));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(unsignedBytes);
        return verifier.verify(signature);
    }

    /**
     * Returns the negotiated version.
     *
     * @return negotiated version
     */
    public ProtocolVersion version() { return version; }

    /**
     * Returns the session identifier.
     *
     * @return session identifier
     */
    public UUID sessionId() { return sessionId; }

    /**
     * Returns a copy of the challenge bytes.
     *
     * @return challenge copy
     */
    public byte[] challenge() { return challenge.clone(); }

    /**
     * Returns the claimed node ID.
     *
     * @return claimed node ID
     */
    public String nodeId() { return nodeId; }

    /**
     * Returns a copy of the public key bytes.
     *
     * @return public-key encoding copy
     */
    public byte[] publicKeyEncoded() { return publicKey.clone(); }

    /**
     * Returns complete encoded proof bytes.
     *
     * @return encoded proof copy
     */
    public byte[] encoded() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(unsignedBytes);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeShort(signature.length);
                output.write(signature);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("byte array output cannot fail", impossible);
        }
    }

    private static byte[] encodeUnsigned(ProtocolVersion version, UUID sessionId, byte[] challenge,
            String nodeId, byte[] publicKey) {
        validateChallenge(challenge);
        byte[] nodeIdBytes = nodeId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (nodeIdBytes.length == 0 || nodeIdBytes.length > MAX_NODE_ID_BYTES
                || publicKey.length == 0 || publicKey.length > MAX_PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("handshake proof field exceeds supported bound");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(version.major());
                output.writeByte(version.minor());
                output.writeLong(sessionId.getMostSignificantBits());
                output.writeLong(sessionId.getLeastSignificantBits());
                writeBytes(output, challenge);
                writeBytes(output, nodeIdBytes);
                writeBytes(output, publicKey);
            }
            if (bytes.size() > MAX_BYTES) {
                throw new IllegalArgumentException("handshake proof exceeds supported bound");
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("byte array output cannot fail", impossible);
        }
    }

    private static void validateChallenge(byte[] challenge) {
        if (challenge == null || challenge.length == 0 || challenge.length > MAX_CHALLENGE_BYTES) {
            throw new IllegalArgumentException("challenge must contain 1 through 64 bytes");
        }
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static byte[] readBytes(DataInputStream input, int max) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > max || length > input.available()) {
            throw new IOException("invalid bounded handshake field");
        }
        return input.readNBytes(length);
    }
}
