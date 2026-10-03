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
 * Projects review requests, grants, snapshots, and read-only review actions.
 *
 * <p>These helpers read the already-loaded durable projection. They create, consume,
 * validate, and integrate no coordination state themselves.
 */
@SuppressWarnings("DuplicatedCode")
final class AgentReviewActionProjection {

    static boolean hasAvailableReviewerReviewContinuation(PredictionEventStore store, Map<String, Object> collaboration) {
        Object projected = collaboration.get("continuationCandidates");
        if (!(projected instanceof List<?> candidates)) {
            return false;
        }
        Set<String> availableReviewerIntents = new LinkedHashSet<>();
        for (Object value : candidates) {
            if (value instanceof Map<?, ?> candidate && Boolean.TRUE.equals(candidate.get("available")) && "reviewer".equals(candidate.get("role")) && candidate.get("sourceIntentId") instanceof String intentId) {
                availableReviewerIntents.add(intentId);
            }
        }
        if (availableReviewerIntents.isEmpty()) {
            return false;
        }
        List<TaskSnapshotRecord> pendingReviews = store.taskCompletionProjection().pendingReviewSnapshots();
        return store.collaborationProjection().activeIntents().stream().filter(intent -> availableReviewerIntents.contains(intent.intentId().toString())).filter(intent -> AgentNextActionService.hasUnresolvedReviewObligation(store, intent))
                .anyMatch(reviewer -> pendingReviews.stream().filter(snapshot -> snapshot.provenance().workGroupId().equals(reviewer.workGroupId())).anyMatch(snapshot -> reviewTargetsMatch(reviewer, snapshot)));
    }

/**
     * Returns whether the current lane revision has an exact review grant.
     */
    static boolean hasCurrentReviewGrant(PredictionEventStore store, WorkIntent intent) {
        return store.workGroupProjection().grants().stream().filter(grant -> grant.workGroupId().equals(intent.workGroupId())).filter(grant -> grant.targetIntentId().equals(intent.intentId()))
                .filter(grant -> grant.claimEpoch() == intent.version()).filter(grant -> !store.workGroupProjection().grantSuperseded(grant.grantId())).filter(grant -> allowsIndependentReview(store, grant))
                .anyMatch(grant -> store.workGroupProjection().grantAvailable(grant.grantId()) || store.workGroupProjection().grantConsumed(grant.grantId()));
    }

static AgentResponse reviewActionResponse(Map<String, Object> collaboration, boolean reviewOnly) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> reviewActions = (List<Map<String, Object>>) collaboration.get("reviewActions");
        if (reviewActions == null || reviewActions.isEmpty()) {
            return null;
        }
        Map<String, Object> review = reviewActions.getFirst();
        String protocolAction = String.valueOf(review.get("nextProtocolAction"));
        AgentNextAction next = AgentNextActionService.protocolNextAction(protocolAction);
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
        return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED, next, projection);
    }

/**
     * Projects a wait while a declared reviewer has no resolved producer target.
     */
    static AgentResponse reviewerPendingAction(PredictionEventStore store, String participantId) {
        for (WorkIntent reviewer : store.collaborationProjection().activeIntents()) {
            if (!reviewer.participant().equals(participantId) || reviewer.role() != WorkIntent.Role.REVIEWER || !AgentNextActionService.hasUnresolvedReviewObligation(store, reviewer)) {
                continue;
            }
            if (hasMandatoryReviewCandidate(store, reviewer)) {
                // The candidate list carries one exact request payload per immutable snapshot. Let the
                // reviewer choose explicitly instead of projecting a generic wait or selecting one.
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("workGroupId", reviewer.workGroupId().toString());
            payload.put("reviewerParticipant", participantId);
            payload.put("reviewTargets", reviewer.reviewTargetSelectors().stream().map(CollaborationResponseMapper::selectorMap).toList());
            payload.put("reason", "WAIT_FOR_ELIGIBLE_PRODUCER");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("state", "REVIEWER_PENDING");
            result.put("reviewerPending", true);
            result.put("currentIntent", CollaborationResponseMapper.intentMap(reviewer));
            result.put("workGroupId", reviewer.workGroupId().toString());
            result.put("reviewerParticipant", participantId);
            result.put("reviewTargets", reviewer.reviewTargetSelectors().stream().map(CollaborationResponseMapper::selectorMap).toList());
            result.put("nextProtocolAction", "wait");
            result.put("nextProtocolKind", "review_admission");
            result.put("nextProtocolPayload", payload);
            return new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, result);
        }
        return null;
    }

