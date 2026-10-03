package org.synesis.coordination.domain.ownership;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Structured ownership decision returned to a provider adapter.
 *
 * @param result  allow, unknown, or owner-response outcome
 * @param owner   current owner when known
 * @param message bounded human-readable reason
 */
public record OwnershipDecision(OwnershipRegistry.Result result, OwnershipOwner owner, String message) {

    /**
     * Validates decision fields.
     */
    public OwnershipDecision {
        CoordinationNullException.requireNonNull(result, "result");
        CoordinationNullException.requireNonNull(message, "message");
    }
}
