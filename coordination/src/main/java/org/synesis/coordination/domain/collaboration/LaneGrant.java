package org.synesis.coordination.domain.collaboration;

import java.util.Objects;
import java.util.UUID;

/** Authenticated, epoch-fenced grant allowing one participant to join a work group. */
public record LaneGrant(UUID grantId, UUID workGroupId, UUID targetIntentId,
                        String targetParticipant, long claimEpoch, boolean singleUse) {
    /** Validates grant identity and epoch bounds. */
    public LaneGrant {
        Objects.requireNonNull(grantId, "grantId");
        Objects.requireNonNull(workGroupId, "workGroupId");
        Objects.requireNonNull(targetIntentId, "targetIntentId");
        Objects.requireNonNull(targetParticipant, "targetParticipant");
        if (targetParticipant.isBlank() || targetParticipant.length() > 256) {
            throw new IllegalArgumentException("target participant is invalid");
        }
        if (claimEpoch < 1) throw new IllegalArgumentException("claim epoch must be positive");
    }
}
