package org.synesis.coordination.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.contract.ContractBinding;
import org.synesis.coordination.domain.contract.ContractChangeRequest;
import org.synesis.coordination.domain.contract.ContractCodec;
import org.synesis.coordination.domain.contract.ContractProjection;
import org.synesis.coordination.domain.contract.ContractRecord;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies what the event log accepts as a public contract, a binding or a change request, and what a replay
 * reads back (ADR-0092).
 */
final class ContractProjectionTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID CONTRACT = UUID.randomUUID();
    private static final ResourceSelector STORE = ResourceSelector.pathExact("src/store.py");
    private static final ResourceSelector OTHER_FILE = ResourceSelector.pathExact("src/other.py");

    private final NodeIdentity identity;

    ContractProjectionTest() throws Exception {
        identity = NodeIdentity.generate();
    }

    private static ContractRecord contract(UUID id, UUID owner, long revision, ResourceSelector... paths) {
        return new ContractRecord(id, owner, revision, List.of(paths),
                new CapabilityContract("Store tasks", "TaskStore", List.of("save and load"), List.of("test")), ContractRecord.State.ACTIVE);
    }

    private void publish(PredictionEventStore store, ContractRecord record) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.CONTRACT_PUBLISHED, identity.nodeId(), ContractCodec.encodePublished(record), identity);
    }

    private void bind(PredictionEventStore store, String handle, UUID contractId, long revision) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.CONTRACT_DEPENDENCY_BOUND, identity.nodeId(),
                ContractCodec.encodeBound(new ContractBinding(handle, contractId, revision)), identity);
    }

    private void file(PredictionEventStore store, ContractChangeRequest request) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.CONTRACT_CHANGE_REQUESTED, identity.nodeId(), ContractCodec.encodeChangeRequested(request), identity);
    }

    private ContractChangeRequest file(PredictionEventStore store, UUID owner, UUID filer) throws Exception {
        ContractChangeRequest request = ContractChangeRequest.open(UUID.randomUUID(), owner, STORE, filer, "listing must not create the store", 0);
        file(store, request);
        return request;
    }

    private void answer(PredictionEventStore store, UUID id, ContractChangeRequest.State answer, long revision, String reason) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.CONTRACT_CHANGE_ANSWERED, identity.nodeId(), ContractCodec.encodeChangeAnswered(id, answer, revision, reason), identity);
    }

    private void endLane(PredictionEventStore store, UUID lineage) throws Exception {
        endLane(store, lineage, true);
    }

    private void endLane(PredictionEventStore store, UUID lineage, boolean integrated) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.CONTRACT_LANE_ENDED, identity.nodeId(), ContractCodec.encodeLaneEnded(lineage, integrated), identity);
    }

    @Test
    void aContractStartsAtRevisionOneAndItsOwnerRevisesItInOrder(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());

        publish(store, contract(CONTRACT, OWNER, 1, STORE));
        publish(store, contract(CONTRACT, OWNER, 2, STORE, OTHER_FILE));

        ContractRecord current = store.contractProjection().contract(CONTRACT).orElseThrow();
        assertEquals(2, current.revision());
        assertEquals(2, current.paths().size());
        assertEquals(CONTRACT, store.contractProjection().activeCovering(OTHER_FILE).orElseThrow().contractId());
    }

    @Test
    void aRevisionFromAnotherLineageIsRefused(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        publish(store, contract(CONTRACT, OWNER, 1, STORE));

        assertThrows(IOException.class, () -> publish(store, contract(CONTRACT, OTHER, 2, STORE)));

        ContractRecord current = store.contractProjection().contract(CONTRACT).orElseThrow();
        assertEquals(1, current.revision());
        assertEquals(OWNER, current.ownerLineageId());
    }

    @Test
    void aRevisionNumberThatDoesNotFollowIsRefused(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());

        assertThrows(IOException.class, () -> publish(store, contract(CONTRACT, OWNER, 2, STORE)), "a contract starts at one");
        publish(store, contract(CONTRACT, OWNER, 1, STORE));
        assertThrows(IOException.class, () -> publish(store, contract(CONTRACT, OWNER, 1, STORE)), "a revision is not repeated");
        assertThrows(IOException.class, () -> publish(store, contract(CONTRACT, OWNER, 3, STORE)), "a revision is not skipped");
        assertEquals(1, store.contractProjection().contract(CONTRACT).orElseThrow().revision());
    }

    @Test
    void aSecondActiveContractOverACoveredFileIsRefused(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        publish(store, contract(CONTRACT, OWNER, 1, ResourceSelector.pathSubtree("src")));

        assertThrows(IOException.class, () -> publish(store, contract(UUID.randomUUID(), OWNER, 1, STORE)));
        assertEquals(1, store.contractProjection().contracts().size());
    }

    @Test
    void aBindingToAnythingButTheCurrentRevisionIsRefused(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());

        assertThrows(IOException.class, () -> bind(store, "cap_a", CONTRACT, 1), "no such contract");
        publish(store, contract(CONTRACT, OWNER, 1, STORE));
        publish(store, contract(CONTRACT, OWNER, 2, STORE));

        assertThrows(IOException.class, () -> bind(store, "cap_a", CONTRACT, 1), "a revision that was replaced");
        assertThrows(IOException.class, () -> bind(store, "cap_a", CONTRACT, 3), "a revision that does not exist");
        bind(store, "cap_a", CONTRACT, 2);
        assertEquals(2, store.contractProjection().binding("cap_a").orElseThrow().revision());
        assertEquals(1, store.contractProjection().bindings(CONTRACT).size());
    }

    @Test
    void aChangeRequestIsAnsweredOnce(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        publish(store, contract(CONTRACT, OWNER, 1, STORE));
        ContractChangeRequest request = file(store, OWNER, OTHER);
        assertEquals(List.of(request.id()), store.contractProjection().openChangeRequestsFor(OWNER).stream().map(ContractChangeRequest::id).toList());

        answer(store, request.id(), ContractChangeRequest.State.DECLINED, 0, "the store must not change");

        ContractChangeRequest declined = store.contractProjection().changeRequest(request.id()).orElseThrow();
        assertEquals(ContractChangeRequest.State.DECLINED, declined.state());
        assertEquals("the store must not change", declined.reason());
        assertTrue(store.contractProjection().openChangeRequestsFor(OWNER).isEmpty());
        assertThrows(IOException.class, () -> answer(store, request.id(), ContractChangeRequest.State.DECLINED, 0, "again"));
        assertThrows(IOException.class, () -> answer(store, UUID.randomUUID(), ContractChangeRequest.State.DECLINED, 0, "no such request"));
        assertThrows(IOException.class, () -> file(store, request), "an identifier is never reused");
    }

    @Test
    void anAcceptedAnswerNamesARevisionThatExistsAndADeclineGivesAReason(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        publish(store, contract(CONTRACT, OWNER, 1, STORE));
        ContractChangeRequest request = file(store, OWNER, OTHER);

        assertThrows(IOException.class, () -> answer(store, request.id(), ContractChangeRequest.State.ACCEPTED, 2, ""), "revision 2 does not exist");
        assertThrows(IOException.class, () -> answer(store, request.id(), ContractChangeRequest.State.DECLINED, 0, " "), "a decline without a reason");
        assertEquals(ContractChangeRequest.State.OPEN, store.contractProjection().changeRequest(request.id()).orElseThrow().state());

        publish(store, contract(CONTRACT, OWNER, 2, STORE));
        answer(store, request.id(), ContractChangeRequest.State.ACCEPTED, 2, "");
        ContractChangeRequest accepted = store.contractProjection().changeRequest(request.id()).orElseThrow();
        assertEquals(ContractChangeRequest.State.ACCEPTED, accepted.state());
        assertEquals(2, accepted.answeredByRevision());
    }

    @Test
    void endingALaneFreezesItsContractsAndSettlesTheRequestsAroundIt(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        publish(store, contract(CONTRACT, OWNER, 1, STORE));
        ContractChangeRequest toOwner = file(store, OWNER, OTHER);
        ContractChangeRequest fromOwner = ContractChangeRequest.open(UUID.randomUUID(), OTHER, OTHER_FILE, OWNER, "needs a listing", 0);
        file(store, fromOwner);

        endLane(store, OWNER);

        ContractProjection projection = store.contractProjection();
        assertEquals(ContractRecord.State.FROZEN, projection.contract(CONTRACT).orElseThrow().state());
        assertFalse(projection.activeCovering(STORE).isPresent());
        ContractChangeRequest declined = projection.changeRequest(toOwner.id()).orElseThrow();
        assertEquals(ContractChangeRequest.State.DECLINED, declined.state());
        assertEquals(ContractChangeRequest.OWNER_LANE_ENDED, declined.reason());
        ContractChangeRequest withdrawn = projection.changeRequest(fromOwner.id()).orElseThrow();
        assertEquals(ContractChangeRequest.State.WITHDRAWN, withdrawn.state());
        assertEquals(ContractChangeRequest.FILER_LANE_ENDED, withdrawn.reason());
        assertThrows(IOException.class, () -> publish(store, contract(CONTRACT, OWNER, 2, STORE)), "a frozen contract is never edited");
        assertThrows(IOException.class, () -> bind(store, "cap_late", CONTRACT, 1), "nothing binds to a frozen contract");
        endLane(store, OWNER);
        assertEquals(ContractRecord.State.FROZEN, store.contractProjection().contract(CONTRACT).orElseThrow().state());
    }

    @Test
    void aLaneThatEndsWithNothingIntegratedWithdrawsItsContractAndALaterEndingDoesNotFreezeIt(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        publish(store, contract(CONTRACT, OWNER, 1, STORE));

        endLane(store, OWNER, false);

        ContractProjection projection = store.contractProjection();
        assertEquals(ContractRecord.State.WITHDRAWN, projection.contract(CONTRACT).orElseThrow().state());
        assertFalse(projection.frozenCovering(STORE).isPresent(), "nothing was built to a withdrawn contract");
        assertFalse(projection.activeCovering(STORE).isPresent());
        assertThrows(IOException.class, () -> publish(store, contract(CONTRACT, OWNER, 2, STORE)), "a withdrawn contract is not revised");
        assertThrows(IOException.class, () -> bind(store, "cap_late", CONTRACT, 1), "nothing binds to a withdrawn contract");

        endLane(store, OWNER, true);

        assertEquals(ContractRecord.State.WITHDRAWN, store.contractProjection().contract(CONTRACT).orElseThrow().state());
        assertFalse(store.contractProjection().frozenCovering(STORE).isPresent());
    }

    @Test
    void aFrozenFileCanBeCoveredByANewContractOfALaterLane(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        publish(store, contract(CONTRACT, OWNER, 1, STORE));
        endLane(store, OWNER);

        UUID next = UUID.randomUUID();
        publish(store, contract(next, OTHER, 1, STORE));

        assertEquals(next, store.contractProjection().activeCovering(STORE).orElseThrow().contractId());
    }

    @Test
    void aFrozenContractStaysFindableByItsFilesAndTheLatestFrozenOneWins(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        publish(store, contract(CONTRACT, OWNER, 1, STORE));
        assertFalse(store.contractProjection().frozenCovering(STORE).isPresent(), "an active contract is not frozen");
        endLane(store, OWNER);
        assertEquals(CONTRACT, store.contractProjection().frozenCovering(STORE).orElseThrow().contractId());
        assertFalse(store.contractProjection().frozenCovering(OTHER_FILE).isPresent());

        UUID next = UUID.randomUUID();
        publish(store, contract(next, OTHER, 1, STORE));
        assertEquals(CONTRACT, store.contractProjection().frozenCovering(STORE).orElseThrow().contractId(), "the new contract is active, not frozen");
        endLane(store, OTHER);
        assertEquals(next, store.contractProjection().frozenCovering(STORE).orElseThrow().contractId());
    }

    @Test
    void aReplayFromDiskReadsTheSameState(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        publish(store, contract(CONTRACT, OWNER, 1, STORE));
        publish(store, contract(CONTRACT, OWNER, 2, STORE));
        bind(store, "cap_a", CONTRACT, 2);
        ContractChangeRequest open = file(store, OWNER, OTHER);
        ContractChangeRequest answered = file(store, OWNER, OTHER);
        answer(store, answered.id(), ContractChangeRequest.State.DECLINED, 0, "no");

        ContractProjection replayed = new PredictionEventStore(temp, project).contractProjection();

        assertEquals(store.contractProjection().contracts(), replayed.contracts());
        assertEquals(store.contractProjection().bindings(CONTRACT), replayed.bindings(CONTRACT));
        assertEquals(store.contractProjection().changeRequest(open.id()), replayed.changeRequest(open.id()));
        assertEquals(store.contractProjection().changeRequest(answered.id()), replayed.changeRequest(answered.id()));
        assertEquals(1, replayed.openChangeRequestsFor(OWNER).size());
    }
}
