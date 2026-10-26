package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.coordination.TestComputers.Computer;

import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.ProjectIntegrationLock;

/**
 * Scenario S2 of ADR-0102 item 1: both computers change the same file while the Link is down. After the heal the higher
 * node ID re-merges, Git cannot merge the file, and the combine lane (ADR-0098 step 5, designer item 22) is offered to
 * the agents of the re-merging computer that hold no lane: one takes it and the other is refused, with an answer that is
 * safe to retry. After the combine, both copies of {@code main} are equal.
 *
 * <p>The two disabled tests are the product defects this scenario found on its first rounds (2026-10-19); each one is
 * the same scenario with the step that exposes the defect, asserting the behaviour ADR-0098 describes.</p>
 */
@Timeout(240)
final class OverlapAndCombineScenarioTest {

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

    /** The scene at the moment the combine is offered: who is where, and the arguments that take the combine. */
    private record Scene(Computer high, Computer low, ScenarioAgent high1, ScenarioAgent high2, ScenarioAgent low1, ScenarioAgent low2, ScenarioAgent combiner1, ScenarioAgent combiner2,
            Map<String, Object> ensureSession) {

        ScenarioAgent[] producers() {
            return new ScenarioAgent[] {high1, high2, low1, low2};
        }

        ScenarioAgent[] everyone() {
            return new ScenarioAgent[] {high1, high2, low1, low2, combiner1, combiner2};
        }
    }

    /**
     * Steps 1 to 5: split, change the same line apart on both computers, integrate apart, heal, and wait until the higher
     * computer offers the combine to both of its lane-less agents.
     *
     * @param originalReviewerRefreshes whether the higher computer's first agent, which reviewed its second agent's snapshot,
     *                                  refreshes its stale workspace with {@code ensure_session} as {@code get_next_action}
     *                                  recommends, which gives it a new session
     */
    @SuppressWarnings("unchecked")
    private Scene toTheCombine(boolean originalReviewerRefreshes) {
        Computer high = lab.higher();
        Computer low = lab.lower();
        ScenarioAgent high1 = lab.agent(high, "high-1");
        ScenarioAgent high2 = lab.agent(high, "high-2");
        ScenarioAgent low1 = lab.agent(low, "low-1");
        ScenarioAgent low2 = lab.agent(low, "low-2");

        // Step 1. The Link fails; both computers stay up.
        lab.split();

        // Step 2. Apart, each computer's first agent changes the same line of shared.txt, and its second agent writes a file of its own.
        high1.claim("Change shared.txt on the higher computer", "shared.txt");
        high2.claim("Write high.txt", "high.txt");
        low1.claim("Change shared.txt on the lower computer", "shared.txt");
        low2.claim("Write low.txt", "low.txt");
        high1.edit("shared.txt", "two", "two, by the higher computer");
        high2.create("high.txt", "written by the higher computer\n");
        low1.edit("shared.txt", "two", "two, by the lower computer");
        low2.create("low.txt", "written by the lower computer\n");
        high1.publish();
        high2.publish();
        low1.publish();
        low2.publish();

        String sessionOfReviewer = high1.participant();

        // Step 3. Each computer's agents review each other, and each computer integrates its own two changes.
        lab.drive("the higher computer's two changes are on its main", () -> lab.hasOnBranch(high, "high.txt") && lab.onBranch(high, "shared.txt").contains("by the higher computer"), high1, high2);
        lab.drive("the lower computer's two changes are on its main", () -> lab.hasOnBranch(low, "low.txt") && lab.onBranch(low, "shared.txt").contains("by the lower computer"), low1, low2);
        assertNotEquals(lab.head(high), lab.head(low), "the copies of main have diverged");

        if (originalReviewerRefreshes) {
            // The main of this computer moved under the first agent's workspace, so its next action recommends the refresh.
            high1.call("ensure_session", Map.of());
            assertNotEquals(sessionOfReviewer, high1.participant(), "the refresh gave the first agent a new session");
        }

        // Step 4. Two fresh agents of the higher computer hold no lane; the combine will be offered to them.
        ScenarioAgent combiner1 = lab.agent(high, "high-combiner-1");
        ScenarioAgent combiner2 = lab.agent(high, "high-combiner-2");
        combiner1.join();
        combiner2.join();

        // Step 5. The Link comes back. The higher node ID re-merges onto the lower one's main, and Git cannot merge shared.txt.
        lab.heal();
        lab.drive("the higher computer opens a combine lane and offers it to both fresh agents", () -> !combiner1.combinesOffered().isEmpty() && !combiner2.combinesOffered().isEmpty(), high1, high2,
                low1, low2);
        assertEquals(1, lab.entriesWrittenUnchecked(high, high, PredictionEventType.COMBINE_REQUIRED), "the higher computer wrote the combine");
        assertEquals(0, lab.entriesWrittenUnchecked(low, low, PredictionEventType.COMBINE_REQUIRED), "the lower computer did not");
        Map<String, Object> ensureSession = (Map<String, Object>) combiner1.combinesOffered().getFirst().get("ensureSession");
        return new Scene(high, low, high1, high2, low1, low2, combiner1, combiner2, ensureSession);
    }

