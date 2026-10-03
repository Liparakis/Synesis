package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.LaneGrantId;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.identity.WorkGroupVersion;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
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
        this.store = CoordinationNullException.requireNonNull(store, "store");
        this.signer = CoordinationNullException.requireNonNull(signer, "signer");
    }

    /**
     * Verifies the exact snapshot, owner lane, epoch, and review state for this grant.
     *
     * @param current fresh authoritative project projection
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void validateSnapshotBoundGrant(PredictionEventStore current, LaneGrant grant) throws IOException {
        SnapshotId snapshotId = grant.snapshot()
                .orElseThrow(() -> new CoordinationException("REVIEW_SNAPSHOT_NOT_FOUND"));
        TaskSnapshotRecord snapshot = current.taskCompletionProjection().findSnapshotById(snapshotId)
                .orElseThrow(() -> new CoordinationException("REVIEW_SNAPSHOT_NOT_FOUND"));
        if (!snapshot.reviewRequired() || !snapshot.provenance().workGroupIdentity().equals(grant.group())
                || !snapshot.provenance().laneIdentity().equals(grant.targetIntent()) || !snapshot.provenance()
                .claimEpochIdentity().equals(grant.epoch()) || snapshot.provenance().participantIdentity()
                .equals(grant.targetParticipantIdentity()) ||
                current.taskCompletionProjection().snapshotState(snapshot.snapshotIdentity())
                        .orElse(TaskCompletionState.ACTIVE) != TaskCompletionState.REVIEW_PENDING
                || current.workGroupProjection().reviewValidationForSnapshot(snapshot.snapshotIdentity()).isPresent()) {
            throw new CoordinationException("REVIEW_SNAPSHOT_MISMATCH");
        }
    }

    /**
     * Creates one logical group.
     *
     * @param group group
     * @throws IOException              persistence failure
     * @throws GeneralSecurityException signing failure
     */
    public void create(WorkGroup group) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(group, "group");
        if (!store.projectIdentity().equals(group.project())) {
            throw new CoordinationArgumentException("group project mismatch");
        }
        withLock(current -> current.append(group.workGroupId(),
                PredictionEventType.WORK_GROUP_CREATED,
                signer.nodeId(),
                CollaborationCodec.encodeWorkGroup(group),
                signer));
    }

    /**
     * Issues a targeted, optionally single-use lane continuation grant.
     *
     * @param grant grant
     * @throws IOException              persistence failure
     * @throws GeneralSecurityException signing failure
     */
    public void issue(LaneGrant grant) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(grant, "grant");
        withLock(current -> current.append(grant.grantId(),
                PredictionEventType.LANE_GRANT_ISSUED,
                signer.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant),
                signer));
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
        consume(new LaneGrantId(grantId),
                new ParticipantId(participant),
                new WorkIntentId(intentId),
                new ClaimEpoch(expectedEpoch));
    }

    /**
     * Consumes the grant only for its exact participant, intent, and claim epoch.
     *
     * @param grantIdentity       typed lane-grant identity
     * @param participantIdentity typed participant identity consuming the grant
     * @param intentIdentity      typed work-intent identity bound to the grant
     * @param expectedEpoch       expected claim epoch for stale-action fencing
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void consume(LaneGrantId grantIdentity,
            ParticipantId participantIdentity,
            WorkIntentId intentIdentity,
            ClaimEpoch expectedEpoch) throws IOException, GeneralSecurityException {
        LaneGrantId grantKey = CoordinationNullException.requireNonNull(grantIdentity, "grant ID");
        ParticipantId participant = CoordinationNullException.requireNonNull(participantIdentity, "participant");
        WorkIntentId intent = CoordinationNullException.requireNonNull(intentIdentity, "intent ID");
        ClaimEpoch epoch = CoordinationNullException.requireNonNull(expectedEpoch, "claim epoch");
        UUID grantId = grantKey.value();
        withLock(current -> {
            if (current.collaborationProjection().isParticipantTerminal(participant)) {
                throw new CoordinationException("SESSION_TERMINAL");
            }
            LaneGrant grant = current.workGroupProjection().grants().stream()
                    .filter(candidate -> candidate.identity().equals(grantKey)).findFirst()
                    .orElseThrow(() -> new CoordinationException("LANE_GRANT_NOT_FOUND"));
            if (!current.workGroupProjection().grantAvailable(grantKey)) {
                throw new CoordinationException("LANE_GRANT_REPLAYED");
            }
            if (grant.snapshotId() == null) {
                if (current.collaborationProjection().successorIntent(grant.targetIntent()).isPresent()) {
                    throw new CoordinationException("LANE_GRANT_TARGET_STALE");
                }
            } else {
                validateSnapshotBoundGrant(current, grant);
            }
            if (!grant.targetParticipantIdentity().equals(participant) || !grant.targetIntent().equals(intent)) {
                throw new CoordinationException("LANE_GRANT_TARGET_MISMATCH");
            }
            if (!grant.epoch().equals(epoch)) {
                throw new CoordinationException("CLAIM_EPOCH_STALE");
            }
            current.append(grantId,
                    PredictionEventType.LANE_GRANT_CONSUMED,
                    signer.nodeId(),
                    CollaborationCodec.encodeLaneGrant(grant),
                    signer);
        });
    }

    /**
     * Revokes a grant owner-independently.
     *
     * @param grantId grant ID
     * @throws IOException              invalid grant
     * @throws GeneralSecurityException signing failure
     */
    public void revoke(UUID grantId) throws IOException, GeneralSecurityException {
        revoke(new LaneGrantId(grantId));
    }

    /**
     * Revokes the exact lane grant identified by its domain type.
     *
     * @param grantIdentity typed lane-grant identity
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void revoke(LaneGrantId grantIdentity) throws IOException, GeneralSecurityException {
        LaneGrantId grantKey = CoordinationNullException.requireNonNull(grantIdentity, "grant ID");
        UUID grantId = grantKey.value();
        withLock(current -> {
            LaneGrant grant = current.workGroupProjection().grants().stream()
                    .filter(candidate -> candidate.identity().equals(grantKey)).findFirst()
                    .orElseThrow(() -> new CoordinationException("LANE_GRANT_NOT_FOUND"));
            current.append(grantId,
                    PredictionEventType.LANE_REVOKED,
                    signer.nodeId(),
                    CollaborationCodec.encodeLaneGrant(grant),
                    signer);
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
        close(new WorkGroupId(groupId), status, new WorkGroupVersion(expectedVersion));
    }

    /**
     * Closes the group only at the supplied typed group version.
     *
     * @param groupIdentity   typed work-group identity to close
     * @param status          projected status
     * @param expectedVersion value used by this operation
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void close(WorkGroupId groupIdentity, WorkGroup.Status status, WorkGroupVersion expectedVersion)
            throws IOException, GeneralSecurityException {
        WorkGroupId group = CoordinationNullException.requireNonNull(groupIdentity, "work group ID");
        WorkGroupVersion version = CoordinationNullException.requireNonNull(expectedVersion, "work group version");
        WorkGroup.Status finalStatus = CoordinationNullException.requireNonNull(status, "group status");
        UUID groupId = group.value();
        PredictionEventStore fresh = new PredictionEventStore(store.rootDirectory(), store.projectId());
        WorkGroup current = fresh.workGroupProjection().group(group)
                .orElseThrow(() -> new CoordinationException("WORK_GROUP_NOT_FOUND"));
        if (!current.groupVersion().equals(version)) {
            throw new CoordinationException("WORK_GROUP_VERSION_STALE");
        }
        if (finalStatus == WorkGroup.Status.ACTIVE) {
            throw new CoordinationException("GROUP_STATUS_INVALID");
        }
        WorkGroup update = new WorkGroup(groupId,
                current.projectId(),
                current.goal(),
                current.acceptance(),
                current.version() + 1,
                finalStatus,
                current.reviewPolicy());
        withLock(reloaded -> reloaded.append(groupId,
                PredictionEventType.WORK_GROUP_STATUS_CHANGED,
                signer.nodeId(),
                CollaborationCodec.encodeWorkGroup(update),
                signer));
    }

    /**
     * Runs the append action within the project-wide lock.
     *
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private void withLock(AppendAction action) throws IOException, GeneralSecurityException {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            action.append(new PredictionEventStore(store.rootDirectory(), store.projectId()));
        }
    }

    /**
     * Encapsulates one append operation executed under the project lock.
     */
    @FunctionalInterface
    private interface AppendAction {

        /**
         * Appends the type event to the durable coordination log.
         *
         * @param current fresh authoritative project projection
         * @throws IOException              when payload validation or durable I/O fails
         * @throws GeneralSecurityException when signing or signature verification fails
         */
        void append(PredictionEventStore current) throws IOException, GeneralSecurityException;
    }
}
