package org.synesis.coordination.domain.collaboration;

import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityActorScope;
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
 * @param sourceActorScope verified capability actor scope for the predecessor, when available
 * @param targetActorScope verified capability actor scope for the successor, when available
 */
public record LaneContinuation(LaneGrantId grant, WorkIntentId sourceIntent, WorkIntent targetIntent,
                               ParticipantId sourceOwner, ParticipantId targetOwner, ClaimEpoch claimEpoch,
                               SnapshotReference recoverySnapshot, CapabilityActorScope sourceActorScope,
                               CapabilityActorScope targetActorScope) {

    /**
     * Creates a continuation without capability actor transfer (legacy/test payloads).
     *
     * @param grant            consumed grant authorizing the transfer
     * @param sourceIntent     predecessor lane
     * @param targetIntent     authorized successor lane
     * @param sourceOwner      participant currently holding the lane
     * @param targetOwner      participant receiving the successor
     * @param claimEpoch       expected source claim epoch
     * @param recoverySnapshot immutable snapshot used for recovery
     */
    public LaneContinuation(LaneGrantId grant,
            WorkIntentId sourceIntent,
            WorkIntent targetIntent,
            ParticipantId sourceOwner,
            ParticipantId targetOwner,
            ClaimEpoch claimEpoch,
            SnapshotReference recoverySnapshot) {
        this(grant, sourceIntent, targetIntent, sourceOwner, targetOwner, claimEpoch, recoverySnapshot, null, null);
    }

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
                new SnapshotReference(snapshotReference),
                null,
                null);
    }

    /**
     * Creates a continuation carrying verified source and successor capability actor scopes.
     *
     * @param grantId           consumed grant authorizing the transfer
     * @param sourceIntentId    predecessor lane
     * @param targetIntent      authorized successor lane
     * @param sourceParticipant current lane owner
     * @param targetParticipant participant receiving the successor
     * @param expectedEpoch     expected source claim epoch
     * @param snapshotReference immutable recovery snapshot reference
     * @param sourceActorScope  verified capability actor scope of the predecessor, or {@code null}
     * @param targetActorScope  verified capability actor scope of the successor, or {@code null}
     */
    public LaneContinuation(UUID grantId,
            UUID sourceIntentId,
            WorkIntent targetIntent,
            String sourceParticipant,
            String targetParticipant,
            long expectedEpoch,
            String snapshotReference,
            CapabilityActorScope sourceActorScope,
            CapabilityActorScope targetActorScope) {
        this(new LaneGrantId(grantId),
                new WorkIntentId(sourceIntentId),
                targetIntent,
                new ParticipantId(sourceParticipant),
                new ParticipantId(targetParticipant),
                new ClaimEpoch(expectedEpoch),
                new SnapshotReference(snapshotReference),
                sourceActorScope,
                targetActorScope);
    }

    /**
     * Requires the capability actor scopes as a pair: a transfer with only one side would move a
     * capability request away from its predecessor without naming who receives it.
     */
    public LaneContinuation {
        if ((sourceActorScope == null) != (targetActorScope == null)) {
            throw new IllegalArgumentException("continuation actor scopes must be supplied as a pair");
        }
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

    /** Prefix of a takeover's reference to the lane's last snapshot that reached the taker's computer. */
    public static final String TAKEOVER_FROM_SNAPSHOT = "snapshot:";
    /** Prefix of a takeover's reference to the lane's base, when none of its snapshots reached the taker's computer. */
    public static final String TAKEOVER_FROM_BASE = "base:";

    /**
     * Whether this continues the lane of a computer that is away rather than a lane held for recovery here (ADR-0098
     * step 7, designer item 11): its reference names the lane's snapshot or base instead of a recovery snapshot, and no
     * session of the source's computer is here to give an actor scope.
     *
     * @return whether it is a takeover
     */
    public boolean takeover() {
        String reference = recoverySnapshot.value();
        return reference.startsWith(TAKEOVER_FROM_SNAPSHOT) || reference.startsWith(TAKEOVER_FROM_BASE);
    }
}
