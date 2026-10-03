package org.synesis.coordination.domain.task;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationInvariantError;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Durable payload pinning a prepared completion tree before lane fencing.
 *
 * @param taskId       task identifier
 * @param completionId idempotent completion transaction identifier
 * @param laneId       mutation lane identifier
 * @param claimEpoch   claim epoch being fenced
 * @param baseCommit   lane base commit
 * @param preparedRef  transaction-owned prepared Git ref
 * @param treeHash     prepared tree object hash
 * @param changedPaths complete changed path list
 */
public record CompletionPreparedPayload(UUID taskId, String completionId, UUID laneId, long claimEpoch,
                                        String baseCommit, String preparedRef, String treeHash,
                                        List<String> changedPaths) {

    private static final int MAGIC = 0x43505250;
    private static final int VERSION = 1;
    private static final int MAX_TEXT = 64 * 1024;

    /**
     * Validates prepared completion invariants.
     */
    public CompletionPreparedPayload {
        CoordinationNullException.requireNonNull(taskId, "taskId");
        CoordinationNullException.requireNonNull(completionId, "completionId");
        CoordinationNullException.requireNonNull(laneId, "laneId");
        if (claimEpoch < 1) {
            throw new CoordinationArgumentException("claim epoch must be positive");
        }
        CoordinationNullException.requireNonNull(baseCommit, "baseCommit");
        CoordinationNullException.requireNonNull(preparedRef, "preparedRef");
        CoordinationNullException.requireNonNull(treeHash, "treeHash");
        changedPaths = List.copyOf(CoordinationNullException.requireNonNull(changedPaths, "changedPaths"));
    }

    /**
     * Decodes a signed event payload.
     *
     * @param encoded encoded bytes
     * @return decoded payload
     * @throws IOException malformed payload
     */
    public static CompletionPreparedPayload decode(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new CoordinationException("unsupported prepared payload");
            }
            UUID task = readUuid(in);
            String completion = readText(in);
            UUID lane = readUuid(in);
            long epoch = in.readLong();
            String base = readText(in);
            String ref = readText(in);
            String tree = readText(in);
            int count = in.readInt();
            if (count < 0 || count > 128) {
                throw new CoordinationException("path bound");
            }
            ArrayList<String> paths = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                paths.add(readText(in));
            }
            return new CompletionPreparedPayload(task, completion, lane, epoch, base, ref, tree, paths);
        } catch (RuntimeException failure) {
            throw new CoordinationException("malformed prepared payload", failure);
        }
    }

    /**
     * Writes a UUID in the canonical binary payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
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
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT) {
            throw new CoordinationException("text bound");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * Reads bounded UTF-8 text and validates its encoded length.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_TEXT) {
            throw new CoordinationException("text bound");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new CoordinationException("truncated payload");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns the typed task identity associated with this prepared completion.
     *
     * @return prepared task identity
     */
    public TaskId taskIdentity() {
        return new TaskId(taskId);
    }

    /**
     * Returns the typed work-intent lane identity being fenced.
     *
     * @return lane identity
     */
    public WorkIntentId laneIdentity() {
        return new WorkIntentId(laneId);
    }

    /**
     * Returns the typed claim epoch being fenced.
     *
     * @return claim epoch
     */
    public ClaimEpoch claimEpochIdentity() {
        return new ClaimEpoch(claimEpoch);
    }

    /**
     * Encodes the signed event payload.
     *
     * @return encoded payload
     */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            writeUuid(out, taskId);
            writeText(out, completionId);
            writeUuid(out, laneId);
            out.writeLong(claimEpoch);
            writeText(out, baseCommit);
            writeText(out, preparedRef);
            writeText(out, treeHash);
            out.writeInt(changedPaths.size());
            for (String path : changedPaths) {
                writeText(out, path);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }
}
