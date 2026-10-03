package org.synesis.coordination.domain.collaboration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.CommitId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationInvariantError;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Signed payload for atomic source-scope transfer to a repair lane.
 *
 * <p>The V2 fields bind the transfer to the immutable conflicting snapshot,
 * the control head from which the repair worktree was materialized, and both claim epochs.  The decoder retains a
 * bounded V1 reader solely
 * so historical event logs remain replayable; new events are always encoded as V2.</p>
 *
 * @param sourceIntentId      published source intent
 * @param targetIntent        newly authorized repair intent
 * @param snapshotId          immutable conflicting snapshot identifier
 * @param expectedControlHead control HEAD used to materialize the repair lane
 * @param sourceClaimEpoch    source lane epoch being transferred
 * @param targetClaimEpoch    target lane epoch receiving the transfer
 */
public record RepairLanePayload(UUID sourceIntentId, WorkIntent targetIntent, String snapshotId,
                                String expectedControlHead, long sourceClaimEpoch, long targetClaimEpoch) {

    private static final int V1_MAGIC = 0x52505231;
    private static final int V2_MAGIC = 0x52505232;
    private static final int MAX_TEXT = 64 * 1024;

    /**
     * Validates repair transfer fields.
     */
    public RepairLanePayload {
        CoordinationNullException.requireNonNull(sourceIntentId, "source intent");
        CoordinationNullException.requireNonNull(targetIntent, "target intent");
        CoordinationNullException.requireNonNull(snapshotId, "snapshot ID");
        CoordinationNullException.requireNonNull(expectedControlHead, "expected control HEAD");
        if (sourceClaimEpoch < 0 || targetClaimEpoch < 0) {
            throw new CoordinationArgumentException("repair claim epochs cannot be negative");
        }
        if (!snapshotId.isBlank() && (sourceClaimEpoch < 1 || targetClaimEpoch < 1)) {
            throw new CoordinationArgumentException("versioned repair transfer requires claim epochs");
        }
    }

    /**
     * Creates a metadata-free payload for callers that only replay historical transfer events.  New repair operations
     * should use the full
     * constructor.
     *
     * @param sourceIntentId source intent
     * @param targetIntent   target intent
     */
    public RepairLanePayload(UUID sourceIntentId, WorkIntent targetIntent) {
        this(sourceIntentId, targetIntent, "", "", 0L, 0L);
    }

    /**
     * Decodes the transfer payload.
     *
     * @param encoded bytes
     * @return decoded payload
     * @throws IOException malformed payload
     */
    public static RepairLanePayload decode(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int magic = in.readInt();
            if (magic != V1_MAGIC && magic != V2_MAGIC) {
                throw new CoordinationException("unsupported repair payload");
            }
            UUID source = new UUID(in.readLong(), in.readLong());
            int length = in.readInt();
            if (length < 1 || length > 1024 * 1024) {
                throw new CoordinationException("repair intent bound");
            }
            byte[] intent = in.readNBytes(length);
            if (intent.length != length) {
                throw new CoordinationException("truncated repair payload");
            }
            WorkIntent target = CollaborationCodec.decodeIntent(intent);
            if (magic == V1_MAGIC) {
                return new RepairLanePayload(source, target);
            }
            String snapshot = readText(in);
            String head = readText(in);
            long sourceEpoch = in.readLong();
            long targetEpoch = in.readLong();
            return new RepairLanePayload(source, target, snapshot, head, sourceEpoch, targetEpoch);
        } catch (RuntimeException failure) {
            throw new CoordinationException("malformed repair payload", failure);
        }
    }

    /**
     * Writes bounded UTF-8 text in the canonical payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT) {
            throw new CoordinationException("repair text bound");
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
            throw new CoordinationException("repair text bound");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new CoordinationException("truncated repair text");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns the typed source lane identity.
     *
     * @return the typed source lane identity.
     */
    public WorkIntentId sourceIntentIdentity() {
        return new WorkIntentId(sourceIntentId);
    }

    /**
     * Returns the conflicting snapshot identity for a versioned repair payload.
     *
     * @return the conflicting snapshot identity for a versioned repair payload.
     */
    public Optional<SnapshotId> snapshotIdentity() {
        return snapshotId.isBlank() ? Optional.empty() : Optional.of(new SnapshotId(snapshotId));
    }

    /**
     * Returns the materialization control commit for a versioned repair payload.
     *
     * @return the materialization control commit for a versioned repair payload.
     */
    public Optional<CommitId> expectedControlHeadIdentity() {
        return expectedControlHead.isBlank() ? Optional.empty() : Optional.of(new CommitId(expectedControlHead));
    }

    /**
     * Returns the source epoch when this payload contains versioned transfer metadata.
     *
     * @return the source epoch when this payload contains versioned transfer metadata.
     */
    public Optional<ClaimEpoch> sourceClaimEpochIdentity() {
        return sourceClaimEpoch == 0L ? Optional.empty() : Optional.of(new ClaimEpoch(sourceClaimEpoch));
    }

    /**
     * Returns the target epoch when this payload contains versioned transfer metadata.
     *
     * @return the target epoch when this payload contains versioned transfer metadata.
     */
    public Optional<ClaimEpoch> targetClaimEpochIdentity() {
        return targetClaimEpoch == 0L ? Optional.empty() : Optional.of(new ClaimEpoch(targetClaimEpoch));
    }

    /**
     * Encodes the transfer payload.
     *
     * @return encoded bytes
     */
    public byte[] encode() {
        try {
            byte[] intent = CollaborationCodec.encodeIntent(targetIntent);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(V2_MAGIC);
            out.writeLong(sourceIntentId.getMostSignificantBits());
            out.writeLong(sourceIntentId.getLeastSignificantBits());
            out.writeInt(intent.length);
            out.write(intent);
            writeText(out, snapshotId);
            writeText(out, expectedControlHead);
            out.writeLong(sourceClaimEpoch);
            out.writeLong(targetClaimEpoch);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }
}
