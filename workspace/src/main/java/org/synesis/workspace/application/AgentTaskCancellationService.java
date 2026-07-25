package org.synesis.workspace.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.coordination.CapabilityRequestRecord;
import org.synesis.coordination.OwnershipRegistry;
import org.synesis.coordination.PredictionEventStore;
import org.synesis.coordination.PredictionEventType;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * Application service handling ambient task cancellation requests for workers (MCP tool #11 {@code synesis.cancel_task}).
 *
 * @since 1.0
 */
public final class AgentTaskCancellationService {

    private final ProjectApplicationService projectService;
    private final ProviderSessionBindingService bindingService;

    /**
     * Creates an agent task cancellation service.
     */
    public AgentTaskCancellationService() {
        this.projectService = new ProjectApplicationService();
        this.bindingService = new ProviderSessionBindingService();
    }

    /**
     * Request payload for ambient task cancellation.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     * @param reason               cancellation reason string (bounded 1-1000 characters)
     */
    public record CancelTaskRequest(
            Path projectRoot,
            String provider,
            String connectionInstanceId,
            String reason
    ) {
        /**
         * Validates non-null core parameters.
         */
        public CancelTaskRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        }
    }

    /**
     * Executes ambient task cancellation authorization, event appends, dependency invalidation,
     * ownership release, and session finalization.
     *
     * @param request cancel task request
     * @return concise agent response payload
     */
    public AgentResponse cancelTask(CancelTaskRequest request) {
        Objects.requireNonNull(request, "request");

        if (request.reason() == null || request.reason().isBlank() || request.reason().length() > 1000) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, null);
        }

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
            if ("REVOKED".equalsIgnoreCase(binding.status())
                    || "COMPLETED".equalsIgnoreCase(binding.status())
                    || "ABANDONED".equalsIgnoreCase(binding.status())
                    || binding.worktreePath() == null) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.SESSION_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
            }
            identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        String callerNodeId = identity.nodeId();
        String callerSupervisorId = binding.supervisorId();
        String callerWorkerId = binding.workerId();

        try {
            Path coordDir = location.root().resolve(".synesis/coordination");
            PredictionEventStore store = new PredictionEventStore(coordDir, location.projectId());

            UUID taskId = deriveTaskId(binding);

            // Check if task is already integrated or integrating
            if (store.taskCompletionProjection().taskState(taskId) == org.synesis.coordination.TaskCompletionState.INTEGRATED) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.WAIT, Map.of("reason", "task_not_cancellable"));
            }

            // Check if task is currently cancelled (Idempotent success)
            if (isTaskCancelled(store, taskId)) {
                Map<String, Object> result = Map.of("task", "cancelled");
                return new AgentResponse(AgentStatus.COMPLETED, null, null, result);
            }

            byte[] payload = request.reason().trim().getBytes(StandardCharsets.UTF_8);

            // 1. Append TASK_CANCELLATION_REQUESTED
            store.append(taskId, PredictionEventType.TASK_CANCELLATION_REQUESTED, callerNodeId, payload, identity);

            // 2. Append TASK_CANCELLED
            store.append(taskId, PredictionEventType.TASK_CANCELLED, callerNodeId, payload, identity);

            // 3. Cancel pending capability requests created by this caller
            List<CapabilityRequestRecord> createdReqs = store.capabilityRequestProjection().findAllForRequester(callerNodeId);
            for (CapabilityRequestRecord req : createdReqs) {
                if (req.matchesRequester(callerNodeId, callerSupervisorId, callerWorkerId)) {
                    store.append(taskId, PredictionEventType.CAPABILITY_REQUEST_CANCELLED, callerNodeId,
                            req.handle().value().getBytes(StandardCharsets.UTF_8), identity);
                }
            }

            // 4. Invalidate capability dependencies provided by this cancelled task
            store.append(taskId, PredictionEventType.DEPENDENCY_INVALIDATED, callerNodeId,
                    ("invalidated:" + callerWorkerId).getBytes(StandardCharsets.UTF_8), identity);

            // 5. Release ownership claims if active
            var coordProj = store.coordinationProjection();
            for (var entry : coordProj.ownerships().entrySet()) {
                var claim = entry.getValue();
                if (callerNodeId.equals(claim.ownerNodeId()) && taskId.equals(claim.taskId())) {
                    org.synesis.coordination.CoordinationCommand relCmd = org.synesis.coordination.CoordinationCommand.create(
                            UUID.randomUUID(), store.projectId(), claim.taskId(),
                            PredictionEventType.OWNERSHIP_RELEASED, identity.nodeId(),
                            claim.encoded(), identity);
                    store.append(taskId, PredictionEventType.OWNERSHIP_RELEASED, callerNodeId, relCmd.encoded(), identity);
                }
            }

            // 6. Finalize provider session with cancellation outcome
            store.append(taskId, PredictionEventType.SESSION_FINALIZED, callerNodeId,
                    ("session_cancelled:" + binding.sessionId()).getBytes(StandardCharsets.UTF_8), identity);

            Map<String, Object> result = Map.of("task", "cancelled");
            return new AgentResponse(AgentStatus.COMPLETED, null, null, result);

        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
        }
    }

    private static boolean isTaskCancelled(PredictionEventStore store, UUID taskId) {
        for (var ev : store.events()) {
            if (ev.predictionId().equals(taskId) && ev.type() == PredictionEventType.TASK_CANCELLED) {
                return true;
            }
        }
        return false;
    }

    private static UUID deriveTaskId(ProviderSessionBindingService.Binding binding) {
        if (binding.sessionId() != null && binding.sessionId().length() >= 36) {
            try {
                return UUID.fromString(binding.sessionId().substring(binding.sessionId().length() - 36));
            } catch (Exception ignored) {
            }
        }
        return UUID.nameUUIDFromBytes(binding.sessionId().getBytes(StandardCharsets.UTF_8));
    }
}
