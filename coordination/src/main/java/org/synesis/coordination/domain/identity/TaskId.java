package org.synesis.coordination.domain.identity;

import java.util.UUID;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Identity of one coordination task.
 *
 * @param value task UUID
 */
public record TaskId(UUID value) {

    /**
     * Validates and initializes this task id.
     */
    public TaskId {
        CoordinationNullException.requireNonNull(value, "task ID");
    }
}
