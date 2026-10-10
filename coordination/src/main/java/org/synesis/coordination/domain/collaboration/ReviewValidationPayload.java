package org.synesis.coordination.domain.collaboration;

import static org.synesis.coordination.domain.identity.WireFormat.readUuid;
import static org.synesis.coordination.domain.identity.WireFormat.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
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
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
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
    private static final WireFormat TEXT = WireFormat.bounded(MAX_TEXT,
            true,
            "invalid review text length",
            "truncated review validation").checkingWrites(MAX_TEXT, "text exceeds payload bound");

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
            ReviewValidationPayload payload = new ReviewValidationPayload(readUuid(in),
                    readUuid(in),
                    readUuid(in),
                    TEXT.readText(in),
                    in.readLong(),
                    readUuid(in),
                    TEXT.readText(in),
                    TEXT.readText(in),
                    TEXT.readText(in),
                    TEXT.readText(in));
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
     * Returns the consumed grant identity.
     *
     * @return the consumed grant identity.
     */
    LaneGrantId grantIdentity() {
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
    ParticipantId targetParticipantIdentity() {
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
        return WireFormat.encode(MAGIC, out -> {
            writeUuid(out, grantId);
            writeUuid(out, workGroupId);
            writeUuid(out, targetIntentId);
            TEXT.writeText(out, targetParticipant);
            out.writeLong(claimEpoch);
            writeUuid(out, taskId);
            TEXT.writeText(out, snapshotId);
            TEXT.writeText(out, result);
            TEXT.writeText(out, reason);
            TEXT.writeText(out, sourceParticipant);
        });
    }
}
