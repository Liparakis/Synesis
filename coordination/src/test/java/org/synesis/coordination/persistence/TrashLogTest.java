package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.TrashService;
import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.trash.TrashEntries.TrashEmptied;
import org.synesis.coordination.domain.trash.TrashEntries.TrashRestored;
import org.synesis.coordination.domain.trash.TrashEntries.WorkTrashed;
import org.synesis.coordination.domain.trash.TrashProjection;
import org.synesis.link.identity.NodeIdentity;

/**
 * The trash as the log says it (ADR-0098 step 7, designer items 12 to 16): trashed once, restored as often as people
 * ask, emptied for good, and the arrival of entries in any order.
 */
final class TrashLogTest {

    private static final UUID GROUP = UUID.fromString("70000000-0000-4000-8000-000000000001");

    @TempDir
    Path root;

    private NodeIdentity self;
    private PredictionEventStore store;
    private WorkIntent lane;
    private TrashService trash;

    private void prepare() throws Exception {
        UUID project = UUID.randomUUID();
        self = NodeIdentity.generate();
        store = new PredictionEventStore(root, project);
        new WorkGroupService(store, self).create(new WorkGroup(GROUP, project, "ship it", "all green", 1,
                WorkGroup.Status.ACTIVE, ReviewPolicy.mandatoryIndependent()));
        UUID intentId = UUID.randomUUID();
        lane = new WorkIntent(intentId, project, "agt-owner", "codex", UUID.randomUUID(), "goal", "acceptance", "base",
                List.of(ResourceSelector.pathSubtree("src")), 1, GROUP, WorkIntent.defaultAuthorityLineage(intentId),
                WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(store, self).announce(lane).acquired());
        trash = new TrashService(store, self);
    }

    private PredictionEventStore reread() throws Exception {
        return new PredictionEventStore(root, store.projectId());
    }

    private WorkTrashed work() {
        return new WorkTrashed(lane.intentId(), lane.version(), lane.taskId(), GROUP, COMMIT, WorkTrashed_REASON);
    }

    private static final String COMMIT = "a".repeat(40);
    private static final String WorkTrashed_REASON = org.synesis.coordination.domain.trash.TrashEntries.TAKEN_OVER;

    @Test
    void workIsTrashedOnceAndASecondCallChangesNothing() throws Exception {
        prepare();

        assertTrue(trash.trash(work()));
        assertFalse(trash.trash(work()), "already in the trash");

        List<TrashProjection.Item> items = reread().trashProjection().items();
        assertEquals(1, items.size());
        assertEquals(self.nodeId(), items.getFirst().writerNodeId());
        assertEquals(COMMIT, items.getFirst().trashed().commit());
        assertFalse(items.getFirst().emptied());
    }

    @Test
    void restoringTwiceMakesTwoGroupsThatKeepTheOriginalPolicyAndAreTheSameAfterARepeatedGroupId() throws Exception {
        prepare();
        trash.trash(work());

        UUID first = trash.restore(lane.intentId(), lane.version());
        UUID second = trash.restore(lane.intentId(), lane.version());

        assertNotEquals(first, second);
        TrashProjection.Item item = reread().trashProjection().item(lane.intentId(), lane.version()).orElseThrow();
        assertEquals(List.of(first, second), item.restoredInto());
        WorkGroup restored = reread().workGroupProjection().group(first).orElseThrow();
        assertEquals(WorkGroup.Status.ACTIVE, restored.status());
        assertEquals(ReviewPolicy.mandatoryIndependent(), restored.reviewPolicy());
        assertEquals("all green", restored.acceptance());
        assertTrue(restored.goal().contains("ship it"));
        assertEquals(item, reread().trashProjection().restoredInto(second).orElseThrow());
    }

    @Test
    void emptiedWorkCannotBeRestoredOrEmptiedAgainAndTheRecordStays() throws Exception {
        prepare();
        trash.trash(work());
        trash.empty(lane.intentId(), lane.version());

        assertEquals("TRASH_EMPTIED", assertThrows(IOException.class, () -> trash.restore(lane.intentId(), lane.version())).getMessage());
        assertEquals("TRASH_ALREADY_EMPTIED", assertThrows(IOException.class, () -> trash.empty(lane.intentId(), lane.version())).getMessage());
        assertTrue(reread().trashProjection().item(lane.intentId(), lane.version()).orElseThrow().emptied());
        assertEquals(1, reread().trashProjection().items().size());
    }

    @Test
    void restoringOrEmptyingWorkThatWasNeverTrashedIsRefused() throws Exception {
        prepare();

        assertEquals("TRASH_ITEM_NOT_FOUND", assertThrows(IOException.class, () -> trash.restore(lane.intentId(), 1)).getMessage());
        assertEquals("TRASH_ITEM_NOT_FOUND", assertThrows(IOException.class, () -> trash.empty(lane.intentId(), 1)).getMessage());
    }

    @Test
    void aLaneThatIsNotThisComputersCannotBeTrashedByIt() throws Exception {
        prepare();
        TrashService other = new TrashService(store, NodeIdentity.generate());

        assertEquals("TRASH_LANE_NOT_THIS_COMPUTERS", assertThrows(IOException.class, () -> other.trash(work())).getMessage());
        assertTrue(reread().trashProjection().items().isEmpty());
    }

