package org.synesis.coordination.domain.task;


import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.domain.identity.WorkerId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

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
 * @param provenance             immutable lane provenance
 * @param reviewRequired         whether exact durable review acceptance gates integration
 * @since 1.0
 */
public record TaskSnapshotRecord(UUID taskId, String snapshotId, String nodeId, String supervisorId, String workerId,
                                 String providerSessionId, String baseCommit, String commitSha,
                                 List<String> changedPaths, List<String> capabilityDependencies, String summary,
                                 long createdAtMillis, SnapshotProvenance provenance, boolean reviewRequired) {

    /**
     * Maximum number of changed paths per snapshot.
     */
    public static final int MAX_CHANGED_PATHS = 128;
    /**
     * Maximum length of the summary text in characters.
     */
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
     * @param provenance             immutable lane provenance
     * @param reviewRequired         whether review acceptance gates integration
     */
    public TaskSnapshotRecord {
        CoordinationNullException.requireNonNull(taskId, "taskId");
        CoordinationNullException.requireNonNull(snapshotId, "snapshotId");
        CoordinationNullException.requireNonNull(nodeId, "nodeId");
        CoordinationNullException.requireNonNull(supervisorId, "supervisorId");
        CoordinationNullException.requireNonNull(workerId, "workerId");
        CoordinationNullException.requireNonNull(providerSessionId, "providerSessionId");
        CoordinationNullException.requireNonNull(baseCommit, "baseCommit");
        CoordinationNullException.requireNonNull(commitSha, "commitSha");
        changedPaths = List.copyOf(CoordinationNullException.requireNonNull(changedPaths, "changedPaths"));
        capabilityDependencies = List.copyOf(CoordinationNullException.requireNonNull(capabilityDependencies,
                "capabilityDependencies"));
        CoordinationNullException.requireNonNull(summary, "summary");
        CoordinationNullException.requireNonNull(provenance, "provenance");
        if (changedPaths.size() > MAX_CHANGED_PATHS) {
            throw new CoordinationArgumentException("too many changed paths (max " + MAX_CHANGED_PATHS + ")");
        }
        if (summary.isBlank() || summary.length() > MAX_SUMMARY_LENGTH) {
            throw new CoordinationArgumentException("summary must be 1-" + MAX_SUMMARY_LENGTH + " characters");
        }
    }

    /**
     * Returns the typed task identity owning this snapshot.
     *
     * @return the typed task identity owning this snapshot.
     */
    public TaskId taskIdentity() {
        return new TaskId(taskId);
    }

    /**
     * Returns the typed immutable snapshot identity.
     *
     * @return the typed immutable snapshot identity.
     */
    public SnapshotId snapshotIdentity() {
        return new SnapshotId(snapshotId);
    }

    /**
     * Returns the snapshot's authenticated worker node identity.
     *
     * @return the snapshot's authenticated worker node identity.
     */
    public NodeId nodeIdentity() {
        return new NodeId(nodeId);
    }

    /**
     * Returns the snapshot's authenticated worker identity.
     *
     * @return the snapshot's authenticated worker identity.
     */
    public WorkerId workerIdentity() {
        return new WorkerId(workerId);
    }

}
