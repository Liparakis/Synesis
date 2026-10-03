package org.synesis.coordination.domain.identity;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Opaque reference to a recovery snapshot.
 *
 * @param value snapshot reference
 */
public record SnapshotReference(String value) {

    /**
     * Validates and initializes this snapshot reference.
     */
    public SnapshotReference {
        CoordinationNullException.requireNonNull(value, "snapshot reference");
    }
}
