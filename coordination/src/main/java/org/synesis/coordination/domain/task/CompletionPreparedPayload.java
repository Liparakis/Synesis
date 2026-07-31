package org.synesis.coordination.domain.task;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable payload pinning a prepared completion tree before lane fencing.
 * @param taskId task identifier
 * @param completionId idempotent completion transaction identifier
 * @param laneId mutation lane identifier
 * @param claimEpoch claim epoch being fenced
 * @param baseCommit lane base commit
 * @param preparedRef transaction-owned prepared Git ref
 * @param treeHash prepared tree object hash
 * @param changedPaths complete changed path list
 */
public record CompletionPreparedPayload(UUID taskId, String completionId, UUID laneId,
        long claimEpoch, String baseCommit, String preparedRef, String treeHash,
        List<String> changedPaths) {
    private static final int MAGIC = 0x43505250;
    private static final int VERSION = 1;
    private static final int MAX_TEXT = 64 * 1024;

    /** Validates prepared completion invariants. */
    public CompletionPreparedPayload {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(completionId, "completionId");
        Objects.requireNonNull(laneId, "laneId");
        if (claimEpoch < 1) throw new IllegalArgumentException("claim epoch must be positive");
        Objects.requireNonNull(baseCommit, "baseCommit");
        Objects.requireNonNull(preparedRef, "preparedRef");
        Objects.requireNonNull(treeHash, "treeHash");
        changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
    }

    /** Encodes the signed event payload.
     * @return encoded payload
     */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(VERSION);
            writeUuid(out, taskId); writeText(out, completionId); writeUuid(out, laneId);
            out.writeLong(claimEpoch); writeText(out, baseCommit); writeText(out, preparedRef);
            writeText(out, treeHash); out.writeInt(changedPaths.size());
            for (String path : changedPaths) writeText(out, path);
            out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /** Decodes a signed event payload.
     * @param encoded encoded bytes
     * @return decoded payload
     * @throws IOException malformed payload
     */
    public static CompletionPreparedPayload decode(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("unsupported prepared payload");
            UUID task = readUuid(in); String completion = readText(in); UUID lane = readUuid(in);
            long epoch = in.readLong(); String base = readText(in); String ref = readText(in);
            String tree = readText(in); int count = in.readInt();
            if (count < 0 || count > 128) throw new IOException("path bound");
            java.util.ArrayList<String> paths = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) paths.add(readText(in));
            return new CompletionPreparedPayload(task, completion, lane, epoch, base, ref, tree, paths);
        } catch (RuntimeException failure) { throw new IOException("malformed prepared payload", failure); }
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void writeText(DataOutputStream out, String value) throws IOException { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); if (bytes.length > MAX_TEXT) throw new IOException("text bound"); out.writeInt(bytes.length); out.write(bytes); }
    private static String readText(DataInputStream in) throws IOException { int length = in.readInt(); if (length < 0 || length > MAX_TEXT) throw new IOException("text bound"); byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new IOException("truncated payload"); return new String(bytes, StandardCharsets.UTF_8); }
}
