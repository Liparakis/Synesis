package org.synesis.coordination;

import java.util.Objects;

/**
 * Records the active disposable validation worktree context for a capability request.
 *
 * <p>Created when a requester begins validation of an implementation revision.
 * Removed when validation completes (accepted or revision_required).
 *
 * @param handle          request handle
 * @param revisionNumber  implementation revision under validation
 * @param worktreePath    absolute path to the disposable validation worktree (not exposed to agent)
 * @param startedAtMillis timestamp when validation context was created
 * @since 1.0
 */
public record ValidationContextRecord(
        CapabilityRequestHandle handle,
        int revisionNumber,
        String worktreePath,
        long startedAtMillis
) {

    /**
     * Compact constructor enforcing non-null invariants.
     *
     * @param handle          request handle
     * @param revisionNumber  implementation revision under validation (1-based)
     * @param worktreePath    absolute path to the disposable validation worktree
     * @param startedAtMillis timestamp when validation context was created
     */
    public ValidationContextRecord {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(worktreePath, "worktreePath");
        if (revisionNumber < 1) {
            throw new IllegalArgumentException("revisionNumber must be >= 1");
        }
        if (worktreePath.isBlank()) {
            throw new IllegalArgumentException("worktreePath must not be blank");
        }
    }
}
