package org.synesis.coordination.domain.collaboration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.LaneGrantId;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationInvariantError;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Immutable, grant-bound decision recorded by a reviewer for one snapshot.
 *
 * @param grantId           consumed review grant
 * @param workGroupId       logical work group
 * @param targetIntentId    reviewed implementer intent
 * @param targetParticipant authorized reviewer participant
 * @param claimEpoch        reviewed authority epoch
 * @param taskId            reviewed task
 * @param snapshotId        immutable snapshot identifier
 * @param result            accepted or rejected
 * @param reason            bounded decision explanation
 * @param sourceParticipant implementer participant for rejection routing
 */
public record ReviewValidationPayload(UUID grantId, UUID workGroupId, UUID targetIntentId, String targetParticipant,
                                      long claimEpoch, UUID taskId, String snapshotId, String result, String reason,
                                      String sourceParticipant) {

    private static final int MAGIC = 0x53525631;
    private static final int MAX_TEXT = 64 * 1024;

    /**
     * Validates the decision's identity, result, and bounded text.
     */
    public ReviewValidationPayload {
        CoordinationNullException.requireNonNull(grantId, "grantId");
        CoordinationNullException.requireNonNull(workGroupId, "workGroupId");
        CoordinationNullException.requireNonNull(targetIntentId, "targetIntentId");
        requireText(targetParticipant, "targetParticipant");
        if (claimEpoch < 1) {
            throw new CoordinationArgumentException("claim epoch must be positive");
        }
        CoordinationNullException.requireNonNull(taskId, "taskId");
        requireText(snapshotId, "snapshotId");
        result = CoordinationNullException.requireNonNull(result, "result").trim().toUpperCase(Locale.ROOT);
        if (!result.equals("ACCEPTED") && !result.equals("REJECTED")) {
            throw new CoordinationArgumentException("review result must be ACCEPTED or REJECTED");
        }
        reason = reason == null ? "" : reason;
        if (result.equals("REJECTED") && reason.isBlank()) {
            throw new CoordinationArgumentException("rejection reason is required");
        }
        if (reason.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT) {
            throw new CoordinationArgumentException("reason exceeds payload bound");
        }
        requireText(sourceParticipant, "sourceParticipant");
    }

    /**
     * Decodes one review decision.
     *
     * @param encoded encoded decision
     * @return decoded decision
     * @throws IOException malformed payload
     */
    public static ReviewValidationPayload decode(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) {
                throw new CoordinationException("unsupported review validation format");
            }
            ReviewValidationPayload payload = new ReviewValidationPayload(uuid(in),
                    uuid(in),
                    uuid(in),
                    readText(in),
                    in.readLong(),
                    uuid(in),
                    readText(in),
                    readText(in),
                    readText(in),
                    readText(in));
            if (in.available() != 0) {
                throw new CoordinationException("trailing review validation bytes");
            }
            return payload;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed review validation", failure);
        }
    }

    /**
     * Checks that the text value satisfies this type's bounds.
     */
    private static void requireText(String value, String field) {
        CoordinationNullException.requireNonNull(value, field);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT) {
            throw new CoordinationArgumentException(field + " is empty or exceeds payload bound");
        }
    }

    /**
     * Writes a UUID in the canonical binary payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    /**
     * Reads a UUID from the canonical binary payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    /**
     * Encodes bounded UTF-8 review text with its byte length.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT) {
            throw new CoordinationException("text exceeds payload bound");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * Reads bounded UTF-8 text and validates its encoded length.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_TEXT) {
            throw new CoordinationException("invalid review text length");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new CoordinationException("truncated review validation");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns the consumed grant identity.
     *
     * @return the consumed grant identity.
     */
    public LaneGrantId grantIdentity() {
        return new LaneGrantId(grantId);
    }

    /**
     * Returns the reviewed work-group identity.
     *
     * @return the reviewed work-group identity.
     */
    public WorkGroupId workGroupIdentity() {
        return new WorkGroupId(workGroupId);
    }

    /**
     * Returns the reviewed producer lane identity.
     *
     * @return the reviewed producer lane identity.
     */
    public WorkIntentId targetIntentIdentity() {
        return new WorkIntentId(targetIntentId);
    }

    /**
     * Returns the authorized reviewer participant identity.
     *
     * @return the authorized reviewer participant identity.
     */
    public ParticipantId targetParticipantIdentity() {
        return new ParticipantId(targetParticipant);
    }

    /**
     * Returns the producer claim epoch bound to this decision.
     *
     * @return the producer claim epoch bound to this decision.
     */
    public ClaimEpoch claimEpochIdentity() {
        return new ClaimEpoch(claimEpoch);
    }

    /**
     * Returns the reviewed task identity.
     *
     * @return the reviewed task identity.
     */
    public TaskId taskIdentity() {
        return new TaskId(taskId);
    }

    /**
     * Returns the immutable reviewed snapshot identity.
     *
     * @return the immutable reviewed snapshot identity.
     */
    public SnapshotId snapshotIdentity() {
        return new SnapshotId(snapshotId);
    }

    /**
     * Encodes this decision in a bounded canonical binary form.
     *
     * @return encoded decision
     */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            uuid(out, grantId);
            uuid(out, workGroupId);
            uuid(out, targetIntentId);
            text(out, targetParticipant);
            out.writeLong(claimEpoch);
            uuid(out, taskId);
            text(out, snapshotId);
            text(out, result);
            text(out, reason);
            text(out, sourceParticipant);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }
}
