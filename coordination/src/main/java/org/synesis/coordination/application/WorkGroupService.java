package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.link.identity.NodeIdentity;

/**
 * Shared application service for durable work-group and lane-grant lifecycle.
 */
public final class WorkGroupService {

  private final PredictionEventStore store;
  private final NodeIdentity signer;

  /**
   * Creates a service for one signed project log.
   *
   * @param store  event store
   * @param signer signer
   */
  public WorkGroupService(PredictionEventStore store, NodeIdentity signer) {
    this.store = Objects.requireNonNull(store, "store");
    this.signer = Objects.requireNonNull(signer, "signer");
  }

  /**
   * Creates one logical group.
   *
   * @param group group
   * @throws IOException              persistence failure
   * @throws GeneralSecurityException signing failure
   */
  public void create(WorkGroup group) throws IOException, GeneralSecurityException {
    Objects.requireNonNull(group, "group");
    if (!store.projectId()
        .equals(group.projectId())) {
      throw new IllegalArgumentException("group project mismatch");
    }
    withLock(current -> current.append(group.workGroupId(), PredictionEventType.WORK_GROUP_CREATED,
        signer.nodeId(), CollaborationCodec.encodeWorkGroup(group), signer));
  }

  /**
   * Issues a targeted, optionally single-use lane continuation grant.
   *
   * @param grant grant
   * @throws IOException              persistence failure
   * @throws GeneralSecurityException signing failure
   */
  public void issue(LaneGrant grant) throws IOException, GeneralSecurityException {
    Objects.requireNonNull(grant, "grant");
    withLock(current -> current.append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED,
        signer.nodeId(), CollaborationCodec.encodeLaneGrant(grant), signer));
  }

  /**
   * Consumes a grant for the exact target participant and expected epoch.
   *
   * @param grantId       grant ID
   * @param participant   target participant
   * @param intentId      target intent
   * @param expectedEpoch expected claim epoch
   * @throws IOException              invalid grant
   * @throws GeneralSecurityException signing failure
   */
  public void consume(UUID grantId, String participant, UUID intentId, long expectedEpoch)
      throws IOException, GeneralSecurityException {
    Objects.requireNonNull(grantId, "grantId");
    withLock(current -> {
      if (current.collaborationProjection()
          .isParticipantTerminal(participant)) {
        throw new IOException("SESSION_TERMINAL");
      }
      LaneGrant grant = current.workGroupProjection()
          .grants()
          .stream()
          .filter(candidate -> candidate.grantId()
              .equals(grantId))
          .findFirst()
          .orElseThrow(() -> new IOException("LANE_GRANT_NOT_FOUND"));
      if (!current.workGroupProjection()
          .grantAvailable(grantId)) {
        throw new IOException("LANE_GRANT_REPLAYED");
      }
      if (grant.snapshotId() == null) {
        if (current.collaborationProjection()
            .successorIntent(grant.targetIntentId())
            .isPresent()) {
          throw new IOException("LANE_GRANT_TARGET_STALE");
        }
      } else {
        validateSnapshotBoundGrant(current, grant);
      }
      if (!grant.targetParticipant()
          .equals(participant) || !grant.targetIntentId()
          .equals(intentId)) {
        throw new IOException("LANE_GRANT_TARGET_MISMATCH");
      }
      if (grant.claimEpoch() != expectedEpoch) {
        throw new IOException("CLAIM_EPOCH_STALE");
      }
      current.append(grantId, PredictionEventType.LANE_GRANT_CONSUMED,
          signer.nodeId(), CollaborationCodec.encodeLaneGrant(grant), signer);
    });
  }

  private static void validateSnapshotBoundGrant(PredictionEventStore current, LaneGrant grant)
      throws IOException {
    TaskSnapshotRecord snapshot = current.taskCompletionProjection()
        .findSnapshotById(grant.snapshotId())
        .orElseThrow(() -> new IOException("REVIEW_SNAPSHOT_NOT_FOUND"));
    if (!snapshot.reviewRequired()
        || !snapshot.provenance().workGroupId().equals(grant.workGroupId())
        || !snapshot.provenance().laneId().equals(grant.targetIntentId())
        || snapshot.provenance().claimEpoch() != grant.claimEpoch()
        || snapshot.provenance().participant().equals(grant.targetParticipant())
        || current.taskCompletionProjection()
        .snapshotState(snapshot.snapshotId())
        .orElse(TaskCompletionState.ACTIVE) != TaskCompletionState.REVIEW_PENDING
        || current.workGroupProjection()
        .reviewValidationForSnapshot(snapshot.snapshotId())
        .isPresent()) {
      throw new IOException("REVIEW_SNAPSHOT_MISMATCH");
    }
  }

  /**
   * Revokes a grant owner-independently.
   *
   * @param grantId grant ID
   * @throws IOException              invalid grant
   * @throws GeneralSecurityException signing failure
   */
  public void revoke(UUID grantId) throws IOException, GeneralSecurityException {
    Objects.requireNonNull(grantId, "grantId");
    withLock(current -> {
      LaneGrant grant = current.workGroupProjection()
          .grants()
          .stream()
          .filter(candidate -> candidate.grantId()
              .equals(grantId))
          .findFirst()
          .orElseThrow(() -> new IOException("LANE_GRANT_NOT_FOUND"));
      current.append(grantId, PredictionEventType.LANE_REVOKED,
          signer.nodeId(), CollaborationCodec.encodeLaneGrant(grant), signer);
    });
  }

  /**
   * Closes or cancels the logical group without releasing sibling lane claims.
   *
   * @param groupId         group ID
   * @param status          terminal status
   * @param expectedVersion current version
   * @throws IOException              invalid transition
   * @throws GeneralSecurityException signing failure
   */
  public void close(UUID groupId, WorkGroup.Status status, long expectedVersion)
      throws IOException, GeneralSecurityException {
    PredictionEventStore fresh = new PredictionEventStore(store.rootDirectory(), store.projectId());
    WorkGroup current = fresh.workGroupProjection()
        .group(groupId)
        .orElseThrow(() -> new IOException("WORK_GROUP_NOT_FOUND"));
    if (current.version() != expectedVersion) {
      throw new IOException("WORK_GROUP_VERSION_STALE");
    }
    if (status == WorkGroup.Status.ACTIVE) {
      throw new IOException("GROUP_STATUS_INVALID");
    }
    WorkGroup update = new WorkGroup(groupId, current.projectId(), current.goal(),
        current.acceptance(),
        current.version() + 1, status, current.reviewPolicy());
    withLock(reloaded -> reloaded.append(groupId, PredictionEventType.WORK_GROUP_STATUS_CHANGED,
        signer.nodeId(), CollaborationCodec.encodeWorkGroup(update), signer));
  }

  private void withLock(AppendAction action) throws IOException, GeneralSecurityException {
    try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
      if (!lock.isHeld()) {
        throw new IOException("event append lock unavailable");
      }
      action.append(new PredictionEventStore(store.rootDirectory(), store.projectId()));
    }
  }

  /**
   * Encapsulates one append operation executed under the project lock.
   */
  @FunctionalInterface
  private interface AppendAction {

    void append(PredictionEventStore current) throws IOException, GeneralSecurityException;
  }
}
