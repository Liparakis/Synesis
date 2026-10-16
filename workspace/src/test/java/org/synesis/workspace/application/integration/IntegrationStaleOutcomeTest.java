package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskCompletionState;

import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Lane;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Project;

/**
 * What a pass does when the log or the branch changed while its slow part ran with no append lock, and when the
 * process dies there (ADR-0098, invariant 8).
 */
class IntegrationStaleOutcomeTest {

    private static final List<String> PASSES = List.of("git", "rev-parse", "--verify", "HEAD");

    private static AgentResponse passWith(Project project, ThrowingRunnable betweenSlowPartAndRecording) throws Exception {
        return new IntegrationOrchestrationService(() -> {
            try {
                betweenSlowPartAndRecording.run();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }).orchestrateIntegration(project.root, project.store(), project.identity);
    }

    private static IntegrationAttemptPayload endOf(Project project, String failureReasonPrefix) throws Exception {
        return project.store().events().stream().filter(event -> event.type() == PredictionEventType.INTEGRATION_ATTEMPT_FAILED)
                .map(event -> {
                    try {
                        return IntegrationAttemptPayload.decode(event.payload());
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }).filter(payload -> payload.failureReason().startsWith(failureReasonPrefix)).findFirst().orElseThrow();
    }

    @Test
    void aControlHeadThatMovedDuringTheSlowPartIsNotAdvancedOverAndALaterPassIntegratesOnTheNewHead(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PASSES);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        AtomicReference<String> moved = new AtomicReference<>();

        AgentResponse stale = passWith(project, () -> {
            Files.writeString(root.resolve("unrelated.txt"), "moved\n");
            project.git("add", "unrelated.txt");
            project.git("commit", "-m", "unrelated commit made during the test gate");
            moved.set(project.head());
        });

        assertEquals(AgentStatus.RETRY_REQUIRED, stale.status(), stale.toString());
        assertEquals(AgentReason.INTEGRATION_STALE, stale.reason(), stale.toString());
        assertEquals(moved.get(), project.head(), "the branch was not advanced over the commit made meanwhile");
        assertFalse(project.headHas("src/f.txt"));
        assertEquals(0, project.count(PredictionEventType.INTEGRATION_COMMIT_CREATED));
        assertEquals(0, project.count(PredictionEventType.CONTROL_BRANCH_ADVANCED));
        IntegrationAttemptPayload ended = endOf(project, "STALE_ATTEMPT_BASIS:");
        assertEquals("pending", ended.status());
        assertTrue(ended.failureReason().contains("CONTROL_HEAD_MOVED"), ended.failureReason());
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, project.state("snap_1"));

        AgentResponse later = project.pump();

        assertEquals(AgentStatus.COMPLETED, later.status(), later.toString());
        assertTrue(project.headHas("src/f.txt"));
        assertTrue(project.headHas("unrelated.txt"));
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_1"));
    }

    @Test
    void aLaneWhoseClaimMovedToALaterEpochDuringTheSlowPartIsNotIntegratedFromItsOldSnapshot(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PASSES);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        String head = project.head();

        AgentResponse stale = passWith(project, () -> {
            assertTrue(new WorkIntentService(project.store(), project.identity).extendScope(new WorkIntentId(lane.id()), new ParticipantId(lane.participant()), new ClaimEpoch(1),
                    List.of(ResourceSelector.pathExact("src/g.txt")), null, null, List.of()).acquired());
            project.publish(lane, "snap_2", 2, "src/f.txt", "two\n");
        });

        assertEquals(AgentStatus.RETRY_REQUIRED, stale.status(), stale.toString());
        assertEquals(head, project.head());
        assertEquals(0, project.count(PredictionEventType.CONTROL_BRANCH_ADVANCED));
        String reason = endOf(project, "STALE_ATTEMPT_BASIS:").failureReason();
        assertTrue(reason.contains("STALE_CLAIM_EPOCH:snap_1") && reason.contains("SNAPSHOT_SUPERSEDED:snap_1"), reason);

        AgentResponse later = project.pump();

        assertEquals(AgentStatus.COMPLETED, later.status(), later.toString());
        assertEquals("two\n", Files.readString(root.resolve("src/f.txt")));
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_2"));
    }

    @Test
    void aPassThatDiesAfterTheSlowPartLeavesAnAttemptTheNextPassEndsAsInterrupted(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PASSES);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        String head = project.head();

        assertThrows(IllegalStateException.class, () -> passWith(project, () -> {
            throw new IllegalStateException("the process died");
        }));

        assertEquals(TaskCompletionState.INTEGRATING, project.state("snap_1"));
        assertEquals(head, project.head());
        assertEquals(0, project.count(PredictionEventType.INTEGRATION_COMMIT_CREATED));

        AgentResponse recovered = project.pump();

        assertEquals(AgentStatus.COMPLETED, recovered.status(), recovered.toString());
        assertEquals("INTERRUPTED", endOf(project, "INTERRUPTED").failureReason());
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_1"));
        assertTrue(project.headHas("src/f.txt"));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {

        void run() throws Exception;
    }
}
