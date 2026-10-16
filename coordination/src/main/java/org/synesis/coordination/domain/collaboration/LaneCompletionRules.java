package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.util.Comparator;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.exception.CoordinationException;

/**
 * Decides, from a fresh project projection, whether a lane or a work group may finish without a change.
 *
 * <p>Every rule is a stateless predicate over projected state, so it holds no lock and appends nothing. It is
 * separate from {@code LaneCompletionService} so the callers that hold the project append lock only sequence these
 * decisions, and a rule can be read without the event-append code around it. Callers must pass a projection read
 * inside the append lock; the rules never re-read durable state.</p>
 */
public final class LaneCompletionRules {

    private LaneCompletionRules() {
    }

    /**
     * Checks whether unresolved output, grants, or requests prevent lane release.
     *
     * @param current fresh authoritative project projection
     */
    private static boolean hasNoChangeObligation(ProjectionView current, WorkIntent intent) {
        if (hasPendingSnapshotOrPreparation(current, intent)) {
            return true;
        }
        if (hasUnresolvedGrant(current, intent)) {
            return true;
        }
        return hasPendingRequest(current, intent);
    }

    /**
     * Checks for unpublished or unintegrated output from the exact claim epoch.
     */
    private static boolean hasPendingSnapshotOrPreparation(ProjectionView current, WorkIntent intent) {
        boolean snapshotPending = current.taskCompletionProjection().allSnapshots().stream()
                .anyMatch(snapshot -> snapshot.provenance().laneIdentity().equals(intent.identity())
                        && snapshot.provenance().claimEpochIdentity().equals(intent.claimEpoch()));
        boolean preparedPending = current.taskCompletionProjection().allPrepared().stream()
                .anyMatch(prepared -> prepared.laneIdentity().equals(intent.identity()) && prepared.claimEpochIdentity()
                        .equals(intent.claimEpoch()));
        return snapshotPending || preparedPending;
    }

    /**
     * Checks whether a grant or review response still reserves this intent.
     */
    private static boolean hasUnresolvedGrant(ProjectionView current, WorkIntent intent) {
        return current.workGroupProjection().grants().stream().filter(grant -> grant.group().equals(intent.group()))
                .filter(grant -> current.collaborationProjection().successorIntent(grant.targetIntent()).isEmpty())
                .filter(grant ->
                        (grant.targetIntent().equals(intent.identity()) && grant.epoch().equals(intent.claimEpoch()))
                                || grant.targetParticipantIdentity().equals(intent.participantIdentity()))
                .anyMatch(grant -> current.workGroupProjection().grantUnresolved(grant.identity()));
    }

    /**
     * Checks whether a pending coordination request still refers to this intent or participant.
     */
    private static boolean hasPendingRequest(ProjectionView current, WorkIntent intent) {
        return current.collaborationProjection().requests().stream()
                .anyMatch(request -> request.status() == CoordinationRequest.Status.PENDING && (
                        request.conflictingIntent().equals(intent.identity()) || request.requesterIdentity()
                                .equals(intent.participantIdentity()) || request.targetIdentity()
                                .equals(intent.participantIdentity())));
    }

    /**
     * Selects the lowest-version active successor matching snapshot task and authority lineage.
     *
     * @param current  project projections
     * @param snapshot snapshot published by a lane that may since have been continued
     * @return the successor lane, or {@code null} when none is active
     */
    public static WorkIntent successorForSnapshot(ProjectionView current, TaskSnapshotRecord snapshot) {
        return current.collaborationProjection().activeIntents().stream()
                .filter(intent -> intent.task().equals(snapshot.taskIdentity()))
                .filter(intent -> intent.group().equals(snapshot.provenance().workGroupIdentity()))
                .filter(intent -> intent.lineage().equals(snapshot.provenance().lineageIdentity()))
                .filter(intent -> intent.intentVersion().value() > snapshot.provenance().claimEpochIdentity().value())
                .min(Comparator.comparingLong(WorkIntent::version)).orElse(null);
    }

    /**
     * Resolves the current intent and verifies the caller's exact completion binding.
     *
     * @param current    project projections
     * @param completion the no-change completion being recorded
     * @return the lane the completion finishes
     * @throws IOException when the lane is unknown, inactive, of another project, or not the one the completion names
     */
    public static WorkIntent validateNoChangeIntent(ProjectionView current, NoChangeCompletion completion)
            throws IOException {
        WorkIntent intent = current.collaborationProjection().intent(completion.intentIdentity())
                .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
        if (!current.projectIdentity().equals(intent.project())) {
            throw new CoordinationException("NO_CHANGE_PROJECT_MISMATCH");
        }
        if (intent.status() != WorkIntent.Status.ANNOUNCED) {
            throw new CoordinationException("INTENT_NOT_ACTIVE");
        }
        if (!intent.group().equals(completion.workGroupIdentity()) || !intent.participantIdentity()
                .equals(completion.participantIdentity()) || !intent.providerIdentity()
                .equals(completion.providerIdentity()) || !intent.lineage().equals(completion.lineageIdentity())
                || !intent.claimEpoch().equals(completion.claimEpochIdentity()) || !intent.baseCommitIdentity()
                .equals(completion.workspaceCommitIdentity())) {
            throw new CoordinationException("NO_CHANGE_COMPLETION_BINDING_MISMATCH");
        }
        return intent;
    }

