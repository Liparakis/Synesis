package org.synesis.workspace.application.agent.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.ImplementationRevisionRecord;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.IntegrationOrchestrationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.ProviderSessionTerminalizationService;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.application.workspace.ReadinessResult;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.command.ProjectCommandDiagnostics;

/**
 * Application service for retrieving the single highest-priority actionable coordination item for
 * an ambient MCP session.
 *
 * <p>The returned action is a projection of durable collaboration state, not
 * a suggestion generated from local timing. A concrete recommended tool and its arguments are the
 * only lifecycle authority; an ordinary {@code WAIT} or {@code IMPLEMENT} response must not be
 * upgraded by the caller into a guessed transition.</p>
 *
 * @since 1.0
 */
@SuppressWarnings({"ExtractMethodRecommender"})
public final class AgentNextActionService {

    /**
     * Resolves project metadata and durable project-local paths.
     */
    private final ProjectApplicationService projectService;
    /**
     * Verifies the exact provider binding and assigned worktree.
     */
    private final WorkspaceReadinessService readinessService;
    /**
     * Adds durable continuation metadata to the projected response.
     */
    private final AgentWorkflowReducer workflowReducer;
    /**
     * Resolves and validates immutable review snapshots when authorized.
     */
    private final TaskSnapshotService snapshotService;

    /**
     * Creates a next-action retrieval application service.
     */
    public AgentNextActionService() {
        this.projectService = new ProjectApplicationService();
        this.readinessService = new WorkspaceReadinessService();
        this.workflowReducer = new AgentWorkflowReducer();
        this.snapshotService = new TaskSnapshotService();
    }

    private static boolean sessionIsTerminal(ProjectLocation location, ProviderSessionBindingService.Binding binding) throws Exception {
        return "TERMINAL".equals(binding.status()) || ProviderSessionTerminalizationService.isSessionTerminal(location, binding.sessionId());
    }

