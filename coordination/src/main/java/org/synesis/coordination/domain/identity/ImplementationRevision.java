package org.synesis.coordination.domain.identity;

import org.synesis.coordination.exception.CoordinationArgumentException;

/**
 * Revision number of one capability implementation.
 *
 * @param value one-based implementation revision
 */
public record ImplementationRevision(int value) {

    /**
     * Requires a positive revision number.
     */
    public ImplementationRevision {
        if (value < 1) {
            throw new CoordinationArgumentException("implementation revision must be positive");
        }
    }
}
