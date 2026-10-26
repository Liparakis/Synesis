package org.synesis.workspace.application.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.application.TrashService;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.trash.TrashEntries;
import org.synesis.coordination.domain.trash.TrashProjection;
import org.synesis.workspace.application.task.SnapshotRefs;
import org.synesis.workspace.test.TakenOverFixture;
import org.synesis.workspace.test.TestGit;

/**
 * The trash on this computer (ADR-0098 step 7, designer items 12 to 16): what a sweep keeps and why, that a second
 * sweep keeps nothing again, that emptying is the one deletion, and that restored work is applied to a new worktree.
 */
final class WorkTrashTest {

    @TempDir
    Path temp;

    private TakenOverFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new TakenOverFixture(temp);
    }

    private void sweep() throws Exception {
        WorkTrash.sweep(fixture.location, fixture.store(), fixture.identity);
    }

    private TrashProjection trash() throws Exception {
        return fixture.store().trashProjection();
    }

    private String ref(long epoch) {
        return SnapshotRefs.trashRef(fixture.lane.intentId(), epoch);
    }

    private String refCommit(String ref) throws Exception {
        return TestGit.output(fixture.root, "rev-parse", "--verify", "-q", ref).strip();
    }

    private boolean refExists(String ref) throws Exception {
        try {
            return !refCommit(ref).isBlank();
        } catch (IOException absent) {
            return false;
        }
    }

    @Test
    void aSweepBeforeAnyTakeoverKeepsNothing() throws Exception {
        fixture.dirtyOwnersWorktree();

        sweep();

        assertEquals(List.of(), trash().items());
    }

    @Test
    void aTakenOverLanesWorktreeIsKeptWithItsUncommittedAndUntrackedFilesRefThenEntryAndOnlyOnce() throws Exception {
        fixture.dirtyOwnersWorktree();
        Path worktree = fixture.worktree(TakenOverFixture.OWNER);
        String head = TestGit.output(worktree, "rev-parse", "HEAD").strip();
        fixture.otherComputerTakesTheLaneOver();

        sweep();

        List<TrashProjection.Item> items = trash().items();
        assertEquals(1, items.size());
        TrashProjection.Item item = items.getFirst();
        assertEquals(TrashEntries.TAKEN_OVER, item.trashed().reason());
        assertEquals(fixture.lane.intentId(), item.trashed().laneId());
        assertEquals(fixture.lane.version(), item.trashed().epoch());
        assertEquals(fixture.identity.nodeId(), item.writerNodeId());
        String kept = item.trashed().commit();
        assertEquals(kept, refCommit(ref(fixture.lane.version())), "the entry names the commit the ref holds");
        assertEquals("base\nowner edit", TestGit.output(fixture.root, "show", kept + ":README.md").strip().replace("\r\n", "\n"));
        assertEquals("class Board {}", TestGit.output(fixture.root, "show", kept + ":src/Board.java").strip());
        assertEquals("deep", TestGit.output(fixture.root, "show", kept + ":src/notes/deep.txt").strip());
        assertEquals(head, TestGit.output(fixture.root, "rev-parse", kept + "^").strip(), "on top of what the lane had committed");
        assertEquals(head, TestGit.output(worktree, "rev-parse", "HEAD").strip(), "the worktree itself was not committed to");
        assertTrue(Files.exists(worktree.resolve("src/notes/deep.txt")), "and keeps its files");

        long entries = fixture.store().events().size();
        sweep();
        assertEquals(1, trash().items().size());
        assertEquals(kept, trash().items().getFirst().trashed().commit());
        assertEquals(entries, fixture.store().events().size(), "a second sweep writes nothing");
    }

    @Test
    void aRefLeftByACrashBeforeTheEntryIsNamedByTheNextSweep() throws Exception {
        fixture.dirtyOwnersWorktree();
        fixture.otherComputerTakesTheLaneOver();
        String left = fixture.lane.baseCommit();
        TestGit.run(fixture.root, "update-ref", ref(fixture.lane.version()), left);

        sweep();

        assertEquals(left, trash().items().getFirst().trashed().commit());
    }

    @Test
    void aLaneThatIsStillOpenIsNotTrashed() throws Exception {
        fixture.dirtyOwnersWorktree();
        fixture.copyHereToRemote();
        fixture.copyRemoteToHere();

        sweep();

        assertEquals(List.of(), trash().items());
    }

    @Test
    void theNewestSnapshotLeftInACompletedGroupIsLateForTheMetGoal() throws Exception {
        fixture.publishSnapshot("snap_late");
        fixture.collaboration.closeWorkGroup(fixture.root, fixture.groupId, WorkGroup.Status.COMPLETED, 1);

        sweep();

        TrashProjection.Item item = trash().items().getFirst();
        assertEquals(TrashEntries.LATE_FOR_MET_GOAL, item.trashed().reason());
        assertEquals(refCommit(ref(fixture.lane.version())), item.trashed().commit());
        assertEquals(1, trash().items().size());
    }

    /** s5-run21, snap_48308bf3: a late snapshot of a group that completed waited for review for ever. */
    @Test
    void aLateSnapshotOnceTrashedNoLongerWaitsForReview() throws Exception {
        fixture.publishSnapshot("snap_late_review");
        assertEquals(List.of("snap_late_review"), pendingReviews());
        fixture.collaboration.closeWorkGroup(fixture.root, fixture.groupId, WorkGroup.Status.COMPLETED, 1);

        sweep();

        assertEquals(List.of(), pendingReviews());
        assertEquals(java.util.Optional.of(org.synesis.coordination.domain.task.TaskCompletionState.CANCELLED),
                fixture.store().taskCompletionProjection().snapshotState("snap_late_review"));
    }

    private List<String> pendingReviews() throws Exception {
        return fixture.store().taskCompletionProjection().pendingReviewSnapshots().stream()
                .map(org.synesis.coordination.domain.task.TaskSnapshotRecord::snapshotId).toList();
    }

    @Test
    void aRejectedSnapshotLeftInACompletedGroupIsGivenUp() throws Exception {
        fixture.publishSnapshot("snap_given_up");
        fixture.reject("snap_given_up");
        fixture.collaboration.closeWorkGroup(fixture.root, fixture.groupId, WorkGroup.Status.COMPLETED, 1);

        sweep();

        assertEquals(TrashEntries.DISPUTE_GIVEN_UP, trash().items().getFirst().trashed().reason());
    }

    /** Coordinator change 6: a cancelled group's leftover snapshot is GROUP_CANCELLED. */
    @Test
    void aSnapshotLeftInACancelledGroupIsTrashedAsGroupCancelled() throws Exception {
        fixture.publishSnapshot("snap_cancelled");
        fixture.collaboration.closeWorkGroup(fixture.root, fixture.groupId, WorkGroup.Status.CANCELLED, 1);

        sweep();

        assertEquals("GROUP_CANCELLED", trash().items().getFirst().trashed().reason());
    }

    @Test
    void nothingIsTrashedWhileTheGroupIsActive() throws Exception {
        fixture.publishSnapshot("snap_active");

        sweep();

        assertEquals(List.of(), trash().items());
    }

    @Test
    void emptyingDeletesTheRefAndTheEntryRemainsAndASweepDoesNotKeepItAgain() throws Exception {
        fixture.dirtyOwnersWorktree();
        fixture.otherComputerTakesTheLaneOver();
        sweep();
        String ref = ref(fixture.lane.version());
        assertTrue(refExists(ref));

        WorkTrash.empty(fixture.location, fixture.identity, fixture.lane.intentId(), fixture.lane.version());
        sweep();

        assertFalse(refExists(ref));
        assertTrue(trash().items().getFirst().emptied());
        assertEquals(1, trash().items().size());
        assertEquals("TRASH_ALREADY_EMPTIED", assertThrows(IOException.class,
                () -> WorkTrash.empty(fixture.location, fixture.identity, fixture.lane.intentId(), fixture.lane.version())).getMessage());
    }

    @Test
    void aSweepDeletesTheRefOfWorkAnotherComputerEmptied() throws Exception {
        fixture.dirtyOwnersWorktree();
        fixture.otherComputerTakesTheLaneOver();
        sweep();
        String ref = ref(fixture.lane.version());
        fixture.copyHereToRemote();
        new TrashService(fixture.remoteStore, fixture.remote).empty(fixture.lane.intentId(), fixture.lane.version());
        fixture.copyRemoteToHere();
        assertTrue(refExists(ref), "the entry arrived; the ref is still here");

        sweep();

        assertFalse(refExists(ref));
    }

    @Test
    void restoredWorkIsAppliedUncommittedToTheNewLanesWorktreeAndRepeatingItChangesNothing() throws Exception {
        fixture.dirtyOwnersWorktree();
        fixture.otherComputerTakesTheLaneOver();
        sweep();
        UUID restoreGroup = WorkTrash.restore(fixture.location, fixture.identity, fixture.lane.intentId(), fixture.lane.version());
        Path worktree = fixture.worktree(TakenOverFixture.RESTORER);
        String head = TestGit.output(worktree, "rev-parse", "HEAD").strip();

        assertTrue(WorkTrash.applyRestored(fixture.location, fixture.store(), restoreGroup, worktree));
        assertTrue(WorkTrash.applyRestored(fixture.location, fixture.store(), restoreGroup, worktree));

        assertEquals("base\nowner edit\n", Files.readString(worktree.resolve("README.md")).replace("\r\n", "\n"));
        assertEquals("deep\n", Files.readString(worktree.resolve("src/notes/deep.txt")).replace("\r\n", "\n"));
        assertEquals(head, TestGit.output(worktree, "rev-parse", "HEAD").strip(), "nothing was committed");
    }

    @Test
    void aGroupThatRestoresNothingAppliesNothing() throws Exception {
        Path worktree = fixture.worktree(TakenOverFixture.RESTORER);

        assertFalse(WorkTrash.applyRestored(fixture.location, fixture.store(), UUID.randomUUID(), worktree));

        assertEquals("", TestGit.output(worktree, "status", "--porcelain", "--untracked-files=all").lines().filter(line -> !line.contains(".synesis")).findFirst().orElse(""));
    }

    @Test
    void restoringEmptiedWorkIsRefused() throws Exception {
        fixture.dirtyOwnersWorktree();
        fixture.otherComputerTakesTheLaneOver();
        sweep();
        WorkTrash.empty(fixture.location, fixture.identity, fixture.lane.intentId(), fixture.lane.version());

        assertEquals("TRASH_EMPTIED", assertThrows(IOException.class,
                () -> WorkTrash.restore(fixture.location, fixture.identity, fixture.lane.intentId(), fixture.lane.version())).getMessage());
    }

    @Test
    void aDirtyWorktreeRefusesTheRestoreAndIsLeftAsItWas() throws Exception {
        fixture.dirtyOwnersWorktree();
        fixture.otherComputerTakesTheLaneOver();
        sweep();
        UUID restoreGroup = WorkTrash.restore(fixture.location, fixture.identity, fixture.lane.intentId(), fixture.lane.version());
        Path worktree = fixture.worktree(TakenOverFixture.RESTORER);
        Files.writeString(worktree.resolve("README.md"), "someone else's edit\n");

        assertThrows(IOException.class, () -> WorkTrash.applyRestored(fixture.location, fixture.store(), restoreGroup, worktree));

        assertEquals("someone else's edit\n", Files.readString(worktree.resolve("README.md")).replace("\r\n", "\n"));
        assertFalse(Files.exists(worktree.resolve("src/notes/deep.txt")));
    }

    @Test
    void describeNamesEachPieceOfWorkItsReasonItsRestoresAndWhetherItIsEmptied() throws Exception {
        fixture.dirtyOwnersWorktree();
        fixture.otherComputerTakesTheLaneOver();
        sweep();
        UUID restored = WorkTrash.restore(fixture.location, fixture.identity, fixture.lane.intentId(), fixture.lane.version());

        List<Map<String, Object>> described = WorkTrash.describe(trash());

        assertEquals(1, described.size());
        Map<String, Object> entry = described.getFirst();
        assertEquals(fixture.lane.intentId().toString(), entry.get("laneId"));
        assertEquals(fixture.lane.version(), entry.get("epoch"));
        assertEquals("TAKEN_OVER", entry.get("reason"));
        assertEquals(List.of(restored.toString()), entry.get("restoredInto"));
        assertEquals(false, entry.get("emptied"));
        assertEquals(List.of(), WorkTrash.describe(new TrashProjection()));
    }
}
