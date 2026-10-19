package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.coordination.TestComputers.Computer;
import org.synesis.cli.command.coordination.TestComputers.Response;

import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.prediction.PredictionEventType;

import org.synesis.workspace.application.collaboration.AwayLanes;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Scenario S4 of ADR-0102 item 1 (ADR-0098 step 7, items 9 to 16): the one open lane of a goal belongs to an agent of the
 * computer that is cut off, nothing else is left to do on the other computer, and that computer's lane-less agent takes the
 * lane over, finishes it, and meets the first agent after the heal: the first agent's version is in the trash, listed, and
 * it is not on {@code main}. The trash is then restored and emptied the way a person does.
 *
 * <p>No timer decides that a computer is away (item 9): the other computer's record of its member sessions does, and it
 * says so as soon as the Link sessions end. The scenario therefore moves no clock; it waits for the record.</p>
 *
 * <p>The taker is the friend, the computer that joined the project. The two disabled tests are product defects this scenario
 * found (2026-10-19), each as the scenario with the step that exposes it.</p>
 */
@Timeout(300)
final class TakeoverScenarioTest {

    /**
     * What the away computer's agent hands in before the other computer takes its lane over. A snapshot handed in while
     * linked does not reach the other computer until a reviewer there fetches it (Code over Link), so a takeover "from the
     * last handed-in snapshot that reached this computer" needs a review begun and abandoned; that variant stays at source
     * level ({@code AwayLanesTest}).
     */
    private enum Handed {
        /** Nothing: the takeover starts from the lane's base. */
        NOTHING,
        /** A snapshot, while apart, so that it arrives after the goal is met. */
        WHILE_APART
    }

    /** The agents and computers of one takeover after the heal. */
    private record Scene(Computer away, Computer here, ScenarioAgent awayAgent, ScenarioAgent taker, ScenarioAgent second) {
    }

    @TempDir
    Path temp;
    private ScenarioLab lab;

    @BeforeEach
    void linkedComputers() throws Exception {
        lab = ScenarioLab.open(temp);
    }

    @AfterEach
    void stop() {
        lab.close();
    }

    @Test
    void anAwayComputersLaneIsTakenOverFromItsBaseFinishedAndThenItsVersionIsInTheTrashAndMainEndsEqual() throws Exception {
        Scene scene = takeOverAndHeal(lab.owner, lab.friend, Handed.NOTHING);

        assertTrashHoldsTheAwayVersion(scene, "half of it, by away-1");
        assertEverythingMeets(scene);
    }

