package org.synesis.workspace.application.agent.workflow;

import static org.synesis.workspace.application.agent.workflow.NextActionSupport.activeIntentOf;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.isNullOrBlank;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.isProducerLane;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.logSuppressed;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.putLaneFence;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.putNextProtocol;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.snapshotStateOf;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
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
import org.synesis.workspace.application.task.TaskSnapshotService;

/**
 * Projects how a lane is closed: capability publication, first publication of the immutable
 * snapshot, completion of an already-reviewed snapshot, clean no-change completion, and the
 * owner-side rules that must settle before a capability owner may finish.
 *
 * <p>Every projection names an existing explicit finish call and re-derives its evidence from the
 * durable projection and the worktree; none performs a lifecycle transition. Separate from the
 * orchestrator so each finish rule sits next to the evidence it requires.</p>
 */
final class LaneCompletionProjection {

    /**
     * Summary attached to every snapshot-publication finish payload.
     */
    private static final String PUBLISH_SNAPSHOT_SUMMARY = "Publish the completed immutable snapshot";

    /**
     * Capability states in which the owner still owes a reply, an implementation, or a revision,
     * or is waiting for the requester's verdict. While any owned request is in one of them the
     * owner's lane is not ready to be closed.
     */
    private static final Set<CapabilityLifecycleState> OWNER_OPEN_STATES = Set.of(CapabilityLifecycleState.AWAITING_OWNER,
            CapabilityLifecycleState.REVISION_REQUESTED,
            CapabilityLifecycleState.ACCEPTED,
            CapabilityLifecycleState.IMPLEMENTING,
            CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE,
            CapabilityLifecycleState.VALIDATING);

    private static final String IMPLEMENTATION_AWAITING_VALIDATION = "IMPLEMENTATION_AWAITING_VALIDATION";

    private LaneCompletionProjection() {
    }

    /**
     * Resolves the first eligible snapshot-publication or no-change completion action.
     *
     * @param snapshotService validates the worktree evidence behind each finish projection
     * @param store current project projection
     * @param lane  the caller's lane
     * @param actor capability identity of the caller
     * @return a {@code FINISH_LANE} response, or {@code null} when the lane cannot be finished yet
     */
    static AgentResponse completionRequestAction(TaskSnapshotService snapshotService, PredictionEventStore store, LaneScope lane, CapabilityActor actor) {
        AgentResponse capability = capabilityPublicationAction(snapshotService, store, lane, actor);
        if (capability != null) {
            return capability;
        }
        Map<String, Object> publishedSnapshot = publishedSnapshotCompletionAction(store, lane.participantId(), actor);
        if (publishedSnapshot != null) {
            return new AgentResponse(AgentStatus.READY, AgentReason.SNAPSHOT_PUBLICATION_REQUIRED, AgentNextAction.FINISH_LANE, publishedSnapshot);
        }
        Map<String, Object> publication = snapshotPublicationAction(snapshotService, store, lane, actor);
        if (publication != null) {
            return new AgentResponse(AgentStatus.READY, AgentReason.SNAPSHOT_PUBLICATION_REQUIRED, AgentNextAction.FINISH_LANE, publication);
        }
        Map<String, Object> noChange = noChangeCompletionAction(snapshotService, store, lane, actor);
        return noChange == null ? null : new AgentResponse(AgentStatus.READY, null, AgentNextAction.FINISH_LANE, noChange);
    }

