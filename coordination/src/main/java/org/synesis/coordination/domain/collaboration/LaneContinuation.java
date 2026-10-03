package org.synesis.coordination.domain.collaboration;

import java.util.UUID;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.LaneGrantId;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.SnapshotReference;
import org.synesis.coordination.domain.identity.WorkIntentId;

/**
 * One append-locked transfer from a recovery-held lane to its successor.
 *
 * @param grant            consumed grant authorizing the transfer
 * @param sourceIntent     predecessor lane
 * @param targetIntent     authorized successor lane
 * @param sourceOwner      participant currently holding the lane
 * @param targetOwner      participant receiving the successor
 * @param claimEpoch       expected source claim epoch
 * @param recoverySnapshot immutable snapshot used for recovery
 */
public record LaneContinuation(LaneGrantId grant, WorkIntentId sourceIntent, WorkIntent targetIntent,
                               ParticipantId sourceOwner, ParticipantId targetOwner, ClaimEpoch claimEpoch,
                               SnapshotReference recoverySnapshot) {

    /**
     * Creates a typed continuation from the existing serialized values.
     *
     * @param grantId           consumed grant authorizing the transfer
     * @param sourceIntentId    predecessor lane
     * @param targetIntent      authorized successor lane
     * @param sourceParticipant current lane owner
     * @param targetParticipant participant receiving the successor
     * @param expectedEpoch     expected source claim epoch
     * @param snapshotReference immutable recovery snapshot reference
     */
    public LaneContinuation(UUID grantId,
            UUID sourceIntentId,
            WorkIntent targetIntent,
            String sourceParticipant,
            String targetParticipant,
            long expectedEpoch,
            String snapshotReference) {
        this(new LaneGrantId(grantId),
                new WorkIntentId(sourceIntentId),
                targetIntent,
                new ParticipantId(sourceParticipant),
                new ParticipantId(targetParticipant),
                new ClaimEpoch(expectedEpoch),
                new SnapshotReference(snapshotReference));
    }

    /**
     * Compatibility accessor used by the current event codec.
     *
     * @return Compatibility accessor used by the current event codec.
     */
    public UUID grantId() {
        return grant.value();
    }

    /**
     * Compatibility accessor used by the current event codec.
     *
     * @return Compatibility accessor used by the current event codec.
     */
    public String targetParticipant() {
        return targetOwner.value();
    }

    /**
     * Compatibility accessor used by the current event codec.
     *
     * @return Compatibility accessor used by the current event codec.
     */
    public long expectedEpoch() {
        return claimEpoch.value();
    }

    /**
     * Compatibility accessor used by the current event codec.
     *
     * @return Compatibility accessor used by the current event codec.
     */
    public String snapshotReference() {
        return recoverySnapshot.value();
    }
}
