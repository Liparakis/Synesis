package org.synesis.coordination.domain.identity;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Opaque identity of one participant incarnation.
 *
 * @param value participant handle
 */
public record ParticipantId(String value) {

    /**
     * Validates and initializes this participant id.
     */
    public ParticipantId {
        CoordinationNullException.requireNonNull(value, "participant ID");
    }
}
