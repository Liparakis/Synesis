package org.synesis.coordination.domain.identity;

import java.util.UUID;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Identity of one coordination request.
 *
 * @param value request UUID
 */
public record RequestId(UUID value) {

    /**
     * Validates and initializes this request id.
     */
    public RequestId {
        CoordinationNullException.requireNonNull(value, "request ID");
    }
}
