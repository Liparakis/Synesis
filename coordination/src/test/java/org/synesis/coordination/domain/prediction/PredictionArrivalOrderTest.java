package org.synesis.coordination.domain.prediction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.exception.CoordinationStateException;
import org.synesis.coordination.exception.MissingReferenceException;
import org.synesis.link.identity.NodeIdentity;

/**
 * Prediction steps written on two computers meet in any order: a step whose predecessor has not arrived waits, and a
 * step the projection is already past keeps the state.
 */
final class PredictionArrivalOrderTest {

    private static final UUID PROJECT = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID PREDICTION = UUID.fromString("60000000-0000-4000-8000-000000000002");

    private final NodeIdentity nodeA = NodeIdentity.generate();
    private final NodeIdentity nodeB = NodeIdentity.generate();
    private long sequence;

    PredictionArrivalOrderTest() throws Exception {
    }

    private PredictionEvent step(NodeIdentity signer, PredictionEventType type) throws Exception {
        sequence++;
        return PredictionEvent.create(PROJECT, PREDICTION, sequence, type, signer.nodeId(), new byte[0],
                new byte[32], signer, sequence);
    }

    private static void arrive(PredictionProjection projection, PredictionEvent event) throws IOException {
        projection.validate(event, ApplyMode.ARRIVAL);
        projection.apply(event);
    }

    private static PredictionProjection with(PredictionEvent... events) throws IOException {
        PredictionProjection projection = new PredictionProjection();
        for (PredictionEvent event : events) {
            arrive(projection, event);
        }
        return projection;
    }

    @Test
    void aStepBeforeTheCreationIsHeldBackOnArrivalAndRefusedOnReplay() throws Exception {
        PredictionEvent created = step(nodeA, PredictionEventType.PREDICTION_CREATED);
        PredictionEvent routed = step(nodeB, PredictionEventType.PREDICTION_ROUTED);
        PredictionProjection projection = new PredictionProjection();

        assertThrows(MissingReferenceException.class, () -> projection.validate(routed, ApplyMode.ARRIVAL));
        assertThrows(CoordinationStateException.class, () -> projection.validate(routed, ApplyMode.REPLAY));
        assertThrows(CoordinationStateException.class, () -> projection.apply(routed));
        assertTrue(projection.snapshot().isEmpty());

        arrive(projection, created);
        arrive(projection, routed);
        assertEquals(PredictionState.ROUTED, projection.state(PREDICTION).orElseThrow());
    }

    @Test
    void aStepWhoseEarlierStepHasNotArrivedIsHeldBackUntilItDoes() throws Exception {
        PredictionEvent created = step(nodeA, PredictionEventType.PREDICTION_CREATED);
        PredictionEvent routed = step(nodeA, PredictionEventType.PREDICTION_ROUTED);
        PredictionEvent received = step(nodeB, PredictionEventType.REQUEST_RECEIVED);
        PredictionProjection projection = with(created);

        assertThrows(MissingReferenceException.class, () -> projection.validate(received, ApplyMode.ARRIVAL));
        assertEquals(PredictionState.PROPOSED, projection.state(PREDICTION).orElseThrow());

        arrive(projection, routed);
        arrive(projection, received);
        assertEquals(PredictionState.RECEIVED, projection.state(PREDICTION).orElseThrow());
        assertEquals(with(created, routed, received).snapshot(), projection.snapshot());
    }

    @Test
    void theSameStepWrittenByTwoComputersKeepsTheState() throws Exception {
        PredictionEvent created = step(nodeA, PredictionEventType.PREDICTION_CREATED);
        PredictionEvent routedByA = step(nodeA, PredictionEventType.PREDICTION_ROUTED);
        PredictionEvent routedByB = step(nodeB, PredictionEventType.PREDICTION_ROUTED);
        PredictionEvent received = step(nodeB, PredictionEventType.REQUEST_RECEIVED);

        PredictionProjection projection = with(created, routedByA, received);
        assertDoesNotThrow(() -> projection.validate(routedByB, ApplyMode.ARRIVAL));
        assertDoesNotThrow(() -> projection.apply(routedByB));

        assertEquals(Map.of(PREDICTION, PredictionState.RECEIVED), projection.snapshot());
    }

    @Test
    void aTerminalStateAbsorbsALaterStep() throws Exception {
        PredictionEvent created = step(nodeA, PredictionEventType.PREDICTION_CREATED);
        PredictionEvent routed = step(nodeA, PredictionEventType.PREDICTION_ROUTED);
        PredictionEvent rejected = step(nodeA, PredictionEventType.REQUEST_REJECTED);
        PredictionEvent received = step(nodeB, PredictionEventType.REQUEST_RECEIVED);
        PredictionProjection projection = with(created, routed, rejected);

        assertDoesNotThrow(() -> projection.validate(received, ApplyMode.ARRIVAL));
        assertDoesNotThrow(() -> projection.apply(received));

        assertEquals(PredictionState.REJECTED, projection.state(PREDICTION).orElseThrow());
    }

    @Test
    void checkingAStepTheProjectionIsPastStillRefuses() throws Exception {
        PredictionEvent created = step(nodeA, PredictionEventType.PREDICTION_CREATED);
        PredictionEvent routed = step(nodeA, PredictionEventType.PREDICTION_ROUTED);
        PredictionEvent again = step(nodeB, PredictionEventType.PREDICTION_ROUTED);
        PredictionProjection projection = with(created, routed);

        assertThrows(CoordinationStateException.class, () -> projection.validate(again));
        assertEquals(PredictionState.ROUTED, projection.state(PREDICTION).orElseThrow());
    }

    private NodeIdentity lower() {
        return nodeA.nodeId().compareTo(nodeB.nodeId()) < 0 ? nodeA : nodeB;
    }

    private NodeIdentity higher() {
        return lower() == nodeA ? nodeB : nodeA;
    }

    /**
     * Designer item 18 case 2: an exact acceptance and a contract revision both follow RECEIVED. The first to arrive
     * stood, so two computers could keep different states for ever; the lower node's stands in either order.
     */
    @Test
    void ofTwoSiblingStepsTheLowerNodesStandsInEitherOrder() throws Exception {
        PredictionEvent created = step(nodeA, PredictionEventType.PREDICTION_CREATED);
        PredictionEvent routed = step(nodeA, PredictionEventType.PREDICTION_ROUTED);
        PredictionEvent received = step(nodeA, PredictionEventType.REQUEST_RECEIVED);
        PredictionEvent revised = step(lower(), PredictionEventType.CONTRACT_REVISED);
        PredictionEvent accepted = step(higher(), PredictionEventType.ACCEPTED_EXACT);

        assertEquals(PredictionState.REVISED, with(created, routed, received, revised, accepted).state(PREDICTION).orElseThrow());
        assertEquals(PredictionState.REVISED, with(created, routed, received, accepted, revised).state(PREDICTION).orElseThrow());
    }

    /** Designer item 18 case 2: two terminal steps end the prediction either way; the label is the lower node's. */
    @Test
    void ofTwoTerminalStepsTheLowerNodesLabelStandsInEitherOrder() throws Exception {
        PredictionEvent created = step(nodeA, PredictionEventType.PREDICTION_CREATED);
        PredictionEvent rejected = step(lower(), PredictionEventType.REQUEST_REJECTED);
        PredictionEvent expired = step(higher(), PredictionEventType.PREDICTION_EXPIRED);

        assertEquals(PredictionState.REJECTED, with(created, rejected, expired).state(PREDICTION).orElseThrow());
        assertEquals(PredictionState.REJECTED, with(created, expired, rejected).state(PREDICTION).orElseThrow());
    }
}
