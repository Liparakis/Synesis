package org.synesis.coordination.domain.collaboration;

import java.util.Objects;
import java.util.UUID;

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
public record LaneGrant(UUID grantId, UUID workGroupId, UUID targetIntentId,
                        String targetParticipant, long claimEpoch, boolean singleUse,
                        String snapshotId) {

  /**
   * Creates a grant that is not bound to a particular review snapshot.
   *
   * @param grantId grant ID
   * @param workGroupId group ID
   * @param targetIntentId target lane intent
   * @param targetParticipant target participant
   * @param claimEpoch claim epoch
   * @param singleUse whether the grant may be consumed only once
   */
  public LaneGrant(UUID grantId, UUID workGroupId, UUID targetIntentId,
      String targetParticipant, long claimEpoch, boolean singleUse) {
    this(grantId, workGroupId, targetIntentId, targetParticipant, claimEpoch, singleUse, null);
  }

  /**
   * Validates grant identity and epoch bounds.
   */
  public LaneGrant {
    Objects.requireNonNull(grantId, "grantId");
    Objects.requireNonNull(workGroupId, "workGroupId");
    Objects.requireNonNull(targetIntentId, "targetIntentId");
    Objects.requireNonNull(targetParticipant, "targetParticipant");
    if (targetParticipant.isBlank() || targetParticipant.length() > 256) {
      throw new IllegalArgumentException("target participant is invalid");
    }
    if (claimEpoch < 1) {
      throw new IllegalArgumentException("claim epoch must be positive");
    }
    if (snapshotId != null && (snapshotId.isBlank() || snapshotId.length() > 256)) {
      throw new IllegalArgumentException("invalid review snapshot ID");
    }
  }
}
