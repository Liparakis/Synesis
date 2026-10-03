package org.synesis.coordination.domain.identity;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Stable name used to address one capability ownership slot.
 *
 * @param value exact capability name
 */
public record CapabilityId(String value) {

    /**
     * Requires a non-null capability name without normalizing its spelling.
     */
    public CapabilityId {
        CoordinationNullException.requireNonNull(value, "capability ID");
    }
}
