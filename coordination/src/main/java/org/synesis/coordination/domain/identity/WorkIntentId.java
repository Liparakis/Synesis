package org.synesis.coordination.domain.identity;

import java.util.UUID;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Identity of one work-intent lane.
 *
 * @param value work-intent UUID
 */
public record WorkIntentId(UUID value) {

    /**
     * Validates and initializes this work intent id.
     */
    public WorkIntentId {
        CoordinationNullException.requireNonNull(value, "work intent ID");
    }
}
