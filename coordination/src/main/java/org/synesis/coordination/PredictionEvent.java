package org.synesis.coordination;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.synesis.link.identity.NodeIdentity;

/** Immutable signed event in the project coordination log. */
public final class PredictionEvent {
    private static final int MAGIC = 0x53434531;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private final UUID eventId;
    private final UUID projectId;
    private final UUID predictionId;
    private final long sequence;
    private final PredictionEventType type;
    private final String actorNodeId;
    private final long createdAtEpochMillis;
    private final byte[] payload;
    private final byte[] previousDigest;
    private final byte[] digest;
    private final byte[] signerPublicKey;
    private final byte[] signature;

    private PredictionEvent(UUID eventId, UUID projectId, UUID predictionId, long sequence,
            PredictionEventType type, String actorNodeId, long createdAtEpochMillis, byte[] payload,
            byte[] previousDigest, byte[] digest, byte[] signerPublicKey, byte[] signature) {
        this.eventId = Objects.requireNonNull(eventId, "event ID");
        this.projectId = Objects.requireNonNull(projectId, "project ID");
        this.predictionId = Objects.requireNonNull(predictionId, "prediction ID");
        this.sequence = sequence;
        this.type = Objects.requireNonNull(type, "event type");
        this.actorNodeId = Objects.requireNonNull(actorNodeId, "actor node ID");
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.payload = Objects.requireNonNull(payload, "payload").clone();
        this.previousDigest = Objects.requireNonNull(previousDigest, "previous digest").clone();
        this.digest = Objects.requireNonNull(digest, "digest").clone();
        this.signerPublicKey = Objects.requireNonNull(signerPublicKey, "signer public key").clone();
        this.signature = Objects.requireNonNull(signature, "signature").clone();
        if (sequence <= 0 || createdAtEpochMillis < 0 || payload.length > MAX_PAYLOAD_BYTES
                || previousDigest.length != 32 || digest.length != 32 || signerPublicKey.length == 0
                || signature.length == 0) {
            throw new IllegalArgumentException("invalid event bounds");
        }
    }

    /**
     * Creates, hashes, and signs an event with the supplied node identity.
     * @param projectId project identifier
     * @param predictionId prediction identifier
     * @param sequence project sequence
     * @param type event type
     * @param actorNodeId authenticated actor node identifier
     * @param payload event payload
     * @param previousDigest previous event digest
     * @param signer signing node identity
     * @param createdAtEpochMillis event timestamp
     * @return signed event
     * @throws GeneralSecurityException when signing fails
     */
    public static PredictionEvent create(UUID projectId, UUID predictionId, long sequence,
            PredictionEventType type, String actorNodeId, byte[] payload, byte[] previousDigest,
            NodeIdentity signer, long createdAtEpochMillis) throws GeneralSecurityException {
        Objects.requireNonNull(signer, "signer");
        if (!signer.nodeId().equals(actorNodeId)) throw new IllegalArgumentException("actor does not match signer");
        byte[] publicKey = signer.publicKeyEncoded();
        byte[] canonical = canonical(eventIdFor(sequence, projectId, predictionId), projectId, predictionId, sequence,
                type, actorNodeId, createdAtEpochMillis, payload, previousDigest);
        byte[] digest = sha256(canonical);
        return new PredictionEvent(eventIdFor(sequence, projectId, predictionId), projectId, predictionId, sequence,
                type, actorNodeId, createdAtEpochMillis, payload, previousDigest, digest, publicKey,
                signer.sign(canonical));
    }

