package org.synesis.coordination.domain.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Work group and grant entries written on two computers meet in either order and end in one state.
 */
final class WorkGroupArrivalOrderTest {

    private static final UUID PROJECT = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID GROUP = UUID.fromString("40000000-0000-4000-8000-000000000002");
    private static final UUID LANE = UUID.fromString("40000000-0000-4000-8000-000000000003");
    private static final UUID GRANT = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID LINEAGE = UUID.fromString("40000000-0000-4000-8000-000000000005");

    private final NodeIdentity nodeA = NodeIdentity.generate();
    private final NodeIdentity nodeB = NodeIdentity.generate();
    private long sequence;

    WorkGroupArrivalOrderTest() throws Exception {
    }

    private PredictionEvent event(NodeIdentity signer, PredictionEventType type, byte[] payload) throws Exception {
        sequence++;
        return PredictionEvent.create(PROJECT, UUID.randomUUID(), sequence, type, signer.nodeId(), payload,
                new byte[32], signer, sequence);
    }

    private static WorkGroup group(String goal, long version, WorkGroup.Status status) {
        return new WorkGroup(GROUP, PROJECT, goal, "acceptance", version, status);
    }

    private PredictionEvent created(NodeIdentity signer, String goal) throws Exception {
        return event(signer, PredictionEventType.WORK_GROUP_CREATED,
                CollaborationCodec.encodeWorkGroup(group(goal, 1, WorkGroup.Status.ACTIVE)));
    }

    private PredictionEvent changed(NodeIdentity signer, long version, WorkGroup.Status status) throws Exception {
        return event(signer, PredictionEventType.WORK_GROUP_STATUS_CHANGED,
                CollaborationCodec.encodeWorkGroup(group("changed goal is ignored", version, status)));
    }

    private PredictionEvent grantEvent(NodeIdentity signer, PredictionEventType type) throws Exception {
        return event(signer, type, CollaborationCodec.encodeLaneGrant(new LaneGrant(GRANT, GROUP, LANE, "agt-b", 1,
                true)));
    }

    private static WorkIntent intent() {
        return new WorkIntent(LANE, PROJECT, "agt-a", "codex", UUID.randomUUID(), "goal", "acceptance", "base",
                List.of(ResourceSelector.pathExact("src/a.py")), 1, GROUP, LINEAGE, WorkIntent.Status.ANNOUNCED);
    }

    private static void arrive(WorkGroupProjection projection, PredictionEvent event) throws IOException {
        projection.validate(event, ApplyMode.ARRIVAL);
        projection.apply(event);
    }

