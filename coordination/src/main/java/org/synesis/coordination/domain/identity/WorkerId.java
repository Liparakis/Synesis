package org.synesis.coordination.domain.identity;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Worker identity within a supervisor.
 *
 * @param value worker identifier
 */
public record WorkerId(String value) {

    /**
     * Validates and initializes this worker id.
     */
    public WorkerId {
        CoordinationNullException.requireNonNull(value, "worker ID");
    }
}
