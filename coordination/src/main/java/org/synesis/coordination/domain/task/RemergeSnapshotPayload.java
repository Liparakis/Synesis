package org.synesis.coordination.domain.task;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Signed payload of {@code REMERGE_SNAPSHOT_CREATED}: one lane's re-merge, to be reviewed again (ADR-0098 step 5,
 * designer items 13 to 15). After two copies of {@code main} diverged, the computer with the higher node ID merged its
 * own integrations again on top of the other copy's head, one merge commit per integration. The snapshot is that
 * lane's merge commit; its provenance is the original snapshot's, so the review grant and the verdict bind to the same
 * lane and epoch as the original review did.
 *
 * @param remergeId          the re-merge the snapshot belongs to: one per (other head, own old head) pair
 * @param originalSnapshotId the integrated snapshot whose work is merged again
 * @param otherHead          the other copy's head the re-merge starts from
 * @param ownHead            this copy's head when the re-merge was built
 * @param result             the commit the whole re-merge ends at, which becomes {@code main} once accepted
 * @param snapshot           the snapshot to review: its commit is the lane's merge commit, its base the commit before
 */
public record RemergeSnapshotPayload(String remergeId, String originalSnapshotId, String otherHead, String ownHead,
                                     String result, TaskSnapshotPayload snapshot) {

    private static final int MAGIC = 0x53524D31;
    private static final WireFormat BYTES = WireFormat.bounded(1_000_000, false, "payload bound", "truncated payload");

    /**
     * Validates the payload fields.
     */
    public RemergeSnapshotPayload {
        for (String field : new String[] {remergeId, originalSnapshotId, otherHead, ownHead, result}) {
            if (field == null || field.isBlank() || field.length() > 128) {
                throw new CoordinationArgumentException("re-merge field out of bounds");
            }
        }
        CoordinationNullException.requireNonNull(snapshot, "snapshot");
        if (snapshot.snapshotId().equals(originalSnapshotId)) {
            throw new CoordinationArgumentException("a re-merge snapshot is a new snapshot");
        }
    }

    /**
     * Decodes and validates one payload.
     *
     * @param encoded bytes
     * @return decoded payload
     * @throws IOException malformed payload
     */
    public static RemergeSnapshotPayload decode(byte[] encoded) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) {
                throw new CoordinationException("unsupported re-merge format");
            }
            String remergeId = BYTES.readText(in);
            String original = BYTES.readText(in);
            String otherHead = BYTES.readText(in);
            String ownHead = BYTES.readText(in);
            String result = BYTES.readText(in);
            byte[] snapshot = BYTES.readBytes(in);
            if (in.available() != 0) {
                throw new CoordinationException("trailing re-merge bytes");
            }
            return new RemergeSnapshotPayload(remergeId, original, otherHead, ownHead, result,
                    TaskSnapshotPayload.decode(snapshot));
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed re-merge snapshot", failure);
        }
    }

    /**
     * Encodes this payload deterministically.
     *
     * @return encoded bytes
     */
    public byte[] encode() {
        return WireFormat.encode(MAGIC, out -> {
            BYTES.writeText(out, remergeId);
            BYTES.writeText(out, originalSnapshotId);
            BYTES.writeText(out, otherHead);
            BYTES.writeText(out, ownHead);
            BYTES.writeText(out, result);
            BYTES.writeBytes(out, snapshot.encode());
        });
    }
}