    /** Step 7 and 8: the taker resolves the file, the re-merge is reviewed again and lands, and the other computer takes it. */
    private void combineAndMeet(Scene scene) throws Exception {
        assertTrue(scene.combiner1().call("read_file", Map.of("path", "shared.txt")).toString().contains("<<<<<<<"), "its worktree holds the conflict markers");
        scene.combiner1().rewrite("shared.txt", "one\ntwo, by the higher computer\ntwo, by the lower computer\nthree\n");
        scene.combiner1().finish();

        lab.drive("both computers hold the same main with the combined file", () -> lab.mainsEqual() && lab.hasOnBranch(scene.low(), "high.txt") && lab.hasOnBranch(scene.high(), "low.txt")
                && lab.onBranch(scene.low(), "shared.txt").contains("by the higher computer"), scene.everyone());
        assertEquals("one\ntwo, by the higher computer\ntwo, by the lower computer\nthree", lab.onBranch(scene.low(), "shared.txt"));
        assertEquals(lab.onBranch(scene.high(), "shared.txt"), lab.onBranch(scene.low(), "shared.txt"));
        assertFalse(lab.onBranch(scene.high(), "shared.txt").contains("<<<<<<<"));
        lab.await("each computer holds every entry the other wrote", () -> {
            try {
                lab.assertLogsIdentical();
                return true;
            } catch (AssertionError | java.io.IOException notYet) {
                return false;
            }
        });
        lab.assertLogsIdentical();
        lab.assertCheckoutsMatchBranch();
    }

    @Test
    void bothComputersChangeOneFileApartTheCombineIsTakenByOneAgentAndRefusedToTheOtherAndMainEndsEqual() throws Exception {
        Scene scene = toTheCombine(false);

        // Step 6. Both ask for the combine; one takes it and the other is refused, and the refusal is safe to repeat.
        Map<String, Object> taken = scene.combiner1().call("ensure_session", scene.ensureSession());
        assertEquals("ready", taken.get("status"), taken.toString());
        long lanes = lab.entriesWritten(scene.high(), scene.high(), PredictionEventType.WORK_INTENT_ANNOUNCED);
        Map<String, Object> refused = scene.combiner2().call("ensure_session", scene.ensureSession());
        assertEquals("COMBINE_TAKEN_OR_CLOSED", ScenarioAgent.inner(refused).get("reason"), refused.toString());
        assertEquals("blocked", refused.get("status"), refused.toString());
        assertEquals(lanes, lab.entriesWritten(scene.high(), scene.high(), PredictionEventType.WORK_INTENT_ANNOUNCED), "the refused call announced no lane");
        assertEquals(refused, scene.combiner2().call("ensure_session", scene.ensureSession()), "asking again gets the same answer");
        assertEquals(lanes, lab.entriesWritten(scene.high(), scene.high(), PredictionEventType.WORK_INTENT_ANNOUNCED), "and announced none");
        assertTrue(scene.combiner2().combinesOffered().isEmpty(), "the taken combine is no longer offered to the refused agent");

        combineAndMeet(scene);
    }

