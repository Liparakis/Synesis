package org.synesis.coordination.domain.identity;

import java.util.UUID;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Identity of one idempotently admitted coordination command.
 *
 * @param value command UUID
 */
public record CommandId(UUID value) {

    /**
     * Validates and initializes this command id.
     */
    public CommandId {
        CoordinationNullException.requireNonNull(value, "command ID");
    }
}
