package org.synesis.coordination.domain.command;

import static org.synesis.coordination.domain.identity.WireFormat.encode;
import static org.synesis.coordination.domain.identity.WireFormat.readUuid;
import static org.synesis.coordination.domain.identity.WireFormat.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.identity.CommandId;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.PredictionId;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.identity.SupervisorId;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.domain.identity.WorkerId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.link.identity.NodeIdentity;

/**
 * A bounded, signed request to append one coordination event.
 */
@SuppressWarnings("DuplicatedCode")
public final class CoordinationCommand {

    private static final int MAGIC = 0x53434331;
    private static final int MAX_BYTES = 64 * 1024;
    private static final WireFormat BYTES = WireFormat.bounded(MAX_BYTES, true, "byte bound", "truncated command")
            .checkingWrites(MAX_BYTES, "byte bound");
    private static final WireFormat TEXT = BYTES.checkingWrites(8192, "text bound");
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

    /**
     * Validates and initializes this coordination command.
     */
    private CoordinationCommand(UUID commandId,
            UUID projectId,
            UUID predictionId,
            PredictionEventType type,
            String actorNodeId,
            String actorSupervisorId,
            String actorWorkerId,
            byte[] payload,
            byte[] signerPublicKey,
            byte[] signature) {
        this.commandId = CoordinationNullException.requireNonNull(commandId, "command ID");
        this.projectId = CoordinationNullException.requireNonNull(projectId, "project ID");
        this.predictionId = CoordinationNullException.requireNonNull(predictionId, "prediction ID");
        this.type = CoordinationNullException.requireNonNull(type, "event type");
        this.actorNodeId = CoordinationNullException.requireNonNull(actorNodeId, "actor node ID");
        this.actorSupervisorId = actorSupervisorId;
        this.actorWorkerId = actorWorkerId;
        this.payload = CoordinationNullException.requireNonNull(payload, "payload").clone();
        this.signerPublicKey = CoordinationNullException.requireNonNull(signerPublicKey, "signer public key").clone();
        this.signature = CoordinationNullException.requireNonNull(signature, "signature").clone();
        if (payload.length > MAX_BYTES || signerPublicKey.length == 0 || signature.length == 0) {
            throw new CoordinationArgumentException("command exceeds bounds");
        }
        TEXT.requireWritable(actorNodeId, "actor node ID");
        if (actorSupervisorId != null) {
            TEXT.requireWritable(actorSupervisorId, "actor supervisor ID");
        }
        if (actorWorkerId != null) {
            TEXT.requireWritable(actorWorkerId, "actor worker ID");
        }
    }

    /**
     * Creates and signs a command with a node identity.
     *
     * @param commandId    idempotency identifier
     * @param projectId    project identifier
     * @param predictionId prediction identifier
     * @param type         event type
     * @param actorNodeId  actor node identifier
     * @param payload      command payload
     * @param signer       signing identity
     * @return signed command
     * @throws GeneralSecurityException when signing fails
     */
    public static CoordinationCommand create(UUID commandId,
            UUID projectId,
            UUID predictionId,
            PredictionEventType type,
            String actorNodeId,
            byte[] payload,
            NodeIdentity signer) throws GeneralSecurityException {
        CoordinationNullException.requireNonNull(signer, "signer");
        if (!signer.nodeId().equals(actorNodeId)) {
            throw new CoordinationArgumentException("actor does not match signer");
        }
        CoordinationCommand unsigned = new CoordinationCommand(commandId,
                projectId,
                predictionId,
                type,
                actorNodeId,
                null,
                null,
                payload,
                signer.publicKeyEncoded(),
                new byte[]{1});
        return new CoordinationCommand(commandId,
                projectId,
                predictionId,
                type,
                actorNodeId,
                null,
                null,
                payload,
                signer.publicKeyEncoded(),
                signer.sign(unsigned.canonical()));
    }

    /**
     * Creates a signed command with explicit logical supervisor and worker identity.
     *
     * @param commandId         idempotency identifier
     * @param projectId         project identifier
     * @param predictionId      target task or prediction
     * @param type              event type
     * @param actorNodeId       authenticated node identity
     * @param actorSupervisorId logical supervisor identity
     * @param actorWorkerId     logical worker identity
     * @param payload           command payload
     * @param signer            signing identity
     * @return signed version-two command
     * @throws GeneralSecurityException when signing fails
     */
    public static CoordinationCommand createAs(UUID commandId,
            UUID projectId,
            UUID predictionId,
            PredictionEventType type,
            String actorNodeId,
            String actorSupervisorId,
            String actorWorkerId,
            byte[] payload,
            NodeIdentity signer) throws GeneralSecurityException {
        CoordinationNullException.requireNonNull(actorSupervisorId, "actor supervisor ID");
        CoordinationNullException.requireNonNull(actorWorkerId, "actor worker ID");
        if (actorSupervisorId.isBlank() || actorWorkerId.isBlank()) {
            throw new CoordinationArgumentException("actor identity blank");
        }
        if (!signer.nodeId().equals(actorNodeId)) {
            throw new CoordinationArgumentException("actor does not match signer");
        }
        CoordinationCommand unsigned = new CoordinationCommand(commandId,
                projectId,
                predictionId,
                type,
                actorNodeId,
                actorSupervisorId,
                actorWorkerId,
                payload,
                signer.publicKeyEncoded(),
                new byte[]{1});
        return new CoordinationCommand(commandId,
                projectId,
                predictionId,
                type,
                actorNodeId,
                actorSupervisorId,
                actorWorkerId,
                payload,
                signer.publicKeyEncoded(),
                signer.sign(unsigned.canonical()));
    }

