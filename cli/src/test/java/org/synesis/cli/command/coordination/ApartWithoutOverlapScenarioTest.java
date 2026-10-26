package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.coordination.TestComputers.Computer;

import org.synesis.coordination.domain.prediction.PredictionEventType;

/**
 * Scenario S1 of ADR-0102 item 1: two computers, linked, then apart. Each one's agents claim, write, publish, review each
 * other and integrate different files while the Link is down. After the heal the copies of {@code main} are equal and the
 * logs are byte-identical (ADR-0098 steps 4 and 5; ADR-0100, "Work while apart and merge back").
 *
 * <p>The disabled test is a product defect this scenario found on its first rounds (2026-10-19), as the same scenario with
 * the step that exposes it.</p>
 *
 * <p>Known rare failure (DEFECT 4, 3 times in about 90 runs of S1 and S2, 2026-10-19, cause not found): the re-merge lands
 * on the higher computer, all its entries reach the lower one, the lower one holds the new commit with a clean checkout
 * that is behind it, and its {@code main} stays where it was, for 60 s, with nothing in the log of the server.
 * {@code CodeOverLink.meet} takes another computer's head only after a pull that brought entries, and has silent returns
 * (a busy integration lock, a {@code followBranch} that declines). Holding the lower computer's integration lock through
 * the arrival did not reproduce it, so that is not the whole story. The failure message of the wait carries the status
 * and refs of both copies.</p>
 */
@Timeout(240)
final class ApartWithoutOverlapScenarioTest {

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
    void bothComputersWorkApartOnDifferentFilesAndMeetWithEqualMainAndIdenticalLogs() throws Exception {
        workApartAndMeet(false);
    }

    /**
     * DEFECT 2 (found 2026-10-19, in one of the first two runs of this scenario with agents that follow every recommended
     * refresh, and by the forced refresh below in its one run): the re-merge of the higher computer is never reviewed, and
     * the copies of {@code main} stayed apart for ever. The agent that reviewed a snapshot refreshes its stale workspace with
     * {@code ensure_session}, as its next action recommends, which gives it a new session. The merge pass judges the old
     * session gone and releases the re-merge review it was granted (ADR-0098 step 5, item 24), and offers it to no one: the
     * only other agent wrote the snapshot, and the refreshed agent, the same connection, is not offered its own review
     * again. Its next action said only that it had no open work. Now the review passes to the session that replaced the
     * reviewer's, which {@code SessionSuccessions} proves is the same agent's, and never to the author's own next session.
     */
    @Test
    void anOriginalReviewerThatRefreshedItsWorkspaceStillGetsTheReMergeToReviewAndMainEndsEqual() throws Exception {
        workApartAndMeet(true);
    }

    private void workApartAndMeet(boolean originalReviewerRefreshes) throws Exception {
        Computer high = lab.higher();
        Computer low = lab.lower();
        ScenarioAgent high1 = lab.agent(high, "high-1");
        ScenarioAgent high2 = lab.agent(high, "high-2");
        ScenarioAgent low1 = lab.agent(low, "low-1");
        ScenarioAgent low2 = lab.agent(low, "low-2");
        String start = lab.head(high);
        assertEquals(start, lab.head(low), "joined: both computers start from the same main");

        // Step 1. The Link between the computers fails; both stay up.
        lab.split();

        // Step 2. Apart, each computer's agents claim a file of their own, write it and publish it.
        high1.claim("Write high1.txt", "high1.txt");
        high2.claim("Write high2.txt", "high2.txt");
        low1.claim("Write low1.txt", "low1.txt");
        low2.claim("Write low2.txt", "low2.txt");
        high1.create("high1.txt", "written by high-1\n");
        high2.create("high2.txt", "written by high-2\n");
        low1.create("low1.txt", "written by low-1\n");
        low2.create("low2.txt", "written by low-2\n");
        high1.publish();
        high2.publish();
        low1.publish();
        low2.publish();

        String sessionOfReviewer = high1.participant();

        // Step 3. Each computer's agents review each other's snapshots, and each computer integrates its own two files.
        lab.drive("the higher computer's agents get both files onto its main", () -> lab.hasOnBranch(high, "high1.txt") && lab.hasOnBranch(high, "high2.txt"), high1, high2);
        lab.drive("the lower computer's agents get both files onto its main", () -> lab.hasOnBranch(low, "low1.txt") && lab.hasOnBranch(low, "low2.txt"), low1, low2);
        assertFalse(lab.hasOnBranch(high, "low1.txt"), "apart, the higher computer does not hold the lower one's files");
        assertFalse(lab.hasOnBranch(low, "high1.txt"), "apart, the lower computer does not hold the higher one's files");
        assertNotEquals(lab.head(high), lab.head(low), "the copies of main have diverged");
        assertFalse(lab.linked());
        if (originalReviewerRefreshes) {
            // The main of the higher computer moved under the agent's workspace, so its next action recommends the refresh.
            high1.call("ensure_session", Map.of());
            assertNotEquals(sessionOfReviewer, high1.participant(), "the refresh gave the agent a new session");
        }

        // Step 4. The Link comes back. The computer with the higher node ID re-merges, the original reviewers review the
        // re-merge, and the result reaches the other computer.
        lab.heal();
        lab.drive("both computers hold the same main with all four files", () -> lab.mainsEqual() && lab.hasOnBranch(low, "high1.txt") && lab.hasOnBranch(low, "high2.txt")
                && lab.hasOnBranch(high, "low1.txt") && lab.hasOnBranch(high, "low2.txt"), high1, high2, low1, low2);

        // Step 5. What each computer holds is the same, in Git and in the logs.
        for (String file : new String[] {"high1.txt", "high2.txt", "low1.txt", "low2.txt"}) {
            assertEquals(lab.onBranch(high, file), lab.onBranch(low, file), file);
        }
        assertEquals("one\ntwo\nthree", lab.onBranch(low, "shared.txt"));
        assertTrue(lab.entriesWritten(low, high, PredictionEventType.REMERGE_SNAPSHOT_CREATED) > 0, "the higher node ID re-merged");
        assertEquals(0, lab.entriesWritten(low, low, PredictionEventType.REMERGE_SNAPSHOT_CREATED), "the lower node ID did not");
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
}