    /**
     * s5-run19 (2026-10-26): while the combine waits for an agent, the lower computer goes on integrating lanes, so its
     * {@code main} moves again and again, and each move named a new re-merge that met the same conflict on the same advance and
     * opened another combine (three for one snapshot, two of them never taken). A combine is about the conflicting advance, not
     * about the other copy's head: one is opened, its result is merged with the newer head, and the copies end equal.
     */
    @Test
    void theOtherComputerGoesOnIntegratingWhileTheCombineWaitsAndOneCombineIsOpenedAndMainEndsEqual() throws Exception {
        Scene scene = toTheCombine(false);
        ScenarioAgent low3 = lab.agent(scene.low(), "low-3");
        ScenarioAgent low4 = lab.agent(scene.low(), "low-4");
        ScenarioAgent[] working = {scene.high1(), scene.high2(), scene.low1(), scene.low2(), low3, low4};
        // Two producers at a time, who review each other, so the lower computer's main moves twice (an advance for each lane).
        int later = 0;
        for (int round = 0; round < 2; round++) {
            String first = "later-" + later++ + ".txt";
            String second = "later-" + later++ + ".txt";
            long seen = lab.entriesWrittenUnchecked(scene.high(), scene.low(), PredictionEventType.CONTROL_BRANCH_ADVANCED);
            low3.claim("Write " + first + " on the lower computer", first);
            low4.claim("Write " + second + " on the lower computer", second);
            low3.create(first, "written by the lower computer, later\n");
            low4.create(second, "written by the lower computer, later\n");
            low3.publish();
            low4.publish();
            lab.drive("the lower computer's main holds " + first + " and " + second + " and the higher computer has their heads",
                    () -> lab.hasOnBranch(scene.low(), first) && lab.hasOnBranch(scene.low(), second)
                            && lab.entriesWrittenUnchecked(scene.high(), scene.low(), PredictionEventType.CONTROL_BRANCH_ADVANCED) >= seen + 2, working);
            // Give the higher computer's passes time to meet the new heads while the combine still waits.
            for (int pass = 0; pass < 6; pass++) {
                scene.high1().step();
                Thread.sleep(300);
            }
        }
        assertEquals(1, lab.entriesWrittenUnchecked(scene.high(), scene.high(), PredictionEventType.COMBINE_REQUIRED), "one combine for one conflicting advance");
        assertEquals("ready", scene.combiner1().call("ensure_session", scene.ensureSession()).get("status"));

        combineAndMeet(scene);
        assertEquals(1, lab.entriesWrittenUnchecked(scene.high(), scene.high(), PredictionEventType.COMBINE_REQUIRED), "and still one when the copies meet");
        for (int present = 0; present < 4; present++) {
            assertTrue(lab.hasOnBranch(scene.high(), "later-" + present + ".txt"), "the lower computer's later work is on the met main");
        }
    }

    /**
     * DEFECT 1 (found 2026-10-19 in every run, fixed by the retry answer of 23312223): the agent that lost the race for the combine is told
     * {@code blocked / policy_denied / request_human_help}. A lost race is the normal outcome of two agents asking at once,
     * the refusal changed nothing, and ADR-0098 item 22 offers the combine to every lane-less agent of the computer; a
     * person should not be asked. The answer should send the agent to {@code get_next_action} (or say wait).
     */
    @Test
    void theLoserOfTheRaceForTheCombineIsNotSentToAPerson() {
        Scene scene = toTheCombine(false);
        assertEquals("ready", scene.combiner1().call("ensure_session", scene.ensureSession()).get("status"));

        Map<String, Object> refused = scene.combiner2().call("ensure_session", scene.ensureSession());

        assertNotEquals("request_human_help", refused.get("nextAction"), refused.toString());
    }

    /**
     * DEFECT 3 (found and fixed 2026-10-19, in 6 of 18 runs of the scenario with agents that follow every recommended refresh; the forced
     * refresh below reproduced it in its one run): the re-merge never lands and the copies of {@code main} stay apart for ever.
     * It starts as DEFECT 2 of {@link ApartWithoutOverlapScenarioTest}: the higher computer's first agent reviewed its second
     * agent's snapshot, refreshed its stale workspace with {@code ensure_session} and so got a new session, and the merge
     * pass released the re-merge review it held. Here another agent is eligible: the fresh agent that is about to take the
     * combine, and the review is offered to it. Its lane then waits for this very re-merge to land, so its next action says
     * "wait" and never shows the grant; the grant stays available and nobody consumes it.
     */
    @Test
    void aReMergeReviewReleasedToTheAgentThatTookTheCombineIsStillReviewedAndMainEndsEqual() throws Exception {
        Scene scene = toTheCombine(true);
        assertEquals("ready", scene.combiner1().call("ensure_session", scene.ensureSession()).get("status"));

        combineAndMeet(scene);
    }

