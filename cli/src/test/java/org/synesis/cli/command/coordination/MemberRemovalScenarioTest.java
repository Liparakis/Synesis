package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.LogDump;

/**
 * Scenario of ADR-0102 item 1, round two, for ADR-0098 step 6: the owner removes a member (STEP6-7-RUNS.md, step 6, item 4).
 * Three computers joined by the one-link flow: the owner O and the members M1 and M2. The owner removes a member by a
 * {@code MEMBER_REMOVED} entry of its log, as {@code synesis project remove-member} does. The member's open work ends on
 * the others, so its files can be claimed again, and an entry it writes after the head the owner saw counts for nothing
 * ({@code LOG_PROBLEM} in {@code synesis coordination status}). The others go on.
 *
 * <p>Each computer has two scripted agents where it needs reviews, so a review never waits for another computer. These
 * tests do not wait for the copies of {@code main} of O and M1 to meet: that is the defect of
 * {@link ThreeComputersMainMeetingScenarioTest}.</p>
 */
@Timeout(300)
final class MemberRemovalScenarioTest {

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
        lab.await("every two computers hold a direct session", () -> lab.linked(m1, m2) && lab.linked(owner, m1) && lab.linked(owner, m2));
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

    private void remove(Computer member) throws Exception {
        new MembershipService(owner.store(), owner.node).remove(member.node.nodeId());
    }

