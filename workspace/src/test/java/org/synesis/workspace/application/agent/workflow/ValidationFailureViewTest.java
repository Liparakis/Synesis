package org.synesis.workspace.application.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.integration.ValidationRefusalPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Who is shown what the project's validation command printed for a refused snapshot: the lane that owes the
 * correction, and nobody else.
 */
final class ValidationFailureViewTest {

    private static final String OUTPUT = "the line the command printed";

    private final UUID projectId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID laneId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final NodeIdentity identity = NodeIdentity.generate();
    private final WorkIntent intent = new WorkIntent(laneId, projectId, "agt-owner", "codex", taskId, "implement", "acceptance", "base",
            List.of(ResourceSelector.pathExact("src/a.py")), 1, groupId, WorkIntent.defaultAuthorityLineage(laneId), WorkIntent.Status.ANNOUNCED);

    ValidationFailureViewTest() throws Exception {
    }

    private PredictionEventStore refusedOnce(Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, projectId);
        assertTrue(new WorkIntentService(store, identity).announce(intent).acquired());
        store = new PredictionEventStore(temp, projectId);
        SnapshotProvenance provenance = new SnapshotProvenance(groupId, laneId, intent.authorityLineageId(), "agt-owner", "session-owner", 1, List.of(), List.of(),
                List.of("PATH:src/a.py"), "refs/synesis/snapshots/snap_s1", "integrity", "artifacts");
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(taskId, "snap_s1", "node", "supervisor", "worker", "session-owner", "base", "commit-s1", List.of("src/a.py"),
                List.of(), "snap_s1", provenance, false);
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);
        for (PredictionEventType type : List.of(PredictionEventType.INTEGRATION_ATTEMPT_STARTED, PredictionEventType.INTEGRATION_ATTEMPT_FAILED)) {
            boolean ended = type == PredictionEventType.INTEGRATION_ATTEMPT_FAILED;
            IntegrationAttemptPayload attempt = new IntegrationAttemptPayload("att_1", projectId, List.of("snap_s1"), "base", "integration", ended ? "pending" : "started",
                    ended ? "VALIDATION_FAILED:non_zero_exit" : "");
            store.append(UUID.randomUUID(), type, identity.nodeId(), attempt.encode(), identity);
        }
        ValidationRefusalPayload refusal = new ValidationRefusalPayload("att_1", "snap_s1", taskId, groupId, intent.authorityLineageId(), 1, laneId, 1, "agt-owner",
                "integration", "[\"git\",\"status\"]", ".", "non_zero_exit", 1, OUTPUT);
        store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_VALIDATION_REFUSED, identity.nodeId(), refusal.encode(), identity);
        return store;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> failureOf(Map<String, Object> snapshot) {
        return (Map<String, Object>) snapshot.get("validationFailure");
    }

    @Test
    void theProducerIsShownTheOutputWhatWasRunAndTheRepairsItHasLeft(@TempDir Path temp) throws Exception {
        PredictionEventStore store = refusedOnce(temp);
        TaskSnapshotRecord refused = store.taskCompletionProjection().allSnapshots().getFirst();

        Map<String, Object> failure = failureOf(CollaborationResponseMapper.rejectedSnapshotMap(refused, store));

        assertEquals(OUTPUT, failure.get("output"));
        assertEquals("[\"git\",\"status\"]", failure.get("command"));
        assertEquals(".", failure.get("workingDirectory"));
        assertEquals("non_zero_exit", failure.get("outcome"));
        assertEquals(2, ((Number) failure.get("repairsLeft")).intValue(), "the repair now owed and one more");
    }

    @Test
    void everyOtherViewOfTheRefusedSnapshotLeavesTheOutputOut(@TempDir Path temp) throws Exception {
        PredictionEventStore store = refusedOnce(temp);
        TaskSnapshotRecord refused = store.taskCompletionProjection().allSnapshots().getFirst();

        Map<String, Object> single = failureOf(CollaborationResponseMapper.snapshotMap(refused, store));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed = (List<Map<String, Object>>) CollaborationView.collaborationDetails(store, null).get("snapshots");

        assertEquals(1, listed.size());
        for (Map<String, Object> failure : List.of(single, failureOf(listed.getFirst()))) {
            assertFalse(failure.containsKey("output"), failure.toString());
            assertEquals("non_zero_exit", failure.get("outcome"));
            assertEquals(1, ((Number) failure.get("exitCode")).intValue());
            assertEquals("[\"git\",\"status\"]", failure.get("command"));
            assertEquals(".", failure.get("workingDirectory"));
        }
    }
}
