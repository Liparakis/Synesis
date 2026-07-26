package org.synesis.workspace.application;

import org.synesis.workspace.project.ProjectApplicationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.CapabilityLifecycleState;
import org.synesis.coordination.domain.CapabilityRequestRecord;

import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.domain.PredictionEventType;
import org.synesis.coordination.domain.TaskSnapshotPayload;
import org.synesis.coordination.domain.TaskSnapshotRecord;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * Application service for workers to request task completion.
 *
 * <p>Handles tool calls for {@code synesis.complete_task}.
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

    /**
     * Creates an agent task completion service.
     */
    public AgentTaskCompletionService() {
        this.projectService = new ProjectApplicationService();
        this.bindingService = new ProviderSessionBindingService();
        this.snapshotService = new TaskSnapshotService();
        this.integrationOrchestrationService = new IntegrationOrchestrationService();
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
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        ProjectApplicationService.ProjectLocation location;
        ProviderSessionBindingService.Binding binding;
        NodeIdentity identity;
        try {
            location = projectService.locate(root);
            var bindings = bindingService.list(location, request.provider());
            if (bindings.isEmpty()) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.SESSION_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
            }
            binding = bindings.getLast();
            if (!"BOUND".equals(binding.status()) || binding.worktreePath() == null) {
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
                System.out.println("[REQ-PENDING] callerWorker=" + callerWorkerId + " count=" + callerPending.size()
                        + " items=" + callerPending.stream().map(r -> r.handle().value() + ":" + r.state() + " reqW=" + r.requesterWorkerId()).toList());
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
            Optional<TaskSnapshotRecord> existingOpt = store.taskCompletionProjection().findSnapshotForTask(taskId);

            TaskSnapshotRecord snapshot;
            try {
                snapshot = snapshotService.createSnapshot(
                        taskId, callerNodeId, callerSupervisorId, callerWorkerId,
                        binding.sessionId(), workerWorktreePath, location.root(),
                        summaryText, existingOpt, workerCapabilities);
            } catch (IllegalStateException immutabilityError) {
                // Task snapshot is immutable and content changed after completion
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, null);
            }

            // 4. Persist TASK_SNAPSHOT_CREATED if new
            if (existingOpt.isEmpty()) {
                TaskSnapshotPayload snapPayload = new TaskSnapshotPayload(
                        snapshot.taskId(), snapshot.snapshotId(), snapshot.nodeId(), snapshot.supervisorId(),
                        snapshot.workerId(), snapshot.providerSessionId(), snapshot.baseCommit(), snapshot.commitSha(),
                        snapshot.changedPaths(), snapshot.capabilityDependencies(), snapshot.summary());

                store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                        callerNodeId, snapPayload.encode(), identity);
            }

            // 5. Trigger integration orchestration
            return integrationOrchestrationService.orchestrateIntegration(location.root(), store, identity);

        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
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