    private boolean holdsAll(Computer computer, String... files) {
        for (String file : files) {
            if (!lab.hasOnBranch(computer, file)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the computer's log lists an entry of the node as counted for nothing because of the removal, as {@code synesis coordination status} prints it. */
    private static boolean countsForNothing(Computer holder, Computer writer) {
        try {
            return holder.store().problems().stream().anyMatch(line -> line.startsWith(writer.node.nodeId() + " ") && line.endsWith(" MEMBER_REMOVED"));
        } catch (Exception unreadable) {
            return false;
        }
    }

    /** The state the computer's projections give a lane, as {@code synesis log dump} prints it; empty when the lane is not one there. */
    private static String laneState(Computer holder, String lane) throws Exception {
        LogDump.Dump dump = LogDump.read(holder.location.coordinationDirectory(), holder.location.projectId());
        return dump.summary().stream().filter(fact -> fact.key().equals("SUMMARY_LANE") && fact.value().startsWith(lane + " ")).map(LogDump.Fact::value).findFirst().orElse("");
    }

    /** The IDs of the announced lanes {@code writer} wrote that {@code holder} holds, in order. */
    private static List<String> lanesWritten(Computer holder, Computer writer) throws Exception {
        LogDump.Dump dump = LogDump.read(holder.location.coordinationDirectory(), holder.location.projectId());
        return LogDump.only(dump.entries(), "WORK_INTENT_ANNOUNCED").stream().filter(entry -> entry.writer().equals(writer.node.nodeId())).map(entry -> entry.fields().get("lane")).toList();
    }

    @Test
    void theOwnerRemovesAMemberItsOpenWorkEndsAndTheOthersGoOnWithoutIt() throws Exception {
        ScenarioAgent o1 = lab.agent(owner, "o-1");
        ScenarioAgent m1a = lab.agent(m1, "m1-a");
        ScenarioAgent m1b = lab.agent(m1, "m1-b");
        ScenarioAgent m2a = lab.agent(m2, "m2-a");
        ScenarioAgent m2b = lab.agent(m2, "m2-b");
        ScenarioAgent m2c = lab.agent(m2, "m2-c");
        String start = lab.head(m2);

        // Step 1. M2 opens two lanes and writes into one; the others hold both claims and refuse the same files while M2 is a member.
        m2a.claim("Write m2a.txt", "m2a.txt");
        m2b.claim("Write m2b.txt", "m2b.txt");
        m2a.create("m2a.txt", "begun by m2-a\n");
        lab.await("O and M1 hold M2's two claims", () -> lab.entriesWrittenUnchecked(owner, m2, PredictionEventType.WORK_INTENT_ANNOUNCED) == 2
                && lab.entriesWrittenUnchecked(m1, m2, PredictionEventType.WORK_INTENT_ANNOUNCED) == 2);
        assertNotEquals("ready", o1.tryClaim("Also m2a.txt", "m2a.txt").get("status"), "O is refused M2's file while M2 is a member");
        assertNotEquals("ready", m1b.tryClaim("Also m2b.txt", "m2b.txt").get("status"), "M1 is refused M2's file while M2 is a member");

        // Step 2. The owner removes M2.
        remove(m2);
        lab.await("O and M1 no longer name M2 a member", () -> !owner.namesMember(m2.node.nodeId()) && !m1.namesMember(m2.node.nodeId()));
        assertTrue(owner.namesMember(m1.node.nodeId()) && m1.namesMember(owner.node.nodeId()), "O and M1 still name each other");

        // Step 3. M2's open work has ended on the others: its two files can be claimed again, one by O's agent and one by M1's.
        lab.await("O's agent can claim M2's first file", () -> "ready".equals(o1.tryClaim("Write m2a.txt", "m2a.txt").get("status")));
        lab.await("M1's agent can claim M2's second file", () -> "ready".equals(m1b.tryClaim("Write m2b.txt", "m2b.txt").get("status")));

        // Step 4. M2 holds the removal (it was linked when it was written), and its agents are refused a new claim, for the removal.
        Map<String, Object> refused = m2c.tryClaim("Write late.txt", "late.txt");
        assertEquals("MEMBER_REMOVED", ScenarioAgent.inner(refused).get("reason"), refused.toString());
        assertEquals(2, lab.entriesWrittenUnchecked(m2, m2, PredictionEventType.WORK_INTENT_ANNOUNCED), "the refused claim wrote no lane");

        // Step 5. M1 goes on without it: its two agents write, review each other's work and integrate on its main.
        m1a.claim("Write m1a.txt", "m1a.txt");
        m1a.create("m1a.txt", "written by m1-a\n");
        m1b.create("m2b.txt", "written by m1-b\n");
        m1a.publishWhileLinked();
        m1b.publishWhileLinked();
        lab.drive("M1's main holds both files", () -> holdsAll(m1, "m1a.txt", "m2b.txt"), m1a, m1b);
        lab.await("O holds every entry M1 wrote and the other way round", () -> lab.logsIdentical(owner, m1));
        lab.assertLogsIdentical(owner, m1);

        // Step 6. The removed computer is not served any more: it holds fewer of M1's entries than O, and its main did not move.
        assertTrue(lab.entriesWrittenUnchecked(m2, m1, PredictionEventType.TASK_SNAPSHOT_CREATED) < lab.entriesWrittenUnchecked(owner, m1, PredictionEventType.TASK_SNAPSHOT_CREATED),
                "M2 gets none of the work M1 did after M2 was removed");
        assertEquals(start, lab.head(m2), "M2's main did not move");
        assertFalse(lab.hasOnBranch(m2, "m1a.txt"));
    }

    /**
     * The late entry. The owner writes the removal with its server down (the command writes the log directly), M2 is linked
     * to M1 only and writes a lane after the head the owner saw, M1 copies it, and the owner comes back. The owner counts the
     * entry for nothing and reports it as a problem; M1 had applied it before the removal reached it (an applied entry is a
     * fact, ADR-0098 step 6 item 10), and the removal ends both of M2's lanes there, so neither lane holds a claim anywhere.
     */
    @Test
    void anEntryTheRemovedMemberWritesAfterTheHeadTheOwnerSawIsCopiedAndCountsForNothing() throws Exception {
        ScenarioAgent o1 = lab.agent(owner, "o-1");
        ScenarioAgent m1a = lab.agent(m1, "m1-a");
        ScenarioAgent m2c = lab.agent(m2, "m2-c");
        ScenarioAgent m2d = lab.agent(m2, "m2-d");

        // Step 1. M2 claims early.txt; O and M1 hold the claim.
        m2c.claim("Write early.txt", "early.txt");
        lab.await("O and M1 hold M2's claim", () -> lab.entriesWrittenUnchecked(owner, m2, PredictionEventType.WORK_INTENT_ANNOUNCED) == 1
                && lab.entriesWrittenUnchecked(m1, m2, PredictionEventType.WORK_INTENT_ANNOUNCED) == 1);

        // Step 2. O's Synesis stops, and the owner removes M2 by an entry of its log, naming the head of M2's log that it holds.
        owner.stop();
        lab.await("neither member holds a session with O", () -> !m1.owner.connectedTo(owner.node.nodeId()) && !m2.owner.connectedTo(owner.node.nodeId()));
        remove(m2);

        // Step 3. M2 does not know; it claims late.txt, and M1, linked to it, copies the entry.
        Map<String, Object> late = m2d.tryClaim("Write late.txt", "late.txt");
        assertEquals("ready", late.get("status"), "M2 has not heard of its removal: " + late);
        lab.await("M1 holds M2's late claim", () -> lab.entriesWrittenUnchecked(m1, m2, PredictionEventType.WORK_INTENT_ANNOUNCED) == 2);

        // Step 4. O comes back, learns M2's log from M1 and counts the late entry for nothing; M1 learns of the removal.
        Computer back = lab.restart(owner);
        lab.await("O reports M2's late entry as counted for nothing", () -> countsForNothing(back, m2));
        lab.await("M1 no longer names M2 a member", () -> !m1.namesMember(m2.node.nodeId()));
        assertEquals(1, lab.entriesWrittenUnchecked(back, m2, PredictionEventType.WORK_INTENT_ANNOUNCED), "O counts only the claim M2 made before the head the owner saw");
        List<String> lanes = lanesWritten(m1, m2);
        assertEquals(2, lanes.size(), "M1 holds both of M2's lanes");
        for (String lane : lanes) {
            lab.await("M1's projections end M2's lane " + lane, () -> {
                try {
                    return laneState(m1, lane).contains("state=ENDED");
                } catch (Exception unreadable) {
                    return false;
                }
            });
        }
        String lateOnOwner = laneState(back, lanes.get(1));
        assertFalse(lateOnOwner.contains("state=ACTIVE") || lateOnOwner.contains("state=ANNOUNCED"), "O holds no lane for the late claim: " + lateOnOwner);

        // Step 5. Neither lane holds a claim on either computer: M1's agent takes the late file, O's agent the early one.
        lab.await("M1's agent can claim the late file", () -> "ready".equals(m1a.tryClaim("Write late.txt", "late.txt").get("status")));
        lab.await("O's agent can claim the early file", () -> "ready".equals(o1.tryClaim("Write early.txt", "early.txt").get("status")));
    }
}
