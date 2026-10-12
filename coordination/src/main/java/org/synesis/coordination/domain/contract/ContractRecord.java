package org.synesis.coordination.domain.contract;

import java.util.List;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * The one public contract over files a lane claims (ADR-0092).
 *
 * <p>Every agent may read it. Only the authority lineage that published it publishes a revision,
 * so a file has one text that says what it provides, and two consumers cannot be promised
 * opposite things about it.</p>
 *
 * @param contractId     contract identifier, stable across revisions
 * @param ownerLineageId authority lineage of the lane that published it
 * @param revision       revision number, starting at one
 * @param paths          the claimed files or subtrees the contract covers
 * @param terms          what the owner promises about them
 * @param state          whether the contract can still be revised
 */
public record ContractRecord(UUID contractId, UUID ownerLineageId, long revision, List<ResourceSelector> paths, CapabilityContract terms, State state) {

    /** Most files or subtrees one contract may cover. */
    public static final int MAX_PATHS = 64;

    /**
     * Validates the contract.
     */
    public ContractRecord {
        CoordinationNullException.requireNonNull(contractId, "contract ID");
        CoordinationNullException.requireNonNull(ownerLineageId, "owner lineage");
        CoordinationNullException.requireNonNull(terms, "terms");
        CoordinationNullException.requireNonNull(state, "state");
        CoordinationNullException.requireNonNull(paths, "paths");
        if (revision < 1) {
            throw new CoordinationArgumentException("contract revision starts at one");
        }
        if (paths.isEmpty() || paths.size() > MAX_PATHS) {
            throw new CoordinationArgumentException("a contract covers 1 to " + MAX_PATHS + " paths");
        }
        paths = List.copyOf(paths);
    }

    /**
     * Tells whether this contract covers a file.
     *
     * @param path file or subtree
     * @return true when one of the contract's paths overlaps it
     */
    public boolean covers(ResourceSelector path) {
        return paths.stream().anyMatch(path::overlaps);
    }

    /** Ends an active contract with its owner's lane; a contract that has ended stays as it ended. */
    ContractRecord ended(boolean integrated) {
        return state != State.ACTIVE ? this : new ContractRecord(contractId, ownerLineageId, revision, paths, terms, integrated ? State.FROZEN : State.WITHDRAWN);
    }

    /** Whether a contract can still be revised. */
    public enum State {
        /** Its owner's lane is open. */
        ACTIVE,
        /** Its owner's lane finished and its work is integrated. The text is what the files were built to and is never edited again. */
        FROZEN,
        /** Its owner's lane ended with nothing integrated. The text stays readable, and nothing was built to it. */
        WITHDRAWN
    }
}
