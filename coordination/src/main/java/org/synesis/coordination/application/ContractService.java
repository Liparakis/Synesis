package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.contract.ContractCodec;
import org.synesis.coordination.domain.contract.ContractDependency;
import org.synesis.coordination.domain.contract.ContractRecord;
import org.synesis.coordination.domain.identity.ContractId;
import org.synesis.coordination.domain.identity.ContractRevision;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.CoordinationStateException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.link.identity.NodeIdentity;

/**
 * Shared application service for exact contract revisions and dependencies.
 */
public final class ContractService {

    private final PredictionEventStore store;
    private final NodeIdentity signer;

    /**
     * Creates the service.
     *
     * @param store  project event store
     * @param signer signing identity
     */
    public ContractService(PredictionEventStore store, NodeIdentity signer) {
        this.store = CoordinationNullException.requireNonNull(store, "store");
        this.signer = CoordinationNullException.requireNonNull(signer, "signer");
    }

    /**
     * Publishes the first or next contract revision.
     *
     * @param contractId contract identifier
     * @param owner      opaque owner handle
     * @param body       contract body
     * @param selectors  declared selector references
     * @return published record
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public ContractRecord publish(UUID contractId, String owner, String body, List<String> selectors)
            throws IOException, GeneralSecurityException {
        return publish(new ContractId(contractId), new ParticipantId(owner), body, selectors);
    }

    /**
     * Publishes a contract using identities that cannot be confused with task or participant IDs.
     *
     * @param contractId contract being created or revised
     * @param owner      participant that owns the contract
     * @param body       bounded contract body
     * @param selectors  declared selector references
     * @return published contract record
     * @throws IOException              persistence or contract validation failure
     * @throws GeneralSecurityException signing failure
     */
    public ContractRecord publish(ContractId contractId, ParticipantId owner, String body, List<String> selectors)
            throws IOException, GeneralSecurityException {
        UUID rawContractId = CoordinationNullException.requireNonNull(contractId, "contract ID").value();
        String ownerHandle = CoordinationNullException.requireNonNull(owner, "owner").value();
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            PredictionEventStore current = fresh();
            ContractRecord prior = current.contractProjection().contract(contractId);
            ContractRecord record = prior == null ? ContractRecord.create(rawContractId,
                    current.projectId(),
                    ownerHandle,
                    body,
                    selectors) : new ContractRecord(rawContractId,
                    current.projectId(),
                    prior.revision() + 1,
                    ownerHandle,
                    ContractRecord.hash(body),
                    body,
                    ContractRecord.Status.ACTIVE,
                    prior.contractId(),
                    selectors);
            if (prior != null) {
                current.append(rawContractId,
                        PredictionEventType.CONTRACT_SUPERSEDED,
                        signer.nodeId(),
                        ContractCodec.encodeSupersede(rawContractId, prior.revision(), record.revision()),
                        signer);
            }
            current.append(rawContractId,
                    PredictionEventType.CONTRACT_PUBLISHED,
                    signer.nodeId(),
                    ContractCodec.encodePublish(record),
                    signer);
            return record;
        }
    }

    /**
     * Binds an intent to the exact active contract revision.
     *
     * @param intentId    intent identifier
     * @param participant consumer handle
     * @param contractId  contract identifier
     * @param revision    exact revision
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void bind(UUID intentId, String participant, UUID contractId, long revision)
            throws IOException, GeneralSecurityException {
        bind(new WorkIntentId(intentId),
                new ParticipantId(participant),
                new ContractId(contractId),
                new ContractRevision(revision));
    }

    /**
     * Binds a consumer to the exact active contract revision using typed identities.
     *
     * @param intentId    consumer intent
     * @param participant consuming participant incarnation
     * @param contractId  exact contract
     * @param revision    exact active contract revision
     * @throws IOException              persistence or contract validation failure
     * @throws GeneralSecurityException signing failure
     */
    public void bind(WorkIntentId intentId, ParticipantId participant, ContractId contractId, ContractRevision revision)
            throws IOException, GeneralSecurityException {
        UUID rawIntentId = CoordinationNullException.requireNonNull(intentId, "intent ID").value();
        String participantHandle = CoordinationNullException.requireNonNull(participant, "participant").value();
        UUID rawContractId = CoordinationNullException.requireNonNull(contractId, "contract ID").value();
        long revisionNumber = CoordinationNullException.requireNonNull(revision, "revision").value();
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            PredictionEventStore current = fresh();
            ContractDependency dependency = new ContractDependency(rawIntentId,
                    participantHandle,
                    rawContractId,
                    revisionNumber,
                    ContractDependency.State.ACCEPTED);
            current.append(rawIntentId,
                    PredictionEventType.CONTRACT_DEPENDENCY_BOUND,
                    signer.nodeId(),
                    ContractCodec.encodeDependency(dependency),
                    signer);
        }
    }

    /**
     * Returns contracts.
     *
     * @return immutable contract snapshot
     */
    public List<ContractRecord> contracts() {
        try {
            return fresh().contractProjection().contracts();
        } catch (Exception e) {
            throw new CoordinationStateException("CONTRACT_STATE_UNAVAILABLE", e);
        }
    }

    /**
     * Returns dependencies.
     *
     * @return immutable dependency snapshot
     */
    public List<ContractDependency> dependencies() {
        try {
            return fresh().contractProjection().dependencies();
        } catch (Exception e) {
            throw new CoordinationStateException("CONTRACT_STATE_UNAVAILABLE", e);
        }
    }

    /**
     * Replays a fresh view of the project's durable coordination state.
     *
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private PredictionEventStore fresh() throws IOException, GeneralSecurityException {
        return new PredictionEventStore(store.rootDirectory(), store.projectId());
    }
}
