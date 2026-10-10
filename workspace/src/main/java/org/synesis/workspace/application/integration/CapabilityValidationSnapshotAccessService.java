package org.synesis.workspace.application.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.integration.ImplementationRevisionRecord;
import org.synesis.coordination.domain.integration.ValidationContextRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.project.execution.ExecutionRequest;
import org.synesis.workspace.application.project.execution.ExecutionResult;
import org.synesis.workspace.application.project.execution.ProjectProcessExecutor;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;

/** Resolves reads and test commands to the exact requester-owned validation revision. */
public final class CapabilityValidationSnapshotAccessService {

    private final ProjectApplicationService projectService;
    private final SessionAuthorityResolver authorityResolver;
    private final ValidationWorkspaceService validationWorkspaces;
    private final ProjectProcessExecutor processExecutor;

    /** Creates validation access backed by current provider bindings and durable capability state. */
    public CapabilityValidationSnapshotAccessService() {
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        this.projectService = new ProjectApplicationService();
        this.authorityResolver = new SessionAuthorityResolver(bindings);
        this.validationWorkspaces = new ValidationWorkspaceService();
        this.processExecutor = new ProjectProcessExecutor();
    }

    /**
     * Resolves one explicitly selected validation worktree. Calls without validation fields leave
     * normal lane and review access unchanged; partial or stale fields fail closed.
     *
     * @param projectRoot control project root
     * @param provider provider identifier
     * @param connectionInstanceId exact provider connection
     * @param requestHandle capability request being validated, or {@code null} when not requested
     * @param revision exact current implementation revision
     * @return internal validation access, an absent result, or a denial
     */
    public AccessResult resolve(Path projectRoot,
            String provider,
            String connectionInstanceId,
            String requestHandle,
            Integer revision) {
        if (requestHandle == null && revision == null) {
            return new AccessResult(null, null);
        }
        if (requestHandle == null || requestHandle.isBlank() || revision == null || revision < 1) {
            return denied("VALIDATION_INSPECTION_EVIDENCE_REQUIRED");
        }

        try {
            ProjectLocation location = projectService.locate(projectRoot.toAbsolutePath().normalize());
            ProviderSessionBindingService.Binding binding = authorityResolver.resolve(location, provider, connectionInstanceId);
            if (!"BOUND".equals(binding.status()) || !"VERIFIED".equals(binding.providerTrustState())) {
                return denied("VALIDATION_REQUESTER_BINDING_UNVERIFIED");
            }
            var identity = location.loadOrCreateIdentity();
            Path coordination = location.coordinationDirectory();
            if (!Files.isDirectory(coordination.resolve("events"))) {
                return denied("VALIDATION_CONTEXT_UNAVAILABLE");
            }

            PredictionEventStore store = location.openCoordinationStore();
            CapabilityRequestRecord request = store.capabilityRequestProjection().findByHandle(requestHandle).orElse(null);
            if (request == null || !request.matchesRequester(identity.nodeId(), binding.supervisorId(), binding.workerId())) {
                return denied("VALIDATION_REQUESTER_MISMATCH");
            }
            if (request.state() != CapabilityLifecycleState.VALIDATING) {
                return denied("VALIDATION_NOT_STARTED");
            }
            ImplementationRevisionRecord implementation = store.capabilityRequestProjection().findLatestImplementation(requestHandle).orElse(null);
            ValidationContextRecord context = store.capabilityRequestProjection().findValidationContext(requestHandle).orElse(null);
            if (implementation == null || implementation.revisionNumber() != revision || context == null || context.revisionNumber() != revision
                    || !context.requesterSessionId().equals(binding.sessionId())) {
                return denied("VALIDATION_REVISION_OR_SESSION_STALE");
            }
            Path worktree = validationWorkspaces.verifyValidationWorktree(location.root(),
                    request.handle(),
                    revision,
                    implementation.commitSha(),
                    context.worktreePath());
            return new AccessResult(new Access(request.handle().value(), revision, implementation.commitSha(), worktree), null);
        } catch (Exception failure) {
            String message = failure.getMessage();
            return denied(message != null && message.startsWith("VALIDATION_") ? message : "VALIDATION_SNAPSHOT_ACCESS_FAILED");
        }
    }