    private static WorkGroupProjection with(PredictionEvent... events) throws IOException {
        WorkGroupProjection projection = new WorkGroupProjection();
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
    void twoCreationsOfOneGroupKeepTheLowerNodesGoalAndTheStatusHistory() throws Exception {
        PredictionEvent fromLower = created(lower(), "goal of the lower node");
        PredictionEvent fromHigher = created(higher(), "goal of the higher node");
        PredictionEvent completed = changed(nodeA, 2, WorkGroup.Status.COMPLETED);

        WorkGroupProjection firstOrder = with(fromLower, fromHigher, completed);
        WorkGroupProjection secondOrder = with(fromHigher, fromLower, completed);
        WorkGroupProjection lateCreation = with(fromHigher, completed, fromLower);
        WorkGroupProjection earlyCreation = with(fromLower, completed, fromHigher);

        for (WorkGroupProjection projection : List.of(firstOrder, secondOrder, lateCreation, earlyCreation)) {
            assertEquals(1, projection.groups().size());
            WorkGroup stood = projection.group(GROUP).orElseThrow();
            assertEquals("goal of the lower node", stood.goal());
            assertEquals(2, stood.version());
            assertEquals(WorkGroup.Status.COMPLETED, stood.status());
        }
        assertEquals(firstOrder.groups(), secondOrder.groups());
    }

    @Test
    void checkingASecondCreationOfOneGroupStillRefuses() throws Exception {
        PredictionEvent first = created(nodeA, "goal");
        PredictionEvent second = created(nodeB, "other goal");
        WorkGroupProjection projection = with(first);

        CoordinationException refused = assertThrows(CoordinationException.class, () -> projection.validate(second));
        assertEquals("WORK_GROUP_EXISTS", refused.getMessage());
        assertEquals("goal", projection.group(GROUP).orElseThrow().goal());
    }

    @Test
    void theSameStatusChangeWrittenByTwoComputersIsOneChange() throws Exception {
        PredictionEvent create = created(nodeA, "goal");
        PredictionEvent byA = changed(nodeA, 2, WorkGroup.Status.COMPLETED);
        PredictionEvent byB = changed(nodeB, 2, WorkGroup.Status.COMPLETED);

        WorkGroupProjection projection = with(create, byA, byB);

        assertEquals(1, projection.groups().size());
        assertEquals(2, projection.group(GROUP).orElseThrow().version());
        assertEquals(WorkGroup.Status.COMPLETED, projection.group(GROUP).orElseThrow().status());
        assertEquals("goal", projection.group(GROUP).orElseThrow().goal());
    }

    @Test
    void completedOutranksAnotherStatusWrittenForTheSameVersionInEitherOrder() throws Exception {
        PredictionEvent create = created(nodeA, "goal");
        PredictionEvent cancelled = changed(nodeA, 2, WorkGroup.Status.CANCELLED);
        PredictionEvent completed = changed(nodeB, 2, WorkGroup.Status.COMPLETED);

        WorkGroupProjection firstOrder = with(create, cancelled, completed);
        WorkGroupProjection secondOrder = with(create, completed, cancelled);

        assertEquals(WorkGroup.Status.COMPLETED, firstOrder.group(GROUP).orElseThrow().status());
        assertEquals(firstOrder.groups(), secondOrder.groups());
    }

    @Test
    void aThirdVersionBeforeTheSecondEndsAtTheThirdInEitherOrder() throws Exception {
        PredictionEvent create = created(nodeA, "goal");
        PredictionEvent second = changed(nodeA, 2, WorkGroup.Status.CANCELLED);
        PredictionEvent third = changed(nodeB, 3, WorkGroup.Status.ACTIVE);

        WorkGroupProjection firstOrder = with(create, second, third);
        WorkGroupProjection secondOrder = with(create, third, second);

        for (WorkGroupProjection projection : List.of(firstOrder, secondOrder)) {
            assertEquals(3, projection.group(GROUP).orElseThrow().version());
            assertEquals(WorkGroup.Status.ACTIVE, projection.group(GROUP).orElseThrow().status());
        }
        assertEquals(firstOrder.groups(), secondOrder.groups());
    }

    @Test
    void aConsumptionAndARevocationOfOneGrantLeaveItUnavailableInEitherOrder() throws Exception {
        PredictionEvent create = created(nodeA, "goal");
        PredictionEvent issue = grantEvent(nodeA, PredictionEventType.LANE_GRANT_ISSUED);
        PredictionEvent consume = grantEvent(nodeB, PredictionEventType.LANE_GRANT_CONSUMED);
        PredictionEvent revoke = grantEvent(nodeA, PredictionEventType.LANE_REVOKED);

        WorkGroupProjection firstOrder = with(create, issue, consume, revoke);
        WorkGroupProjection secondOrder = with(create, issue, revoke, consume);

        for (WorkGroupProjection projection : List.of(firstOrder, secondOrder)) {
            assertFalse(projection.grantAvailable(GRANT));
            assertTrue(projection.grantConsumed(GRANT));
        }
        WorkGroupProjection own = with(create, issue, revoke);
        CoordinationException refused = assertThrows(CoordinationException.class, () -> own.validate(consume));
        assertEquals("LANE_GRANT_REPLAYED", refused.getMessage());
    }

    @Test
    void aGrantActionBeforeItsIssueIsHeldBackUntilTheGrantArrives() throws Exception {
        PredictionEvent create = created(nodeA, "goal");
        PredictionEvent issue = grantEvent(nodeA, PredictionEventType.LANE_GRANT_ISSUED);
        PredictionEvent consume = grantEvent(nodeB, PredictionEventType.LANE_GRANT_CONSUMED);
        PredictionEvent revoke = grantEvent(nodeB, PredictionEventType.LANE_REVOKED);
        WorkGroupProjection projection = with(create);

        for (PredictionEvent early : List.of(consume, revoke)) {
            assertThrows(MissingReferenceException.class, () -> projection.validate(early, ApplyMode.ARRIVAL));
            assertTrue(projection.grants().isEmpty());
        }
        WorkGroupProjection beforeGroup = new WorkGroupProjection();
        assertThrows(MissingReferenceException.class, () -> beforeGroup.validate(issue, ApplyMode.ARRIVAL));
        assertTrue(beforeGroup.groups().isEmpty());

        arrive(projection, issue);
        arrive(projection, consume);
        assertFalse(projection.grantAvailable(GRANT));
        assertEquals(with(create, issue, consume).grants(), projection.grants());
    }

    @Test
    void anAnnouncementOfALaneWhoseGroupHasNotArrivedIsHeldBack() throws Exception {
        PredictionEvent create = created(nodeA, "goal");
        PredictionEvent announce = event(nodeB, PredictionEventType.WORK_INTENT_ANNOUNCED,
                CollaborationCodec.encodeIntent(intent()));
        WorkGroupProjection projection = new WorkGroupProjection();

        assertThrows(MissingReferenceException.class, () -> projection.validate(announce, ApplyMode.ARRIVAL));
        assertTrue(projection.groups().isEmpty());

        arrive(projection, create);
        arrive(projection, announce);
        assertEquals("goal", projection.group(GROUP).orElseThrow().goal());
        assertEquals(1, projection.groups().size());
    }
}
