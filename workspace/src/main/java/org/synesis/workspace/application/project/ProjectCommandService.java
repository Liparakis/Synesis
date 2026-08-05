package org.synesis.workspace.application.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;

/**
 * Application boundary for direct argv execution in an authenticated lane.
 *
 * <p>This service owns session and control-checkout safety. Process creation,
 * timeout, output evidence, and stream draining are centralized in
 * {@link ProjectProcessExecutor}; no build-system or shell adapter is
 * selected here.</p>
 *
 * @since 1.0
 */
public final class ProjectCommandService {

    private final ProjectApplicationService projectService;
    private final WorkspaceReadinessService readinessService;
    private final ProjectProcessExecutor executor;

    /** Creates a command service using the default project and process services. */
    public ProjectCommandService() {
        this(new ProjectApplicationService(), new WorkspaceReadinessService(), new ProjectProcessExecutor());
    }

    /**
     * Creates a command service with explicit collaborators.
     *
     * @param projectService project discovery service
     * @param readinessService lane readiness service
     * @param executor shared direct process executor
     */
    public ProjectCommandService(ProjectApplicationService projectService,
            WorkspaceReadinessService readinessService, ProjectProcessExecutor executor) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.readinessService = Objects.requireNonNull(readinessService, "readinessService");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Direct argv request from the MCP boundary.
     *
     * @param projectRoot          control checkout
     * @param provider             canonical provider ID
     * @param connectionInstanceId exact persistent MCP connection identity
     * @param argv                 executable and arguments, passed unchanged
     * @param workingDirectory     relative lane directory, or {@code null}
     * @param timeoutSeconds       timeout in seconds, or {@code null} for default
     */
    public record CommandRequest(Path projectRoot, String provider, String connectionInstanceId,
            List<String> argv, String workingDirectory, Integer timeoutSeconds) {
        /** Validates and copies request values. */
        public CommandRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(argv, "argv");
            argv = List.copyOf(argv);
        }

        /**
         * Creates a request with default working directory and timeout.
         *
         * @param projectRoot control checkout
         * @param provider provider identifier
         * @param connectionInstanceId exact connection identity
         * @param argv direct executable and arguments
         */
        public CommandRequest(Path projectRoot, String provider, String connectionInstanceId, List<String> argv) {
            this(projectRoot, provider, connectionInstanceId, argv, ".", null);
        }
    }

    /**
     * Executes direct argv after exact session/worktree readiness checks.
     *
     * @param request command request
     * @return bounded structured command evidence
     */
    public AgentResponse runCommand(CommandRequest request) {
        return runCommand(request, ignored -> {
        });
    }

    /** Executes direct argv while observing the exact child process at start. */
    AgentResponse runCommand(CommandRequest request, Consumer<ProjectProcessExecutor.StartedProcessIdentity> observer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(observer, "observer");
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY,
                    AgentNextAction.ENSURE_SESSION, null);
        }

        ProjectApplicationService.ProjectLocation location;
        WorkspaceReadinessService.ReadinessResult readiness;
        try {
            location = projectService.locate(root);
            readiness = readinessService.assess(location, request.provider(), request.connectionInstanceId());
        } catch (Exception failure) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY,
                    AgentNextAction.ENSURE_SESSION, null);
        }
        if (!readiness.ready()) {
            return readiness.response();
        }

        Path assignedWorktree = readiness.worktree();
        long controlBefore = getDirectoryLastModified(location.root());
        ProjectProcessExecutor.ExecutionResult result = executor.execute(
                new ProjectProcessExecutor.ExecutionRequest(request.argv(), assignedWorktree,
                        request.workingDirectory(), request.timeoutSeconds(), location.root()), observer);
        long controlAfter = getDirectoryLastModified(location.root());
        if (controlAfter > controlBefore) {
            return AgentResponse.blocked(AgentReason.PROTECTED_CONFIGURATION);
        }

        return new AgentResponse(statusFor(result.outcome()), reasonFor(result.outcome()),
                nextActionFor(result.outcome()), result.toMap());
    }

    private static AgentStatus statusFor(ProjectProcessExecutor.Outcome outcome) {
        return switch (outcome) {
            case COMPLETED -> AgentStatus.COMPLETED;
            case NON_ZERO_EXIT, COMMAND_WORKING_DIRECTORY_INVALID, COMMAND_EXECUTABLE_NOT_FOUND,
                    COMMAND_PERMISSION_DENIED -> AgentStatus.BLOCKED;
            case COMMAND_TIMED_OUT, COMMAND_CANCELLED, COMMAND_START_FAILED, COMMAND_TERMINATED -> AgentStatus.FAILED;
        };
    }

    private static AgentReason reasonFor(ProjectProcessExecutor.Outcome outcome) {
        return switch (outcome) {
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

    private static AgentNextAction nextActionFor(ProjectProcessExecutor.Outcome outcome) {
        return switch (outcome) {
            case COMPLETED, NON_ZERO_EXIT, COMMAND_WORKING_DIRECTORY_INVALID,
                    COMMAND_EXECUTABLE_NOT_FOUND, COMMAND_PERMISSION_DENIED -> null;
            case COMMAND_TIMED_OUT, COMMAND_CANCELLED, COMMAND_START_FAILED, COMMAND_TERMINATED ->
                    AgentNextAction.REQUEST_HUMAN_HELP;
        };
    }

    private static long getDirectoryLastModified(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (Exception failure) {
            return 0L;
        }
    }
}
