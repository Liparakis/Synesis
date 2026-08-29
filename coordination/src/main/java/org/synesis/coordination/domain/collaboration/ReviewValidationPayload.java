package org.synesis.coordination.domain.collaboration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

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
public record ReviewValidationPayload(UUID grantId, UUID workGroupId, UUID targetIntentId,
                                      String targetParticipant, long claimEpoch, UUID taskId, String snapshotId,
                                      String result, String reason, String sourceParticipant) {

    private static final int MAGIC = 0x53525631;
    private static final int MAX_TEXT = 64 * 1024;

    /**
     * Validates the decision's identity, result, and bounded text.
     */
    public ReviewValidationPayload {
        Objects.requireNonNull(grantId, "grantId");
        Objects.requireNonNull(workGroupId, "workGroupId");
        Objects.requireNonNull(targetIntentId, "targetIntentId");
        requireText(targetParticipant, "targetParticipant");
        if (claimEpoch < 1) {
            throw new IllegalArgumentException("claim epoch must be positive");
        }
        Objects.requireNonNull(taskId, "taskId");
        requireText(snapshotId, "snapshotId");
        result = Objects.requireNonNull(result, "result")
                .trim()
                .toUpperCase(java.util.Locale.ROOT);
        if (!result.equals("ACCEPTED") && !result.equals("REJECTED")) {
            throw new IllegalArgumentException("review result must be ACCEPTED or REJECTED");
        }
        reason = reason == null ? "" : reason;
        if (result.equals("REJECTED") && reason.isBlank()) {
            throw new IllegalArgumentException("rejection reason is required");
        }
        if (reason.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT) {
            throw new IllegalArgumentException("reason exceeds payload bound");
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
                throw new IOException("unsupported review validation format");
            }
            ReviewValidationPayload payload = new ReviewValidationPayload(
                    uuid(in), uuid(in), uuid(in), readText(in), in.readLong(), uuid(in),
                    readText(in), readText(in), readText(in), readText(in));
            if (in.available() != 0) {
                throw new IOException("trailing review validation bytes");
            }
            return payload;
        } catch (RuntimeException | java.io.EOFException failure) {
            throw new IOException("malformed review validation", failure);
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT) {
            throw new IllegalArgumentException(field + " is empty or exceeds payload bound");
        }
    }

    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT) {
            throw new IOException("text exceeds payload bound");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_TEXT) {
            throw new IOException("invalid review text length");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("truncated review validation");
        }
        return new String(bytes, StandardCharsets.UTF_8);
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
            throw new AssertionError(impossible);
        }
    }
}