static boolean hasMandatoryReviewCandidate(PredictionEventStore store, WorkIntent reviewer) {
        boolean mandatory = store.workGroupProjection().group(reviewer.workGroupId()).map(group -> group.reviewPolicy().mandatory()).orElse(false);
        if (!mandatory) {
            return false;
        }
        Set<String> candidateIds = reviewCandidates(store).stream().filter(candidate -> reviewer.workGroupId().toString().equals(candidate.get("workGroupId"))).map(candidate -> (String) candidate.get("snapshotId"))
                .collect(Collectors.toSet());
        return store.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> candidateIds.contains(snapshot.snapshotId())).anyMatch(snapshot -> reviewTargetsMatch(reviewer, snapshot));
    }

static boolean pendingReviewAdmissionTargets(PredictionEventStore store, CoordinationRequest request, WorkIntent intent, String participantId) {
        if (request.status() != CoordinationRequest.Status.PENDING || request.kind() != CoordinationRequest.Kind.REVIEW || !request.target().equals(participantId)) {
            return false;
        }
        return request.conflictingIntentId().equals(intent.intentId()) || store.collaborationProjection().successorIntent(request.conflictingIntentId()).map(intent.intentId()::equals).orElse(false);
    }

static boolean allowsIndependentReview(PredictionEventStore store, LaneGrant grant) {
        boolean activeSelfReview = store.collaborationProjection().intent(grant.targetIntentId()).map(intent -> intent.participant().equals(grant.targetParticipant())).orElse(false);
        if (activeSelfReview) {
            return false;
        }
        return store.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.provenance().laneId().equals(grant.targetIntentId()))
                .filter(snapshot -> snapshot.provenance().claimEpoch() == grant.claimEpoch()).noneMatch(snapshot -> snapshot.provenance().participant().equals(grant.targetParticipant()));
    }

/**
     * Returns whether a grant names an obsolete intent without a live exact review snapshot.
     *
     * <p>A snapshot-bound review grant remains actionable after producer continuation only while
     * that exact mandatory review is still pending. Ordinary grants to the replaced intent remain
     * stale.</p>
     *
     * @param store durable project projection
     * @param grant grant under consideration
     * @return {@code true} when the grant cannot authorize current work
     */
    static boolean grantTargetsSupersededIntent(PredictionEventStore store, LaneGrant grant) {
        if (store.collaborationProjection().successorIntent(grant.targetIntentId()).isEmpty()) {
            return false;
        }
        if (grant.snapshotId() == null) {
            return true;
        }
        return store.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.snapshotId().equals(grant.snapshotId()))
                .filter(snapshot -> snapshot.provenance().workGroupId().equals(grant.workGroupId())).filter(snapshot -> snapshot.provenance().laneId().equals(grant.targetIntentId()))
                .filter(snapshot -> snapshot.provenance().claimEpoch() == grant.claimEpoch()).filter(TaskSnapshotRecord::reviewRequired)
                .noneMatch(snapshot -> store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.REVIEW_PENDING);
    }

/**
     * Returns whether the caller's declared capabilities or active capability requests still prevent
     * completion of the exact intent.
     *
     * @param store        current durable projection
     * @param intent       exact active intent
     * @param nodeId       caller node identity
     * @param supervisorId caller supervisor identity
     * @param workerId     caller worker identity
     * @return {@code true} when a declared or active capability obligation remains
     */
    static boolean hasUnresolvedCapabilityObligation(PredictionEventStore store, WorkIntent intent, String nodeId, String supervisorId, String workerId) {
        if (intent == null || nodeId == null || supervisorId == null || workerId == null) {
            return true;
        }
        var projection = store.capabilityRequestProjection();
        List<CapabilityRequestRecord> requesterRequests = projection.findAllForRequester(nodeId).stream().filter(request -> request.matchesRequester(nodeId, supervisorId, workerId)).toList();
        Set<String> requestedCapabilities = requesterRequests.stream().map(CapabilityRequestRecord::capability).collect(Collectors.toSet());
        if (intent.knownDependencies().stream().anyMatch(dependency -> !requestedCapabilities.contains(dependency))) {
            return true;
        }
        return requesterRequests.stream().anyMatch(request -> request.state() != CapabilityLifecycleState.VALIDATED) || !projection.allValidationContexts().isEmpty();
    }

