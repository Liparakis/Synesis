package org.synesis.coordination.domain.identity;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Stable provider identity bound to a participant.
 *
 * @param value provider identifier
 */
public record ProviderId(String value) {

    /**
     * Validates and initializes this provider id.
     */
    public ProviderId {
        CoordinationNullException.requireNonNull(value, "provider ID");
    }
}