    private static AgentResponse terminalSessionResponse(String sessionId) {
        return new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of("state", "SESSION_TERMINAL", "lane", sessionId));
    }

    private static AgentResponse terminalStateUnavailableResponse(Exception failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return new AgentResponse(AgentStatus.FAILED,
                AgentReason.INTERNAL_FAILURE,
                AgentNextAction.REQUEST_HUMAN_HELP,
                Map.of("reason", "SESSION_TERMINAL_STATE_UNAVAILABLE", "causeType", cause.getClass().getSimpleName()));
    }

    /**
     * Returns whether the exact bound session owns an active intent.
     *
     * <p>This predicate is intentionally scoped to the exact participant and
     * active intent; a completed work group or another participant's lane cannot authorize
     * continuation for the caller.</p>
     */
    private static boolean hasActiveIntent(ProjectLocation location, ProviderSessionBindingService.Binding binding) {
        try {
            Path coordination = location.root().resolve(".synesis/coordination");
            if (!Files.exists(coordination.resolve("events"))) {
                return false;
            }
            var store = new PredictionEventStore(coordination, location.projectId());
            String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            return store.collaborationProjection().activeIntents().stream().anyMatch(intent -> intent.participant().equals(participant));
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Projects the exact correction revision after a reviewed snapshot was rejected.
     */
    private static AgentResponse revisionRequiredAction(PredictionEventStore store, String participantId, Path assignedWorktree, TaskSnapshotService snapshotService) {
        if (participantId == null || participantId.isBlank()) {
            return null;
        }
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!participantId.equals(intent.participant()) || intent.role() != WorkIntent.Role.PRODUCER) {
                continue;
            }
            // The owner must admit a fresh review request before the
            // implementer is told to publish the correction.  Otherwise the
            // revision projection would hide the pending request and leave
            // the reviewer without a lawful current-epoch grant.
            boolean pendingReviewAdmission = store.collaborationProjection().requests().stream()
                    .anyMatch(request -> request.status() == CoordinationRequest.Status.PENDING && request.kind() == CoordinationRequest.Kind.REVIEW && request.target().equals(participantId));

            if (pendingReviewAdmission) {
                continue;
            }

            TaskSnapshotRecord current = store.taskCompletionProjection().findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).orElse(null);

            if (current != null) {
                continue;
            }

            TaskSnapshotRecord rejected = store.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.taskId().equals(intent.taskId()))
                    .filter(snapshot -> snapshot.provenance().authorityLineageId().equals(intent.authorityLineageId())).filter(snapshot -> snapshot.provenance().claimEpoch() < intent.version())
                    .filter(snapshot -> store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.REVIEW_REJECTED)
                    .max(Comparator.comparingLong(snapshot -> snapshot.provenance().claimEpoch())).orElse(null);

            if (rejected == null || AgentReviewActionProjection.hasCurrentReviewGrant(store, intent)) {
                continue;
            }

            boolean revised = false;

            if (assignedWorktree != null && snapshotService != null) {
                try {
                    revised = snapshotService.hasRevisionPublishableChanges(assignedWorktree, intent.selectors(), intent.baseCommit(), rejected);
                } catch (Exception ignored) {
                    // A failed comparison must keep the revision gate closed.
                }
            }

            if (revised) {
                continue;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intentId", intent.intentId().toString());
            payload.put("workGroupId", intent.workGroupId().toString());
            payload.put("claimEpoch", intent.version());
            payload.put("workGroupVersion", store.workGroupProjection().group(intent.workGroupId()).map(WorkGroup::version).orElse(0L));
            payload.put("expectedRevision", store.headSequence());
            payload.put("participant", participantId);
            payload.put("authorityLineageId", intent.authorityLineageId().toString());
            payload.put("baseCommit", intent.baseCommit());
            payload.put("selectors", intent.selectors().stream().map(CollaborationResponseMapper::selectorMap).toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("state", "REVISION_REQUIRED");
            result.put("revisionRequired", true);
            result.put("currentIntent", CollaborationResponseMapper.intentMap(intent));
            result.put("rejectedSnapshot", CollaborationResponseMapper.snapshotMap(rejected, store));
            result.put("latestRejectedSnapshotId", rejected.snapshotId());
            result.put("workGroupId", intent.workGroupId().toString());
            result.put("claimEpoch", intent.version());
            result.put("authorityLineageId", intent.authorityLineageId().toString());
            result.put("nextProtocolAction", "implement");
            result.put("nextProtocolKind", "implementation_revision");
            result.put("nextProtocolPayload", payload);
            return new AgentResponse(AgentStatus.READY, AgentReason.REVISION_REQUIRED, null, result);
        }
        return null;
    }

    /**
     * Projects the wait state for an implementer whose exact snapshot awaits review.
     */
    private static AgentResponse reviewPendingAction(PredictionEventStore store, String participantId) {
        if (participantId == null || participantId.isBlank()) {
            return null;
        }
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!participantId.equals(intent.participant()) || intent.role() != WorkIntent.Role.PRODUCER) {
                continue;
            }
            // A continuation preserves authority lineage, but a predecessor snapshot is not the
            // successor's executable review revision.  Review waits must therefore remain bound to
            // the exact current intent and epoch; otherwise a migrated review grant can be stranded
            // behind an older REVIEW_PENDING snapshot.
            TaskSnapshotRecord snapshot = store.taskCompletionProjection().findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).orElse(null);
            if (snapshot == null || store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE) != TaskCompletionState.REVIEW_PENDING) {
                continue;
            }
            boolean pendingReviewAdmission = store.collaborationProjection().requests().stream().anyMatch(request -> AgentReviewActionProjection.pendingReviewAdmissionTargets(store, request, intent, participantId));
            if (pendingReviewAdmission) {
                // Let the owner answer the migrated review-admission request first. The immutable
                // snapshot wait is only meaningful after a reviewer has been admitted.
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("snapshotId", snapshot.snapshotId());
            payload.put("intentId", intent.intentId().toString());
            payload.put("workGroupId", intent.workGroupId().toString());
            payload.put("claimEpoch", intent.version());
            payload.put("participant", participantId);
            payload.put("authorityLineageId", intent.authorityLineageId().toString());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("state", TaskCompletionState.REVIEW_PENDING.name());
            result.put("reviewPending", true);
            result.put("snapshot", CollaborationResponseMapper.snapshotMap(snapshot, store));
            result.put("currentIntent", CollaborationResponseMapper.intentMap(intent));
            result.put("workGroupId", intent.workGroupId().toString());
            result.put("claimEpoch", intent.version());
            result.put("nextProtocolAction", "wait");
            result.put("nextProtocolKind", "review_validation");
            result.put("nextProtocolPayload", payload);
            return new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, result);
        }
        return null;
    }

    /**
     * Projects recovery-held work that a different current participant may explicitly continue.
     *
     * <p>This is a read-only projection. It never issues a grant, changes claims, or exposes the
     * internal snapshot reference. The continuation request derives its target identity from the
     * exact authenticated caller and revalidates every field under the project append lock.</p>
     *
     * @param store         current project projection
     * @param participantId exact current caller participant
     * @return deterministic public-safe continuation candidates
     */
    private static List<Map<String, Object>> continuationCandidates(PredictionEventStore store, String participantId) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (store.collaborationProjection().participantState(intent.participant()).orElse(null) != Participant.State.RECOVERY_HELD || store.collaborationProjection().recoverySnapshotReference(intent.participant())
                    .isEmpty()) {
                continue;
            }
            WorkGroup group = store.workGroupProjection().group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            boolean sameParticipant = intent.participant().equals(participantId);
            boolean scopeConflict = !sameParticipant && store.collaborationProjection().activeIntents().stream().filter(existing -> existing.participant().equals(participantId))
                    .anyMatch(existing -> existing.selectors().stream().anyMatch(existingSelector -> intent.selectors().stream().anyMatch(existingSelector::overlaps)));
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("sourceIntentId", intent.intentId().toString());
            candidate.put("intentId", intent.intentId().toString());
            candidate.put("taskId", intent.taskId().toString());
            candidate.put("workGroupId", intent.workGroupId().toString());
            candidate.put("goal", intent.goal());
            candidate.put("acceptance", intent.acceptance());
            candidate.put("selectors", intent.selectors().stream().map(CollaborationResponseMapper::selectorMap).toList());
            candidate.put("claimEpoch", intent.version());
            candidate.put("role", intent.role().wireValue());
            candidate.put("knownDependencies", intent.knownDependencies());
            candidate.put("formerOwner", intent.participant());
            candidate.put("formerOwnerState", Participant.State.RECOVERY_HELD.name());
            candidate.put("workGroup", CollaborationResponseMapper.workGroupMap(group));
            candidate.put("unfinished", true);
            candidate.put("blocksWorkGroupCompletion", true);
            candidate.put("durableArtifactsAvailable", true);
            candidate.put("available", !sameParticipant && !scopeConflict);
            candidate.put("continuationRequest", Map.of("kind", "continuation", "payload", Map.of("intentId", intent.intentId().toString(), "claimEpoch", intent.version())));
            if (sameParticipant) {
                candidate.put("blockedReason", "CURRENT_PARTICIPANT_IS_FORMER_OWNER");
            } else if (scopeConflict) {
                candidate.put("blockedReason", "OVERLAPPING_CLAIM");
            }
            candidates.add(candidate);
        }
        candidates.sort(Comparator.comparing(candidate -> String.valueOf(candidate.get("sourceIntentId"))));
        return List.copyOf(candidates);
    }

    /**
     * Returns the highest-priority explicit successor action for the current participant.
     *
     * @param participantId exact caller participant
     * @param collaboration public collaboration projection
     * @return continuation action, or {@code null} when no candidate is available
     */
    private static AgentResponse continuationAction(String participantId, Map<String, Object> collaboration) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> projected = (List<Map<String, Object>>) collaboration.getOrDefault("continuationCandidates", List.of());
        List<Map<String, Object>> available = projected.stream().filter(candidate -> Boolean.TRUE.equals(candidate.get("available"))).toList();
        if (available.isEmpty()) {
            return null;
        }
        Map<String, Object> selected = available.getFirst();
        Map<String, Object> result = new LinkedHashMap<>(collaboration);
        result.put("state", "RECOVERY_CONTINUATION_AVAILABLE");
        result.put("continuationAvailable", true);
        result.put("continuationSelectionRequired", available.size() > 1);
        result.put("nextProtocolAction", "request_coordination");
        result.put("nextProtocolKind", "continuation");
        if (available.size() == 1) {
            result.put("selectedContinuation", selected);
            result.put("nextProtocolPayload", Map.of("intentId", selected.get("sourceIntentId"), "claimEpoch", selected.get("claimEpoch")));
        } else {
            result.put("selectionInstruction", "Choose one continuation candidate explicitly; no candidate is auto-assigned.");
        }
        result.put("continuationParticipant", participantId);
        return new AgentResponse(AgentStatus.READY, AgentReason.CONTINUATION_AVAILABLE, AgentNextAction.REQUEST_COORDINATION, result);
    }











    /**
     * Returns whether a reviewer still lacks a resolved current producer review.
     */
    static boolean hasUnresolvedReviewObligation(PredictionEventStore store, WorkIntent reviewer) {
        if (reviewer.role() != WorkIntent.Role.REVIEWER) {
            return false;
        }
        boolean unresolvedGrant = store.workGroupProjection().grants().stream().filter(grant -> grant.workGroupId().equals(reviewer.workGroupId()))
                .filter(grant -> grant.targetParticipant().equals(reviewer.participant())).filter(grant -> !AgentReviewActionProjection.grantTargetsSupersededIntent(store, grant))
                .filter(grant -> !store.workGroupProjection().grantSuperseded(grant.grantId()))
                // A reviewer continuation is represented by a consumed grant addressed
                // to the successor reviewer.  That grant authorizes continuation of the
                // reviewer lane; it is explicitly not a review obligation and must not
                // strand the successor in REVIEWER_PENDING.
                .filter(grant -> AgentReviewActionProjection.allowsIndependentReview(store, grant)).anyMatch(grant -> store.workGroupProjection().reviewValidationForGrant(grant.grantId()).isEmpty());
        if (unresolvedGrant) {
            return true;
        }
        List<WorkIntent> producers = store.collaborationProjection().activeIntents().stream().filter(intent -> intent.role() == WorkIntent.Role.PRODUCER)
                .filter(intent -> intent.workGroupId().equals(reviewer.workGroupId())).filter(intent -> !intent.participant().equals(reviewer.participant())).toList();
        List<WorkIntent> matching = reviewer.reviewTargetSelectors().isEmpty() ? producers : producers.stream().filter(producer -> AgentReviewActionProjection.reviewTargetsMatch(reviewer, producer)).toList();
        if (matching.isEmpty()) {
            List<LaneGrant> reviewerGrants = store.workGroupProjection().grants().stream().filter(grant -> grant.workGroupId().equals(reviewer.workGroupId()))
                    .filter(grant -> grant.targetParticipant().equals(reviewer.participant())).filter(grant -> !AgentReviewActionProjection.grantTargetsSupersededIntent(store, grant))
                    .filter(grant -> !store.workGroupProjection().grantSuperseded(grant.grantId())).toList();
            if (reviewerGrants.stream().anyMatch(grant -> store.workGroupProjection().continuationGrant(grant.grantId()))) {
                return false;
            }
            // With no producer yet, an empty review-grant list means the reviewer is
            // waiting for review work to emerge. A continuation grant is handled
            // above because it restores the reviewer lane, not a review obligation.
            // Open review grants were handled by unresolvedGrant above. Any grant
            // left here is self-review or already validated, so it does not imply
            // that future review work is pending.
            return reviewerGrants.isEmpty();
        }
        return matching.stream().anyMatch(producer -> !AgentReviewActionProjection.hasCompletedReview(store, reviewer, producer));
    }











































    static AgentNextAction protocolNextAction(String protocolAction) {
        return switch (protocolAction) {
            case "respond_coordination" -> AgentNextAction.RESPOND_COORDINATION;
            case "review_decision" -> AgentNextAction.REVIEW_DECISION;
            case "wait" -> AgentNextAction.WAIT;
            default -> AgentNextAction.REQUEST_COORDINATION;
        };
    }

    /**
     * Collects active claims owned by other participants so restored durable content can remain
     * visible without becoming this participant's publication authority.
     */
    private static List<ResourceSelector> foreignClaims(PredictionEventStore store, String participantId) {
        return store.collaborationProjection().activeIntents().stream().filter(intent -> !intent.participant().equals(participantId)).flatMap(intent -> intent.selectors().stream()).distinct().toList();
    }

    private static Map<String, Object> snapshotPublicationAction(PredictionEventStore store,
            String participantId,
            Path assignedWorktree,
            TaskSnapshotService snapshotService,
            String bindingBaseCommit,
            String nodeId,
            String supervisorId,
            String workerId) {
        var collaboration = store.collaborationProjection();
        var completion = store.taskCompletionProjection();
        List<ResourceSelector> participantClaims = collaboration.activeIntents().stream().filter(candidate -> candidate.participant().equals(participantId)).flatMap(candidate -> candidate.selectors().stream()).distinct()
                .toList();
        List<ResourceSelector> foreignClaims = foreignClaims(store, participantId);
        for (var intent : collaboration.activeIntents()) {
            if (!intent.participant().equals(participantId)) {
                continue;
            }
            // Reviewer lanes may publish only their own claimed report or test artifact.  The
            // role-independent snapshot path is safe here because the lane claims and the foreign
            // claim fence below still exclude another participant's source changes.
            // REVIEW grant consumption authorizes publication but does not
            // manufacture implementation work.  Keep the projection
            // executable by applying the same read-only source/artifact gate
            // that finish_lane applies while creating the snapshot.
            if (assignedWorktree == null || snapshotService == null) {
                continue;
            }
            try {
                String snapshotBaseCommit = snapshotService.snapshotBaseForBoundWorktree(assignedWorktree, intent.baseCommit(), bindingBaseCommit);
                boolean publishable = snapshotService.hasScopedPublishableChanges(assignedWorktree, intent.selectors(), participantClaims, foreignClaims, snapshotBaseCommit);
                if (!publishable) {
                    continue;
                }
            } catch (Exception ignored) {
                // A failed inspection must not turn into a false publication
                // permission.  The normal IMPLEMENT path remains available.
                continue;
            }
            boolean reviewGrantConsumed = store.workGroupProjection().grants().stream()
                    .anyMatch(grant -> grant.workGroupId().equals(intent.workGroupId()) && grant.targetIntentId().equals(intent.intentId()) && grant.claimEpoch() == intent.version() && !grant.targetParticipant()
                            .equals(participantId) && !store.workGroupProjection().grantSuperseded(grant.grantId()) && store.workGroupProjection().grantConsumed(grant.grantId()));
            boolean mandatoryPolicyReview = store.workGroupProjection().group(intent.workGroupId()).map(group -> group.reviewPolicy().mandatory()).orElse(false);
            if (!reviewGrantConsumed && !mandatoryPolicyReview && AgentTaskCompletionService.reviewRequired(store, intent, participantId)) {
                continue;
            }
            if (AgentReviewActionProjection.hasUnresolvedCapabilityObligation(store, intent, nodeId, supervisorId, workerId)) {
                continue;
            }
            boolean snapshotPublished = completion.findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).isPresent();
            if (snapshotPublished) {
                continue;
            }
            WorkGroup group = store.workGroupProjection().group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("snapshotPublicationRequired", true);
            result.put("workGroupId", intent.workGroupId().toString());
            result.put("intentId", intent.intentId().toString());
            result.put("claimEpoch", intent.version());
            result.put("participant", participantId);
            result.put("nextProtocolAction", "finish_lane");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary", "Publish the completed immutable snapshot");
            payload.put("intentId", intent.intentId().toString());
            payload.put("workGroupId", intent.workGroupId().toString());
            payload.put("claimEpoch", intent.version());
            payload.put("workGroupVersion", group.version());
            payload.put("expectedRevision", store.headSequence());
            payload.put("participant", participantId);
            boolean correction = completion.allSnapshots().stream()
                    .anyMatch(snapshot -> snapshot.taskId().equals(intent.taskId()) && snapshot.provenance().laneId().equals(intent.intentId()) && snapshot.provenance().authorityLineageId()
                            .equals(intent.authorityLineageId()) && snapshot.provenance().claimEpoch() < intent.version()
                            && completion.snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.REVIEW_REJECTED);
            if (correction) {
                payload.put("authorityLineageId", intent.authorityLineageId().toString());
            }
            result.put("nextProtocolPayload", payload);
            return result;
        }
        return null;
    }

    /**
     * Projects the exact explicit finish request for a clean no-change lane.
     *
     * <p>The returned payload contains only server-derived identifiers and the
     * current optimistic versions. It is an executable suggestion, not an implicit lifecycle
     * transition; the provider must call {@code finish_lane} with this payload.</p>
     *
     * @param store            current project projection
     * @param participantId    exact caller participant
     * @param nodeId           caller node identity
     * @param supervisorId     caller supervisor identity
     * @param workerId         caller worker identity
     * @param assignedWorktree verified assigned worktree
     * @param snapshotService  snapshot inspection service
     * @return typed finish payload, or {@code null} when no-change completion is not eligible
     */
    private static Map<String, Object> noChangeCompletionAction(PredictionEventStore store,
            String participantId,
            String nodeId,
            String supervisorId,
            String workerId,
            Path assignedWorktree,
            TaskSnapshotService snapshotService) {
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!intent.participant().equals(participantId)) {
                continue;
            }
            NoChangeCompletionEligibility.Result eligibility = NoChangeCompletionEligibility.assess(store, intent, participantId, nodeId, supervisorId, workerId, assignedWorktree, snapshotService);
            if (!eligibility.eligible()) {
                continue;
            }
            WorkGroup group = store.workGroupProjection().group(intent.workGroupId()).orElse(null);
            if (group == null) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("outcome", "no_change");
            payload.put("intentId", intent.intentId().toString());
            payload.put("workGroupId", intent.workGroupId().toString());
            payload.put("claimEpoch", intent.version());
            payload.put("workGroupVersion", group.version());
            payload.put("expectedRevision", store.headSequence());
            payload.put("participant", participantId);
            payload.put("summary", "Verification completed successfully; no repository mutation was required");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("state", "NO_CHANGE_COMPLETION_READY");
            result.put("noChangeCompletionAvailable", true);
            result.put("currentIntent", CollaborationResponseMapper.intentMap(intent));
            result.put("workGroup", CollaborationResponseMapper.workGroupMap(group));
            result.put("nextProtocolAction", "finish_lane");
            result.put("nextProtocolKind", "no_change_completion");
            result.put("nextProtocolPayload", payload);
            return result;
        }
        return null;
    }

    private static Map<String, Object> pendingReviewGrantAction(PredictionEventStore store, String participantId, Path assignedWorktree, TaskSnapshotService snapshotService, String bindingBaseCommit) {
        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        var completion = store.taskCompletionProjection();
        List<ResourceSelector> participantClaims = collaboration.activeIntents().stream().filter(candidate -> candidate.participant().equals(participantId))
                .filter(candidate -> candidate.role() == WorkIntent.Role.PRODUCER).flatMap(candidate -> candidate.selectors().stream()).distinct().toList();
        List<ResourceSelector> foreignClaims = foreignClaims(store, participantId);
        for (WorkIntent intent : collaboration.activeIntents()) {
            if (!intent.participant().equals(participantId) || intent.role() != WorkIntent.Role.PRODUCER) {
                continue;
            }
            WorkGroup group = workGroups.group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            boolean snapshotPublished = completion.findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).isPresent();
            if (snapshotPublished) {
                continue;
            }
            for (LaneGrant grant : workGroups.grants()) {
                if (!grant.workGroupId().equals(intent.workGroupId()) || !grant.targetIntentId().equals(intent.intentId()) || grant.claimEpoch() != intent.version() || grant.targetParticipant().equals(participantId)
                        || workGroups.grantSuperseded(grant.grantId()) || !workGroups.grantAvailable(grant.grantId()) || workGroups.reviewValidationForGrant(grant.grantId()).isPresent()) {
                    continue;
                }
                // A reciprocal reviewer grant must not fence an active lane
                // before that lane has produced any claim-covered source
                // changes.  The participant still needs the ordinary
                // IMPLEMENT envelope to perform its assigned work.  Once
                // publishable changes exist, keep the wait so publication
                // remains gated on reviewer admission and consumption.
                if (assignedWorktree != null) {
                    try {
                        String snapshotBaseCommit = snapshotService.snapshotBaseForBoundWorktree(assignedWorktree, intent.baseCommit(), bindingBaseCommit);
                        boolean publishable = snapshotService.hasScopedPublishableChanges(assignedWorktree, intent.selectors(), participantClaims, foreignClaims, snapshotBaseCommit);
                        if (!publishable) {
                            continue;
                        }
                    } catch (Exception ignored) {
                        // Inspection failure is fail-closed: retain the wait
                        // rather than projecting an unverified continuation.
                    }
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("grantId", grant.grantId().toString());
                payload.put("workGroupId", grant.workGroupId().toString());
                payload.put("intentId", grant.targetIntentId().toString());
                payload.put("reviewedIntentId", grant.targetIntentId().toString());
                payload.put("reviewedParticipantId", intent.participant());
                payload.put("claimEpoch", grant.claimEpoch());
                payload.put("targetParticipant", grant.targetParticipant());
                payload.put("reviewerParticipant", grant.targetParticipant());
                payload.put("snapshotRequired", true);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("state", "REVIEW_GRANT_PENDING");
                result.put("reviewGrantPending", true);
                result.put("reviewGrant", CollaborationResponseMapper.laneGrantMap(grant));
                result.put("workGroup", CollaborationResponseMapper.workGroupMap(group));
                result.put("reviewerParticipant", grant.targetParticipant());
                result.put("nextProtocolAction", "wait");
                result.put("nextProtocolKind", "review_grant_consumption");
                result.put("nextProtocolPayload", payload);
                return result;
            }
        }
        return null;
    }

    private static int priorityOf(String type) {
        if (type == null) {
            return 99;
        }
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "SAFETY_FAILURE" -> 1;
            case "DEPENDENCY_INVALIDATED" -> 2;
            case "OWNER_REQUEST" -> 3;
            case "NEEDS_CAPABILITY" -> 4;
            case "VALIDATION_REQUIRED" -> 5;
            case "WAITING_FOR_OWNER" -> 6;
            default -> 99;
        };
    }

    @SuppressWarnings("unchecked")
    private static List<CoordinationItem> loadCoordinationItems(Path assignedWorktree, Path projectRoot, String targetWorker) {
        Path itemsFile = assignedWorktree.resolve(".synesis/local/coordination/items.json");
        if (!Files.exists(itemsFile)) {
            itemsFile = projectRoot.resolve(".synesis/local/coordination/items.json");
        }
        if (!Files.exists(itemsFile)) {
            return List.of();
        }

        try {
            String json = Files.readString(itemsFile);
            Object parsed = ProviderJson.parse(json);
            if (!(parsed instanceof List<?> list)) {
                return List.of();
            }

            List<CoordinationItem> items = new ArrayList<>();
            long seq = 0;
            for (Object obj : list) {
                seq++;
                if (obj instanceof Map<?, ?> map) {
                    String worker = (String) map.get("workerId");
                    boolean matchesWorker = worker == null || worker.isBlank() || worker.equalsIgnoreCase(targetWorker);
                    if (!matchesWorker) {
                        continue;
                    }

                    boolean obsolete = Boolean.TRUE.equals(map.get("obsolete")) || Boolean.TRUE.equals(map.get("completed"));
                    if (obsolete) {
                        continue;
                    }

                    String type = (String) map.get("type");
                    String capability = (String) map.get("capability");
                    Map<String, Object> details = (Map<String, Object>) map.get("details");
                    if (details == null) {
                        details = Map.of();
                    }

                    if (type != null) {
                        items.add(new CoordinationItem(type, capability, worker, details, seq));
                    }
                }
            }
            return items;
        } catch (Exception ex) {
            return List.of();
        }
    }

    /**
     * Projects the idempotent finish required after a reviewed snapshot is accepted, including when
     * the original worker lane remains dirty.
     *
     * <p>{@code finish_lane} snapshots the visible lane into an immutable
     * commit and deliberately does not require the harness to commit its worktree first.  Once review
     * accepts that snapshot, the producer still needs one exact finish call to drive integration and
     * release its binding.  This projection is therefore based only on the durable snapshot and
     * current producer intent; it never authorizes another mutable workspace operation.</p>
     *
     * @param store         durable project event store
     * @param participantId exact caller participant
     * @param nodeId        caller node identity
     * @param supervisorId  caller supervisor identity
     * @param workerId      caller worker identity
     * @return projected finish payload, or {@code null} when no accepted published snapshot awaits
     *         producer completion
     */
    private static Map<String, Object> publishedSnapshotCompletionAction(PredictionEventStore store, String participantId, String nodeId, String supervisorId, String workerId) {
        if (participantId == null || participantId.isBlank()) {
            return null;
        }
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!participantId.equals(intent.participant()) || intent.role() != WorkIntent.Role.PRODUCER) {
                continue;
            }
            if (AgentReviewActionProjection.hasUnresolvedCapabilityObligation(store, intent, nodeId, supervisorId, workerId)) {
                continue;
            }
            TaskSnapshotRecord snapshot = store.taskCompletionProjection().findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).orElse(null);
            if (snapshot == null) {
                continue;
            }
            TaskCompletionState state = store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE);
            if (state != TaskCompletionState.REVIEW_ACCEPTED && state != TaskCompletionState.INTEGRATED) {
                continue;
            }
            WorkGroup group = store.workGroupProjection().group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary", "Publish the completed immutable snapshot");
            payload.put("intentId", intent.intentId().toString());
            payload.put("workGroupId", intent.workGroupId().toString());
            payload.put("claimEpoch", intent.version());
            payload.put("workGroupVersion", group.version());
            payload.put("expectedRevision", store.headSequence());
            payload.put("participant", participantId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("state", state.name());
            result.put("snapshotAlreadyPublished", true);
            result.put("snapshot", CollaborationResponseMapper.snapshotMap(snapshot, store));
            result.put("currentIntent", CollaborationResponseMapper.intentMap(intent));
            result.put("nextProtocolAction", "finish_lane");
            result.put("nextProtocolKind", "snapshot_completion");
            result.put("nextProtocolPayload", payload);
            return result;
        }
        return null;
    }

    /**
     * Resolves the single highest-priority actionable coordination item for the active session
     * worker.
     *
     * @param request request payload
     * @return concise agent response
     */
    public AgentResponse getNextAction(NextActionRequest request) {
        AgentResponse response = resolveNextAction(request);
        return workflowReducer.decorate(request, response);
    }

    private AgentResponse resolveNextAction(NextActionRequest request) {
        Objects.requireNonNull(request, "request");

        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        ProjectLocation location;
        ReadinessResult readiness;
        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        Optional<ProviderSessionBindingService.Binding> exactBinding;
        try {
            location = projectService.locate(root);
            exactBinding = bindingService.find(location, request.provider(), request.connectionInstanceId());
            if (exactBinding.isPresent()) {
                try {
                    if (sessionIsTerminal(location, exactBinding.get())) {
                        return terminalSessionResponse(exactBinding.get().sessionId());
                    }
                } catch (Exception failure) {
                    return terminalStateUnavailableResponse(failure);
                }
            }
            if (exactBinding.isPresent() && "COMPLETED".equals(exactBinding.get().status())) {
                AgentResponse reviewResponse = completedReviewAction(location, exactBinding.get().sessionId());
                if (reviewResponse != null) {
                    return reviewResponse;
                }
                // A completed lane remains terminal when no review action is
                // available.  It never re-enters workspace readiness or write
                // ownership merely because a sibling lane is still active.
                return new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of("state", "COMPLETED", "lane", exactBinding.get().sessionId()));
            }
            readiness = readinessService.assess(location, request.provider(), request.connectionInstanceId());
            if (!readiness.ready() && "WORKSPACE_GENERATION_MISMATCH".equals(readiness.internalReason())) {
                // A worker may commit its completed change before polling the
                // completion inbox. Ordinary readiness must reject that
                // generation for mutation, while this read-only projection
                // may validate a clean descendant for snapshot publication.
                ReadinessResult completionReadiness = readinessService.assessCompletion(location, request.provider(), request.connectionInstanceId());
                if (completionReadiness.ready()) {
                    readiness = completionReadiness;
                }
            }
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }
        if (!readiness.ready()) {
            // A sibling integration may advance the control checkout while a
            // reviewer has legitimate uncommitted work in its assigned
            // worktree.  Keep the stale-dirty workspace fail-closed for all
            // workspace operations, but allow the durable review protocol to
            // continue because review_validation is authorized by the exact
            // session, grant, epoch, and immutable snapshot rather than by a
            // replacement worktree.
            if (exactBinding.isPresent() && "BOUND".equals(exactBinding.get().status()) && "CONTROL_BASE_ADVANCED".equals(readiness.internalReason()) && (bindingService.hasConfirmedUncommittedWork(exactBinding.get())
                    || hasActiveIntent(location, exactBinding.get()))) {
                AgentResponse staleAction = staleCoordinationAction(location, exactBinding.get(), request.completionRequested());
                if (staleAction != null) {
                    return staleAction;
                }
            }
            return readiness.response();
        }
        ProviderSessionBindingService.Binding binding = readiness.binding();
        Path assignedWorktree = readiness.worktree();

        try {
            NodeIdentity callerIdentity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            String callerNodeId = callerIdentity.nodeId();
            String callerWorkerId = binding.workerId();
            Path coordDir = location.root().resolve(".synesis/coordination");
            if (Files.exists(coordDir.resolve("events"))) {
                PredictionEventStore store = new PredictionEventStore(coordDir, location.projectId());
                // Startup reconciliation is deliberately pull-safe: each
                // durable inbox read gives the shared integration pump an
                // opportunity to recover an interrupted attempt or advance
                // the oldest eligible immutable snapshot. The pump owns its
                // project lock and never mutates a worker worktree.
                AgentResponse integrationPump = new IntegrationOrchestrationService().orchestrateIntegration(root, store, callerIdentity);
                if (integrationPump.status() == AgentStatus.COMPLETED) {
                    store = new PredictionEventStore(coordDir, location.projectId());
                    exactBinding = bindingService.find(location, request.provider(), request.connectionInstanceId());
                    if (exactBinding.isPresent()) {
                        try {
                            if (sessionIsTerminal(location, exactBinding.get())) {
                                return terminalSessionResponse(exactBinding.get().sessionId());
                            }
                        } catch (Exception failure) {
                            return terminalStateUnavailableResponse(failure);
                        }
                    }
                    if (exactBinding.isPresent() && "COMPLETED".equals(exactBinding.get().status())) {
                        AgentResponse reviewResponse = completedReviewAction(location, exactBinding.get().sessionId());
                        if (reviewResponse != null) {
                            return reviewResponse;
                        }
                        return new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of("state", "COMPLETED", "lane", exactBinding.get().sessionId()));
                    }
                }
                CapabilityRequestProjection capProj = store.capabilityRequestProjection();
                Map<String, Object> collaboration = collaborationDetails(store, binding.sessionId());
                String callerParticipant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
                AgentResponse pendingReviewResponse = AgentReviewActionProjection.pendingReviewRequestResponse(collaboration, store, callerParticipant);
                if (pendingReviewResponse != null) {
                    return pendingReviewResponse;
                }
                AgentResponse reviewResponse = AgentReviewActionProjection.reviewActionResponse(collaboration, false);
                if (reviewResponse != null) {
                    return reviewResponse;
                }
                AgentResponse reviewerPendingResponse = AgentReviewActionProjection.reviewerPendingAction(store, callerParticipant);
                if (reviewerPendingResponse != null) {
                    return reviewerPendingResponse;
                }
                AgentResponse revisionResponse = revisionRequiredAction(store, callerParticipant, assignedWorktree, snapshotService);
                if (revisionResponse != null) {
                    return revisionResponse;
                }
                AgentResponse reviewPendingResponse = reviewPendingAction(store, callerParticipant);
                if (reviewPendingResponse != null) {
                    return reviewPendingResponse;
                }
                @SuppressWarnings("unchecked") List<Map<String, Object>> pendingCoordination = (List<Map<String, Object>>) collaboration.get("pendingCoordination");
                if (!pendingCoordination.isEmpty()) {
                    Map<String, Object> ownerAction = new LinkedHashMap<>(collaboration);
                    Map<String, Object> reviewAcceptance = AgentReviewActionProjection.reviewAcceptanceAction(pendingCoordination);
                    if (reviewAcceptance != null) {
                        ownerAction.putAll(reviewAcceptance);
                    } else {
                        ownerAction.put("reviewDecision",
                                Map.of("required",
                                        true,
                                        "tool",
                                        "respond_coordination",
                                        "field",
                                        "coordinationStatus",
                                        "allowedResults",
                                        List.of("ACCEPTED", "REVISED", "REJECTED", "CANCELLED", "COMPLETED"),
                                        "optionalFields",
                                        List.of("proposal"),
                                        "responseTemplates",
                                        pendingCoordination.stream().map(item -> Map.of("kind", "coordination_response", "payload", Map.of("coordinationRequest", item.get("requestId")))).toList(),
                                        "instruction",
                                        "Choose an owned request template and supply coordinationStatus after judgment; the template is not executable until that decision is filled."));
                    }
                    return new AgentResponse(AgentStatus.READY, AgentReason.OWNER_REQUEST_PENDING, AgentNextAction.RESPOND_COORDINATION, ownerAction);
                }
                Map<String, Object> pendingReviewGrant = pendingReviewGrantAction(store, callerParticipant, assignedWorktree, snapshotService, binding.baseCommit());
                if (pendingReviewGrant != null) {
                    Map<String, Object> ownerWait = new LinkedHashMap<>(collaboration);
                    ownerWait.putAll(pendingReviewGrant);
                    return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, ownerWait);
                }

                if (request.completionRequested()) {
                    Map<String, Object> publishedSnapshotCompletion = publishedSnapshotCompletionAction(store, callerParticipant, callerNodeId, binding.supervisorId(), binding.workerId());
                    if (publishedSnapshotCompletion != null) {
                        return new AgentResponse(AgentStatus.READY, AgentReason.SNAPSHOT_PUBLICATION_REQUIRED, AgentNextAction.FINISH_LANE, publishedSnapshotCompletion);
                    }
                    Map<String, Object> publicationAction = snapshotPublicationAction(store,
                            callerParticipant,
                            assignedWorktree,
                            snapshotService,
                            binding.baseCommit(),
                            callerNodeId,
                            binding.supervisorId(),
                            binding.workerId());
                    if (publicationAction != null) {
                        return new AgentResponse(AgentStatus.READY, AgentReason.SNAPSHOT_PUBLICATION_REQUIRED, AgentNextAction.FINISH_LANE, publicationAction);
                    }
                    Map<String, Object> noChangeAction = noChangeCompletionAction(store, callerParticipant, callerNodeId, binding.supervisorId(), binding.workerId(), assignedWorktree, snapshotService);
                    if (noChangeAction != null) {
                        return new AgentResponse(AgentStatus.READY, null, AgentNextAction.FINISH_LANE, noChangeAction);
                    }
                }

                AgentResponse continuationResponse = continuationAction(callerParticipant, collaboration);
                // A recovery-held owner cannot resolve the request fencing integration until
                // a successor can discover its continuation. This projection transfers no authority.
                if (integrationPump.status() == AgentStatus.WAITING && integrationPump.reason() == AgentReason.INTEGRATION_PENDING && continuationResponse != null) {
                    return continuationResponse;
                }

                // Keep a nonterminal integration result visible when no higher-priority review,
                // coordination, completion, or review-candidate discovery action is available. A
                // pending candidate must remain visible so a new reviewer can lawfully select it.
                boolean reviewCandidateSelectionRequired = Boolean.TRUE.equals(collaboration.get("reviewCandidateSelectionRequired"));
                boolean reviewerContinuationForPendingReview = AgentReviewActionProjection.hasAvailableReviewerReviewContinuation(store, collaboration);
                if (integrationPump.status() != AgentStatus.READY && integrationPump.status() != AgentStatus.COMPLETED && !reviewCandidateSelectionRequired && !reviewerContinuationForPendingReview) {
                    return integrationPump;
                }

                // A request addressed to this participant is a durable inbox item and an explicit
                // completion request is a durable request to close this participant's own lane.  Both
                // must remain reachable even when an unrelated recovery-held lane is also available.
                // Continuing first can select an opposite-role lane and make the required owner response
                // or snapshot publication unreachable (or trigger REVIEW_SELF_NOT_ALLOWED).
                if (continuationResponse != null) {
                    return continuationResponse;
                }

                // A session that has not established its own active intent is
                // not eligible to service another lane's capability inbox.
                // Provider models may receive an implementation-available
                // item before they have successfully decoded a claim-bearing
                // ensure_session request.  Returning that item here would
                // allow the unclaimed session to answer as the owner or
                // requester of a different lane.  Keep the response
                // actionable: the caller must refresh/establish its own
                // claim first, while discovery remains available in the
                // bounded result payload.
                boolean callerHasActiveIntent = store.collaborationProjection().activeIntents().stream().anyMatch(intent -> intent.participant().equals(callerParticipant));
                if (store.collaborationProjection().activated() && !callerHasActiveIntent) {
                    Map<String, Object> claimRequired = new LinkedHashMap<>(collaboration);
                    claimRequired.put("claimsRequired", true);
                    claimRequired.put("reason", AgentReason.COORDINATION_INTENT_REQUIRED.value());
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COORDINATION_INTENT_REQUIRED, AgentNextAction.ENSURE_SESSION, claimRequired);
                }

                boolean coordinationActivated = store.collaborationProjection().activated();
                var callerIntent = store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(callerParticipant)).findFirst();
                List<CapabilityRequestRecord> ownerPending = capProj.findPendingForOwner(callerNodeId).stream()
                        .filter(candidate -> !coordinationActivated || callerIntent.map(intent -> intent.authorityLineageId().equals(candidate.authorityLineageId())).orElse(false)).toList();
                List<CapabilityRequestRecord> ownerAccepted = capProj.records().values().stream().filter(candidate -> candidate.matchesOwner(callerNodeId, binding.supervisorId(), binding.workerId()))
                        .filter(candidate -> candidate.state() == CapabilityLifecycleState.ACCEPTED || candidate.state() == CapabilityLifecycleState.IMPLEMENTING)
                        .filter(candidate -> !coordinationActivated || callerIntent.map(intent -> intent.authorityLineageId().equals(candidate.authorityLineageId())).orElse(false)).toList();

                // Slice 3: Check active integration projection states
                var taskCompProj = store.taskCompletionProjection();
                var activeAttemptOpt = taskCompProj.activeIntegrationAttempt();
                if (activeAttemptOpt.isPresent()) {
                    var att = activeAttemptOpt.get();
                    if ("conflict".equals(att.status())) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("pending", 1);
                        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_CONFLICT, AgentNextAction.REQUEST_HUMAN_HELP, result);
                    }
                }

                // Check if worker's task is waiting for dependencies
                var workerSnapshotOpt = taskCompProj.findLatestSnapshotForWorker(callerNodeId, callerWorkerId);
                if (workerSnapshotOpt.isPresent()) {
                    var state = taskCompProj.taskState(workerSnapshotOpt.get().taskId());
                    if (state == org.synesis.coordination.domain.task.TaskCompletionState.WAITING_FOR_DEPENDENCIES || state == org.synesis.coordination.domain.task.TaskCompletionState.SNAPSHOT_READY) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("pending", 1);
                        return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, result);
                    }
                }
                if (callerIntent.isPresent() && !callerIntent.get().knownDependencies().isEmpty()) {
                    var requestedCapabilities = capProj.findAllForRequester(callerNodeId).stream().filter(candidate -> candidate.matchesRequester(callerNodeId, binding.supervisorId(), binding.workerId()))
                            .map(CapabilityRequestRecord::capability).collect(Collectors.toSet());
                    String missingDependency = callerIntent.get().knownDependencies().stream().filter(dependency -> !requestedCapabilities.contains(dependency)).findFirst().orElse(null);
                    if (missingDependency != null) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("capability", missingDependency);
                        result.put("requiredFields", List.of("inputs", "output", "requiredBehavior", "acceptanceTests"));
                        result.put("pending", callerIntent.get().knownDependencies().size());
                        result.put("currentIntent", CollaborationResponseMapper.intentMap(callerIntent.get()));
                        result.put("knownDependencies", callerIntent.get().knownDependencies());
                        result.put("coordinationKind", "capability_request");
                        return new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, result);
                    }
                }
                if (!ownerPending.isEmpty()) {
                    CapabilityRequestRecord topReq = ownerPending.getFirst();
                    Map<String, Object> contractMap = new LinkedHashMap<>();
                    contractMap.put("inputs", topReq.contract().inputs());
                    contractMap.put("output", topReq.contract().output());
                    contractMap.put("requiredBehavior", topReq.contract().requiredBehavior());
                    contractMap.put("acceptanceTests", topReq.contract().acceptanceTests());

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("capabilityRequestHandle", topReq.handle().value());
                    result.put("capability", topReq.capability());
                    result.put("authorityLineageId", topReq.authorityLineageId().toString());
                    result.put("contract", contractMap);
                    result.put("pending", ownerPending.size());
                    return new AgentResponse(AgentStatus.READY, null, AgentNextAction.RESPOND_COORDINATION, result);
                }
                if (!ownerAccepted.isEmpty()) {
                    CapabilityRequestRecord topReq = ownerAccepted.getFirst();
                    Map<String, Object> contractMap = new LinkedHashMap<>();
                    contractMap.put("inputs", topReq.contract().inputs());
                    contractMap.put("output", topReq.contract().output());
                    contractMap.put("requiredBehavior", topReq.contract().requiredBehavior());
                    contractMap.put("acceptanceTests", topReq.contract().acceptanceTests());

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("capabilityRequestHandle", topReq.handle().value());
                    result.put("capability", topReq.capability());
                    result.put("authorityLineageId", topReq.authorityLineageId().toString());
                    result.put("contract", contractMap);
                    result.put("pending", ownerAccepted.size());
                    return new AgentResponse(AgentStatus.WAITING, AgentReason.IMPLEMENTATION_UNAVAILABLE, AgentNextAction.WAIT, result);
                }

                // Slice 2: owner must respond to a validation revision
                List<CapabilityRequestRecord> validationRevList = capProj.findValidationRevisionForOwner(callerNodeId).stream()
                        .filter(candidate -> !coordinationActivated || callerIntent.map(intent -> intent.authorityLineageId().equals(candidate.authorityLineageId())).orElse(false)).toList();
                if (!validationRevList.isEmpty()) {
                    CapabilityRequestRecord topReq = validationRevList.getFirst();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("capabilityRequestHandle", topReq.handle().value());
                    result.put("reason", topReq.reason() != null ? topReq.reason() : "Revision required by requester");
                    result.put("pending", validationRevList.size());
                    return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_FAILED, AgentNextAction.RESPOND_TO_VALIDATION_REVISION, result);
                }

                List<CapabilityRequestRecord> reqPending = capProj.findPendingForRequester(callerNodeId).stream().filter(candidate -> candidate.matchesRequester(callerNodeId, binding.supervisorId(), binding.workerId()))
                        .toList();
                if (!reqPending.isEmpty()) {
                    CapabilityRequestRecord topReq = null;
                    for (CapabilityRequestRecord r : reqPending) {
                        if (r.state() == CapabilityLifecycleState.REVISION_REQUESTED) {
                            topReq = r;
                            break;
                        } else if (r.state() == CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE && topReq == null) {
                            topReq = r;
                        } else if (r.state() == CapabilityLifecycleState.REJECTED && topReq == null) {
                            topReq = r;
                        } else if (r.state() == CapabilityLifecycleState.AWAITING_OWNER && topReq == null) {
                            topReq = r;
                        } else if (r.state() == CapabilityLifecycleState.ACCEPTED && topReq == null) {
                            topReq = r;
                        } else if (r.state() == CapabilityLifecycleState.IMPLEMENTING && topReq == null) {
                            topReq = r;
                        }
                    }
                    if (topReq != null) {
                        if (topReq.state() == CapabilityLifecycleState.REVISION_REQUESTED) {
                            Map<String, Object> contractMap = new LinkedHashMap<>();
                            contractMap.put("inputs", topReq.contract().inputs());
                            contractMap.put("output", topReq.contract().output());
                            contractMap.put("requiredBehavior", topReq.contract().requiredBehavior());
                            contractMap.put("acceptanceTests", topReq.contract().acceptanceTests());

                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle", topReq.handle().value());
                            result.put("contract", contractMap);
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.READY, AgentReason.REVISION_REQUIRED, AgentNextAction.REVISE_CAPABILITY_REQUEST, result);
                        } else if (topReq.state() == CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE) {
                            // Slice 2: requester must validate the available implementation
                            ImplementationRevisionRecord implRec = capProj.findLatestImplementation(topReq.handle().value()).orElse(null);
                            int revision = implRec != null ? implRec.revisionNumber() : 1;
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle", topReq.handle().value());
                            result.put("inboxItemId", UUID.nameUUIDFromBytes(("capability:" + topReq.handle().value()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
                            result.put("capability", topReq.capability());
                            result.put("authorityLineageId", topReq.authorityLineageId().toString());
                            result.put("revision", revision);
                            result.put("pending", reqPending.size());
                            result.put("reviewDecision", Map.of("required", true, "field", "result", "allowedResults", List.of("accepted", "revision_required"), "revisionReasonRequired", true));
                            return new AgentResponse(AgentStatus.READY, null, AgentNextAction.VALIDATE_IMPLEMENTATION, result);
                        } else if (topReq.state() == CapabilityLifecycleState.REJECTED) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle", topReq.handle().value());
                            result.put("reason", topReq.reason() != null ? topReq.reason() : "Capability request rejected by owner");
                            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.CAPABILITY_REJECTED, AgentNextAction.RETRY, result);
                        } else if (topReq.state() == CapabilityLifecycleState.AWAITING_OWNER) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle", topReq.handle().value());
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
                        } else {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle", topReq.handle().value());
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.WAITING, AgentReason.IMPLEMENTATION_UNAVAILABLE, AgentNextAction.WAIT, result);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        List<CoordinationItem> items = loadCoordinationItems(assignedWorktree, location.root(), request.provider());
        if (items.isEmpty()) {
            Map<String, Object> collaboration = new LinkedHashMap<>(collaborationDetailsForRequest(location, request));
            ProjectCommandDiagnostics.Report command = ProjectCommandDiagnostics.inspect(AdministrativeStateLocator.applicationStateRoot().resolve("commands"));
            Map<String, Object> durableCommands = new LinkedHashMap<>();
            durableCommands.put("namespacePresent", command.present());
            durableCommands.put("formatValid", command.formatValid());
            durableCommands.put("newerObjects", command.newerObjectCount());
            durableCommands.put("olderFormats", command.olderFormatCount());
            durableCommands.put("permanentLocks", command.permanentLockCount());
            durableCommands.put("scopes", command.scopeCount());
            durableCommands.put("anchors", command.anchorCount());
            durableCommands.put("requests", command.requestCount());
            durableCommands.put("liveAtCapacity", command.liveAtCapacityCount());
            durableCommands.put("deadAnchors", command.deadAnchorCount());
            durableCommands.put("terminalEligible", command.eligibleTerminalCount());
            durableCommands.put("pinnedEvidence", command.pinnedEvidenceCount());
            durableCommands.put("staleIndex", command.staleIndexCount());
            durableCommands.put("enumerationComplete", command.enumerationComplete());
            durableCommands.put("terminalHistoryCompactions", command.terminalHistoryCompactionCount());
            durableCommands.put("leaseGapRevisionMismatches", command.leaseGapRevisionMismatchCount());
            durableCommands.put("admissionRestarts", command.admissionRestartCount());
            durableCommands.put("cleanCloseDetachBlocked", command.cleanCloseDetachBlockedCount());
            durableCommands.put("deferredMutations", command.deferredMutationCount());
            collaboration.put("durableCommands", durableCommands);
            return new AgentResponse(AgentStatus.READY, null, null, collaboration);
        }

        // Priority Order:
        // 1. Safety Failure
        // 2. Invalidated Dependency
        // 3. Owner Request Pending
        // 4. Capability Description Required (Needs Capability)
        // 5. Validation Required
        // 6. Waiting for Owner
        CoordinationItem topItem = null;
        int topPriority = Integer.MAX_VALUE;

        for (CoordinationItem item : items) {
            int p = priorityOf(item.type());
            if (p < topPriority) {
                topPriority = p;
                topItem = item;
            } else if (p == topPriority && topItem != null && item.sequence() < topItem.sequence()) {
                topItem = item;
            }
        }

        int pendingCount = items.size();

        if (topItem == null) {
            return AgentResponse.ready("isolated", 0);
        }

        return switch (topItem.type().toUpperCase(Locale.ROOT)) {
            case "SAFETY_FAILURE" -> new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
            case "DEPENDENCY_INVALIDATED" -> {
                Map<String, Object> res = new LinkedHashMap<>();
                if (topItem.capability() != null) {
                    res.put("capability", topItem.capability());
                }
                res.put("pending", pendingCount);
                yield new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.DEPENDENCY_INVALIDATED, AgentNextAction.RETRY, res);
            }
            case "OWNER_REQUEST" -> {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("capability", topItem.capability() != null ? topItem.capability() : "unknown");
                req.put("inputs", topItem.details().getOrDefault("inputs", "..."));
                req.put("output", topItem.details().getOrDefault("output", "..."));
                req.put("behavior", topItem.details().getOrDefault("behavior", "..."));
                req.put("acceptanceTest", topItem.details().getOrDefault("acceptanceTest", "..."));

                Map<String, Object> res = new LinkedHashMap<>();
                res.put("request", req);
                res.put("pending", pendingCount);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_REQUEST_PENDING, null, res);
            }
            case "NEEDS_CAPABILITY" -> {
                List<String> reqFields = List.of("inputs", "output", "behavior", "acceptanceTest");
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("capability", topItem.capability());
                res.put("requiredFields", reqFields);
                res.put("pending", pendingCount);
                yield new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, res);
            }
            case "VALIDATION_REQUIRED" -> {
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("capability", topItem.capability());
                res.put("pending", pendingCount);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, null, res);
            }
            case "WAITING_FOR_OWNER" -> {
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("pending", pendingCount);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, res);
            }
            default -> AgentResponse.ready("isolated", pendingCount);
        };
    }

    private AgentResponse completedReviewAction(ProjectLocation location, String sessionId) {
        try {
            Path coordination = location.root().resolve(".synesis/coordination");
            if (!Files.exists(coordination.resolve("events"))) {
                return null;
            }
            PredictionEventStore store = new PredictionEventStore(coordination, location.projectId());
            String participant = WorkspaceCollaborationService.participantHandle(sessionId);
            Map<String, Object> baseCollaboration = collaborationDetails(store, sessionId);
            AgentResponse pendingReviewResponse = AgentReviewActionProjection.pendingReviewRequestResponse(baseCollaboration, store, participant);
            if (pendingReviewResponse != null) {
                return pendingReviewResponse;
            }
            Set<UUID> completedGroups = AgentReviewActionProjection.completedParticipantWorkGroups(store, participant);
            if (completedGroups.isEmpty()) {
                return null;
            }
            Map<String, Object> collaboration = collaborationDetails(store, sessionId, completedGroups);
            AgentResponse reviewResponse = AgentReviewActionProjection.reviewActionResponse(collaboration, true);
            if (reviewResponse != null) {
                return reviewResponse;
            }
            boolean groupActive = completedGroups.stream().anyMatch(groupId -> store.workGroupProjection().group(groupId).filter(group -> group.status() == WorkGroup.Status.ACTIVE).isPresent());
            return groupActive ? new AgentResponse(AgentStatus.WAITING, null, AgentNextAction.WAIT, collaboration) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private AgentResponse staleCoordinationAction(ProjectLocation location, ProviderSessionBindingService.Binding binding, boolean completionRequested) {
        try {
            Path coordination = location.root().resolve(".synesis/coordination");
            if (!Files.exists(coordination.resolve("events"))) {
                return null;
            }
            PredictionEventStore store = new PredictionEventStore(coordination, location.projectId());
            Map<String, Object> collaboration = collaborationDetails(store, binding.sessionId());
            String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            AgentResponse pendingReviewResponse = AgentReviewActionProjection.pendingReviewRequestResponse(collaboration, store, participant);
            if (pendingReviewResponse != null) {
                return pendingReviewResponse;
            }
            AgentResponse reviewResponse = AgentReviewActionProjection.reviewActionResponse(collaboration, true);
            if (reviewResponse != null) {
                return reviewResponse;
            }
            AgentResponse reviewerPendingResponse = AgentReviewActionProjection.reviewerPendingAction(store, participant);
            if (reviewerPendingResponse != null) {
                return reviewerPendingResponse;
            }
            Path assignedWorktree = binding.worktreePath() == null ? null : Path.of(binding.worktreePath());
            AgentResponse revisionResponse = revisionRequiredAction(store, participant, assignedWorktree, snapshotService);
            if (revisionResponse != null) {
                return revisionResponse;
            }
            AgentResponse reviewPendingResponse = reviewPendingAction(store, participant);
            if (reviewPendingResponse != null) {
                return reviewPendingResponse;
            }

            @SuppressWarnings("unchecked") List<Map<String, Object>> pendingCoordination = (List<Map<String, Object>>) collaboration.get("pendingCoordination");
            Map<String, Object> reviewAcceptance = AgentReviewActionProjection.reviewAcceptanceAction(pendingCoordination);
            if (reviewAcceptance != null) {
                Map<String, Object> ownerAction = new LinkedHashMap<>(collaboration);
                ownerAction.putAll(reviewAcceptance);
                return new AgentResponse(AgentStatus.READY, AgentReason.OWNER_REQUEST_PENDING, AgentNextAction.RESPOND_COORDINATION, ownerAction);
            }

            Map<String, Object> pendingReviewGrant = pendingReviewGrantAction(store, participant, assignedWorktree, snapshotService, binding.baseCommit());
            if (pendingReviewGrant != null) {
                Map<String, Object> ownerWait = new LinkedHashMap<>(collaboration);
                ownerWait.putAll(pendingReviewGrant);
                return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, ownerWait);
            }
            if (completionRequested) {
                NodeIdentity callerIdentity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
                Map<String, Object> publishedSnapshotCompletion = publishedSnapshotCompletionAction(store, participant, callerIdentity.nodeId(), binding.supervisorId(), binding.workerId());
                if (publishedSnapshotCompletion != null) {
                    return new AgentResponse(AgentStatus.READY, AgentReason.SNAPSHOT_PUBLICATION_REQUIRED, AgentNextAction.FINISH_LANE, publishedSnapshotCompletion);
                }
                Map<String, Object> publicationAction = snapshotPublicationAction(store,
                        participant,
                        assignedWorktree,
                        snapshotService,
                        binding.baseCommit(),
                        callerIdentity.nodeId(),
                        binding.supervisorId(),
                        binding.workerId());
                if (publicationAction != null) {
                    return new AgentResponse(AgentStatus.READY, AgentReason.SNAPSHOT_PUBLICATION_REQUIRED, AgentNextAction.FINISH_LANE, publicationAction);
                }
                Map<String, Object> noChangeAction = noChangeCompletionAction(store, participant, callerIdentity.nodeId(), binding.supervisorId(), binding.workerId(), assignedWorktree, snapshotService);
                if (noChangeAction != null) {
                    return new AgentResponse(AgentStatus.READY, null, AgentNextAction.FINISH_LANE, noChangeAction);
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Builds a JSON-safe collaboration discovery and pending-request projection.
     */
    private Map<String, Object> collaborationDetailsForRequest(ProjectLocation location, NextActionRequest request) {
        try {
            Path coordDir = location.root().resolve(".synesis/coordination");
            if (Files.exists(coordDir.resolve("events"))) {
                var store = new PredictionEventStore(coordDir, location.projectId());
                String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.connectionInstanceId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var binding = new ProviderSessionBindingService().list(location, request.provider()).stream().filter(candidate -> fingerprint.equals(candidate.providerInstanceFingerprint())).findFirst().orElse(null);
                if (binding != null) {
                    return collaborationDetails(store, binding.sessionId());
                }
                return collaborationDetails(store, "");
            }
        } catch (Exception ignored) {
        }
        return Map.of("workspace", "isolated", "pending", 0, "participants", List.of(), "intents", List.of(), "pendingCoordination", List.of());
    }

    /**
     * Converts collaboration records to a provider-safe next-action payload.
     */
    private Map<String, Object> collaborationDetails(PredictionEventStore store, String sessionId) {
        return collaborationDetails(store, sessionId, null);
    }

    private Map<String, Object> collaborationDetails(PredictionEventStore store, String sessionId, Set<UUID> reviewGroupFilter) {
        String participantId = sessionId == null || sessionId.isBlank() ? "" : WorkspaceCollaborationService.participantHandle(sessionId);
        List<Map<String, Object>> intents = store.collaborationProjection().activeIntents().stream().map(CollaborationResponseMapper::intentMap).toList();
        Map<String, Object> currentIntent = store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participantId)).map(CollaborationResponseMapper::intentMap).findFirst()
                .orElse(null);
        List<Map<String, Object>> participants = store.collaborationProjection().participants().stream().map(CollaborationResponseMapper::participantMap).toList();
        List<Map<String, Object>> pending = store.collaborationProjection().requests().stream().filter(request -> request.status() == CoordinationRequest.Status.PENDING)
                .filter(request -> !store.collaborationProjection().inboxAcknowledged(request.requestId())).filter(request -> participantId.isBlank() || request.target().equals(participantId))
                .map(CollaborationResponseMapper::requestMap).toList();
        List<Map<String, Object>> enrichedPending = pending.stream().map(request -> AgentReviewActionProjection.enrichPendingRequest(request, store)).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspace", "isolated");
        result.put("pending", enrichedPending.size());
        result.put("participants", participants);
        result.put("intents", intents);
        result.put("groups", store.workGroupProjection().groups().stream().map(CollaborationResponseMapper::workGroupMap).toList());
        result.put("grants", store.workGroupProjection().grants().stream().map(CollaborationResponseMapper::laneGrantMap).toList());
        result.put("snapshots", store.taskCompletionProjection().allSnapshots().stream().map(snapshot -> CollaborationResponseMapper.snapshotMap(snapshot, store)).toList());
        result.put("currentParticipant", participantId);
        result.put("currentIntent", currentIntent);
        result.put("pendingCoordination", enrichedPending);
        result.put("continuationCandidates", continuationCandidates(store, participantId));
        List<Map<String, Object>> reviewCandidates = AgentReviewActionProjection.reviewCandidates(store);
        result.put("reviewCandidates", reviewCandidates);
        result.put("reviewCandidateSelectionRequired", !reviewCandidates.isEmpty());
        result.put("actionableReviewCandidates", AgentReviewActionProjection.actionableReviewCandidates(store, participantId, reviewCandidates));
        if (!reviewCandidates.isEmpty()) {
            result.put("reviewCandidateSelectionInstruction", "Choose an exact candidate explicitly; Synesis does not select one for you.");
        }
        result.put("reviewActions", AgentReviewActionProjection.reviewActions(store, participantId, reviewGroupFilter));
        result.put("claimConflicts", List.of());
        return result;
    }

    /**
     * Represents a pending coordination item.
     *
     * @param type       item type (SAFETY_FAILURE, DEPENDENCY_INVALIDATED, OWNER_REQUEST,
     *                   NEEDS_CAPABILITY, VALIDATION_REQUIRED, WAITING_FOR_OWNER)
     * @param capability capability identifier
     * @param workerId   target worker identifier or provider
     * @param details    additional detail payload
     * @param sequence   sequence number for deterministic ordering
     */
    private record CoordinationItem(String type, String capability, String workerId, Map<String, Object> details, long sequence) {

    }
}
