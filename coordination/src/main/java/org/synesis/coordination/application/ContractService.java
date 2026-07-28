package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.synesis.coordination.domain.contract.ContractCodec;
import org.synesis.coordination.domain.contract.ContractDependency;
import org.synesis.coordination.domain.contract.ContractRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.NodeIdentity;

/** Shared application service for exact contract revisions and dependencies. */
public final class ContractService {
    private final PredictionEventStore store;
    private final NodeIdentity signer;
    /** Creates the service.
     * @param store project event store
     * @param signer signing identity
     */
    public ContractService(PredictionEventStore store, NodeIdentity signer) { this.store=Objects.requireNonNull(store,"store"); this.signer=Objects.requireNonNull(signer,"signer"); }

    /** Publishes the first or next contract revision.
     * @param contractId contract identifier
     * @param owner opaque owner handle
     * @param body contract body
     * @param selectors declared selector references
     * @return published record
     * @throws IOException if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public ContractRecord publish(UUID contractId, String owner, String body, List<String> selectors) throws IOException, GeneralSecurityException {
        try(ProjectAppendLock lock=ProjectAppendLock.acquire(store.rootDirectory())) { if(!lock.isHeld()) throw new IOException("event append lock unavailable"); PredictionEventStore current=fresh(); ContractRecord prior=current.contractProjection().contract(contractId); ContractRecord record=prior==null ? ContractRecord.create(contractId,current.projectId(),owner,body,selectors) : new ContractRecord(contractId,current.projectId(),prior.revision()+1,owner,ContractRecord.hash(body),body,ContractRecord.Status.ACTIVE,prior.contractId(),selectors); if(prior!=null) current.append(contractId,PredictionEventType.CONTRACT_SUPERSEDED,signer.nodeId(),ContractCodec.encodeSupersede(contractId,prior.revision(),record.revision()),signer); current.append(contractId,PredictionEventType.CONTRACT_PUBLISHED,signer.nodeId(),ContractCodec.encodePublish(record),signer); return record; }
    }

    /** Binds an intent to the exact active contract revision.
     * @param intentId intent identifier
     * @param participant consumer handle
     * @param contractId contract identifier
     * @param revision exact revision
     * @throws IOException if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void bind(UUID intentId, String participant, UUID contractId, long revision) throws IOException, GeneralSecurityException {
        try(ProjectAppendLock lock=ProjectAppendLock.acquire(store.rootDirectory())) { if(!lock.isHeld()) throw new IOException("event append lock unavailable"); PredictionEventStore current=fresh(); ContractDependency dependency=new ContractDependency(intentId,participant,contractId,revision,ContractDependency.State.ACCEPTED); current.append(intentId,PredictionEventType.CONTRACT_DEPENDENCY_BOUND,signer.nodeId(),ContractCodec.encodeDependency(dependency),signer); }
    }
    /** Returns contracts.
     * @return immutable contract snapshot
     */
    public List<ContractRecord> contracts() { try { return fresh().contractProjection().contracts(); } catch(Exception e) { throw new IllegalStateException("CONTRACT_STATE_UNAVAILABLE",e); } }
    /** Returns dependencies.
     * @return immutable dependency snapshot
     */
    public List<ContractDependency> dependencies() { try { return fresh().contractProjection().dependencies(); } catch(Exception e) { throw new IllegalStateException("CONTRACT_STATE_UNAVAILABLE",e); } }
    private PredictionEventStore fresh() throws IOException, GeneralSecurityException { return new PredictionEventStore(store.rootDirectory(),store.projectId()); }
}
