package org.synesis.workspace;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.workspace.application.task.TaskSnapshotService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for task completion and integration application services.
 */
class TaskIntegrationServiceTest {

    @Test
    void taskSnapshotRecordInvariants() {
        UUID taskId = UUID.randomUUID();
        TaskSnapshotRecord rec = new TaskSnapshotRecord(
                taskId, "snap_test", "node-1", "sup-1", "worker-1", "sess-1",
                "base", "commit", List.of("src/App.java"), List.of(), "Completed work", System.currentTimeMillis());

        assertEquals(taskId, rec.taskId());
        assertEquals("snap_test", rec.snapshotId());
        assertEquals("node-1", rec.nodeId());
        assertEquals("sup-1", rec.supervisorId());
        assertEquals("worker-1", rec.workerId());
        assertEquals("base", rec.baseCommit());
        assertEquals("commit", rec.commitSha());
        assertEquals(List.of("src/App.java"), rec.changedPaths());
        assertEquals("Completed work", rec.summary());
    }

    @Test
    void taskSnapshotServiceInstantiates() {
        TaskSnapshotService service = new TaskSnapshotService();
        assertNotNull(service);
    }
}
