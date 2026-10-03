package org.synesis.coordination.domain.identity;

import java.util.UUID;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Identity of one authorization grant for a work-group lane.
 *
 * @param value lane grant UUID
 */
public record LaneGrantId(UUID value) {

    /**
     * Validates and initializes this lane grant id.
     */
    public LaneGrantId {
        CoordinationNullException.requireNonNull(value, "lane grant ID");
    }
}
