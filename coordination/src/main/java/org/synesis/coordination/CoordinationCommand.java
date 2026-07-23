package org.synesis.coordination;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;
import java.util.UUID;

import org.synesis.link.identity.NodeIdentity;

/** A bounded, signed request to append one coordination event. */
public final class CoordinationCommand {
    private static final int MAGIC = 0x53434331;
    private static final int MAX_BYTES = 64 * 1024;
    private final UUID commandId;
    private final UUID projectId;
    private final UUID predictionId;
    private final PredictionEventType type;
    private final String actorNodeId;
    private final String actorSupervisorId;
    private final String actorWorkerId;
    private final byte[] payload;
    private final byte[] signerPublicKey;
    private final byte[] signature;

    private CoordinationCommand(UUID commandId, UUID projectId, UUID predictionId, PredictionEventType type,
            String actorNodeId, String actorSupervisorId, String actorWorkerId, byte[] payload,
            byte[] signerPublicKey, byte[] signature) {
        this.commandId = Objects.requireNonNull(commandId, "command ID");
        this.projectId = Objects.requireNonNull(projectId, "project ID");
        this.predictionId = Objects.requireNonNull(predictionId, "prediction ID");
        this.type = Objects.requireNonNull(type, "event type");
        this.actorNodeId = Objects.requireNonNull(actorNodeId, "actor node ID");
        this.actorSupervisorId = actorSupervisorId;
        this.actorWorkerId = actorWorkerId;
        this.payload = Objects.requireNonNull(payload, "payload").clone();
        this.signerPublicKey = Objects.requireNonNull(signerPublicKey, "signer public key").clone();
        this.signature = Objects.requireNonNull(signature, "signature").clone();
        if (payload.length > MAX_BYTES || signerPublicKey.length == 0 || signature.length == 0) {
            throw new IllegalArgumentException("command exceeds bounds");
        }
    }

    /**
     * Creates and signs a command with a node identity.
     * @param commandId idempotency identifier
     * @param projectId project identifier
     * @param predictionId prediction identifier
     * @param type event type
     * @param actorNodeId actor node identifier
     * @param payload command payload
     * @param signer signing identity
     * @return signed command
     * @throws GeneralSecurityException when signing fails
     */
    public static CoordinationCommand create(UUID commandId, UUID projectId, UUID predictionId,
            PredictionEventType type, String actorNodeId, byte[] payload, NodeIdentity signer)
            throws GeneralSecurityException {
        Objects.requireNonNull(signer, "signer");
        if (!signer.nodeId().equals(actorNodeId)) throw new IllegalArgumentException("actor does not match signer");
        CoordinationCommand unsigned = new CoordinationCommand(commandId, projectId, predictionId, type,
                actorNodeId, null, null, payload, signer.publicKeyEncoded(), new byte[] {1});
        return new CoordinationCommand(commandId, projectId, predictionId, type, actorNodeId, null, null, payload,
                signer.publicKeyEncoded(), signer.sign(unsigned.canonical()));
    }

    /** Creates a signed command with explicit logical supervisor and worker identity.
     * @param commandId idempotency identifier
     * @param projectId project identifier
     * @param predictionId target task or prediction
     * @param type event type
     * @param actorNodeId authenticated node identity
     * @param actorSupervisorId logical supervisor identity
     * @param actorWorkerId logical worker identity
     * @param payload command payload
     * @param signer signing identity
     * @return signed version-two command
     * @throws GeneralSecurityException when signing fails
     */
    public static CoordinationCommand createAs(UUID commandId, UUID projectId, UUID predictionId,
            PredictionEventType type, String actorNodeId, String actorSupervisorId, String actorWorkerId,
            byte[] payload, NodeIdentity signer) throws GeneralSecurityException {
        Objects.requireNonNull(actorSupervisorId, "actor supervisor ID");
        Objects.requireNonNull(actorWorkerId, "actor worker ID");
        if (actorSupervisorId.isBlank() || actorWorkerId.isBlank()) throw new IllegalArgumentException("actor identity blank");
        if (!signer.nodeId().equals(actorNodeId)) throw new IllegalArgumentException("actor does not match signer");
        CoordinationCommand unsigned = new CoordinationCommand(commandId, projectId, predictionId, type,
                actorNodeId, actorSupervisorId, actorWorkerId, payload, signer.publicKeyEncoded(), new byte[] {1});
        return new CoordinationCommand(commandId, projectId, predictionId, type, actorNodeId, actorSupervisorId,
                actorWorkerId, payload, signer.publicKeyEncoded(), signer.sign(unsigned.canonical()));
    }

