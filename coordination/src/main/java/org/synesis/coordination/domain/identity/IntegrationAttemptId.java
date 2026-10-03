package org.synesis.coordination.domain.identity;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Opaque identity of one durable integration attempt.
 *
 * @param value integration attempt locator
 */
public record IntegrationAttemptId(String value) {

    /**
     * Requires a non-null integration attempt locator.
     *
     * @param value non-null integration attempt locator
     */
    public IntegrationAttemptId {
        CoordinationNullException.requireNonNull(value, "integration attempt ID");
    }
}
