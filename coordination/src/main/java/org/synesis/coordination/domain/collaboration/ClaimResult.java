package org.synesis.coordination.domain.collaboration;

import java.util.List;

/**
 * Result of an atomic intent announcement.
 * @param acquired whether all selectors were acquired
 * @param intent requested intent
 * @param conflicts conflicting active claims
 */
public record ClaimResult(boolean acquired, WorkIntent intent, List<ClaimConflict> conflicts) {
    /** Validates and freezes the conflict list. */
    public ClaimResult {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }
}
