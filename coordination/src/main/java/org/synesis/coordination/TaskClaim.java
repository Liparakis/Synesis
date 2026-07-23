package org.synesis.coordination;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded task claim submitted by one authenticated supervisor.
 * @param taskId task identifier
 * @param ownerNodeId claiming node identity
 * @param ownerSupervisorId claiming supervisor identity
 * @param ownerWorkerId claiming worker identity
 */
public record TaskClaim(UUID taskId, String ownerNodeId, String ownerSupervisorId, String ownerWorkerId) {
    private static final int MAGIC = 0x53544331;
    private static final int MAX_TEXT_BYTES = 8 * 1024;

    /** Validates claim identity and bounded text. */
    public TaskClaim {
        Objects.requireNonNull(taskId, "task ID");
        requireText(ownerNodeId, "owner node ID");
        requireText(ownerSupervisorId, "owner supervisor ID");
        requireText(ownerWorkerId, "owner worker ID");
    }

    /** Encodes the claim for a signed command payload.
     * @return canonical bytes
     */
    public byte[] encoded() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(1); out.writeLong(taskId.getMostSignificantBits());
            out.writeLong(taskId.getLeastSignificantBits()); writeText(out, ownerNodeId);
            writeText(out, ownerSupervisorId); writeText(out, ownerWorkerId); out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /** Decodes one claim.
     * @param encoded canonical bytes
     * @return claim
     * @throws IOException malformed or unsupported bytes
     */
    public static TaskClaim decode(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("unsupported claim format");
            TaskClaim claim = new TaskClaim(new UUID(in.readLong(), in.readLong()), readText(in), readText(in), readText(in));
            if (in.available() != 0) throw new IOException("trailing claim bytes"); return claim;
        } catch (RuntimeException | java.io.EOFException failure) { throw new IOException("malformed task claim", failure); }
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(label + " is empty or exceeds bound");
        }
    }
    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt(); if (length < 1 || length > MAX_TEXT_BYTES) throw new IOException("text bound");
        byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new IOException("truncated text");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
