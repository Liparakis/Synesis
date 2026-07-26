package org.synesis.coordination.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.coordination.persistence.PredictionEventStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void taskCompletionProjectionStateTransitionsAndRestart(@TempDir Path tempDir) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity workerIdentity = NodeIdentity.generate();

        PredictionEventStore store1 = new PredictionEventStore(tempDir, projectId);

        TaskSnapshotPayload snapPayload = new TaskSnapshotPayload(
                taskId, "snap_100", workerIdentity.nodeId(), "sup-1", "worker-1", "sess-1",
                "base1", "commit1", List.of("file.txt"), List.of(), "First task snapshot");

        store1.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                workerIdentity.nodeId(), snapPayload.encode(), workerIdentity);

        TaskCompletionProjection proj1 = store1.taskCompletionProjection();
        assertEquals(TaskCompletionState.SNAPSHOT_READY, proj1.taskState(taskId));
        assertTrue(proj1.findSnapshotForTask(taskId).isPresent());
        assertEquals("commit1", proj1.findSnapshotForTask(taskId).get().commitSha());

        // Restart recovery check
        PredictionEventStore store2 = new PredictionEventStore(tempDir, projectId);
        TaskCompletionProjection proj2 = store2.taskCompletionProjection();

        assertEquals(TaskCompletionState.SNAPSHOT_READY, proj2.taskState(taskId));
        assertTrue(proj2.findSnapshotForTask(taskId).isPresent());
        assertEquals("snap_100", proj2.findSnapshotForTask(taskId).get().snapshotId());
    }
}
