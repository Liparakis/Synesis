package org.synesis.workspace.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.CapabilityLifecycleState;
import org.synesis.coordination.domain.CapabilityRequestRecord;
import org.synesis.coordination.domain.ImplementationEventPayload;
import org.synesis.coordination.domain.ImplementationRevisionRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.domain.PredictionEventType;
import org.synesis.coordination.domain.ValidationContextRecord;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * Application service for requesters to validate an available implementation snapshot.
 *
 * <p>When the requester calls {@code synesis.validate_available_implementation}:
 * <ol>
 *   <li>Authorizes the ambient worker as the original requester.</li>
 *   <li>Verifies the capability is in {@code IMPLEMENTATION_AVAILABLE} or {@code VALIDATING} state.</li>
 *   <li>If {@code IMPLEMENTATION_AVAILABLE}: creates a disposable validation worktree and
 *       appends {@code CAPABILITY_VALIDATION_STARTED}.</li>
 *   <li>Processes the validation result: {@code accepted} → {@code VALIDATED},
 *       {@code revision_required} → {@code IMPLEMENTING}.</li>
 *   <li>Cleans up the validation worktree after result is recorded.</li>
 * </ol>
 *
 * @since 1.0
 */
public final class ImplementationValidationService {

    private final ProjectApplicationService projectService;
    private final ProviderSessionBindingService bindingService;
    private final ValidationWorkspaceService validationWorkspaceService;

    /**
     * Creates an implementation validation service.
     */
    public ImplementationValidationService() {
        this.projectService = new ProjectApplicationService();
        this.bindingService = new ProviderSessionBindingService();
        this.validationWorkspaceService = new ValidationWorkspaceService();
    }

    /**
     * Request parameters for validating an available implementation.
     *
     * @param projectRoot           control project root path
     * @param provider              provider identifier
     * @param connectionInstanceId  connection instance identifier
     * @param requestHandle         public capability request handle
     * @param result                validation result: {@code "accepted"} or {@code "revision_required"}
     * @param reason                free-text failure reason (required when {@code result=revision_required})
     * @param failedAcceptanceTests list of failed acceptance test names (for revision_required)
     */
    public record ValidateRequest(
            Path projectRoot,
            String provider,
            String connectionInstanceId,
            String requestHandle,
            String result,
            String reason,
            List<String> failedAcceptanceTests
    ) {
        /**
         * Validates non-null required request parameters.
         */
        public ValidateRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(requestHandle, "requestHandle");
            Objects.requireNonNull(result, "result");
            failedAcceptanceTests = failedAcceptanceTests != null ? List.copyOf(failedAcceptanceTests) : List.of();
        }
    }

    /**
     * Validates the currently available implementation snapshot for the given capability request.
     *
     * @param request validate request payload
     * @return concise agent response
     */
    public AgentResponse validateImplementation(ValidateRequest request) {
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

        String requesterNodeId = identity.nodeId();
        Path requesterWorktreePath = Path.of(binding.worktreePath()).toAbsolutePath().normalize();

        try {
            Path coordDir = location.root().resolve(".synesis/coordination");
            PredictionEventStore store = new PredictionEventStore(coordDir, location.projectId());

            Optional<CapabilityRequestRecord> recOpt = store.capabilityRequestProjection().findByHandle(request.requestHandle());
            if (recOpt.isEmpty()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.REQUEST_NOT_FOUND, AgentNextAction.RETRY, null);
            }
            CapabilityRequestRecord record = recOpt.get();

            // Authorization: caller must be the exact requester (node + worker)
            if (!record.matchesRequester(requesterNodeId, binding.supervisorId(), binding.workerId())) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, null);
            }

            CapabilityLifecycleState state = record.state();

            // State must be IMPLEMENTATION_AVAILABLE or VALIDATING
            if (state != CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE
                    && state != CapabilityLifecycleState.VALIDATING) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY, null);
            }

            // Resolve current implementation revision
            Optional<ImplementationRevisionRecord> implOpt = store.capabilityRequestProjection()
                    .findLatestImplementation(request.requestHandle());
            if (implOpt.isEmpty()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.IMPLEMENTATION_UNAVAILABLE, AgentNextAction.WAIT, null);
            }
            ImplementationRevisionRecord impl = implOpt.get();

            String normalizedResult = request.result().trim().toLowerCase(java.util.Locale.ROOT);
            if (!normalizedResult.equals("accepted") && !normalizedResult.equals("revision_required")) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, null);
            }

            // If IMPLEMENTATION_AVAILABLE: create validation worktree and transition to VALIDATING
            String worktreePath = "";
            if (state == CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE) {
                try {
                    Path wt = validationWorkspaceService.createValidationWorktree(
                            location.root(), record.handle(), impl.revisionNumber(),
                            requesterWorktreePath, impl);
                    worktreePath = wt.toString();
                } catch (Exception wtEx) {
                    // Worktree creation failed — log to stderr but do not block validation
                    System.err.println("[synesis-mcp] Validation worktree creation failed: " + wtEx.getMessage());
                    worktreePath = "";
                }

                // Append CAPABILITY_VALIDATION_STARTED
                ImplementationEventPayload startedPayload = new ImplementationEventPayload(
                        record.handle(), impl.revisionNumber(), impl.baseCommit(), impl.commitSha(),
                        impl.changedPaths(), impl.summary(), "", "", List.of(), worktreePath);
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_VALIDATION_STARTED,
                        requesterNodeId, startedPayload.encode(), identity);

                // Re-read updated record from store after event
                Optional<CapabilityRequestRecord> updatedRecOpt = store.capabilityRequestProjection().findByHandle(request.requestHandle());
                if (updatedRecOpt.isPresent()) {
                    record = updatedRecOpt.get();
                }
            } else {
                // Already VALIDATING: recover worktree path from existing context
                Optional<ValidationContextRecord> ctxOpt = store.capabilityRequestProjection()
                        .findValidationContext(request.requestHandle());
                worktreePath = ctxOpt.map(ValidationContextRecord::worktreePath).orElse("");
            }

            // Now process the validation result
            if (normalizedResult.equals("accepted")) {
                ImplementationEventPayload validatedPayload = new ImplementationEventPayload(
                        record.handle(), impl.revisionNumber(), impl.baseCommit(), impl.commitSha(),
                        impl.changedPaths(), impl.summary(), "accepted", "", List.of(), "");
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_VALIDATED,
                        requesterNodeId, validatedPayload.encode(), identity);

                // Cleanup validation worktree
                if (!worktreePath.isBlank()) {
                    validationWorkspaceService.removeValidationWorktree(Path.of(worktreePath));
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("request", record.handle().value());
                result.put("capability", "validated");
                return new AgentResponse(AgentStatus.COMPLETED, null, null, result);

            } else {
                // revision_required
                String reason = (request.reason() != null && !request.reason().isBlank())
                        ? request.reason() : "Validation failed";
                List<String> failedTests = request.failedAcceptanceTests();

                ImplementationEventPayload revisionPayload = new ImplementationEventPayload(
                        record.handle(), impl.revisionNumber(), impl.baseCommit(), impl.commitSha(),
                        impl.changedPaths(), impl.summary(), "revision_required", reason, failedTests, "");
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED,
                        requesterNodeId, revisionPayload.encode(), identity);

                // Cleanup validation worktree
                if (!worktreePath.isBlank()) {
                    validationWorkspaceService.removeValidationWorktree(Path.of(worktreePath));
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("request", record.handle().value());
                return new AgentResponse(AgentStatus.WAITING, AgentReason.REVISION_REQUIRED, AgentNextAction.WAIT, result);
            }

        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
        }
    }
}
