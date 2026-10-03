package org.synesis.coordination.domain.identity;

import java.util.UUID;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Identity of one project namespace.
 *
 * @param value project UUID
 */
public record ProjectId(UUID value) {

    /**
     * Validates and initializes this project id.
     */
    public ProjectId {
        CoordinationNullException.requireNonNull(value, "project ID");
    }
}