    /** Projects publication only after the owner explicitly declares its implementation complete. */
    private static AgentResponse capabilityPublicationAction(TaskSnapshotService snapshotService, PredictionEventStore store, LaneScope lane, CapabilityActor actor) {
        if (lane.worktree() == null) {
            return null;
        }
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!intent.participant().equals(lane.participantId())) {
                continue;
            }
            for (CapabilityRequestRecord request : store.capabilityRequestProjection().records().values()) {
                if (!request.matchesOwner(actor.nodeId(), actor.supervisorId(), actor.workerId())
                        || !request.authorityLineageId().equals(intent.authorityLineageId())
                        || (request.state() != CapabilityLifecycleState.ACCEPTED && request.state() != CapabilityLifecycleState.IMPLEMENTING)) {
                    continue;
                }
                try {
                    String base = snapshotService.snapshotBaseForBoundWorktree(lane.worktree(), intent.baseCommit(), lane.baseCommit());
                    String tree = snapshotService.capabilityPublicationTreeHash(lane.worktree(), base, intent.selectors(), foreignClaims(store, lane.participantId()));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("capabilityRequestHandle", request.handle().value());
                    payload.put("contractRevision", request.contractRevision());
                    payload.put("intentId", intent.intentId().toString());
                    payload.put("claimEpoch", intent.version());
                    payload.put("expectedTreeHash", tree);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.putAll(payload);
                    result.put("nextProtocolPayload", payload);
                    return new AgentResponse(AgentStatus.READY, AgentReason.SNAPSHOT_PUBLICATION_REQUIRED,
                            AgentNextAction.PUBLISH_CAPABILITY_IMPLEMENTATION, result);
                } catch (Exception failure) {
                    logSuppressed("capability publication inspection", failure);
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.REQUEST_HUMAN_HELP,
                            Map.of("reason", "CAPABILITY_PUBLICATION_NOT_READY", "capabilityRequestHandle", request.handle().value(),
                                    "detail", Objects.toString(failure.getMessage(), failure.getClass().getSimpleName())));
                }
            }
        }
        return null;
    }

    /**
     * Projects the idempotent finish required after a reviewed snapshot is accepted, including when
     * the original worker lane remains dirty.
     *
     * <p>{@code finish_lane} snapshots the visible lane into an immutable commit and deliberately
     * does not require the harness to commit its worktree first. Once review accepts that snapshot,
     * the producer still needs one exact finish call to drive integration and release its binding.
     * The projection is therefore based only on the durable snapshot and the current producer
     * intent; it never authorizes another mutable workspace operation.</p>
     *
     * @param store         durable project event store
     * @param participantId exact caller participant
     * @param actor         capability identity of the caller
     * @return the finish payload, or {@code null} when no accepted published snapshot awaits
     *         producer completion
     */
    private static Map<String, Object> publishedSnapshotCompletionAction(PredictionEventStore store, String participantId, CapabilityActor actor) {
        if (isNullOrBlank(participantId)) {
            return null;
        }
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!isProducerLane(intent, participantId) || AgentReviewActionProjection.hasUnresolvedCapabilityObligation(store, intent, actor.nodeId(), actor.supervisorId(), actor.workerId())) {
                continue;
            }
            TaskSnapshotRecord snapshot = store.taskCompletionProjection().findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).orElse(null);
            if (snapshot == null) {
                continue;
            }
            TaskCompletionState state = snapshotStateOf(store, snapshot);
            if (state != TaskCompletionState.REVIEW_ACCEPTED && state != TaskCompletionState.INTEGRATED) {
                continue;
            }
            WorkGroup group = store.workGroupProjection().group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            return publishedSnapshotCompletionResult(store, intent, participantId, group, snapshot, state);
        }
        return null;
    }

    /** Builds the finish response for a snapshot that has already passed review. */
    private static Map<String, Object> publishedSnapshotCompletionResult(PredictionEventStore store,
            WorkIntent intent,
            String participantId,
            WorkGroup group,
            TaskSnapshotRecord snapshot,
            TaskCompletionState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NextActionKeys.SUMMARY, PUBLISH_SNAPSHOT_SUMMARY);
        putLaneFence(payload, intent, participantId, group.version(), store.headSequence());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(NextActionKeys.STATE, state.name());
        result.put("snapshotAlreadyPublished", true);
        result.put(NextActionKeys.SNAPSHOT, CollaborationResponseMapper.snapshotMap(snapshot, store));
        result.put(NextActionKeys.CURRENT_INTENT, CollaborationResponseMapper.intentMap(intent));
        putNextProtocol(result, NextActionProtocol.ACTION_FINISH_LANE, "snapshot_completion", payload);
        return result;
    }

    /**
     * Projects the finish request that publishes a lane's immutable snapshot for the first time.
     *
     * <p>Reviewer lanes may publish only their own claimed report or test artifact. The
     * role-independent snapshot path is safe here because the lane claims and the foreign-claim
     * fence still exclude another participant's source changes. Consuming a {@code REVIEW} grant
     * authorizes publication but does not manufacture implementation work, so the same read-only
     * source/artifact gate that {@code finish_lane} applies while creating the snapshot is applied
     * here. A failed inspection never turns into a false publication permission; the ordinary
     * {@code IMPLEMENT} path stays available.</p>
     *
     * @param snapshotService validates the worktree evidence for publication
     * @param store current project projection
     * @param lane  the caller's lane
     * @param actor capability identity of the caller
     * @return the finish detail map, or {@code null} when no publication is due
     */
    private static Map<String, Object> snapshotPublicationAction(TaskSnapshotService snapshotService, PredictionEventStore store, LaneScope lane, CapabilityActor actor) {
        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        var completion = store.taskCompletionProjection();
        String participantId = lane.participantId();

        List<ResourceSelector> participantClaims = collaboration.activeIntents().stream().filter(candidate -> candidate.participant().equals(participantId)).flatMap(candidate -> candidate.selectors().stream()).distinct()
                .toList();
        List<ResourceSelector> foreignClaims = foreignClaims(store, participantId);

        for (WorkIntent intent : collaboration.activeIntents()) {
            if (!intent.participant().equals(participantId)) {
                continue;
            }
            Publishability publishability = inspectScopedPublishableChanges(snapshotService, lane.worktree(), intent, participantClaims, foreignClaims, lane.baseCommit());
            if (publishability != Publishability.PUBLISHABLE || !reviewRequirementSatisfied(store, intent, participantId) || AgentReviewActionProjection.hasUnresolvedCapabilityObligation(store,
                    intent,
                    actor.nodeId(),
                    actor.supervisorId(),
                    actor.workerId()) || completion.findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).isPresent()) {
                continue;
            }
            WorkGroup group = workGroups.group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            return snapshotPublicationResult(store, intent, participantId, group);
        }
        return null;
    }

    /**
     * Returns whether review is either not required or already satisfied for a lane.
     *
     * @param store         current project projection
     * @param intent        the lane's intent
     * @param participantId exact caller participant
     * @return {@code true} if a review grant was consumed, the group's policy makes review
     *         mandatory (and is handled elsewhere), or the task does not require review
     */
    private static boolean reviewRequirementSatisfied(PredictionEventStore store, WorkIntent intent, String participantId) {
        var workGroups = store.workGroupProjection();
        boolean reviewGrantConsumed = workGroups.grants().stream()
                .anyMatch(grant -> grant.workGroupId().equals(intent.workGroupId()) && grant.targetIntentId().equals(intent.intentId()) && grant.claimEpoch() == intent.version() && !grant.targetParticipant()
                        .equals(participantId) && !workGroups.grantSuperseded(grant.grantId()) && workGroups.grantConsumed(grant.grantId()));
        boolean mandatoryPolicyReview = workGroups.group(intent.workGroupId()).map(group -> group.reviewPolicy().mandatory()).orElse(false);
        return reviewGrantConsumed || mandatoryPolicyReview || !AgentTaskCompletionService.reviewRequired(store, intent, participantId);
    }

    /**
     * Builds the finish detail map that publishes a lane's snapshot.
     *
     * @param store         current project projection
     * @param intent        the lane's intent
     * @param participantId exact caller participant
     * @param group         the active work group of the intent
     * @return the {@code snapshotPublicationRequired} detail map
     */
    private static Map<String, Object> snapshotPublicationResult(PredictionEventStore store, WorkIntent intent, String participantId, WorkGroup group) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotPublicationRequired", true);
        result.put(NextActionKeys.WORK_GROUP_ID, intent.workGroupId().toString());
        result.put(NextActionKeys.INTENT_ID, intent.intentId().toString());
        result.put(NextActionKeys.CLAIM_EPOCH, intent.version());
        result.put(NextActionKeys.PARTICIPANT, participantId);
        result.put(NextActionKeys.NEXT_PROTOCOL_ACTION, NextActionProtocol.ACTION_FINISH_LANE);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NextActionKeys.SUMMARY, PUBLISH_SNAPSHOT_SUMMARY);
        putLaneFence(payload, intent, participantId, group.version(), store.headSequence());
        if (hasRejectedPredecessor(store, intent)) {
            payload.put(NextActionKeys.AUTHORITY_LINEAGE_ID, intent.authorityLineageId().toString());
        }
        result.put(NextActionKeys.NEXT_PROTOCOL_PAYLOAD, payload);
        return result;
    }

    /**
     * Returns whether this lane corrects a snapshot of an earlier epoch that review rejected.
     *
     * @param store  current project projection
     * @param intent the lane's intent
     * @return {@code true} if a rejected predecessor snapshot exists for the same lane and lineage
     */
    private static boolean hasRejectedPredecessor(PredictionEventStore store, WorkIntent intent) {
        return store.taskCompletionProjection().allSnapshots().stream()
                .anyMatch(snapshot -> snapshot.taskId().equals(intent.taskId()) && snapshot.provenance().laneId().equals(intent.intentId()) && snapshot.provenance().authorityLineageId()
                        .equals(intent.authorityLineageId()) && snapshot.provenance().claimEpoch() < intent.version() && snapshotStateOf(store, snapshot) == TaskCompletionState.REVIEW_REJECTED);
    }

    /**
     * Projects the exact explicit finish request for a clean no-change lane.
     *
     * <p>The payload contains only server-derived identifiers and the current optimistic versions.
     * It is an executable suggestion, not an implicit lifecycle transition; the provider must call
     * {@code finish_lane} with this payload.</p>
     *
     * @param snapshotService validates the worktree for no-change eligibility
     * @param store current project projection
     * @param lane  the caller's lane
     * @param actor capability identity of the caller
     * @return the typed finish payload, or {@code null} when no-change completion is not eligible
     */
    private static Map<String, Object> noChangeCompletionAction(TaskSnapshotService snapshotService, PredictionEventStore store, LaneScope lane, CapabilityActor actor) {
        String participantId = lane.participantId();
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!intent.participant().equals(participantId)) {
                continue;
            }
            NoChangeCompletionEligibility.Result eligibility = NoChangeCompletionEligibility.assess(store, intent, participantId, actor.nodeId(), actor.supervisorId(), actor.workerId(), lane.worktree(), snapshotService);
            if (!eligibility.eligible()) {
                continue;
            }
            WorkGroup group = store.workGroupProjection().group(intent.workGroupId()).orElse(null);
            if (group == null) {
                continue;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put(NextActionKeys.STATE, NextActionProtocol.STATE_NO_CHANGE_COMPLETION_READY);
            result.put("noChangeCompletionAvailable", true);
            result.put(NextActionKeys.CURRENT_INTENT, CollaborationResponseMapper.intentMap(intent));
            result.put(NextActionKeys.WORK_GROUP, CollaborationResponseMapper.workGroupMap(group));
            putNextProtocol(result, NextActionProtocol.ACTION_FINISH_LANE, "no_change_completion", noChangeCompletionPayload(store, intent, participantId, group));
            return result;
        }
        return null;
    }

    /** Builds the server-versioned finish payload for an eligible no-change lane. */
    private static Map<String, Object> noChangeCompletionPayload(PredictionEventStore store, WorkIntent intent, String participantId, WorkGroup group) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", "no_change");
        putLaneFence(payload, intent, participantId, group.version(), store.headSequence());
        payload.put(NextActionKeys.SUMMARY, "Verification completed successfully; no repository mutation was required");
        return payload;
    }

    /**
     * Checks publishability once for projections that share the same lane and claim fence.
     *
     * @param snapshotService inspects the worktree against the lane and claim fence
     * @param worktree          verified assigned worktree, or {@code null} when unknown
     * @param intent            the lane's intent
     * @param participantClaims selectors claimed by the caller
     * @param foreignClaims     selectors claimed by other participants
     * @param bindingBaseCommit base commit recorded in the provider binding
     * @return the inspection outcome; {@link Publishability#UNKNOWN} when no worktree is known or
     *         the inspection failed
     */
    static Publishability inspectScopedPublishableChanges(TaskSnapshotService snapshotService, Path worktree, WorkIntent intent, List<ResourceSelector> participantClaims, List<ResourceSelector> foreignClaims, String bindingBaseCommit) {
        if (worktree == null) {
            return Publishability.UNKNOWN;
        }
        try {
            String snapshotBaseCommit = snapshotService.snapshotBaseForBoundWorktree(worktree, intent.baseCommit(), bindingBaseCommit);
            boolean publishable = snapshotService.hasScopedPublishableChanges(worktree, intent.selectors(), participantClaims, foreignClaims, snapshotBaseCommit);
            return publishable ? Publishability.PUBLISHABLE : Publishability.NOT_PUBLISHABLE;
        } catch (Exception failure) {
            logSuppressed("scoped publishable-change inspection", failure);
            return Publishability.UNKNOWN;
        }
    }

    /**
     * Collects the claims of other participants so restored durable content can stay visible
     * without becoming this participant's publication authority.
     *
     * @param store         current project projection
     * @param participantId exact caller participant
     * @return the distinct selectors claimed by every other participant
     */
    static List<ResourceSelector> foreignClaims(PredictionEventStore store, String participantId) {
        return store.collaborationProjection().activeIntents().stream().filter(intent -> !intent.participant().equals(participantId)).flatMap(intent -> intent.selectors().stream()).distinct().toList();
    }

    /**
     * Returns the reply a capability owner still owes, which outranks its own request to finish.
     *
     * <p>Only the owner can answer a request addressed to its lane. If the lane closed first the
     * requester would wait on a reply nobody is left to give, so an owner asking to finish is shown
     * the request instead. Only an unanswered request counts. For a request the owner has
     * accepted, and for a revision the requester asked for after validating, the completion
     * request is itself how the owner declares the next implementation ready to publish.</p>
     *
     * @param store current project projection
     * @param lane  the caller's lane
     * @param actor capability identity of the caller
     * @return the owed reply, or {@code null} when none is owed
     */
    static AgentResponse owedOwnerReply(PredictionEventStore store, LaneScope lane, CapabilityActor actor) {
        WorkIntent intent = activeIntentOf(store, lane.participantId()).orElse(null);
        if (intent == null) {
            return null;
        }
        AgentResponse owner = CapabilityActionProjection.ownerCapabilityAction(store.capabilityRequestProjection(), actor, CapabilityActionProjection.inCallerLineage(store.collaborationProjection().activated(), intent));
        return owner != null && owner.nextAction() == AgentNextAction.RESPOND_COORDINATION ? owner : null;
    }

    /**
     * Returns the wait an owner must observe before finishing while a published implementation
     * still awaits the requester's verdict.
     *
     * <p>The verdict may be a revision, and only an open owner lane can make one. Offering
     * {@code finish_lane} here also contradicted the action itself, which refuses as soon as the
     * requester opens its validation.</p>
     *
     * @param store current project projection
     * @param lane  the caller's lane
     * @param actor capability identity of the caller
     * @return the wait, or {@code null} when no published implementation of this lane is unjudged
     */
    static AgentResponse awaitedVerdict(PredictionEventStore store, LaneScope lane, CapabilityActor actor) {
        WorkIntent intent = activeIntentOf(store, lane.participantId()).orElse(null);
        if (intent == null) {
            return null;
        }
        List<CapabilityRequestRecord> unjudged = AgentReviewActionProjection.ownerImplementationsAwaitingVerdict(store, intent, actor.nodeId(), actor.supervisorId(), actor.workerId());
        if (unjudged.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(NextActionKeys.CAPABILITY_REQUEST_HANDLE, unjudged.getFirst().handle().value());
        result.put(NextActionKeys.REASON, IMPLEMENTATION_AWAITING_VALIDATION);
        result.put(NextActionKeys.PENDING, unjudged.size());
        return new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, result);
    }

    /**
     * Tells a capability owner to request completion once its published implementation is validated.
     *
     * <p>After validation the capability lifecycle addresses the owner no further, yet integration
     * of the dependent lane waits for the owner's own lane snapshot. Without this projection the
     * owner only ever sees the shared integration wait. The projection grants nothing: it names the
     * existing completion request, and that request re-derives publication evidence itself.</p>
     *
     * @param store current project projection
     * @param lane  the caller's lane
     * @param actor capability identity of the caller
     * @return the completion prompt, or {@code null} unless every capability this lane owns is
     *         settled, at least one is validated, and the lane has not published its snapshot
     */
    static AgentResponse validatedOwnerCompletionAction(PredictionEventStore store, LaneScope lane, CapabilityActor actor) {
        WorkIntent intent = activeIntentOf(store, lane.participantId()).orElse(null);
        if (intent == null || !isProducerLane(intent, lane.participantId())
                || store.taskCompletionProjection().findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).isPresent()) {
            return null;
        }
        List<CapabilityRequestRecord> owned = store.capabilityRequestProjection().records().values().stream()
                .filter(candidate -> candidate.matchesOwner(actor.nodeId(), actor.supervisorId(), actor.workerId()))
                .filter(candidate -> candidate.authorityLineageId().equals(intent.authorityLineageId()))
                .toList();
        List<CapabilityRequestRecord> validated = owned.stream().filter(candidate -> candidate.state() == CapabilityLifecycleState.VALIDATED).toList();
        if (validated.isEmpty() || owned.stream().anyMatch(candidate -> OWNER_OPEN_STATES.contains(candidate.state()))
                || AgentReviewActionProjection.hasUnresolvedCapabilityObligation(store, intent, actor.nodeId(), actor.supervisorId(), actor.workerId())) {
            return null;
        }
        return CapabilityActionProjection.capabilityOwnerResponse(validated, AgentStatus.READY, null, null);
    }
}
