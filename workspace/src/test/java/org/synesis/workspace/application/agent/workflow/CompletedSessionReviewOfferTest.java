package org.synesis.workspace.application.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.application.collaboration.CombineFixture;

/**
 * ADR-0098 step 5 item 42: a completed session in a work group that is still active is offered an open review it may take,
 * the candidates an active session is offered, before it is told to wait. Told only to wait, the last snapshot published
 * after every other agent had reviewed one and finished stayed unreviewed.
 */
final class CompletedSessionReviewOfferTest {

    @TempDir
    Path temp;

    @Test
    void aCompletedSessionIsOfferedTheOpenSnapshotOfAnotherAuthor() throws Exception {
        CombineFixture fixture = CombineFixture.create(temp);
        fixture.publishedForReview("done-agent", "src/a.txt");
        TaskSnapshotRecord other = fixture.publishedForReview("last-agent", "src/b.txt");

        AgentResponse response = ReviewLaneProjection.completedReviewAction(fixture.location, fixture.binding("done-agent").sessionId());

        assertEquals(AgentNextAction.REQUEST_COORDINATION, response.nextAction(), response.toString());
        assertTrue(response.toString().contains(other.snapshotId()), "the other author's snapshot is the offer: " + response);
    }

    @Test
    void aCompletedSessionWhoseOnlyOpenSnapshotIsItsOwnIsToldToWait() throws Exception {
        CombineFixture fixture = CombineFixture.create(temp);
        fixture.publishedForReview("done-agent", "src/a.txt");

        AgentResponse response = ReviewLaneProjection.completedReviewAction(fixture.location, fixture.binding("done-agent").sessionId());

        assertEquals(AgentNextAction.WAIT, response.nextAction(), response.toString());
    }
}
