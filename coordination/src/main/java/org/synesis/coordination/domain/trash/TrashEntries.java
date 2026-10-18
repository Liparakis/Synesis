package org.synesis.coordination.domain.trash;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.UUID;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * The signed payloads of the trash entries (ADR-0098 step 7, "The trash"; designer items 12 to 16). A trashed piece of
 * work is named by its lane and the lane's epoch, and kept as a commit under {@code refs/synesis/trash/<lane>/<epoch>}
 * on the computer that trashed it; the entry names the commit, so every computer can fetch it.
 */
public final class TrashEntries {

    /** Why work went to the trash: another computer took its lane over. */
    public static final String TAKEN_OVER = "TAKEN_OVER";
    /** Why work went to the trash: it was finished after its work group's goal was met. */
    public static final String LATE_FOR_MET_GOAL = "LATE_FOR_MET_GOAL";
    /** Why work went to the trash: its snapshot was still disputed or rejected when its work group completed. */
    public static final String DISPUTE_GIVEN_UP = "DISPUTE_GIVEN_UP";
    /** Why work went to the trash: its work group was cancelled before it was integrated. */
    public static final String GROUP_CANCELLED = "GROUP_CANCELLED";

    private static final WireFormat TEXT = WireFormat.bounded(256, false, "text bound", "truncated payload");
    /** A full Git object ID, SHA-1 or SHA-256: what reaches Git and Link, never a ref or an option. */
    private static final java.util.regex.Pattern COMMIT_ID = java.util.regex.Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private TrashEntries() {
    }

    /**
     * {@code WORK_TRASHED}: one lane's work at one epoch, kept as a commit. Written once, by the computer of the lane's
     * agent.
     *
     * @param laneId      the lane
     * @param epoch       the lane's epoch the work was done at
     * @param taskId      the lane's task
     * @param workGroupId the lane's work group
     * @param commit      the commit that holds the work, a full lowercase Git object ID
     * @param reason      why it went to the trash; a reason this build does not know is kept as written
     */
    public record WorkTrashed(UUID laneId, long epoch, UUID taskId, UUID workGroupId, String commit, String reason) {

        private static final int MAGIC = 0x53545731;

        /** Validates the fields. */
        public WorkTrashed {
            CoordinationNullException.requireNonNull(laneId, "laneId");
            CoordinationNullException.requireNonNull(taskId, "taskId");
            CoordinationNullException.requireNonNull(workGroupId, "workGroupId");
            requireEpoch(epoch);
            if (commit == null || !COMMIT_ID.matcher(commit).matches()) {
                throw new CoordinationArgumentException("commit is not a commit ID");
            }
            requireText(reason);
        }

        /**
         * Returns the encoded payload.
         *
         * @return the encoded payload
         */
        public byte[] encode() {
            return WireFormat.encode(MAGIC, out -> {
                WireFormat.writeUuid(out, laneId);
                out.writeLong(epoch);
                WireFormat.writeUuid(out, taskId);
                WireFormat.writeUuid(out, workGroupId);
                TEXT.writeText(out, commit);
                TEXT.writeText(out, reason);
            });
        }

        /**
         * Decodes and validates one payload.
         *
         * @param encoded bytes
         * @return the payload
         * @throws IOException when malformed
         */
        public static WorkTrashed decode(byte[] encoded) throws IOException {
            return read(encoded, MAGIC, in -> new WorkTrashed(WireFormat.readUuid(in), in.readLong(), WireFormat.readUuid(in),
                    WireFormat.readUuid(in), TEXT.readText(in), TEXT.readText(in)));
        }
    }

    /**
     * {@code TRASH_RESTORED}: a person brought trashed work back, in a work group of its own.
     *
     * @param laneId      the trashed lane
     * @param epoch       its epoch
     * @param workGroupId the work group created to bring it back
     */
    public record TrashRestored(UUID laneId, long epoch, UUID workGroupId) {

        private static final int MAGIC = 0x53545231;

        /** Validates the fields. */
        public TrashRestored {
            CoordinationNullException.requireNonNull(laneId, "laneId");
            CoordinationNullException.requireNonNull(workGroupId, "workGroupId");
            requireEpoch(epoch);
        }

        /**
         * Returns the encoded payload.
         *
         * @return the encoded payload
         */
        public byte[] encode() {
            return WireFormat.encode(MAGIC, out -> {
                WireFormat.writeUuid(out, laneId);
                out.writeLong(epoch);
                WireFormat.writeUuid(out, workGroupId);
            });
        }

        /**
         * Decodes and validates one payload.
         *
         * @param encoded bytes
         * @return the payload
         * @throws IOException when malformed
         */
        public static TrashRestored decode(byte[] encoded) throws IOException {
            return read(encoded, MAGIC, in -> new TrashRestored(WireFormat.readUuid(in), in.readLong(), WireFormat.readUuid(in)));
        }
    }

    /**
     * {@code TRASH_EMPTIED}: a person deleted trashed work for good; every computer deletes its copy of the ref.
     *
     * @param laneId the trashed lane
     * @param epoch  its epoch
     */
    public record TrashEmptied(UUID laneId, long epoch) {

        private static final int MAGIC = 0x53544531;

        /** Validates the fields. */
        public TrashEmptied {
            CoordinationNullException.requireNonNull(laneId, "laneId");
            requireEpoch(epoch);
        }

        /**
         * Returns the encoded payload.
         *
         * @return the encoded payload
         */
        public byte[] encode() {
            return WireFormat.encode(MAGIC, out -> {
                WireFormat.writeUuid(out, laneId);
                out.writeLong(epoch);
            });
        }

        /**
         * Decodes and validates one payload.
         *
         * @param encoded bytes
         * @return the payload
         * @throws IOException when malformed
         */
        public static TrashEmptied decode(byte[] encoded) throws IOException {
            return read(encoded, MAGIC, in -> new TrashEmptied(WireFormat.readUuid(in), in.readLong()));
        }
    }

    @FunctionalInterface
    private interface Reader<T> {

        T read(DataInputStream in) throws IOException;
    }

    private static <T> T read(byte[] encoded, int magic, Reader<T> reader) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != magic) {
                throw new CoordinationException("unsupported trash payload");
            }
            T value = reader.read(in);
            if (in.available() != 0) {
                throw new CoordinationException("trailing trash bytes");
            }
            return value;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed trash payload", failure);
        }
    }

    private static void requireEpoch(long epoch) {
        if (epoch < 0) {
            throw new CoordinationArgumentException("epoch out of bounds");
        }
    }

    private static void requireText(String value) {
        CoordinationNullException.requireNonNull(value, "text");
        if (value.isBlank()) {
            throw new CoordinationArgumentException("text blank");
        }
    }
}
