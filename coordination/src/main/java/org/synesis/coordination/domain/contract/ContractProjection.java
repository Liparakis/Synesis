package org.synesis.coordination.domain.contract;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.synesis.coordination.domain.identity.ContractId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Deterministic replay projection for contracts and exact consumer revisions.
 */
public final class ContractProjection {

    private final Map<ContractId, ContractRecord> contracts = new LinkedHashMap<>();
    private final Map<WorkIntentId, ContractDependency> dependencies = new LinkedHashMap<>();

    /**
     * Creates an empty replay projection.
     */
    public ContractProjection() {
    }

    /**
     * Applies a contract event.
     *
     * @param event signed event
     * @throws IOException if the event violates contract invariants
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        switch (event.type()) {
            case CONTRACT_PUBLISHED -> publish(ContractCodec.decodePublish(event.payload()));
            case CONTRACT_DEPENDENCY_BOUND -> bind(ContractCodec.decodeDependency(event.payload()));
            case CONTRACT_SUPERSEDED -> supersede(ContractCodec.decodeSupersede(event.payload()));
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
        ContractProjection copy = new ContractProjection();
        copy.contracts.putAll(contracts);
        copy.dependencies.putAll(dependencies);
        copy.apply(event);
    }

    /**
     * Returns all current contracts.
     *
     * @return immutable contract snapshot
     */
    public synchronized List<ContractRecord> contracts() {
        return List.copyOf(contracts.values());
    }

    /**
     * Returns all explicit consumer bindings.
     *
     * @return immutable dependency snapshot
     */
    public synchronized List<ContractDependency> dependencies() {
        return List.copyOf(dependencies.values());
    }

    /**
     * Finds one contract.
     *
     * @param id contract identifier
     * @return contract or {@code null}
     */
    public synchronized ContractRecord contract(UUID id) {
        return id == null ? null : contract(new ContractId(id));
    }

    /**
     * Returns the current contract by typed identity.
     *
     * @param id contract identity
     * @return current record, or {@code null} when absent
     */
    public synchronized ContractRecord contract(ContractId id) {
        return contracts.get(CoordinationNullException.requireNonNull(id, "contract ID"));
    }

    /**
     * Adds a contract only when its identifier is new and its initial revision is valid.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void publish(ContractRecord record) throws IOException {
        ContractRecord prior = contracts.get(record.identity());
        if (prior != null && record.revision() != prior.revision() + 1) {
            throw new CoordinationException("CONTRACT_REVISION_STALE");
        }
        if (prior == null && record.revision() != 1) {
            throw new CoordinationException("CONTRACT_REVISION_STALE");
        }
        contracts.put(record.identity(), record);
    }

    /**
     * Binds an intent to the exact published contract revision after validating ownership.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void bind(ContractDependency dependency) throws IOException {
        ContractRecord contract = contracts.get(dependency.contractIdentity());
        if (contract == null || contract.revision() != dependency.revision()
                || contract.status() != ContractRecord.Status.ACTIVE) {
            throw new CoordinationException("CONTRACT_REVISION_STALE");
        }
        dependencies.put(dependency.intentIdentity(), dependency);
    }

    /**
     * Replaces a contract revision only when the supplied predecessor is current.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void supersede(ContractSupersede supersede) throws IOException {
        ContractRecord current = contracts.get(supersede.contract());
        if (current == null || current.revision() != supersede.oldRevision()) {
            throw new CoordinationException("CONTRACT_REVISION_STALE");
        }
        contracts.put(current.identity(),
                new ContractRecord(current.contractId(),
                        current.projectId(),
                        current.revision(),
                        current.owner(),
                        current.contentHash(),
                        current.body(),
                        ContractRecord.Status.SUPERSEDED,
                        current.supersedes(),
                        current.selectorRefs()));
        dependencies.replaceAll((ignoredId, dependency) ->
                dependency.contractIdentity().equals(current.identity()) && dependency.contractRevision()
                        .equals(current.contractRevision()) ? new ContractDependency(dependency.intentId(),
                        dependency.participant(),
                        dependency.contractId(),
                        dependency.revision(),
                        ContractDependency.State.REPLAN_REQUIRED) : dependency);
    }
}
