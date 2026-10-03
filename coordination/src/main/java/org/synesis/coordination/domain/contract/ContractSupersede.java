package org.synesis.coordination.domain.contract;

import java.util.UUID;
import org.synesis.coordination.domain.identity.ContractId;
import org.synesis.coordination.domain.identity.ContractRevision;

/**
 * Decoded contract revision supersession.
 *
 * @param contract            contract being superseded
 * @param previousRevision    currently published revision
 * @param replacementRevision revision replacing the current one
 */
public record ContractSupersede(ContractId contract, ContractRevision previousRevision,
                                ContractRevision replacementRevision) {

    /**
     * Creates a typed supersession from its existing wire values.
     *
     * @param contractId  contract being superseded
     * @param oldRevision currently published revision
     * @param newRevision replacement revision
     */
    public ContractSupersede(UUID contractId, long oldRevision, long newRevision) {
        this(new ContractId(contractId), new ContractRevision(oldRevision), new ContractRevision(newRevision));
    }

    /**
     * Compatibility accessor for the existing codec.
     *
     * @return previous revision number
     */
    public long oldRevision() {
        return previousRevision.value();
    }

}
