package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.coordination.TestComputers.Computer;

import org.synesis.coordination.domain.prediction.PredictionEventType;

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
        if (!originalReviewerRefreshes) {
            // A lane that is published and under review needs no fresh workspace; a model that stays put keeps its session.
            high1.keepsItsSession();
            high2.keepsItsSession();
            low1.keepsItsSession();
            low2.keepsItsSession();
        }

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

        // Step 3. Each computer's agents review each other, and each computer integrates its own two changes.
        lab.drive("the higher computer's two changes are on its main", () -> lab.hasOnBranch(high, "high.txt") && lab.onBranch(high, "shared.txt").contains("by the higher computer"), high1, high2);
        lab.drive("the lower computer's two changes are on its main", () -> lab.hasOnBranch(low, "low.txt") && lab.onBranch(low, "shared.txt").contains("by the lower computer"), low1, low2);
        assertNotEquals(lab.head(high), lab.head(low), "the copies of main have diverged");

        if (originalReviewerRefreshes) {
            // The main of this computer moved under the first agent's workspace, so its next action recommends the refresh.
            String session = high1.participant();
            high1.call("ensure_session", Map.of());
            assertNotEquals(session, high1.participant(), "the refresh gave the first agent a new session");
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
     * DEFECT 1 (found 2026-10-19, every run): the agent that lost the race for the combine is told
     * {@code blocked / policy_denied / request_human_help}. A lost race is the normal outcome of two agents asking at once,
     * the refusal changed nothing, and ADR-0098 item 22 offers the combine to every lane-less agent of the computer; a
     * person should not be asked. The answer should send the agent to {@code get_next_action} (or say wait).
     */
    @Disabled("DEFECT 1: the loser of a combine is sent to request_human_help; see the Javadoc")
    @Test
    void theLoserOfTheRaceForTheCombineIsNotSentToAPerson() {
        Scene scene = toTheCombine(false);
        assertEquals("ready", scene.combiner1().call("ensure_session", scene.ensureSession()).get("status"));

        Map<String, Object> refused = scene.combiner2().call("ensure_session", scene.ensureSession());

        assertNotEquals("request_human_help", refused.get("nextAction"), refused.toString());
    }

    /**
     * DEFECT 3 (found 2026-10-19, in 6 of 18 runs of the scenario with agents that follow every recommended refresh; the forced
     * refresh below reproduced it in its one run): the re-merge never lands and the copies of {@code main} stay apart for ever.
     * It starts as DEFECT 2 of {@link ApartWithoutOverlapScenarioTest}: the higher computer's first agent reviewed its second
     * agent's snapshot, refreshed its stale workspace with {@code ensure_session} and so got a new session, and the merge
     * pass released the re-merge review it held. Here another agent is eligible: the fresh agent that is about to take the
     * combine, and the review is offered to it. Its lane then waits for this very re-merge to land, so its next action says
     * "wait" and never shows the grant; the grant stays available and nobody consumes it.
     */
    @Disabled("DEFECT 3: a released re-merge review is offered to the agent whose combine waits for it and is never shown to it; see the Javadoc")
    @Test
    void aReMergeReviewReleasedToTheAgentThatTookTheCombineIsStillReviewedAndMainEndsEqual() throws Exception {
        Scene scene = toTheCombine(true);
        assertEquals("ready", scene.combiner1().call("ensure_session", scene.ensureSession()).get("status"));

        combineAndMeet(scene);
    }
}
