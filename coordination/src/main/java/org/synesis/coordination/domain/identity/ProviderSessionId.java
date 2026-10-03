package org.synesis.coordination.domain.identity;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Identity of one exact provider session.
 *
 * @param value provider-session identifier
 */
public record ProviderSessionId(String value) {

    /**
     * Validates and initializes this provider session id.
     */
    public ProviderSessionId {
        CoordinationNullException.requireNonNull(value, "provider session ID");
    }
}
