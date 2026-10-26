package org.synesis.workspace.application.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.completion.CompleteTaskRequest;
import org.synesis.workspace.application.collaboration.CombineFixture;

/**
 * s5-run22 (ADR-0098 step 5 item 37): the combine wait says that nothing more is needed from the lane. It is true of a
 * combine lane that has published and of no other lane. The lane that published nothing is shown no wait at all (item 36).
 */
final class CombineWaitShownToTest {

    @TempDir
    Path temp;

    private static AgentResponse combineWait() {
        return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, Map.of("combinePending", true));
    }

    private static WorkIntent laneOf(PredictionEventStore store, String participant) {
        return store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participant)).findFirst().orElseThrow();
    }

    @Test
    void onlyACombineLaneIsToldToWaitForTheCombinesSnapshot() throws Exception {
        CombineFixture fixture = CombineFixture.create(temp);
        fixture.session("combine-combiner");
        fixture.collaboration.joinRepair(fixture.root, CombineFixture.PROVIDER, "combine-combiner", fixture.authorLane.intentId(), fixture.authorSnapshot.snapshotId());
        java.nio.file.Files.writeString(Path.of(fixture.binding("combine-combiner").worktreePath()).resolve(CombineFixture.FILE), "both\n");
        new AgentTaskCompletionService().completeTask(new CompleteTaskRequest(fixture.root, CombineFixture.PROVIDER, "combine-combiner", "combined both versions"));
        fixture.awaitingReview("combine-other", "src/g.txt", "combine-reviewer");
        PredictionEventStore store = fixture.store();
        AgentResponse wait = combineWait();

        AgentResponse combiner = AgentNextActionService.pumpShownTo(store, Optional.of(laneOf(store, fixture.participant("combine-combiner"))), wait);
        AgentResponse other = AgentNextActionService.pumpShownTo(store, Optional.of(laneOf(store, fixture.participant("combine-other"))), wait);

        assertSame(wait, combiner, "the combine lane is shown the wait");
        assertEquals(AgentStatus.READY, other.status(), "a lane of another group is not told that nothing more is needed from it: " + other.toJson());
    }
}
