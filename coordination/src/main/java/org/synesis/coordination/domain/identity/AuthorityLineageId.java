package org.synesis.coordination.domain.identity;

import java.util.UUID;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * UUID preserving authority across lawful successor intents.
 *
 * @param value authority lineage UUID
 */
public record AuthorityLineageId(UUID value) {

    /**
     * Validates and initializes this authority lineage id.
     */
    public AuthorityLineageId {
        CoordinationNullException.requireNonNull(value, "authority lineage ID");
    }
}
