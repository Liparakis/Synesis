package org.synesis.workspace.application.integration;

import java.util.Objects;

import org.synesis.coordination.domain.integration.ImplementationRevisionRecord;

/**
 * Helper service for determining implementation snapshot idempotency.
 *
 * <p>Two publications are considered identical when the owner Git commit SHA is the same.
 * Identical publications return the existing revision number without creating a new revision.
 *
 * @since 1.0
 */
public final class ImplementationSnapshotService {

    /**
     * Creates an implementation snapshot service.
     */
    public ImplementationSnapshotService() {
    }

    /**
     * Returns {@code true} if a new publication with the given commit SHA is identical
     * to the most recently published revision.
     *
     * <p>Identical is defined as: the same owner Git commit SHA, meaning the owner's working
     * tree has not changed since the last publication.
     *
     * @param existing     the most recently published implementation revision record
     * @param newCommitSha the Git commit SHA of the candidate new publication
     * @return {@code true} if this publication is identical to the existing revision
     */
    public boolean isIdempotentPublication(ImplementationRevisionRecord existing, String newCommitSha) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(newCommitSha, "newCommitSha");
        return existing.commitSha()
                .equals(newCommitSha);
    }
}
