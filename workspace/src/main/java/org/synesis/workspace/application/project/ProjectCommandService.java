package org.synesis.workspace.application.project;

import org.synesis.workspace.application.project.execution.StartedProcessIdentity;

import org.synesis.workspace.application.project.execution.ProjectProcessExecutor;

import org.synesis.workspace.application.project.execution.ExecutionResult;

import org.synesis.workspace.application.project.execution.ExecutionRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.List;
import java.util.Objects;

import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.workspace.ReadinessResult;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;

/**
 * Application boundary for direct argv execution in an authenticated lane.
 *
 * <p>This service owns session and control-checkout safety. Process creation,
 * timeout, output evidence, and stream draining are centralized in {@link ProjectProcessExecutor};
 * no build-system or shell adapter is selected here.</p>
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class ProjectCommandService {

    private final ProjectApplicationService projectService;
    private final WorkspaceReadinessService readinessService;
    private final ProjectProcessExecutor executor;

    /**
     * Creates a command service using the default project and process services.
     */
    public ProjectCommandService() {
        this(new ProjectApplicationService(), new WorkspaceReadinessService(), new ProjectProcessExecutor());
    }

    /**
     * Creates a command service with explicit collaborators.
     *
     * @param projectService   project discovery service
     * @param readinessService lane readiness service
     * @param executor         shared direct process executor
     */
    public ProjectCommandService(ProjectApplicationService projectService, WorkspaceReadinessService readinessService, ProjectProcessExecutor executor) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.readinessService = Objects.requireNonNull(readinessService, "readinessService");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    private static long getDirectoryLastModified(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (Exception failure) {
            return 0L;
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

    /**
     * Executes direct argv while observing the exact child process at start.
     */
    AgentResponse runCommand(CommandRequest request, Consumer<StartedProcessIdentity> observer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(observer, "observer");
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return WorkspaceReadinessService.workspaceNotReady();
        }

        ProjectLocation location;
        ReadinessResult readiness;
        try {
            location = projectService.locate(root);
            readiness = readinessService.assess(location, request.provider(), request.connectionInstanceId());
        } catch (Exception failure) {
            return WorkspaceReadinessService.workspaceNotReady();
        }
        if (!readiness.ready()) {
            return readiness.response();
        }

        Path assignedWorktree = readiness.worktree();
        long controlBefore = getDirectoryLastModified(location.root());
        ExecutionResult result = executor.execute(new ExecutionRequest(request.argv(), assignedWorktree, request.workingDirectory(), request.timeoutSeconds(), location.root()), observer);
        long controlAfter = getDirectoryLastModified(location.root());
        if (controlAfter > controlBefore) {
            return AgentResponse.blocked(AgentReason.PROTECTED_CONFIGURATION);
        }

        return result.toAgentResponse(result.toMap());
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
    public record CommandRequest(Path projectRoot, String provider, String connectionInstanceId, List<String> argv, String workingDirectory, Integer timeoutSeconds) {

        /**
         * Validates and copies request values.
         */
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
         * @param projectRoot          control checkout
         * @param provider             provider identifier
         * @param connectionInstanceId exact connection identity
         * @param argv                 direct executable and arguments
         */
        public CommandRequest(Path projectRoot, String provider, String connectionInstanceId, List<String> argv) {
            this(projectRoot, provider, connectionInstanceId, argv, ".", null);
        }
    }
}
