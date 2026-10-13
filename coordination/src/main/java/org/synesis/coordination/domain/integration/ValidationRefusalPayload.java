package org.synesis.coordination.domain.integration;

import static org.synesis.coordination.domain.identity.WireFormat.readUuid;
import static org.synesis.coordination.domain.identity.WireFormat.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Binary codec for the refusal of one snapshot by the project's validation command at integration.
 *
 * <p>The command ran on the integration commit of an attempt and exited non-zero. The snapshot
 * added last to that attempt is refused for it. The payload names the lane as it stood under the
 * project lock when the refusal was recorded, so replay reopens that lane without reading a clock
 * or Git.</p>
 *
 * @param attemptId           integration attempt whose candidate failed
 * @param snapshotId          refused snapshot
 * @param taskId              task of the refused snapshot
 * @param workGroupId         work group of the refused snapshot
 * @param authorityLineageId  authority lineage of the refused snapshot
 * @param claimEpoch          claim epoch the snapshot was published at
 * @param targetIntentId      intent that holds the lane now
 * @param targetIntentVersion version of that intent; equal to {@code claimEpoch} for the lane that
 *                            published, later for a lane that continued it
 * @param participant         participant holding that intent
 * @param candidateCommit     integration commit the command ran on
 * @param command             argument vector that was run, as the text of a JSON array, cut to the bound
 * @param workingDirectory    directory the command ran in, relative to the project root, cut to the bound
 * @param outcome             outcome of the command
 * @param exitCode            exit code of the command
 * @param output              the end of what the command printed, standard output then standard error
 * @since 1.0
 */
public record ValidationRefusalPayload(String attemptId, String snapshotId, UUID taskId, UUID workGroupId,
                                       UUID authorityLineageId, long claimEpoch, UUID targetIntentId,
                                       long targetIntentVersion, String participant, String candidateCommit,
                                       String command, String workingDirectory, String outcome, int exitCode,
                                       String output) {

    private static final int MAGIC = 0x49565246; // "IVRF"
    private static final int VERSION = 1;
    private static final int MAX_TEXT = 8 * 1024;
    private static final WireFormat TEXT = WireFormat.bounded(MAX_TEXT,
            true,
            "Invalid text length in payload",
            "Truncated payload").checkingWrites(MAX_TEXT, "text exceeds payload bound");

    /**
     * Validates the refusal's identities and bounded text.
     */
    public ValidationRefusalPayload {
        CoordinationNullException.requireNonNull(taskId, "taskId");
        CoordinationNullException.requireNonNull(workGroupId, "workGroupId");
        CoordinationNullException.requireNonNull(authorityLineageId, "authorityLineageId");
        CoordinationNullException.requireNonNull(targetIntentId, "targetIntentId");
        CoordinationNullException.requireNonNull(output, "output");
        if (claimEpoch < 1 || targetIntentVersion < claimEpoch) {
            throw new CoordinationArgumentException("target intent version must not precede a positive claim epoch");
        }
        for (String text : new String[] {attemptId, snapshotId, participant, candidateCommit, command,
                workingDirectory, outcome}) {
            if (CoordinationNullException.requireNonNull(text, "text").isBlank()) {
                throw new CoordinationArgumentException("validation refusal text is required");
            }
            TEXT.requireWritable(text, "text");
        }
        TEXT.requireWritable(output, "output");
    }

    /**
     * Keeps the end of what a command printed within the payload bound. The failure a command
     * reports is at the end of its output, so the start is what is dropped.
     *
     * @param stdout retained standard output
     * @param stderr retained standard error
     * @return standard output then standard error, cut at the front to the bound on a character boundary
     */
    public static String tail(String stdout, String stderr) {
        byte[] bytes = (stdout.isEmpty() || stderr.isEmpty() ? stdout + stderr : stdout + "\n" + stderr).getBytes(StandardCharsets.UTF_8);
        int start = Math.max(0, bytes.length - MAX_TEXT);
        while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    /**
     * Keeps the start of a text within the payload bound. A command is known by its executable
     * and first arguments, so the end is what is dropped.
     *
     * @param text text of any length
     * @return the text, cut at the end to the bound on a character boundary
     */
    public static String head(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int end = Math.min(bytes.length, MAX_TEXT);
        while (end < bytes.length && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * Decodes a {@link ValidationRefusalPayload} from binary format.
     *
     * @param encoded encoded bytes
     * @return decoded payload
     * @throws IOException if malformed or unsupported format
     */
    public static ValidationRefusalPayload decode(byte[] encoded) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded payload");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new CoordinationException("Unsupported validation refusal payload format");
            }
            ValidationRefusalPayload payload = new ValidationRefusalPayload(TEXT.readText(in),
                    TEXT.readText(in),
                    readUuid(in),
                    readUuid(in),
                    readUuid(in),
                    in.readLong(),
                    readUuid(in),
                    in.readLong(),
                    TEXT.readText(in),
                    TEXT.readText(in),
                    TEXT.readText(in),
                    TEXT.readText(in),
                    TEXT.readText(in),
                    in.readInt(),
                    TEXT.readText(in));
            if (in.available() != 0) {
                throw new CoordinationException("Trailing validation refusal payload bytes");
            }
            return payload;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("Malformed validation refusal payload", failure);
        }
    }

    /**
     * Returns the typed identity of the refused snapshot.
     *
     * @return the typed identity of the refused snapshot.
     */
    public SnapshotId snapshotIdentity() {
        return new SnapshotId(snapshotId);
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
            TEXT.writeText(out, snapshotId);
            writeUuid(out, taskId);
            writeUuid(out, workGroupId);
            writeUuid(out, authorityLineageId);
            out.writeLong(claimEpoch);
            writeUuid(out, targetIntentId);
            out.writeLong(targetIntentVersion);
            TEXT.writeText(out, participant);
            TEXT.writeText(out, candidateCommit);
            TEXT.writeText(out, command);
            TEXT.writeText(out, workingDirectory);
            TEXT.writeText(out, outcome);
            out.writeInt(exitCode);
            TEXT.writeText(out, output);
        });
    }
}
