package org.synesis.coordination.domain.identity;

import java.util.UUID;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Identity of one logical work group.
 *
 * @param value work-group UUID
 */
public record WorkGroupId(UUID value) {

    /**
     * Validates and initializes this work group id.
     */
    public WorkGroupId {
        CoordinationNullException.requireNonNull(value, "work group ID");
    }
}
