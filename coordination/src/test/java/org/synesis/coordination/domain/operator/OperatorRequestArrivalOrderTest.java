package org.synesis.coordination.domain.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.MissingReferenceException;
import org.synesis.link.identity.NodeIdentity;

/**
 * Operator request entries written on two computers meet in either order and end in one state.
 */
final class OperatorRequestArrivalOrderTest {

    private static final UUID PROJECT = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final String CONDITION = OperatorRequest.CONTROL_CHECKOUT_FOREIGN_CHANGES;

    private final NodeIdentity nodeA = NodeIdentity.generate();
    private final NodeIdentity nodeB = NodeIdentity.generate();
    private long sequence;

    OperatorRequestArrivalOrderTest() throws Exception {
    }

    private PredictionEvent event(NodeIdentity signer, PredictionEventType type, byte[] payload) throws Exception {
        sequence++;
        return PredictionEvent.create(PROJECT, UUID.randomUUID(), sequence, type, signer.nodeId(), payload,
                new byte[32], signer, sequence);
    }

    private PredictionEvent raised(NodeIdentity signer, String id) throws Exception {
        return event(signer, PredictionEventType.OPERATOR_REQUEST_RAISED,
                OperatorRequest.raised(id, CONDITION, "stray file", "evidence").encodeRaised());
    }

    private PredictionEvent closed(NodeIdentity signer, String id, String because) throws Exception {
        return event(signer, PredictionEventType.OPERATOR_REQUEST_CLOSED,
                OperatorRequest.encodeClosed(id, because));
    }

    private static void arrive(OperatorRequestProjection projection, PredictionEvent event) throws IOException {
        projection.validate(event, ApplyMode.ARRIVAL);
        projection.apply(event);
    }

    private static OperatorRequestProjection with(PredictionEvent... events) throws IOException {
        OperatorRequestProjection projection = new OperatorRequestProjection();
        for (PredictionEvent event : events) {
            arrive(projection, event);
        }
        return projection;
    }

    @Test
    void twoDifferentAnswersLeaveTheRequestOpenInEitherOrder() throws Exception {
        PredictionEvent raise = raised(nodeA, "req_1");
        PredictionEvent byA = closed(nodeA, "req_1", "cleaned up");
        PredictionEvent byB = closed(nodeB, "req_1", "committed it");

        OperatorRequestProjection firstOrder = with(raise, byA, byB);
        OperatorRequestProjection secondOrder = with(raise, byB, byA);

        for (OperatorRequestProjection projection : List.of(firstOrder, secondOrder)) {
            assertEquals(1, projection.open().size());
            assertEquals(OperatorRequest.State.OPEN, projection.all().getFirst().state());
            assertEquals("", projection.all().getFirst().closedBecause());
        }
        assertEquals(firstOrder.all(), secondOrder.all());
    }

    @Test
    void theSameAnswerFromTwoComputersClosesTheRequestInEitherOrder() throws Exception {
        PredictionEvent raise = raised(nodeA, "req_1");
        PredictionEvent byA = closed(nodeA, "req_1", "cleaned up");
        PredictionEvent byB = closed(nodeB, "req_1", "cleaned up");

        OperatorRequestProjection firstOrder = with(raise, byA, byB);
        OperatorRequestProjection secondOrder = with(raise, byB, byA);

        for (OperatorRequestProjection projection : List.of(firstOrder, secondOrder)) {
            assertTrue(projection.open().isEmpty());
            assertEquals(OperatorRequest.State.CLOSED, projection.all().getFirst().state());
            assertEquals("cleaned up", projection.all().getFirst().closedBecause());
        }
        assertEquals(firstOrder.all(), secondOrder.all());
    }

    @Test
    void aNodesNewAnswerReplacesItsOwn() throws Exception {
        PredictionEvent raise = raised(nodeA, "req_1");
        PredictionEvent firstByA = closed(nodeA, "req_1", "cleaned up");
        PredictionEvent byB = closed(nodeB, "req_1", "committed it");
        PredictionEvent secondByA = closed(nodeA, "req_1", "committed it");

        OperatorRequestProjection projection = with(raise, firstByA, byB);
        assertEquals(1, projection.open().size());
        arrive(projection, secondByA);

        assertTrue(projection.open().isEmpty());
        assertEquals("committed it", projection.all().getFirst().closedBecause());
    }

    @Test
    void anAnswerBeforeTheRaisingIsHeldBackUntilTheRequestArrives() throws Exception {
        PredictionEvent raise = raised(nodeA, "req_1");
        PredictionEvent byB = closed(nodeB, "req_1", "cleaned up");
        OperatorRequestProjection projection = new OperatorRequestProjection();

        assertThrows(MissingReferenceException.class, () -> projection.validate(byB, ApplyMode.ARRIVAL));
        assertTrue(projection.all().isEmpty());

        arrive(projection, raise);
        arrive(projection, byB);
        assertEquals(with(raise, byB).all(), projection.all());
        assertEquals(OperatorRequest.State.CLOSED, projection.all().getFirst().state());
    }

    @Test
    void checkingAnAnswerToAnUnknownRequestStillRefuses() throws Exception {
        OperatorRequestProjection projection = new OperatorRequestProjection();
        PredictionEvent byB = closed(nodeB, "req_1", "cleaned up");

        CoordinationException refused = assertThrows(CoordinationException.class, () -> projection.validate(byB));
        assertEquals("OPERATOR_REQUEST_NOT_OPEN", refused.getMessage());
    }

    @Test
    void twoSynesisRaisesOfOneConditionBothStandOnArrivalButNotOnCheck() throws Exception {
        PredictionEvent first = raised(nodeA, "req_a");
        PredictionEvent second = raised(nodeB, "req_b");

        OperatorRequestProjection firstOrder = with(first, second);
        OperatorRequestProjection secondOrder = with(second, first);
        assertEquals(2, firstOrder.open().size());
        assertEquals(2, secondOrder.open().size());

        OperatorRequestProjection replayed = new OperatorRequestProjection();
        replayed.apply(first);
        replayed.apply(second);
        assertEquals(2, replayed.open().size());

        OperatorRequestProjection own = with(first);
        CoordinationException refused = assertThrows(CoordinationException.class, () -> own.validate(second));
        assertEquals("OPERATOR_REQUEST_ALREADY_OPEN", refused.getMessage());
        assertEquals(1, own.open().size());
    }
}