    @Test
    void aSnapshotTheAwayAgentHandedInWhileApartArrivesAfterTheGoalIsMetAndGoesToTheTrashNotToMain() throws Exception {
        Scene scene = takeOverAndHeal(lab.owner, lab.friend, Handed.WHILE_APART);

        assertTrashHoldsTheAwayVersion(scene, "half of it, by away-1");
        assertEverythingMeets(scene);
        assertEquals(1L, scene.away().store().taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.provenance().participant().equals(scene.awayAgent().participant())).count(),
                "the late snapshot arrived on both computers");
        assertEquals("all of it, by here-1", lab.onBranch(scene.here(), "result.txt").strip(), "the late snapshot is not on main");
    }

    @Test
    void aPersonRestoresTheTrashedWorkIntoAWorkGroupOfItsOwnAndThenEmptiesItForGood() throws Exception {
        Scene scene = takeOverAndHeal(lab.owner, lab.friend, Handed.NOTHING);
        Map<String, Object> trashed = assertTrashHoldsTheAwayVersion(scene, "half of it, by away-1");
        String lane = String.valueOf(trashed.get("laneId"));
        Number epoch = (Number) trashed.get("epoch");
        String ref = "refs/synesis/trash/" + lane + "/" + epoch.longValue();

        // Restore opens a work group of its own and records it.
        Response restored = scene.away().post("restore-trash", Map.of("laneId", lane, "epoch", epoch));
        assertEquals(200, restored.status(), restored.toString());
        UUID group = UUID.fromString(String.valueOf(restored.body().get("workGroupId")));
        assertEquals(1L, lab.entriesWrittenUnchecked(scene.away(), scene.away(), PredictionEventType.TRASH_RESTORED));
        assertEquals(WorkGroup.Status.ACTIVE, scene.away().store().workGroupProjection().group(group).orElseThrow().status());
        assertEquals(List.of(group.toString()), lab.trash(scene.away()).getFirst().get("restoredInto"));

        // Emptying needs the person's confirmation, and is then the one deletion of work: the entry first, then the ref.
        Response unconfirmed = scene.away().post("empty-trash", Map.of("laneId", lane, "epoch", epoch));
        assertEquals(400, unconfirmed.status(), unconfirmed.toString());
        assertTrue(refExists(scene.away(), ref), "an unconfirmed empty deleted nothing");
        Response emptied = scene.away().post("empty-trash", Map.of("laneId", lane, "epoch", epoch, "confirmed", true));
        assertEquals(200, emptied.status(), emptied.toString());
        assertEquals(1L, lab.entriesWrittenUnchecked(scene.away(), scene.away(), PredictionEventType.TRASH_EMPTIED));
        assertFalse(refExists(scene.away(), ref), "the ref is deleted on the computer that emptied it");
        lab.await("the other computer lists the work as emptied", () -> Boolean.TRUE.equals(lab.trash(scene.here()).getFirst().get("emptied")));
        assertFalse(refExists(scene.here(), ref), "and on the other computer, if it held a copy");
    }

    /**
     * DEFECT 5 (found 2026-10-19, in the first run of this scenario; ADR-0098 step 7 item 12 says the returning agent finds
     * {@code laneTakenOver} in every answer). Failing step: after the heal, the away agent's {@code get_next_action} answers
     * {@code retry_required / workspace_stale / ensure_session} with no {@code laneTakenOver}, because {@code main} moved under
     * its workspace. Following that answer (the agent calls {@code ensure_session}) gives it a new session whose participant
     * is not the one the taken lane belongs to, and that session's answers are {@code NO_OPEN_WORK} with no
     * {@code laneTakenOver} and no {@code trashedWork} for the 60 s it was asked, so the agent is never told to stop and the
     * trash holds its work without it knowing. Expected: the notice comes with, or before, the refresh, or the new session of
     * the same connection is told of the lane its predecessor held.
     */
    @Test
    @Disabled("DEFECT 5: a returning agent whose workspace is stale is never told its lane was taken over; see the Javadoc")
    @SuppressWarnings("unchecked")
    void theReturningAgentWhoseWorkspaceIsStaleIsStillToldItsLaneWasTakenOver() throws Exception {
        Scene scene = takeOverAndHeal(lab.owner, lab.friend, Handed.NOTHING);

        Map<String, Object> told = scene.awayAgent().next();
        Map<String, Object> notice = ScenarioAgent.inner(told).get("laneTakenOver") instanceof Map<?, ?> found ? (Map<String, Object>) found : null;
        assertNotNull(notice, "the away agent is told its lane was taken over: " + told);
        assertEquals(scene.taker().participant(), notice.get("takenOverBy"));
    }

    /**
     * DEFECT 6 (found 2026-10-19, in the first run of this scenario with the owner as the taker): the computer that created
     * the project never records its joiner's session, so it never judges the joiner away and never offers its lane. A session
     * that is attached before the join is accepted is not a member's (the exchange keeps it so that the accept needs no
     * redial), and {@code LeaderReplication.attach} records and watches only the sessions of members. When the owner accepts,
     * that session stays and is never recorded, so the owner's {@code replication.members} is never written, and
     * {@code AwayLanes.away} with no readable record judges nobody away (item 9: "with no readable record nobody is away").
     * The failing step is the first wait of step 2: the owner's record stays {@code none} while the joiner's record of the
     * owner says DISCONNECTED. Expected: the record names the joiner from the accept on, so the owner takes a lost joiner's
     * lane over as the joiner takes the owner's.
     */
    @Test
    @Disabled("DEFECT 6: the owner never records the joiner's session, so it never judges the joiner away; see the Javadoc")
    void theComputerThatCreatedTheProjectTakesAJoinersLaneOverToo() throws Exception {
        Scene scene = takeOverAndHeal(lab.friend, lab.owner, Handed.NOTHING);

        assertTrashHoldsTheAwayVersion(scene, "half of it, by away-1");
        assertEverythingMeets(scene);
    }

    private static boolean refExists(Computer computer, String ref) {
        try {
            return !GitProcessRunner.runOptional(computer.location.root(), "rev-parse", "--verify", "-q", ref).isBlank();
        } catch (Exception unreadable) {
            return false;
        }
    }

    /**
     * Steps 1 to 4: the away agent starts the goal's one file, the Link fails, the other computer's lane-less agent takes the
     * lane over, finishes it and has it integrated on its own main, and the Link comes back.
     */
    private Scene takeOverAndHeal(Computer away, Computer here, Handed handed) throws Exception {
        ScenarioAgent awayAgent = lab.agent(away, "away-1");
        ScenarioAgent taker = lab.agent(here, "here-1");
        ScenarioAgent second = lab.agent(here, "here-2");

        // Step 1. Linked, the away computer's agent claims the one file of the goal and starts on it; the other computer's agents hold no lane.
        awayAgent.claim("Write result.txt", "result.txt");
        awayAgent.create("result.txt", "half of it, by away-1\n");
        taker.join();
        second.join();
        lab.await("the other computer's log holds the away agent's lane", () -> {
            try {
                return here.store().collaborationProjection().activeIntents().stream().anyMatch(intent -> intent.participant().equals(awayAgent.participant()));
            } catch (Exception unreadable) {
                return false;
            }
        });

        // Step 2. The Link fails. The other computer's record of member sessions says the away computer is away, and its lane-less agent is offered the lane.
        lab.split();
        lab.await("the other computer records the away computer as not connected", () -> AwayLanes.away(here.location.coordinationDirectory(), here.node.nodeId()).test(away.node.nodeId()));
        lab.await("the taker is offered the away lane", () -> !taker.takeoversOffered().isEmpty());
        assertEquals("AWAY", taker.takeoversOffered().getFirst().get("formerOwnerState"));
        if (handed == Handed.WHILE_APART) {
            awayAgent.publish();
        }

        // Step 3. It takes the lane over, finishes it, and its snapshot is reviewed and integrated on the other computer alone.
        Map<String, Object> taken = taker.takeOver();
        assertFalse(Boolean.TRUE.equals(taken.get("isError")), taken.toString());
        assertEquals(1, lab.entriesWrittenUnchecked(here, here, PredictionEventType.LANE_CONTINUATION_ACCEPTED));
        assertFalse(String.valueOf(taker.call("read_file", Map.of("path", "result.txt"))).contains("half of it"), "the takeover starts from the lane's base");
        taker.create("result.txt", "all of it, by here-1\n");
        taker.publish();
        second.claim("Write note.txt", "note.txt");
        second.create("note.txt", "written by here-2\n");
        second.publish();
        lab.drive("the taker's file is on the other computer's main and the goal is met there", () -> lab.hasOnBranch(here, "result.txt") && goalMet(here), taker, second);
        assertFalse(lab.hasOnBranch(away, "result.txt"), "apart, the away computer does not hold it");

        // Step 4. The Link comes back.
        lab.heal();
        lab.await("both computers hold the same main", lab::mainsEqual);
        return new Scene(away, here, awayAgent, taker, second);
    }

    private static boolean goalMet(Computer computer) {
        try {
            var groups = computer.store().workGroupProjection().groups();
            return !groups.isEmpty() && groups.stream().allMatch(group -> group.status() == WorkGroup.Status.COMPLETED);
        } catch (Exception unreadable) {
            return false;
        }
    }

    /** The away computer lists the lane's version in the trash, as its own work and kept as the agent left it; returns that listing. */
    private Map<String, Object> assertTrashHoldsTheAwayVersion(Scene scene, String content) throws Exception {
        lab.await("the away computer lists its version in the trash", () -> lab.trash(scene.away()).size() == 1);
        Map<String, Object> trashed = lab.trash(scene.away()).getFirst();
        assertEquals("TAKEN_OVER", trashed.get("reason"));
        assertEquals(scene.away().node.nodeId(), trashed.get("trashedBy"));
        assertEquals(content, GitProcessRunner.run(scene.away().location.root(), "show", trashed.get("commit") + ":result.txt").strip());
        assertEquals(1L, lab.entriesWrittenUnchecked(scene.away(), scene.away(), PredictionEventType.WORK_TRASHED));
        lab.await("the other computer lists the same trash", () -> lab.trash(scene.here()).size() == 1);
        assertEquals(trashed.get("laneId"), lab.trash(scene.here()).getFirst().get("laneId"));
        return trashed;
    }

    /** The goal is met on both computers, main is equal and holds the taker's work, and the logs are byte-identical. */
    private void assertEverythingMeets(Scene scene) throws Exception {
        assertTrue(lab.mainsEqual());
        assertEquals("all of it, by here-1", lab.onBranch(scene.away(), "result.txt").strip());
        assertEquals("written by here-2", lab.onBranch(scene.away(), "note.txt").strip());
        lab.await("the goal is met on the away computer", () -> goalMet(scene.away()));
        lab.await("each computer holds every entry the other wrote", () -> {
            try {
                lab.assertLogsIdentical();
                return true;
            } catch (AssertionError | java.io.IOException notYet) {
                return false;
            }
        });
        lab.assertCheckoutsMatchBranch();
    }
}
