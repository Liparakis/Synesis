package org.synesis.coordination.domain.identity;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Supervisor identity within a node.
 *
 * @param value supervisor identifier
 */
public record SupervisorId(String value) {

    /**
     * Validates and initializes this supervisor id.
     */
    public SupervisorId {
        CoordinationNullException.requireNonNull(value, "supervisor ID");
    }
}
