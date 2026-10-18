package org.synesis.coordination.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.MissingReferenceException;
import org.synesis.link.identity.NodeIdentity;

/**
 * Legacy task claims written on two computers meet in either order and the lower node keeps the claim.
 */
final class CoordinationArrivalOrderTest {

    private static final UUID PROJECT = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID TASK_ONE = UUID.fromString("70000000-0000-4000-8000-000000000002");
    private static final UUID TASK_TWO = UUID.fromString("70000000-0000-4000-8000-000000000003");

    private final NodeIdentity nodeA = NodeIdentity.generate();
    private final NodeIdentity nodeB = NodeIdentity.generate();
    private long sequence;

    CoordinationArrivalOrderTest() throws Exception {
    }

    private PredictionEvent event(NodeIdentity signer, UUID prediction, PredictionEventType type, byte[] payload)
            throws Exception {
        sequence++;
        byte[] command = CoordinationCommand.create(UUID.randomUUID(), PROJECT, prediction, type, signer.nodeId(),
                payload, signer).encoded();
        return PredictionEvent.create(PROJECT, prediction, sequence, type, signer.nodeId(), command, new byte[32],
                signer, sequence);
    }

    private PredictionEvent created(NodeIdentity signer, UUID task) throws Exception {
        return event(signer, task, PredictionEventType.TASK_CREATED,
                new CoordinationTask(task, PROJECT, "title", "capability", signer.nodeId(), "sup", "wrk")
                        .encoded());
    }

    private PredictionEvent claimed(NodeIdentity signer, UUID task) throws Exception {
        return event(signer, task, PredictionEventType.TASK_CLAIMED,
                new TaskClaim(task, signer.nodeId(), "sup", "wrk").encoded());
    }

    private PredictionEvent owned(NodeIdentity signer, UUID task) throws Exception {
        return event(signer, task, PredictionEventType.OWNERSHIP_CLAIMED,
                new OwnershipClaim(task, "shared-capability", signer.nodeId(), "sup", List.of("src/**"), 1)
                        .encoded());
    }

    private static void arrive(CoordinationProjection projection, PredictionEvent event) throws IOException {
        projection.validate(event, ApplyMode.ARRIVAL);
        projection.apply(event);
    }

    private static CoordinationProjection with(PredictionEvent... events) throws IOException {
        CoordinationProjection projection = new CoordinationProjection();
        for (PredictionEvent event : events) {
            arrive(projection, event);
        }
        return projection;
    }

    private NodeIdentity lower() {
        return nodeA.nodeId().compareTo(nodeB.nodeId()) <= 0 ? nodeA : nodeB;
    }

    private NodeIdentity higher() {
        return lower() == nodeA ? nodeB : nodeA;
    }

    @Test
    void twoClaimsOfOneTaskLeaveTheLowerNodeTheOwnerInEitherOrder() throws Exception {
        PredictionEvent create = created(nodeA, TASK_ONE);
        PredictionEvent byLower = claimed(lower(), TASK_ONE);
        PredictionEvent byHigher = claimed(higher(), TASK_ONE);

        CoordinationProjection firstOrder = with(create, byLower, byHigher);
        CoordinationProjection secondOrder = with(create, byHigher, byLower);

        for (CoordinationProjection projection : List.of(firstOrder, secondOrder)) {
            assertEquals(lower().nodeId(), projection.task(TASK_ONE).orElseThrow().ownerNodeId());
        }
    }

    @Test
    void checkingASecondClaimStillRefuses() throws Exception {
        CoordinationProjection projection = with(created(nodeA, TASK_ONE), claimed(nodeA, TASK_ONE));
        PredictionEvent second = claimed(nodeB, TASK_ONE);

        CoordinationException refused = assertThrows(CoordinationException.class, () -> projection.validate(second));
        assertEquals("TASK_ALREADY_CLAIMED", refused.getMessage());
        assertEquals(nodeA.nodeId(), projection.task(TASK_ONE).orElseThrow().ownerNodeId());
    }

    @Test
    void aClaimBeforeTheTaskIsHeldBackUntilTheTaskArrives() throws Exception {
        PredictionEvent create = created(nodeA, TASK_ONE);
        PredictionEvent claim = claimed(nodeB, TASK_ONE);
        CoordinationProjection projection = new CoordinationProjection();

        assertThrows(MissingReferenceException.class, () -> projection.validate(claim, ApplyMode.ARRIVAL));
        assertTrue(projection.tasks().isEmpty());

        arrive(projection, create);
        arrive(projection, claim);
        assertEquals(nodeB.nodeId(), projection.task(TASK_ONE).orElseThrow().ownerNodeId());
    }

    @Test
    void twoOwnershipClaimsOfOneCapabilityLeaveTheLowerNodeTheOwnerInEitherOrder() throws Exception {
        PredictionEvent createOne = created(nodeA, TASK_ONE);
        PredictionEvent createTwo = created(nodeB, TASK_TWO);
        PredictionEvent claimLower = claimed(lower(), TASK_ONE);
        PredictionEvent claimHigher = claimed(higher(), TASK_TWO);
        PredictionEvent ownLower = owned(lower(), TASK_ONE);
        PredictionEvent ownHigher = owned(higher(), TASK_TWO);

        CoordinationProjection firstOrder = with(createOne, createTwo, claimLower, claimHigher, ownLower, ownHigher);
        CoordinationProjection secondOrder = with(createOne, createTwo, claimLower, claimHigher, ownHigher,
                ownLower);

        for (CoordinationProjection projection : List.of(firstOrder, secondOrder)) {
            assertEquals(lower().nodeId(), projection.ownership("shared-capability").orElseThrow().ownerNodeId());
        }

        CoordinationProjection own = with(createOne, createTwo, claimLower, claimHigher, ownLower);
        CoordinationException refused = assertThrows(CoordinationException.class, () -> own.validate(ownHigher));
        assertEquals("OWNERSHIP_CONFLICT", refused.getMessage());
    }
}