/**
     * Returns whether the reviewer has a terminal validation for the producer revision.
     */
    static boolean hasCompletedReview(PredictionEventStore store, WorkIntent reviewer, WorkIntent producer) {
        return store.workGroupProjection().grants().stream().filter(grant -> grant.workGroupId().equals(reviewer.workGroupId())).filter(grant -> grant.targetParticipant().equals(reviewer.participant()))
                .filter(grant -> grant.targetIntentId().equals(producer.intentId())).filter(grant -> grant.claimEpoch() == producer.version())
                .filter(grant -> !store.workGroupProjection().grantSuperseded(grant.grantId())).anyMatch(grant -> store.workGroupProjection().reviewValidationForGrant(grant.grantId()).isPresent());
    }

static AgentResponse pendingReviewRequestResponse(Map<String, Object> collaboration, PredictionEventStore store, String participantId) {
        if (participantId == null || participantId.isBlank()) {
            return null;
        }
        for (CoordinationRequest request : store.collaborationProjection().requests()) {
            if (request.status() != CoordinationRequest.Status.PENDING || request.kind() != CoordinationRequest.Kind.REVIEW || !request.requester().equals(participantId)) {
                continue;
            }
            Map<String, Object> requestProjection = enrichPendingRequest(CollaborationResponseMapper.requestMap(request), store);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("requestId", request.requestId().toString());
            payload.put("intentId", request.conflictingIntentId().toString());
            payload.put("target", request.target());
            payload.put("status", request.status().name());
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
            return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
        }
        return null;
    }

static Set<UUID> completedParticipantWorkGroups(PredictionEventStore store, String participant) {
        Set<UUID> groups = new LinkedHashSet<>();
        for (TaskSnapshotRecord snapshot : store.taskCompletionProjection().allSnapshots()) {
            if (participant.equals(snapshot.provenance().participant())) {
                groups.add(snapshot.provenance().workGroupId());
            }
        }
        return Set.copyOf(groups);
    }

static Map<String, Object> enrichPendingRequest(Map<String, Object> request, PredictionEventStore store) {
        Map<String, Object> enriched = new LinkedHashMap<>(request);
        Object conflictingIntent = request.get("conflictingIntentId");
        if (conflictingIntent instanceof String intentId) {
            store.collaborationProjection().activeIntents().stream().filter(intent -> intent.intentId().toString().equals(intentId)).findFirst().ifPresent(intent -> {
                enriched.put("intentId", intent.intentId().toString());
                enriched.put("workGroupId", intent.workGroupId().toString());
                enriched.put("claimEpoch", intent.version());
            });
        }
        return enriched;
    }

