package org.synesis.coordination.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record representing a verified task snapshot created from a worker worktree.
 *
 * @param taskId                 task identifier
 * @param snapshotId             unique snapshot locator string (e.g. {@code snap_...})
 * @param nodeId                 worker node ID
 * @param supervisorId           worker supervisor ID
 * @param workerId               worker ID
 * @param providerSessionId      provider session ID
 * @param baseCommit             Git base commit SHA
 * @param commitSha              Git commit SHA produced by the worker worktree
 * @param changedPaths           bounded list of changed paths relative to project root
 * @param capabilityDependencies list of validated capability request handles this task depends on
 * @param summary                human-readable task completion summary
 * @param createdAtMillis        creation timestamp
 * @since 1.0
 */
public record TaskSnapshotRecord(
        UUID taskId,
        String snapshotId,
        String nodeId,
        String supervisorId,
        String workerId,
        String providerSessionId,
        String baseCommit,
        String commitSha,
        List<String> changedPaths,
        List<String> capabilityDependencies,
        String summary,
        long createdAtMillis
) {

    /** Maximum number of changed paths per snapshot. */
    public static final int MAX_CHANGED_PATHS = 128;

    /** Maximum length of the summary text in characters. */
    public static final int MAX_SUMMARY_LENGTH = 500;

    /**
     * Compact constructor enforcing invariants.
     *
     * @param taskId                 task identifier
     * @param snapshotId             unique snapshot locator string
     * @param nodeId                 worker node ID
     * @param supervisorId           worker supervisor ID
     * @param workerId               worker ID
     * @param providerSessionId      provider session ID
     * @param baseCommit             Git base commit SHA
     * @param commitSha              Git commit SHA
     * @param changedPaths           bounded list of changed paths
     * @param capabilityDependencies list of capability dependencies
     * @param summary                human-readable summary
     * @param createdAtMillis        creation timestamp
     */
    public TaskSnapshotRecord {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(supervisorId, "supervisorId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(providerSessionId, "providerSessionId");
        Objects.requireNonNull(baseCommit, "baseCommit");
        Objects.requireNonNull(commitSha, "commitSha");
        changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
        capabilityDependencies = List.copyOf(Objects.requireNonNull(capabilityDependencies, "capabilityDependencies"));
        Objects.requireNonNull(summary, "summary");
        if (changedPaths.size() > MAX_CHANGED_PATHS) {
            throw new IllegalArgumentException("too many changed paths (max " + MAX_CHANGED_PATHS + ")");
        }
        if (summary.isBlank() || summary.length() > MAX_SUMMARY_LENGTH) {
            throw new IllegalArgumentException("summary must be 1-" + MAX_SUMMARY_LENGTH + " characters");
        }
    }
}