    /** Returns the command identifier used for idempotency.
     * @return command ID */
    public UUID commandId() { return commandId; }
    /** Returns the project identifier.
     * @return project ID */
    public UUID projectId() { return projectId; }
    /** Returns the prediction identifier.
     * @return prediction ID */
    public UUID predictionId() { return predictionId; }
    /** Returns the requested event type.
     * @return event type */
    public PredictionEventType type() { return type; }
    /** Returns the authenticated actor node identifier.
     * @return node ID */
    public String actorNodeId() { return actorNodeId; }
    /** Returns the logical supervisor actor, when supplied by a version-two command.
     * @return supervisor identity, or null for version one
     */
    public String actorSupervisorId() { return actorSupervisorId; }
    /** Returns the logical worker actor, when supplied by a version-two command.
     * @return worker identity, or null for version one
     */
    public String actorWorkerId() { return actorWorkerId; }
    /** Returns a copy of the command payload.
     * @return payload copy */
    public byte[] payload() { return payload.clone(); }

    /**
     * Verifies signer binding and command signature.
     * @return true when valid
     * @throws GeneralSecurityException when verification cannot be performed
     */
    public boolean verify() throws GeneralSecurityException {
        PublicKey key = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(signerPublicKey));
        if (!NodeIdentity.deriveNodeId(signerPublicKey).equals(actorNodeId)) return false;
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key); verifier.update(canonical());
        return verifier.verify(signature);
    }

    /**
     * Encodes this command for HTTP transport or durable audit.
     * @return encoded command
     */
    public byte[] encoded() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(formatVersion()); writeUuid(out, commandId); writeUuid(out, projectId);
            writeUuid(out, predictionId); out.writeInt(type.ordinal()); writeText(out, actorNodeId);
            if (formatVersion() == 2) { writeText(out, actorSupervisorId); writeText(out, actorWorkerId); }
            writeBytes(out, payload); writeBytes(out, signerPublicKey); writeBytes(out, signature); out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /**
     * Decodes a command envelope.
     * @param encoded bytes
     * @return command
     * @throws IOException malformed input
     */
    public static CoordinationCommand decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded command");
        if (encoded.length > MAX_BYTES) throw new IOException("command exceeds bound");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) throw new IOException("unsupported command format");
            int version = in.readInt(); if (version != 1 && version != 2) throw new IOException("unsupported command format");
            UUID commandId = readUuid(in), projectId = readUuid(in), predictionId = readUuid(in);
            int ordinal = in.readInt();
            if (ordinal < 0 || ordinal >= PredictionEventType.values().length) throw new IOException("event type");
            String actor = readText(in); String supervisor = version == 2 ? readText(in) : null;
            String worker = version == 2 ? readText(in) : null;
            byte[] payload = readBytes(in), key = readBytes(in), signature = readBytes(in);
            if (in.available() != 0) throw new IOException("trailing command bytes");
            return new CoordinationCommand(commandId, projectId, predictionId,
                    PredictionEventType.values()[ordinal], actor, supervisor, worker, payload, key, signature);
        } catch (RuntimeException | java.io.EOFException failure) {
            throw new IOException("malformed coordination command", failure);
        }
    }

    private byte[] canonical() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(formatVersion()); writeUuid(out, commandId); writeUuid(out, projectId);
            writeUuid(out, predictionId); out.writeInt(type.ordinal()); writeText(out, actorNodeId);
            if (formatVersion() == 2) { writeText(out, actorSupervisorId); writeText(out, actorWorkerId); }
            writeBytes(out, payload); writeBytes(out, signerPublicKey); out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }
    private int formatVersion() { return actorSupervisorId == null ? 1 : 2; }
    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
    }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); if (bytes.length > 8192) throw new IOException("text bound");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String readText(DataInputStream in) throws IOException { return new String(readBytes(in), StandardCharsets.UTF_8); }
    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        if (value.length > MAX_BYTES) throw new IOException("byte bound"); out.writeInt(value.length); out.write(value);
    }
    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt(); if (length < 0 || length > MAX_BYTES) throw new IOException("byte bound");
        byte[] value = in.readNBytes(length); if (value.length != length) throw new IOException("truncated command"); return value;
    }
}
