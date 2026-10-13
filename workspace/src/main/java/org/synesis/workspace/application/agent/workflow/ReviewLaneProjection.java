package org.synesis.workspace.application.agent.workflow;

import static org.synesis.workspace.application.agent.workflow.NextActionSupport.isNullOrBlank;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.isProducerLane;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.logSuppressed;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.openEventStore;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.projectedList;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.putLaneFence;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.putNextProtocol;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.selectorMaps;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.snapshotStateOf;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.task.TaskSnapshotService;

/**
 * Projects what a lane must do or wait for because of review: pending review requests, reviewer
 * waits, the correction after a rejected snapshot, snapshot and grant waits, and the owner's reply
 * to review requests.
 *
 * <p>Separate from {@link AgentReviewActionProjection}, which supplies the reviewer-side building
 * blocks; this class composes them, strongest first, for the caller's own lane. It reads the
 * durable projection and inspects worktrees but never changes coordination state.</p>
 */
final class ReviewLaneProjection {

    private ReviewLaneProjection() {
    }

    /**
     * Resolves the review-related actions of a lane, strongest first: pending review requests,
     * review actions, reviewer wait, correction revision, then review coordination and grant waits.
     *
     * @param snapshotService inspects the assigned worktree for the revision and grant stages
     * @param store         current project projection
     * @param collaboration public collaboration projection
     * @param lane          the caller's lane
     * @param reviewOnly    {@code true} to restrict coordination and review actions to review
     *                      matters, as required while the workspace is stale
     * @param finishAsked   whether the caller asked to finish its lane
     * @return the first applicable action, or {@code null} when the lane has no review matters
     */
    static AgentResponse reviewLaneAction(TaskSnapshotService snapshotService, PredictionEventStore store, Map<String, Object> collaboration, LaneScope lane, boolean reviewOnly, boolean finishAsked) {
        String participant = lane.participantId();
        AgentResponse response = AgentReviewActionProjection.pendingReviewRequestResponse(collaboration, store, participant);
        if (response != null) {
            return response;
        }
        response = AgentReviewActionProjection.reviewActionResponse(collaboration, reviewOnly);
        if (response != null) {
            return response;
        }
        response = AgentReviewActionProjection.reviewerPendingAction(store, participant);
        if (response != null) {
            return response;
        }
        response = revisionRequiredAction(snapshotService, store, participant, lane.worktree(), finishAsked);
        if (response != null) {
            return response;
        }
        return reviewCoordinationAndGrantResponse(snapshotService, store, lane, collaboration, reviewOnly);
    }

    /**
     * Resolves the review wait first, then pending coordination, then an outstanding grant wait.
     *
     * @param snapshotService inspects the assigned worktree for the grant wait
     * @param store         current project projection
     * @param lane          the caller's lane
     * @param collaboration public collaboration projection
     * @param reviewOnly    {@code true} to limit pending coordination to review requests
     * @return the first applicable action, or {@code null} when nothing is pending
     */
    private static AgentResponse reviewCoordinationAndGrantResponse(TaskSnapshotService snapshotService, PredictionEventStore store, LaneScope lane, Map<String, Object> collaboration, boolean reviewOnly) {
        AgentResponse reviewResponse = reviewPendingAction(store, lane.participantId());
        if (reviewResponse != null) {
            return reviewResponse;
        }
        AgentResponse coordinationResponse = pendingCoordinationResponse(collaboration, reviewOnly);
        if (coordinationResponse != null) {
            return coordinationResponse;
        }
        return pendingReviewGrantResponse(collaboration, pendingReviewGrantAction(snapshotService, store, lane));
    }

