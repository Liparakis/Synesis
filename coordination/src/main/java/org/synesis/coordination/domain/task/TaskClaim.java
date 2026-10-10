package org.synesis.coordination.domain.task;

import static org.synesis.coordination.domain.identity.WireFormat.encode;
import static org.synesis.coordination.domain.identity.WireFormat.readUuid;
import static org.synesis.coordination.domain.identity.WireFormat.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.SupervisorId;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.domain.identity.WorkerId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Bounded task claim submitted by one authenticated supervisor.
 *
 * @param taskId            task identifier
 * @param ownerNodeId       claiming node identity
 * @param ownerSupervisorId claiming supervisor identity
 * @param ownerWorkerId     claiming worker identity
 */
@SuppressWarnings("DuplicatedCode")
public record TaskClaim(UUID taskId, String ownerNodeId, String ownerSupervisorId, String ownerWorkerId) {

    private static final int MAGIC = 0x53544331;
    private static final int MAX_TEXT_BYTES = 8 * 1024;
    private static final WireFormat TEXT = WireFormat.bounded(MAX_TEXT_BYTES, false, "text bound", "truncated text");

    /**
     * Validates claim identity and bounded text.
     */
    public TaskClaim {
        CoordinationNullException.requireNonNull(taskId, "task ID");
        requireText(ownerNodeId, "owner node ID");
        requireText(ownerSupervisorId, "owner supervisor ID");
        requireText(ownerWorkerId, "owner worker ID");
    }

    /**
     * Decodes one claim.
     *
     * @param encoded canonical bytes
     * @return claim
     * @throws IOException malformed or unsupported bytes
     */
    public static TaskClaim decode(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != 1) {
                throw new CoordinationException("unsupported claim format");
            }
            TaskClaim claim = new TaskClaim(readUuid(in),
                    TEXT.readText(in),
                    TEXT.readText(in),
                    TEXT.readText(in));
            if (in.available() != 0) {
                throw new CoordinationException("trailing claim bytes");
            }
            return claim;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed task claim", failure);
        }
    }

    /**
     * Checks that the text value satisfies this type's bounds.
     */
    private static void requireText(String value, String label) {
        CoordinationNullException.requireNonNull(value, label);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new CoordinationArgumentException(label + " is empty or exceeds bound");
        }
    }

    /**
     * Returns the typed task identity.
     *
     * @return the typed task identity.
     */
    public TaskId taskIdentity() {
        return new TaskId(taskId);
    }

    /**
     * Returns the authenticated owner node identity.
     *
     * @return the authenticated owner node identity.
     */
    public NodeId ownerNode() {
        return new NodeId(ownerNodeId);
    }

    /**
     * Returns the owner supervisor identity.
     *
     * @return the owner supervisor identity.
     */
    public SupervisorId ownerSupervisor() {
        return new SupervisorId(ownerSupervisorId);
    }

    /**
     * Returns the owner worker identity.
     *
     * @return the owner worker identity.
     */
    public WorkerId ownerWorker() {
        return new WorkerId(ownerWorkerId);
    }

    /**
     * Encodes the claim for a signed command payload.
     *
     * @return canonical bytes
     */
    public byte[] encoded() {
        return encode(MAGIC, out -> {
            out.writeInt(1);
            writeUuid(out, taskId);
            TEXT.writeText(out, ownerNodeId);
            TEXT.writeText(out, ownerSupervisorId);
            TEXT.writeText(out, ownerWorkerId);
        });
    }
}
