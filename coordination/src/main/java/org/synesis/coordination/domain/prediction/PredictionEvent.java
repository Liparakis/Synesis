package org.synesis.coordination.domain.prediction;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.UUID;
import org.synesis.coordination.domain.identity.EventSequence;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.PredictionId;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationInvariantError;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.link.identity.NodeIdentity;

/**
 * Immutable signed event in the project coordination log.
 */
@SuppressWarnings("DuplicatedCode")
public final class PredictionEvent {

    private static final int MAGIC = 0x53434531;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private final UUID eventId;
    private final UUID projectId;
    private final UUID predictionId;
    private final long sequence;
    private final PredictionEventType type;
    private final int encodedWireCode;
    private final String actorNodeId;
    private final long createdAtEpochMillis;
    private final byte[] payload;
    private final byte[] previousDigest;
    private final byte[] digest;
    private final byte[] signerPublicKey;
    private final byte[] signature;

    /**
     * Validates and initializes this prediction event.
     */
    private PredictionEvent(UUID eventId,
            UUID projectId,
            UUID predictionId,
            long sequence,
            PredictionEventType type,
            int encodedWireCode,
            String actorNodeId,
            long createdAtEpochMillis,
            byte[] payload,
            byte[] previousDigest,
            byte[] digest,
            byte[] signerPublicKey,
            byte[] signature) {
        this.eventId = CoordinationNullException.requireNonNull(eventId, "event ID");
        this.projectId = CoordinationNullException.requireNonNull(projectId, "project ID");
        this.predictionId = CoordinationNullException.requireNonNull(predictionId, "prediction ID");
        this.sequence = sequence;
        this.type = CoordinationNullException.requireNonNull(type, "event type");
        this.encodedWireCode = encodedWireCode;
        this.actorNodeId = CoordinationNullException.requireNonNull(actorNodeId, "actor node ID");
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.payload = CoordinationNullException.requireNonNull(payload, "payload").clone();
        this.previousDigest = CoordinationNullException.requireNonNull(previousDigest, "previous digest").clone();
        this.digest = CoordinationNullException.requireNonNull(digest, "digest").clone();
        this.signerPublicKey = CoordinationNullException.requireNonNull(signerPublicKey, "signer public key").clone();
        this.signature = CoordinationNullException.requireNonNull(signature, "signature").clone();
        if (sequence <= 0 || createdAtEpochMillis < 0 || payload.length > MAX_PAYLOAD_BYTES
                || previousDigest.length != 32 || digest.length != 32 || signerPublicKey.length == 0
                || signature.length == 0) {
            throw new CoordinationArgumentException("invalid event bounds");
        }
    }

    /**
     * Creates, hashes, and signs an event with the supplied node identity.
     *
     * @param projectId            project identifier
     * @param predictionId         prediction identifier
     * @param sequence             project sequence
     * @param type                 event type
     * @param actorNodeId          authenticated actor node identifier
     * @param payload              event payload
     * @param previousDigest       previous event digest
     * @param signer               signing node identity
     * @param createdAtEpochMillis event timestamp
     * @return signed event
     * @throws GeneralSecurityException when signing fails
     */
    public static PredictionEvent create(UUID projectId,
            UUID predictionId,
            long sequence,
            PredictionEventType type,
            String actorNodeId,
            byte[] payload,
            byte[] previousDigest,
            NodeIdentity signer,
            long createdAtEpochMillis) throws GeneralSecurityException {
        return create(new ProjectId(projectId),
                new PredictionId(predictionId),
                new EventSequence(sequence),
                type,
                new NodeId(actorNodeId),
                payload,
                previousDigest,
                signer,
                createdAtEpochMillis);
    }

    /**
     * Creates a signed event using distinct project, aggregate, sequence, and actor identities.
     *
     * @param projectId            project namespace
     * @param predictionId         event aggregate identity
     * @param sequence             position in the project event log
     * @param type                 event kind
     * @param actorNodeId          authenticated actor node
     * @param payload              event payload
     * @param previousDigest       prior event digest
     * @param signer               signing identity matching the actor node
     * @param createdAtEpochMillis event timestamp
     * @return signed coordination event
     * @throws GeneralSecurityException when signing fails
     */
    public static PredictionEvent create(ProjectId projectId,
            PredictionId predictionId,
            EventSequence sequence,
            PredictionEventType type,
            NodeId actorNodeId,
            byte[] payload,
            byte[] previousDigest,
            NodeIdentity signer,
            long createdAtEpochMillis) throws GeneralSecurityException {
        UUID rawProjectId = CoordinationNullException.requireNonNull(projectId, "project ID").value();
        UUID rawPredictionId = CoordinationNullException.requireNonNull(predictionId, "prediction ID").value();
        long rawSequence = CoordinationNullException.requireNonNull(sequence, "event sequence").value();
        String rawActorNodeId = CoordinationNullException.requireNonNull(actorNodeId, "actor node ID").value();
        CoordinationNullException.requireNonNull(signer, "signer");
        if (!signer.nodeId().equals(rawActorNodeId)) {
            throw new CoordinationArgumentException("actor does not match signer");
        }
        byte[] publicKey = signer.publicKeyEncoded();
        byte[] canonical = canonical(eventIdFor(rawSequence, rawProjectId, rawPredictionId),
                rawProjectId,
                rawPredictionId,
                rawSequence,
                type,
                rawActorNodeId,
                createdAtEpochMillis,
                payload,
                previousDigest);
        byte[] digest = sha256(canonical);
        return new PredictionEvent(eventIdFor(rawSequence, rawProjectId, rawPredictionId),
                rawProjectId,
                rawPredictionId,
                rawSequence,
                type,
                type.wireCode(),
                rawActorNodeId,
                createdAtEpochMillis,
                payload,
                previousDigest,
                digest,
                publicKey,
                signer.sign(canonical));
    }

