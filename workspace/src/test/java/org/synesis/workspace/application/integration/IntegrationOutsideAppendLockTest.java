package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.persistence.ReplicationRole;

import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Lane;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Project;
import org.synesis.workspace.test.PortableTestCommand;

/**
 * An integration pass keeps the project's append lock only to record, never through its worktree and test gate
 * (ADR-0098, invariant 8), runs one at a time, and merges only the lanes its own computer introduced.
 */
class IntegrationOutsideAppendLockTest {

    /** A pass held open inside the project's validation command, until {@link #release()}. */
    private static final class HeldPass implements AutoCloseable {

        private final Project project;
        private final Path started;
        private final Path release;
        private final ExecutorService pool = Executors.newSingleThreadExecutor();
        private final Future<AgentResponse> pass;

        HeldPass(Project project, Path signals) throws Exception {
            this.project = project;
            this.started = signals.resolve("started");
            this.release = signals.resolve("release");
            project.declare(PortableTestCommand.fixture("gate", started.toString(), release.toString()), 120);
            Lane lane = project.announce("agt_first", "src/a.txt");
            project.publish(lane, "snap_1", 1, "src/a.txt", "a\n");
            pass = pool.submit(project::pump);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!Files.exists(started) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(started), "the test gate never started");
        }

        void release() throws IOException {
            Files.writeString(release, "go");
        }

        AgentResponse finish() throws Exception {
            release();
            return pass.get(60, TimeUnit.SECONDS);
        }

        long gateRuns() throws IOException {
            return Files.readAllLines(started).size();
        }

        @Override
        public void close() {
            try {
                release();
                pass.get(60, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void anotherWriterAppendsWithinTheNormalLockWaitWhileTheTestGateRuns(@TempDir Path root, @TempDir Path signals) throws Exception {
        Project project = new Project(root);
        try (HeldPass held = new HeldPass(project, signals)) {
            long begin = System.nanoTime();

            // Takes the project append lock, which waits two seconds at most; during a merge it used to give up.
            Lane other = project.announce("agt_other", "src/other.txt");

            assertNotNull(other);
            assertTrue(Duration.ofNanos(System.nanoTime() - begin).toMillis() < 1_500, "the writer did not wait for the merge");
            AgentResponse done = held.finish();
            assertEquals(AgentStatus.COMPLETED, done.status(), done.toString());
            assertTrue(project.headHas("src/a.txt"), "the merge still integrated the lane while the other lane was announced");
        }
    }

    @Test
    void aSecondPassWhileOneRunsReturnsBusyAndRunsNoSecondTestGate(@TempDir Path root, @TempDir Path signals) throws Exception {
        Project project = new Project(root);
        try (HeldPass held = new HeldPass(project, signals)) {
            AgentResponse busy = project.pump();

            assertEquals(AgentStatus.WAITING, busy.status(), busy.toString());
            assertEquals(AgentReason.INTEGRATION_PENDING, busy.reason(), busy.toString());
            assertEquals(Boolean.TRUE, ((Map<?, ?>) busy.result()).get("integrationRunning"));
            assertEquals(1, held.gateRuns(), "no second test gate was started");

            AgentResponse done = held.finish();

            assertEquals(AgentStatus.COMPLETED, done.status(), done.toString());
            assertEquals(1, held.gateRuns());
            assertEquals(1, project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED));
        }
    }

    @Test
    void aMembersPassIntegratesItsOwnLaneAndWritesOnlyToItsOwnLog(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(List.of("git", "rev-parse", "--verify", "HEAD"));
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        Path coordination = root.resolve(".synesis/coordination");
        String head = project.head();
        ReplicationRole.register(coordination, ReplicationRole.memberOf("leader-node"));
        try {
            AgentResponse asMember = project.pump();

            // ADR-0098 step 5: every computer merges the lanes it introduced, on its own checkout.
            assertEquals(AgentStatus.COMPLETED, asMember.status(), asMember.toString());
            assertEquals(1, project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED));
            assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_1"));
            assertTrue(project.headHas("src/f.txt"));
            assertFalse(project.head().equals(head));
            assertTrue(project.store().events().stream().allMatch(event -> event.actorNodeId().equals(project.identity.nodeId())),
                    "the pass wrote nothing under another computer's name");
            assertFalse(Files.exists(coordination.resolve("peers")), "no other computer's log was touched");
        } finally {
            ReplicationRole.register(coordination, ReplicationRole.leader());
        }
    }
}
