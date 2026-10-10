package org.synesis.coordination.domain.collaboration;

import static org.synesis.coordination.domain.identity.WireFormat.readUuid;
import static org.synesis.coordination.domain.identity.WireFormat.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.CommitId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Signed payload for atomic source-scope transfer to a repair lane.
 *
 * <p>The fields bind the transfer to the immutable conflicting snapshot,
 * the control head from which the repair worktree was materialized, and both claim epochs.</p>
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

    private static final int V2_MAGIC = 0x52505232;
    private static final int MAX_TEXT = 64 * 1024;
    private static final WireFormat TEXT = WireFormat.bounded(MAX_TEXT, true, "repair text bound", "truncated repair text")
            .checkingWrites(MAX_TEXT, "repair text bound");
    private static final WireFormat INTENT = WireFormat.bounded(1024 * 1024,
            false,
            "repair intent bound",
            "truncated repair payload");

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
            if (magic != V2_MAGIC) {
                throw new CoordinationException("unsupported repair payload");
            }
            UUID source = readUuid(in);
            WorkIntent target = CollaborationCodec.decodeIntent(INTENT.readBytes(in));
            String snapshot = TEXT.readText(in);
            String head = TEXT.readText(in);
            long sourceEpoch = in.readLong();
            long targetEpoch = in.readLong();
            return new RepairLanePayload(source, target, snapshot, head, sourceEpoch, targetEpoch);
        } catch (RuntimeException failure) {
            throw new CoordinationException("malformed repair payload", failure);
        }
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
        byte[] intent = CollaborationCodec.encodeIntent(targetIntent);
        return WireFormat.encode(V2_MAGIC, out -> {
            writeUuid(out, sourceIntentId);
            INTENT.writeBytes(out, intent);
            TEXT.writeText(out, snapshotId);
            TEXT.writeText(out, expectedControlHead);
            out.writeLong(sourceClaimEpoch);
            out.writeLong(targetClaimEpoch);
        });
    }
}