    /**
     * Derives the deterministic event ID for the specified sequence.
     */
    private static UUID eventIdFor(long sequence, UUID projectId, UUID predictionId) {
        return UUID.nameUUIDFromBytes((projectId + ":" + predictionId + ":"
                + sequence).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes and validates a persisted event envelope.
     *
     * @param encoded encoded event bytes
     * @return decoded event
     * @throws IOException when the envelope is malformed
     */
    public static PredictionEvent decode(byte[] encoded) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded event");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != 1) {
                throw new CoordinationException("unsupported event format");
            }
            UUID eventId = readUuid(in), projectId = readUuid(in), predictionId = readUuid(in);
            long sequence = in.readLong();
            int wireCode = in.readInt();
            String actor = readText(in);
            long created = in.readLong();
            byte[] payload = readBytes(in);
            byte[] previous = readBytes(in), digest = readBytes(in), key = readBytes(in), signature = readBytes(in);
            if (in.available() != 0) {
                throw new CoordinationException("trailing event bytes");
            }
            PredictionEventType type = decodeWireType(wireCode, payload);
            return new PredictionEvent(eventId,
                    projectId,
                    predictionId,
                    sequence,
                    type,
                    wireCode,
                    actor,
                    created,
                    payload,
                    previous,
                    digest,
                    key,
                    signature);
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed coordination event", failure);
        }
    }

    /**
     * Decodes one event type from its stable wire code.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static PredictionEventType decodeWireType(int wireCode, byte[] payload) throws IOException {
        if (wireCode == 42 && (hasMagic(payload, 0x53494e31) || hasMagic(payload, 0x53494e32) || hasMagic(payload,
                0x53494e33) || hasMagic(payload, 0x53494e34) || hasMagic(payload, 0x53494e35) || hasMagic(payload,
                0x53494e36) || hasMagic(payload, 0x53494e37))) {
            return PredictionEventType.WORK_INTENT_ANNOUNCED;
        }
        if (wireCode == 42 || wireCode == 43 || wireCode == 44) {
            if (hasMagic(payload, 0x53494e31) || hasMagic(payload, 0x53494e32) || hasMagic(payload, 0x53494e33)
                    || hasMagic(payload, 0x53494e34) || hasMagic(payload, 0x53494e35) || hasMagic(payload, 0x53494e36)
                    || hasMagic(payload, 0x53494e37)) {
                return PredictionEventType.WORK_INTENT_ANNOUNCED;
            }
            if (hasMagic(payload, 0x53524c31) || hasMagic(payload, 0x534e4331)) {
                return PredictionEventType.WORK_INTENT_RELEASED;
            }
            // Before stable collaboration codes, this slot represented the
            // dependency invalidation event. Preserve that historical log.
            return PredictionEventType.DEPENDENCY_INVALIDATED;
        }
        try {
            return PredictionEventType.fromWireCode(wireCode);
        } catch (IllegalArgumentException failure) {
            throw new CoordinationException("invalid event type", failure);
        }
    }

    /**
     * Checks whether the payload starts with a recognized format marker.
     */
    private static boolean hasMagic(byte[] payload, int magic) {
        return payload.length >= 4 &&
                ((payload[0] & 0xff) << 24 | (payload[1] & 0xff) << 16 | (payload[2] & 0xff) << 8 | (payload[3] & 0xff))
                        == magic;
    }

    /**
     * Produces the canonical event bytes used for signing and hashing.
     */
    private static byte[] canonical(UUID eventId,
            UUID projectId,
            UUID predictionId,
            long sequence,
            PredictionEventType type,
            String actor,
            long created,
            byte[] payload,
            byte[] previous) {
        return canonical(eventId,
                projectId,
                predictionId,
                sequence,
                type.wireCode(),
                actor,
                created,
                payload,
                previous);
    }

    /**
     * Produces the canonical event bytes used for signing and hashing.
     */
    private static byte[] canonical(UUID eventId,
            UUID projectId,
            UUID predictionId,
            long sequence,
            int wireCode,
            String actor,
            long created,
            byte[] payload,
            byte[] previous) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(1);
            writeUuid(out, eventId);
            writeUuid(out, projectId);
            writeUuid(out, predictionId);
            out.writeLong(sequence);
            out.writeInt(wireCode);
            writeText(out, actor);
            out.writeLong(created);
            writeBytes(out, payload);
            writeBytes(out, previous);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Computes the SHA-256 digest of the canonical event bytes.
     */
    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Writes a UUID in the canonical binary payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    /**
     * Reads a UUID from the canonical binary payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    /**
     * Writes bounded UTF-8 text in the canonical payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        if (b.length > 8192) {
            throw new CoordinationException("text bound");
        }
        out.writeInt(b.length);
        out.write(b);
    }

    /**
     * Reads bounded UTF-8 text and validates its encoded length.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static String readText(DataInputStream in) throws IOException {
        byte[] b = readBytes(in);
        return new String(b, StandardCharsets.UTF_8);
    }

    /**
     * Writes a length-bounded byte field.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        if (value.length > MAX_PAYLOAD_BYTES) {
            throw new CoordinationException("payload bound");
        }
        out.writeInt(value.length);
        out.write(value);
    }

    /**
     * Reads a length-bounded byte field.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static byte[] readBytes(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_PAYLOAD_BYTES) {
            throw new CoordinationException("byte bound");
        }
        byte[] b = in.readNBytes(n);
        if (b.length != n) {
            throw new CoordinationException("truncated event");
        }
        return b;
    }

    /**
     * Returns the deterministic event identifier.
     *
     * @return event identifier
     */
    public UUID eventId() {
        return eventId;
    }

    /**
     * Returns the project namespace.
     *
     * @return project identifier
     */
    public UUID projectId() {
        return projectId;
    }

    /**
     * Returns the prediction identity.
     *
     * @return prediction identifier
     */
    public UUID predictionId() {
        return predictionId;
    }

    /**
     * Returns the typed prediction identity affected by this event.
     *
     * @return the typed prediction identity affected by this event.
     */
    public PredictionId predictionIdentity() {
        return new PredictionId(predictionId);
    }

    /**
     * Returns the monotonic project sequence.
     *
     * @return sequence
     */
    public long sequence() {
        return sequence;
    }

    /**
     * Returns the event's typed position in the append-only project log.
     *
     * @return the event's typed position in the append-only project log.
     */
    public EventSequence sequenceIdentity() {
        return new EventSequence(sequence);
    }

    /**
     * Returns the event kind.
     *
     * @return event type
     */
    public PredictionEventType type() {
        return type;
    }

    /**
     * Returns the coordinator timestamp as epoch milliseconds.
     *
     * @return timestamp
     */
    public long createdAtEpochMillis() {
        return createdAtEpochMillis;
    }

    /**
     * Returns a copy of the event payload.
     *
     * @return payload copy
     */
    public byte[] payload() {
        return payload.clone();
    }

    /**
     * Returns a copy of the previous event digest.
     *
     * @return previous digest copy
     */
    public byte[] previousDigest() {
        return previousDigest.clone();
    }

    /**
     * Returns a copy of the event digest.
     *
     * @return digest copy
     */
    public byte[] digest() {
        return digest.clone();
    }

    /**
     * Verifies the hash, signer binding, and Ed25519 signature.
     *
     * @return true when all authenticity checks pass
     * @throws GeneralSecurityException when the public key or signature cannot be checked
     */
    public boolean verify() throws GeneralSecurityException {
        byte[] canonical = canonical(eventId,
                projectId,
                predictionId,
                sequence,
                encodedWireCode,
                actorNodeId,
                createdAtEpochMillis,
                payload,
                previousDigest);
        if (!Arrays.equals(digest, sha256(canonical))) {
            canonical = canonical(eventId,
                    projectId,
                    predictionId,
                    sequence,
                    type.wireCode(),
                    actorNodeId,
                    createdAtEpochMillis,
                    payload,
                    previousDigest);
            if (!Arrays.equals(digest, sha256(canonical))) {
                canonical = canonicalWithWireCode(type.ordinal());
                if (!Arrays.equals(digest, sha256(canonical))) {
                    return false;
                }
            }
        }
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(signerPublicKey));
        if (!NodeIdentity.deriveNodeId(signerPublicKey).equals(actorNodeId)) {
            return false;
        }
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(canonical);
        return verifier.verify(signature);
    }

    /**
     * Produces canonical event bytes using the event's preserved wire code.
     */
    private byte[] canonicalWithWireCode(int wireCode) {
        return canonical(eventId,
                projectId,
                predictionId,
                sequence,
                wireCode,
                actorNodeId,
                createdAtEpochMillis,
                payload,
                previousDigest);
    }

    /**
     * Encodes the complete event for atomic persistence.
     *
     * @return canonical encoded event
     */
    public byte[] encoded() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(1);
            writeUuid(out, eventId);
            writeUuid(out, projectId);
            writeUuid(out, predictionId);
            out.writeLong(sequence);
            out.writeInt(encodedWireCode);
            writeText(out, actorNodeId);
            out.writeLong(createdAtEpochMillis);
            writeBytes(out, payload);
            writeBytes(out, previousDigest);
            writeBytes(out, digest);
            writeBytes(out, signerPublicKey);
            writeBytes(out, signature);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }
}
