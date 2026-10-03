package org.synesis.coordination.domain.identity;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Identity of an authenticated coordination node.
 *
 * @param value node identifier
 */
public record NodeId(String value) {

    /**
     * Validates and initializes this node id.
     */
    public NodeId {
        CoordinationNullException.requireNonNull(value, "node ID");
    }
}
