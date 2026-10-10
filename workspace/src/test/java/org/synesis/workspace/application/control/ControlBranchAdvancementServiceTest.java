package org.synesis.workspace.application.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CoordinationTask;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskClaim;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.test.TestGit;

/**
 * Fixes the durable event sequence appended when the control branch advances and when an
 * interrupted advancement is recovered.
 */
class ControlBranchAdvancementServiceTest {

    private static final List<PredictionEventType> SEQUENCE = List.of(PredictionEventType.CONTROL_BRANCH_ADVANCED,
            PredictionEventType.TASK_INTEGRATED,
            PredictionEventType.OWNERSHIP_RELEASED,
            PredictionEventType.SESSION_FINALIZED);

    private record Fixture(Path repo, PredictionEventStore store, NodeIdentity identity, TaskSnapshotRecord snapshot, String base, String integration) {
    }

    private static Fixture fixture(Path root) throws Exception {
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        TestGit.run(repo, "init");
        TestGit.run(repo, "config", "user.email", "test@example.invalid");
        TestGit.run(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("README.md"), "base\n");
        TestGit.run(repo, "add", "README.md");
        TestGit.run(repo, "commit", "-m", "base");
        String base = TestGit.output(repo, "rev-parse", "HEAD");
        String integration = TestGit.output(repo, "commit-tree", "HEAD^{tree}", "-p", base, "-m", "integration");

        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(root.resolve("coordination"), project);
        UUID task = UUID.randomUUID();
        appendCommand(store, project, task, identity, PredictionEventType.TASK_CREATED, new CoordinationTask(task, project, "t", "cap", identity.nodeId(), "sup", "worker").encoded());
        appendCommand(store, project, task, identity, PredictionEventType.TASK_CLAIMED, new TaskClaim(task, identity.nodeId(), "sup", "worker").encoded());
        appendCommand(store, project, task, identity, PredictionEventType.OWNERSHIP_CLAIMED, new OwnershipClaim(task, "cap", identity.nodeId(), "sup", List.of("src/**"), 1).encoded());
        SnapshotProvenance provenance = new SnapshotProvenance(task, task, identity.nodeId(), "session", 1, List.of(), List.of(), List.of(), base, base);
        TaskSnapshotRecord snapshot = new TaskSnapshotRecord(task, "snap_1", identity.nodeId(), "sup", "worker", "session", base, integration, List.of("README.md"), List.of(), "snapshot", 1L, provenance, false);
        TaskSnapshotPayload payload = new TaskSnapshotPayload(task, "snap_1", identity.nodeId(), "sup", "worker", "session", base, integration, snapshot.changedPaths(), List.of(), "snapshot", provenance, false);
        store.append(task, PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), payload.encode(), identity);
        return new Fixture(repo, store, identity, snapshot, base, integration);
    }

    private static void appendCommand(PredictionEventStore store, UUID project, UUID task, NodeIdentity identity, PredictionEventType type, byte[] payload) throws Exception {
        store.append(task, type, identity.nodeId(), CoordinationCommand.create(UUID.randomUUID(), project, task, type, identity.nodeId(), payload, identity).encoded(), identity);
    }

    private static void assertSequence(Fixture fixture, int fromIndex) throws Exception {
        List<PredictionEvent> appended = fixture.store().events().subList(fromIndex, fixture.store().events().size());
        assertEquals(SEQUENCE, appended.stream().map(PredictionEvent::type).toList());
        IntegrationAttemptPayload advanced = IntegrationAttemptPayload.decode(appended.get(0).payload());
        assertEquals("att_1", advanced.attemptId());
        assertEquals(List.of("snap_1"), advanced.taskSnapshotIds());
        assertEquals(fixture.base(), advanced.expectedControlHead());
        assertEquals(fixture.integration(), advanced.integrationCommitSha());
        assertEquals("advanced", advanced.status());
        for (int index : new int[] {1, 3}) {
            TaskSnapshotPayload lane = TaskSnapshotPayload.decode(appended.get(index).payload());
            assertEquals("snap_1", lane.snapshotId());
            assertEquals(fixture.snapshot().taskId(), lane.taskId());
        }
        assertEquals(fixture.snapshot().taskId(), appended.get(2).predictionId());
    }

    @Test
    void advanceAppendsAdvancedIntegratedReleasedFinalizedInOrder(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        int before = fixture.store().events().size();

        var result = new ControlBranchAdvancementService()
                .advanceControlBranch(fixture.repo(), "att_1", fixture.base(), fixture.integration(), List.of(fixture.snapshot()), fixture.store(), fixture.identity());

        assertTrue(result.advanced(), result.failureReason());
        assertEquals(fixture.integration(), TestGit.output(fixture.repo(), "rev-parse", "HEAD"));
        assertSequence(fixture, before);
    }

    @Test
    void recoveryAppendsTheSameSequenceOnceAndIsIdempotent(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        TestGit.run(fixture.repo(), "merge", "--ff-only", fixture.integration());
        int before = fixture.store().events().size();
        var service = new ControlBranchAdvancementService();

        var result = service.recoverAdvancedControlBranch(fixture.repo(), "att_1", fixture.base(), fixture.integration(), List.of(fixture.snapshot()), fixture.store(), fixture.identity());

        assertTrue(result.advanced(), result.failureReason());
        assertSequence(fixture, before);
        int afterFirst = fixture.store().events().size();
        var again = service.recoverAdvancedControlBranch(fixture.repo(), "att_1", fixture.base(), fixture.integration(), List.of(fixture.snapshot()), fixture.store(), fixture.identity());
        assertTrue(again.advanced(), again.failureReason());
        assertEquals(afterFirst, fixture.store().events().size());
    }
}