static Map<String, Object> reviewAcceptanceAction(List<Map<String, Object>> pendingCoordination) {
        for (Map<String, Object> request : pendingCoordination) {
            if (!"REVIEW".equals(request.get("kind"))) {
                continue;
            }
            Object requestId = request.get("requestId");
            Object intentId = request.get("intentId");
            Object workGroupId = request.get("workGroupId");
            Object claimEpoch = request.get("claimEpoch");
            if (!(requestId instanceof String) || !(intentId instanceof String) || !(workGroupId instanceof String) || !(claimEpoch instanceof Number)) {
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
            return Map.of("nextProtocolAction", "respond_coordination", "nextProtocolKind", "coordination_response", "nextProtocolPayload", payload, "nextProtocolContext", context);
        }
        return null;
    }

static List<Map<String, Object>> reviewActions(PredictionEventStore store, String participantId, Set<UUID> reviewGroupFilter) {
        List<Map<String, Object>> actions = new ArrayList<>();
        var projection = store.workGroupProjection();
        List<WorkIntent> activeIntents = store.collaborationProjection().activeIntents();
        for (LaneGrant grant : projection.grants()) {
            if (projection.grantSuperseded(grant.grantId()) || grantTargetsSupersededIntent(store, grant)) {
                continue;
            }
            if (!grant.targetParticipant().equals(participantId)) {
                continue;
            }
            if (reviewGroupFilter != null && !reviewGroupFilter.contains(grant.workGroupId())) {
                continue;
            }
            WorkIntent reviewedIntent = activeIntents.stream().filter(intent -> intent.intentId().equals(grant.targetIntentId())).findFirst().orElse(null);
            TaskSnapshotRecord snapshot = store.taskCompletionProjection().allSnapshots().stream().filter(value -> grant.snapshotId() == null || grant.snapshotId().equals(value.snapshotId()))
                    .filter(value -> value.provenance().workGroupId().equals(grant.workGroupId())).filter(value -> value.provenance().laneId().equals(grant.targetIntentId()))
                    .filter(value -> value.provenance().claimEpoch() == grant.claimEpoch()).findFirst().orElse(null);
            if (reviewedIntent == null && snapshot == null) {
                continue;
            }
            if (reviewedIntent != null && reviewedIntent.role() != WorkIntent.Role.PRODUCER) {
                continue;
            }
            String reviewedParticipant = reviewedIntent == null ? snapshot.provenance().participant() : reviewedIntent.participant();
            // A malformed or historical grant must not turn a producer into its own reviewer.
            // Keep the durable record available for audit, but fail closed in the agent-facing
            // projection so it cannot be used for self-approval.
            if (reviewedParticipant.equals(participantId)) {
                continue;
            }
            boolean activeReviewer = activeIntents.stream()
                    .anyMatch(intent -> intent.participant().equals(participantId) && intent.workGroupId().equals(grant.workGroupId()) && intent.role() == WorkIntent.Role.REVIEWER && (reviewedIntent != null
                            ? reviewTargetsMatch(intent, reviewedIntent) : reviewTargetsMatch(intent, snapshot)));
            boolean unannouncedReviewer = activeIntents.stream().noneMatch(intent -> intent.participant().equals(participantId));
            boolean explicitReviewGrant = hasExplicitReviewGrant(store, grant, participantId);
            boolean completedReviewer = reviewGroupFilter != null && reviewGroupFilter.contains(grant.workGroupId());
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
                                grant.grantId().toString(),
                                "workGroupId",
                                grant.workGroupId().toString(),
                                "reviewedIntentId",
                                grant.targetIntentId().toString(),
                                "reviewedParticipantId",
                                reviewedParticipant,
                                "reviewerParticipant",
                                grant.targetParticipant(),
                                "snapshotRequired",
                                true));
                waiting.put("grant", CollaborationResponseMapper.laneGrantMap(grant));
                actions.add(waiting);
                continue;
            }
            if (projection.reviewValidationForGrant(grant.grantId()).isPresent()) {
                continue;
            }
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("state", projection.grantAvailable(grant.grantId()) ? "GRANT_AVAILABLE" : "VALIDATION_REQUIRED");
            action.put("nextProtocolAction", projection.grantAvailable(grant.grantId()) ? "request_coordination" : "review_decision");
            action.put("nextProtocolKind", projection.grantAvailable(grant.grantId()) ? "work_group_join" : "review_validation");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("grantId", grant.grantId().toString());
            payload.put("intentId", grant.targetIntentId().toString());
            payload.put("reviewedIntentId", grant.targetIntentId().toString());
            payload.put("reviewedParticipantId", reviewedParticipant);
            payload.put("reviewerParticipant", grant.targetParticipant());
            payload.put("claimEpoch", grant.claimEpoch());
            if (snapshot != null) {
                payload.put("snapshotId", snapshot.snapshotId());
            }
            if (projection.grantAvailable(grant.grantId())) {
                payload.put("workGroupId", grant.workGroupId().toString());
                payload.put("targetParticipant", grant.targetParticipant());
            } else {
                TaskSnapshotRecord reviewedSnapshot = Objects.requireNonNull(snapshot, "review snapshot");
                action.put("reviewDecision", Map.of("required", true, "field", "result", "allowedResults", List.of("accepted", "rejected"), "rejectionReasonRequired", true));
                action.put("reviewAccess",
                        Map.of("workspace",
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
            action.put("grant", CollaborationResponseMapper.laneGrantMap(grant));
            if (snapshot != null) {
                action.put("snapshot", CollaborationResponseMapper.snapshotMap(snapshot, store));
            }
            actions.add(action);
        }
        if (actions.isEmpty() && !participantId.isBlank()) {
            Set<String> completedReviewRevisions = new LinkedHashSet<>();
            Set<UUID> pendingReviewIntents = new LinkedHashSet<>();
            for (LaneGrant grant : projection.grants()) {
                if (grant.targetParticipant().equals(participantId) && !grantTargetsSupersededIntent(store, grant) && projection.reviewValidationForGrant(grant.grantId()).isPresent()) {
                    completedReviewRevisions.add(grant.targetIntentId() + ":" + grant.claimEpoch());
                }
            }
            for (CoordinationRequest request : store.collaborationProjection().requests()) {
                if (request.status() == CoordinationRequest.Status.PENDING && request.kind() == CoordinationRequest.Kind.REVIEW && request.requester().equals(participantId)) {
                    pendingReviewIntents.add(request.conflictingIntentId());
                }
            }
            List<WorkIntent> callerReviewers = activeIntents.stream().filter(intent -> intent.participant().equals(participantId)).filter(intent -> intent.role() == WorkIntent.Role.REVIEWER)
                    .filter(intent -> reviewGroupFilter == null || reviewGroupFilter.contains(intent.workGroupId())).toList();
            for (WorkIntent reviewer : callerReviewers) {
                WorkGroup group = projection.group(reviewer.workGroupId()).orElse(null);
                if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                    continue;
                }
                WorkIntent owner = selectReviewTarget(store, activeIntents, reviewer, participantId, completedReviewRevisions, pendingReviewIntents);
                if (owner == null || group.reviewPolicy().mandatory()) {
                    continue;
                }
                actions.add(reviewAdmissionAction(group, owner, participantId));
            }
        }
        return List.copyOf(actions);
    }