    /** The scene of two combines of one re-merge: the first is finished, the second is offered, and the lower computer has moved on. */
    private record TwoCombines(Computer high, Computer low, ScenarioAgent[] everyone, ScenarioAgent combiner1, ScenarioAgent combiner2) {
    }

    /**
     * Two different changes of the higher computer each conflict with the lower computer's while the Link is down (a change
     * of shared.txt, and a pair.txt both wrote), so after the heal one re-merge opens two combines, the second only once the
     * first has its result: it combines onto the first's result. The first is taken and finished. The lower computer then
     * goes on integrating, as it did in the run, so the heads of the re-merge move while the second combine waits.
     */
    private TwoCombines toTheSecondCombine() throws Exception {
        return toTheSecondCombine(false);
    }

    /** @param oneCombiner whether the only agent that combines is the one that took the first combine (s5-run24: the other agent wrote the second) */
    private TwoCombines toTheSecondCombine(boolean oneCombiner) throws Exception {
        Computer high = lab.higher();
        Computer low = lab.lower();
        ScenarioAgent high1 = lab.agent(high, "high-1");
        ScenarioAgent high2 = lab.agent(high, "high-2");
        ScenarioAgent low1 = lab.agent(low, "low-1");
        ScenarioAgent low2 = lab.agent(low, "low-2");
        lab.split();
        high1.claim("Change shared.txt on the higher computer", "shared.txt");
        high2.claim("Write pair.txt on the higher computer", "pair.txt");
        low1.claim("Change shared.txt on the lower computer", "shared.txt");
        low2.claim("Write pair.txt on the lower computer", "pair.txt");
        high1.edit("shared.txt", "two", "two, by the higher computer");
        high2.create("pair.txt", "pair, by the higher computer\n");
        low1.edit("shared.txt", "two", "two, by the lower computer");
        low2.create("pair.txt", "pair, by the lower computer\n");
        high1.publish();
        high2.publish();
        low1.publish();
        low2.publish();
        lab.drive("both computers hold their two changes on main",
                () -> lab.onBranch(high, "shared.txt").contains("by the higher computer") && lab.hasOnBranch(high, "pair.txt") && lab.onBranch(low, "shared.txt").contains("by the lower computer")
                        && lab.hasOnBranch(low, "pair.txt"),
                high1, high2, low1, low2);
        ScenarioAgent combiner1 = lab.agent(high, "high-combiner-1");
        ScenarioAgent combiner2 = oneCombiner ? combiner1 : lab.agent(high, "high-combiner-2");
        combiner1.join();
        if (!oneCombiner) {
            combiner2.join();
        }
        lab.heal();

        lab.drive("the first combine is offered", () -> !combiner1.combinesOffered().isEmpty(), high1, high2, low1, low2);
        takeResolveAndFinish(combiner1);
        ScenarioAgent second = combiner2;
        if (oneCombiner) {
            // The agent's lane is released once its result is published, and the run controller resumes the agent on a new connection
            // for the combine that waits (the observer offers it to the sessions of the agent).
            String firstLane = combiner1.participant();
            lab.drive("the first combine's lane is released", () -> activeIntents(high).noneMatch(intent -> intent.participant().equals(firstLane)), high1, high2, low1, low2);
            second = lab.agent(high, "high-combiner-1-resumed");
            second.join();
        }
        ScenarioAgent offeredTo = second;
        lab.drive("the second combine is offered", () -> !offeredTo.combinesOffered().isEmpty(), high1, high2, low1, low2, offeredTo);
        combiner2 = second;
        ScenarioAgent low3 = lab.agent(low, "low-3");
        ScenarioAgent low4 = lab.agent(low, "low-4");
        long seen = lab.entriesWrittenUnchecked(high, low, PredictionEventType.CONTROL_BRANCH_ADVANCED);
        low3.claim("Write later-0.txt on the lower computer", "later-0.txt");
        low4.claim("Write later-1.txt on the lower computer", "later-1.txt");
        low3.create("later-0.txt", "written by the lower computer, later\n");
        low4.create("later-1.txt", "written by the lower computer, later\n");
        low3.publish();
        low4.publish();
        lab.drive("the lower computer's main moved and the higher computer has its heads",
                () -> lab.hasOnBranch(low, "later-0.txt") && lab.hasOnBranch(low, "later-1.txt")
                        && lab.entriesWrittenUnchecked(high, low, PredictionEventType.CONTROL_BRANCH_ADVANCED) >= seen + 2,
                high1, high2, low1, low2, low3, low4);
        for (int pass = 0; pass < 6; pass++) {
            high1.step();
            Thread.sleep(300);
        }
        return new TwoCombines(high, low, oneCombiner ? new ScenarioAgent[] {high1, high2, low1, low2, low3, low4, combiner2} : new ScenarioAgent[] {high1, high2, low1, low2, low3, low4, combiner1, combiner2},
                combiner1, combiner2);
    }