    private static UUID eventIdFor(long sequence, UUID projectId, UUID predictionId) {
        return UUID.nameUUIDFromBytes((projectId + ":" + predictionId + ":" + sequence).getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the deterministic event identifier.
     * @return event identifier */
    public UUID eventId() { return eventId; }
    /** Returns the project namespace.
     * @return project identifier */
    public UUID projectId() { return projectId; }
    /** Returns the prediction identity.
     * @return prediction identifier */
    public UUID predictionId() { return predictionId; }
    /** Returns the monotonic project sequence.
     * @return sequence */
    public long sequence() { return sequence; }
    /** Returns the event kind.
     * @return event type */
    public PredictionEventType type() { return type; }
    /** Returns the authenticated actor node ID.
     * @return actor node identifier */
    public String actorNodeId() { return actorNodeId; }
    /** Returns the coordinator timestamp as epoch milliseconds.
     * @return timestamp */
    public long createdAtEpochMillis() { return createdAtEpochMillis; }
    /** Returns a copy of the event payload.
     * @return payload copy */
    public byte[] payload() { return payload.clone(); }
    /** Returns a copy of the previous event digest.
     * @return previous digest copy */
    public byte[] previousDigest() { return previousDigest.clone(); }
    /** Returns a copy of the event digest.
     * @return digest copy */
    public byte[] digest() { return digest.clone(); }
    /** Returns a lowercase hexadecimal digest.
     * @return hexadecimal digest */
    public String digestHex() { return HexFormat.of().formatHex(digest); }

    /**
     * Verifies the hash, signer binding, and Ed25519 signature.
     * @return true when all authenticity checks pass
     * @throws GeneralSecurityException when the public key or signature cannot be checked
     */
    public boolean verify() throws GeneralSecurityException {
        byte[] canonical = canonical(eventId, projectId, predictionId, sequence, type, actorNodeId,
                createdAtEpochMillis, payload, previousDigest);
        if (!Arrays.equals(digest, sha256(canonical))) return false;
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(signerPublicKey));
        if (!NodeIdentity.deriveNodeId(signerPublicKey).equals(actorNodeId)) return false;
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(canonical);
        return verifier.verify(signature);
    }

    /**
     * Encodes the complete event for atomic persistence.
     * @return canonical encoded event
     */
    public byte[] encoded() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(1);
            writeUuid(out, eventId); writeUuid(out, projectId); writeUuid(out, predictionId);
            out.writeLong(sequence); out.writeInt(type.ordinal()); writeText(out, actorNodeId);
            out.writeLong(createdAtEpochMillis); writeBytes(out, payload); writeBytes(out, previousDigest);
            writeBytes(out, digest); writeBytes(out, signerPublicKey); writeBytes(out, signature);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /**
     * Decodes and validates a persisted event envelope.
     * @param encoded encoded event bytes
     * @return decoded event
     * @throws IOException when the envelope is malformed
     */
    public static PredictionEvent decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded event");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("unsupported event format");
            UUID eventId = readUuid(in), projectId = readUuid(in), predictionId = readUuid(in);
            long sequence = in.readLong();
            int ordinal = in.readInt();
            if (ordinal < 0 || ordinal >= PredictionEventType.values().length) throw new IOException("invalid event type");
            String actor = readText(in); long created = in.readLong(); byte[] payload = readBytes(in);
            byte[] previous = readBytes(in), digest = readBytes(in), key = readBytes(in), signature = readBytes(in);
            if (in.available() != 0) throw new IOException("trailing event bytes");
            return new PredictionEvent(eventId, projectId, predictionId, sequence,
                    PredictionEventType.values()[ordinal], actor, created, payload, previous, digest, key, signature);
        } catch (RuntimeException | java.io.EOFException failure) {
            throw new IOException("malformed coordination event", failure);
        }
    }

    private static byte[] canonical(UUID eventId, UUID projectId, UUID predictionId, long sequence,
            PredictionEventType type, String actor, long created, byte[] payload, byte[] previous) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(1); writeUuid(out, eventId); writeUuid(out, projectId);
            writeUuid(out, predictionId); out.writeLong(sequence); out.writeInt(type.ordinal()); writeText(out, actor);
            out.writeLong(created); writeBytes(out, payload); writeBytes(out, previous); out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    private static byte[] sha256(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void writeUuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void writeText(DataOutputStream out, String value) throws IOException { byte[] b=value.getBytes(StandardCharsets.UTF_8); if (b.length>8192) throw new IOException("text bound"); out.writeInt(b.length); out.write(b); }
    private static String readText(DataInputStream in) throws IOException { byte[] b=readBytes(in); return new String(b, StandardCharsets.UTF_8); }
    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException { if(value.length>MAX_PAYLOAD_BYTES) throw new IOException("payload bound"); out.writeInt(value.length); out.write(value); }
    private static byte[] readBytes(DataInputStream in) throws IOException { int n=in.readInt(); if(n<0 || n>MAX_PAYLOAD_BYTES) throw new IOException("byte bound"); byte[] b=in.readNBytes(n); if(b.length!=n) throw new IOException("truncated event"); return b; }
}