    /**
     * Projects the exact correction revision after a reviewed snapshot was rejected.
     *
     * <p>The owner must admit a fresh review request before the implementer is told to publish the
     * correction; otherwise the revision projection would hide the pending request and leave the
     * reviewer without a lawful current-epoch grant. A failed publishable-change comparison keeps
     * the revision gate closed.</p>
     *
     * @param snapshotService compares the worktree with the rejected snapshot
     * @param store            current project projection
     * @param participantId    exact caller participant
     * @param assignedWorktree verified assigned worktree, or {@code null} when unknown
     * @param finishAsked      whether the caller asked to finish its lane
     * @return a {@code REVISION_REQUIRED} response, or {@code null} when no revision is owed
     */
    private static AgentResponse revisionRequiredAction(TaskSnapshotService snapshotService, PredictionEventStore store, String participantId, Path assignedWorktree, boolean finishAsked) {
        if (isNullOrBlank(participantId)) {
            return null;
        }
        var completion = store.taskCompletionProjection();
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!isProducerLane(intent, participantId) || hasPendingReviewRequest(store, participantId)) {
                continue;
            }
            if (completion.findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).isPresent()) {
                continue;
            }
            TaskSnapshotRecord rejected = latestRejectedSnapshot(store, intent);
            if (rejected == null || AgentReviewActionProjection.hasCurrentReviewGrant(store, intent)) {
                continue;
            }
            if (hasRevisionPublishableChanges(snapshotService, assignedWorktree, intent, rejected)) {
                continue;
            }
            // A reviewer's rejection asks for a change, so the same content is not offered again.
            // The validation command may have failed for what was never the lane's: the command
            // itself, or what was integrated before. After an operator had changed the command,
            // a lane with nothing to correct was shown this for ever and could not finish.
            if (finishAsked && snapshotStateOf(store, rejected) == TaskCompletionState.VALIDATION_REFUSED) {
                continue;
            }
            return revisionRequiredResponse(store, participantId, intent, rejected);
        }
        return null;
    }

    /**
     * Finds the rejection a producer has not yet answered with a new snapshot.
     *
     * <p>{@link #revisionRequiredAction} projects it only until the producer admits the reviewer's
     * next request. From then on the producer was answered as if it were implementing from the
     * start, and an implementer left without learning that its snapshot had been rejected.</p>
     *
     * @param store         current project projection
     * @param participantId exact caller participant
     * @return the rejected snapshot with its review decision, or {@code null} when none is unanswered
     */
    static Map<String, Object> unansweredRejection(PredictionEventStore store, String participantId) {
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!isProducerLane(intent, participantId) || store.taskCompletionProjection().findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).isPresent()) {
                continue;
            }
            TaskSnapshotRecord rejected = latestRejectedSnapshot(store, intent);
            if (rejected != null) {
                return CollaborationResponseMapper.rejectedSnapshotMap(rejected, store);
            }
        }
        return null;
    }

    /**
     * Returns whether a review request addressed to the participant is still pending admission.
     *
     * @param store         current project projection
     * @param participantId exact caller participant
     * @return {@code true} if a pending {@code REVIEW} request targets the participant
     */
    private static boolean hasPendingReviewRequest(PredictionEventStore store, String participantId) {
        return store.collaborationProjection().requests().stream()
                .anyMatch(request -> request.status() == CoordinationRequest.Status.PENDING && request.kind() == CoordinationRequest.Kind.REVIEW && request.target().equals(participantId));
    }

    /**
     * Finds the most recent refused snapshot of an earlier claim epoch in the same authority
     * lineage: one a reviewer rejected, or one the project's validation command refused at
     * integration. Either way the lane owes a correction and is shown what was refused.
     *
     * @param store  current project projection
     * @param intent the producer intent being corrected
     * @return the latest rejected predecessor snapshot, or {@code null} when there is none
     */
    private static TaskSnapshotRecord latestRejectedSnapshot(PredictionEventStore store, WorkIntent intent) {
        return store.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.taskId().equals(intent.taskId()))
                .filter(snapshot -> snapshot.provenance().authorityLineageId().equals(intent.authorityLineageId())).filter(snapshot -> snapshot.provenance().claimEpoch() < intent.version())
                .filter(snapshot -> snapshotStateOf(store, snapshot).refused()).max(Comparator.comparingLong(snapshot -> snapshot.provenance().claimEpoch())).orElse(null);
    }

    /**
     * Returns whether the worktree already holds changes that revise a rejected snapshot.
     *
     * @param snapshotService compares the worktree with the rejected snapshot
     * @param assignedWorktree verified assigned worktree, or {@code null} when unknown
     * @param intent           the producer intent being corrected
     * @param rejected         the rejected predecessor snapshot
     * @return {@code true} only when the comparison succeeded and found publishable changes; a
     *         failed comparison returns {@code false} so the revision gate stays closed
     */
    private static boolean hasRevisionPublishableChanges(TaskSnapshotService snapshotService, Path assignedWorktree, WorkIntent intent, TaskSnapshotRecord rejected) {
        if (assignedWorktree == null) {
            return false;
        }
        try {
            return snapshotService.hasRevisionPublishableChanges(assignedWorktree, intent.selectors(), intent.baseCommit(), rejected);
        } catch (Exception failure) {
            logSuppressed("revision publishable-change comparison", failure);
            return false;
        }
    }

    /**
     * Builds the {@code REVISION_REQUIRED} response with its executable {@code implement} payload.
     *
     * @param store         current project projection
     * @param participantId exact caller participant
     * @param intent        the producer intent being corrected
     * @param rejected      the rejected predecessor snapshot
     * @return the ready response asking the implementer to publish the correction
     */
    private static AgentResponse revisionRequiredResponse(PredictionEventStore store, String participantId, WorkIntent intent, TaskSnapshotRecord rejected) {
        long workGroupVersion = store.workGroupProjection().group(intent.workGroupId()).map(WorkGroup::version).orElse(0L);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(NextActionKeys.STATE, NextActionProtocol.STATE_REVISION_REQUIRED);
        result.put("revisionRequired", true);
        result.put(NextActionKeys.CURRENT_INTENT, CollaborationResponseMapper.intentMap(intent));
        result.put("rejectedSnapshot", CollaborationResponseMapper.rejectedSnapshotMap(rejected, store));
        result.put(NextActionKeys.LATEST_REJECTED_SNAPSHOT_ID, rejected.snapshotId());
        result.put(NextActionKeys.WORK_GROUP_ID, intent.workGroupId().toString());
        result.put(NextActionKeys.CLAIM_EPOCH, intent.version());
        result.put(NextActionKeys.AUTHORITY_LINEAGE_ID, intent.authorityLineageId().toString());
        putNextProtocol(result, NextActionProtocol.ACTION_IMPLEMENT, "implementation_revision", revisionRequiredPayload(store, participantId, intent, workGroupVersion));
        return new AgentResponse(AgentStatus.READY, AgentReason.REVISION_REQUIRED, null, result);
    }

    /** Builds the exact executable correction payload for a rejected snapshot. */
    private static Map<String, Object> revisionRequiredPayload(PredictionEventStore store, String participantId, WorkIntent intent, long workGroupVersion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putLaneFence(payload, intent, participantId, workGroupVersion, store.headSequence());
        payload.put(NextActionKeys.AUTHORITY_LINEAGE_ID, intent.authorityLineageId().toString());
        payload.put("baseCommit", intent.baseCommit());
        payload.put(NextActionKeys.SELECTORS, selectorMaps(intent));
        return payload;
    }

    /**
     * Projects the wait state for an implementer whose exact snapshot awaits review.
     *
     * <p>A continuation preserves authority lineage, but a predecessor snapshot is not the
     * successor's executable review revision. The wait therefore stays bound to the exact current
     * intent and epoch; otherwise a migrated review grant could be stranded behind an older
     * {@code REVIEW_PENDING} snapshot. A pending review-admission request is answered by the owner
     * first, because the snapshot wait only makes sense once a reviewer has been admitted.</p>
     *
     * @param store         current project projection
     * @param participantId exact caller participant
     * @return a {@code WAITING} response, or {@code null} when no snapshot awaits review
     */
    private static AgentResponse reviewPendingAction(PredictionEventStore store, String participantId) {
        if (isNullOrBlank(participantId)) {
            return null;
        }
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!isProducerLane(intent, participantId)) {
                continue;
            }
            TaskSnapshotRecord snapshot = store.taskCompletionProjection().findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).orElse(null);
            if (snapshot == null || snapshotStateOf(store, snapshot) != TaskCompletionState.REVIEW_PENDING) {
                continue;
            }
            boolean pendingReviewAdmission = store.collaborationProjection().requests().stream().anyMatch(request -> AgentReviewActionProjection.pendingReviewAdmissionTargets(store, request, intent, participantId));
            if (pendingReviewAdmission) {
                continue;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put(NextActionKeys.STATE, TaskCompletionState.REVIEW_PENDING.name());
            result.put("reviewPending", true);
            result.put(NextActionKeys.SNAPSHOT, CollaborationResponseMapper.snapshotMap(snapshot, store));
            result.put(NextActionKeys.CURRENT_INTENT, CollaborationResponseMapper.intentMap(intent));
            result.put(NextActionKeys.WORK_GROUP_ID, intent.workGroupId().toString());
            result.put(NextActionKeys.CLAIM_EPOCH, intent.version());
            putNextProtocol(result, NextActionProtocol.ACTION_WAIT, NextActionProtocol.KIND_REVIEW_VALIDATION, reviewPendingPayload(intent, snapshot, participantId));
            return new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, result);
        }
        return null;
    }

    /** Builds the exact wait payload for the lane and immutable snapshot awaiting review. */
    private static Map<String, Object> reviewPendingPayload(WorkIntent intent, TaskSnapshotRecord snapshot, String participantId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NextActionKeys.SNAPSHOT_ID, snapshot.snapshotId());
        payload.put(NextActionKeys.INTENT_ID, intent.intentId().toString());
        payload.put(NextActionKeys.WORK_GROUP_ID, intent.workGroupId().toString());
        payload.put(NextActionKeys.CLAIM_EPOCH, intent.version());
        payload.put(NextActionKeys.PARTICIPANT, participantId);
        payload.put(NextActionKeys.AUTHORITY_LINEAGE_ID, intent.authorityLineageId().toString());
        return payload;
    }

    /**
     * Wraps a pending review-grant detail map in a {@code WAIT} response.
     *
     * @param collaboration      public collaboration projection
     * @param pendingReviewGrant grant details from {@link #pendingReviewGrantAction}, or
     *                           {@code null}
     * @return a ready {@code WAIT} response merging both maps, or {@code null} when there is no
     *         pending grant
     */
    private static AgentResponse pendingReviewGrantResponse(Map<String, Object> collaboration, Map<String, Object> pendingReviewGrant) {
        if (pendingReviewGrant == null) {
            return null;
        }
        Map<String, Object> ownerWait = new LinkedHashMap<>(collaboration);
        ownerWait.putAll(pendingReviewGrant);
        return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, ownerWait);
    }

    /**
     * Projects a review grant that a reviewer must still be admitted to and consume.
     *
     * <p>A reciprocal reviewer grant must not fence an active lane before that lane has produced
     * any claim-covered source change: the participant still needs the ordinary {@code IMPLEMENT}
     * envelope to do its assigned work. Once publishable changes exist, the wait is kept so that
     * publication stays gated on reviewer admission and consumption. An inspection failure is
     * fail-closed - the wait is retained rather than projecting an unverified continuation.</p>
     *
     * @param snapshotService inspects the lane's worktree for publishable changes
     * @param store current project projection
     * @param lane  the caller's lane
     * @return the grant-pending detail map, or {@code null} when no grant is pending
     */
    private static Map<String, Object> pendingReviewGrantAction(TaskSnapshotService snapshotService, PredictionEventStore store, LaneScope lane) {
        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        var completion = store.taskCompletionProjection();
        String participantId = lane.participantId();

        List<ResourceSelector> participantClaims = collaboration.activeIntents().stream().filter(candidate -> candidate.participant().equals(participantId))
                .filter(candidate -> candidate.role() == WorkIntent.Role.PRODUCER).flatMap(candidate -> candidate.selectors().stream()).distinct().toList();
        List<ResourceSelector> foreignClaims = LaneCompletionProjection.foreignClaims(store, participantId);

        for (WorkIntent intent : collaboration.activeIntents()) {
            if (!isProducerLane(intent, participantId)) {
                continue;
            }
            WorkGroup group = workGroups.group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            if (completion.findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).isPresent()) {
                continue;
            }
            for (LaneGrant grant : workGroups.grants()) {
                if (!isOpenReviewGrantFor(store, grant, intent, participantId)) {
                    continue;
                }
                if (lane.worktree() != null) {
                    Publishability publishability = LaneCompletionProjection.inspectScopedPublishableChanges(snapshotService, lane.worktree(), intent, participantClaims, foreignClaims, lane.baseCommit());
                    if (publishability == Publishability.NOT_PUBLISHABLE) {
                        // The inspection depends only on the intent, so every remaining grant of
                        // this intent would be skipped for the same reason.
                        break;
                    }
                }
                return reviewGrantPendingResult(grant, intent, group);
            }
        }
        return null;
    }

    /**
     * Returns whether a grant is an open, unsuperseded review grant addressed to another
     * participant for the exact intent and claim epoch.
     *
     * @param store         current project projection
     * @param grant         the grant to test
     * @param intent        the producer intent under review
     * @param participantId exact caller participant
     * @return {@code true} if the grant still awaits admission or consumption
     */
    private static boolean isOpenReviewGrantFor(PredictionEventStore store, LaneGrant grant, WorkIntent intent, String participantId) {
        var workGroups = store.workGroupProjection();
        return grant.workGroupId().equals(intent.workGroupId()) && grant.targetIntentId().equals(intent.intentId()) && grant.claimEpoch() == intent.version() && !grant.targetParticipant().equals(participantId)
                && !workGroups.grantSuperseded(grant.grantId()) && workGroups.grantAvailable(grant.grantId()) && workGroups.reviewValidationForGrant(grant.grantId()).isEmpty();
    }

    /**
     * Builds the detail map for a pending review grant.
     *
     * @param grant  the pending grant
     * @param intent the producer intent under review
     * @param group  the active work group of the intent
     * @return the {@code REVIEW_GRANT_PENDING} detail map
     */
    private static Map<String, Object> reviewGrantPendingResult(LaneGrant grant, WorkIntent intent, WorkGroup group) {
        Map<String, Object> payload = reviewGrantPendingPayload(grant, intent);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(NextActionKeys.STATE, NextActionProtocol.STATE_REVIEW_GRANT_PENDING);
        result.put("reviewGrantPending", true);
        result.put("reviewGrant", CollaborationResponseMapper.laneGrantMap(grant));
        result.put(NextActionKeys.WORK_GROUP, CollaborationResponseMapper.workGroupMap(group));
        result.put(NextActionKeys.REVIEWER_PARTICIPANT, grant.targetParticipant());
        putNextProtocol(result, NextActionProtocol.ACTION_WAIT, "review_grant_consumption", payload);
        return result;
    }

    /** Builds the wait payload that admits the reviewer for the exact grant epoch. */
    private static Map<String, Object> reviewGrantPendingPayload(LaneGrant grant, WorkIntent intent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NextActionKeys.GRANT_ID, grant.grantId().toString());
        payload.put(NextActionKeys.WORK_GROUP_ID, grant.workGroupId().toString());
        payload.put(NextActionKeys.INTENT_ID, grant.targetIntentId().toString());
        payload.put(NextActionKeys.REVIEWED_INTENT_ID, grant.targetIntentId().toString());
        payload.put(NextActionKeys.REVIEWED_PARTICIPANT_ID, intent.participant());
        payload.put(NextActionKeys.CLAIM_EPOCH, grant.claimEpoch());
        payload.put(NextActionKeys.TARGET_PARTICIPANT, grant.targetParticipant());
        payload.put(NextActionKeys.REVIEWER_PARTICIPANT, grant.targetParticipant());
        payload.put("snapshotRequired", true);
        return payload;
    }

    /**
     * Builds the response for pending coordination, optionally limited to review requests.
     *
     * @param collaboration public collaboration projection containing {@code pendingCoordination}
     * @param reviewOnly    {@code true} to answer only review-acceptance requests
     * @return a {@code RESPOND_COORDINATION} response, or {@code null} when nothing qualifies
     */
    private static AgentResponse pendingCoordinationResponse(Map<String, Object> collaboration, boolean reviewOnly) {
        List<Map<String, Object>> pending = projectedList(collaboration, NextActionKeys.PENDING_COORDINATION);
        if (pending.isEmpty()) {
            return null;
        }
        Map<String, Object> reviewAcceptance = AgentReviewActionProjection.reviewAcceptanceAction(pending);
        if (reviewOnly && reviewAcceptance == null) {
            return null;
        }
        Map<String, Object> ownerAction = new LinkedHashMap<>(collaboration);
        if (reviewAcceptance != null) {
            ownerAction.putAll(reviewAcceptance);
        } else {
            ownerAction.put(NextActionKeys.REVIEW_DECISION, ownerReviewDecision(pending));
        }
        return new AgentResponse(AgentStatus.READY, AgentReason.OWNER_REQUEST_PENDING, AgentNextAction.RESPOND_COORDINATION, ownerAction);
    }

    /**
     * Builds the decision template an owner must complete when answering pending requests.
     *
     * @param pending the pending coordination requests addressed to the owner
     * @return the {@code reviewDecision} descriptor, including one response template per request
     */
    private static Map<String, Object> ownerReviewDecision(List<Map<String, Object>> pending) {
        return Map.of("required",
                true,
                NextActionKeys.TOOL,
                NextActionProtocol.ACTION_RESPOND_COORDINATION,
                "field",
                "coordinationStatus",
                "allowedResults",
                List.of("ACCEPTED", "REVISED", "REJECTED", "CANCELLED", "COMPLETED"),
                "optionalFields",
                List.of("proposal"),
                "responseTemplates",
                pending.stream().map(item -> Map.of(NextActionKeys.KIND, NextActionProtocol.KIND_COORDINATION_RESPONSE, NextActionKeys.PAYLOAD, Map.of("coordinationRequest", item.get(NextActionKeys.REQUEST_ID)))).toList(),
                "instruction",
                "Choose an owned request template and supply coordinationStatus after judgment; " + "the template is not executable until that decision is filled.");
    }

    /**
     * Projects review follow-up for a completed lane whose participant may still owe or await
     * review work.
     *
     * @param location  project location
     * @param sessionId the completed session
     * @return a review response, a {@code WAIT} while a completed work group is still active, or
     *         {@code null} when nothing is owed or the projection is unavailable
     */
    static AgentResponse completedReviewAction(ProjectLocation location, String sessionId) {
        try {
            Optional<PredictionEventStore> openedStore = openEventStore(location);
            if (openedStore.isEmpty()) {
                return null;
            }
            PredictionEventStore store = openedStore.get();
            String participant = WorkspaceCollaborationService.participantHandle(sessionId);
            Map<String, Object> baseCollaboration = CollaborationView.collaborationDetails(store, sessionId);
            AgentResponse pendingReviewResponse = AgentReviewActionProjection.pendingReviewRequestResponse(baseCollaboration, store, participant);
            if (pendingReviewResponse != null) {
                return pendingReviewResponse;
            }
            Set<UUID> completedGroups = AgentReviewActionProjection.completedParticipantWorkGroups(store, participant);
            if (completedGroups.isEmpty()) {
                return null;
            }
            Map<String, Object> collaboration = CollaborationView.collaborationDetails(store, sessionId, completedGroups);
            AgentResponse reviewResponse = AgentReviewActionProjection.reviewActionResponse(collaboration, true);
            if (reviewResponse != null) {
                return reviewResponse;
            }
            var workGroups = store.workGroupProjection();
            boolean groupActive = completedGroups.stream().anyMatch(groupId -> workGroups.group(groupId).filter(group -> group.status() == WorkGroup.Status.ACTIVE).isPresent());
            return groupActive ? new AgentResponse(AgentStatus.WAITING, null, AgentNextAction.WAIT, collaboration) : null;
        } catch (Exception failure) {
            logSuppressed("completed-lane review projection", failure);
            return null;
        }
    }
}
