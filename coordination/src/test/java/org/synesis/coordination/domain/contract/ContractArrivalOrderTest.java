package org.synesis.coordination.domain.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.MissingReferenceException;
import org.synesis.link.identity.NodeIdentity;

/**
 * Contract entries written on two computers: an entry that names a contract or revision not seen yet waits for it.
 */
final class ContractArrivalOrderTest {

    private static final UUID PROJECT = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID CONTRACT = UUID.fromString("80000000-0000-4000-8000-000000000002");
    private static final UUID LINEAGE = UUID.fromString("80000000-0000-4000-8000-000000000003");

    private final NodeIdentity nodeA = NodeIdentity.generate();
    private final NodeIdentity nodeB = NodeIdentity.generate();
    private long sequence;

    ContractArrivalOrderTest() throws Exception {
    }

    private PredictionEvent event(NodeIdentity signer, PredictionEventType type, byte[] payload) throws Exception {
        sequence++;
        return PredictionEvent.create(PROJECT, UUID.randomUUID(), sequence, type, signer.nodeId(), payload,
                new byte[32], signer, sequence);
    }

    private static ContractRecord record(long revision, String purpose) {
        CapabilityContract terms = new CapabilityContract(purpose, "inputs", "output", List.of("behavior"),
                "errors", "effects", "invariants", "compatibility", "performance", "concurrency", List.of("test"));
        return new ContractRecord(CONTRACT, LINEAGE, revision, List.of(ResourceSelector.pathExact("src/api.py")),
                terms, ContractRecord.State.ACTIVE);
    }

    private PredictionEvent published(NodeIdentity signer, long revision, String purpose) throws Exception {
        return event(signer, PredictionEventType.CONTRACT_PUBLISHED,
                ContractCodec.encodePublished(record(revision, purpose)));
    }

    private PredictionEvent bound(NodeIdentity signer, long revision) throws Exception {
        return event(signer, PredictionEventType.CONTRACT_DEPENDENCY_BOUND,
                ContractCodec.encodeBound(new ContractBinding("req_handle", CONTRACT, revision)));
    }

    private static void arrive(ContractProjection projection, PredictionEvent event) throws IOException {
        projection.validate(event, ApplyMode.ARRIVAL);
        projection.apply(event);
    }

    private static ContractProjection with(PredictionEvent... events) throws IOException {
        ContractProjection projection = new ContractProjection();
        for (PredictionEvent event : events) {
            arrive(projection, event);
        }
        return projection;
    }

    @Test
    void aBindingBeforeTheContractIsPublishedIsHeldBackUntilItArrives() throws Exception {
        PredictionEvent publish = published(nodeA, 1, "first");
        PredictionEvent bind = bound(nodeB, 1);
        ContractProjection projection = new ContractProjection();

        assertThrows(MissingReferenceException.class, () -> projection.validate(bind, ApplyMode.ARRIVAL));
        assertTrue(projection.binding("req_handle").isEmpty());
        assertTrue(projection.contracts().isEmpty());

        arrive(projection, publish);
        arrive(projection, bind);
        assertEquals(1, projection.binding("req_handle").orElseThrow().revision());
    }

    @Test
    void aBindingToARevisionNotSeenYetIsHeldBack() throws Exception {
        ContractProjection projection = with(published(nodeA, 1, "first"));
        PredictionEvent bind = bound(nodeB, 2);

        assertThrows(MissingReferenceException.class, () -> projection.validate(bind, ApplyMode.ARRIVAL));
        assertTrue(projection.binding("req_handle").isEmpty());
    }

    @Test
    void aSecondRevisionBeforeTheFirstIsHeldBackUntilTheFirstArrives() throws Exception {
        PredictionEvent first = published(nodeA, 1, "first");
        PredictionEvent second = published(nodeB, 2, "second");
        ContractProjection projection = new ContractProjection();

        MissingReferenceException held = assertThrows(MissingReferenceException.class,
                () -> projection.validate(second, ApplyMode.ARRIVAL));
        assertEquals("CONTRACT_REVISION_STALE", held.getMessage());
        assertTrue(projection.contracts().isEmpty());

        arrive(projection, first);
        arrive(projection, second);
        assertEquals(2, projection.contract(CONTRACT).orElseThrow().revision());
        assertEquals(with(first, second).contracts(), projection.contracts());
    }

    @Test
    void twoWritersOfOneRevisionKeepTheFirstAppliedAndDoNotThrow() throws Exception {
        PredictionEvent first = published(nodeA, 1, "first");
        PredictionEvent byA = published(nodeA, 2, "second by A");
        PredictionEvent byB = published(nodeB, 2, "second by B");

        ContractProjection projection = with(first, byA, byB);

        assertEquals(2, projection.contract(CONTRACT).orElseThrow().revision());
        assertEquals("second by A", projection.contract(CONTRACT).orElseThrow().terms().purpose());
    }

    @Test
    void checkingARevisionOutOfOrderStillRefuses() throws Exception {
        ContractProjection projection = with(published(nodeA, 1, "first"));
        PredictionEvent skipped = published(nodeA, 3, "third");

        CoordinationException refused = assertThrows(CoordinationException.class,
                () -> projection.validate(skipped));
        assertEquals("CONTRACT_REVISION_STALE", refused.getMessage());
        assertEquals(1, projection.contract(CONTRACT).orElseThrow().revision());
    }
}
