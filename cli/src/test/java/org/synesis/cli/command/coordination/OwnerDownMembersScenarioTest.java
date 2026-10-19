package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.coordination.TestComputers.Computer;

import org.synesis.coordination.domain.prediction.PredictionEventType;

/**
 * Scenario of ADR-0102 item 1, round two, for ADR-0098 step 6: three computers, the owner down. The owner O and the members
 * M1 and M2 are joined by the one-link flow (each member opens a project link of O's, O accepts), so the project is shared
 * and each member has published its card. O's Synesis is then stopped, as a stopped computer is: its Link and its server
 * close, its folders stay. M1 and M2 go on together over the direct session their cards gave them. Each of them has two
 * agents, so a review never waits for the other computer.
 *
 * <p>Done-when of the real run (STEP6-7-RUNS.md, step 6, items 2 and 3): each member sees the other's claims, an overlapping
 * claim is refused while they are connected, both produce, their agents review, their copies of {@code main} meet; when O
 * comes back all three copies of {@code main} are equal and all three computers hold byte-identical logs.</p>
 */
@Timeout(300)
final class OwnerDownMembersScenarioTest {

    @TempDir
    Path temp;
    private ScenarioLab lab;
    private Computer owner;
    private Computer m1;
    private Computer m2;

    @BeforeEach
    void threeJoinedComputers() throws Exception {
        lab = ScenarioLab.open(temp);
        owner = lab.owner;
        m1 = lab.friend;
        m2 = lab.addMember(temp, "gamma");
        lab.await("each computer's log holds the three cards", () -> allHoldThreeCards(owner, m1, m2));
        lab.await("the two members hold a direct session with each other", () -> lab.linked(m1, m2));
    }

    @AfterEach
    void stop() {
        lab.close();
    }

    private static boolean allHoldThreeCards(Computer... computers) {
        for (Computer computer : computers) {
            try {
                if (computer.membership().cards().size() != 3) {
                    return false;
                }
            } catch (Exception unreadable) {
                return false;
            }
        }
        return true;
    }

    @Test
    void membersGoOnTogetherWhileTheOwnerIsDownAndAllThreeMainsAndLogsAreEqualWhenItReturns() throws Exception {
        ScenarioAgent m1a = lab.agent(m1, "m1-a");
        ScenarioAgent m1b = lab.agent(m1, "m1-b");
        ScenarioAgent m2a = lab.agent(m2, "m2-a");
        ScenarioAgent m2b = lab.agent(m2, "m2-b");
        String start = lab.head(owner);
        assertEquals(start, lab.head(m1), "joined: every computer starts from the same main");
        assertEquals(start, lab.head(m2));

        // Step 1. The owner's Synesis stops; the two members keep the direct session their cards gave them.
        owner.stop();
        lab.await("neither member holds a session with the owner", () -> !m1.owner.connectedTo(owner.node.nodeId()) && !m2.owner.connectedTo(owner.node.nodeId()));
        assertTrue(lab.linked(m1, m2), "the members are still linked to each other");

        // Step 2. M1's first agent claims board.txt. M2 sees the claim, and M2's agent is refused the same file while they are connected.
        m1a.claim("Write board.txt", "board.txt");
        lab.await("M2 holds M1's claim", () -> lab.entriesWrittenUnchecked(m2, m1, PredictionEventType.WORK_INTENT_ANNOUNCED) == 1);
        long m2Claims = lab.entriesWrittenUnchecked(m2, m2, PredictionEventType.WORK_INTENT_ANNOUNCED);
        Map<String, Object> refused = m2a.tryClaim("Also write board.txt", "board.txt");
        assertNotEquals("ready", refused.get("status"), "an overlapping claim is refused while the computers are connected: " + refused);
        assertEquals(m2Claims, lab.entriesWrittenUnchecked(m2, m2, PredictionEventType.WORK_INTENT_ANNOUNCED), "the refused claim wrote no lane");

        // Step 3. The rest claim files of their own; every claim reaches the other member.
        m2a.claim("Write m2a.txt", "m2a.txt");
        m1b.claim("Write m1b.txt", "m1b.txt");
        m2b.claim("Write m2b.txt", "m2b.txt");
        lab.await("each member holds the other's two claims", () -> lab.entriesWrittenUnchecked(m1, m2, PredictionEventType.WORK_INTENT_ANNOUNCED) == 2
                && lab.entriesWrittenUnchecked(m2, m1, PredictionEventType.WORK_INTENT_ANNOUNCED) == 2);

        // Step 4. All four write and publish; the agents review and integrate; the members' copies of main meet.
        m1a.create("board.txt", "written by m1-a\n");
        m1b.create("m1b.txt", "written by m1-b\n");
        m2a.create("m2a.txt", "written by m2-a\n");
        m2b.create("m2b.txt", "written by m2-b\n");
        m1a.publishWhileLinked();
        m1b.publishWhileLinked();
        m2a.publishWhileLinked();
        m2b.publishWhileLinked();
        String[] files = {"board.txt", "m1b.txt", "m2a.txt", "m2b.txt"};
        lab.drive("the members' copies of main are equal and hold all four files", () -> lab.mainsEqual(m1, m2) && holdsAll(m1, files) && holdsAll(m2, files), m1a, m1b, m2a, m2b);
        assertNotEquals(start, lab.head(m1), "main moved");
        assertTrue(acceptedReviews(m1) >= 4, "each snapshot was reviewed: " + acceptedReviews(m1));
        lab.await("both members hold the same verdicts", () -> acceptedReviews(m1) == acceptedReviews(m2));
        assertEquals(start, lab.head(owner), "the owner is down and did not move");
        assertFalse(lab.hasOnBranch(owner, "board.txt"));

        // Step 5. The owner's Synesis starts again; all three copies of main and all three logs become equal.
        Computer back = lab.restart(owner);
        lab.drive("all three copies of main are equal and hold the four files", () -> lab.mainsEqual(back, m1, m2) && holdsAll(back, files), m1a, m1b, m2a, m2b);
        lab.await("the three computers hold identical logs", () -> lab.logsIdentical(back, m1, m2));
        lab.assertLogsIdentical(back, m1, m2);
        lab.assertCheckoutsClean(back, m1, m2);
        for (String file : files) {
            assertEquals(lab.onBranch(m1, file), lab.onBranch(back, file), file);
            assertEquals(lab.onBranch(m2, file), lab.onBranch(back, file), file);
        }
        assertEquals(Set.of(back.node.nodeId(), m1.node.nodeId(), m2.node.nodeId()), back.membership().members(), "the owner still names both members");
        lab.await("the owner is linked to both members again", () -> lab.linked(back, m1) && lab.linked(back, m2));
    }

    private boolean holdsAll(Computer computer, String... files) {
        for (String file : files) {
            if (!lab.hasOnBranch(computer, file)) {
                return false;
            }
        }
        return true;
    }

    private static long acceptedReviews(Computer computer) {
        try {
            return computer.store().workGroupProjection().reviewValidations().stream().filter(verdict -> "ACCEPTED".equals(verdict.result())).count();
        } catch (Exception unreadable) {
            return -1;
        }
    }
}
