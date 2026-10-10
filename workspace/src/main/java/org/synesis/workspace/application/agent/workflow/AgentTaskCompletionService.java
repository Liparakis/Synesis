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
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.completion.CompleteTaskRequest;
import org.synesis.workspace.application.agent.completion.CompletionOutcome;
import org.synesis.workspace.application.agent.session.CallerAdmission;
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
     * Validates optional server-issued finish evidence for the current lane revision.
     */
    private static AgentResponse validateSnapshotEvidence(CompleteTaskRequest request, WorkIntent intent, PredictionEventStore store, String participantHandle) {
        boolean correctionRequired = store.taskCompletionProjection().allSnapshots().stream()
                .anyMatch(snapshot -> snapshot.taskId().equals(intent.taskId()) && snapshot.provenance().authorityLineageId().equals(intent.authorityLineageId()) && snapshot.provenance().claimEpoch() < intent.version()
                        && store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.REVIEW_REJECTED);
        if (!request.hasAnyEvidence() && !correctionRequired) {
            return null;
        }
        if (!request.hasCompleteEvidence()) {
            return snapshotEvidenceDenied(request, NextActionProtocol.REASON_SNAPSHOT_COMPLETION_EVIDENCE_REQUIRED);
        }
        WorkGroup group = store.workGroupProjection().group(intent.workGroupId()).orElse(null);
        if (group == null) {
            return snapshotEvidenceDenied(request, NextActionProtocol.REASON_WORK_GROUP_NOT_FOUND);
        }
        if (!request.expectedIntentId().equals(intent.intentId()) || !request.expectedWorkGroupId().equals(intent.workGroupId()) || !request.expectedClaimEpoch().equals(intent.version())
                || !request.expectedWorkGroupVersion().equals(group.version()) || !store.unchangedSince(request.expectedRevision()) || !request.expectedParticipant().equals(participantHandle)
                || !intent.participant().equals(participantHandle) || intent.status() != WorkIntent.Status.ANNOUNCED) {
            return snapshotEvidenceDenied(request, NextActionProtocol.REASON_SNAPSHOT_COMPLETION_EVIDENCE_STALE);
        }
        return null;
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

    private static UUID deriveIntentId(ProviderSessionBindingService.Binding binding, String provider) {
        return UUID.nameUUIDFromBytes((provider + ":" + binding.sessionId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
                    .filter(requestRecord -> !RequesterAnswerPolicy.ownerFinishedWithoutAnswer(requestRecord)).toList();
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
                return completeNoChangeTask(request, location, identity, participantHandle, laneIntent);
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
            // prepared or visible to integration.
            if (location.validation() != null) {
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
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, failure);
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

        // A completion transaction must publish a complete lane diff. A clean lane cannot create a
        // useful implementation snapshot or release its authority without preserving any work.
        if (snapshot.changedPaths().isEmpty()) {
            if (!snapshotAlreadyExists) {
                snapshotService.removeSnapshotRef(worktree, snapshot);
            }
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, Map.of(NextActionKeys.REASON, "NO_CHANGES_TO_PUBLISH"));
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
        String completionId =
                "cmp_" + UUID.nameUUIDFromBytes((snapshot.provenance().laneId() + "\n" + snapshot.provenance().claimEpoch() + "\n" + snapshot.commitSha()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!snapshotAlreadyExists) {
            // Direct callers may create a provisional ref; completion moves it behind a durable
            // prepared phase.
            snapshotService.removeSnapshotRef(worktree, snapshot);
        }
        String preparedRef = snapshotService.pinPreparedRef(worktree, snapshot, completionId);
        String preparedTreeHash = snapshotService.treeHash(worktree, snapshot.commitSha());
        supersedeUnansweredProposals(store, snapshot, binding, identity);
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
            UUID completedIntentId = deriveIntentId(binding, request.provider());
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
        List<CapabilityRequestRecord> unjudged = CapabilityActionProjection.ownerImplementationsAwaitingVerdict(store, callerIntent, callerNodeId, callerSupervisorId, callerWorkerId);
        if (!unjudged.isEmpty()) {
            // Same rule the inbox projects: the requester may still send this back, and only an
            // open owner lane can revise it.
            return new AgentResponse(AgentStatus.BLOCKED,
                    AgentReason.VALIDATION_REQUIRED,
                    AgentNextAction.WAIT,
                    Map.of(NextActionKeys.CAPABILITY_REQUEST_HANDLE, unjudged.getFirst().handle().value(), NextActionKeys.REASON, NextActionProtocol.REASON_IMPLEMENTATION_AWAITING_VALIDATION, NextActionKeys.PENDING, unjudged.size()));
        }
        long now = System.currentTimeMillis();
        List<CapabilityRequestRecord> unanswered = LaneCompletionProjection.unansweredProposals(store, callerIntent, callerNodeId, callerSupervisorId, callerWorkerId).stream()
                .filter(proposal -> RequesterAnswerPolicy.ownerMustWait(proposal, now))
                .toList();
        if (!unanswered.isEmpty()) {
            // Same bounded wait the inbox projects. Once it has passed, finishing supersedes the
            // proposal instead of leaving the requester an answer nobody will read.
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

    /**
     * Records that this lane's unanswered contract proposals are superseded by the work it is
     * about to publish.
     *
     * <p>Readiness has already established that each proposal's answer period is over. Recording
     * the outcome before the completion is prepared means the requester never finds a proposal
     * waiting for an answer from a lane that can no longer act on it: it is told the owner went
     * ahead and builds against the integrated work. Should the completion then fail, the
     * requester simply asks again while the owner is still open.</p>
     */
    private static void supersedeUnansweredProposals(PredictionEventStore store, TaskSnapshotRecord snapshot, ProviderSessionBindingService.Binding binding, NodeIdentity identity)
            throws IOException, java.security.GeneralSecurityException {
        WorkIntent ownerIntent = store.collaborationProjection().intent(snapshot.provenance().laneId()).orElse(null);
        for (CapabilityRequestRecord proposal : LaneCompletionProjection.unansweredProposals(store, ownerIntent, identity.nodeId(), binding.supervisorId(), binding.workerId())) {
            CapabilityRequestPayload superseded = new CapabilityRequestPayload(proposal.handle(),
                    proposal.capability(),
                    proposal.requesterNodeId(),
                    proposal.ownerNodeId(),
                    proposal.authorityLineageId(),
                    proposal.contract(),
                    CapabilityLifecycleState.SUPERSEDED,
                    RequesterAnswerPolicy.OWNER_FINISHED_WITHOUT_ANSWER,
                    proposal.contractRevision());
            store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_SUPERSEDED, identity.nodeId(), superseded.encode(), identity);
        }
    }

    private static boolean isCompletionBlockingCapability(CapabilityRequestRecord request) {
        return request.state() == CapabilityLifecycleState.AWAITING_OWNER || request.state() == CapabilityLifecycleState.REVISION_REQUESTED || request.state() == CapabilityLifecycleState.IMPLEMENTING
                || request.state() == CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE || request.state() == CapabilityLifecycleState.VALIDATING;
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
        if (!request.expectedIntentId().equals(announced.intentId()) || !request.expectedWorkGroupId().equals(announced.workGroupId()) || request.expectedClaimEpoch() != announced.version() || !participantHandle.equals(
                request.expectedParticipant())) {
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
        if (location.validation() != null) {
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
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, result);
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
                return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", "PREPARED_REF_CLEANUP_FAILED", "message", cleanupFailure.getMessage()));
            }
            return new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of("task", "completion_unwound", NextActionKeys.CLAIM_EPOCH, replacement.version()));
        } catch (Exception failure) {
            return new AgentResponse(AgentStatus.FAILED,
                    AgentReason.INTERNAL_FAILURE,
                    AgentNextAction.REQUEST_HUMAN_HELP,
                    Map.of("error", failure.getClass().getSimpleName(), "message", failure.getMessage() == null ? "unwind failed" : failure.getMessage()));
        }
    }


}