    /**
     * Executes a bounded direct-argv command inside the exact authorized validation workspace.
     *
     * @param projectRoot project control root
     * @param provider provider identifier
     * @param connectionInstanceId exact provider connection
     * @param requestHandle validation request handle, or {@code null} for ordinary commands
     * @param revision exact validation revision
     * @param argv executable and arguments passed without a shell
     * @param workingDirectory relative directory in the validation worktree
     * @param timeoutSeconds bounded timeout or {@code null} for the default
     * @return validation command result, denial, or {@code null} when no validation was requested
     */
    public AgentResponse runCommand(Path projectRoot,
            String provider,
            String connectionInstanceId,
            String requestHandle,
            Integer revision,
            List<String> argv,
            String workingDirectory,
            Integer timeoutSeconds) {
        AccessResult resolution = resolve(projectRoot, provider, connectionInstanceId, requestHandle, revision);
        if (!resolution.available()) {
            return resolution.denied() ? blocked(resolution.error()) : null;
        }
        Access access = resolution.access();
        ExecutionResult execution = processExecutor.execute(new ExecutionRequest(argv,
                access.worktreePath(),
                workingDirectory,
                timeoutSeconds,
                projectRoot));
        Map<String, Object> result = new LinkedHashMap<>(execution.toMap());
        result.put("workspace", "capability_validation_snapshot");
        result.put("capabilityRequestHandle", access.requestHandle());
        result.put("implementationRevision", access.revision());
        return new AgentResponse(commandStatus(execution), commandReason(execution), commandNextAction(execution), result);
    }

    private static AccessResult denied(String error) {
        return new AccessResult(null, error);
    }

    private static AgentResponse blocked(String error) {
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, Map.of("reason", error));
    }

    private static AgentStatus commandStatus(ExecutionResult result) {
        return switch (result.outcome()) {
            case COMPLETED -> AgentStatus.COMPLETED;
            case NON_ZERO_EXIT, COMMAND_WORKING_DIRECTORY_INVALID, COMMAND_EXECUTABLE_NOT_FOUND, COMMAND_PERMISSION_DENIED -> AgentStatus.BLOCKED;
            case COMMAND_TIMED_OUT, COMMAND_CANCELLED, COMMAND_START_FAILED, COMMAND_TERMINATED -> AgentStatus.FAILED;
        };
    }

    private static AgentReason commandReason(ExecutionResult result) {
        return switch (result.outcome()) {
            case COMPLETED -> null;
            case NON_ZERO_EXIT -> AgentReason.COMMAND_FAILED;
            case COMMAND_WORKING_DIRECTORY_INVALID -> AgentReason.COMMAND_WORKING_DIRECTORY_INVALID;
            case COMMAND_EXECUTABLE_NOT_FOUND -> AgentReason.COMMAND_EXECUTABLE_NOT_FOUND;
            case COMMAND_PERMISSION_DENIED -> AgentReason.COMMAND_PERMISSION_DENIED;
            case COMMAND_TIMED_OUT -> AgentReason.COMMAND_TIMEOUT;
            case COMMAND_CANCELLED -> AgentReason.COMMAND_CANCELLED;
            case COMMAND_START_FAILED -> AgentReason.COMMAND_START_FAILED;
            case COMMAND_TERMINATED -> AgentReason.COMMAND_TERMINATED;
        };
    }

    private static AgentNextAction commandNextAction(ExecutionResult result) {
        return switch (result.outcome()) {
            case COMPLETED, NON_ZERO_EXIT, COMMAND_WORKING_DIRECTORY_INVALID, COMMAND_EXECUTABLE_NOT_FOUND, COMMAND_PERMISSION_DENIED -> null;
            case COMMAND_TIMED_OUT, COMMAND_CANCELLED, COMMAND_START_FAILED, COMMAND_TERMINATED -> AgentNextAction.REQUEST_HUMAN_HELP;
        };
    }

    /**
     * Resolved data kept inside workspace services and never returned with an absolute path.
     *
     * @param requestHandle exact validation request
     * @param revision implementation revision
     * @param commitSha immutable implementation commit
     * @param worktreePath validated local inspection worktree
     */
    public record Access(String requestHandle, int revision, String commitSha, Path worktreePath) {

        /** Ensures one complete revision reference reached the caller. */
        public Access {
            Objects.requireNonNull(requestHandle, "requestHandle");
            Objects.requireNonNull(commitSha, "commitSha");
            Objects.requireNonNull(worktreePath, "worktreePath");
        }
    }

    /**
     * Result of resolving an optional validation selection for the current connection.
     *
     * @param access internal worktree authority, or {@code null}
     * @param error bounded denial reason, or {@code null}
     */
    public record AccessResult(Access access, String error) {

        /** Rejects contradictory resolution results. */
        public AccessResult {
            if (access != null && error != null) {
                throw new IllegalArgumentException("validation access cannot contain both access and error");
            }
        }

        /**
         * Returns whether the requested validation context is available.
         *
         * @return {@code true} when the exact request and revision resolve
         */
        public boolean available() {
            return access != null;
        }

        /**
         * Returns whether supplied validation evidence was denied.
         *
         * @return {@code true} when supplied fields failed closed
         */
        public boolean denied() {
            return error != null;
        }
    }
}
