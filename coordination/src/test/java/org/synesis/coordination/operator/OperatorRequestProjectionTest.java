package org.synesis.coordination.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies what the event log accepts as an operator request and what a replay reads back.
 */
final class OperatorRequestProjectionTest {

    private static final String CONDITION = OperatorRequest.CONTROL_CHECKOUT_FOREIGN_CHANGES;

    private static void raise(PredictionEventStore store, NodeIdentity identity, String id) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, identity.nodeId(),
                OperatorRequest.raised(id, CONDITION, "The checkout holds foreign changes.", "?? stray.py").encodeRaised(), identity);
    }

    private static void close(PredictionEventStore store, NodeIdentity identity, String id) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_CLOSED, identity.nodeId(), OperatorRequest.encodeClosed(id, "The checkout is clean."), identity);
    }

    @Test
    void aRaisedRequestIsOpenUntilItIsClosedAndAReplayReadsTheSame(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);

        raise(store, identity, "opr_one");
        OperatorRequest open = store.operatorRequestProjection().open(CONDITION).orElseThrow();
        assertEquals("?? stray.py", open.evidence());
        assertTrue(open.raisedBySynesis());
        assertEquals(1, new PredictionEventStore(temp, project).operatorRequestProjection().open().size());

        close(store, identity, "opr_one");
        assertTrue(store.operatorRequestProjection().open().isEmpty());
        OperatorRequest closed = new PredictionEventStore(temp, project).operatorRequestProjection().all().getFirst();
        assertEquals(OperatorRequest.State.CLOSED, closed.state());
        assertEquals("The checkout is clean.", closed.closedBecause());
    }

    /** Several processes see one stray file at once; without this each would raise its own request. */
    @Test
    void oneConditionHasOneOpenRequestAndCanBeRaisedAgainOnceClosed(@TempDir Path temp) throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        raise(store, identity, "opr_one");

        assertThrows(IOException.class, () -> raise(store, identity, "opr_two"));
        assertEquals(1, store.operatorRequestProjection().all().size());

        close(store, identity, "opr_one");
        raise(store, identity, "opr_two");
        assertEquals("opr_two", store.operatorRequestProjection().open(CONDITION).orElseThrow().id());
        assertThrows(IOException.class, () -> raise(store, identity, "opr_one"), "an identifier is never reused");
    }

    @Test
    void onlyAnOpenRequestCanBeClosed(@TempDir Path temp) throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());

        assertThrows(IOException.class, () -> close(store, identity, "opr_missing"));
        raise(store, identity, "opr_one");
        close(store, identity, "opr_one");
        assertThrows(IOException.class, () -> close(store, identity, "opr_one"));
    }

    /** Tool output is not ours to size, and an oversized one must not stop the request being recorded. */
    @Test
    void evidenceLongerThanTheWireBoundIsCutNotRefused(@TempDir Path temp) throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        OperatorRequest request = OperatorRequest.raised("opr_big", CONDITION, "The checkout holds foreign changes.", "?? a-very-long-path.py\n".repeat(2_000));

        store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, identity.nodeId(), request.encodeRaised(), identity);

        assertTrue(store.operatorRequestProjection().open(CONDITION).orElseThrow().evidence().startsWith("?? a-very-long-path.py"));
    }
}
