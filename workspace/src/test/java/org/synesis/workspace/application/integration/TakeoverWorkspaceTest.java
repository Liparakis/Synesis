package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.application.ContinuationResult;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.replication.LeaderReplication.MemberConnection.State;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.test.TestGit;

/**
 * A lane-less agent of a stalled computer takes over the lane of an away computer through
 * {@link WorkspaceCollaborationService#continueLane}: its worktree gets the lane's last snapshot that reached this
 * computer, uncommitted, and the log gets a takeover with no recovery hold (ADR-0098 step 7, designer items 9 to 11).
 */
final class TakeoverWorkspaceTest {

    private String previousHome;
    @TempDir
    Path temp;
    private AwayComputerFixture fixture;

    @BeforeEach
    void setUp(@TempDir Path home) throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        fixture = new AwayComputerFixture(temp.resolve("here"), temp.resolve("away"));
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousHome);
        }
    }

    private ContinuationResult takeOver(UUID grant) throws Exception {
        return new WorkspaceCollaborationService().continueLane(fixture.project.root, AwayComputerFixture.PROVIDER, AwayComputerFixture.TAKER, grant,
                fixture.awayLane.intentId(), fixture.awayLane.version());
    }

    @Test
    void theTakersWorktreeGetsTheSnapshotsChangesUncommittedAndTheSourceLaneEnds() throws Exception {
        String snapshotCommit = fixture.snapshot("snap_away", true);
        fixture.awayIs(State.DISCONNECTED);
        Path worktree = fixture.worktree();
        String headBefore = TestGit.output(worktree, "rev-parse", "HEAD").strip();

        ContinuationResult result = takeOver(null);

        assertTrue(result.callerOwnsSuccessor());
        assertEquals("away work\n", Files.readString(worktree.resolve("src/away.txt")));
        assertEquals(headBefore, TestGit.output(worktree, "rev-parse", "HEAD").strip(), "nothing was committed");
        assertTrue(TestGit.output(worktree, "status", "--porcelain", "--untracked-files=all").contains("src/away.txt"), "the work is a change in the worktree");
        var store = fixture.project.store();
        assertTrue(store.collaborationProjection().intent(fixture.awayLane.intentId()).isEmpty(), "the away lane ended");
        WorkIntent successor = store.collaborationProjection().intent(store.collaborationProjection().successorIntent(fixture.awayLane.intentId()).orElseThrow()).orElseThrow();
        assertEquals(fixture.takerParticipant, successor.participant());
        assertEquals(Participant.State.DETACHED, store.collaborationProjection().participantState("agt_away").orElseThrow());
        var continuation = store.events().stream().filter(event -> event.type() == PredictionEventType.LANE_CONTINUATION_ACCEPTED).toList();
        assertEquals(1, continuation.size());
        assertEquals(LaneContinuation.TAKEOVER_FROM_SNAPSHOT + "snap_away",
                org.synesis.coordination.domain.collaboration.CollaborationCodec.decodeContinuation(continuation.getFirst().payload()).snapshotReference());
        assertFalse(snapshotCommit.isBlank());
    }

    @Test
    void aSecondCallObservesTheSameSuccessorAndWritesNothingMore() throws Exception {
        fixture.snapshot("snap_away", true);
        fixture.awayIs(State.DISCONNECTED);
        takeOver(null);
        long before = fixture.project.store().events().size();

        ContinuationResult again = takeOver(null);

        assertTrue(again.callerOwnsSuccessor());
        assertEquals(before, fixture.project.store().events().size());
    }

    @Test
    void whenNoSnapshotReachedHereTheTakeoverStartsFromTheLanesBase() throws Exception {
        fixture.awayIs(State.DISCONNECTED);
        Path worktree = fixture.worktree();

        takeOver(null);

        assertFalse(Files.exists(worktree.resolve("src/away.txt")));
        var continuation = fixture.project.store().events().stream().filter(event -> event.type() == PredictionEventType.LANE_CONTINUATION_ACCEPTED).findFirst()
                .orElseThrow();
        assertEquals(LaneContinuation.TAKEOVER_FROM_BASE + fixture.base,
                org.synesis.coordination.domain.collaboration.CollaborationCodec.decodeContinuation(continuation.payload()).snapshotReference());
    }

    @Test
    void aSnapshotWhoseCommitDidNotReachHereIsNotUsed() throws Exception {
        fixture.snapshot("snap_missing", false);
        fixture.awayIs(State.DISCONNECTED);

        takeOver(null);

        var continuation = fixture.project.store().events().stream().filter(event -> event.type() == PredictionEventType.LANE_CONTINUATION_ACCEPTED).findFirst()
                .orElseThrow();
        assertEquals(LaneContinuation.TAKEOVER_FROM_BASE + fixture.base,
                org.synesis.coordination.domain.collaboration.CollaborationCodec.decodeContinuation(continuation.payload()).snapshotReference());
    }

    @Test
    void aConnectedComputersLaneIsNotOfferedAndNothingChanges() throws Exception {
        fixture.snapshot("snap_away", true);
        fixture.awayIs(State.CONNECTED);
        Path worktree = fixture.worktree();
        long before = fixture.project.store().events().size();

        IOException refused = assertThrows(IOException.class, () -> takeOver(null));

        assertEquals("TAKEOVER_NOT_OFFERED", refused.getMessage());
        assertFalse(Files.exists(worktree.resolve("src/away.txt")));
        assertEquals(before, fixture.project.store().events().size());
        assertTrue(fixture.project.store().collaborationProjection().intent(fixture.awayLane.intentId()).isPresent());
    }

    @Test
    void aGrantIsNotAcceptedForATakeover() throws Exception {
        fixture.awayIs(State.DISCONNECTED);

        IOException refused = assertThrows(IOException.class, () -> takeOver(UUID.randomUUID()));

        assertEquals("TAKEOVER_GRANT_NOT_ACCEPTED", refused.getMessage());
        assertTrue(fixture.project.store().collaborationProjection().intent(fixture.awayLane.intentId()).isPresent());
    }

    @Test
    void aDirtyWorktreeRefusesTheTakeoverAndLeavesTheLog() throws Exception {
        fixture.snapshot("snap_away", true);
        fixture.awayIs(State.DISCONNECTED);
        Files.writeString(fixture.worktree().resolve("README.md"), "changed\n");
        long before = fixture.project.store().events().size();

        IOException refused = assertThrows(IOException.class, () -> takeOver(null));

        assertTrue(refused.getMessage().startsWith("TAKEOVER_TARGET_DIRTY"), refused.getMessage());
        assertEquals(before, fixture.project.store().events().size());
        assertTrue(fixture.project.store().collaborationProjection().intent(fixture.awayLane.intentId()).isPresent());
    }
}
