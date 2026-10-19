package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Scenario S6 of ADR-0102 item 1, from the real run s6-run1 (owner PC and two member computers, one agent on each member,
 * mandatory review, 2026-10-19): every agent announced a producer lane, published its snapshot and was told to wait for
 * its review. The only two agents each held an open producer lane, so neither was offered the other's snapshot, and both
 * runs waited until their time ran out (ADR-0098 step 6, designer item 3).
 *
 * <p>Here the two computers are linked, each has one agent, and the project requires review of every snapshot. The
 * agents must review each other's snapshot without announcing a reviewer lane, and both changes must reach both mains.</p>
 */
@Timeout(240)
final class ProducersReviewEachOtherScenarioTest {

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
    void theOnlyAgentOfEachComputerReviewsTheOtherComputersSnapshotWhileItsOwnWaits() throws Exception {
        ScenarioAgent primes = lab.agent(lab.owner, "primes");
        ScenarioAgent fib = lab.agent(lab.friend, "fib");

        // Both agents announce at about the same moment, before either computer has the other's work group, as in s6-run1:
        // each computer then creates a work group of its own, and a snapshot's review must not depend on sharing one.
        lab.split();
        primes.claim("Write primes.txt", "primes.txt");
        fib.claim("Write fib.txt", "fib.txt");
        primes.create("primes.txt", "2 3 5 7\n");
        fib.create("fib.txt", "1 1 2 3 5\n");
        String primesSnapshot = primes.publish();
        String fibSnapshot = fib.publish();
        lab.heal();

        lab.drive("both snapshots are reviewed and integrated, so both files are on both mains",
                () -> lab.hasOnBranch(lab.owner, "primes.txt") && lab.hasOnBranch(lab.owner, "fib.txt") && lab.hasOnBranch(lab.friend, "primes.txt") && lab.hasOnBranch(lab.friend, "fib.txt"),
                primes, fib);

        lab.await("the two copies of main are the same commit", lab::mainsEqual);
        // Each snapshot was reviewed and accepted by the agent of the other computer, through a grant and not through a reviewer lane
        // of its own. (A re-merge of the computers' two integrations is reviewed again, so there may be more than these two verdicts.)
        var groups = lab.owner.store().workGroupProjection();
        var primesVerdict = groups.reviewValidationForSnapshot(primesSnapshot).orElseThrow();
        var fibVerdict = groups.reviewValidationForSnapshot(fibSnapshot).orElseThrow();
        assertEquals("ACCEPTED", primesVerdict.result());
        assertEquals("ACCEPTED", fibVerdict.result());
        assertEquals(fib.participant(), primesVerdict.targetParticipant(), "fib reviewed primes");
        assertEquals(primes.participant(), fibVerdict.targetParticipant(), "primes reviewed fib");
        assertEquals(2, lab.owner.store().events().stream().filter(event -> event.type() == org.synesis.coordination.domain.prediction.PredictionEventType.WORK_INTENT_ANNOUNCED).count(),
                "no agent announced a reviewer lane");
    }
}
