package org.synesis.coordination.domain.integration;

import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Records the active disposable validation worktree context for a capability request.
 *
 * <p>Created when a requester begins validation of an implementation revision.
 * Removed when validation completes (accepted or revision_required).
 *
 * @param handle          request handle
 * @param revisionNumber  implementation revision under validation
 * @param worktreePath    absolute path to the disposable validation worktree (not exposed to agent)
 * @param requesterSessionId exact requester session that started validation
 * @param startedAtMillis timestamp when validation context was created
 * @since 1.0
 */
public record ValidationContextRecord(CapabilityRequestHandle handle, int revisionNumber, String worktreePath,
                                      String requesterSessionId, long startedAtMillis) {

    /**
     * Compact constructor enforcing non-null invariants.
     *
     * @param handle          request handle
     * @param revisionNumber  implementation revision under validation (1-based)
     * @param worktreePath    absolute path to the disposable validation worktree
     * @param requesterSessionId exact requester session that started validation
     * @param startedAtMillis timestamp when validation context was created
     */
    public ValidationContextRecord {
        CoordinationNullException.requireNonNull(handle, "handle");
        CoordinationNullException.requireNonNull(worktreePath, "worktreePath");
        CoordinationNullException.requireNonNull(requesterSessionId, "requesterSessionId");
        if (revisionNumber < 1) {
            throw new CoordinationArgumentException("revisionNumber must be >= 1");
        }
        if (worktreePath.isBlank()) {
            throw new CoordinationArgumentException("worktreePath must not be blank");
        }
    }

    /**
     * Retains the prior projection constructor for historical contexts without a session fence.
     *
     * @param handle capability request handle
     * @param revisionNumber implementation revision under validation
     * @param worktreePath validation worktree path
     * @param startedAtMillis validation start time
     */
    public ValidationContextRecord(CapabilityRequestHandle handle, int revisionNumber, String worktreePath, long startedAtMillis) {
        this(handle, revisionNumber, worktreePath, "", startedAtMillis);
    }

}
