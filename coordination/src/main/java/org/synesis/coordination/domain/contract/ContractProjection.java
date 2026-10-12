package org.synesis.coordination.domain.contract;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Deterministic replay projection of the public contracts, the consumers bound to them, and the
 * change requests against them (ADR-0092).
 *
 * <p>Who may publish is decided by the service that appends, under the project append lock,
 * against the claims of that moment: a projection does not see claims. What can be judged from
 * the contracts alone is refused here as well, on append and on replay: a revision from another
 * lineage than the owner's, a revision number that does not follow, a second active contract
 * over a covered file, and a binding to a revision that is not the current one.</p>
 */
public final class ContractProjection {

    private final Map<UUID, ContractRecord> contracts = new LinkedHashMap<>();
    private final Map<String, ContractBinding> bindings = new LinkedHashMap<>();
    private final Map<UUID, ContractChangeRequest> changeRequests = new LinkedHashMap<>();

    /**
     * Creates an empty replay projection.
     */
    public ContractProjection() {
    }

    private ContractProjection(ContractProjection source) {
        contracts.putAll(source.contracts);
        bindings.putAll(source.bindings);
        changeRequests.putAll(source.changeRequests);
    }

    /**
     * Applies a contract event.
     *
     * @param event signed event
     * @throws IOException if the event violates contract invariants
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        CoordinationNullException.requireNonNull(event, "event");
        switch (event.type()) {
            case CONTRACT_PUBLISHED -> publish(ContractCodec.decodePublished(event.payload()));
            case CONTRACT_DEPENDENCY_BOUND -> bind(ContractCodec.decodeBound(event.payload()));
            case CONTRACT_CHANGE_REQUESTED -> requestChange(ContractCodec.decodeChangeRequested(event.payload()));
            case CONTRACT_CHANGE_ANSWERED -> answerChange(ContractCodec.decodeChangeAnswered(event.payload()));
            case CONTRACT_LANE_ENDED -> endLane(ContractCodec.decodeLaneEnded(event.payload()));
            default -> {
            }
        }
    }

    /**
     * Validates a contract event without mutating this projection.
     *
     * @param event signed event
     * @throws IOException if the event violates contract invariants
     */
    public synchronized void validate(PredictionEvent event) throws IOException {
        new ContractProjection(this).apply(event);
    }

    /**
     * Returns every contract at its current revision, oldest first.
     *
     * @return immutable contract snapshot
     */
    public synchronized List<ContractRecord> contracts() {
        return List.copyOf(contracts.values());
    }

    /**
     * Finds one contract.
     *
     * @param contractId contract identifier
     * @return the contract at its current revision, or empty
     */
    public synchronized Optional<ContractRecord> contract(UUID contractId) {
        return Optional.ofNullable(contracts.get(contractId));
    }

    /**
     * Finds the contract that can still be revised and covers a file. At most one does.
     *
     * @param path file or subtree
     * @return the active contract covering it, or empty
     */
    public synchronized Optional<ContractRecord> activeCovering(ResourceSelector path) {
        return contracts.values().stream().filter(contract -> contract.state() == ContractRecord.State.ACTIVE && contract.covers(path)).findFirst();
    }

    /**
     * Returns the contracts a lineage owns.
     *
     * @param lineageId authority lineage
     * @return its contracts, oldest first
     */
    public synchronized List<ContractRecord> ownedBy(UUID lineageId) {
        return contracts.values().stream().filter(contract -> contract.ownerLineageId().equals(lineageId)).toList();
    }

    /**
     * Finds what a consumer's request record is bound to.
     *
     * @param handle handle of the request record
     * @return its binding, or empty when the record is not a binding
     */
    public synchronized Optional<ContractBinding> binding(String handle) {
        return Optional.ofNullable(bindings.get(handle));
    }

    /**
     * Returns the bindings to one contract, at whatever revision each was last made.
     *
     * @param contractId contract identifier
     * @return its bindings, oldest first
     */
    public synchronized List<ContractBinding> bindings(UUID contractId) {
        return bindings.values().stream().filter(binding -> binding.contractId().equals(contractId)).toList();
    }

    /**
     * Returns the change requests a lineage has not answered.
     *
     * @param ownerLineageId authority lineage of the lane that claims the files
     * @return its open change requests, oldest first
     */
    public synchronized List<ContractChangeRequest> openChangeRequestsFor(UUID ownerLineageId) {
        return changeRequests.values().stream().filter(request -> request.state() == ContractChangeRequest.State.OPEN && request.ownerLineageId().equals(ownerLineageId)).toList();
    }

