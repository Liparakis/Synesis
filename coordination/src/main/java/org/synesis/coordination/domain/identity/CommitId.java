package org.synesis.coordination.domain.identity;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Commit identity used as a workspace base.
 *
 * @param value commit identifier
 */
public record CommitId(String value) {

    /**
     * Validates and initializes this commit id.
     */
    public CommitId {
        CoordinationNullException.requireNonNull(value, "commit ID");
    }
}
