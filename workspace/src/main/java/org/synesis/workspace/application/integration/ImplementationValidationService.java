package org.synesis.workspace.application.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.integration.ImplementationEventPayload;
import org.synesis.coordination.domain.integration.ImplementationRevisionRecord;
import org.synesis.coordination.domain.integration.ValidationContextRecord;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentFailures;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;

/**
 * Application service for requesters to validate an available implementation snapshot.
 *
 * <p>When the requester calls the {@code respond_coordination} implementation-validation variant:
 * <ol>
 *   <li>Authorizes the ambient worker as the original requester.</li>
 *   <li>Verifies the capability is in {@code IMPLEMENTATION_AVAILABLE} or {@code VALIDATING} state.</li>
 *   <li>{@code inspect} creates a disposable validation worktree and appends
 *       {@code CAPABILITY_VALIDATION_STARTED}; decisions require that exact durable context.</li>
 *   <li>Processes the inspected result: {@code accepted} → {@code VALIDATED},
 *       {@code revision_required} → {@code IMPLEMENTING}.</li>
 *   <li>Cleans up the validation worktree after result is recorded.</li>
 * </ol>
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class ImplementationValidationService {

    private final ProjectApplicationService projectService;
    private final SessionAuthorityResolver authorityResolver;
    private final ProviderManualService manualService;
    private final ValidationWorkspaceService validationWorkspaceService;

    /**
     * Creates an implementation validation service.
     */
    public ImplementationValidationService() {
        this.projectService = new ProjectApplicationService();
        this.authorityResolver = new SessionAuthorityResolver(new ProviderSessionBindingService());
        this.manualService = new ProviderManualService();
        this.validationWorkspaceService = new ValidationWorkspaceService();
    }

    /**
     * Validates the currently available implementation snapshot for the given capability request.
     *
     * @param request validate request payload
     * @return concise agent response
     */
    @SuppressWarnings("ExtractMethodRecommender")
    public AgentResponse validateImplementation(ValidateRequest request) {
        Objects.requireNonNull(request, "request");

        Path root = request.projectRoot().toAbsolutePath().normalize();
        try {
            manualService.requireAttested(request.provider());
        } catch (Exception failure) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, Map.of("reason", "MANUAL_ATTESTATION_REQUIRED"));
        }
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        ProjectLocation location;
        ProviderSessionBindingService.Binding binding;
        NodeIdentity identity;
        try {
            location = projectService.locate(root);
            binding = authorityResolver.resolve(location, request.provider(), request.connectionInstanceId());
            if (binding.worktreePath() == null) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.SESSION_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
            }
            identity = location.loadOrCreateIdentity();
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        String requesterNodeId = identity.nodeId();
        try {
            Path coordDir = location.coordinationDirectory();
            try (ProjectAppendLock lock = ProjectAppendLock.acquire(coordDir)) {
                if (!lock.isHeld()) {
                    return blocked("EVENT_APPEND_LOCK_UNAVAILABLE", AgentReason.TASK_NOT_READY);
                }
                PredictionEventStore store = location.openCoordinationStore();
                Optional<CapabilityRequestRecord> recOpt = store.capabilityRequestProjection().findByHandle(request.requestHandle());
                if (recOpt.isEmpty()) {
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.REQUEST_NOT_FOUND, AgentNextAction.RETRY, null);
                }
                CapabilityRequestRecord record = recOpt.get();
                if (!record.matchesRequester(requesterNodeId, binding.supervisorId(), binding.workerId())) {
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, null);
                }
                Optional<ImplementationRevisionRecord> implOpt = store.capabilityRequestProjection().findLatestImplementation(request.requestHandle());
                if (implOpt.isEmpty()) {
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.IMPLEMENTATION_UNAVAILABLE, AgentNextAction.WAIT, null);
                }
                ImplementationRevisionRecord implementation = implOpt.get();
                if (request.implementationRevision() < 1 || request.implementationRevision() != implementation.revisionNumber()) {
                    return new AgentResponse(AgentStatus.BLOCKED,
                            AgentReason.STALE_REQUEST,
                            AgentNextAction.RETRY,
                            Map.of("expectedRevision", implementation.revisionNumber(), "providedRevision", request.implementationRevision()));
                }
                String result = request.result().trim().toLowerCase(java.util.Locale.ROOT);
                if ("inspect".equals(result)) {
                    return beginInspection(request, location, binding, identity, store, record, implementation);
                }
                if (!"accepted".equals(result) && !"revision_required".equals(result)) {
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, null);
                }
                ValidationContextRecord context = matchingValidationContext(store, request, binding, record, implementation);
                if (context == null) {
                    return blocked("VALIDATION_INSPECTION_REQUIRED", AgentReason.POLICY_DENIED);
                }
                Path validationWorktree = validationWorkspaceService.verifyValidationWorktree(location.root(),
                        record.handle(),
                        implementation.revisionNumber(),
                        implementation.commitSha(),
                        context.worktreePath());
                if ("accepted".equals(result)) {
                    ImplementationEventPayload validatedPayload = event(record, implementation, "accepted", "", List.of(), "", binding.sessionId());
                    store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_VALIDATED, requesterNodeId, validatedPayload.encode(), identity);
                    removeValidationWorktree(validationWorktree.toString());
                    return new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of("request", record.handle().value(), "capability", "validated"));
                }
                String reason = request.reason() != null && !request.reason().isBlank() ? request.reason() : "Validation failed";
                ImplementationEventPayload revisionPayload = event(record, implementation, "revision_required", reason, request.failedAcceptanceTests(), "", binding.sessionId());
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED, requesterNodeId, revisionPayload.encode(), identity);
                removeValidationWorktree(validationWorktree.toString());
                return new AgentResponse(AgentStatus.WAITING, AgentReason.REVISION_REQUIRED, AgentNextAction.WAIT, Map.of("request", record.handle().value()));
            }

        } catch (Exception ex) {
            if (ex instanceof java.io.IOException ioFailure && ioFailure.getMessage() != null
                    && ioFailure.getMessage().startsWith("VALIDATION_WORKSPACE")) {
                return blocked(ioFailure.getMessage(), AgentReason.TASK_NOT_READY);
            }
            return AgentFailures.internal(request.projectRoot(), "capability.validate", ex, null);
        }
    }

    private AgentResponse beginInspection(ValidateRequest request,
            ProjectLocation location,
            ProviderSessionBindingService.Binding binding,
            NodeIdentity identity,
            PredictionEventStore store,
            CapabilityRequestRecord record,
            ImplementationRevisionRecord implementation) throws Exception {
        if (record.state() == CapabilityLifecycleState.VALIDATING) {
            ValidationContextRecord context = matchingValidationContext(store, request, binding, record, implementation);
            if (context == null) {
                return blocked("VALIDATION_CONTEXT_STALE", AgentReason.STALE_REQUEST);
            }
            validationWorkspaceService.verifyValidationWorktree(location.root(), record.handle(), implementation.revisionNumber(), implementation.commitSha(), context.worktreePath());
            return inspectionReady(record, implementation);
        }
        if (record.state() != CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE
                || store.capabilityRequestProjection().findValidationContext(request.requestHandle()).isPresent()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY, null);
        }
        Path validationWorktree = validationWorkspaceService.createValidationWorktree(location.root(), record.handle(), implementation.revisionNumber(), implementation);
        try {
            ImplementationEventPayload started = event(record, implementation, "", "", List.of(), validationWorktree.toString(), binding.sessionId());
            store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_VALIDATION_STARTED, identity.nodeId(), started.encode(), identity);
            ValidationContextRecord context = store.capabilityRequestProjection().findValidationContext(request.requestHandle()).orElse(null);
            if (context == null || !matchesContext(context, request, binding)) {
                removeValidationWorktree(validationWorktree.toString());
                return blocked("VALIDATION_CONTEXT_PERSISTENCE_FAILED", AgentReason.TASK_NOT_READY);
            }
            validationWorkspaceService.verifyValidationWorktree(location.root(), record.handle(), implementation.revisionNumber(), implementation.commitSha(), context.worktreePath());
            return inspectionReady(record, implementation);
        } catch (Exception failure) {
            removeValidationWorktree(validationWorktree.toString());
            throw failure;
        }
    }

    private static ValidationContextRecord matchingValidationContext(PredictionEventStore store,
            ValidateRequest request,
            ProviderSessionBindingService.Binding binding,
            CapabilityRequestRecord record,
            ImplementationRevisionRecord implementation) {
        if (record.state() != CapabilityLifecycleState.VALIDATING) {
            return null;
        }
        ValidationContextRecord context = store.capabilityRequestProjection().findValidationContext(request.requestHandle()).orElse(null);
        return context != null && context.revisionNumber() == implementation.revisionNumber() && matchesContext(context, request, binding) ? context : null;
    }

    private static boolean matchesContext(ValidationContextRecord context, ValidateRequest request, ProviderSessionBindingService.Binding binding) {
        return context.handle().value().equals(request.requestHandle())
                && context.revisionNumber() == request.implementationRevision()
                && context.requesterSessionId().equals(binding.sessionId());
    }

    private static AgentResponse inspectionReady(CapabilityRequestRecord record, ImplementationRevisionRecord implementation) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("capabilityRequestHandle", record.handle().value());
        details.put("implementationRevision", implementation.revisionNumber());
        details.put("validationContextActive", true);
        details.put("workspace", "capability_validation");
        return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED, AgentNextAction.VALIDATE_IMPLEMENTATION, details);
    }

    private static ImplementationEventPayload event(CapabilityRequestRecord record,
            ImplementationRevisionRecord implementation,
            String result,
            String reason,
            List<String> failedTests,
            String worktreePath,
            String requesterSessionId) {
        return new ImplementationEventPayload(record.handle(),
                record.authorityLineageId(),
                implementation.revisionNumber(),
                implementation.baseCommit(),
                implementation.commitSha(),
                implementation.changedPaths(),
                implementation.summary(),
                result,
                reason,
                failedTests,
                worktreePath,
                requesterSessionId);
    }

    private static AgentResponse blocked(String code, AgentReason reason) {
        return new AgentResponse(AgentStatus.BLOCKED, reason, AgentNextAction.RETRY, Map.of("reason", code));
    }

    /**
     * Removes a temporary validation worktree when validation created one.
     *
     * @param worktreePath temporary worktree path, or blank when none exists
     */
    private void removeValidationWorktree(String worktreePath) {
        if (!worktreePath.isBlank()) {
            validationWorkspaceService.removeValidationWorktree(Path.of(worktreePath));
        }
    }

    /**
     * Request parameters for validating an available implementation.
     *
     * @param projectRoot            control project root path
     * @param provider               provider identifier
     * @param connectionInstanceId   connection instance identifier
     * @param requestHandle          public capability request handle
     * @param result                 {@code "inspect"}, {@code "accepted"}, or {@code "revision_required"}
     * @param reason                 free-text failure reason (required when
     *                               {@code result=revision_required})
     * @param implementationRevision exact server-published implementation revision (zero permits
     *                               internal recovery callers)
     * @param failedAcceptanceTests  list of failed acceptance test names (for revision_required)
     */
    public record ValidateRequest(Path projectRoot, String provider, String connectionInstanceId, String requestHandle, String result, String reason, int implementationRevision, List<String> failedAcceptanceTests) {

        /**
         * Validates non-null required request parameters.
         */
        public ValidateRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(requestHandle, "requestHandle");
            Objects.requireNonNull(result, "result");
            if (implementationRevision < 0) {
                throw new IllegalArgumentException("implementationRevision must not be negative");
            }
            failedAcceptanceTests = failedAcceptanceTests != null ? List.copyOf(failedAcceptanceTests) : List.of();
        }
    }
}
