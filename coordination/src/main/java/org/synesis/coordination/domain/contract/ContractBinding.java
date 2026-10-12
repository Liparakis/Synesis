package org.synesis.coordination.domain.contract;

import java.util.UUID;

import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Which revision of which contract a consumer's request record stands for (ADR-0092).
 *
 * <p>The request record carries the consumer's side: who it is, the implementation revisions it
 * was given and what it validated. This is the link from that record to the one shared text, so a
 * revision of the text can find every consumer it must be accepted by again.</p>
 *
 * @param handle     handle of the consumer's request record
 * @param contractId contract the consumer bound to
 * @param revision   revision it accepted
 */
public record ContractBinding(String handle, UUID contractId, long revision) {

    /**
     * Validates the binding.
     */
    public ContractBinding {
        CoordinationNullException.requireNonNull(contractId, "contract ID");
        if (handle == null || handle.isBlank()) {
            throw new CoordinationArgumentException("a binding names its request record");
        }
        if (revision < 1) {
            throw new CoordinationArgumentException("contract revision starts at one");
        }
    }
}
