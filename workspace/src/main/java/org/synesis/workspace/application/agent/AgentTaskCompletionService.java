package org.synesis.workspace.application.agent;
import org.synesis.workspace.application.integration.IntegrationOrchestrationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.SessionAuthorityResolver;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.task.TaskSnapshotService;

import org.synesis.workspace.application.ProjectApplicationService;

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
import org.synesis.coordination.domain.task.CompletionPreparedPayload;
import org.synesis.coordination.domain.task.CompletionUnwoundPayload;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
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
    }

    /**
     * Request payload for task completion.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     * @param summary              human-readable task completion summary (optional)
     */
    public record CompleteTaskRequest(
            Path projectRoot,
            String provider,
            String connectionInstanceId,
            String summary
    ) {
        /**
         * Validates non-null core parameters.
         */
        public CompleteTaskRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
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

            if (terminalRetry) {
                UUID completedTaskId = deriveTaskId(binding);
                Optional<TaskSnapshotRecord> completedSnapshot = store.taskCompletionProjection()
                        .findSnapshotForTask(completedTaskId);
                if (completedSnapshot.isPresent()) {
                    return completionResult(store, completedSnapshot.get(),
                            WorkspaceCollaborationService.participantHandle(binding.sessionId()),
                            new AgentResponse(AgentStatus.COMPLETED, null, null,
                                    Map.of("task", "already_integrated")));
                }
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY,
                        AgentNextAction.REQUEST_HUMAN_HELP, Map.of("reason", "COMPLETED_BINDING_WITHOUT_SNAPSHOT"));
            }

            // 1. Completion Readiness Check: Unresolved capability requests
            List<CapabilityRequestRecord> reqPending = store.capabilityRequestProjection().findAllForRequester(callerNodeId);
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

            List<CapabilityRequestRecord> workerCapabilities = store.capabilityRequestProjection().findAllForRequester(callerNodeId);
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
            Optional<TaskSnapshotRecord> existingOpt = store.taskCompletionProjection().findSnapshotForTask(taskId);

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
                                        .defaultAuthorityLineage(taskId)), List.of());
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
                // The legacy helper creates the public ref for direct callers;
                // completion moves that ref behind a durable prepared phase.
                snapshotService.removeSnapshotRef(workerWorktreePath, snapshot);
            }
            String preparedRef = snapshotService.pinPreparedRef(workerWorktreePath, snapshot, completionId);
            String preparedTreeHash = snapshotService.treeHash(workerWorktreePath, snapshot.commitSha());
            if (store.taskCompletionProjection().findPrepared(taskId).isEmpty()) {
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
                    snapshot.changedPaths(), snapshot.capabilityDependencies(), snapshot.summary(), snapshot.provenance());

                store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                        callerNodeId, snapPayload.encode(), identity);
            }

            // 6. Trigger integration orchestration
            AgentResponse result = integrationOrchestrationService.orchestrateIntegration(location.root(), store, identity);
            result = completionResult(store, snapshot, participantHandle, result);
            if (result.status() == AgentStatus.COMPLETED) {
                releaseClaims(request, collaborationService);
                // Complete only the exact calling connection.  Keeping the
                // binding terminal (and retaining its worktree) prevents the
                // next inbox read from attempting to reuse a stale lane.
                bindingService.complete(location, request.provider(), request.connectionInstanceId());
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

    private static AgentResponse completionResult(PredictionEventStore store, TaskSnapshotRecord snapshot,
            String participantHandle, AgentResponse integrationResult) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (integrationResult.result() instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        result.put("participant", participantHandle);
        result.put("laneId", snapshot.provenance().laneId().toString());
        result.put("claimEpoch", snapshot.provenance().claimEpoch());
        result.put("snapshotId", snapshot.snapshotId());
        result.put("snapshotState", "PUBLISHED");
        result.put("integrationState", store.taskCompletionProjection().taskState(snapshot.taskId()).value());
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
                    currentIntent.authorityLineageId(), WorkIntent.Status.ANNOUNCED);
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
}
