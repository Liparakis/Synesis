package org.synesis.workspace.application.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.ProviderSessionTerminalizationService;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.command.ProjectCommandDiagnostics;

/**
 * Application service for retrieving the single highest-priority actionable coordination item
 * for an ambient MCP session.
 *
 * @since 1.0
 */
@SuppressWarnings({"DuplicatedCode", "ExtractMethodRecommender"})
public final class AgentNextActionService {

    private final ProjectApplicationService projectService;
    private final WorkspaceReadinessService readinessService;
    private final AgentWorkflowReducer workflowReducer;
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

    private static boolean sessionIsTerminal(ProjectApplicationService.ProjectLocation location,
            ProviderSessionBindingService.Binding binding) throws Exception {
        return "TERMINAL".equals(binding.status())
                || ProviderSessionTerminalizationService.isSessionTerminal(location, binding.sessionId());
    }

    private static AgentResponse terminalSessionResponse(String sessionId) {
        return new AgentResponse(AgentStatus.COMPLETED, null, null,
                Map.of("state", "SESSION_TERMINAL", "lane", sessionId));
    }

    /**
     * Returns whether the exact bound session owns an active no-change lane.
     */
    private static boolean hasActiveNoChangeIntent(
            ProjectApplicationService.ProjectLocation location,
            ProviderSessionBindingService.Binding binding) {
        try {
            Path coordination = location.root()
                    .resolve(".synesis/coordination");
            if (!Files.exists(coordination.resolve("events"))) {
                return false;
            }
            var store = new org.synesis.coordination.persistence.PredictionEventStore(
                    coordination, location.projectId());
            String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            return store.collaborationProjection()
                    .activeIntents()
                    .stream()
                    .anyMatch(intent -> intent.participant()
                            .equals(participant)
                            && intent.completionMode() == WorkIntent.CompletionMode.NO_CHANGE_ALLOWED);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Projects the exact correction revision after a reviewed snapshot was rejected.
     */
    private static AgentResponse revisionRequiredAction(
            org.synesis.coordination.persistence.PredictionEventStore store, String participantId) {
        if (participantId == null || participantId.isBlank()) {
            return null;
        }
        for (WorkIntent intent : store.collaborationProjection()
                .activeIntents()) {
            if (!participantId.equals(intent.participant()) || intent.role() != WorkIntent.Role.PRODUCER) {
                continue;
            }
            // The owner must admit a fresh review request before the
            // implementer is told to publish the correction.  Otherwise the
            // revision projection would hide the pending request and leave
            // the reviewer without a lawful current-epoch grant.
            boolean pendingReviewAdmission = store.collaborationProjection()
                    .requests()
                    .stream()
                    .anyMatch(request -> request.status() == CoordinationRequest.Status.PENDING
                            && request.kind() == CoordinationRequest.Kind.REVIEW
                            && request.target()
                            .equals(participantId));
            if (pendingReviewAdmission) {
                continue;
            }
            TaskSnapshotRecord current = store.taskCompletionProjection()
                    .findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version())
                    .orElse(null);
            if (current != null) {
                continue;
            }
            TaskSnapshotRecord rejected = store.taskCompletionProjection()
                    .allSnapshots()
                    .stream()
                    .filter(snapshot -> snapshot.taskId()
                            .equals(intent.taskId()))
                    .filter(snapshot -> snapshot.provenance()
                            .laneId()
                            .equals(intent.intentId()))
                    .filter(snapshot -> snapshot.provenance()
                            .authorityLineageId()
                            .equals(intent.authorityLineageId()))
                    .filter(snapshot -> snapshot.provenance()
                            .claimEpoch() < intent.version())
                    .filter(snapshot -> store.taskCompletionProjection()
                            .snapshotState(snapshot.snapshotId())
                            .orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.REVIEW_REJECTED)
                    .max(java.util.Comparator.comparingLong(snapshot -> snapshot.provenance()
                            .claimEpoch()))
                    .orElse(null);
            if (rejected == null || hasCurrentReviewGrant(store, intent)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intentId",
                    intent.intentId()
                            .toString());
            payload.put("workGroupId",
                    intent.workGroupId()
                            .toString());
            payload.put("claimEpoch", intent.version());
            payload.put("workGroupVersion",
                    store.workGroupProjection()
                            .group(intent.workGroupId())
                            .map(WorkGroup::version)
                            .orElse(0L));
            payload.put("expectedRevision", store.headSequence());
            payload.put("participant", participantId);
            payload.put("authorityLineageId",
                    intent.authorityLineageId()
                            .toString());
            payload.put("baseCommit", intent.baseCommit());
            payload.put("selectors",
                    intent.selectors()
                            .stream()
                            .map(AgentNextActionService::selectorMap)
                            .toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("state", "REVISION_REQUIRED");
            result.put("revisionRequired", true);
            result.put("currentIntent", intentMap(intent));
            result.put("rejectedSnapshot", snapshotMap(rejected));
            result.put("latestRejectedSnapshotId", rejected.snapshotId());
            result.put("workGroupId",
                    intent.workGroupId()
                            .toString());
            result.put("claimEpoch", intent.version());
            result.put("authorityLineageId",
                    intent.authorityLineageId()
                            .toString());
            result.put("nextProtocolAction", "implement");
            result.put("nextProtocolKind", "implementation_revision");
            result.put("nextProtocolPayload", payload);
            return new AgentResponse(AgentStatus.READY, AgentReason.REVISION_REQUIRED,
                    null, result);
        }
        return null;
    }

    /**
     * Projects the wait state for an implementer whose exact snapshot awaits review.
     */
    private static AgentResponse reviewPendingAction(
            org.synesis.coordination.persistence.PredictionEventStore store, String participantId) {
        if (participantId == null || participantId.isBlank()) {
            return null;
        }
        for (WorkIntent intent : store.collaborationProjection()
                .activeIntents()) {
            if (!participantId.equals(intent.participant()) || intent.role() != WorkIntent.Role.PRODUCER) {
                continue;
            }
            TaskSnapshotRecord snapshot = store.taskCompletionProjection()
                    .findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version())
                    .orElse(null);
            if (snapshot == null || store.taskCompletionProjection()
                    .snapshotState(snapshot.snapshotId())
                    .orElse(TaskCompletionState.ACTIVE) != TaskCompletionState.REVIEW_PENDING) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("snapshotId", snapshot.snapshotId());
            payload.put("intentId",
                    intent.intentId()
                            .toString());
            payload.put("workGroupId",
                    intent.workGroupId()
                            .toString());
            payload.put("claimEpoch", intent.version());
            payload.put("participant", participantId);
            payload.put("authorityLineageId",
                    intent.authorityLineageId()
                            .toString());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("state", TaskCompletionState.REVIEW_PENDING.name());
            result.put("reviewPending", true);
            result.put("snapshot", snapshotMap(snapshot));
            result.put("currentIntent", intentMap(intent));
            result.put("workGroupId",
                    intent.workGroupId()
                            .toString());
            result.put("claimEpoch", intent.version());
            result.put("nextProtocolAction", "wait");
            result.put("nextProtocolKind", "review_validation");
            result.put("nextProtocolPayload", payload);
            return new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED,
                    AgentNextAction.WAIT, result);
        }
        return null;
    }

    /**
     * Returns whether the current lane revision has an exact review grant.
     */
    private static boolean hasCurrentReviewGrant(
            org.synesis.coordination.persistence.PredictionEventStore store, WorkIntent intent) {
        return store.workGroupProjection()
                .grants()
                .stream()
                .filter(grant -> grant.workGroupId()
                        .equals(intent.workGroupId()))
                .filter(grant -> grant.targetIntentId()
                        .equals(intent.intentId()))
                .filter(grant -> grant.claimEpoch() == intent.version())
                .anyMatch(grant -> store.workGroupProjection()
                        .grantAvailable(grant.grantId())
                        || store.workGroupProjection()
                        .grantConsumed(grant.grantId()));
    }

