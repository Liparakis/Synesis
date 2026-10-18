package org.synesis.coordination.domain.task;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Signed payload of {@code COMBINE_REQUIRED}: Git could not merge one advance of a re-merge, so an agent other than its
 * authors combines the two versions in a combine lane (ADR-0098 step 5, designer item 22; ADR-0100 "The combine
 * lane"). The combine lane works in a work group and on a task of its own, both named by the combine.
 *
 * @param combineId   the combine: one per re-merge and advance
 * @param remergeId   the re-merge that met the conflict
 * @param otherHead   the other copy's head the re-merge started from
 * @param ownHead     this copy's head when the re-merge was built
 * @param base        the commit the advance could not be merged onto: the other head, or the re-merge so far
 * @param theirs      the advance's head, the version to combine with the base
 * @param snapshotIds the advance's snapshots, whose lanes the combine acts for
 */
public record CombinePayload(String combineId, String remergeId, String otherHead, String ownHead, String base, String theirs,
                             List<String> snapshotIds) {

    private static final int MAGIC = 0x53434231;
    private static final int MAX_SNAPSHOTS = 128;
    private static final WireFormat BYTES = WireFormat.bounded(1_000_000, false, "payload bound", "truncated payload");

    /**
     * Validates the payload fields.
     */
    public CombinePayload {
        for (String field : new String[] {combineId, remergeId, otherHead, ownHead, base, theirs}) {
            requireField(field);
        }
        snapshotIds = List.copyOf(CoordinationNullException.requireNonNull(snapshotIds, "snapshotIds"));
        if (snapshotIds.isEmpty() || snapshotIds.size() > MAX_SNAPSHOTS) {
            throw new CoordinationArgumentException("a combine names 1 to " + MAX_SNAPSHOTS + " snapshots");
        }
        snapshotIds.forEach(CombinePayload::requireField);
    }

    private static void requireField(String field) {
        if (field == null || field.isBlank() || field.length() > 128) {
            throw new CoordinationArgumentException("combine field out of bounds");
        }
    }

    /**
     * The work group of the combine lane, named by the combine so that every computer derives the same one.
     *
     * @return the work group's ID
     */
    public UUID workGroupId() {
        return UUID.nameUUIDFromBytes(("combine-group:" + combineId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The task of the combine lane, named by the combine.
     *
     * @return the task's ID
     */
    public UUID taskId() {
        return UUID.nameUUIDFromBytes(("combine-task:" + combineId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes and validates one payload.
     *
     * @param encoded bytes
     * @return decoded payload
     * @throws IOException malformed payload
     */
    public static CombinePayload decode(byte[] encoded) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) {
                throw new CoordinationException("unsupported combine format");
            }
            String combineId = BYTES.readText(in);
            String remergeId = BYTES.readText(in);
            String otherHead = BYTES.readText(in);
            String ownHead = BYTES.readText(in);
            String base = BYTES.readText(in);
            String theirs = BYTES.readText(in);
            int count = in.readInt();
            if (count < 1 || count > MAX_SNAPSHOTS) {
                throw new CoordinationException("combine snapshot count out of bounds");
            }
            List<String> snapshotIds = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                snapshotIds.add(BYTES.readText(in));
            }
            if (in.available() != 0) {
                throw new CoordinationException("trailing combine bytes");
            }
            return new CombinePayload(combineId, remergeId, otherHead, ownHead, base, theirs, snapshotIds);
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed combine", failure);
        }
    }

    /**
     * Encodes this payload deterministically.
     *
     * @return encoded bytes
     */
    public byte[] encode() {
        return WireFormat.encode(MAGIC, out -> {
            BYTES.writeText(out, combineId);
            BYTES.writeText(out, remergeId);
            BYTES.writeText(out, otherHead);
            BYTES.writeText(out, ownHead);
            BYTES.writeText(out, base);
            BYTES.writeText(out, theirs);
            out.writeInt(snapshotIds.size());
            for (String snapshotId : snapshotIds) {
                BYTES.writeText(out, snapshotId);
            }
        });
    }
}
