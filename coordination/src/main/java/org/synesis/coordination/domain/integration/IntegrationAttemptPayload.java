package org.synesis.coordination.domain.integration;




import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Binary codec for Stage 2B Slice 3 integration attempt events.
 *
 * @param attemptId            attempt locator ID
 * @param projectId            project UUID
 * @param taskSnapshotIds      list of included task snapshot IDs
 * @param expectedControlHead  expected control HEAD SHA
 * @param integrationCommitSha produced integration commit SHA
 * @param status               attempt status
 * @param failureReason        failure explanation
 * @since 1.0
 */
public record IntegrationAttemptPayload(
        String attemptId,
        UUID projectId,
        List<String> taskSnapshotIds,
        String expectedControlHead,
        String integrationCommitSha,
        String status,
        String failureReason
) {

    private static final int MAGIC = 0x494E5447; // "INTG"
    private static final int VERSION = 1;
    private static final int MAX_TEXT = 64 * 1024;

    /**
     * Compact constructor.
     *
     * @param attemptId            attempt locator ID
     * @param projectId            project UUID
     * @param taskSnapshotIds      list of task snapshot IDs
     * @param expectedControlHead  expected control HEAD SHA
     * @param integrationCommitSha integration commit SHA
     * @param status               attempt status
     * @param failureReason        failure reason
     */
    public IntegrationAttemptPayload {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(projectId, "projectId");
        taskSnapshotIds = List.copyOf(Objects.requireNonNull(taskSnapshotIds, "taskSnapshotIds"));
        Objects.requireNonNull(expectedControlHead, "expectedControlHead");
        Objects.requireNonNull(integrationCommitSha, "integrationCommitSha");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(failureReason, "failureReason");
    }

    /**
     * Encodes this payload into binary format.
     *
     * @return encoded bytes
     */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            writeText(out, attemptId);
            writeUuid(out, projectId);
            out.writeInt(taskSnapshotIds.size());
            for (String snap : taskSnapshotIds) {
                writeText(out, snap);
            }
            writeText(out, expectedControlHead);
            writeText(out, integrationCommitSha);
            writeText(out, status);
            writeText(out, failureReason);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * Decodes an {@link IntegrationAttemptPayload} from binary format.
     *
     * @param encoded encoded bytes
     * @return decoded payload
     * @throws IOException if malformed or unsupported format
     */
    public static IntegrationAttemptPayload decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded payload");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException("Unsupported integration attempt payload format");
            }
            String attemptId = readText(in);
            UUID projectId = readUuid(in);
            int snapCount = in.readInt();
            List<String> snaps = new ArrayList<>(snapCount);
            for (int i = 0; i < snapCount; i++) {
                snaps.add(readText(in));
            }
            String expectedHead = readText(in);
            String integrationCommit = readText(in);
            String status = readText(in);
            String failureReason = readText(in);

            return new IntegrationAttemptPayload(attemptId, projectId, snaps, expectedHead,
                    integrationCommit, status, failureReason);
        } catch (RuntimeException failure) {
            throw new IOException("Malformed integration attempt payload", failure);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID val) throws IOException {
        out.writeLong(val.getMostSignificantBits());
        out.writeLong(val.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeText(DataOutputStream out, String val) throws IOException {
        byte[] b = val.getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_TEXT) {
            throw new IOException("text exceeds payload bound");
        }
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readText(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_TEXT) {
            throw new IOException("Invalid text length in payload");
        }
        byte[] b = in.readNBytes(len);
        if (b.length != len) {
            throw new IOException("Truncated payload");
        }
        return new String(b, StandardCharsets.UTF_8);
    }
}