    /**
     * s5-run20 (2026-10-26): both combines are taken and finished, the re-merge lands once each has a result, and the copies
     * of {@code main} end equal. A combine's base is the commit its predecessor's result made, so the build starts from the
     * newest combine that has a result and meets the base again after the other computer moved.
     */
    @Test
    void twoCombinesOfOneReMergeAreBothTakenAndFinishedAndTheReMergeLandsAndMainEndsEqual() throws Exception {
        TwoCombines scene = toTheSecondCombine();
        takeResolveAndFinish(scene.combiner2());

        lab.drive("both computers hold the same main with both combined files", () -> lab.mainsEqual() && lab.onBranch(scene.low(), "pair.txt").contains("by the higher computer")
                && lab.onBranch(scene.low(), "shared.txt").contains("by the higher computer"), scene.everyone());
        assertEquals(2, lab.entriesWrittenUnchecked(scene.high(), scene.high(), PredictionEventType.COMBINE_REQUIRED), "one combine for each conflicting change");
        assertEquals(lab.onBranch(scene.high(), "pair.txt"), lab.onBranch(scene.low(), "pair.txt"));
        assertFalse(lab.onBranch(scene.high(), "pair.txt").contains("<<<<<<<"));
        assertTrue(lab.onBranch(scene.high(), "later-1.txt").contains("later"), "the lower computer's later work is on the met main");
    }

    /**
     * s5-run20 (2026-10-26): the agent of the second combine lane asked to finish and was answered with the first lane's wait
     * ("the combined version lands once the original reviewer accepts it again; nothing more is needed from this lane"). The
     * pump's wait names the unlanded snapshot of every combine lane to every caller, so a lane that had published nothing
     * was told its work was done, stopped, and the re-merge, which lands only when every combine has a result, never landed.
     * Here the agent resolves the second conflict by keeping the other computer's file as it is, which leaves its lane with
     * nothing to publish: it must not be told to wait for another lane.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aCombineLaneThatHasPublishedNothingIsNotToldToWaitForAnotherCombinesSnapshot() throws Exception {
        TwoCombines scene = toTheSecondCombine();
        ScenarioAgent combiner = scene.combiner2();
        Map<String, Object> offered = combiner.combinesOffered().getFirst();
        assertEquals("ready", combiner.call("ensure_session", (Map<String, Object>) offered.get("ensureSession")).get("status"));
        // Keeps the lower computer's file: the worktree then equals the combine's base and holds nothing to publish.
        Map<String, String> lowers = Map.of("shared.txt", "one\ntwo, by the lower computer\nthree\n", "pair.txt", "pair, by the lower computer\n");
        lowers.forEach((path, content) -> {
            if (combiner.call("read_file", Map.of("path", path)).toString().contains("<<<<<<<")) {
                combiner.rewrite(path, content);
            }
        });

        Map<String, Object> answer = combiner.call("get_next_action", Map.of("completionRequested", true));

        assertFalse(answer.toString().contains("combinePending"), "told to wait for the first lane's snapshot: " + answer);
        assertNotEquals("waiting", answer.get("status"), answer.toString());
    }

    /**
     * s5-run24 (2026-10-26): the agent that took the first combine published its result and kept its lane, which holds until
     * the re-merge lands; the re-merge lands only when the second combine has a result, and the only other agent wrote the
     * second combine's snapshot and may not take it. Nothing could end that wait. A combine lane that has published is
     * released, so its agent is offered the second combine.
     */
    @Test
    void theAgentThatPublishedTheFirstCombineIsOfferedTheSecondAndTheCopiesMeet() throws Exception {
        TwoCombines scene = toTheSecondCombine(true);
        ScenarioAgent combiner = scene.combiner2();

        lab.drive("the agent is offered the second combine after publishing the first", () -> !combiner.combinesOffered().isEmpty(), scene.everyone());
        takeResolveAndFinish(combiner);

        lab.drive("both computers hold the same main", () -> lab.mainsEqual(), scene.everyone());
        assertFalse(lab.onBranch(scene.high(), "pair.txt").contains("<<<<<<<"));
    }

