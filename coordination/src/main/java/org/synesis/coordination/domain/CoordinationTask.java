package org.synesis.coordination.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

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
public record CoordinationTask(
        UUID taskId,
        UUID projectId,
        String title,
        String capability,
        String creatorNodeId,
        String creatorSupervisorId,
        String creatorWorkerId) {

    private static final int MAGIC = 0x53435431;
    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_TEXT_BYTES = 8 * 1024;

    /**
     * Validates task identity and bounded text.
     */
    public CoordinationTask {
        Objects.requireNonNull(taskId, "task ID");
        Objects.requireNonNull(projectId, "project ID");
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
        Objects.requireNonNull(encoded, "encoded task");
        if (encoded.length > MAX_BYTES) {
            throw new IOException("task exceeds bound");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != 1) {
                throw new IOException("unsupported task format");
            }
            CoordinationTask task = new CoordinationTask(readUuid(in), readUuid(in), readText(in), readText(in),
                    readText(in), readText(in), readText(in));
            if (in.available() != 0) {
                throw new IOException("trailing task bytes");
            }
            return task;
        } catch (RuntimeException | java.io.EOFException failure) {
            throw new IOException("malformed task", failure);
        }
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(label + " is empty or exceeds bound");
        }
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > MAX_TEXT_BYTES) {
            throw new IOException("text bound");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("truncated text");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Encodes the task declaration for a signed command payload.
     *
     * @return bounded canonical bytes
     */
    public byte[] encoded() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(1);
            writeUuid(out, taskId);
            writeUuid(out, projectId);
            writeText(out, title);
            writeText(out, capability);
            writeText(out, creatorNodeId);
            writeText(out, creatorSupervisorId);
            writeText(out, creatorWorkerId);
            out.flush();
            if (bytes.size() > MAX_BYTES) {
                throw new IllegalArgumentException("task exceeds bound");
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
