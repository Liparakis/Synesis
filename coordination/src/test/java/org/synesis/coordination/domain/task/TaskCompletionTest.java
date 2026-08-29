package org.synesis.coordination.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Tests Stage 2B Slice 3 task completion and integration projection state machine.
 */
class TaskCompletionTest {

    @Test
    void taskSnapshotPayloadRoundTrip() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskSnapshotPayload payload = new TaskSnapshotPayload(
                taskId, "snap_123456", "node-1", "sup-1", "worker-1", "session-1",
                "base123", "commit456", List.of("src/Main.java"), List.of("req_TEST"),
                "Added feature");

        byte[] encoded = payload.encode();
        TaskSnapshotPayload decoded = TaskSnapshotPayload.decode(encoded);

        assertEquals(taskId, decoded.taskId());
        assertEquals("snap_123456", decoded.snapshotId());
        assertEquals("node-1", decoded.nodeId());
        assertEquals("sup-1", decoded.supervisorId());
        assertEquals("worker-1", decoded.workerId());
        assertEquals("session-1", decoded.providerSessionId());
        assertEquals("base123", decoded.baseCommit());
        assertEquals("commit456", decoded.commitSha());
        assertEquals(List.of("src/Main.java"), decoded.changedPaths());
        assertEquals(List.of("req_TEST"), decoded.capabilityDependencies());
        assertEquals("Added feature", decoded.summary());
    }

    @Test
    void integrationAttemptPayloadRoundTrip() throws Exception {
        UUID projectId = UUID.randomUUID();
        IntegrationAttemptPayload payload = new IntegrationAttemptPayload(
                "att_789", projectId, List.of("snap_1", "snap_2"),
                "head123", "intg456", "advanced", "");

        byte[] encoded = payload.encode();
        IntegrationAttemptPayload decoded = IntegrationAttemptPayload.decode(encoded);

        assertEquals("att_789", decoded.attemptId());
        assertEquals(projectId, decoded.projectId());
        assertEquals(List.of("snap_1", "snap_2"), decoded.taskSnapshotIds());
        assertEquals("head123", decoded.expectedControlHead());
        assertEquals("intg456", decoded.integrationCommitSha());
        assertEquals("advanced", decoded.status());
    }

    @Test
    void preparedCompletionPayloadRoundTripsAndFencesBeforePublication() throws Exception {
        CompletionPreparedPayload payload = new CompletionPreparedPayload(
                UUID.randomUUID(), "cmp_123", UUID.randomUUID(), 4,
                "base", "refs/synesis/prepared/cmp_123", "tree123", List.of("src/Main.java"));
        CompletionPreparedPayload decoded = CompletionPreparedPayload.decode(payload.encode());
        assertEquals(payload, decoded);
    }

    @Test
    void taskCompletionProjectionStateTransitionsAndRestart(@TempDir Path tempDir) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity workerIdentity = NodeIdentity.generate();

        PredictionEventStore store1 = new PredictionEventStore(tempDir, projectId);

        TaskSnapshotPayload snapPayload = new TaskSnapshotPayload(
                taskId, "snap_100", workerIdentity.nodeId(), "sup-1", "worker-1", "sess-1",
                "base1", "commit1", List.of("file.txt"), List.of(), "First task snapshot");

        CompletionPreparedPayload prepared = new CompletionPreparedPayload(taskId, "cmp_100", taskId, 1,
                "base1", "refs/synesis/prepared/cmp_100", "tree1", List.of("file.txt"));
        store1.append(UUID.randomUUID(), PredictionEventType.COMPLETION_PREPARED,
                workerIdentity.nodeId(), prepared.encode(), workerIdentity);
        assertEquals(TaskCompletionState.COMPLETION_PREPARED,
                store1.taskCompletionProjection()
                        .taskState(taskId));

        store1.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                workerIdentity.nodeId(), snapPayload.encode(), workerIdentity);

        TaskCompletionProjection proj1 = store1.taskCompletionProjection();
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, proj1.taskState(taskId));
        assertTrue(proj1.findSnapshotForTask(taskId)
                .isPresent());
        assertEquals("commit1",
                proj1.findSnapshotForTask(taskId)
                        .get()
                        .commitSha());

        // Restart recovery check
        PredictionEventStore store2 = new PredictionEventStore(tempDir, projectId);
        TaskCompletionProjection proj2 = store2.taskCompletionProjection();

        assertEquals(TaskCompletionState.INTEGRATION_PENDING, proj2.taskState(taskId));
        assertTrue(proj2.findSnapshotForTask(taskId)
                .isPresent());
        assertEquals("snap_100",
                proj2.findSnapshotForTask(taskId)
                        .get()
                        .snapshotId());
    }

    @Test
    void blockedAndRepairStatesLeaveEligibleQueue(@TempDir Path tempDir) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(tempDir, projectId);
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(taskId, "snap_state", identity.nodeId(), "sup",
                "worker", "session", "base", "commit", List.of("src/a"), List.of(), "state test");
        store.append(taskId, PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);
        IntegrationAttemptPayload blocked = new IntegrationAttemptPayload("blocked", projectId,
                List.of("snap_state"), "head", "", "blocked", "stale contract");
        store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_BLOCKED,
                identity.nodeId(), blocked.encode(), identity);
        assertEquals(TaskCompletionState.INTEGRATION_BLOCKED,
                store.taskCompletionProjection()
                        .taskState(taskId));
        assertTrue(store.taskCompletionProjection()
                .eligibleSnapshots()
                .isEmpty());
    }

    @Test
    void transientAttemptFailureReturnsToPendingQueue(@TempDir Path tempDir) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(tempDir, projectId);
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(taskId, "snap_pending", identity.nodeId(), "sup",
                "worker", "session", "base", "commit", List.of("src/a"), List.of(), "pending test");
        store.append(taskId, PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);
        IntegrationAttemptPayload attempt = new IntegrationAttemptPayload("att_pending", projectId,
                List.of("snap_pending"), "head", "", "started", "");
        store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_STARTED,
                identity.nodeId(), attempt.encode(), identity);
        IntegrationAttemptPayload retry = new IntegrationAttemptPayload("att_pending", projectId,
                List.of("snap_pending"), "head", "", "pending", "temporary integration I/O failure");
        store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_FAILED,
                identity.nodeId(), retry.encode(), identity);

        assertEquals(TaskCompletionState.INTEGRATION_PENDING,
                store.taskCompletionProjection()
                        .taskState(taskId));
        assertEquals(List.of("snap_pending"),
                store.taskCompletionProjection()
                        .eligibleSnapshots()
                        .stream()
                        .map(TaskSnapshotRecord::snapshotId)
                        .toList());
    }

    @Test
    void preparedCompletionUnwindRestoresNextClaimEpoch(@TempDir Path tempDir) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        UUID laneId = UUID.randomUUID();
        PredictionEventStore store = new PredictionEventStore(tempDir, projectId);
        WorkIntent intent = new WorkIntent(laneId, projectId, "agt_owner", "codex", taskId,
                "implement", "tests", "base", List.of(ResourceSelector.pathExact("src/a")),
                1, projectId, WorkIntent.Status.ANNOUNCED);
        new WorkIntentService(store, identity).announce(intent);
        store = new PredictionEventStore(tempDir, projectId);
        CompletionPreparedPayload prepared = new CompletionPreparedPayload(taskId, "cmp_unwind", laneId,
                1, "base", "refs/synesis/prepared/cmp_unwind", "tree", List.of("src/a"));
        store.append(taskId, PredictionEventType.COMPLETION_PREPARED, identity.nodeId(),
                prepared.encode(), identity);
        WorkIntent replacement = new WorkIntent(laneId, projectId, "agt_owner", "codex", taskId,
                "implement", "tests", "base", intent.selectors(), 2, projectId, WorkIntent.Status.ANNOUNCED);
        CompletionUnwoundPayload unwind = new CompletionUnwoundPayload(prepared, replacement);
        assertEquals(replacement,
                CompletionUnwoundPayload.decode(unwind.encode())
                        .replacementIntent());
        store.append(taskId, PredictionEventType.COMPLETION_UNWOUND, identity.nodeId(),
                unwind.encode(), identity);
        assertEquals(TaskCompletionState.ACTIVE,
                store.taskCompletionProjection()
                        .taskState(taskId));
        assertTrue(store.taskCompletionProjection()
                .findPrepared(taskId)
                .isEmpty());
        assertEquals(2,
                store.collaborationProjection()
                        .intent(laneId)
                        .orElseThrow()
                        .version());
    }
}
