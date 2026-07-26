package org.synesis.coordination.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record representing a dedicated integration attempt.
 *
 * @param attemptId            unique integration attempt locator (e.g. {@code att_...})
 * @param projectId            project ID
 * @param taskSnapshotIds      ordered list of task snapshot IDs included in this attempt
 * @param expectedControlHead  Git commit SHA of expected control branch HEAD
 * @param integrationCommitSha Git commit SHA produced in the integration worktree
 * @param status               outcome status: {@code "started"}, {@code "conflict"}, {@code "failed"}, {@code "advanced"}
 * @param failureReason        human-readable failure or conflict explanation
 * @param startedAtMillis      timestamp when attempt started
 * @param completedAtMillis    timestamp when attempt completed (0 if in progress)
 * @since 1.0
 */
public record IntegrationAttemptRecord(
        String attemptId,
        UUID projectId,
        List<String> taskSnapshotIds,
        String expectedControlHead,
        String integrationCommitSha,
        String status,
        String failureReason,
        long startedAtMillis,
        long completedAtMillis
) {

    /**
     * Compact constructor enforcing invariants.
     *
     * @param attemptId            attempt identifier
     * @param projectId            project identifier
     * @param taskSnapshotIds      list of included task snapshot IDs
     * @param expectedControlHead  expected control HEAD commit SHA
     * @param integrationCommitSha produced integration commit SHA
     * @param status               attempt status
     * @param failureReason        failure reason text
     * @param startedAtMillis      started timestamp
     * @param completedAtMillis    completed timestamp
     */
    public IntegrationAttemptRecord {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(projectId, "projectId");
        taskSnapshotIds = List.copyOf(Objects.requireNonNull(taskSnapshotIds, "taskSnapshotIds"));
        Objects.requireNonNull(expectedControlHead, "expectedControlHead");
        Objects.requireNonNull(integrationCommitSha, "integrationCommitSha");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(failureReason, "failureReason");
    }
}
