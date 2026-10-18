package org.synesis.workspace.application.agent.workflow;

import static org.synesis.workspace.application.agent.workflow.NextActionSupport.putResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.application.RequesterAnswerPolicy;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.NoChangeCompletion;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ProviderSessionId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CompletionPreparedPayload;
import org.synesis.coordination.domain.task.CompletionUnwoundPayload;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentFailures;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.completion.CompleteTaskRequest;
import org.synesis.workspace.application.agent.completion.CompletionOutcome;
import org.synesis.workspace.application.agent.session.CallerAdmission;
import org.synesis.workspace.application.collaboration.LaneIdentities;
import org.synesis.workspace.application.control.CodeExchange;
import org.synesis.workspace.application.control.CodeRemote;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.IntegrationOrchestrationService;
import org.synesis.workspace.application.project.execution.ExecutionRequest;
import org.synesis.workspace.application.project.execution.ExecutionResult;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.project.execution.ProjectProcessExecutor;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.ProviderSessionTerminalizationService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;
import org.synesis.workspace.application.task.SnapshotRequest;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.application.workspace.ReadinessResult;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;

/**
 * Application service for workers to request task completion.
 *
 * <p>Handles tool calls for {@code synesis.finish_lane}.
 *
 * <p>Executes completion checks, creates immutable task snapshots, and triggers
 * dependency integration.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class AgentTaskCompletionService {

    /** Result key naming the finish argument that was copied wrong. */
    private static final String MISMATCHED_FIELD = "field";
    /** Result key holding the value that argument must have. */
    private static final String EXPECTED_VALUE = "expected";

    private final ProjectApplicationService projectService;
    private final ProviderSessionBindingService bindingService;
    private final TaskSnapshotService snapshotService;
    private final IntegrationOrchestrationService integrationOrchestrationService;
    private final SessionAuthorityResolver authorityResolver;
    private final WorkspaceCollaborationService collaborationService;
    private final ProviderManualService manualService;
    private final ProjectProcessExecutor processExecutor;
    private final AgentNextActionService nextActionService;
    private final WorkspaceReadinessService readinessService;
    private final ProviderSessionTerminalizationService terminalizationService;

    /**
     * Creates an agent task completion service.
     */
    public AgentTaskCompletionService() {
        this.projectService = new ProjectApplicationService();
        this.bindingService = new ProviderSessionBindingService();
        this.snapshotService = new TaskSnapshotService();
        this.integrationOrchestrationService = new IntegrationOrchestrationService();
        this.authorityResolver = new SessionAuthorityResolver(bindingService);
        this.collaborationService = new WorkspaceCollaborationService();
        this.manualService = new ProviderManualService();
        this.processExecutor = new ProjectProcessExecutor();
        this.nextActionService = new AgentNextActionService();
        this.readinessService = new WorkspaceReadinessService(bindingService);
        this.terminalizationService = new ProviderSessionTerminalizationService(bindingService, new org.synesis.workspace.lifecycle.lease.SessionLeaseService());
    }

    private static AgentResponse continuationWithCompletion(AgentResponse completion, AgentResponse continuation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completion", completion.result());
        putResult(result, continuation.result(), "continuation");
        return new AgentResponse(continuation.status(), continuation.reason(), continuation.nextAction(), result);
    }

    private static AgentResponse noChangeCompletionResult(PredictionEventStore store, NoChangeCompletion completion, ExecutionResult validation) {
        Map<String, Object> result = new LinkedHashMap<>();
        var group = store.workGroupProjection().group(completion.workGroupId()).orElse(null);
        result.put("outcome", "NO_CHANGE");
        result.put(NextActionKeys.PARTICIPANT, completion.participant());
        result.put(NextActionKeys.INTENT_ID, completion.intentId().toString());
        result.put(NextActionKeys.LANE_ID, completion.intentId().toString());
        result.put(NextActionKeys.WORK_GROUP_ID, completion.workGroupId().toString());
        result.put(NextActionKeys.CLAIM_EPOCH, completion.claimEpoch());
        result.put(NextActionKeys.WORK_GROUP_VERSION, completion.workGroupVersion());
        result.put(NextActionKeys.EXPECTED_REVISION, completion.expectedRevision());
        result.put(NextActionKeys.SNAPSHOT_STATE, NextActionProtocol.STATE_NOT_REQUIRED);
        result.put("integrationState", NextActionProtocol.STATE_NOT_REQUIRED);
        result.put("claimsReleased", true);
        result.put("workGroupState", group == null ? "UNKNOWN" : group.status().name());
        result.put(NextActionKeys.SUMMARY, completion.summary());
        if (validation != null) {
            result.put(NextActionKeys.PRE_PUBLICATION_VALIDATION, validation.toMap());
        }
        return new AgentResponse(AgentStatus.COMPLETED, null, null, result);
    }

    private static boolean noChangeEvidenceMatches(CompleteTaskRequest request, ProviderSessionBindingService.Binding binding, NoChangeCompletion completion) {
        return request.outcome() == CompletionOutcome.NO_CHANGE && request.hasCompleteEvidence() && request.expectedIntentId().equals(completion.intentId())
                && request.expectedWorkGroupId().equals(completion.workGroupId()) && request.expectedClaimEpoch() == completion.claimEpoch() && request.expectedWorkGroupVersion() == completion.workGroupVersion()
                && request.expectedRevision() == completion.expectedRevision() && request.expectedParticipant().equals(completion.participant()) && request.provider().equals(completion.provider())
                && new ProviderSessionId(binding.sessionId()).equals(completion.bindingSessionIdentity()) && binding.baseCommit().equals(completion.workspaceCommit()) && normalizedSummary(request.summary()).equals(
                completion.summary());
    }

    private static String normalizedSummary(String summary) {
        return summary == null || summary.isBlank() ? "Completed successfully without repository mutation" : summary.trim();
    }

    private static boolean isNoChangeDenial(String reason) {
        return reason.startsWith("NO_CHANGE_") || reason.equals(NextActionProtocol.REASON_NO_ACTIVE_INTENT) || reason.equals(NextActionProtocol.REASON_WORK_GROUP_NOT_FOUND) || reason.equals(NextActionProtocol.REASON_WORK_GROUP_NOT_ACTIVE) || reason.equals(NextActionProtocol.REASON_WORK_GROUP_VERSION_STALE)
                || reason.equals(NextActionProtocol.REASON_COMPLETION_REVISION_STALE) || reason.equals("INTENT_NOT_FOUND");
    }

    private static AgentResponse noChangeDenied(String reason, ExecutionResult validation) {
        AgentReason publicReason;
        AgentNextAction nextAction;
        AgentStatus status;
        if (reason.contains("WORKSPACE") || reason.contains("REVISION_STALE") || reason.equals(NextActionProtocol.REASON_WORK_GROUP_VERSION_STALE) || reason.equals(NextActionProtocol.REASON_COMPLETION_REVISION_STALE)) {
            publicReason = reason.contains("GENERATION") ? AgentReason.WORKSPACE_GENERATION_CHANGED : AgentReason.TASK_NOT_READY;
            nextAction = AgentNextAction.RETRY;
            status = AgentStatus.RETRY_REQUIRED;
        } else if (reason.equals("NO_CHANGE_NOT_AUTHORIZED") || reason.contains("PARTICIPANT_MISMATCH") || reason.contains("EVIDENCE_REQUIRED") || reason.contains("EVIDENCE_MISMATCH") || reason.contains("CONFLICT")) {
            publicReason = AgentReason.POLICY_DENIED;
            nextAction = AgentNextAction.REQUEST_HUMAN_HELP;
            status = AgentStatus.BLOCKED;
        } else {
            publicReason = AgentReason.TASK_NOT_READY;
            nextAction = AgentNextAction.RETRY;
            status = AgentStatus.BLOCKED;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(NextActionKeys.REASON, reason);
        if (validation != null) {
            result.put(NextActionKeys.PRE_PUBLICATION_VALIDATION, validation.toMap());
        }
        return new AgentResponse(status, publicReason, nextAction, result);
    }

    /**
     * Events that cannot make a projected finish stale. Liveness and takeover of a lane are fenced
     * by the envelope's own intent, epoch and participant. Another lane's announcement cannot touch
     * this one, whose claims are exclusive. Capability events are judged afresh by the readiness
     * check, which reads the same current log before the envelope is examined. Another lane being
     * integrated and released changes neither this lane nor what it may publish, and this lane's
     * own group closing is fenced by the group version. A prepared completion or a new snapshot
     * still makes the envelope stale: it may be this lane's own, and the event does not say whose.
     */
    private static final java.util.Set<PredictionEventType> NOT_STALING_A_FINISH = java.util.Set.of(PredictionEventType.PARTICIPANT_HEARTBEAT,
            PredictionEventType.PARTICIPANT_SUSPENDED,
            PredictionEventType.RECOVERY_SNAPSHOT_HELD,
            PredictionEventType.PARTICIPANT_DETACHED,
            PredictionEventType.LANE_CONTINUATION_ACCEPTED,
            PredictionEventType.WORK_INTENT_ANNOUNCED,
            PredictionEventType.WORK_GROUP_CREATED,
            PredictionEventType.WORK_GROUP_STATUS_CHANGED,
            PredictionEventType.WORK_INTENT_RELEASED,
            PredictionEventType.TASK_WAITING_FOR_DEPENDENCIES,
            PredictionEventType.INTEGRATION_ATTEMPT_STARTED,
            PredictionEventType.INTEGRATION_COMMIT_CREATED,
            PredictionEventType.CONTROL_BRANCH_ADVANCED,
            PredictionEventType.TASK_INTEGRATED,
            PredictionEventType.SESSION_FINALIZED,
            PredictionEventType.PROVIDER_SESSION_TERMINALIZED,
            PredictionEventType.CAPABILITY_REQUEST_CREATED,
            PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED,
            PredictionEventType.CAPABILITY_REQUEST_ACCEPTED,
            PredictionEventType.CAPABILITY_REQUEST_SUPERSEDED,
            PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED,
            PredictionEventType.CAPABILITY_VALIDATION_STARTED,
            PredictionEventType.CAPABILITY_IMPLEMENTATION_VALIDATED,
            PredictionEventType.CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED,
            PredictionEventType.OPERATOR_REQUEST_RAISED,
            PredictionEventType.OPERATOR_REQUEST_CLOSED,
            PredictionEventType.CONTRACT_PUBLISHED,
            PredictionEventType.CONTRACT_DEPENDENCY_BOUND,
            PredictionEventType.CONTRACT_CHANGE_REQUESTED,
            PredictionEventType.CONTRACT_CHANGE_ANSWERED,
            PredictionEventType.CONTRACT_LANE_ENDED,
            PredictionEventType.CONVERSATION_MESSAGE_SENT,
            PredictionEventType.CONVERSATION_MESSAGES_DELIVERED);

    /**
     * Reports whether a finish projected at {@code revision} still describes the log.
     *
     * <p>Requiring that nothing but heartbeats followed let any other agent's activity invalidate
     * the envelope. With five agents announcing and negotiating, an owner's finish was refused as
     * stale twelve times in a row and it gave up with its work unpublished; with two, a requester
     * was refused three times while its owner's lane was being integrated. A review decision, a
     * grant, a coordination request, a scope change, a failed or blocked integration, or any event
     * not listed still makes the envelope stale.</p>
     */
    private static boolean finishEnvelopeStillHolds(PredictionEventStore store, long revision) {
        List<org.synesis.coordination.domain.prediction.PredictionEvent> events = store.events();
        return revision >= 0L && revision <= events.size()
                && events.subList((int) revision, events.size()).stream().allMatch(event -> NOT_STALING_A_FINISH.contains(event.type()));
    }

    /**
     * Validates optional server-issued finish evidence for the current lane revision.
     */
    private static AgentResponse validateSnapshotEvidence(CompleteTaskRequest request, WorkIntent intent, PredictionEventStore store, String participantHandle) {
        boolean correctionRequired = store.taskCompletionProjection().allSnapshots().stream()
                .anyMatch(snapshot -> snapshot.taskId().equals(intent.taskId()) && snapshot.provenance().authorityLineageId().equals(intent.authorityLineageId()) && snapshot.provenance().claimEpoch() < intent.version()
                        && store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE).refused());
        if (!request.hasAnyEvidence() && !correctionRequired) {
            return null;
        }
        if (!request.hasCompleteEvidence()) {
            return snapshotEvidenceDenied(request, NextActionProtocol.REASON_SNAPSHOT_COMPLETION_EVIDENCE_REQUIRED);
        }
        Optional<Map<String, Object>> mistyped = mistypedEvidence(request, intent, participantHandle);
        if (mistyped.isPresent()) {
            return evidenceMismatch(NextActionProtocol.REASON_SNAPSHOT_COMPLETION_EVIDENCE_MISMATCH, mistyped.get());
        }
        WorkGroup group = store.workGroupProjection().group(intent.workGroupId()).orElse(null);
        if (group == null) {
            return snapshotEvidenceDenied(request, NextActionProtocol.REASON_WORK_GROUP_NOT_FOUND);
        }
        if (!request.expectedIntentId().equals(intent.intentId()) || !request.expectedWorkGroupId().equals(intent.workGroupId()) || !request.expectedClaimEpoch().equals(intent.version())
                || !request.expectedWorkGroupVersion().equals(group.version()) || !finishEnvelopeStillHolds(store, request.expectedRevision()) || !request.expectedParticipant().equals(participantHandle)
                || !intent.participant().equals(participantHandle) || intent.status() != WorkIntent.Status.ANNOUNCED) {
            return snapshotEvidenceDenied(request, NextActionProtocol.REASON_SNAPSHOT_COMPLETION_EVIDENCE_STALE);
        }
        return null;
    }

    /**
     * Names the first finish argument that can only have been copied wrong, never gone stale: the caller's own
     * participant and its lane's work group do not change while the lane is open. Answered as stale, which sends
     * the agent back to the same projection, a Codex agent that mistyped its participant three times in a row was
     * shown the same action each time and stopped with its tested work unpublished (ADR-0098 step 4, item 22).
     */
    private static Optional<Map<String, Object>> mistypedEvidence(CompleteTaskRequest request, WorkIntent intent, String participantHandle) {
        if (!request.expectedParticipant().equals(participantHandle)) {
            return Optional.of(Map.of(MISMATCHED_FIELD, NextActionKeys.PARTICIPANT, EXPECTED_VALUE, participantHandle));
        }
        if (!request.expectedWorkGroupId().equals(intent.workGroupId())) {
            return Optional.of(Map.of(MISMATCHED_FIELD, NextActionKeys.WORK_GROUP_ID, EXPECTED_VALUE, intent.workGroupId().toString()));
        }
        return Optional.empty();
    }

    /**
     * Refuses a finish whose argument was copied wrong, naming it, so the agent corrects it and calls again.
     */
    private static AgentResponse evidenceMismatch(String reason, Map<String, Object> mistyped) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(NextActionKeys.REASON, reason);
        result.putAll(mistyped);
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, result);
    }

    /**
     * Creates the fail-closed public response for stale snapshot evidence.
     */
    private static AgentResponse snapshotEvidenceDenied(CompleteTaskRequest request, String reason) {
        AgentResponse response = new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, Map.of(NextActionKeys.REASON, reason));
        return NextActionProtocol.REASON_SNAPSHOT_COMPLETION_EVIDENCE_STALE.equals(reason) ? new AgentWorkflowReducer().decorate(new NextActionRequest(request.projectRoot(), request.provider(), request.connectionInstanceId()), response)
                : response;
    }

    private static AgentResponse completionResult(PredictionEventStore store, TaskSnapshotRecord snapshot, String participantHandle, AgentResponse integrationResult, ExecutionResult prePublicationValidation) {
        Map<String, Object> result = new LinkedHashMap<>();
        putResult(result, integrationResult.result(), null);
        result.put(NextActionKeys.PARTICIPANT, participantHandle);
        result.put(NextActionKeys.LANE_ID, snapshot.provenance().laneId().toString());
        result.put(NextActionKeys.CLAIM_EPOCH, snapshot.provenance().claimEpoch());
        result.put(NextActionKeys.SNAPSHOT_ID, snapshot.snapshotId());
        TaskCompletionState state = store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE);
        result.put(NextActionKeys.SNAPSHOT_STATE,
                snapshot.reviewRequired() ? state.value().toUpperCase(java.util.Locale.ROOT) : state == TaskCompletionState.INTEGRATED ? "PUBLISHED" : state.value().toUpperCase(java.util.Locale.ROOT));
        result.put(NextActionKeys.REVIEW_REQUIRED, snapshot.reviewRequired());
        result.put("integrationState", state.value());
        if (prePublicationValidation != null) {
            result.put(NextActionKeys.PRE_PUBLICATION_VALIDATION, prePublicationValidation.toMap());
        }
        if (integrationResult.nextAction() != null) {
            result.put("nextAction", integrationResult.nextAction().value());
        }
        return new AgentResponse(integrationResult.status(), integrationResult.reason(), integrationResult.nextAction(), result);
    }

    private static UUID findActiveTaskId(PredictionEventStore store, String callerNodeId, String callerWorkerId, ProviderSessionBindingService.Binding binding) {
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (intent.participant().equals(participant)) {
                return intent.taskId();
            }
        }
        var tasks = store.coordinationProjection().tasks();
        for (var entry : tasks.entrySet()) {
            var claim = entry.getValue().claim();
            if (claim != null && claim.ownerNodeId().equals(callerNodeId) && claim.ownerWorkerId().equals(callerWorkerId)) {
                return entry.getKey();
            }
        }
        return binding.taskId();
    }

    static String completionId(UUID laneId, long claimEpoch, String commitSha) {
        return "cmp_" + UUID.nameUUIDFromBytes((laneId + "\n" + claimEpoch + "\n" + commitSha).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Executes task completion checks, snapshot creation, and integration orchestration.
     *
     * @param request complete task request
     * @return concise agent response
     */
    public AgentResponse completeTask(CompleteTaskRequest request) {
        Objects.requireNonNull(request, "request");

        try {
            manualService.requireAttested(request.provider());
        } catch (Exception failure) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, Map.of(NextActionKeys.REASON, "MANUAL_ATTESTATION_REQUIRED"));
        }
        CallerAdmission admission = CallerAdmission.admit(request.projectRoot(), projectService, authorityResolver, request.provider(), request.connectionInstanceId(), true);
        if (admission.refused()) {
            return admission.refusal();
        }
        ProjectLocation location = admission.location();
        ProviderSessionBindingService.Binding binding = admission.binding();
        boolean terminalRetry = admission.completedBinding();
        NodeIdentity identity = admission.identity();

        String callerNodeId = identity.nodeId();
        String callerSupervisorId = binding.supervisorId();
        String callerWorkerId = binding.workerId();
        Path workerWorktreePath = Path.of(binding.worktreePath()).toAbsolutePath().normalize();

        try {
            PredictionEventStore store = location.openCoordinationStore();
            ExecutionResult prePublicationValidation = null;

            if (terminalRetry) {
                return completedBindingResult(request, store, binding);
            }

            AgentResponse readinessFailure = completionReadinessFailure(store,
                    callerNodeId,
                    callerSupervisorId,
                    callerWorkerId,
                    WorkspaceCollaborationService.participantHandle(binding.sessionId()));
            if (readinessFailure != null) {
                return readinessFailure;
            }

            // 3. Create or recover immutable task snapshot
            String summaryText = (request.summary() != null && !request.summary().isBlank()) ? request.summary().trim() : "Completed task implementation";

            List<CapabilityRequestRecord> workerCapabilities = store.capabilityRequestProjection().findAllForRequester(callerNodeId).stream()
                    .filter(requestRecord -> requestRecord.matchesRequester(callerNodeId, callerSupervisorId, callerWorkerId))
                    // A request its owner superseded by finishing is not a dependency of this
                    // snapshot: nothing was agreed, and the owner's work is already in the base.
                    .filter(requestRecord -> !requestRecord.ownerLaneEnded()).toList();
            // Collaboration participants are derived from the verified durable
            // session binding, never from the transient MCP connection ID.
            // Using the connection here made an otherwise valid claimed lane
            // appear unowned at completion time.
            String participantHandle = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            List<WorkIntent> participantIntents = store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participantHandle)).toList();
            Optional<WorkIntent> laneIntent;
            if (request.expectedIntentId() != null) {
                WorkIntent exact = store.collaborationProjection().intent(request.expectedIntentId()).orElse(null);
                if (exact == null || !exact.participant().equals(participantHandle)) {
                    return snapshotEvidenceDenied(request, NextActionProtocol.REASON_SNAPSHOT_COMPLETION_EVIDENCE_STALE);
                }
                laneIntent = Optional.of(exact);
            } else if (participantIntents.size() > 1) {
                return snapshotEvidenceDenied(request, NextActionProtocol.REASON_SNAPSHOT_COMPLETION_EVIDENCE_REQUIRED);
            } else {
                laneIntent = participantIntents.stream().findFirst();
            }
            UUID taskId = laneIntent.map(WorkIntent::taskId).orElse(null);
            if (taskId == null) {
                taskId = findActiveTaskId(store, callerNodeId, callerWorkerId, binding);
            }
            if (store.collaborationProjection().activated() && laneIntent.isEmpty()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COORDINATION_INTENT_REQUIRED, AgentNextAction.ENSURE_SESSION, Map.of(NextActionKeys.REASON, "COORDINATION_INTENT_REQUIRED"));
            }
            UUID completionTaskId = taskId;
            List<ResourceSelector> currentClaims = laneIntent.map(WorkIntent::selectors).orElse(List.of());
            Optional<TaskSnapshotRecord> existingOpt = laneIntent.flatMap(intent -> store.taskCompletionProjection().findSnapshotForTaskRevision(completionTaskId, intent.intentId(), intent.version()));
            if (laneIntent.isEmpty()) {
                existingOpt = store.taskCompletionProjection().findSnapshotForTask(taskId);
            }

            if (request.outcome() == CompletionOutcome.NO_CHANGE) {
                return ReviewNotes.onNoChangeRefused(completeNoChangeTask(request, location, identity, participantHandle, laneIntent), store, participantHandle);
            }

            if (laneIntent.isPresent()) {
                AgentResponse evidenceFailure = validateSnapshotEvidence(request, laneIntent.get(), store, participantHandle);
                if (evidenceFailure != null) {
                    return evidenceFailure;
                }
            }

            if (existingOpt.isPresent() && store.taskCompletionProjection().snapshotState(existingOpt.get().snapshotId()).orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.INTEGRATED) {
                AgentResponse result = completionResult(store, existingOpt.get(), participantHandle, new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of("task", "already_integrated")), null);
                bindingService.complete(location, request.provider(), request.connectionInstanceId());
                return sealAndContinue(result, request, location, binding, identity);
            }

            boolean reviewRequired = laneIntent.map(intent -> AgentReviewActionProjection.reviewRequired(store, intent, participantHandle)).orElse(false);
            List<ResourceSelector> participantClaims = store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participantHandle))
                    .flatMap(intent -> intent.selectors().stream()).distinct().toList();
            List<ResourceSelector> foreignClaims = store.collaborationProjection().activeIntents().stream().filter(intent -> !intent.participant().equals(participantHandle)).flatMap(intent -> intent.selectors().stream())
                    .distinct().toList();
            boolean sharedProducerWorktree =
                    store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participantHandle)).filter(intent -> intent.role() == WorkIntent.Role.PRODUCER).count() > 1;
            boolean scopeToLane = laneIntent.isPresent() && (sharedProducerWorktree || !foreignClaims.isEmpty());
            String snapshotBaseCommit = laneIntent.map(WorkIntent::baseCommit).orElse(null);
            if (laneIntent.isPresent()) {
                try {
                    snapshotBaseCommit = snapshotService.snapshotBaseForBoundWorktree(workerWorktreePath, snapshotBaseCommit, binding.baseCommit());
                } catch (IOException inspectionFailure) {
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, Map.of(NextActionKeys.REASON, NextActionProtocol.REASON_SNAPSHOT_DIFF_INSPECTION_FAILED));
                }
            }
            if (scopeToLane) {
                try {
                    if (!snapshotService.hasScopedPublishableChanges(workerWorktreePath, currentClaims, participantClaims, foreignClaims, snapshotBaseCommit)) {
                        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, Map.of(NextActionKeys.REASON, "UNCLAIMED_SNAPSHOT_PATH"));
                    }
                } catch (IOException inspectionFailure) {
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, Map.of(NextActionKeys.REASON, NextActionProtocol.REASON_SNAPSHOT_DIFF_INSPECTION_FAILED));
                }
            }

            // Project-owned validation is a server gate. It runs through the
            // same direct argv primitive exposed by run_command, against the
            // authenticated lane worktree, before any snapshot can become
            // prepared or visible to integration. A lane whose tests need a
            // sibling's code cannot pass it alone, so it runs here only when
            // the project asks for it; it always runs on the combined tree
            // before the control branch moves.
            if (location.validation() != null && location.validationAlsoInLane()) {
                prePublicationValidation = processExecutor.execute(ExecutionRequest.from(location.validation(), workerWorktreePath, location.root()));
                if (!prePublicationValidation.succeeded()) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put(NextActionKeys.PHASE, NextActionProtocol.PHASE_PRE_PUBLICATION);
                    result.put("validation", prePublicationValidation.toMap());
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.VALIDATION_FAILED, AgentNextAction.RETRY, result);
                }
            }

            TaskSnapshotRecord snapshot;
            try {
                snapshot = snapshotService.createSnapshot(new SnapshotRequest(taskId,
                        callerNodeId,
                        callerSupervisorId,
                        callerWorkerId,
                        binding.sessionId(),
                        workerWorktreePath,
                        location.root(),
                        summaryText,
                        existingOpt,
                        workerCapabilities,
                        new SnapshotRequest.ClaimScope(currentClaims, foreignClaims, scopeToLane),
                        new SnapshotRequest.Lane(laneIntent.map(WorkIntent::workGroupId).orElse(taskId),
                                laneIntent.map(WorkIntent::intentId).orElse(taskId),
                                participantHandle,
                                binding.sessionId(),
                                laneIntent.map(WorkIntent::version).orElse(1L),
                                laneIntent.map(WorkIntent::authorityLineageId).orElse(org.synesis.coordination.domain.collaboration.WorkIntent.defaultAuthorityLineage(taskId)),
                                List.of()),
                        reviewRequired,
                        snapshotBaseCommit,
                        null));
            } catch (IllegalStateException immutabilityError) {
                // Task snapshot is immutable and content changed after completion
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, null);
            }

            return persistAndIntegrateSnapshot(request, location, binding, identity, store, snapshot, existingOpt.isPresent(), workerWorktreePath, prePublicationValidation);

        } catch (Exception ex) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("error", ex.getClass().getSimpleName());
            failure.put("message", ex.getMessage() == null ? "unknown completion failure" : ex.getMessage());
            return AgentFailures.internal(request.projectRoot(), "lane.finish", ex, failure);
        }
    }

    /**
     * Durably prepares a newly created snapshot and starts its integration workflow.
     *
     * @param request                   original completion request
     * @param location                  verified project location
     * @param binding                   exact provider session binding
     * @param identity                  caller's durable node identity
     * @param store                     project event store
     * @param snapshot                  immutable snapshot produced for the task
     * @param snapshotAlreadyExists     whether an immutable snapshot already existed for this lane
     * @param worktree                  verified worker worktree
     * @param prePublicationValidation successful owner-defined validation, if configured
     * @return integration and completion result
     * @throws Exception when durable preparation or integration orchestration fails
     */
    private AgentResponse persistAndIntegrateSnapshot(CompleteTaskRequest request,
            ProjectLocation location,
            ProviderSessionBindingService.Binding binding,
            NodeIdentity identity,
            PredictionEventStore store,
            TaskSnapshotRecord snapshot,
            boolean snapshotAlreadyExists,
            Path worktree,
            ExecutionResult prePublicationValidation) throws Exception {
        String callerNodeId = identity.nodeId();
        String participantHandle = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        UUID taskId = snapshot.taskId();

        // On a computer that exchanges code, the commits the next entries name stay here for the others to
        // fetch, and reach a mirror first when there is one (ADR-0098). Settings that cannot be read are known
        // before anything is pinned or proposed.
        Optional<CodeExchange> codeExchange;
        try {
            codeExchange = CodeExchange.of(location.coordinationDirectory(), location.projectId());
        } catch (CodeRemote.Refused refused) {
            if (!snapshotAlreadyExists) {
                snapshotService.removeSnapshotRef(worktree, snapshot);
            }
            return codeRemoteRefusal(refused);
        }

        // A completion transaction must publish a complete lane diff. A clean lane cannot create a
        // useful implementation snapshot or release its authority without preserving any work.
        if (snapshot.changedPaths().isEmpty()) {
            if (!snapshotAlreadyExists) {
                snapshotService.removeSnapshotRef(worktree, snapshot);
            }
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, Map.of(NextActionKeys.REASON, "NO_CHANGES_TO_PUBLISH"));
        }

        // A requester validated an exact implementation of this lane, and its own work can only be
        // integrated once that content is. A snapshot that changed a validated file would leave the
        // requester waiting for content that can no longer arrive, with this lane already closed.
        for (CapabilityRequestRecord validated : store.capabilityRequestProjection().records().values()) {
            if (validated.state() != CapabilityLifecycleState.VALIDATED || !validated.authorityLineageId().equals(snapshot.provenance().authorityLineageId())) {
                continue;
            }
            var implementation = store.capabilityRequestProjection().findLatestImplementation(validated.handle()).orElse(null);
            if (implementation == null) {
                continue;
            }
            List<String> changed = IntegrationOrchestrationService.validatedPathsChanged(location.root(), implementation.commitSha(), implementation.changedPaths(), snapshot.commitSha());
            if (!changed.isEmpty()) {
                if (!snapshotAlreadyExists) {
                    snapshotService.removeSnapshotRef(worktree, snapshot);
                }
                Map<String, Object> refused = new LinkedHashMap<>();
                refused.put(NextActionKeys.REASON, "VALIDATED_IMPLEMENTATION_CHANGED");
                refused.put(NextActionKeys.CAPABILITY_REQUEST_HANDLE, validated.handle().value());
                refused.put("changedValidatedPaths", changed);
                refused.put("validatedCommit", implementation.commitSha());
                refused.put("instruction", "The requester validated these files as you published them, and its work can only be integrated with exactly that content. Restore them to the validated commit, then finish.");
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, refused);
            }
        }

        // A stable identity collision is not a second publication. Fail closed instead of
        // advertising another task record over the immutable snapshot ID.
        var sameIdentity = store.taskCompletionProjection().findSnapshotById(snapshot.snapshotId());
        if (sameIdentity.isPresent() && !sameIdentity.get().taskId().equals(snapshot.taskId())) {
            if (!snapshotAlreadyExists) {
                snapshotService.removeSnapshotRef(worktree, snapshot);
            }
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.REQUEST_HUMAN_HELP, Map.of(NextActionKeys.REASON, "SNAPSHOT_ID_COLLISION", NextActionKeys.SNAPSHOT_ID, snapshot.snapshotId()));
        }

        // Pin the exact prepared tree and fence the lane before its snapshot becomes visible to
        // integration.
        String completionId = completionId(snapshot.provenance().laneId(), snapshot.provenance().claimEpoch(), snapshot.commitSha());
        if (!snapshotAlreadyExists) {
            // Direct callers may create a provisional ref; completion moves it behind a durable
            // prepared phase.
            snapshotService.removeSnapshotRef(worktree, snapshot);
        }
        String preparedRef = snapshotService.pinPreparedRef(worktree, snapshot, completionId);
        String preparedTreeHash = snapshotService.treeHash(worktree, snapshot.commitSha());
        if (codeExchange.isPresent()) {
            try {
                codeExchange.get().handIn(worktree, preparedRef);
            } catch (CodeRemote.Refused refused) {
                return codeRemoteRefusal(refused);
            }
        }
        if (store.taskCompletionProjection().findPrepared(taskId, snapshot.provenance().laneId(), snapshot.provenance().claimEpoch()).isEmpty()) {
            CompletionPreparedPayload prepared = new CompletionPreparedPayload(taskId,
                    completionId,
                    snapshot.provenance().laneId(),
                    snapshot.provenance().claimEpoch(),
                    snapshot.baseCommit(),
                    preparedRef,
                    preparedTreeHash,
                    snapshot.changedPaths());
            store.append(UUID.randomUUID(), PredictionEventType.COMPLETION_PREPARED, callerNodeId, prepared.encode(), identity);
        }

        snapshotService.promotePreparedRef(worktree, preparedRef, snapshot.provenance().snapshotRef(), snapshot.commitSha());
        if (codeExchange.isPresent()) {
            try {
                codeExchange.get().handIn(worktree, snapshot.provenance().snapshotRef());
            } catch (CodeRemote.Refused refused) {
                return codeRemoteRefusal(refused);
            }
        }
        if (!snapshotAlreadyExists) {
            TaskSnapshotPayload snapshotPayload = new TaskSnapshotPayload(snapshot.taskId(),
                    snapshot.snapshotId(),
                    snapshot.nodeId(),
                    snapshot.supervisorId(),
                    snapshot.workerId(),
                    snapshot.providerSessionId(),
                    snapshot.baseCommit(),
                    snapshot.commitSha(),
                    snapshot.changedPaths(),
                    snapshot.capabilityDependencies(),
                    snapshot.summary(),
                    snapshot.provenance(),
                    snapshot.reviewRequired());
            store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, callerNodeId, snapshotPayload.encode(), identity);
        }

        AgentResponse result = integrationOrchestrationService.orchestrateIntegration(location.root(), store, identity);
        PredictionEventStore completionStore = new PredictionEventStore(store.rootDirectory(), store.projectId());
        result = completionResult(completionStore, snapshot, participantHandle, result, prePublicationValidation);
        if (result.status() != AgentStatus.COMPLETED) {
            return result;
        }

        // Complete only the exact calling connection. Retaining its worktree prevents the next
        // inbox read from reusing stale write authority.
        bindingService.complete(location, request.provider(), request.connectionInstanceId());
        return sealAndContinue(result, request, location, binding, identity);
    }

    /** Nothing was proposed; an unreachable mirror is tried again, unreadable settings need the operator. */
    private static AgentResponse codeRemoteRefusal(CodeRemote.Refused refused) {
        AgentNextAction next = refused.retryable() ? AgentNextAction.RETRY : AgentNextAction.REQUEST_HUMAN_HELP;
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, next, Map.of(NextActionKeys.REASON, refused.code(), "message", refused.getMessage()));
    }

    /**
     * Reconstructs the durable completion result when the provider binding is already terminal.
     *
     * <p>A completed binding cannot publish new work. It may only replay an already durable
     * no-change completion or integrated snapshot; every other state requires human recovery.</p>
     *
     * @param request original completion request
     * @param store   current durable event store
     * @param binding completed provider binding
     * @return the existing completion response, or a blocked response when no valid completion is
     *         recorded
     */
    private static AgentResponse completedBindingResult(CompleteTaskRequest request, PredictionEventStore store, ProviderSessionBindingService.Binding binding) {
        if (request.outcome() == CompletionOutcome.NO_CHANGE) {
            UUID completedIntentId = LaneIdentities.sessionIntentId(request.provider(), binding.sessionId());
            Optional<NoChangeCompletion> completed = store.collaborationProjection().noChangeCompletion(completedIntentId);
            if (completed.isPresent() && noChangeEvidenceMatches(request, binding, completed.get())) {
                return noChangeCompletionResult(store, completed.get(), null);
            }
            return new AgentResponse(AgentStatus.BLOCKED,
                    AgentReason.TASK_NOT_READY,
                    AgentNextAction.REQUEST_HUMAN_HELP,
                    Map.of(NextActionKeys.REASON, "COMPLETED_BINDING_WITHOUT_NO_CHANGE_COMPLETION"));
        }
        UUID completedTaskId = binding.taskId();
        Optional<TaskSnapshotRecord> completedSnapshot = store.taskCompletionProjection().findSnapshotForTask(completedTaskId);
        if (completedSnapshot.isEmpty()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.REQUEST_HUMAN_HELP, Map.of(NextActionKeys.REASON, "COMPLETED_BINDING_WITHOUT_SNAPSHOT"));
        }
        TaskSnapshotRecord snapshot = completedSnapshot.get();
        TaskCompletionState completedState = store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE);
        if (completedState != TaskCompletionState.INTEGRATED) {
            return new AgentResponse(AgentStatus.BLOCKED,
                    AgentReason.TASK_NOT_READY,
                    AgentNextAction.REQUEST_HUMAN_HELP,
                    Map.of(NextActionKeys.REASON, "COMPLETED_BINDING_WITHOUT_INTEGRATED_SNAPSHOT", NextActionKeys.SNAPSHOT_ID, snapshot.snapshotId(), NextActionKeys.SNAPSHOT_STATE, completedState.value()));
        }
        return completionResult(store,
                snapshot,
                WorkspaceCollaborationService.participantHandle(binding.sessionId()),
                new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of("task", "already_integrated")),
                null);
    }

    /**
     * Returns the first unresolved capability or validation gate that prevents task completion.
     *
     * @param store              current durable event store
     * @param callerNodeId       exact caller node identity
     * @param callerSupervisorId exact caller supervisor identity
     * @param callerWorkerId     exact caller worker identity
     * @return the blocking response, or {@code null} when both readiness checks pass
     */
    private static AgentResponse completionReadinessFailure(PredictionEventStore store,
            String callerNodeId,
            String callerSupervisorId,
            String callerWorkerId,
            String participant) {
        List<CapabilityRequestRecord> pending = store.capabilityRequestProjection().findAllForRequester(callerNodeId).stream()
                .filter(record -> record.matchesRequester(callerNodeId, callerSupervisorId, callerWorkerId))
                .filter(AgentTaskCompletionService::isCompletionBlockingCapability)
                .toList();
        if (!pending.isEmpty()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.UNRESOLVED_DEPENDENCY, AgentNextAction.WAIT, Map.of(NextActionKeys.PENDING, pending.size()));
        }
        WorkIntent callerIntent = store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participant)).findFirst().orElse(null);
        int unansweredChanges = callerIntent == null ? 0 : store.contractProjection().openChangeRequestsFor(callerIntent.authorityLineageId()).size();
        if (unansweredChanges > 0) {
            // Only the lane that holds the file can answer what another lane needs from it, and
            // a lane that finished first would leave that need with nobody to answer (ADR-0092).
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.RESPOND_COORDINATION,
                    Map.of(NextActionKeys.REASON, "CONTRACT_CHANGE_REQUEST_OPEN", NextActionKeys.PENDING, unansweredChanges,
                            "instruction", "Lanes that build on your files have change requests you have not answered. Call get_next_action to see them, and answer each before finishing."));
        }
        List<CapabilityRequestRecord> unjudged = CapabilityActionProjection.ownerImplementationsAwaitingVerdict(store, callerIntent, callerNodeId, callerSupervisorId, callerWorkerId);
        if (!unjudged.isEmpty()) {
            // Same rule the inbox projects: the requester may still send this back, and only an
            // open owner lane can revise it.
            return new AgentResponse(AgentStatus.BLOCKED,
                    AgentReason.VALIDATION_REQUIRED,
                    AgentNextAction.WAIT,
                    Map.of(NextActionKeys.CAPABILITY_REQUEST_HANDLE, unjudged.getFirst().handle().value(), NextActionKeys.REASON, NextActionProtocol.REASON_IMPLEMENTATION_AWAITING_VALIDATION, NextActionKeys.PENDING, unjudged.size()));
        }
        List<CapabilityRequestRecord> unanswered = LaneCompletionProjection.unansweredProposals(store, callerIntent, callerNodeId, callerSupervisorId, callerWorkerId);
        if (!unanswered.isEmpty()) {
            // Same wait the inbox projects: a consumer that has not bound again to the revised
            // contract has not checked this lane's work, and the lane does not finish past it.
            return new AgentResponse(AgentStatus.BLOCKED,
                    AgentReason.REQUESTER_RESPONSE_PENDING,
                    AgentNextAction.WAIT,
                    Map.of(NextActionKeys.CAPABILITY_REQUEST_HANDLE, unanswered.getFirst().handle().value(),
                            NextActionKeys.REASON, LaneCompletionProjection.CONTRACT_PROPOSAL_AWAITING_REQUESTER,
                            LaneCompletionProjection.REQUESTER_ANSWER_DEADLINE, unanswered.stream().mapToLong(RequesterAnswerPolicy::answerDeadlineEpochMillis).max().orElseThrow(),
                            NextActionKeys.PENDING, unanswered.size()));
        }
        int validationCount = store.capabilityRequestProjection().allValidationContexts().size();
        if (validationCount > 0) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, Map.of(NextActionKeys.PENDING, validationCount));
        }
        var ownerImplementation = CapabilityActionProjection.ownerImplementationPending(store, callerIntent, callerNodeId, callerSupervisorId, callerWorkerId);
        if (ownerImplementation.isPresent()) {
            CapabilityRequestRecord request = ownerImplementation.get();
            return new AgentResponse(AgentStatus.BLOCKED,
                    AgentReason.UNRESOLVED_DEPENDENCY,
                    null,
                    Map.of(NextActionKeys.CAPABILITY_REQUEST_HANDLE, request.handle().value(), NextActionKeys.CONTRACT_REVISION, request.contractRevision()));
        }
        return null;
    }

    private static boolean isCompletionBlockingCapability(CapabilityRequestRecord request) {
        return request.state().blocksRequesterCompletion();
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private AgentResponse completeNoChangeTask(CompleteTaskRequest request, ProjectLocation location, NodeIdentity identity, String participantHandle, Optional<WorkIntent> laneIntent) {
        if (!request.hasCompleteEvidence()) {
            return noChangeDenied("NO_CHANGE_COMPLETION_EVIDENCE_REQUIRED", null);
        }
        if (laneIntent.isEmpty()) {
            return noChangeDenied(NextActionProtocol.REASON_NO_ACTIVE_INTENT, null);
        }
        WorkIntent announced = laneIntent.get();
        Optional<Map<String, Object>> mistyped = mistypedEvidence(request, announced, participantHandle);
        if (mistyped.isPresent()) {
            return evidenceMismatch(NextActionProtocol.REASON_NO_CHANGE_COMPLETION_EVIDENCE_MISMATCH, mistyped.get());
        }
        if (!request.expectedIntentId().equals(announced.intentId()) || request.expectedClaimEpoch() != announced.version()) {
            return noChangeDenied(NextActionProtocol.REASON_NO_CHANGE_COMPLETION_EVIDENCE_MISMATCH, null);
        }

        ReadinessResult readiness = request.outcome() == CompletionOutcome.NO_CHANGE ? readinessService.assessNoChange(location, request.provider(), request.connectionInstanceId())
                : readinessService.assess(location, request.provider(), request.connectionInstanceId());
        if (!readiness.ready()) {
            return readiness.response();
        }
        ProviderSessionBindingService.Binding binding = readiness.binding();
        Path workerWorktreePath = readiness.worktree();
        participantHandle = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        if (!participantHandle.equals(request.expectedParticipant())) {
            return noChangeDenied(NextActionProtocol.REASON_NO_CHANGE_COMPLETION_EVIDENCE_MISMATCH, null);
        }

        ExecutionResult validation = null;
        if (location.validation() != null && location.validationAlsoInLane()) {
            try {
                validation = processExecutor.execute(ExecutionRequest.from(location.validation(), workerWorktreePath, location.root()));
            } catch (Exception failure) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.VALIDATION_FAILED, AgentNextAction.RETRY, Map.of(NextActionKeys.PHASE, NextActionProtocol.PHASE_PRE_PUBLICATION, NextActionKeys.REASON, "VALIDATION_EXECUTION_FAILED"));
            }
            if (!validation.succeeded()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put(NextActionKeys.PHASE, NextActionProtocol.PHASE_PRE_PUBLICATION);
                result.put("validation", validation.toMap());
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.VALIDATION_FAILED, AgentNextAction.RETRY, result);
            }
        }

        try {
            PredictionEventStore current = location.openCoordinationStore();
            NoChangeCompletion alreadyCompleted = current.collaborationProjection().noChangeCompletion(request.expectedIntentId()).orElse(null);
            if (alreadyCompleted != null) {
                if (!noChangeEvidenceMatches(request, readiness.binding(), alreadyCompleted)) {
                    return noChangeDenied("NO_CHANGE_COMPLETION_CONFLICT", validation);
                }
                if (!bindingService.complete(location, request.provider(), request.connectionInstanceId()) && !bindingService.hasActiveIntent(location, readiness.binding())) {
                    return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, Map.of(NextActionKeys.REASON, NextActionProtocol.REASON_SESSION_COMPLETE_FAILED));
                }
                return noChangeCompletionWithContinuation(location, request, current, alreadyCompleted, validation, readiness.binding(), identity);
            }

            Optional<WorkIntent> currentIntent = current.collaborationProjection().intent(request.expectedIntentId());
            if (currentIntent.isEmpty()) {
                return noChangeDenied(NextActionProtocol.REASON_NO_ACTIVE_INTENT, validation);
            }
            WorkIntent exactIntent = currentIntent.get();
            if (!exactIntent.workGroupId().equals(request.expectedWorkGroupId()) || exactIntent.version() != request.expectedClaimEpoch() || !current.unchangedSince(request.expectedRevision())) {
                return noChangeDenied("NO_CHANGE_COMPLETION_EVIDENCE_STALE", validation);
            }
            NoChangeCompletionEligibility.Result eligibility = NoChangeCompletionEligibility.assess(current,
                    exactIntent,
                    participantHandle,
                    identity.nodeId(),
                    binding.supervisorId(),
                    binding.workerId(),
                    readiness.worktree(),
                    snapshotService);
            if (!eligibility.eligible()) {
                return noChangeDenied(eligibility.reason(), validation);
            }
            var group = current.workGroupProjection().group(request.expectedWorkGroupId()).orElse(null);
            if (group == null || group.version() != request.expectedWorkGroupVersion()) {
                return noChangeDenied(NextActionProtocol.REASON_WORK_GROUP_VERSION_STALE, validation);
            }
            NoChangeCompletion completion = collaborationService.completeNoChange(location.root(),
                    request.provider(),
                    request.connectionInstanceId(),
                    request.expectedIntentId(),
                    request.expectedWorkGroupId(),
                    request.expectedClaimEpoch(),
                    request.expectedWorkGroupVersion(),
                    request.expectedRevision(),
                    request.expectedParticipant(),
                    request.summary());
            if (!bindingService.complete(location, request.provider(), request.connectionInstanceId()) && !bindingService.hasActiveIntent(location, binding)) {
                return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, Map.of(NextActionKeys.REASON, NextActionProtocol.REASON_SESSION_COMPLETE_FAILED));
            }
            PredictionEventStore completedStore = location.openCoordinationStore();
            return noChangeCompletionWithContinuation(location, request, completedStore, completion, validation, binding, identity);
        } catch (Exception failure) {
            String reason = failure.getMessage() == null ? "NO_CHANGE_COMPLETION_FAILED" : failure.getMessage();
            if (isNoChangeDenial(reason)) {
                return noChangeDenied(reason, validation);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", failure.getClass().getSimpleName());
            result.put(NextActionKeys.REASON, reason);
            return AgentFailures.internal(request.projectRoot(), "lane.finish_without_changes", failure, result);
        }
    }

    private AgentResponse noChangeCompletionWithContinuation(ProjectLocation location,
            CompleteTaskRequest request,
            PredictionEventStore store,
            NoChangeCompletion completion,
            ExecutionResult validation,
            ProviderSessionBindingService.Binding binding,
            NodeIdentity identity) {
        return sealAndContinue(noChangeCompletionResult(store, completion, validation), request, location, binding, identity);
    }

    /**
     * Seals the terminal session when requested, then attaches the caller's next action.
     *
     * <p>The completion is already durable, so a failed continuation lookup never fails it; a
     * later inbox read recovers any review continuation, such as a completed lane that may still
     * review an active sibling.</p>
     */
    private AgentResponse sealAndContinue(AgentResponse completion, CompleteTaskRequest request, ProjectLocation location, ProviderSessionBindingService.Binding binding, NodeIdentity identity) {
        AgentResponse terminalResult = terminalSessionResult(completion, request, location, binding, identity);
        if (request.terminalSession() && terminalResult.result() instanceof Map<?, ?> terminalMap
                && ProviderSessionTerminalizationService.Outcome.SESSION_TERMINATED.name().equals(String.valueOf(terminalMap.get(NextActionKeys.SESSION_TERMINATION)))) {
            return terminalResult;
        }
        try {
            AgentResponse continuation = nextActionService.getNextAction(new NextActionRequest(location.root(), request.provider(), request.connectionInstanceId()));
            if (continuation.nextAction() != null) {
                return continuationWithCompletion(terminalResult, continuation);
            }
        } catch (Exception ignored) {
            // The durable completion is authoritative.
        }
        return terminalResult;
    }

    private AgentResponse terminalSessionResult(AgentResponse completion, CompleteTaskRequest request, ProjectLocation location, ProviderSessionBindingService.Binding binding, NodeIdentity identity) {
        if (!request.terminalSession()) {
            return completion;
        }
        ProviderSessionTerminalizationService.SealResult seal;
        try {
            seal = terminalizationService.seal(location, binding, request.connectionInstanceId(), identity, "finish_lane_terminal_session");
        } catch (Exception failure) {
            seal = new ProviderSessionTerminalizationService.SealResult(ProviderSessionTerminalizationService.Outcome.SESSION_TERMINATION_BLOCKED, List.of("AUTHORITY_STATE_UNAVAILABLE"), -1L);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        putResult(result, completion.result(), "completion");
        result.put(NextActionKeys.SESSION_TERMINATION, seal.outcome().name());
        if (!seal.blockers().isEmpty()) {
            result.put("sessionTerminationBlockers", seal.blockers());
        }
        if (seal.eventSequence() >= 0L) {
            result.put("terminalFenceSequence", seal.eventSequence());
        }
        return new AgentResponse(completion.status(), completion.reason(), completion.nextAction(), result);
    }

    /**
     * Unwinds an exact caller's prepared but unpublished completion. The operation is intentionally
     * separate from cancellation: it removes the prepared reference, records one replayable unwind
     * event, and advances the lane epoch before mutation authority is restored.
     *
     * @param request exact caller completion request
     * @return completion response describing the new epoch
     */
    public AgentResponse unwindPrepared(CompleteTaskRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            manualService.requireAttested(request.provider());
            Path root = request.projectRoot().toAbsolutePath().normalize();
            ProjectLocation location = projectService.locate(root);
            ProviderSessionBindingService.Binding binding = authorityResolver.resolve(location, request.provider(), request.connectionInstanceId());
            if (binding.worktreePath() == null) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.SESSION_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
            }
            NodeIdentity identity = location.loadOrCreateIdentity();
            PredictionEventStore store = location.openCoordinationStore();
            UUID taskId = binding.taskId();
            var preparedOpt = store.taskCompletionProjection().findPrepared(taskId);
            if (preparedOpt.isEmpty()) {
                if (store.taskCompletionProjection().findSnapshotForTask(taskId).isPresent()) {
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.REQUEST_HUMAN_HELP, Map.of(NextActionKeys.REASON, "PUBLISHED_LANE_NOT_UNWINDABLE"));
                }
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, Map.of(NextActionKeys.REASON, "NO_PREPARED_COMPLETION"));
            }
            CompletionPreparedPayload prepared = preparedOpt.get();
            String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            WorkIntent currentIntent = store.collaborationProjection().intent(prepared.laneId()).orElseThrow(() -> new java.io.IOException("UNWIND_INTENT_NOT_FOUND"));
            if (!currentIntent.participant().equals(participant) || currentIntent.version() != prepared.claimEpoch()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of(NextActionKeys.REASON, "UNWIND_CALLER_MISMATCH"));
            }
            WorkIntent replacement = currentIntent.nextVersion();
            CompletionUnwoundPayload payload = new CompletionUnwoundPayload(prepared, replacement);
            try (ProjectAppendLock lock = ProjectAppendLock.acquire(location.coordinationDirectory())) {
                if (!lock.isHeld()) {
                    throw new java.io.IOException("event append lock unavailable");
                }
                PredictionEventStore currentStore = location.openCoordinationStore();
                if (currentStore.taskCompletionProjection().findPrepared(taskId).isEmpty()) {
                    return new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of("task", "already_unwound", NextActionKeys.CLAIM_EPOCH, replacement.version()));
                }
                currentStore.append(taskId, PredictionEventType.COMPLETION_UNWOUND, identity.nodeId(), payload.encode(), identity);
            }
            try {
                snapshotService.removePreparedRef(Path.of(binding.worktreePath()), prepared.preparedRef());
            } catch (Exception cleanupFailure) {
                return AgentFailures.internal(request.projectRoot(), "lane.unwind_cleanup", cleanupFailure, Map.of("error", "PREPARED_REF_CLEANUP_FAILED", "message", String.valueOf(cleanupFailure.getMessage())));
            }
            return new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of("task", "completion_unwound", NextActionKeys.CLAIM_EPOCH, replacement.version()));
        } catch (Exception failure) {
            return AgentFailures.internal(request.projectRoot(), "lane.unwind", failure,
                    Map.of("error", failure.getClass().getSimpleName(), "message", failure.getMessage() == null ? "unwind failed" : failure.getMessage()));
        }
    }


}
