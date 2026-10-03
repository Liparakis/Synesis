package org.synesis.coordination.domain.identity;

import java.util.UUID;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Identity of a prediction or task stream in event envelopes.
 *
 * @param value prediction UUID
 */
public record PredictionId(UUID value) {

    /**
     * Validates and initializes this prediction id.
     */
    public PredictionId {
        CoordinationNullException.requireNonNull(value, "prediction ID");
    }
}
