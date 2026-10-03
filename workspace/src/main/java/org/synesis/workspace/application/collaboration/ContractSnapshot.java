package org.synesis.workspace.application.collaboration;

import java.util.List;
import org.synesis.coordination.domain.contract.ContractDependency;
import org.synesis.coordination.domain.contract.ContractRecord;

/**
 * Contract revisions and consumer bindings exposed to CLI and MCP adapters.
 *
 * @param contracts    contract revisions
 * @param dependencies consumer bindings
 */
public record ContractSnapshot(List<ContractRecord> contracts, List<ContractDependency> dependencies) {

}