    /**
     * Returns the change requests a lineage filed, in every state.
     *
     * @param filerLineageId authority lineage of the consumer's lane
     * @return its change requests, oldest first
     */
    public synchronized List<ContractChangeRequest> changeRequestsFiledBy(UUID filerLineageId) {
        return changeRequests.values().stream().filter(request -> request.filerLineageId().equals(filerLineageId)).toList();
    }

    /**
     * Finds one change request.
     *
     * @param id change request identifier
     * @return the request, or empty
     */
    public synchronized Optional<ContractChangeRequest> changeRequest(UUID id) {
        return Optional.ofNullable(changeRequests.get(id));
    }

    private void publish(ContractRecord published) throws IOException {
        ContractRecord prior = contracts.get(published.contractId());
        if (prior == null ? published.revision() != 1 : published.revision() != prior.revision() + 1) {
            throw new CoordinationException("CONTRACT_REVISION_STALE");
        }
        if (prior != null && !prior.ownerLineageId().equals(published.ownerLineageId())) {
            throw new CoordinationException("CONTRACT_OWNED_BY_ANOTHER_LANE");
        }
        if (prior != null && prior.state() == ContractRecord.State.FROZEN) {
            throw new CoordinationException("CONTRACT_FROZEN");
        }
        boolean covered = contracts.values().stream()
                .anyMatch(other -> !other.contractId().equals(published.contractId()) && other.state() == ContractRecord.State.ACTIVE && published.paths().stream().anyMatch(other::covers));
        if (covered) {
            throw new CoordinationException("CONTRACT_PATH_ALREADY_COVERED");
        }
        contracts.put(published.contractId(), published);
    }

    private void bind(ContractBinding binding) throws IOException {
        ContractRecord contract = contracts.get(binding.contractId());
        if (contract == null || contract.state() != ContractRecord.State.ACTIVE || contract.revision() != binding.revision()) {
            throw new CoordinationException("CONTRACT_REVISION_STALE");
        }
        bindings.put(binding.handle(), binding);
    }

    private void requestChange(ContractChangeRequest request) throws IOException {
        if (changeRequests.containsKey(request.id())) {
            throw new CoordinationException("CONTRACT_CHANGE_REQUEST_EXISTS");
        }
        changeRequests.put(request.id(), request);
    }

    private void answerChange(ContractCodec.ChangeAnswer answer) throws IOException {
        ContractChangeRequest request = changeRequests.get(answer.id());
        if (request == null || request.state() != ContractChangeRequest.State.OPEN) {
            throw new CoordinationException("CONTRACT_CHANGE_REQUEST_NOT_OPEN");
        }
        switch (answer.answer()) {
            case ACCEPTED -> {
                // Accepted means a revision covers it, and that revision has to exist.
                boolean covers = contracts.values().stream()
                        .anyMatch(contract -> contract.ownerLineageId().equals(request.ownerLineageId()) && contract.covers(request.path()) && contract.revision() == answer.revision());
                if (!covers) {
                    throw new CoordinationException("CONTRACT_REVISION_STALE");
                }
            }
            case DECLINED -> {
                if (answer.reason().isBlank()) {
                    throw new CoordinationException("CONTRACT_CHANGE_DECLINED_WITHOUT_REASON");
                }
            }
            default -> throw new CoordinationException("CONTRACT_CHANGE_ANSWER_INVALID");
        }
        changeRequests.put(request.id(), request.answered(answer.answer(), answer.revision(), answer.reason()));
    }

    /**
     * Ends everything a lane held here: its contracts are frozen, the change requests waiting for
     * it are declined, and the change requests it filed are withdrawn. Applying it twice changes
     * nothing, so a release and a cancellation need not know of each other.
     */
    private void endLane(UUID lineageId) {
        contracts.replaceAll((id, contract) -> contract.ownerLineageId().equals(lineageId) ? contract.frozen() : contract);
        changeRequests.replaceAll((id, request) -> {
            if (request.state() != ContractChangeRequest.State.OPEN) {
                return request;
            }
            if (request.ownerLineageId().equals(lineageId)) {
                return request.answered(ContractChangeRequest.State.DECLINED, 0, ContractChangeRequest.OWNER_LANE_ENDED);
            }
            return request.filerLineageId().equals(lineageId) ? request.answered(ContractChangeRequest.State.WITHDRAWN, 0, ContractChangeRequest.FILER_LANE_ENDED) : request;
        });
    }
}
