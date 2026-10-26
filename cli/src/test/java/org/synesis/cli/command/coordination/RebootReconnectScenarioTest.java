package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.coordination.TestComputers.Computer;

import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.ReplicationCard;
import org.synesis.coordination.replication.ReplicationJournal;

/**
 * Scenario of ADR-0102 item 1, round two, for ADR-0098 step 6: a reboot reconnect (design item 19, "listens on that port
 * again after a restart"). Three computers joined by the one-link flow. A member with an open lane is stopped as a computer
 * that reboots is: its Link, server and merge driver close and its folders stay. While it is down the other member does
 * work and integrates it. The member starts again on the same node and folders: it listens on the same card (the same
 * port and invitation, no new {@code MEMBER_CARD}), the others reconnect with no new invitation, it takes the {@code main}
 * it missed, its log and the other member's become byte-identical, and the claim of its open lane still holds.
 */
@Timeout(300)
final class RebootReconnectScenarioTest {

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

    @Test
    void aMemberThatRebootsListensOnItsCardAgainTakesTheMainItMissedAndKeepsItsOpenClaim() throws Exception {
        ScenarioAgent m1a = lab.agent(m1, "m1-a");
        ScenarioAgent m1b = lab.agent(m1, "m1-b");
        ScenarioAgent m1c = lab.agent(m1, "m1-c");
        ScenarioAgent m2a = lab.agent(m2, "m2-a");

        // Step 1. M2 opens a lane and writes into it. Every computer holds the claim.
        m2a.claim("Write early.txt", "early.txt");
        m2a.create("early.txt", "begun by m2-a\n");
        lab.await("O and M1 hold M2's claim", () -> lab.entriesWrittenUnchecked(owner, m2, PredictionEventType.WORK_INTENT_ANNOUNCED) == 1
                && lab.entriesWrittenUnchecked(m1, m2, PredictionEventType.WORK_INTENT_ANNOUNCED) == 1);
        assertNotEquals("ready", m1c.tryClaim("Also early.txt", "early.txt").get("status"), "M1 is refused M2's file while they are connected");
        ReplicationCard card = ReplicationCard.read(m2.location.coordinationDirectory()).orElseThrow();
        long cardEntries = lab.entriesWrittenUnchecked(m1, m2, PredictionEventType.MEMBER_CARD);
        assertEquals(1, cardEntries, "M2 published its card once");

        // Step 2. M2 reboots: it stops, and while it is down M1's two agents write, review each other and integrate.
        m2.stop();
        lab.await("neither O nor M1 holds a session with M2", () -> !owner.owner.connectedTo(m2.node.nodeId()) && !m1.owner.connectedTo(m2.node.nodeId()));
        m1a.claim("Write m1a.txt", "m1a.txt");
        m1b.claim("Write m1b.txt", "m1b.txt");
        m1a.create("m1a.txt", "written by m1-a\n");
        m1b.create("m1b.txt", "written by m1-b\n");
        m1a.publishWhileLinked();
        m1b.publishWhileLinked();
        lab.drive("M1's main holds both files", () -> lab.hasOnBranch(m1, "m1a.txt") && lab.hasOnBranch(m1, "m1b.txt"), m1a, m1b);
        assertNotEquals(lab.head(m2), lab.head(m1), "M2 is behind M1");

        // Step 3. M2 starts again on the same node and folders: the same card, with no new invitation.
        Computer back = lab.restart(m2);
        assertEquals(back.node.nodeId(), m2.node.nodeId(), "the same node");
        assertEquals(Optional.of(card), ReplicationCard.read(back.location.coordinationDirectory()), "the same port and invitation");
        assertTrue(ReplicationJournal.read(back.location.coordinationDirectory()).stream().anyMatch(line -> line.endsWith("CARD_RESUMED port=" + card.port())),
                "the journal says the card was resumed");
        lab.await("O and M1 are linked to M2 again", () -> lab.linked(owner, back) && lab.linked(m1, back));
        assertEquals(cardEntries, lab.entriesWrittenUnchecked(m1, back, PredictionEventType.MEMBER_CARD), "no new MEMBER_CARD");

        // Step 4. M2 takes the main it missed, and it and M1 hold the same logs.
        lab.drive("M2's main holds M1's files", () -> lab.mainsEqual(back, m1) && lab.hasOnBranch(back, "m1a.txt") && lab.hasOnBranch(back, "m1b.txt"), m1a, m1b);
        lab.await("M2 and M1 hold identical logs", () -> lab.logsIdentical(back, m1));
        lab.assertLogsIdentical(back, m1);

        // Step 5. M2's open claim survived the reboot: M1 is refused the file again, and M2's agent still holds its lane.
        assertNotEquals("ready", m1c.tryClaim("Still early.txt", "early.txt").get("status"), "M2's claim holds after its reboot");
        assertEquals(1, lab.entriesWrittenUnchecked(m1, back, PredictionEventType.WORK_INTENT_ANNOUNCED), "M2 wrote no second lane");
    }
}