static List<Map<String, Object>> reviewCandidates(PredictionEventStore store) {
        record SnapshotProducer(TaskSnapshotRecord snapshot, WorkIntent producer) {

        }

        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        var completion = store.taskCompletionProjection();
        return completion.allSnapshots().stream().filter(TaskSnapshotRecord::reviewRequired)
                .filter(snapshot -> completion.snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.REVIEW_PENDING)
                .filter(snapshot -> workGroups.group(snapshot.provenance().workGroupId()).map(group -> group.status() == WorkGroup.Status.ACTIVE).orElse(false)).flatMap(snapshot -> {
                    WorkIntent producer = reviewProducerIntent(store, snapshot);
                    return producer == null ? Stream.empty() : Stream.of(new SnapshotProducer(snapshot, producer));
                }).filter(source -> workGroups.grants().stream()
                        .noneMatch(grant -> !workGroups.grantSuperseded(grant.grantId()) && !workGroups.continuationGrant(grant.grantId()) && (source.snapshot().snapshotId().equals(grant.snapshotId()) || (
                                grant.snapshotId() == null && grant.workGroupId().equals(source.snapshot().provenance().workGroupId()) && grant.targetIntentId().equals(source.snapshot().provenance().laneId())
                                        && grant.claimEpoch() == source.snapshot().provenance().claimEpoch())) && (workGroups.grantAvailable(grant.grantId()) || workGroups.grantConsumed(grant.grantId()))))
                .filter(source -> collaboration.requests().stream()
                        .noneMatch(request -> request.kind() == CoordinationRequest.Kind.REVIEW && source.snapshot().snapshotId().equals(request.snapshotId()) && (request.status() == CoordinationRequest.Status.PENDING
                                || request.status() == CoordinationRequest.Status.ACCEPTED))).sorted(Comparator.comparing(source -> source.snapshot().snapshotId())).map(source -> {
                    TaskSnapshotRecord snapshot = source.snapshot();
                    WorkGroup group = workGroups.group(snapshot.provenance().workGroupId()).orElseThrow();
                    WorkIntent producer = source.producer();
                    Map<String, Object> metadata = new LinkedHashMap<>(CollaborationResponseMapper.snapshotMap(snapshot, store));
                    metadata.put("candidateState", "AVAILABLE");
                    metadata.put("authorityLineageId", snapshot.provenance().authorityLineageId().toString());
                    metadata.put("reviewPolicy", Map.of("mandatory", group.reviewPolicy().mandatory(), "independenceRequired", group.reviewPolicy().independenceRequired()));
                    metadata.put("reviewerIntent", reviewerIntentGuidance(group, snapshot));
                    metadata.put("requestCoordination",
                            Map.of("kind",
                                    "work_group_join",
                                    "payload",
                                    Map.of("workGroupId",
                                            snapshot.provenance().workGroupId().toString(),
                                            "intentId",
                                            producer.intentId().toString(),
                                            "snapshotId",
                                            snapshot.snapshotId(),
                                            "proposal",
                                            "Review this exact immutable snapshot")));
                    return metadata;
                }).toList();
    }