    /**
     * Verifies that this no-change completion can close its active work group.
     *
     * @param current    project projections
     * @param intent     the lane being finished
     * @param completion the no-change completion being recorded
     * @throws IOException when the group is closed, has moved on, or the lane still owes a review or an answer
     */
    public static void validateNoChangeGroup(ProjectionView current,
            WorkIntent intent,
            NoChangeCompletion completion) throws IOException {
        WorkGroup group = current.workGroupProjection().group(intent.group())
                .orElseThrow(() -> new CoordinationException("WORK_GROUP_NOT_FOUND"));
        if (group.status() != WorkGroup.Status.ACTIVE) {
            throw new CoordinationException("WORK_GROUP_NOT_ACTIVE");
        }
        if (!group.groupVersion().equals(completion.workGroupVersionIdentity())) {
            throw new CoordinationException("WORK_GROUP_VERSION_STALE");
        }
        if (hasNoChangeObligation(current, intent)) {
            throw new CoordinationException("NO_CHANGE_REVIEW_OBLIGATION");
        }
    }

    /**
     * Returns whether any active intent still belongs to the group.
     *
     * @param current     project projections
     * @param workGroupId the group
     * @return {@code true} when a lane of the group is open
     */
    public static boolean hasActiveGroupIntents(ProjectionView current, WorkGroupId workGroupId) {
        return current.collaborationProjection().activeIntents().stream()
                .anyMatch(intent -> intent.group().equals(workGroupId));
    }

    /**
     * Returns whether any lane of the group ended with a result: a snapshot or a recorded no-change completion.
     *
     * @param current     project projections
     * @param workGroupId the group
     * @return {@code true} when the group produced a result
     */
    public static boolean hasFinishedWork(ProjectionView current, WorkGroupId workGroupId) {
        return current.taskCompletionProjection().allSnapshots().stream()
                .anyMatch(snapshot -> snapshot.provenance().workGroupIdentity().equals(workGroupId))
                || current.collaborationProjection().noChangeCompletions().stream()
                .anyMatch(completion -> completion.workGroupIdentity().equals(workGroupId));
    }

    /**
     * Returns whether a live or unreviewed grant can still add work to the group.
     *
     * @param current     project projections
     * @param workGroupId the group
     * @return {@code true} when such a grant exists
     */
    public static boolean hasUnresolvedGroupGrants(ProjectionView current, WorkGroupId workGroupId) {
        return current.workGroupProjection().grants().stream().filter(grant -> grant.group().equals(workGroupId))
                .filter(grant -> current.collaborationProjection().successorIntent(grant.targetIntent()).isEmpty())
                .anyMatch(grant -> current.workGroupProjection().grantUnresolved(grant.identity()));
    }

    /**
     * Returns whether an outstanding request still refers to this group.
     *
     * @param current     project projections
     * @param workGroupId the group
     * @return {@code true} when a pending request names a lane of the group
     */
    public static boolean hasPendingGroupRequests(ProjectionView current, WorkGroupId workGroupId) {
        return current.collaborationProjection().requests().stream()
                .filter(request -> request.status() == CoordinationRequest.Status.PENDING)
                .anyMatch(request -> current.collaborationProjection().intent(request.conflictingIntent())
                        .map(intent -> intent.group().equals(workGroupId))
                        .orElseGet(() -> current.collaborationProjection()
                                .noChangeCompletion(request.conflictingIntent())
                                .map(completion -> completion.workGroupIdentity().equals(workGroupId)).orElse(false)));
    }

    /**
     * Requires every group snapshot to be integrated or superseded by an integrated later revision
     * of the same task and authority lineage.
     *
     * @param current     project projections
     * @param workGroupId the group
     * @return {@code true} when nothing of the group's work is left unintegrated
     */
    public static boolean allGroupSnapshotsIntegrated(ProjectionView current, WorkGroupId workGroupId) {
        for (var snapshot : current.taskCompletionProjection().allSnapshots()) {
            if (!snapshot.provenance().workGroupIdentity().equals(workGroupId)) {
                continue;
            }
            TaskCompletionState state = current.taskCompletionProjection().snapshotState(snapshot.snapshotIdentity())
                    .orElse(TaskCompletionState.ACTIVE);
            if (state != TaskCompletionState.INTEGRATED) {
                // Rejected and corrected, or left behind when its continued lane published again:
                // either way the lane's work is the later revision, and that one is integrated.
                boolean corrected = current.taskCompletionProjection().allSnapshots().stream()
                        .anyMatch(candidate -> candidate.taskIdentity().equals(snapshot.taskIdentity())
                                && candidate.provenance().lineageIdentity()
                                .equals(snapshot.provenance().lineageIdentity())
                                && candidate.provenance().claimEpochIdentity().value() > snapshot.provenance()
                                .claimEpochIdentity().value() &&
                                current.taskCompletionProjection().snapshotState(candidate.snapshotIdentity())
                                        .orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.INTEGRATED);
                if (!corrected) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns whether prepared lane completions still lack integrated snapshots.
     *
     * @param current     project projections
     * @param workGroupId the group
     * @return {@code true} when a completion of the group is prepared but not integrated
     */
    public static boolean hasUnintegratedPreparedCompletion(ProjectionView current, WorkGroupId workGroupId) {
        return current.taskCompletionProjection().allPrepared().stream()
                .anyMatch(prepared -> current.collaborationProjection().intent(prepared.laneIdentity())
                        .map(intent -> intent.group().equals(workGroupId) && current.taskCompletionProjection()
                                .findSnapshotForTaskRevision(intent.task(),
                                        intent.identity(),
                                        prepared.claimEpochIdentity()).map(snapshot ->
                                        current.taskCompletionProjection().snapshotState(snapshot.snapshotIdentity())
                                                .orElse(TaskCompletionState.ACTIVE) != TaskCompletionState.INTEGRATED)
                                .orElse(true))
                        .orElseGet(() -> current.collaborationProjection().noChangeCompletion(prepared.laneIdentity())
                                .map(completion -> completion.workGroupIdentity().equals(workGroupId)).orElse(false)));
    }
}