    /**
     * The whole two-combine path with one combining agent, each answer it is given read back: the first combine is resolved,
     * the second keeps the other copy, and no answer on the way is one that nothing can end (ADR-0098 step 5 item 39, after
     * items 35 to 38 were each a different dead end of this path found only by a real run).
     */
    @Test
    @SuppressWarnings("unchecked")
    void theWholeTwoCombinePathNeverAnswersAnAgentWithSomethingNothingCanEnd() throws Exception {
        TwoCombines scene = toTheSecondCombine(true);
        ScenarioAgent combiner = scene.combiner2();

        lab.drive("the agent is offered the second combine", () -> !combiner.combinesOffered().isEmpty(), scene.everyone());
        Map<String, Object> offered = combiner.combinesOffered().getFirst();
        assertEquals("ready", combiner.call("ensure_session", (Map<String, Object>) offered.get("ensureSession")).get("status"));
        Map<String, String> lowers = Map.of("shared.txt", "one\ntwo, by the lower computer\nthree\n", "pair.txt", "pair, by the lower computer\n");
        lowers.forEach((path, content) -> {
            if (combiner.call("read_file", Map.of("path", path)).toString().contains("<<<<<<<")) {
                combiner.rewrite(path, content);
            }
        });
        combiner.finish();
        lab.drive("both computers hold the same main", () -> lab.mainsEqual(), scene.everyone());

        for (ScenarioAgent agent : java.util.stream.Stream.of(scene.combiner1(), scene.combiner2()).distinct().toList()) {
            for (Map<String, Object> answer : agent.answers) {
                Map<String, Object> inner = ScenarioAgent.inner(answer);
                String shown = agent.name + " was answered " + answer;
                assertFalse(String.valueOf(inner.get("instruction")).contains("cancel_lane") && Boolean.TRUE.equals(inner.get("nothingToPublish")) && agent.name.contains("combiner"),
                        "told to cancel a combine lane: " + shown);
                // A session that completed is told to wait for a review it may still be asked for, which a log entry announces; a session
                // that is not complete and is told to wait with no blocker is s5-run24's idle agent, which stopped with a combine waiting.
                boolean completed = inner.get("participants") instanceof List<?> listed && listed.stream().anyMatch(known -> known instanceof Map<?, ?> participant
                        && String.valueOf(participant.get("id")).equals(String.valueOf(inner.get("currentParticipant"))) && "COMPLETED".equals(participant.get("state")));
                assertFalse("waiting".equals(answer.get("status")) && !completed && ScenarioAgent.workflow(answer).get("blockers") instanceof List<?> blockers && blockers.equals(List.of("none")),
                        "a wait that names no blocker: " + shown);
            }
        }
    }