    /**
     * Decodes a command envelope.
     *
     * @param encoded bytes
     * @return command
     * @throws IOException malformed input
     */
    public static CoordinationCommand decode(byte[] encoded) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded command");
        if (encoded.length > MAX_BYTES) {
            throw new CoordinationException("command exceeds bound");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) {
                throw new CoordinationException("unsupported command format");
            }
            int version = in.readInt();
            if (version != 1 && version != 2) {
                throw new CoordinationException("unsupported command format");
            }
            UUID commandId = readUuid(in), projectId = readUuid(in), predictionId = readUuid(in);
            int wireCode = in.readInt();
            PredictionEventType type;
            try {
                type = PredictionEventType.fromWireCode(wireCode);
            } catch (IllegalArgumentException unknown) {
                throw new CoordinationException("event type");
            }
            String actor = TEXT.readText(in);
            String supervisor = version == 2 ? TEXT.readText(in) : null;
            String worker = version == 2 ? TEXT.readText(in) : null;
            byte[] payload = BYTES.readBytes(in), key = BYTES.readBytes(in), signature = BYTES.readBytes(in);
            if (in.available() != 0) {
                throw new CoordinationException("trailing command bytes");
            }
            return new CoordinationCommand(commandId,
                    projectId,
                    predictionId,
                    type,
                    actor,
                    supervisor,
                    worker,
                    payload,
                    key,
                    signature);
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed coordination command", failure);
        }
    }

    /**
     * Returns the idempotency key with its command-specific type.
     *
     * @return command identity used for durable idempotency
     */
    public CommandId identity() {
        return new CommandId(commandId);
    }

    /**
     * Returns the project identity without conflating it with task or event IDs.
     *
     * @return typed project namespace
     */
    public ProjectId projectIdentity() {
        return new ProjectId(projectId);
    }

    /**
     * Returns the prediction identifier.
     *
     * @return prediction ID
     */
    public UUID predictionId() {
        return predictionId;
    }

    /**
     * Returns the prediction stream identity.
     *
     * @return typed prediction identity
     */
    public PredictionId predictionIdentity() {
        return new PredictionId(predictionId);
    }

    /**
     * Returns the aggregate UUID as a task identity for task-scoped event types.
     *
     * @return typed task identity for task-scoped commands
     */
    public TaskId taskIdentity() {
        return new TaskId(predictionId);
    }

    /**
     * Returns the requested event type.
     *
     * @return event type
     */
    public PredictionEventType type() {
        return type;
    }

    /**
     * Returns the authenticated actor node identity.
     *
     * @return authenticated node identity
     */
    public NodeId actorNode() {
        return new NodeId(actorNodeId);
    }

    /**
     * Returns the logical supervisor actor, when supplied by a version-two command.
     *
     * @return supervisor identity, or null for version one
     */
    public String actorSupervisorId() {
        return actorSupervisorId;
    }

    /**
     * Returns the optional logical supervisor identity.
     *
     * @return supervisor identity, when supplied by a version-two command
     */
    public Optional<SupervisorId> actorSupervisor() {
        return Optional.ofNullable(actorSupervisorId).map(SupervisorId::new);
    }

    /**
     * Returns the logical worker actor, when supplied by a version-two command.
     *
     * @return worker identity, or null for version one
     */
    public String actorWorkerId() {
        return actorWorkerId;
    }

    /**
     * Returns the optional logical worker identity.
     *
     * @return worker identity, when supplied by a version-two command
     */
    public Optional<WorkerId> actorWorker() {
        return Optional.ofNullable(actorWorkerId).map(WorkerId::new);
    }

    /**
     * Returns a copy of the command payload.
     *
     * @return payload copy
     */
    public byte[] payload() {
        return payload.clone();
    }

    /**
     * Verifies signer binding and command signature.
     *
     * @return true when valid
     * @throws GeneralSecurityException when verification cannot be performed
     */
    public boolean verify() throws GeneralSecurityException {
        return NodeIdentity.deriveNodeId(signerPublicKey).equals(actorNodeId)
                && NodeIdentity.verifySignature(signerPublicKey, canonical(), signature);
    }

    /**
     * Encodes this command for HTTP transport or durable audit.
     *
     * @return encoded command
     */
    public byte[] encoded() {
        return encode(MAGIC, out -> {
            out.writeInt(formatVersion());
            writeUuid(out, commandId);
            writeUuid(out, projectId);
            writeUuid(out, predictionId);
            out.writeInt(type.wireCode());
            TEXT.writeText(out, actorNodeId);
            if (formatVersion() == 2) {
                TEXT.writeText(out, actorSupervisorId);
                TEXT.writeText(out, actorWorkerId);
            }
            BYTES.writeBytes(out, payload);
            BYTES.writeBytes(out, signerPublicKey);
            BYTES.writeBytes(out, signature);
        });
    }

    /**
     * Produces the canonical event bytes used for signing and hashing.
     */
    private byte[] canonical() {
        return encode(MAGIC, out -> {
            out.writeInt(formatVersion());
            writeUuid(out, commandId);
            writeUuid(out, projectId);
            writeUuid(out, predictionId);
            out.writeInt(type.wireCode());
            TEXT.writeText(out, actorNodeId);
            if (formatVersion() == 2) {
                TEXT.writeText(out, actorSupervisorId);
                TEXT.writeText(out, actorWorkerId);
            }
            BYTES.writeBytes(out, payload);
            BYTES.writeBytes(out, signerPublicKey);
        });
    }

    /**
     * Selects the command payload version from its logical identity fields.
     */
    private int formatVersion() {
        return actorSupervisorId == null ? 1 : 2;
    }
}