    @Test
    void aLaneThatWasNeverOnTheLogCannotBeTrashed() throws Exception {
        prepare();
        WorkTrashed ghost = new WorkTrashed(UUID.randomUUID(), 1, UUID.randomUUID(), GROUP, COMMIT, "TAKEN_OVER");

        assertEquals("TRASH_LANE_NOT_FOUND", assertThrows(IOException.class, () -> trash.trash(ghost)).getMessage());
    }

    // ---- arrival order, on a bare projection ----

    private static final UUID PROJECT = UUID.fromString("70000000-0000-4000-8000-000000000009");
    private NodeIdentity writer;
    private Projections view;
    private long sequence;

    @org.junit.jupiter.api.BeforeEach
    void bare() throws Exception {
        writer = NodeIdentity.generate();
        view = new Projections(new ProjectId(PROJECT));
    }

    private Projections.Arrival arrive(PredictionEventType type, byte[] payload) throws Exception {
        sequence++;
        return view.arrive(PredictionEvent.create(PROJECT, UUID.randomUUID(), sequence, type, writer.nodeId(), payload,
                new byte[32], writer, sequence), sequence);
    }

    @Test
    void aRestoreThatArrivesBeforeTheTrashingWaitsAndIsNotApplied() throws Exception {
        UUID laneId = UUID.randomUUID();

        Projections.Arrival early = arrive(PredictionEventType.TRASH_RESTORED, new TrashRestored(laneId, 1, UUID.randomUUID()).encode());

        assertFalse(early.applied(), "waits for the entry it names: " + early);
        assertTrue(view.trash().items().isEmpty());
    }

    @Test
    void anEmptyThatArrivesTwiceChangesNothingTheSecondTime() throws Exception {
        UUID laneId = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        // The trashing is applied straight to the trash projection: its lane belongs to another log not modelled here.
        view.trash().apply(PredictionEvent.create(PROJECT, UUID.randomUUID(), 1, PredictionEventType.WORK_TRASHED, writer.nodeId(),
                new WorkTrashed(laneId, 2, task, GROUP, COMMIT, "DISPUTE_GIVEN_UP").encode(), new byte[32], writer, 1));
        sequence = 1;

        assertTrue(arrive(PredictionEventType.TRASH_EMPTIED, new TrashEmptied(laneId, 2).encode()).applied());
        arrive(PredictionEventType.TRASH_EMPTIED, new TrashEmptied(laneId, 2).encode());

        assertTrue(view.trash().item(laneId, 2).orElseThrow().emptied());
        assertEquals(1, view.trash().items().size());
    }

    @Test
    void aRestoreOfEmptiedWorkIsRefusedOnTheRestorersOwnAppendButKeptWhenItArrives() throws Exception {
        UUID laneId = UUID.randomUUID();
        view.trash().apply(PredictionEvent.create(PROJECT, UUID.randomUUID(), 1, PredictionEventType.WORK_TRASHED, writer.nodeId(),
                new WorkTrashed(laneId, 1, UUID.randomUUID(), GROUP, COMMIT, "LATE_FOR_MET_GOAL").encode(), new byte[32], writer, 1));
        view.trash().apply(PredictionEvent.create(PROJECT, UUID.randomUUID(), 2, PredictionEventType.TRASH_EMPTIED, writer.nodeId(),
                new TrashEmptied(laneId, 1).encode(), new byte[32], writer, 2));
        sequence = 2;

        sequence++;
        PredictionEvent restore = PredictionEvent.create(PROJECT, UUID.randomUUID(), sequence, PredictionEventType.TRASH_RESTORED,
                writer.nodeId(), new TrashRestored(laneId, 1, UUID.randomUUID()).encode(), new byte[32], writer, sequence);

        assertEquals("TRASH_EMPTIED", assertThrows(IOException.class, () -> view.check(restore, sequence)).getMessage());
    }

    @Test
    void trashPayloadsRoundTripAndRejectTrailingBytes() throws Exception {
        UUID laneId = UUID.randomUUID();
        WorkTrashed trashed = new WorkTrashed(laneId, 3, UUID.randomUUID(), GROUP, COMMIT, "SOME_FUTURE_REASON");

        assertEquals(trashed, WorkTrashed.decode(trashed.encode()));
        assertEquals(new TrashEmptied(laneId, 3), TrashEmptied.decode(new TrashEmptied(laneId, 3).encode()));
        byte[] padded = java.util.Arrays.copyOf(new TrashEmptied(laneId, 3).encode(), new TrashEmptied(laneId, 3).encode().length + 1);
        assertThrows(IOException.class, () -> TrashEmptied.decode(padded));
        assertThrows(IllegalArgumentException.class, () -> new TrashEmptied(laneId, -1));
        assertThrows(IOException.class, () -> WorkTrashed.decode(new TrashEmptied(laneId, 3).encode()));
    }

    /** Coordinator change 5 (commit must be a 40 or 64 hex commit id); fails until that production edit lands. */
    @Test
    void aTrashedCommitMustBeAHexCommitId() {
        UUID laneId = UUID.randomUUID();
        assertThrows(RuntimeException.class, () -> new WorkTrashed(laneId, 1, UUID.randomUUID(), GROUP, "not-hex", "r"));
        assertThrows(RuntimeException.class, () -> new WorkTrashed(laneId, 1, UUID.randomUUID(), GROUP, "abc123", "r"));
        assertEquals("b".repeat(64), new WorkTrashed(laneId, 1, UUID.randomUUID(), GROUP, "b".repeat(64), "r").commit());
    }
}
