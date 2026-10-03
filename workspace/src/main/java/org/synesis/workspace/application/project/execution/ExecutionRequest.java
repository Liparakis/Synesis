package org.synesis.workspace.application.project.execution;

import org.synesis.workspace.application.project.ProjectCommandSpec;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One execution request. The working directory is resolved relative to {@link #worktreeRoot()}
 * and never relative to the server process.
 *
 * @param argv             direct executable and arguments
 * @param worktreeRoot     authoritative lane or integration worktree
 * @param workingDirectory relative working directory, or {@code null}
 * @param timeoutSeconds   timeout in seconds, or {@code null} for default
 * @param controlRoot      control root whose absolute path is sanitized, or {@code null}
 */
public record ExecutionRequest(List<String> argv, Path worktreeRoot, String workingDirectory, Integer timeoutSeconds, Path controlRoot) {

    /**
     * Creates a request without a separate control-root sanitization path.
     *
     * @param argv             direct executable and arguments
     * @param worktreeRoot     authoritative worktree root
     * @param workingDirectory relative working directory, or {@code null}
     * @param timeoutSeconds   timeout in seconds, or {@code null} for default
     */
    @SuppressWarnings("unused")
    public ExecutionRequest(List<String> argv, Path worktreeRoot, String workingDirectory, Integer timeoutSeconds) {
        this(argv, worktreeRoot, workingDirectory, timeoutSeconds, null);
    }

    /**
     * Validates and normalizes request values.
     */
    public ExecutionRequest {
        Objects.requireNonNull(argv, "argv");
        Objects.requireNonNull(worktreeRoot, "worktreeRoot");
        argv = List.copyOf(argv);
        worktreeRoot = worktreeRoot.toAbsolutePath().normalize();
        if (workingDirectory == null || workingDirectory.isBlank()) {
            workingDirectory = ".";
        }
        if (timeoutSeconds == null) {
            timeoutSeconds = ProjectProcessExecutor.DEFAULT_TIMEOUT_SECONDS;
        }
        if (timeoutSeconds < 1 || timeoutSeconds > ProjectProcessExecutor.MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and " + ProjectProcessExecutor.MAX_TIMEOUT_SECONDS);
        }
    }

    /**
     * Creates a request from a project-owned command specification.
     *
     * @param spec         project-owned command specification
     * @param worktreeRoot authoritative lane or integration root
     * @param controlRoot  control root used for sanitization
     * @return executable request
     */
    public static ExecutionRequest from(ProjectCommandSpec spec, Path worktreeRoot, Path controlRoot) {
        Objects.requireNonNull(spec, "spec");
        return new ExecutionRequest(spec.argv(), worktreeRoot, spec.workingDirectory(), spec.timeoutSeconds(), controlRoot);
    }
}