static List<Map<String, Object>> actionableReviewCandidates(PredictionEventStore store, String participantId, List<Map<String, Object>> candidates) {
        WorkIntent reviewer = store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participantId)).filter(intent -> intent.role() == WorkIntent.Role.REVIEWER).findFirst()
                .orElse(null);
        if (reviewer == null) {
            return List.of();
        }
        return candidates.stream().filter(candidate -> reviewer.workGroupId().toString().equals(candidate.get("workGroupId")))
                .filter(candidate -> store.taskCompletionProjection().findSnapshotById(String.valueOf(candidate.get("snapshotId"))).filter(snapshot -> reviewTargetsMatch(reviewer, snapshot)).isPresent()).toList();
    }

static WorkIntent reviewProducerIntent(PredictionEventStore store, TaskSnapshotRecord snapshot) {
        var collaboration = store.collaborationProjection();
        var provenance = snapshot.provenance();
        UUID intentId = provenance.laneId();
        Set<UUID> visited = new LinkedHashSet<>();
        while (visited.add(intentId)) {
            var successor = collaboration.successorIntent(intentId);
            if (successor.isEmpty()) {
                return collaboration.intent(intentId).filter(intent -> intent.role() == WorkIntent.Role.PRODUCER).filter(intent -> intent.workGroupId().equals(provenance.workGroupId()))
                        .filter(intent -> intent.taskId().equals(snapshot.taskId())).filter(intent -> intent.authorityLineageId().equals(provenance.authorityLineageId()))
                        .filter(intent -> intent.version() >= provenance.claimEpoch()).orElse(null);
            }
            intentId = successor.orElseThrow();
        }
        return null;
    }

static Map<String, Object> reviewerIntentGuidance(WorkGroup group, TaskSnapshotRecord snapshot) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("goal", "Independently review this candidate against the WorkGroup acceptance criteria");
        task.put("acceptance", group.acceptance());
        task.put("workGroupId", group.workGroupId().toString());
        task.put("role", WorkIntent.Role.REVIEWER.wireValue());
        task.put("reviewTargets", snapshotReviewTargets(snapshot));

        Map<String, Object> claimRequirement = new LinkedHashMap<>();
        claimRequirement.put("field", "task.claims");
        claimRequirement.put("required", true);
        claimRequirement.put("purpose", "review report or test evidence");
        claimRequirement.put("selectorKinds", List.of("path_exact", "path_subtree"));
        claimRequirement.put("mustBeDisjointFrom", List.of("producer snapshot paths", "all active claims"));
        claimRequirement.put("guidance", "Choose a report or test path to claim; Synesis will validate that the claim is disjoint.");

        return Map.of("snapshotId", snapshot.snapshotId(), "tool", "ensure_session", "arguments", Map.of("task", task), "claimRequirement", claimRequirement);
    }

static List<Map<String, Object>> snapshotReviewTargets(TaskSnapshotRecord snapshot) {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (String encoded : snapshot.provenance().claimSelectors()) {
            int separator = encoded.indexOf(':');
            if (separator <= 0 || separator == encoded.length() - 1) {
                continue;
            }
            try {
                ResourceSelector selector = new ResourceSelector(ResourceSelector.Kind.valueOf(encoded.substring(0, separator)), encoded.substring(separator + 1));
                targets.add(Map.of("kind", selector.kind().name().toLowerCase(Locale.ROOT), "path", selector.value()));
            } catch (IllegalArgumentException ignored) {
                // Invalid historical selectors are omitted from agent guidance; server-side review
                // admission still validates the immutable snapshot provenance.
            }
        }
        return List.copyOf(targets);
    }

