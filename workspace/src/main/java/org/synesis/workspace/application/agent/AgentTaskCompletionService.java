package org.synesis.workspace.application.agent;
import org.synesis.workspace.application.integration.IntegrationOrchestrationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.ProviderSessionTerminalizationService;
import org.synesis.workspace.application.provider.SessionAuthorityResolver;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectProcessExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;

import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.CompletionPreparedPayload;
import org.synesis.coordination.domain.task.CompletionUnwoundPayload;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.NoChangeCompletion;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

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
        this.terminalizationService = new ProviderSessionTerminalizationService(bindingService,
                new org.synesis.workspace.lifecycle.lease.SessionLeaseService());
    }

    /**
     * Request payload for task completion.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     * @param summary              human-readable task completion summary (optional)
     * @param outcome              completion outcome
     * @param expectedIntentId     server-issued intent identity observed by the caller
     * @param expectedWorkGroupId  server-issued work-group identity observed by the caller
     * @param expectedClaimEpoch   current claim epoch observed by the caller
     * @param expectedWorkGroupVersion current work-group version observed by the caller
     * @param expectedRevision     current event-log revision observed by the caller
     * @param expectedParticipant  exact participant handle observed by the caller
     * @param terminalSession      explicit opt-in to seal the exact provider session after lane completion
     */
    public record CompleteTaskRequest(
            Path projectRoot,
            String provider,
            String connectionInstanceId,
            String summary,
            CompletionOutcome outcome,
            UUID expectedIntentId,
            UUID expectedWorkGroupId,
            Long expectedClaimEpoch,
            Long expectedWorkGroupVersion,
            Long expectedRevision,
            String expectedParticipant,
            boolean terminalSession
    ) {
        /**
         * Validates non-null core parameters.
         */
        public CompleteTaskRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            outcome = outcome == null ? CompletionOutcome.SNAPSHOT : outcome;
        }

        /** Constructs the existing snapshot completion request shape.
         * @param projectRoot control project root
         * @param provider provider identifier
         * @param connectionInstanceId connection instance identifier
         * @param summary completion summary
         */
        public CompleteTaskRequest(Path projectRoot, String provider, String connectionInstanceId,
                String summary) {
            this(projectRoot, provider, connectionInstanceId, summary, CompletionOutcome.SNAPSHOT,
                    null, null, null, null, null, null, false);
        }

        /** Constructs the pre-terminal-session request shape with terminal sealing disabled.
         * @param projectRoot control project root
         * @param provider provider identifier
         * @param connectionInstanceId provider connection instance
         * @param summary completion summary
         * @param outcome completion outcome
         * @param expectedIntentId exact intent evidence
         * @param expectedWorkGroupId exact work-group evidence
         * @param expectedClaimEpoch exact claim epoch
         * @param expectedWorkGroupVersion exact work-group version
         * @param expectedRevision exact event revision
         * @param expectedParticipant exact participant evidence
         */
        public CompleteTaskRequest(Path projectRoot, String provider, String connectionInstanceId,
                String summary, CompletionOutcome outcome, UUID expectedIntentId, UUID expectedWorkGroupId,
                Long expectedClaimEpoch, Long expectedWorkGroupVersion, Long expectedRevision,
                String expectedParticipant) {
            this(projectRoot, provider, connectionInstanceId, summary, outcome, expectedIntentId,
                    expectedWorkGroupId, expectedClaimEpoch, expectedWorkGroupVersion, expectedRevision,
                    expectedParticipant, false);
        }
    }

    /** Explicit terminal outcome accepted by {@code finish_lane}. */
    public enum CompletionOutcome {
        /** Publish the normal immutable snapshot and integration candidate. */
        SNAPSHOT("snapshot"),
        /** Complete a declared clean, no-mutation intent. */
        NO_CHANGE("no_change");

        private final String wireValue;

        CompletionOutcome(String wireValue) {
            this.wireValue = wireValue;
        }

        /** Returns the stable protocol representation.
         * @return wire value
         */
        public String wireValue() {
            return wireValue;
        }

        /** Parses a protocol completion outcome.
         * @param value wire value
         * @return outcome
         * @throws IllegalArgumentException for an unknown value
         */
        public static CompletionOutcome fromWire(String value) {
            Objects.requireNonNull(value, "completion outcome");
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "snapshot" -> SNAPSHOT;
                case "no_change", "no_change_allowed" -> NO_CHANGE;
                default -> throw new IllegalArgumentException("unknown completion outcome: " + value);
            };
        }
    }

    /**
     * Executes task completion checks, snapshot creation, and integration orchestration.
     *
     * @param request complete task request
     * @return concise agent response
     */
    public AgentResponse completeTask(CompleteTaskRequest request) {
        Objects.requireNonNull(request, "request");

        Path root = request.projectRoot().toAbsolutePath().normalize();
        try {
            manualService.requireAttested(request.provider());
        } catch (Exception failure) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY,
                    Map.of("reason", "MANUAL_ATTESTATION_REQUIRED"));
        }
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        ProjectApplicationService.ProjectLocation location;
        ProviderSessionBindingService.Binding binding;
        boolean terminalRetry = false;
        NodeIdentity identity;
        try {
            location = projectService.locate(root);
            try {
                binding = authorityResolver.resolve(location, request.provider(), request.connectionInstanceId());
            } catch (Exception activeResolutionFailure) {
                binding = authorityResolver.resolveCompleted(location, request.provider(), request.connectionInstanceId());
                terminalRetry = true;
            }
            if (binding.worktreePath() == null) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.SESSION_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
            }
            identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        String callerNodeId = identity.nodeId();
        String callerSupervisorId = binding.supervisorId();
        String callerWorkerId = binding.workerId();
        Path workerWorktreePath = Path.of(binding.worktreePath()).toAbsolutePath().normalize();

        try {
            Path coordDir = location.root().resolve(".synesis/coordination");
            PredictionEventStore store = new PredictionEventStore(coordDir, location.projectId());
            ProjectProcessExecutor.ExecutionResult prePublicationValidation = null;

            if (terminalRetry) {
                if (request.outcome() == CompletionOutcome.NO_CHANGE) {
                    UUID completedIntentId = deriveIntentId(binding, request.provider());
                    Optional<org.synesis.coordination.domain.collaboration.NoChangeCompletion> completed =
                            store.collaborationProjection().noChangeCompletion(completedIntentId);
                    if (completed.isPresent() && noChangeEvidenceMatches(request, binding, completed.get())) {
                        return noChangeCompletionResult(store, completed.get(), null);
                    }
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY,
                            AgentNextAction.REQUEST_HUMAN_HELP,
                            Map.of("reason", "COMPLETED_BINDING_WITHOUT_NO_CHANGE_COMPLETION"));
                }
                UUID completedTaskId = deriveTaskId(binding);
                Optional<TaskSnapshotRecord> completedSnapshot = store.taskCompletionProjection()
                        .findSnapshotForTask(completedTaskId);
                if (completedSnapshot.isPresent()) {
                    TaskCompletionState completedState = store.taskCompletionProjection()
                            .snapshotState(completedSnapshot.get().snapshotId())
                            .orElse(TaskCompletionState.ACTIVE);
                    if (completedState != TaskCompletionState.INTEGRATED) {
                        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY,
                                AgentNextAction.REQUEST_HUMAN_HELP,
                                Map.of("reason", "COMPLETED_BINDING_WITHOUT_INTEGRATED_SNAPSHOT",
                                        "snapshotId", completedSnapshot.get().snapshotId(),
                                        "snapshotState", completedState.value()));
                    }
                    return completionResult(store, completedSnapshot.get(),
                            WorkspaceCollaborationService.participantHandle(binding.sessionId()),
                            new AgentResponse(AgentStatus.COMPLETED, null, null,
                                    Map.of("task", "already_integrated")), null);
                }
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY,
                        AgentNextAction.REQUEST_HUMAN_HELP, Map.of("reason", "COMPLETED_BINDING_WITHOUT_SNAPSHOT"));
            }

            // 1. Completion Readiness Check: Unresolved capability requests
            List<CapabilityRequestRecord> reqPending = store.capabilityRequestProjection().findAllForRequester(callerNodeId).stream()
                    .filter(requestRecord -> requestRecord.matchesRequester(callerNodeId, callerSupervisorId, callerWorkerId))
                    .toList();
            List<CapabilityRequestRecord> callerPending = new ArrayList<>();
            for (CapabilityRequestRecord r : reqPending) {
                if (r.matchesRequester(callerNodeId, callerSupervisorId, callerWorkerId)) {
                    if (r.state() == CapabilityLifecycleState.AWAITING_OWNER
                            || r.state() == CapabilityLifecycleState.REVISION_REQUESTED
                            || r.state() == CapabilityLifecycleState.IMPLEMENTING
                            || r.state() == CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE
                            || r.state() == CapabilityLifecycleState.VALIDATING) {
                        callerPending.add(r);
                    }
                }
            }

            if (!callerPending.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("pending", callerPending.size());
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.UNRESOLVED_DEPENDENCY, AgentNextAction.WAIT, result);
            }

            // 2. Derive or look up task ID for this session/worker
            UUID taskId = findActiveTaskId(store, callerNodeId, callerWorkerId, binding);

            // Check if active validation context is open for this requester
            var validationContexts = store.capabilityRequestProjection().allValidationContexts();
            if (!validationContexts.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("pending", validationContexts.size());
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, result);
            }

            // 3. Create or recover immutable task snapshot
            String summaryText = (request.summary() != null && !request.summary().isBlank())
                    ? request.summary().trim() : "Completed task implementation";

            List<CapabilityRequestRecord> workerCapabilities = store.capabilityRequestProjection().findAllForRequester(callerNodeId).stream()
                    .filter(requestRecord -> requestRecord.matchesRequester(callerNodeId, callerSupervisorId, callerWorkerId))
                    .toList();
            // Collaboration participants are derived from the verified durable
            // session binding, never from the transient MCP connection ID.
            // Using the connection here made an otherwise valid claimed lane
            // appear unowned at completion time.
            String participantHandle = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            var laneIntent = store.collaborationProjection().activeIntents().stream()
                    .filter(intent -> intent.participant().equals(participantHandle)).findFirst();
            if (store.collaborationProjection().activated() && laneIntent.isEmpty()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COORDINATION_INTENT_REQUIRED,
                        AgentNextAction.ENSURE_SESSION, Map.of("reason", "COORDINATION_INTENT_REQUIRED"));
            }
            List<ResourceSelector> currentClaims = laneIntent.map(intent -> intent.selectors()).orElse(List.of());
            Optional<TaskSnapshotRecord> existingOpt = laneIntent
                    .flatMap(intent -> store.taskCompletionProjection().findSnapshotForTaskRevision(
                            taskId, intent.intentId(), intent.version()));
            if (laneIntent.isEmpty()) {
                existingOpt = store.taskCompletionProjection().findSnapshotForTask(taskId);
            }

            if (request.outcome() == CompletionOutcome.NO_CHANGE) {
                return completeNoChangeTask(request, location, binding, identity, workerWorktreePath,
                        participantHandle, laneIntent);
            }

            if (laneIntent.isPresent()) {
                AgentResponse evidenceFailure = validateSnapshotEvidence(request, laneIntent.get(), store,
                        participantHandle);
                if (evidenceFailure != null) {
                    return evidenceFailure;
                }
            }

            if (existingOpt.isPresent()
                    && store.taskCompletionProjection().snapshotState(existingOpt.get().snapshotId())
                            .orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.INTEGRATED) {
                return completionResult(store, existingOpt.get(), participantHandle,
                        new AgentResponse(AgentStatus.COMPLETED, null, null,
                                Map.of("task", "already_integrated")), null);
            }

            boolean reviewRequired = laneIntent.map(intent -> reviewRequired(store, intent, participantHandle))
                    .orElse(false);

            // Project-owned validation is a server gate. It runs through the
            // same direct argv primitive exposed by run_command, against the
            // authenticated lane worktree, before any snapshot can become
            // prepared or visible to integration.
            if (location.validation() != null) {
                prePublicationValidation = processExecutor.execute(
                        ProjectProcessExecutor.ExecutionRequest.from(location.validation(), workerWorktreePath,
                                location.root()));
                if (!prePublicationValidation.succeeded()) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("phase", "pre_publication");
                    result.put("validation", prePublicationValidation.toMap());
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.VALIDATION_FAILED,
                            AgentNextAction.RETRY, result);
                }
            }

            TaskSnapshotRecord snapshot;
            try {
                snapshot = snapshotService.createSnapshot(
                        taskId, callerNodeId, callerSupervisorId, callerWorkerId,
                        binding.sessionId(), workerWorktreePath, location.root(),
                        summaryText, existingOpt, workerCapabilities, currentClaims,
                        laneIntent.map(intent -> intent.workGroupId()).orElse(taskId),
                        laneIntent.map(intent -> intent.intentId()).orElse(taskId), participantHandle,
                        binding.sessionId(), laneIntent.map(intent -> intent.version()).orElse(1L),
                        laneIntent.map(intent -> intent.authorityLineageId())
                                .orElse(org.synesis.coordination.domain.collaboration.WorkIntent
                                        .defaultAuthorityLineage(taskId)), List.of(), reviewRequired);
            } catch (IllegalStateException immutabilityError) {
                // Task snapshot is immutable and content changed after completion
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, null);
            }

            // A completion transaction must publish a complete lane diff.  A
            // clean lane is not a valid implementation snapshot: accepting it
            // would create an apparently successful, empty integration and
            // release the caller's authority without preserving any work.
            if (snapshot.changedPaths().isEmpty()) {
                if (existingOpt.isEmpty()) {
                    snapshotService.removeSnapshotRef(workerWorktreePath, snapshot);
                }
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY,
                        AgentNextAction.RETRY, Map.of("reason", "NO_CHANGES_TO_PUBLISH"));
            }

            // Stable identity collisions are not a second publication.  They
            // indicate that two lanes produced the same canonical snapshot
            // identity, so fail closed rather than advertising another task
            // record over the immutable snapshot ID.
            var sameIdentity = store.taskCompletionProjection().findSnapshotById(snapshot.snapshotId());
            if (sameIdentity.isPresent() && !sameIdentity.get().taskId().equals(snapshot.taskId())) {
                if (existingOpt.isEmpty()) {
                    snapshotService.removeSnapshotRef(workerWorktreePath, snapshot);
                }
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY,
                        AgentNextAction.REQUEST_HUMAN_HELP, Map.of("reason", "SNAPSHOT_ID_COLLISION",
                                "snapshotId", snapshot.snapshotId()));
            }

            // 4. Pin the exact prepared tree and fence this lane before the
            // snapshot becomes visible to the integration queue.
            String completionId = "cmp_" + java.util.UUID.nameUUIDFromBytes((
                    snapshot.provenance().laneId() + "\n" + snapshot.provenance().claimEpoch()
                            + "\n" + snapshot.commitSha()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (existingOpt.isEmpty()) {
                // Direct callers may create a provisional ref; completion moves
                // that ref behind a durable prepared phase.
                snapshotService.removeSnapshotRef(workerWorktreePath, snapshot);
            }
            String preparedRef = snapshotService.pinPreparedRef(workerWorktreePath, snapshot, completionId);
            String preparedTreeHash = snapshotService.treeHash(workerWorktreePath, snapshot.commitSha());
            if (store.taskCompletionProjection().findPrepared(taskId, snapshot.provenance().laneId(),
                    snapshot.provenance().claimEpoch()).isEmpty()) {
                CompletionPreparedPayload prepared = new CompletionPreparedPayload(taskId, completionId,
                        snapshot.provenance().laneId(), snapshot.provenance().claimEpoch(), snapshot.baseCommit(),
                        preparedRef, preparedTreeHash, snapshot.changedPaths());
                store.append(UUID.randomUUID(), PredictionEventType.COMPLETION_PREPARED,
                        callerNodeId, prepared.encode(), identity);
            }

            snapshotService.promotePreparedRef(workerWorktreePath, preparedRef,
                    snapshot.provenance().snapshotRef(), snapshot.commitSha());

            // 5. Persist TASK_SNAPSHOT_CREATED if new
            if (existingOpt.isEmpty()) {
                TaskSnapshotPayload snapPayload = new TaskSnapshotPayload(
                        snapshot.taskId(), snapshot.snapshotId(), snapshot.nodeId(), snapshot.supervisorId(),
                        snapshot.workerId(), snapshot.providerSessionId(), snapshot.baseCommit(), snapshot.commitSha(),
                        snapshot.changedPaths(), snapshot.capabilityDependencies(), snapshot.summary(), snapshot.provenance(),
                        snapshot.reviewRequired());

                store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                        callerNodeId, snapPayload.encode(), identity);
            }

            // 6. Trigger integration orchestration
            AgentResponse result = integrationOrchestrationService.orchestrateIntegration(location.root(), store, identity);
            result = completionResult(store, snapshot, participantHandle, result, prePublicationValidation);
            if (result.status() == AgentStatus.COMPLETED) {
                releaseClaims(request, collaborationService);
                // Complete only the exact calling connection.  Keeping the
                // binding terminal (and retaining its worktree) prevents the
                // next inbox read from attempting to reuse a stale lane.
                bindingService.complete(location, request.provider(), request.connectionInstanceId());
                AgentResponse terminalResult = terminalSessionResult(result, request, location, binding, identity);
                if (request.terminalSession()
                        && terminalResult.result() instanceof Map<?, ?> terminalMap
                        && "SESSION_TERMINATED".equals(String.valueOf(terminalMap.get("sessionTermination")))) {
                    return terminalResult;
                }
                result = terminalResult;
                // A completed lane may still be the only participant able to
                // review an active sibling.  Project that existing review
                // protocol before returning a terminal result, so the
                // provider remains engaged without retaining write ownership.
                AgentResponse continuation = nextActionService.getNextAction(
                        new AgentNextActionService.NextActionRequest(
                                location.root(), request.provider(), request.connectionInstanceId()));
                if (continuation.nextAction() != null) {
                    return continuationWithCompletion(result, continuation);
                }
            }
            return result;

        } catch (Exception ex) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("error", ex.getClass().getSimpleName());
            failure.put("message", ex.getMessage() == null ? "unknown completion failure" : ex.getMessage());
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE,
                    AgentNextAction.REQUEST_HUMAN_HELP, failure);
        }
    }

    private static AgentResponse continuationWithCompletion(
            AgentResponse completion, AgentResponse continuation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completion", completion.result());
        if (continuation.result() instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        } else if (continuation.result() != null) {
            result.put("continuation", continuation.result());
        }
        return new AgentResponse(continuation.status(), continuation.reason(),
                continuation.nextAction(), result);
    }

    private AgentResponse completeNoChangeTask(CompleteTaskRequest request,
            ProjectApplicationService.ProjectLocation location,
            ProviderSessionBindingService.Binding binding,
            NodeIdentity identity,
            Path workerWorktreePath,
            String participantHandle,
            Optional<WorkIntent> laneIntent) {
        if (request.expectedIntentId() == null || request.expectedWorkGroupId() == null
                || request.expectedClaimEpoch() == null || request.expectedWorkGroupVersion() == null
                || request.expectedRevision() == null || request.expectedParticipant() == null) {
            return noChangeDenied("NO_CHANGE_COMPLETION_EVIDENCE_REQUIRED", null);
        }
        if (laneIntent.isEmpty()) {
            return noChangeDenied("NO_ACTIVE_INTENT", null);
        }
        WorkIntent announced = laneIntent.get();
        if (!request.expectedIntentId().equals(announced.intentId())
                || !request.expectedWorkGroupId().equals(announced.workGroupId())
                || request.expectedClaimEpoch() != announced.version()
                || !participantHandle.equals(request.expectedParticipant())) {
            return noChangeDenied("NO_CHANGE_COMPLETION_EVIDENCE_MISMATCH", null);
        }

        WorkspaceReadinessService.ReadinessResult readiness = request.outcome() == CompletionOutcome.NO_CHANGE
                ? readinessService.assessNoChange(location, request.provider(), request.connectionInstanceId())
                : readinessService.assess(location, request.provider(), request.connectionInstanceId());
        if (!readiness.ready()) {
            return readiness.response();
        }
        binding = readiness.binding();
        workerWorktreePath = readiness.worktree();
        participantHandle = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        if (!participantHandle.equals(request.expectedParticipant())) {
            return noChangeDenied("NO_CHANGE_COMPLETION_EVIDENCE_MISMATCH", null);
        }

        ProjectProcessExecutor.ExecutionResult validation = null;
        if (location.validation() != null) {
            try {
                validation = processExecutor.execute(ProjectProcessExecutor.ExecutionRequest.from(
                        location.validation(), workerWorktreePath, location.root()));
            } catch (Exception failure) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.VALIDATION_FAILED,
                        AgentNextAction.RETRY, Map.of("phase", "pre_publication",
                                "reason", "VALIDATION_EXECUTION_FAILED"));
            }
            if (!validation.succeeded()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("phase", "pre_publication");
                result.put("validation", validation.toMap());
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.VALIDATION_FAILED,
                        AgentNextAction.RETRY, result);
            }
        }

        try {
            PredictionEventStore current = new PredictionEventStore(
                    location.root().resolve(".synesis/coordination"), location.projectId());
            NoChangeCompletion alreadyCompleted = current.collaborationProjection()
                    .noChangeCompletion(request.expectedIntentId()).orElse(null);
            if (alreadyCompleted != null) {
                if (!noChangeEvidenceMatches(request, readiness.binding(), alreadyCompleted)) {
                    return noChangeDenied("NO_CHANGE_COMPLETION_CONFLICT", validation);
                }
                if (!bindingService.complete(location, request.provider(), request.connectionInstanceId())) {
                    return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE,
                            AgentNextAction.REQUEST_HUMAN_HELP,
                            Map.of("reason", "SESSION_COMPLETE_FAILED"));
                }
                return noChangeCompletionWithContinuation(location, request, current,
                        alreadyCompleted, validation, readiness.binding(), identity);
            }

            Optional<WorkIntent> currentIntent = current.collaborationProjection()
                    .intent(request.expectedIntentId());
            if (currentIntent.isEmpty()) {
                return noChangeDenied("NO_ACTIVE_INTENT", validation);
            }
            WorkIntent exactIntent = currentIntent.get();
            if (!exactIntent.workGroupId().equals(request.expectedWorkGroupId())
                    || exactIntent.version() != request.expectedClaimEpoch()
                    || current.headSequence() != request.expectedRevision()) {
                return noChangeDenied("NO_CHANGE_COMPLETION_EVIDENCE_STALE", validation);
            }
            NoChangeCompletionEligibility.Result eligibility = NoChangeCompletionEligibility.assess(
                    current, exactIntent, participantHandle, identity.nodeId(), binding.supervisorId(),
                    binding.workerId(), readiness.worktree(), snapshotService);
            if (!eligibility.eligible()) {
                return noChangeDenied(eligibility.reason(), validation);
            }
            var group = current.workGroupProjection().group(request.expectedWorkGroupId()).orElse(null);
            if (group == null || group.version() != request.expectedWorkGroupVersion()) {
                return noChangeDenied("WORK_GROUP_VERSION_STALE", validation);
            }
            NoChangeCompletion completion = collaborationService.completeNoChange(
                    location.root(), request.provider(), request.connectionInstanceId(),
                    request.expectedIntentId(), request.expectedWorkGroupId(),
                    request.expectedClaimEpoch(), request.expectedWorkGroupVersion(),
                    request.expectedRevision(), request.expectedParticipant(), request.summary());
            if (!bindingService.complete(location, request.provider(), request.connectionInstanceId())) {
                return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE,
                        AgentNextAction.REQUEST_HUMAN_HELP,
                        Map.of("reason", "SESSION_COMPLETE_FAILED"));
            }
            PredictionEventStore completedStore = new PredictionEventStore(
                    location.root().resolve(".synesis/coordination"), location.projectId());
            return noChangeCompletionWithContinuation(location, request, completedStore,
                    completion, validation, binding, identity);
        } catch (Exception failure) {
            String reason = failure.getMessage() == null ? "NO_CHANGE_COMPLETION_FAILED" : failure.getMessage();
            if (isNoChangeDenial(reason)) {
                return noChangeDenied(reason, validation);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", failure.getClass().getSimpleName());
            result.put("reason", reason);
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE,
                    AgentNextAction.REQUEST_HUMAN_HELP, result);
        }
    }

    private AgentResponse noChangeCompletionWithContinuation(
            ProjectApplicationService.ProjectLocation location,
            CompleteTaskRequest request,
            PredictionEventStore store,
            NoChangeCompletion completion,
            ProjectProcessExecutor.ExecutionResult validation,
            ProviderSessionBindingService.Binding binding,
            NodeIdentity identity) {
        AgentResponse result = noChangeCompletionResult(store, completion, validation);
        AgentResponse terminalResult = terminalSessionResult(result, request, location, binding, identity);
        if (request.terminalSession()
                && terminalResult.result() instanceof Map<?, ?> terminalMap
                && "SESSION_TERMINATED".equals(String.valueOf(terminalMap.get("sessionTermination")))) {
            return terminalResult;
        }
        result = terminalResult;
        try {
            AgentResponse continuation = nextActionService.getNextAction(
                    new AgentNextActionService.NextActionRequest(
                            location.root(), request.provider(), request.connectionInstanceId()));
            if (continuation.nextAction() != null) {
                return continuationWithCompletion(result, continuation);
            }
        } catch (Exception ignored) {
            // The durable completion is authoritative; a subsequent inbox read
            // can recover any continuation projection.
        }
        return result;
    }

    private AgentResponse terminalSessionResult(AgentResponse completion, CompleteTaskRequest request,
            ProjectApplicationService.ProjectLocation location,
            ProviderSessionBindingService.Binding binding, NodeIdentity identity) {
        if (!request.terminalSession()) return completion;
        ProviderSessionTerminalizationService.SealResult seal;
        try {
            seal = terminalizationService.seal(location, binding, request.connectionInstanceId(), identity,
                    "finish_lane_terminal_session");
        } catch (Exception failure) {
            seal = new ProviderSessionTerminalizationService.SealResult(
                    ProviderSessionTerminalizationService.Outcome.SESSION_TERMINATION_BLOCKED,
                    List.of("AUTHORITY_STATE_UNAVAILABLE"), -1L);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (completion.result() instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        } else if (completion.result() != null) {
            result.put("completion", completion.result());
        }
        result.put("sessionTermination", seal.outcome().name());
        if (!seal.blockers().isEmpty()) result.put("sessionTerminationBlockers", seal.blockers());
        if (seal.eventSequence() >= 0L) result.put("terminalFenceSequence", seal.eventSequence());
        return new AgentResponse(completion.status(), completion.reason(), completion.nextAction(), result);
    }

    private static AgentResponse noChangeCompletionResult(PredictionEventStore store,
            NoChangeCompletion completion, ProjectProcessExecutor.ExecutionResult validation) {
        Map<String, Object> result = new LinkedHashMap<>();
        var group = store.workGroupProjection().group(completion.workGroupId()).orElse(null);
        result.put("outcome", "NO_CHANGE");
        result.put("participant", completion.participant());
        result.put("intentId", completion.intentId().toString());
        result.put("laneId", completion.intentId().toString());
        result.put("workGroupId", completion.workGroupId().toString());
        result.put("claimEpoch", completion.claimEpoch());
        result.put("workGroupVersion", completion.workGroupVersion());
        result.put("expectedRevision", completion.expectedRevision());
        result.put("snapshotState", "NOT_REQUIRED");
        result.put("integrationState", "NOT_REQUIRED");
        result.put("claimsReleased", true);
        result.put("workGroupState", group == null ? "UNKNOWN" : group.status().name());
        result.put("summary", completion.summary());
        if (validation != null) {
            result.put("prePublicationValidation", validation.toMap());
        }
        return new AgentResponse(AgentStatus.COMPLETED, null, null, result);
    }

    private static boolean noChangeEvidenceMatches(CompleteTaskRequest request,
            ProviderSessionBindingService.Binding binding, NoChangeCompletion completion) {
        return request.outcome() == CompletionOutcome.NO_CHANGE
                && request.expectedIntentId() != null
                && request.expectedWorkGroupId() != null
                && request.expectedClaimEpoch() != null
                && request.expectedWorkGroupVersion() != null
                && request.expectedRevision() != null
                && request.expectedParticipant() != null
                && request.expectedIntentId().equals(completion.intentId())
                && request.expectedWorkGroupId().equals(completion.workGroupId())
                && request.expectedClaimEpoch() == completion.claimEpoch()
                && request.expectedWorkGroupVersion() == completion.workGroupVersion()
                && request.expectedRevision() == completion.expectedRevision()
                && request.expectedParticipant().equals(completion.participant())
                && request.provider().equals(completion.provider())
                && binding.sessionId().equals(completion.bindingIdentity())
                && binding.baseCommit().equals(completion.workspaceCommit())
                && normalizedSummary(request.summary()).equals(completion.summary());
    }

    private static String normalizedSummary(String summary) {
        return summary == null || summary.isBlank()
                ? "Completed successfully without repository mutation" : summary.trim();
    }

    private static boolean isNoChangeDenial(String reason) {
        return reason.startsWith("NO_CHANGE_") || reason.equals("NO_ACTIVE_INTENT")
                || reason.equals("WORK_GROUP_NOT_FOUND") || reason.equals("WORK_GROUP_NOT_ACTIVE")
                || reason.equals("WORK_GROUP_VERSION_STALE") || reason.equals("COMPLETION_REVISION_STALE")
                || reason.equals("INTENT_NOT_FOUND");
    }

    private static AgentResponse noChangeDenied(String reason,
            ProjectProcessExecutor.ExecutionResult validation) {
        AgentReason publicReason;
        AgentNextAction nextAction;
        AgentStatus status;
        if (reason.contains("WORKSPACE") || reason.contains("REVISION_STALE")
                || reason.equals("WORK_GROUP_VERSION_STALE") || reason.equals("COMPLETION_REVISION_STALE")) {
            publicReason = reason.contains("GENERATION")
                    ? AgentReason.WORKSPACE_GENERATION_CHANGED : AgentReason.TASK_NOT_READY;
            nextAction = AgentNextAction.RETRY;
            status = AgentStatus.RETRY_REQUIRED;
        } else if (reason.equals("NO_CHANGE_NOT_AUTHORIZED")
                || reason.contains("PARTICIPANT_MISMATCH")
                || reason.contains("EVIDENCE_REQUIRED")
                || reason.contains("EVIDENCE_MISMATCH")
                || reason.contains("CONFLICT")) {
            publicReason = AgentReason.POLICY_DENIED;
            nextAction = AgentNextAction.REQUEST_HUMAN_HELP;
            status = AgentStatus.BLOCKED;
        } else {
            publicReason = AgentReason.TASK_NOT_READY;
            nextAction = AgentNextAction.RETRY;
            status = AgentStatus.BLOCKED;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reason", reason);
        if (validation != null) {
            result.put("prePublicationValidation", validation.toMap());
        }
        return new AgentResponse(status, publicReason, nextAction, result);
    }

    /** Validates optional server-issued finish evidence for the current lane revision. */
    private static AgentResponse validateSnapshotEvidence(CompleteTaskRequest request,
            WorkIntent intent, PredictionEventStore store, String participantHandle) {
        boolean evidencePresent = request.expectedIntentId() != null
                || request.expectedWorkGroupId() != null
                || request.expectedParticipant() != null
                || (request.expectedClaimEpoch() != null && request.expectedClaimEpoch() != 1L)
                || (request.expectedWorkGroupVersion() != null && request.expectedWorkGroupVersion() != 1L)
                || (request.expectedRevision() != null && request.expectedRevision() != 0L);
        boolean correctionRequired = store.taskCompletionProjection().allSnapshots().stream()
                .anyMatch(snapshot -> snapshot.provenance().laneId().equals(intent.intentId())
                        && snapshot.provenance().authorityLineageId().equals(intent.authorityLineageId())
                        && snapshot.provenance().claimEpoch() < intent.version()
                        && store.taskCompletionProjection().snapshotState(snapshot.snapshotId())
                                .orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.REVIEW_REJECTED);
        if (!evidencePresent && !correctionRequired) {
            return null;
        }
        if (request.expectedIntentId() == null || request.expectedWorkGroupId() == null
                || request.expectedClaimEpoch() == null || request.expectedWorkGroupVersion() == null
                || request.expectedRevision() == null || request.expectedParticipant() == null) {
            return snapshotEvidenceDenied("SNAPSHOT_COMPLETION_EVIDENCE_REQUIRED");
        }
        WorkGroup group = store.workGroupProjection().group(intent.workGroupId()).orElse(null);
        if (group == null) {
            return snapshotEvidenceDenied("WORK_GROUP_NOT_FOUND");
        }
        if (!request.expectedIntentId().equals(intent.intentId())
                || !request.expectedWorkGroupId().equals(intent.workGroupId())
                || !request.expectedClaimEpoch().equals(intent.version())
                || !request.expectedWorkGroupVersion().equals(group.version())
                || !request.expectedRevision().equals(store.headSequence())
                || !request.expectedParticipant().equals(participantHandle)
                || !intent.participant().equals(participantHandle)
                || intent.status() != WorkIntent.Status.ANNOUNCED) {
            return snapshotEvidenceDenied("SNAPSHOT_COMPLETION_EVIDENCE_STALE");
        }
        return null;
    }

    /** Determines whether this exact lane revision is subject to review authority. */
    private static boolean reviewRequired(PredictionEventStore store, WorkIntent intent,
            String participantHandle) {
        boolean exactGrant = store.workGroupProjection().grants().stream()
                .filter(grant -> grant.workGroupId().equals(intent.workGroupId()))
                .filter(grant -> grant.targetIntentId().equals(intent.intentId()))
                .filter(grant -> grant.claimEpoch() == intent.version())
                .filter(grant -> !grant.targetParticipant().equals(participantHandle))
                .anyMatch(grant -> store.workGroupProjection().grantAvailable(grant.grantId())
                        || store.workGroupProjection().grantConsumed(grant.grantId()));
        if (exactGrant) {
            return true;
        }
        boolean requested = store.collaborationProjection().requests().stream()
                .anyMatch(request -> request.kind() == org.synesis.coordination.domain.collaboration.CoordinationRequest.Kind.REVIEW
                        && request.conflictingIntentId().equals(intent.intentId())
                        && (request.status() == org.synesis.coordination.domain.collaboration.CoordinationRequest.Status.PENDING
                        || request.status() == org.synesis.coordination.domain.collaboration.CoordinationRequest.Status.ACCEPTED));
        if (requested) {
            return true;
        }
        return store.taskCompletionProjection().allSnapshots().stream()
                .anyMatch(snapshot -> snapshot.provenance().laneId().equals(intent.intentId())
                        && snapshot.provenance().authorityLineageId().equals(intent.authorityLineageId())
                        && snapshot.provenance().claimEpoch() < intent.version()
                        && store.taskCompletionProjection().snapshotState(snapshot.snapshotId())
                                .orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.REVIEW_REJECTED);
    }

    /** Creates the fail-closed public response for stale snapshot evidence. */
    private static AgentResponse snapshotEvidenceDenied(String reason) {
        return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.TASK_NOT_READY,
                AgentNextAction.RETRY, Map.of("reason", reason));
    }

    private static AgentResponse completionResult(PredictionEventStore store, TaskSnapshotRecord snapshot,
            String participantHandle, AgentResponse integrationResult,
            ProjectProcessExecutor.ExecutionResult prePublicationValidation) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (integrationResult.result() instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        result.put("participant", participantHandle);
        result.put("laneId", snapshot.provenance().laneId().toString());
        result.put("claimEpoch", snapshot.provenance().claimEpoch());
        result.put("snapshotId", snapshot.snapshotId());
        TaskCompletionState state = store.taskCompletionProjection().snapshotState(snapshot.snapshotId())
                .orElse(TaskCompletionState.ACTIVE);
        result.put("snapshotState", snapshot.reviewRequired()
                ? state.value().toUpperCase(java.util.Locale.ROOT)
                : state == TaskCompletionState.INTEGRATED ? "PUBLISHED"
                        : state.value().toUpperCase(java.util.Locale.ROOT));
        result.put("reviewRequired", snapshot.reviewRequired());
        result.put("integrationState", state.value());
        if (prePublicationValidation != null) {
            result.put("prePublicationValidation", prePublicationValidation.toMap());
        }
        if (integrationResult.nextAction() != null) {
            result.put("nextAction", integrationResult.nextAction().value());
        }
        return new AgentResponse(integrationResult.status(), integrationResult.reason(),
                integrationResult.nextAction(), result);
    }

    /**
     * Unwinds an exact caller's prepared but unpublished completion. The
     * operation is intentionally separate from cancellation: it removes the
     * prepared reference, records one replayable unwind event, and advances
     * the lane epoch before mutation authority is restored.
     *
     * @param request exact caller completion request
     * @return completion response describing the new epoch
     */
    public AgentResponse unwindPrepared(CompleteTaskRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            manualService.requireAttested(request.provider());
            Path root = request.projectRoot().toAbsolutePath().normalize();
            ProjectApplicationService.ProjectLocation location = projectService.locate(root);
            ProviderSessionBindingService.Binding binding = authorityResolver.resolve(location,
                    request.provider(), request.connectionInstanceId());
            if (binding.worktreePath() == null) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.SESSION_NOT_READY,
                        AgentNextAction.ENSURE_SESSION, null);
            }
            NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            PredictionEventStore store = new PredictionEventStore(
                    location.root().resolve(".synesis/coordination"), location.projectId());
            UUID taskId = deriveTaskId(binding);
            var preparedOpt = store.taskCompletionProjection().findPrepared(taskId);
            if (preparedOpt.isEmpty()) {
                if (store.taskCompletionProjection().findSnapshotForTask(taskId).isPresent()) {
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY,
                            AgentNextAction.REQUEST_HUMAN_HELP, Map.of("reason", "PUBLISHED_LANE_NOT_UNWINDABLE"));
                }
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY,
                        AgentNextAction.RETRY, Map.of("reason", "NO_PREPARED_COMPLETION"));
            }
            CompletionPreparedPayload prepared = preparedOpt.get();
            String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            WorkIntent currentIntent = store.collaborationProjection().intent(prepared.laneId())
                    .orElseThrow(() -> new java.io.IOException("UNWIND_INTENT_NOT_FOUND"));
            if (!currentIntent.participant().equals(participant)
                    || currentIntent.version() != prepared.claimEpoch()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED,
                        AgentNextAction.REQUEST_HUMAN_HELP, Map.of("reason", "UNWIND_CALLER_MISMATCH"));
            }
            WorkIntent replacement = new WorkIntent(currentIntent.intentId(), currentIntent.projectId(),
                    currentIntent.participant(), currentIntent.provider(), currentIntent.taskId(),
                    currentIntent.goal(), currentIntent.acceptance(), currentIntent.baseCommit(),
                    currentIntent.selectors(), currentIntent.version() + 1, currentIntent.workGroupId(),
                    currentIntent.authorityLineageId(), WorkIntent.Status.ANNOUNCED,
                    currentIntent.completionMode(), currentIntent.role(), currentIntent.reviewTargetSelectors());
            CompletionUnwoundPayload payload = new CompletionUnwoundPayload(prepared, replacement);
            try (ProjectAppendLock lock = ProjectAppendLock.acquire(location.root().resolve(".synesis/coordination"))) {
                if (!lock.isHeld()) throw new java.io.IOException("event append lock unavailable");
                PredictionEventStore currentStore = new PredictionEventStore(
                        location.root().resolve(".synesis/coordination"), location.projectId());
                if (currentStore.taskCompletionProjection().findPrepared(taskId).isEmpty()) {
                    return new AgentResponse(AgentStatus.COMPLETED, null, null,
                            Map.of("task", "already_unwound", "claimEpoch", replacement.version()));
                }
                currentStore.append(taskId, PredictionEventType.COMPLETION_UNWOUND,
                        identity.nodeId(), payload.encode(), identity);
            }
            try {
                snapshotService.removePreparedRef(Path.of(binding.worktreePath()), prepared.preparedRef());
            } catch (Exception cleanupFailure) {
                return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE,
                        AgentNextAction.REQUEST_HUMAN_HELP,
                        Map.of("error", "PREPARED_REF_CLEANUP_FAILED", "message", cleanupFailure.getMessage()));
            }
            return new AgentResponse(AgentStatus.COMPLETED, null, null,
                    Map.of("task", "completion_unwound", "claimEpoch", replacement.version()));
        } catch (Exception failure) {
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE,
                    AgentNextAction.REQUEST_HUMAN_HELP,
                    Map.of("error", failure.getClass().getSimpleName(),
                            "message", failure.getMessage() == null ? "unwind failed" : failure.getMessage()));
        }
    }

    private static void releaseClaims(CompleteTaskRequest request, WorkspaceCollaborationService service) {
        try {
            service.release(request.projectRoot(), request.provider(), request.connectionInstanceId());
        } catch (Exception ignored) {
            // The task result remains authoritative; reconciliation can retry release.
        }
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
        return deriveTaskId(binding);
    }

    private static UUID deriveTaskId(ProviderSessionBindingService.Binding binding) {
        if (binding.sessionId() != null && binding.sessionId().length() >= 36) {
            try {
                return UUID.fromString(binding.sessionId().substring(binding.sessionId().length() - 36));
            } catch (Exception ignored) {
            }
        }
        return UUID.nameUUIDFromBytes(binding.sessionId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static UUID deriveIntentId(ProviderSessionBindingService.Binding binding, String provider) {
        return UUID.nameUUIDFromBytes((provider + ":" + binding.sessionId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
