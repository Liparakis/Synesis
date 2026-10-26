package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;

/**
 * Scenario S7 of ADR-0102 item 1, from the real run s5-run15 (PC and VM, 2026-10-19): a Codex agent whose lane held finished
 * but unpublished work was resumed after its publication was refused as stale, asked for its next action, and was told to
 * wait for the review of another lane's snapshot. It stopped, its lane was held, and {@code NOTES.md} never landed. The wait was
 * the integration pass's answer for its computer's own snapshots, shown to a caller who had a step of its own.
 *
 * <p>Here one computer holds two lanes. One agent has published and its session has ended (so nobody can be admitted to review
 * its snapshot). The other has written its file and has not published: its next action must be its own, and it must publish.</p>
 */
@Timeout(240)
final class LaneWithWorkWaitsForNoOtherLaneScenarioTest {

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
    void aLaneWithUnpublishedWorkIsNotToldToWaitForAnotherLanesReview() throws Exception {
        ScenarioAgent published = lab.agent(lab.owner, "published");
        ScenarioAgent writing = lab.agent(lab.owner, "writing");

        published.claim("Write a.txt", "a.txt");
        writing.claim("Write b.txt", "b.txt");
        published.create("a.txt", "a\n");
        writing.create("b.txt", "b\n");
        published.publish();

        // Its author's session ended and the lane is held: nobody is offered its snapshot to review (the author cannot answer), so
        // the integration pass's wait for review is all that was left to show.
        new WorkIntentService(lab.owner.store(), lab.owner.node).suspend(published.participant());

        Map<String, Object> next = writing.next();
        assertNotEquals("wait", next.get("nextAction"), "a lane that has not published is not told to wait for another lane's review: " + next);
        assertNotEquals(Boolean.TRUE, ScenarioAgent.inner(next).get("reviewPending"), "what is pending is another lane's: " + next);

        assertEquals("REVIEW_PENDING", ScenarioAgent.inner(writing.finish()).get("snapshotState"), "it can publish: its own step was shown");
    }
}
