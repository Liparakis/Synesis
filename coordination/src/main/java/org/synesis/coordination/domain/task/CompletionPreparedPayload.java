package org.synesis.coordination.domain.task;

import static org.synesis.coordination.domain.identity.WireFormat.readUuid;
import static org.synesis.coordination.domain.identity.WireFormat.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
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
    private static final WireFormat TEXT = WireFormat.bounded(MAX_TEXT, true, "text bound", "truncated payload")
            .checkingWrites(MAX_TEXT, "text bound");

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
        TEXT.requireWritable(completionId, "completionId");
        TEXT.requireWritable(baseCommit, "baseCommit");
        TEXT.requireWritable(preparedRef, "preparedRef");
        TEXT.requireWritable(treeHash, "treeHash");
        TEXT.requireWritable(changedPaths, "changedPaths entry");
    }

    /**
     * Decodes a signed event payload.
     *
     * @param encoded encoded bytes
     * @return decoded payload
     * @throws IOException malformed payload
     */
    static CompletionPreparedPayload decode(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new CoordinationException("unsupported prepared payload");
            }
            UUID task = readUuid(in);
            String completion = TEXT.readText(in);
            UUID lane = readUuid(in);
            long epoch = in.readLong();
            String base = TEXT.readText(in);
            String ref = TEXT.readText(in);
            String tree = TEXT.readText(in);
            int count = in.readInt();
            if (count < 0 || count > 128) {
                throw new CoordinationException("path bound");
            }
            ArrayList<String> paths = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                paths.add(TEXT.readText(in));
            }
            if (in.available() != 0) {
                throw new CoordinationException("trailing prepared payload bytes");
            }
            return new CompletionPreparedPayload(task, completion, lane, epoch, base, ref, tree, paths);
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed prepared payload", failure);
        }
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
        return WireFormat.encode(MAGIC, out -> {
            out.writeInt(VERSION);
            writeUuid(out, taskId);
            TEXT.writeText(out, completionId);
            writeUuid(out, laneId);
            out.writeLong(claimEpoch);
            TEXT.writeText(out, baseCommit);
            TEXT.writeText(out, preparedRef);
            TEXT.writeText(out, treeHash);
            out.writeInt(changedPaths.size());
            for (String path : changedPaths) {
                TEXT.writeText(out, path);
            }
        });
    }
}
