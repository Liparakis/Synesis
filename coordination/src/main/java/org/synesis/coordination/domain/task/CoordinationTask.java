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
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.identity.SupervisorId;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.domain.identity.WorkerId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Bounded task declaration used to establish a claimable unit of work.
 *
 * @param taskId              task identifier
 * @param projectId           project identifier
 * @param title               human-readable task title
 * @param capability          requested capability
 * @param creatorNodeId       creator node identity
 * @param creatorSupervisorId creator supervisor identity
 * @param creatorWorkerId     creator worker identity
 */
@SuppressWarnings("DuplicatedCode")
public record CoordinationTask(UUID taskId, UUID projectId, String title, String capability, String creatorNodeId,
                               String creatorSupervisorId, String creatorWorkerId) {

    private static final int MAGIC = 0x53435431;
    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_TEXT_BYTES = 8 * 1024;
    private static final WireFormat TEXT = WireFormat.bounded(MAX_TEXT_BYTES, false, "text bound", "truncated text");

    /**
     * Validates task identity and bounded text.
     */
    public CoordinationTask {
        CoordinationNullException.requireNonNull(taskId, "task ID");
        CoordinationNullException.requireNonNull(projectId, "project ID");
        requireText(title, "title");
        requireText(capability, "capability");
        requireText(creatorNodeId, "creator node ID");
        requireText(creatorSupervisorId, "creator supervisor ID");
        requireText(creatorWorkerId, "creator worker ID");
    }

    /**
     * Decodes one task declaration.
     *
     * @param encoded canonical bytes
     * @return task declaration
     * @throws IOException malformed or unsupported bytes
     */
    public static CoordinationTask decode(byte[] encoded) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded task");
        if (encoded.length > MAX_BYTES) {
            throw new CoordinationException("task exceeds bound");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != 1) {
                throw new CoordinationException("unsupported task format");
            }
            CoordinationTask task = new CoordinationTask(readUuid(in),
                    readUuid(in),
                    TEXT.readText(in),
                    TEXT.readText(in),
                    TEXT.readText(in),
                    TEXT.readText(in),
                    TEXT.readText(in));
            if (in.available() != 0) {
                throw new CoordinationException("trailing task bytes");
            }
            return task;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed task", failure);
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
     * @return task identity
     */
    public TaskId taskIdentity() {
        return new TaskId(taskId);
    }

    /**
     * Returns the typed project identity.
     *
     * @return project identity
     */
    public ProjectId projectIdentity() {
        return new ProjectId(projectId);
    }

    /**
     * Returns the creator's authenticated node identity.
     *
     * @return creator node identity
     */
    public NodeId creatorNode() {
        return new NodeId(creatorNodeId);
    }

    /**
     * Returns the creator's supervisor identity.
     *
     * @return creator supervisor identity
     */
    public SupervisorId creatorSupervisor() {
        return new SupervisorId(creatorSupervisorId);
    }

    /**
     * Returns the creator's worker identity.
     *
     * @return creator worker identity
     */
    public WorkerId creatorWorker() {
        return new WorkerId(creatorWorkerId);
    }

    /**
     * Encodes the task declaration for a signed command payload.
     *
     * @return bounded canonical bytes
     */
    public byte[] encoded() {
        byte[] encoded = encode(MAGIC, out -> {
            out.writeInt(1);
            writeUuid(out, taskId);
            writeUuid(out, projectId);
            TEXT.writeText(out, title);
            TEXT.writeText(out, capability);
            TEXT.writeText(out, creatorNodeId);
            TEXT.writeText(out, creatorSupervisorId);
            TEXT.writeText(out, creatorWorkerId);
        });
        if (encoded.length > MAX_BYTES) {
            throw new CoordinationArgumentException("task exceeds bound");
        }
        return encoded;
    }
}