    /**
     * s5-run23 (2026-10-26): the agent of the second combine lane resolved its conflict by keeping the other copy's files, so
     * its worktree equalled the combine's base. It was told "nothing to publish; call cancel_lane", cancelled, and the combine
     * was left with no lane, no result and a cancelled work group; the re-merge, which lands only when every combine has a
     * result, never landed. Keeping the other copy is a resolution: the lane publishes it, and the copies meet.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aCombineLaneThatKeepsTheOtherCopyPublishesThatAsItsResultAndTheCopiesMeet() throws Exception {
        TwoCombines scene = toTheSecondCombine();
        ScenarioAgent combiner = scene.combiner2();
        Map<String, Object> offered = combiner.combinesOffered().getFirst();
        assertEquals("ready", combiner.call("ensure_session", (Map<String, Object>) offered.get("ensureSession")).get("status"));
        Map<String, String> lowers = Map.of("shared.txt", "one\ntwo, by the lower computer\nthree\n", "pair.txt", "pair, by the lower computer\n");
        lowers.forEach((path, content) -> {
            if (combiner.call("read_file", Map.of("path", path)).toString().contains("<<<<<<<")) {
                combiner.rewrite(path, content);
            }
        });

        Map<String, Object> answer = combiner.call("get_next_action", Map.of("completionRequested", true));
        assertFalse(answer.toString().contains("cancel_lane"), "told to cancel the lane: " + answer);
        combiner.finish();

        lab.drive("both computers hold the same main", () -> lab.mainsEqual(), scene.everyone());
        assertFalse(lab.onBranch(scene.high(), "pair.txt").contains("<<<<<<<"));
    }

    /**
     * s5-run21 (2026-10-26): the agent of the second combine lane joined and its first {@code get_next_action} was answered
     * {@code waiting}, {@code integration_pending}, {@code integrationRunning}: another pass held the integration lock at that
     * moment. That wait is for the pass, not for the lane, nothing recorded in the log ends it, so the agent stopped, no wake
     * ever came, and the lane never published; the log stayed flat for 25 minutes. A lane that has published nothing is
     * shown its own next step whatever the pump answers.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aCombineLaneThatHasPublishedNothingIsNotToldThatAnotherPassIsRunning() throws Exception {
        TwoCombines scene = toTheSecondCombine();
        ScenarioAgent combiner = scene.combiner2();
        Map<String, Object> offered = combiner.combinesOffered().getFirst();
        assertEquals("ready", combiner.call("ensure_session", (Map<String, Object>) offered.get("ensureSession")).get("status"));

        Map<String, Object> answer;
        try (ProjectIntegrationLock otherPass = takenWhenFree(scene.high().location.coordinationDirectory())) {
            answer = combiner.call("get_next_action", Map.of());
            assertNotNull(otherPass);
        }

        assertFalse(answer.toString().contains("integrationRunning"), "told that another pass runs: " + answer);
        assertNotEquals("waiting", answer.get("status"), answer.toString());
    }

    /** The computer's own passes hold the lock now and then; this is a pass of the test's own that holds it as long as it is open. */
    static ProjectIntegrationLock takenWhenFree(Path coordination) throws Exception {
        long giveUp = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < giveUp) {
            ProjectIntegrationLock held = ProjectIntegrationLock.tryAcquire(coordination);
            if (held != null) {
                return held;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the integration lock stayed taken for a minute");
    }

    private static java.util.stream.Stream<org.synesis.coordination.domain.collaboration.WorkIntent> activeIntents(Computer computer) {
        try {
            return computer.store().collaborationProjection().activeIntents().stream();
        } catch (Exception unreadable) {
            return java.util.stream.Stream.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static void takeResolveAndFinish(ScenarioAgent combiner) {
        Map<String, Object> offered = combiner.combinesOffered().getFirst();
        assertEquals("ready", combiner.call("ensure_session", (Map<String, Object>) offered.get("ensureSession")).get("status"));
        // Which of the two files conflicts depends on which advance Git meets first.
        Map<String, String> resolutions = Map.of("shared.txt", "one\ntwo, by the higher computer\ntwo, by the lower computer\nthree\n", "pair.txt",
                "pair, by the higher computer\npair, by the lower computer\n");
        List<String> conflicted = resolutions.keySet().stream().filter(path -> combiner.call("read_file", Map.of("path", path)).toString().contains("<<<<<<<")).toList();
        assertEquals(1, conflicted.size(), "its worktree holds the conflict markers of one file: " + conflicted);
        combiner.rewrite(conflicted.getFirst(), resolutions.get(conflicted.getFirst()));
        combiner.finish();
    }
}
