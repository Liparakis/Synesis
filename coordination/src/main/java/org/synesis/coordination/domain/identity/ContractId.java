package org.synesis.coordination.domain.identity;

import java.util.UUID;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Identity of one durable contract.
 *
 * @param value contract UUID
 */
public record ContractId(UUID value) {

    /**
     * Validates and initializes this contract id.
     */
    public ContractId {
        CoordinationNullException.requireNonNull(value, "contract ID");
    }
}