    private static AgentResponse reviewActionResponse(
            Map<String, Object> collaboration, boolean reviewOnly) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviewActions =
                (List<Map<String, Object>>) collaboration.get("reviewActions");
        if (reviewActions == null || reviewActions.isEmpty()) {
            return null;
        }
        Map<String, Object> review = reviewActions.getFirst();
        String protocolAction = String.valueOf(review.get("nextProtocolAction"));
        AgentNextAction next = protocolNextAction(protocolAction);
        Map<String, Object> projection = new LinkedHashMap<>(collaboration);
        if (reviewOnly) {
            projection.put("reviewOnly", true);
        }
        projection.put("nextProtocolAction", review.get("nextProtocolAction"));
        projection.put("nextProtocolKind", review.get("nextProtocolKind"));
        projection.put("nextProtocolPayload", review.get("nextProtocolPayload"));
        if (review.containsKey("reviewDecision")) {
            projection.put("reviewDecision", review.get("reviewDecision"));
        }
        if (review.containsKey("reviewAccess")) {
            projection.put("reviewAccess", review.get("reviewAccess"));
        }
        return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED,
                next, projection);
    }

    /**
     * Projects a wait while a declared reviewer has no resolved producer target.
     */
    private static AgentResponse reviewerPendingAction(
            org.synesis.coordination.persistence.PredictionEventStore store, String participantId) {
        for (WorkIntent reviewer : store.collaborationProjection()
                .activeIntents()) {
            if (!reviewer.participant()
                    .equals(participantId)
                    || reviewer.role() != WorkIntent.Role.REVIEWER
                    || !hasUnresolvedReviewObligation(store, reviewer)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("workGroupId",
                    reviewer.workGroupId()
                            .toString());
            payload.put("reviewerParticipant", participantId);
            payload.put("reviewTargets",
                    reviewer.reviewTargetSelectors()
                            .stream()
                            .map(AgentNextActionService::selectorMap)
                            .toList());
            payload.put("reason", "WAIT_FOR_ELIGIBLE_PRODUCER");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("state", "REVIEWER_PENDING");
            result.put("reviewerPending", true);
            result.put("currentIntent", intentMap(reviewer));
            result.put("workGroupId",
                    reviewer.workGroupId()
                            .toString());
            result.put("reviewerParticipant", participantId);
            result.put("reviewTargets",
                    reviewer.reviewTargetSelectors()
                            .stream()
                            .map(AgentNextActionService::selectorMap)
                            .toList());
            result.put("nextProtocolAction", "wait");
            result.put("nextProtocolKind", "review_admission");
            result.put("nextProtocolPayload", payload);
            return new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED,
                    AgentNextAction.WAIT, result);
        }
        return null;
    }

    /**
     * Returns whether a reviewer still lacks a resolved current producer review.
     */
    static boolean hasUnresolvedReviewObligation(
            org.synesis.coordination.persistence.PredictionEventStore store, WorkIntent reviewer) {
        if (reviewer.role() != WorkIntent.Role.REVIEWER) {
            return false;
        }
        boolean unresolvedGrant = store.workGroupProjection()
                .grants()
                .stream()
                .filter(grant -> grant.workGroupId()
                        .equals(reviewer.workGroupId()))
                .filter(grant -> grant.targetParticipant()
                        .equals(reviewer.participant()))
                .anyMatch(grant -> store.workGroupProjection()
                        .reviewValidationForGrant(grant.grantId())
                        .isEmpty());
        if (unresolvedGrant) {
            return true;
        }
        List<WorkIntent> producers = store.collaborationProjection()
                .activeIntents()
                .stream()
                .filter(intent -> intent.role() == WorkIntent.Role.PRODUCER)
                .filter(intent -> intent.workGroupId()
                        .equals(reviewer.workGroupId()))
                .filter(intent -> !intent.participant()
                        .equals(reviewer.participant()))
                .toList();
        List<WorkIntent> matching = reviewer.reviewTargetSelectors()
                .isEmpty()
                ? producers
                : producers.stream()
                  .filter(producer -> reviewTargetsMatch(reviewer, producer))
                  .toList();
        if (matching.isEmpty()) {
            return store.workGroupProjection()
                    .grants()
                    .stream()
                    .filter(grant -> grant.workGroupId()
                            .equals(reviewer.workGroupId()))
                    .filter(grant -> grant.targetParticipant()
                            .equals(reviewer.participant()))
                    .noneMatch(grant -> store.workGroupProjection()
                            .reviewValidationForGrant(grant.grantId())
                            .isPresent());
        }
        return matching.stream()
                .anyMatch(producer -> !hasCompletedReview(store, reviewer, producer));
    }

    /**
     * Returns whether the reviewer has a terminal validation for the producer revision.
     */
    private static boolean hasCompletedReview(
            org.synesis.coordination.persistence.PredictionEventStore store,
            WorkIntent reviewer, WorkIntent producer) {
        return store.workGroupProjection()
                .grants()
                .stream()
                .filter(grant -> grant.workGroupId()
                        .equals(reviewer.workGroupId()))
                .filter(grant -> grant.targetParticipant()
                        .equals(reviewer.participant()))
                .filter(grant -> grant.targetIntentId()
                        .equals(producer.intentId()))
                .filter(grant -> grant.claimEpoch() == producer.version())
                .anyMatch(grant -> store.workGroupProjection()
                        .reviewValidationForGrant(grant.grantId())
                        .isPresent());
    }

    private static AgentResponse pendingReviewRequestResponse(
            Map<String, Object> collaboration,
            org.synesis.coordination.persistence.PredictionEventStore store,
            String participantId) {
        if (participantId == null || participantId.isBlank()) {
            return null;
        }
        for (CoordinationRequest request : store.collaborationProjection()
                .requests()) {
            if (request.status() != CoordinationRequest.Status.PENDING
                    || request.kind() != CoordinationRequest.Kind.REVIEW
                    || !request.requester()
                    .equals(participantId)) {
                continue;
            }
            Map<String, Object> requestProjection = enrichPendingRequest(requestMap(request), store);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("requestId",
                    request.requestId()
                            .toString());
            payload.put("intentId",
                    request.conflictingIntentId()
                            .toString());
            payload.put("target", request.target());
            payload.put("status",
                    request.status()
                            .name());
            if (requestProjection.get("workGroupId") != null) {
                payload.put("workGroupId", requestProjection.get("workGroupId"));
            }
            if (requestProjection.get("claimEpoch") != null) {
                payload.put("claimEpoch", requestProjection.get("claimEpoch"));
            }

            Map<String, Object> result = new LinkedHashMap<>(collaboration);
            result.put("reviewRequestPending", true);
            result.put("reviewRequest", requestProjection);
            result.put("nextProtocolAction", "wait");
            result.put("nextProtocolKind", "review_admission");
            result.put("nextProtocolPayload", payload);
            result.put("instruction", "Poll get_next_action for the owner response; do not resubmit this request.");
            return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING,
                    AgentNextAction.WAIT, result);
        }
        return null;
    }

    private static Set<UUID> completedParticipantWorkGroups(
            org.synesis.coordination.persistence.PredictionEventStore store, String participant) {
        Set<UUID> groups = new LinkedHashSet<>();
        for (TaskSnapshotRecord snapshot : store.taskCompletionProjection()
                .allSnapshots()) {
            if (participant.equals(snapshot.provenance()
                    .participant())) {
                groups.add(snapshot.provenance()
                        .workGroupId());
            }
        }
        return Set.copyOf(groups);
    }

    private static Map<String, Object> enrichPendingRequest(Map<String, Object> request,
            org.synesis.coordination.persistence.PredictionEventStore store) {
        Map<String, Object> enriched = new LinkedHashMap<>(request);
        Object conflictingIntent = request.get("conflictingIntentId");
        if (conflictingIntent instanceof String intentId) {
            store.collaborationProjection()
                    .activeIntents()
                    .stream()
                    .filter(intent -> intent.intentId()
                            .toString()
                            .equals(intentId))
                    .findFirst()
                    .ifPresent(intent -> {
                        enriched.put("intentId",
                                intent.intentId()
                                        .toString());
                        enriched.put("workGroupId",
                                intent.workGroupId()
                                        .toString());
                        enriched.put("claimEpoch", intent.version());
                    });
        }
        return enriched;
    }

    private static Map<String, Object> reviewAcceptanceAction(List<Map<String, Object>> pendingCoordination) {
        for (Map<String, Object> request : pendingCoordination) {
            if (!"REVIEW".equals(request.get("kind"))) {
                continue;
            }
            Object requestId = request.get("requestId");
            Object intentId = request.get("intentId");
            Object workGroupId = request.get("workGroupId");
            Object claimEpoch = request.get("claimEpoch");
            if (!(requestId instanceof String) || !(intentId instanceof String)
                    || !(workGroupId instanceof String) || !(claimEpoch instanceof Number)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("coordinationRequest", requestId);
            payload.put("coordinationStatus", CoordinationRequest.Status.ACCEPTED.name());
            payload.put("proposal", "admitted");
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("requestId", requestId);
            context.put("kind", request.get("kind"));
            context.put("workGroupId", workGroupId);
            context.put("intentId", intentId);
            context.put("claimEpoch", claimEpoch);
            context.put("requester", request.get("requester"));
            context.put("target", request.get("target"));
            return Map.of(
                    "nextProtocolAction", "respond_coordination",
                    "nextProtocolKind", "coordination_response",
                    "nextProtocolPayload", payload,
                    "nextProtocolContext", context);
        }
        return null;
    }

    private static List<Map<String, Object>> reviewActions(
            org.synesis.coordination.persistence.PredictionEventStore store, String participantId,
            Set<UUID> reviewGroupFilter) {
        List<Map<String, Object>> actions = new ArrayList<>();
        var projection = store.workGroupProjection();
        List<WorkIntent> activeIntents = store.collaborationProjection()
                .activeIntents();
        for (LaneGrant grant : projection.grants()) {
            if (!grant.targetParticipant()
                    .equals(participantId)) {
                continue;
            }
            if (reviewGroupFilter != null && !reviewGroupFilter.contains(grant.workGroupId())) {
                continue;
            }
            WorkIntent reviewedIntent = activeIntents.stream()
                    .filter(intent -> intent.intentId()
                            .equals(grant.targetIntentId()))
                    .findFirst()
                    .orElse(null);
            TaskSnapshotRecord snapshot = store.taskCompletionProjection()
                    .allSnapshots()
                    .stream()
                    .filter(value -> value.provenance()
                            .workGroupId()
                            .equals(grant.workGroupId()))
                    .filter(value -> value.provenance()
                            .laneId()
                            .equals(grant.targetIntentId()))
                    .filter(value -> value.provenance()
                            .claimEpoch() == grant.claimEpoch())
                    .findFirst()
                    .orElse(null);
            if (reviewedIntent == null && snapshot == null) {
                continue;
            }
            if (reviewedIntent != null && reviewedIntent.role() != WorkIntent.Role.PRODUCER) {
                continue;
            }
            String reviewedParticipant = reviewedIntent == null
                    ? snapshot.provenance()
                      .participant() : reviewedIntent.participant();
            boolean activeReviewer = activeIntents.stream()
                    .anyMatch(intent -> intent.participant()
                            .equals(participantId)
                            && intent.workGroupId()
                            .equals(grant.workGroupId())
                            && intent.role() == WorkIntent.Role.REVIEWER
                            && (reviewedIntent != null
                            ? reviewTargetsMatch(intent, reviewedIntent)
                            : reviewTargetsMatch(intent, snapshot)));
            boolean unannouncedReviewer = activeIntents.stream()
                    .noneMatch(intent -> intent.participant()
                            .equals(participantId));
            boolean explicitReviewGrant = hasExplicitReviewGrant(store, grant, participantId);
            boolean completedReviewer = reviewGroupFilter != null
                    && reviewGroupFilter.contains(grant.workGroupId());
            if (!activeReviewer && !unannouncedReviewer && !explicitReviewGrant && !completedReviewer) {
                continue;
            }
            if (snapshot == null && !projection.grantAvailable(grant.grantId())) {
                Map<String, Object> waiting = new LinkedHashMap<>();
                waiting.put("state", "SNAPSHOT_PENDING");
                waiting.put("nextProtocolAction", "wait");
                waiting.put("nextProtocolKind", "review_validation");
                waiting.put("nextProtocolPayload",
                        Map.of("grantId",
                                grant.grantId()
                                        .toString(),
                                "workGroupId",
                                grant.workGroupId()
                                        .toString(),
                                "reviewedIntentId",
                                grant.targetIntentId()
                                        .toString(),
                                "reviewedParticipantId",
                                reviewedParticipant,
                                "reviewerParticipant",
                                grant.targetParticipant(),
                                "snapshotRequired",
                                true));
                waiting.put("grant", laneGrantMap(grant));
                actions.add(waiting);
                continue;
            }
            if (projection.reviewValidationForGrant(grant.grantId())
                    .isPresent()) {
                continue;
            }
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("state", projection.grantAvailable(grant.grantId()) ? "GRANT_AVAILABLE" : "VALIDATION_REQUIRED");
            action.put("nextProtocolAction", projection.grantAvailable(grant.grantId())
                    ? "request_coordination" : "review_decision");
            action.put("nextProtocolKind", projection.grantAvailable(grant.grantId())
                    ? "work_group_join" : "review_validation");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("grantId",
                    grant.grantId()
                            .toString());
            payload.put("intentId",
                    grant.targetIntentId()
                            .toString());
            payload.put("reviewedIntentId",
                    grant.targetIntentId()
                            .toString());
            payload.put("reviewedParticipantId", reviewedParticipant);
            payload.put("reviewerParticipant", grant.targetParticipant());
            payload.put("claimEpoch", grant.claimEpoch());
            if (projection.grantAvailable(grant.grantId())) {
                payload.put("workGroupId",
                        grant.workGroupId()
                                .toString());
                payload.put("targetParticipant", grant.targetParticipant());
            } else {
                TaskSnapshotRecord reviewedSnapshot = Objects.requireNonNull(snapshot, "review snapshot");
                payload.put("snapshotId", reviewedSnapshot.snapshotId());
                action.put("reviewDecision", Map.of(
                        "required", true,
                        "field", "result",
                        "allowedResults", List.of("accepted", "rejected"),
                        "rejectionReasonRequired", true));
                action.put("reviewAccess", Map.of(
                        "workspace",
                        "immutable_review_snapshot",
                        "snapshotId",
                        reviewedSnapshot.snapshotId(),
                        "commitSha",
                        snapshot.commitSha(),
                        "readTools",
                        List.of("read_file", "run_command"),
                        "writeLaneProtected",
                        true,
                        "instruction",
                        "Inspect the immutable snapshot with read_file and run_command before deciding; do not patch it."));
            }
            action.put("nextProtocolPayload", payload);
            action.put("grant", laneGrantMap(grant));
            if (snapshot != null) {
                action.put("snapshot", snapshotMap(snapshot));
            }
            actions.add(action);
        }
        if (actions.isEmpty() && !participantId.isBlank()) {
            Set<String> completedReviewRevisions = new LinkedHashSet<>();
            Set<UUID> pendingReviewIntents = new LinkedHashSet<>();
            for (LaneGrant grant : projection.grants()) {
                if (grant.targetParticipant()
                        .equals(participantId)
                        && projection.reviewValidationForGrant(grant.grantId())
                        .isPresent()) {
                    completedReviewRevisions.add(grant.targetIntentId() + ":" + grant.claimEpoch());
                }
            }
            for (CoordinationRequest request : store.collaborationProjection()
                    .requests()) {
                if (request.status() == CoordinationRequest.Status.PENDING
                        && request.kind() == CoordinationRequest.Kind.REVIEW
                        && request.requester()
                        .equals(participantId)) {
                    pendingReviewIntents.add(request.conflictingIntentId());
                }
            }
            List<WorkIntent> callerReviewers = activeIntents.stream()
                    .filter(intent -> intent.participant()
                            .equals(participantId))
                    .filter(intent -> intent.role() == WorkIntent.Role.REVIEWER)
                    .filter(intent -> reviewGroupFilter == null
                            || reviewGroupFilter.contains(intent.workGroupId()))
                    .toList();
            if (callerReviewers.isEmpty() && reviewGroupFilter != null) {
                for (UUID groupId : reviewGroupFilter) {
                    WorkGroup group = projection.group(groupId)
                            .orElse(null);
                    if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                        continue;
                    }
                    WorkIntent owner = selectUniqueProducerTarget(activeIntents, groupId,
                            participantId, completedReviewRevisions, pendingReviewIntents);
                    if (owner != null) {
                        actions.add(reviewAdmissionAction(group, owner, participantId));
                    }
                }
            }
            for (WorkIntent reviewer : callerReviewers) {
                WorkGroup group = projection.group(reviewer.workGroupId())
                        .orElse(null);
                if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                    continue;
                }
                WorkIntent owner = selectReviewTarget(activeIntents, reviewer, participantId,
                        completedReviewRevisions, pendingReviewIntents);
                if (owner == null) {
                    continue;
                }
                actions.add(reviewAdmissionAction(group, owner, participantId));
            }
        }
        return List.copyOf(actions);
    }

    /**
     * Selects one semantically identified producer without using arrival order as a fallback.
     */
    private static WorkIntent selectReviewTarget(List<WorkIntent> activeIntents, WorkIntent reviewer,
            String reviewerParticipant, Set<String> completedReviewRevisions,
            Set<UUID> pendingReviewIntents) {
        List<WorkIntent> candidates = activeIntents.stream()
                .filter(intent -> intent.role() == WorkIntent.Role.PRODUCER)
                .filter(intent -> intent.workGroupId()
                        .equals(reviewer.workGroupId()))
                .filter(intent -> !intent.participant()
                        .equals(reviewerParticipant))
                .filter(intent -> !completedReviewRevisions.contains(intent.intentId() + ":" + intent.version()))
                .filter(intent -> !pendingReviewIntents.contains(intent.intentId()))
                .toList();
        if (reviewer.reviewTargetSelectors()
                .isEmpty()) {
            return candidates.size() == 1 ? candidates.getFirst() : null;
        }
        List<WorkIntent> matching = candidates.stream()
                .filter(candidate -> reviewer.reviewTargetSelectors()
                        .stream()
                        .allMatch(target -> candidate.selectors()
                                .stream()
                                .anyMatch(target::overlaps)))
                .toList();
        return matching.size() == 1 ? matching.getFirst() : null;
    }

    /**
     * Selects a single producer for a completed review-only participant.
     */
    private static WorkIntent selectUniqueProducerTarget(List<WorkIntent> activeIntents, UUID workGroupId,
            String reviewerParticipant, Set<String> completedReviewRevisions,
            Set<UUID> pendingReviewIntents) {
        List<WorkIntent> candidates = activeIntents.stream()
                .filter(intent -> intent.role() == WorkIntent.Role.PRODUCER)
                .filter(intent -> intent.workGroupId()
                        .equals(workGroupId))
                .filter(intent -> !intent.participant()
                        .equals(reviewerParticipant))
                .filter(intent -> !completedReviewRevisions.contains(intent.intentId() + ":" + intent.version()))
                .filter(intent -> !pendingReviewIntents.contains(intent.intentId()))
                .toList();
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    /**
     * Returns whether a reviewer declaration covers the producer intent.
     */
    private static boolean reviewTargetsMatch(WorkIntent reviewer, WorkIntent producer) {
        return reviewer.reviewTargetSelectors()
                .stream()
                .allMatch(targetSelector -> producer.selectors()
                        .stream()
                        .anyMatch(targetSelector::overlaps));
    }

    /**
     * Returns whether a reviewer declaration covers an already published snapshot claim.
     */
    private static boolean reviewTargetsMatch(WorkIntent reviewer, TaskSnapshotRecord snapshot) {
        return reviewer.reviewTargetSelectors()
                .stream()
                .allMatch(targetSelector ->
                        snapshot.provenance()
                                .claimSelectors()
                                .stream()
                                .anyMatch(encoded -> {
                                    int separator = encoded.indexOf(':');
                                    if (separator <= 0 || separator == encoded.length() - 1) {
                                        return false;
                                    }
                                    try {
                                        ResourceSelector selector = new ResourceSelector(
                                                ResourceSelector.Kind.valueOf(encoded.substring(0, separator)),
                                                encoded.substring(separator + 1));
                                        return targetSelector.overlaps(selector);
                                    } catch (IllegalArgumentException ignored) {
                                        return false;
                                    }
                                }));
    }

    /**
     * Returns whether a durable accepted review request authorizes this grant recipient.
     */
    private static boolean hasExplicitReviewGrant(
            org.synesis.coordination.persistence.PredictionEventStore store, LaneGrant grant,
            String participantId) {
        return store.collaborationProjection()
                .requests()
                .stream()
                .filter(request -> request.kind() == CoordinationRequest.Kind.REVIEW)
                .filter(request -> request.status() == CoordinationRequest.Status.ACCEPTED)
                .filter(request -> request.requester()
                        .equals(participantId))
                .filter(request -> request.conflictingIntentId()
                        .equals(grant.targetIntentId()))
                .anyMatch(request -> UUID.nameUUIDFromBytes(
                                ("synesis-review-grant:" + request.requestId())
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .equals(grant.grantId()));
    }

    /**
     * Builds an explicit review-admission request for one reviewed producer intent.
     */
    private static Map<String, Object> reviewAdmissionAction(WorkGroup group, WorkIntent owner,
            String reviewerParticipant) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("state", "REVIEW_ADMISSION_REQUIRED");
        action.put("nextProtocolAction", "request_coordination");
        action.put("nextProtocolKind", "work_group_join");
        action.put("nextProtocolPayload", Map.of(
                "workGroupId",
                group.workGroupId()
                        .toString(),
                "intentId",
                owner.intentId()
                        .toString(),
                "reviewedIntentId",
                owner.intentId()
                        .toString(),
                "reviewedParticipantId",
                owner.participant(),
                "reviewerParticipant",
                reviewerParticipant,
                "proposal",
                "Review the immutable snapshot for this work group"));
        action.put("workGroup", workGroupMap(group));
        return action;
    }

    private static AgentNextAction protocolNextAction(String protocolAction) {
        return switch (protocolAction) {
            case "respond_coordination" -> AgentNextAction.RESPOND_COORDINATION;
            case "review_decision" -> AgentNextAction.REVIEW_DECISION;
            case "wait" -> AgentNextAction.WAIT;
            default -> AgentNextAction.REQUEST_COORDINATION;
        };
    }

    private static Map<String, Object> snapshotPublicationAction(
            org.synesis.coordination.persistence.PredictionEventStore store, String participantId,
            Path assignedWorktree, TaskSnapshotService snapshotService) {
        var collaboration = store.collaborationProjection();
        var completion = store.taskCompletionProjection();
        for (var intent : collaboration.activeIntents()) {
            if (!intent.participant()
                    .equals(participantId) || intent.role() != WorkIntent.Role.PRODUCER) {
                continue;
            }
            // REVIEW grant consumption authorizes publication but does not
            // manufacture implementation work.  Keep the projection
            // executable by applying the same read-only source/artifact gate
            // that finish_lane applies while creating the snapshot.
            if (assignedWorktree == null || snapshotService == null) {
                continue;
            }
            try {
                if (!snapshotService.hasPublishableChanges(assignedWorktree, intent.selectors())) {
                    continue;
                }
            } catch (Exception ignored) {
                // A failed inspection must not turn into a false publication
                // permission.  The normal IMPLEMENT path remains available.
                continue;
            }
            boolean reviewGrantConsumed = store.workGroupProjection()
                    .grants()
                    .stream()
                    .anyMatch(grant -> grant.workGroupId()
                            .equals(intent.workGroupId())
                            && grant.targetIntentId()
                            .equals(intent.intentId())
                            && grant.claimEpoch() == intent.version()
                            && !grant.targetParticipant()
                            .equals(participantId)
                            && store.workGroupProjection()
                            .grantConsumed(grant.grantId()));
            if (!reviewGrantConsumed) {
                continue;
            }
            boolean snapshotPublished = completion.findSnapshotForTaskRevision(
                            intent.taskId(), intent.intentId(), intent.version())
                    .isPresent();
            if (snapshotPublished) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("snapshotPublicationRequired", true);
            result.put("workGroupId",
                    intent.workGroupId()
                            .toString());
            result.put("intentId",
                    intent.intentId()
                            .toString());
            result.put("claimEpoch", intent.version());
            result.put("participant", participantId);
            result.put("nextProtocolAction", "finish_lane");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary", "Publish the completed immutable snapshot");
            boolean correction = completion.allSnapshots()
                    .stream()
                    .anyMatch(snapshot ->
                            snapshot.taskId()
                                    .equals(intent.taskId())
                                    && snapshot.provenance()
                                    .laneId()
                                    .equals(intent.intentId())
                                    && snapshot.provenance()
                                    .authorityLineageId()
                                    .equals(intent.authorityLineageId())
                                    && snapshot.provenance()
                                    .claimEpoch() < intent.version()
                                    && completion.snapshotState(snapshot.snapshotId())
                                    .orElse(TaskCompletionState.ACTIVE)
                                    == TaskCompletionState.REVIEW_REJECTED);
            if (correction) {
                WorkGroup group = store.workGroupProjection()
                        .group(intent.workGroupId())
                        .orElse(null);
                payload.put("intentId",
                        intent.intentId()
                                .toString());
                payload.put("workGroupId",
                        intent.workGroupId()
                                .toString());
                payload.put("claimEpoch", intent.version());
                payload.put("workGroupVersion", group == null ? 0L : group.version());
                payload.put("expectedRevision", store.headSequence());
                payload.put("participant", participantId);
                payload.put("authorityLineageId",
                        intent.authorityLineageId()
                                .toString());
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
     * current optimistic versions. It is an executable suggestion, not an
     * implicit lifecycle transition; the provider must call {@code finish_lane}
     * with this payload.</p>
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
    private static Map<String, Object> noChangeCompletionAction(
            org.synesis.coordination.persistence.PredictionEventStore store,
            String participantId, String nodeId, String supervisorId, String workerId,
            Path assignedWorktree, TaskSnapshotService snapshotService) {
        for (WorkIntent intent : store.collaborationProjection()
                .activeIntents()) {
            if (!intent.participant()
                    .equals(participantId)
                    || intent.completionMode() != WorkIntent.CompletionMode.NO_CHANGE_ALLOWED) {
                continue;
            }
            NoChangeCompletionEligibility.Result eligibility = NoChangeCompletionEligibility.assess(
                    store, intent, participantId, nodeId, supervisorId, workerId,
                    assignedWorktree, snapshotService);
            if (!eligibility.eligible()) {
                continue;
            }
            WorkGroup group = store.workGroupProjection()
                    .group(intent.workGroupId())
                    .orElse(null);
            if (group == null) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("outcome", "no_change");
            payload.put("intentId",
                    intent.intentId()
                            .toString());
            payload.put("workGroupId",
                    intent.workGroupId()
                            .toString());
            payload.put("claimEpoch", intent.version());
            payload.put("workGroupVersion", group.version());
            payload.put("expectedRevision", store.headSequence());
            payload.put("participant", participantId);
            payload.put("summary", "Verification completed successfully; no repository mutation was required");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("state", "NO_CHANGE_COMPLETION_READY");
            result.put("noChangeCompletionAvailable", true);
            result.put("currentIntent", intentMap(intent));
            result.put("workGroup", workGroupMap(group));
            result.put("nextProtocolAction", "finish_lane");
            result.put("nextProtocolKind", "no_change_completion");
            result.put("nextProtocolPayload", payload);
            return result;
        }
        return null;
    }

    private static Map<String, Object> pendingReviewGrantAction(
            org.synesis.coordination.persistence.PredictionEventStore store, String participantId,
            Path assignedWorktree, TaskSnapshotService snapshotService) {
        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        var completion = store.taskCompletionProjection();
        for (WorkIntent intent : collaboration.activeIntents()) {
            if (!intent.participant()
                    .equals(participantId) || intent.role() != WorkIntent.Role.PRODUCER) {
                continue;
            }
            WorkGroup group = workGroups.group(intent.workGroupId())
                    .orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            boolean snapshotPublished = completion.findSnapshotForTaskRevision(
                            intent.taskId(), intent.intentId(), intent.version())
                    .isPresent();
            if (snapshotPublished) {
                continue;
            }
            for (LaneGrant grant : workGroups.grants()) {
                if (!grant.workGroupId()
                        .equals(intent.workGroupId())
                        || !grant.targetIntentId()
                        .equals(intent.intentId())
                        || grant.claimEpoch() != intent.version()
                        || grant.targetParticipant()
                        .equals(participantId)
                        || !workGroups.grantAvailable(grant.grantId())
                        || workGroups.reviewValidationForGrant(grant.grantId())
                        .isPresent()) {
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
                        if (!snapshotService.hasPublishableChanges(assignedWorktree, intent.selectors())) {
                            continue;
                        }
                    } catch (Exception ignored) {
                        // Inspection failure is fail-closed: retain the wait
                        // rather than projecting an unverified continuation.
                    }
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("grantId",
                        grant.grantId()
                                .toString());
                payload.put("workGroupId",
                        grant.workGroupId()
                                .toString());
                payload.put("intentId",
                        grant.targetIntentId()
                                .toString());
                payload.put("reviewedIntentId",
                        grant.targetIntentId()
                                .toString());
                payload.put("reviewedParticipantId", intent.participant());
                payload.put("claimEpoch", grant.claimEpoch());
                payload.put("targetParticipant", grant.targetParticipant());
                payload.put("reviewerParticipant", grant.targetParticipant());
                payload.put("snapshotRequired", true);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("state", "REVIEW_GRANT_PENDING");
                result.put("reviewGrantPending", true);
                result.put("reviewGrant", laneGrantMap(grant));
                result.put("workGroup", workGroupMap(group));
                result.put("reviewerParticipant", grant.targetParticipant());
                result.put("nextProtocolAction", "wait");
                result.put("nextProtocolKind", "review_grant_consumption");
                result.put("nextProtocolPayload", payload);
                return result;
            }
        }
        return null;
    }

    private static Map<String, Object> intentMap(WorkIntent intent) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("intentId",
                intent.intentId()
                        .toString());
        map.put("participant", intent.participant());
        map.put("provider", intent.provider());
        map.put("goal", intent.goal());
        map.put("acceptance", intent.acceptance());
        map.put("selectors",
                intent.selectors()
                        .stream()
                        .map(AgentNextActionService::selectorMap)
                        .toList());
        map.put("version", intent.version());
        map.put("claimEpoch", intent.version());
        map.put("workGroupId",
                intent.workGroupId()
                        .toString());
        map.put("authorityLineageId",
                intent.authorityLineageId()
                        .toString());
        map.put("status",
                intent.status()
                        .name());
        map.put("completionMode",
                intent.completionMode()
                        .wireValue());
        map.put("role",
                intent.role()
                        .wireValue());
        map.put("reviewTargets",
                intent.reviewTargetSelectors()
                        .stream()
                        .map(AgentNextActionService::selectorMap)
                        .toList());
        return map;
    }

    private static Map<String, Object> participantMap(Participant participant) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", participant.id());
        map.put("provider", participant.provider());
        map.put("goal", participant.goal());
        map.put("state",
                participant.state()
                        .name());
        map.put("lastVerifiedActivity", participant.lastVerifiedActivity());
        map.put("claims",
                participant.claims()
                        .stream()
                        .map(AgentNextActionService::selectorMap)
                        .toList());
        return map;
    }

    private static Map<String, Object> requestMap(CoordinationRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestId",
                request.requestId()
                        .toString());
        map.put("inboxItemId",
                request.requestId()
                        .toString());
        map.put("requester", request.requester());
        map.put("target", request.target());
        map.put("conflictingIntentId",
                request.conflictingIntentId()
                        .toString());
        map.put("reviewedIntentId",
                request.conflictingIntentId()
                        .toString());
        map.put("reviewedParticipantId", request.target());
        map.put("reviewerParticipant", request.requester());
        map.put("kind",
                request.kind()
                        .name());
        map.put("proposal", request.proposal());
        map.put("status",
                request.status()
                        .name());
        return map;
    }

    private static Map<String, Object> workGroupMap(WorkGroup group) {
        return Map.of("workGroupId",
                group.workGroupId()
                        .toString(),
                "projectId",
                group.projectId()
                        .toString(),
                "goal",
                group.goal(),
                "acceptance",
                group.acceptance(),
                "version",
                group.version(),
                "status",
                group.status()
                        .name());
    }

    private static Map<String, Object> laneGrantMap(LaneGrant grant) {
        return Map.of("grantId",
                grant.grantId()
                        .toString(),
                "workGroupId",
                grant.workGroupId()
                        .toString(),
                "targetIntentId",
                grant.targetIntentId()
                        .toString(),
                "reviewedIntentId",
                grant.targetIntentId()
                        .toString(),
                "targetParticipant",
                grant.targetParticipant(),
                "reviewerParticipant",
                grant.targetParticipant(),
                "claimEpoch",
                grant.claimEpoch(),
                "singleUse",
                grant.singleUse());
    }

    private static Map<String, Object> snapshotMap(TaskSnapshotRecord snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId",
                snapshot.taskId()
                        .toString());
        map.put("snapshotId", snapshot.snapshotId());
        map.put("baseCommit", snapshot.baseCommit());
        map.put("commitSha", snapshot.commitSha());
        map.put("changedPaths", snapshot.changedPaths());
        map.put("summary", snapshot.summary());
        map.put("createdAtMillis", snapshot.createdAtMillis());
        map.put("laneId",
                snapshot.provenance()
                        .laneId()
                        .toString());
        map.put("claimEpoch",
                snapshot.provenance()
                        .claimEpoch());
        map.put("workGroupId",
                snapshot.provenance()
                        .workGroupId()
                        .toString());
        map.put("participant",
                snapshot.provenance()
                        .participant());
        map.put("reviewRequired", snapshot.reviewRequired());
        return map;
    }

    private static Map<String, Object> selectorMap(ResourceSelector selector) {
        return Map.of("kind",
                selector.kind()
                        .name(),
                "path",
                selector.value());
    }

    private static int priorityOf(String type) {
        if (type == null) {
            return 99;
        }
        return switch (type.toUpperCase(java.util.Locale.ROOT)) {
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
    private static List<CoordinationItem> loadCoordinationItems(Path assignedWorktree,
            Path projectRoot,
            String targetWorker) {
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

                    boolean obsolete =
                            Boolean.TRUE.equals(map.get("obsolete")) || Boolean.TRUE.equals(map.get("completed"));
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
     * Resolves the single highest-priority actionable coordination item for the active session worker.
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

        Path root = request.projectRoot()
                .toAbsolutePath()
                .normalize();
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED,
                    AgentReason.WORKSPACE_NOT_READY,
                    AgentNextAction.ENSURE_SESSION,
                    null);
        }

        ProjectApplicationService.ProjectLocation location;
        WorkspaceReadinessService.ReadinessResult readiness;
        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        java.util.Optional<ProviderSessionBindingService.Binding> exactBinding;
        try {
            location = projectService.locate(root);
            exactBinding = bindingService.find(
                    location, request.provider(), request.connectionInstanceId());
            if (exactBinding.isPresent() && sessionIsTerminal(location, exactBinding.get())) {
                return terminalSessionResponse(exactBinding.get()
                        .sessionId());
            }
            if (exactBinding.isPresent() && "COMPLETED".equals(exactBinding.get()
                    .status())) {
                AgentResponse reviewResponse = completedReviewAction(location,
                        exactBinding.get()
                                .sessionId());
                if (reviewResponse != null) {
                    return reviewResponse;
                }
                // A completed lane remains terminal when no review action is
                // available.  It never re-enters workspace readiness or write
                // ownership merely because a sibling lane is still active.
                return new AgentResponse(AgentStatus.COMPLETED, null, null,
                        Map.of("state",
                                "COMPLETED",
                                "lane",
                                exactBinding.get()
                                        .sessionId()));
            }
            readiness = readinessService.assess(location, request.provider(), request.connectionInstanceId());
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED,
                    AgentReason.WORKSPACE_NOT_READY,
                    AgentNextAction.ENSURE_SESSION,
                    null);
        }
        if (!readiness.ready()) {
            // A sibling integration may advance the control checkout while a
            // reviewer has legitimate uncommitted work in its assigned
            // worktree.  Keep the stale-dirty workspace fail-closed for all
            // workspace operations, but allow the durable review protocol to
            // continue because review_validation is authorized by the exact
            // session, grant, epoch, and immutable snapshot rather than by a
            // replacement worktree.
            if (exactBinding.isPresent()
                    && "BOUND".equals(exactBinding.get()
                    .status())
                    && "CONTROL_BASE_ADVANCED".equals(readiness.internalReason())
                    && (bindingService.hasConfirmedUncommittedWork(exactBinding.get())
                    || hasActiveNoChangeIntent(location, exactBinding.get()))) {
                AgentResponse staleAction = staleCoordinationAction(location, exactBinding.get());
                if (staleAction != null) {
                    return staleAction;
                }
            }
            return readiness.response();
        }
        ProviderSessionBindingService.Binding binding = readiness.binding();
        Path assignedWorktree = readiness.worktree();

        try {
            org.synesis.link.identity.NodeIdentity callerIdentity = new org.synesis.link.identity.IdentityBootstrap(
                    location.profile()
                            .resolve("link")).loadOrCreate()
                    .identity();
            String callerNodeId = callerIdentity.nodeId();
            String callerWorkerId = binding.workerId();
            Path coordDir = location.root()
                    .resolve(".synesis/coordination");
            if (Files.exists(coordDir.resolve("events"))) {
                org.synesis.coordination.persistence.PredictionEventStore store = new org.synesis.coordination.persistence.PredictionEventStore(
                        coordDir,
                        location.projectId());
                // Startup reconciliation is deliberately pull-safe: each
                // durable inbox read gives the shared integration pump an
                // opportunity to recover an interrupted attempt or advance
                // the oldest eligible immutable snapshot. The pump owns its
                // project lock and never mutates a worker worktree.
                AgentResponse integrationPump = new org.synesis.workspace.application.integration.IntegrationOrchestrationService()
                        .orchestrateIntegration(root, store, callerIdentity);
                if (integrationPump.status() == AgentStatus.COMPLETED) {
                    store = new org.synesis.coordination.persistence.PredictionEventStore(coordDir,
                            location.projectId());
                    exactBinding = bindingService.find(location, request.provider(), request.connectionInstanceId());
                    if (exactBinding.isPresent() && sessionIsTerminal(location, exactBinding.get())) {
                        return terminalSessionResponse(exactBinding.get()
                                .sessionId());
                    }
                    if (exactBinding.isPresent() && "COMPLETED".equals(exactBinding.get()
                            .status())) {
                        AgentResponse reviewResponse = completedReviewAction(location,
                                exactBinding.get()
                                        .sessionId());
                        if (reviewResponse != null) {
                            return reviewResponse;
                        }
                        return new AgentResponse(AgentStatus.COMPLETED, null, null,
                                Map.of("state",
                                        "COMPLETED",
                                        "lane",
                                        exactBinding.get()
                                                .sessionId()));
                    }
                }
                org.synesis.coordination.domain.capability.CapabilityRequestProjection capProj = store.capabilityRequestProjection();
                Map<String, Object> collaboration = collaborationDetails(store, binding.sessionId());
                String callerParticipant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
                AgentResponse pendingReviewResponse = pendingReviewRequestResponse(
                        collaboration, store, callerParticipant);
                if (pendingReviewResponse != null) {
                    return pendingReviewResponse;
                }
                AgentResponse reviewResponse = reviewActionResponse(collaboration, false);
                if (reviewResponse != null) {
                    return reviewResponse;
                }
                AgentResponse reviewerPendingResponse = reviewerPendingAction(store, callerParticipant);
                if (reviewerPendingResponse != null) {
                    return reviewerPendingResponse;
                }
                AgentResponse revisionResponse = revisionRequiredAction(store, callerParticipant);
                if (revisionResponse != null) {
                    return revisionResponse;
                }
                AgentResponse reviewPendingResponse = reviewPendingAction(store, callerParticipant);
                if (reviewPendingResponse != null) {
                    return reviewPendingResponse;
                }
                Map<String, Object> publicationAction = snapshotPublicationAction(
                        store, callerParticipant, assignedWorktree, snapshotService);
                if (publicationAction != null) {
                    return new AgentResponse(AgentStatus.READY, AgentReason.SNAPSHOT_PUBLICATION_REQUIRED,
                            AgentNextAction.FINISH_LANE, publicationAction);
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> pendingCoordination = (List<Map<String, Object>>) collaboration.get(
                        "pendingCoordination");
                if (!pendingCoordination.isEmpty()) {
                    Map<String, Object> ownerAction = new LinkedHashMap<>(collaboration);
                    Map<String, Object> reviewAcceptance = reviewAcceptanceAction(pendingCoordination);
                    if (reviewAcceptance != null) {
                        ownerAction.putAll(reviewAcceptance);
                    }
                    return new AgentResponse(AgentStatus.READY, AgentReason.OWNER_REQUEST_PENDING,
                            AgentNextAction.RESPOND_COORDINATION, ownerAction);
                }

                Map<String, Object> pendingReviewGrant = pendingReviewGrantAction(
                        store, callerParticipant, assignedWorktree, snapshotService);
                if (pendingReviewGrant != null) {
                    Map<String, Object> ownerWait = new LinkedHashMap<>(collaboration);
                    ownerWait.putAll(pendingReviewGrant);
                    return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED,
                            AgentNextAction.WAIT, ownerWait);
                }

                Map<String, Object> noChangeAction = noChangeCompletionAction(
                        store, callerParticipant, callerNodeId, binding.supervisorId(),
                        binding.workerId(), assignedWorktree, snapshotService);
                if (noChangeAction != null) {
                    return new AgentResponse(AgentStatus.READY, null,
                            AgentNextAction.FINISH_LANE, noChangeAction);
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
                boolean callerHasActiveIntent = store.collaborationProjection()
                        .activeIntents()
                        .stream()
                        .anyMatch(intent -> intent.participant()
                                .equals(callerParticipant));
                if (store.collaborationProjection()
                        .activated() && !callerHasActiveIntent) {
                    Map<String, Object> claimRequired = new LinkedHashMap<>(collaboration);
                    claimRequired.put("claimsRequired", true);
                    claimRequired.put("reason", AgentReason.COORDINATION_INTENT_REQUIRED.value());
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COORDINATION_INTENT_REQUIRED,
                            AgentNextAction.ENSURE_SESSION, claimRequired);
                }

                List<org.synesis.coordination.domain.capability.CapabilityRequestRecord> ownerPending = capProj.findPendingForOwner(
                        callerNodeId);

                // Slice 3: Check active integration projection states
                var taskCompProj = store.taskCompletionProjection();
                var activeAttemptOpt = taskCompProj.activeIntegrationAttempt();
                if (activeAttemptOpt.isPresent()) {
                    var att = activeAttemptOpt.get();
                    if ("conflict".equals(att.status())) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("pending", 1);
                        return new AgentResponse(AgentStatus.BLOCKED,
                                AgentReason.INTEGRATION_CONFLICT,
                                AgentNextAction.REQUEST_HUMAN_HELP,
                                result);
                    }
                }

                // Check if worker's task is waiting for dependencies
                var workerSnapshotOpt = taskCompProj.findLatestSnapshotForWorker(callerNodeId, callerWorkerId);
                if (workerSnapshotOpt.isPresent()) {
                    var state = taskCompProj.taskState(workerSnapshotOpt.get()
                            .taskId());
                    if (state == org.synesis.coordination.domain.task.TaskCompletionState.WAITING_FOR_DEPENDENCIES
                            || state == org.synesis.coordination.domain.task.TaskCompletionState.SNAPSHOT_READY) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("pending", 1);
                        return new AgentResponse(AgentStatus.WAITING,
                                AgentReason.INTEGRATION_PENDING,
                                AgentNextAction.WAIT,
                                result);
                    }
                }
                if (!ownerPending.isEmpty()) {
                    org.synesis.coordination.domain.capability.CapabilityRequestRecord topReq = ownerPending.getFirst();
                    Map<String, Object> contractMap = new LinkedHashMap<>();
                    contractMap.put("inputs",
                            topReq.contract()
                                    .inputs());
                    contractMap.put("output",
                            topReq.contract()
                                    .output());
                    contractMap.put("requiredBehavior",
                            topReq.contract()
                                    .requiredBehavior());
                    contractMap.put("acceptanceTests",
                            topReq.contract()
                                    .acceptanceTests());

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("capabilityRequestHandle",
                            topReq.handle()
                                    .value());
                    result.put("capability", topReq.capability());
                    result.put("authorityLineageId",
                            topReq.authorityLineageId()
                                    .toString());
                    result.put("contract", contractMap);
                    result.put("pending", ownerPending.size());
                    return new AgentResponse(AgentStatus.READY, null, AgentNextAction.RESPOND_COORDINATION, result);
                }

                // Slice 2: owner must respond to a validation revision
                List<org.synesis.coordination.domain.capability.CapabilityRequestRecord> validationRevList = capProj.findValidationRevisionForOwner(
                        callerNodeId);
                if (!validationRevList.isEmpty()) {
                    org.synesis.coordination.domain.capability.CapabilityRequestRecord topReq = validationRevList.getFirst();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("capabilityRequestHandle",
                            topReq.handle()
                                    .value());
                    result.put("reason", topReq.reason() != null ? topReq.reason() : "Revision required by requester");
                    result.put("pending", validationRevList.size());
                    return new AgentResponse(AgentStatus.READY,
                            AgentReason.VALIDATION_FAILED,
                            AgentNextAction.RESPOND_TO_VALIDATION_REVISION,
                            result);
                }

                List<org.synesis.coordination.domain.capability.CapabilityRequestRecord> reqPending = capProj.findPendingForRequester(
                        callerNodeId);
                if (!reqPending.isEmpty()) {
                    org.synesis.coordination.domain.capability.CapabilityRequestRecord topReq = null;
                    for (org.synesis.coordination.domain.capability.CapabilityRequestRecord r : reqPending) {
                        if (r.state()
                                == org.synesis.coordination.domain.capability.CapabilityLifecycleState.REVISION_REQUESTED) {
                            topReq = r;
                            break;
                        } else if (r.state()
                                == org.synesis.coordination.domain.capability.CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE
                                && topReq == null) {
                            topReq = r;
                        } else if (r.state()
                                == org.synesis.coordination.domain.capability.CapabilityLifecycleState.REJECTED
                                && topReq == null) {
                            topReq = r;
                        } else if (r.state()
                                == org.synesis.coordination.domain.capability.CapabilityLifecycleState.AWAITING_OWNER
                                && topReq == null) {
                            topReq = r;
                        } else if (r.state()
                                == org.synesis.coordination.domain.capability.CapabilityLifecycleState.ACCEPTED
                                && topReq == null) {
                            topReq = r;
                        } else if (r.state()
                                == org.synesis.coordination.domain.capability.CapabilityLifecycleState.IMPLEMENTING
                                && topReq == null) {
                            topReq = r;
                        }
                    }
                    if (topReq != null) {
                        if (topReq.state()
                                == org.synesis.coordination.domain.capability.CapabilityLifecycleState.REVISION_REQUESTED) {
                            Map<String, Object> contractMap = new LinkedHashMap<>();
                            contractMap.put("inputs",
                                    topReq.contract()
                                            .inputs());
                            contractMap.put("output",
                                    topReq.contract()
                                            .output());
                            contractMap.put("requiredBehavior",
                                    topReq.contract()
                                            .requiredBehavior());
                            contractMap.put("acceptanceTests",
                                    topReq.contract()
                                            .acceptanceTests());

                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle",
                                    topReq.handle()
                                            .value());
                            result.put("contract", contractMap);
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.READY,
                                    AgentReason.REVISION_REQUIRED,
                                    AgentNextAction.REVISE_CAPABILITY_REQUEST,
                                    result);
                        } else if (topReq.state()
                                == org.synesis.coordination.domain.capability.CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE) {
                            // Slice 2: requester must validate the available implementation
                            org.synesis.coordination.domain.integration.ImplementationRevisionRecord implRec = capProj.findLatestImplementation(
                                            topReq.handle()
                                                    .value())
                                    .orElse(null);
                            int revision = implRec != null ? implRec.revisionNumber() : 1;
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle",
                                    topReq.handle()
                                            .value());
                            result.put("capability", topReq.capability());
                            result.put("authorityLineageId",
                                    topReq.authorityLineageId()
                                            .toString());
                            result.put("revision", revision);
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.READY,
                                    null,
                                    AgentNextAction.VALIDATE_IMPLEMENTATION,
                                    result);
                        } else if (topReq.state()
                                == org.synesis.coordination.domain.capability.CapabilityLifecycleState.REJECTED) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle",
                                    topReq.handle()
                                            .value());
                            result.put("reason",
                                    topReq.reason() != null ? topReq.reason() : "Capability request rejected by owner");
                            return new AgentResponse(AgentStatus.BLOCKED,
                                    AgentReason.CAPABILITY_REJECTED,
                                    AgentNextAction.RETRY,
                                    result);
                        } else if (topReq.state()
                                == org.synesis.coordination.domain.capability.CapabilityLifecycleState.AWAITING_OWNER) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle",
                                    topReq.handle()
                                            .value());
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.WAITING,
                                    AgentReason.OWNER_RESPONSE_PENDING,
                                    AgentNextAction.WAIT,
                                    result);
                        } else {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle",
                                    topReq.handle()
                                            .value());
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.WAITING,
                                    AgentReason.IMPLEMENTATION_UNAVAILABLE,
                                    AgentNextAction.WAIT,
                                    result);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        List<CoordinationItem> items = loadCoordinationItems(assignedWorktree, location.root(), request.provider());
        if (items.isEmpty()) {
            Map<String, Object> collaboration = new LinkedHashMap<>(collaborationDetailsForRequest(location, request));
            ProjectCommandDiagnostics.Report command = ProjectCommandDiagnostics.inspect(
                    AdministrativeStateLocator.applicationStateRoot()
                            .resolve("commands"));
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

        return switch (topItem.type()
                .toUpperCase(java.util.Locale.ROOT)) {
            case "SAFETY_FAILURE" -> new AgentResponse(AgentStatus.FAILED,
                    AgentReason.INTERNAL_FAILURE,
                    AgentNextAction.REQUEST_HUMAN_HELP,
                    null);
            case "DEPENDENCY_INVALIDATED" -> {
                Map<String, Object> res = new LinkedHashMap<>();
                if (topItem.capability() != null) {
                    res.put("capability", topItem.capability());
                }
                res.put("pending", pendingCount);
                yield new AgentResponse(AgentStatus.RETRY_REQUIRED,
                        AgentReason.DEPENDENCY_INVALIDATED,
                        AgentNextAction.RETRY,
                        res);
            }
            case "OWNER_REQUEST" -> {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("capability", topItem.capability() != null ? topItem.capability() : "unknown");
                req.put("inputs",
                        topItem.details()
                                .getOrDefault("inputs", "..."));
                req.put("output",
                        topItem.details()
                                .getOrDefault("output", "..."));
                req.put("behavior",
                        topItem.details()
                                .getOrDefault("behavior", "..."));
                req.put("acceptanceTest",
                        topItem.details()
                                .getOrDefault("acceptanceTest", "..."));

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
                yield new AgentResponse(AgentStatus.NEEDS_CAPABILITY,
                        AgentReason.OWNER_REQUIRED,
                        AgentNextAction.REQUEST_COORDINATION,
                        res);
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
                yield new AgentResponse(AgentStatus.WAITING,
                        AgentReason.OWNER_RESPONSE_PENDING,
                        AgentNextAction.WAIT,
                        res);
            }
            default -> AgentResponse.ready("isolated", pendingCount);
        };
    }

    private AgentResponse completedReviewAction(
            ProjectApplicationService.ProjectLocation location, String sessionId) {
        try {
            Path coordination = location.root()
                    .resolve(".synesis/coordination");
            if (!Files.exists(coordination.resolve("events"))) {
                return null;
            }
            org.synesis.coordination.persistence.PredictionEventStore store =
                    new org.synesis.coordination.persistence.PredictionEventStore(
                            coordination, location.projectId());
            String participant = WorkspaceCollaborationService.participantHandle(sessionId);
            Map<String, Object> baseCollaboration = collaborationDetails(store, sessionId);
            AgentResponse pendingReviewResponse = pendingReviewRequestResponse(
                    baseCollaboration, store, participant);
            if (pendingReviewResponse != null) {
                return pendingReviewResponse;
            }
            Set<UUID> completedGroups = completedParticipantWorkGroups(store, participant);
            if (completedGroups.isEmpty()) {
                return null;
            }
            Map<String, Object> collaboration = collaborationDetails(store, sessionId, completedGroups);
            return reviewActionResponse(collaboration, true);
        } catch (Exception ignored) {
            return null;
        }
    }

    private AgentResponse staleCoordinationAction(
            ProjectApplicationService.ProjectLocation location,
            ProviderSessionBindingService.Binding binding) {
        try {
            Path coordination = location.root()
                    .resolve(".synesis/coordination");
            if (!Files.exists(coordination.resolve("events"))) {
                return null;
            }
            org.synesis.coordination.persistence.PredictionEventStore store =
                    new org.synesis.coordination.persistence.PredictionEventStore(
                            coordination, location.projectId());
            Map<String, Object> collaboration = collaborationDetails(store, binding.sessionId());
            String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            AgentResponse pendingReviewResponse = pendingReviewRequestResponse(
                    collaboration, store, participant);
            if (pendingReviewResponse != null) {
                return pendingReviewResponse;
            }
            AgentResponse reviewResponse = reviewActionResponse(collaboration, true);
            if (reviewResponse != null) {
                return reviewResponse;
            }
            AgentResponse reviewerPendingResponse = reviewerPendingAction(store, participant);
            if (reviewerPendingResponse != null) {
                return reviewerPendingResponse;
            }
            AgentResponse revisionResponse = revisionRequiredAction(store, participant);
            if (revisionResponse != null) {
                return revisionResponse;
            }
            AgentResponse reviewPendingResponse = reviewPendingAction(store, participant);
            if (reviewPendingResponse != null) {
                return reviewPendingResponse;
            }

            Path assignedWorktree = binding.worktreePath() == null
                    ? null : Path.of(binding.worktreePath());
            Map<String, Object> publicationAction = snapshotPublicationAction(
                    store, participant, assignedWorktree, snapshotService);
            if (publicationAction != null) {
                return new AgentResponse(AgentStatus.READY, AgentReason.SNAPSHOT_PUBLICATION_REQUIRED,
                        AgentNextAction.FINISH_LANE, publicationAction);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pendingCoordination =
                    (List<Map<String, Object>>) collaboration.get("pendingCoordination");
            Map<String, Object> reviewAcceptance = reviewAcceptanceAction(pendingCoordination);
            if (reviewAcceptance != null) {
                Map<String, Object> ownerAction = new LinkedHashMap<>(collaboration);
                ownerAction.putAll(reviewAcceptance);
                return new AgentResponse(AgentStatus.READY, AgentReason.OWNER_REQUEST_PENDING,
                        AgentNextAction.RESPOND_COORDINATION, ownerAction);
            }

            Map<String, Object> pendingReviewGrant = pendingReviewGrantAction(
                    store, participant, assignedWorktree, snapshotService);
            if (pendingReviewGrant != null) {
                Map<String, Object> ownerWait = new LinkedHashMap<>(collaboration);
                ownerWait.putAll(pendingReviewGrant);
                return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED,
                        AgentNextAction.WAIT, ownerWait);
            }
            org.synesis.link.identity.NodeIdentity callerIdentity =
                    new org.synesis.link.identity.IdentityBootstrap(location.profile()
                            .resolve("link"))
                            .loadOrCreate()
                            .identity();
            Map<String, Object> noChangeAction = noChangeCompletionAction(
                    store, participant, callerIdentity.nodeId(), binding.supervisorId(),
                    binding.workerId(), assignedWorktree, snapshotService);
            if (noChangeAction != null) {
                return new AgentResponse(AgentStatus.READY, null,
                        AgentNextAction.FINISH_LANE, noChangeAction);
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Builds a JSON-safe collaboration discovery and pending-request projection.
     */
    private Map<String, Object> collaborationDetailsForRequest(ProjectApplicationService.ProjectLocation location,
            NextActionRequest request) {
        try {
            Path coordDir = location.root()
                    .resolve(".synesis/coordination");
            if (Files.exists(coordDir.resolve("events"))) {
                var store = new org.synesis.coordination.persistence.PredictionEventStore(coordDir,
                        location.projectId());
                String fingerprint = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(request.connectionInstanceId()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var binding = new ProviderSessionBindingService().list(location, request.provider())
                        .stream()
                        .filter(candidate -> fingerprint.equals(candidate.providerInstanceFingerprint()))
                        .findFirst()
                        .orElse(null);
                if (binding != null) {
                    return collaborationDetails(store, binding.sessionId());
                }
                return collaborationDetails(store, "");
            }
        } catch (Exception ignored) {
        }
        return Map.of("workspace", "isolated", "pending", 0,
                "participants", List.of(), "intents", List.of(), "pendingCoordination", List.of());
    }

    /**
     * Converts collaboration records to a provider-safe next-action payload.
     */
    private Map<String, Object> collaborationDetails(
            org.synesis.coordination.persistence.PredictionEventStore store, String sessionId) {
        return collaborationDetails(store, sessionId, null);
    }

    private Map<String, Object> collaborationDetails(
            org.synesis.coordination.persistence.PredictionEventStore store, String sessionId,
            Set<UUID> reviewGroupFilter) {
        String participantId = sessionId == null || sessionId.isBlank()
                ? "" : WorkspaceCollaborationService.participantHandle(sessionId);
        List<Map<String, Object>> intents = store.collaborationProjection()
                .activeIntents()
                .stream()
                .map(AgentNextActionService::intentMap)
                .toList();
        Map<String, Object> currentIntent = store.collaborationProjection()
                .activeIntents()
                .stream()
                .filter(intent -> intent.participant()
                        .equals(participantId))
                .map(AgentNextActionService::intentMap)
                .findFirst()
                .orElse(null);
        List<Map<String, Object>> participants = store.collaborationProjection()
                .participants()
                .stream()
                .map(AgentNextActionService::participantMap)
                .toList();
        List<Map<String, Object>> pending = store.collaborationProjection()
                .requests()
                .stream()
                .filter(request -> request.status() == CoordinationRequest.Status.PENDING)
                .filter(request -> !store.collaborationProjection()
                        .inboxAcknowledged(request.requestId()))
                .filter(request -> participantId.isBlank() || request.target()
                        .equals(participantId))
                .map(AgentNextActionService::requestMap)
                .toList();
        List<Map<String, Object>> enrichedPending = pending.stream()
                .map(request -> enrichPendingRequest(request, store))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspace", "isolated");
        result.put("pending", enrichedPending.size());
        result.put("participants", participants);
        result.put("intents", intents);
        result.put("groups",
                store.workGroupProjection()
                        .groups()
                        .stream()
                        .map(AgentNextActionService::workGroupMap)
                        .toList());
        result.put("grants",
                store.workGroupProjection()
                        .grants()
                        .stream()
                        .map(AgentNextActionService::laneGrantMap)
                        .toList());
        result.put("snapshots",
                store.taskCompletionProjection()
                        .allSnapshots()
                        .stream()
                        .map(AgentNextActionService::snapshotMap)
                        .toList());
        result.put("currentParticipant", participantId);
        result.put("currentIntent", currentIntent);
        result.put("pendingCoordination", enrichedPending);
        result.put("reviewActions", reviewActions(store, participantId, reviewGroupFilter));
        result.put("claimConflicts", List.of());
        return result;
    }

    /**
     * Request parameters for next action resolution.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     */
    public record NextActionRequest(
            Path projectRoot,
            String provider,
            String connectionInstanceId
    ) {

        /**
         * Validates non-null request parameters.
         */
        public NextActionRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        }
    }

    /**
     * Represents a pending coordination item.
     *
     * @param type       item type (SAFETY_FAILURE, DEPENDENCY_INVALIDATED, OWNER_REQUEST, NEEDS_CAPABILITY,
     *                   VALIDATION_REQUIRED, WAITING_FOR_OWNER)
     * @param capability capability identifier
     * @param workerId   target worker identifier or provider
     * @param details    additional detail payload
     * @param sequence   sequence number for deterministic ordering
     */
    public record CoordinationItem(
            String type,
            String capability,
            String workerId,
            Map<String, Object> details,
            long sequence
    ) {

    }
}
