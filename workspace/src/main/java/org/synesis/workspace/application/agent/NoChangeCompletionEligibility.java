package org.synesis.workspace.application.agent;

import java.nio.file.Path;
import java.util.Objects;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.application.task.TaskSnapshotService;

/** Shared read-only gate used by no-change completion execution and projection. */
final class NoChangeCompletionEligibility {

    private NoChangeCompletionEligibility() {
    }

    /** Evaluates all local lifecycle predicates for one active intent.
     * @param store current project projection
     * @param intent exact active intent
     * @param participant exact caller participant
     * @param nodeId caller node identity
     * @param supervisorId caller supervisor identity
     * @param workerId caller worker identity
     * @param worktree exact assigned worktree
     * @param snapshotService snapshot inspection service
     * @return eligibility result
     */
    static Result assess(PredictionEventStore store, WorkIntent intent, String participant,
            String nodeId, String supervisorId, String workerId, Path worktree,
            TaskSnapshotService snapshotService) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(supervisorId, "supervisorId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(worktree, "worktree");
        Objects.requireNonNull(snapshotService, "snapshotService");
        if (intent == null) {
            return denied("NO_ACTIVE_INTENT");
        }
        if (!intent.participant().equals(participant)) {
            return denied("NO_CHANGE_PARTICIPANT_MISMATCH");
        }
        if (intent.completionMode() != WorkIntent.CompletionMode.NO_CHANGE_ALLOWED) {
            return denied("NO_CHANGE_NOT_AUTHORIZED");
        }
        if (AgentNextActionService.hasUnresolvedReviewObligation(store, intent)) {
            return denied("NO_CHANGE_REVIEWER_PENDING");
        }
        WorkGroup group = store.workGroupProjection().group(intent.workGroupId()).orElse(null);
        if (group == null) {
            return denied("WORK_GROUP_NOT_FOUND");
        }
        if (group.status() != WorkGroup.Status.ACTIVE) {
            return denied("WORK_GROUP_NOT_ACTIVE");
        }
        try {
            if (!snapshotService.isCleanWorktree(worktree)) {
                return denied("NO_CHANGE_DIRTY_WORKSPACE");
            }
        } catch (Exception failure) {
            return denied("NO_CHANGE_WORKSPACE_UNVERIFIED");
        }
        if (store.taskCompletionProjection().allSnapshots().stream()
                .anyMatch(snapshot -> snapshot.provenance().laneId().equals(intent.intentId())
                        && snapshot.provenance().claimEpoch() == intent.version())) {
            return denied("NO_CHANGE_SNAPSHOT_OBLIGATION");
        }
        if (store.taskCompletionProjection().allPrepared().stream()
                .anyMatch(prepared -> prepared.laneId().equals(intent.intentId())
                        && prepared.claimEpoch() == intent.version())) {
            return denied("NO_CHANGE_SNAPSHOT_OBLIGATION");
        }
        for (LaneGrant grant : store.workGroupProjection().grants()) {
            if (!grant.workGroupId().equals(intent.workGroupId())) {
                continue;
            }
            boolean targetsThisIntent = grant.targetIntentId().equals(intent.intentId())
                    && grant.claimEpoch() == intent.version();
            boolean targetsCaller = grant.targetParticipant().equals(participant);
            if ((targetsThisIntent || targetsCaller)
                    && (store.workGroupProjection().grantAvailable(grant.grantId())
                    || (store.workGroupProjection().grantConsumed(grant.grantId())
                    && store.workGroupProjection().reviewValidationForGrant(grant.grantId()).isEmpty()))) {
                return denied("NO_CHANGE_REVIEW_OBLIGATION");
            }
        }
        for (CoordinationRequest request : store.collaborationProjection().requests()) {
            if (request.status() != CoordinationRequest.Status.PENDING) {
                continue;
            }
            if (request.conflictingIntentId().equals(intent.intentId())
                    || request.requester().equals(participant)
                    || request.target().equals(participant)) {
                return denied("NO_CHANGE_COORDINATION_OBLIGATION");
            }
        }
        if (hasPendingCapability(store, nodeId, supervisorId, workerId)
                || !store.capabilityRequestProjection().allValidationContexts().isEmpty()) {
            return denied("NO_CHANGE_DEPENDENCY_PENDING");
        }
        return new Result(true, null);
    }

    private static boolean hasPendingCapability(PredictionEventStore store, String nodeId,
            String supervisorId, String workerId) {
        return store.capabilityRequestProjection().findAllForRequester(nodeId).stream()
                .filter(request -> request.matchesRequester(nodeId, supervisorId, workerId))
                .map(CapabilityRequestRecord::state)
                .anyMatch(state -> state == CapabilityLifecycleState.AWAITING_OWNER
                        || state == CapabilityLifecycleState.REVISION_REQUESTED
                        || state == CapabilityLifecycleState.IMPLEMENTING
                        || state == CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE
                        || state == CapabilityLifecycleState.VALIDATING);
    }

    private static Result denied(String reason) {
        return new Result(false, reason);
    }

    /** Result of the no-change lifecycle gate.
     * @param eligible whether all read-only predicates passed
     * @param reason stable denial reason, or {@code null} when eligible
     */
    record Result(boolean eligible, String reason) {
    }
}
