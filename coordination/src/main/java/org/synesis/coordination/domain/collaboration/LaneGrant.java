package org.synesis.coordination.domain.collaboration;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.LaneGrantId;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Authenticated, epoch-fenced grant allowing one participant to join a work group.
 *
 * @param grantId           grant ID
 * @param workGroupId       group ID
 * @param targetIntentId    target lane intent
 * @param targetParticipant target participant
 * @param claimEpoch        claim epoch
 * @param singleUse         whether the grant may be consumed only once
 * @param snapshotId        exact review candidate, when this is a review grant
 */
public record LaneGrant(UUID grantId, UUID workGroupId, UUID targetIntentId, String targetParticipant, long claimEpoch,
                        boolean singleUse, String snapshotId) {

    /**
     * Creates a grant that is not bound to a particular review snapshot.
     *
     * @param grantId           grant ID
     * @param workGroupId       group ID
     * @param targetIntentId    target lane intent
     * @param targetParticipant target participant
     * @param claimEpoch        claim epoch
     * @param singleUse         whether the grant may be consumed only once
     */
    public LaneGrant(UUID grantId,
            UUID workGroupId,
            UUID targetIntentId,
            String targetParticipant,
            long claimEpoch,
            boolean singleUse) {
        this(grantId, workGroupId, targetIntentId, targetParticipant, claimEpoch, singleUse, null);
    }

    /**
     * Validates grant identity and epoch bounds.
     */
    public LaneGrant {
        CoordinationNullException.requireNonNull(grantId, "grantId");
        CoordinationNullException.requireNonNull(workGroupId, "workGroupId");
        CoordinationNullException.requireNonNull(targetIntentId, "targetIntentId");
        CoordinationNullException.requireNonNull(targetParticipant, "targetParticipant");

        if (targetParticipant.isBlank() || targetParticipant.length() > 256) {
            throw new CoordinationArgumentException("target participant is invalid");
        }

        if (claimEpoch < 1) {
            throw new CoordinationArgumentException("claim epoch must be positive");
        }

        if (snapshotId != null && (snapshotId.isBlank() || snapshotId.length() > 256)) {
            throw new CoordinationArgumentException("invalid review snapshot ID");
        }
    }

    /**
     * Derives the identifier of the grant issued for an accepted review request.
     *
     * <p>The derivation is persisted: a retry after a lost append must recompute the same identifier.</p>
     *
     * @param requestId review request ID
     * @return deterministic grant ID for that request
     */
    public static UUID reviewGrantId(UUID requestId) {
        return UUID.nameUUIDFromBytes(("synesis-review-grant:" + requestId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the typed grant identity.
     *
     * @return the typed grant identity.
     */
    public LaneGrantId identity() {
        return new LaneGrantId(grantId);
    }

    /**
     * Returns the typed work-group identity.
     *
     * @return the typed work-group identity.
     */
    public WorkGroupId group() {
        return new WorkGroupId(workGroupId);
    }

    /**
     * Returns the typed target intent identity.
     *
     * @return the typed target intent identity.
     */
    public WorkIntentId targetIntent() {
        return new WorkIntentId(targetIntentId);
    }

    /**
     * Returns the opaque target participant identity.
     *
     * @return the opaque target participant identity.
     */
    public ParticipantId targetParticipantIdentity() {
        return new ParticipantId(targetParticipant);
    }

    /**
     * Returns the typed claim epoch.
     *
     * @return the typed claim epoch.
     */
    public ClaimEpoch epoch() {
        return new ClaimEpoch(claimEpoch);
    }

    /**
     * Returns the optional typed review snapshot identifier.
     *
     * @return the optional typed review snapshot identifier.
     */
    public Optional<SnapshotId> snapshot() {
        return Optional.ofNullable(snapshotId).map(SnapshotId::new);
    }
}