/**
     * Selects one semantically identified producer without using arrival order as a fallback.
     */
    static WorkIntent selectReviewTarget(PredictionEventStore store,
            List<WorkIntent> activeIntents,
            WorkIntent reviewer,
            String reviewerParticipant,
            Set<String> completedReviewRevisions,
            Set<UUID> pendingReviewIntents) {
        List<WorkIntent> candidates = activeIntents.stream().filter(intent -> intent.role() == WorkIntent.Role.PRODUCER).filter(intent -> intent.workGroupId().equals(reviewer.workGroupId()))
                .filter(intent -> !intent.participant().equals(reviewerParticipant)).filter(intent -> !completedReviewRevisions.contains(intent.intentId() + ":" + intent.version()))
                .filter(intent -> !pendingReviewIntents.contains(intent.intentId())).toList();
        List<WorkIntent> publishedReviewCandidates = candidates.stream().filter(candidate -> hasPublishedReviewRequiredSnapshot(store, candidate)).toList();
        if (!publishedReviewCandidates.isEmpty()) {
            candidates = publishedReviewCandidates;
        }
        if (reviewer.reviewTargetSelectors().isEmpty()) {
            return candidates.size() == 1 ? candidates.getFirst() : null;
        }
        List<WorkIntent> matching = candidates.stream().filter(candidate -> reviewer.reviewTargetSelectors().stream().allMatch(target -> candidate.selectors().stream().anyMatch(target::overlaps))).toList();
        return matching.size() == 1 ? matching.getFirst() : null;
    }

static boolean hasPublishedReviewRequiredSnapshot(PredictionEventStore store, WorkIntent producer) {
        return store.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.taskId().equals(producer.taskId()))
                .filter(snapshot -> snapshot.provenance().authorityLineageId().equals(producer.authorityLineageId())).filter(snapshot -> snapshot.provenance().claimEpoch() <= producer.version())
                .anyMatch(org.synesis.coordination.domain.task.TaskSnapshotRecord::reviewRequired);
    }

/**
     * Returns whether a reviewer declaration covers the producer intent.
     */
    static boolean reviewTargetsMatch(WorkIntent reviewer, WorkIntent producer) {
        return reviewer.reviewTargetSelectors().stream().allMatch(targetSelector -> producer.selectors().stream().anyMatch(targetSelector::overlaps));
    }

/**
     * Returns whether a reviewer declaration covers an already published snapshot claim.
     */
    static boolean reviewTargetsMatch(WorkIntent reviewer, TaskSnapshotRecord snapshot) {
        return reviewer.reviewTargetSelectors().stream().allMatch(targetSelector -> snapshot.provenance().claimSelectors().stream().anyMatch(encoded -> {
            int separator = encoded.indexOf(':');
            if (separator <= 0 || separator == encoded.length() - 1) {
                return false;
            }
            try {
                ResourceSelector selector = new ResourceSelector(ResourceSelector.Kind.valueOf(encoded.substring(0, separator)), encoded.substring(separator + 1));
                return targetSelector.overlaps(selector);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }));
    }

/**
     * Returns whether a durable accepted review request authorizes this grant recipient.
     */
    static boolean hasExplicitReviewGrant(PredictionEventStore store, LaneGrant grant, String participantId) {
        return store.collaborationProjection().requests().stream().filter(request -> request.kind() == CoordinationRequest.Kind.REVIEW).filter(request -> request.status() == CoordinationRequest.Status.ACCEPTED)
                .filter(request -> request.requester().equals(participantId)).filter(request -> request.conflictingIntentId().equals(grant.targetIntentId()))
                .anyMatch(request -> UUID.nameUUIDFromBytes(("synesis-review-grant:" + request.requestId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(grant.grantId()));
    }

/**
     * Builds an explicit review-admission request for one reviewed producer intent.
     */
    static Map<String, Object> reviewAdmissionAction(WorkGroup group, WorkIntent owner, String reviewerParticipant) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("state", "REVIEW_ADMISSION_REQUIRED");
        action.put("nextProtocolAction", "request_coordination");
        action.put("nextProtocolKind", "work_group_join");
        action.put("nextProtocolPayload",
                Map.of("workGroupId",
                        group.workGroupId().toString(),
                        "intentId",
                        owner.intentId().toString(),
                        "reviewedIntentId",
                        owner.intentId().toString(),
                        "reviewedParticipantId",
                        owner.participant(),
                        "reviewerParticipant",
                        reviewerParticipant,
                        "proposal",
                        "Review the immutable snapshot for this work group"));
        action.put("workGroup", CollaborationResponseMapper.workGroupMap(group));
        return action;
    }

}
