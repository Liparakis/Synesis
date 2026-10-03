package org.synesis.coordination.domain.identity;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Opaque identity of an immutable snapshot.
 *
 * @param value snapshot identifier
 */
public record SnapshotId(String value) {

    /**
     * Validates and initializes this snapshot id.
     */
    public SnapshotId {
        CoordinationNullException.requireNonNull(value, "snapshot ID");
    }
}
