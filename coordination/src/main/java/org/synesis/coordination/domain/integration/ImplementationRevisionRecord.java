package org.synesis.coordination.domain.integration;

import org.synesis.coordination.domain.capability.CapabilityRequestHandle;


import org.synesis.coordination.domain.capability.CapabilityRequestHandle;



import java.util.List;
import java.util.Objects;

/**
 * Immutable record representing a published implementation revision for a capability request.
 *
 * <p>Each revision is content-addressed by the owner Git commit SHA and is permanently
 * retained even after further revisions are published. Older revisions remain immutable
 * and traceable.
 *
 * @param handle            request handle
 * @param revisionNumber    monotonically increasing revision counter (1-based)
 * @param baseCommit        Git base commit SHA in the owner worktree at time of publication
 * @param commitSha         Git commit SHA produced in the owner worktree (snapshot reference)
 * @param changedPaths      bounded list of changed paths relative to project root
 * @param summary           human-readable implementation summary (1-500 chars)
 * @param publishedAtMillis publication timestamp
 * @since 1.0
 */
public record ImplementationRevisionRecord(
        CapabilityRequestHandle handle,
        int revisionNumber,
        String baseCommit,
        String commitSha,
        List<String> changedPaths,
        String summary,
        long publishedAtMillis
) {

    /** Maximum number of changed paths per revision. */
    public static final int MAX_CHANGED_PATHS = 64;

    /** Maximum length of the summary text in characters. */
    public static final int MAX_SUMMARY_LENGTH = 500;

    /**
     * Compact constructor enforcing invariants.
     *
     * @param handle            request handle
     * @param revisionNumber    monotonically increasing revision counter (1-based)
     * @param baseCommit        Git base commit SHA
     * @param commitSha         Git commit SHA for the implementation snapshot
     * @param changedPaths      bounded list of changed paths relative to project root
     * @param summary           human-readable implementation summary
     * @param publishedAtMillis publication timestamp
     */
    public ImplementationRevisionRecord {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(baseCommit, "baseCommit");
        Objects.requireNonNull(commitSha, "commitSha");
        changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
        Objects.requireNonNull(summary, "summary");
        if (revisionNumber < 1) {
            throw new IllegalArgumentException("revisionNumber must be >= 1");
        }
        if (changedPaths.size() > MAX_CHANGED_PATHS) {
            throw new IllegalArgumentException("too many changed paths (max " + MAX_CHANGED_PATHS + ")");
        }
        if (summary.isBlank() || summary.length() > MAX_SUMMARY_LENGTH) {
            throw new IllegalArgumentException("summary must be 1-" + MAX_SUMMARY_LENGTH + " characters");
        }
    }
}
