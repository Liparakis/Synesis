package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stall of {@code OwnerDownMembersScenarioTest} and {@code ThreeComputersMainMeetingScenarioTest} under load
 * (ADR-0098 step 5 item 42 and its follow-up): when the last snapshot is published, every other agent has reviewed one,
 * been integrated and finished its session. Each of them is offered the open snapshot, and the request is refused, so
 * the snapshot stays {@code REVIEW_PENDING} and the copies of {@code main} are equal but lack its file. Here the order
 * that load produces is forced: two agents review each other and finish, then the third publishes.
 */
@Timeout(240)
final class LastSnapshotReviewedByFinishedAgentsScenarioTest {

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
    void aSnapshotPublishedAfterEveryOtherAgentFinishedIsReviewedByAFinishedAgent() throws Exception {
        ScenarioAgent first = lab.agent(lab.owner, "first");
        ScenarioAgent second = lab.agent(lab.owner, "second");
        ScenarioAgent last = lab.agent(lab.owner, "last");
        first.claim("Write first.txt", "first.txt");
        second.claim("Write second.txt", "second.txt");
        last.claim("Write last.txt", "last.txt");
        first.create("first.txt", "written by first\n");
        second.create("second.txt", "written by second\n");
        last.create("last.txt", "written by last\n");
        first.publishWhileLinked();
        second.publishWhileLinked();

        lab.drive("the first two agents review each other and are integrated", () -> lab.hasOnBranch(lab.owner, "first.txt") && lab.hasOnBranch(lab.owner, "second.txt"),
                first, second);

        last.publishWhileLinked();
        lab.drive("the last snapshot is reviewed by an agent that has finished, and integrated", () -> lab.hasOnBranch(lab.owner, "last.txt"), first, second, last);
        assertTrue(lab.hasOnBranch(lab.owner, "last.txt"));
    }
}
