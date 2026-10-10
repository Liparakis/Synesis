package org.synesis.coordination.domain.integration;

import static org.synesis.coordination.domain.identity.WireFormat.readUuid;
import static org.synesis.coordination.domain.identity.WireFormat.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.identity.IntegrationAttemptId;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

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
@SuppressWarnings("DuplicatedCode")
public record IntegrationAttemptPayload(String attemptId, UUID projectId, List<String> taskSnapshotIds,
                                        String expectedControlHead, String integrationCommitSha, String status,
                                        String failureReason) {

    private static final int MAGIC = 0x494E5447; // "INTG"
    private static final int VERSION = 1;
    private static final int MAX_TEXT = 64 * 1024;
    // Every producer appends this payload as an event, whose payload is capped at 64 KiB, and each snapshot ID costs at
    // least its 4-byte length prefix, so no payload that was ever appended lists more IDs than this.
    private static final int MAX_SNAPSHOT_IDS = MAX_TEXT / 4;
    private static final WireFormat TEXT = WireFormat.bounded(MAX_TEXT,
            true,
            "Invalid text length in payload",
            "Truncated payload").checkingWrites(MAX_TEXT, "text exceeds payload bound");

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
        CoordinationNullException.requireNonNull(attemptId, "attemptId");
        CoordinationNullException.requireNonNull(projectId, "projectId");
        taskSnapshotIds = List.copyOf(CoordinationNullException.requireNonNull(taskSnapshotIds, "taskSnapshotIds"));
        CoordinationNullException.requireNonNull(expectedControlHead, "expectedControlHead");
        CoordinationNullException.requireNonNull(integrationCommitSha, "integrationCommitSha");
        CoordinationNullException.requireNonNull(status, "status");
        CoordinationNullException.requireNonNull(failureReason, "failureReason");
        if (taskSnapshotIds.size() > MAX_SNAPSHOT_IDS) {
            throw new CoordinationArgumentException("taskSnapshotIds exceeds " + MAX_SNAPSHOT_IDS + " entries");
        }
        for (String text : List.of(attemptId, expectedControlHead, integrationCommitSha, status, failureReason)) {
            TEXT.requireWritable(text, "text");
        }
        TEXT.requireWritable(taskSnapshotIds, "task snapshot ID");
    }

    /**
     * Decodes an {@link IntegrationAttemptPayload} from binary format.
     *
     * @param encoded encoded bytes
     * @return decoded payload
     * @throws IOException if malformed or unsupported format
     */
    public static IntegrationAttemptPayload decode(byte[] encoded) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded payload");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new CoordinationException("Unsupported integration attempt payload format");
            }
            String attemptId = TEXT.readText(in);
            UUID projectId = readUuid(in);
            int snapCount = in.readInt();
            if (snapCount < 0 || snapCount > MAX_SNAPSHOT_IDS) {
                throw new CoordinationException("Invalid snapshot count: " + snapCount);
            }
            List<String> snaps = new ArrayList<>(snapCount);
            for (int i = 0; i < snapCount; i++) {
                snaps.add(TEXT.readText(in));
            }
            String expectedHead = TEXT.readText(in);
            String integrationCommit = TEXT.readText(in);
            String status = TEXT.readText(in);
            String failureReason = TEXT.readText(in);
            if (in.available() != 0) {
                throw new CoordinationException("Trailing integration attempt payload bytes");
            }

            return new IntegrationAttemptPayload(attemptId,
                    projectId,
                    snaps,
                    expectedHead,
                    integrationCommit,
                    status,
                    failureReason);
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("Malformed integration attempt payload", failure);
        }
    }

    /**
     * Returns the typed identity of this integration attempt.
     *
     * @return the typed identity of this integration attempt.
     */
    public IntegrationAttemptId identity() {
        return new IntegrationAttemptId(attemptId);
    }

    /**
     * Encodes this payload into binary format.
     *
     * @return encoded bytes
     */
    public byte[] encode() {
        return WireFormat.encode(MAGIC, out -> {
            out.writeInt(VERSION);
            TEXT.writeText(out, attemptId);
            writeUuid(out, projectId);
            out.writeInt(taskSnapshotIds.size());
            for (String snap : taskSnapshotIds) {
                TEXT.writeText(out, snap);
            }
            TEXT.writeText(out, expectedControlHead);
            TEXT.writeText(out, integrationCommitSha);
            TEXT.writeText(out, status);
            TEXT.writeText(out, failureReason);
        });
    }
}
