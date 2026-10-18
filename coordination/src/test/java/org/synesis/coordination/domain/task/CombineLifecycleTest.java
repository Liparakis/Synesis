package org.synesis.coordination.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.MissingReferenceException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * A combine names snapshots that must be known, and on an own append integrated; one combine per ID (ADR-0098 step 5).
 */
final class CombineLifecycleTest {

    private static final String ORIGINAL = "snap_original";

    private final UUID projectId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID laneId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private Path root;
    private NodeIdentity identity;
    private WorkIntent intent;
    private PredictionEventStore store;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        root = temp;
        identity = NodeIdentity.generate();
        intent = new WorkIntent(laneId, projectId, "agt-owner", "codex", taskId, "implement", "acceptance", "base",
                List.of(ResourceSelector.pathExact("src/a.py")), 1, groupId, WorkIntent.defaultAuthorityLineage(laneId),
                WorkIntent.Status.ANNOUNCED);
        store = new PredictionEventStore(temp, projectId);
        assertTrue(new WorkIntentService(store, identity).announce(intent).acquired());
        store = new PredictionEventStore(temp, projectId);
    }

    private TaskSnapshotPayload snapshot(String snapshotId) {
        SnapshotProvenance provenance = new SnapshotProvenance(groupId, laneId, intent.authorityLineageId(), "agt-owner",
                "session-owner", 1, List.of(), List.of(), List.of("PATH:src/a.py"), "refs/synesis/snapshots/" + snapshotId,
                "integrity-" + snapshotId, "artifacts-" + snapshotId);
        return new TaskSnapshotPayload(taskId, snapshotId, "node", "supervisor", "worker", "session-owner", "base",
                "commit-" + snapshotId, List.of("src/a.py"), List.of(), snapshotId, provenance, false);
    }

    private void append(PredictionEventType type, byte[] payload) throws Exception {
        store.append(UUID.randomUUID(), type, identity.nodeId(), payload, identity);
    }

    private void integrateOriginal() throws Exception {
        append(PredictionEventType.TASK_SNAPSHOT_CREATED, snapshot(ORIGINAL).encode());
        append(PredictionEventType.INTEGRATION_ATTEMPT_STARTED,
                new IntegrationAttemptPayload("att_original", projectId, List.of(ORIGINAL), "base", "integration",
                        "started", "").encode());
        append(PredictionEventType.TASK_INTEGRATED, snapshot(ORIGINAL).encode());
        assertEquals(TaskCompletionState.INTEGRATED,
                store.taskCompletionProjection().snapshotState(ORIGINAL).orElseThrow());
    }

    private static CombinePayload combine(String combineId, String... snapshots) {
        return new CombinePayload(combineId, "remerge-1", "other-head", "own-head", "base-commit", "theirs-commit",
                List.of(snapshots));
    }

    private IOException refused(CombinePayload payload) {
        return assertThrows(IOException.class, () -> append(PredictionEventType.COMBINE_REQUIRED, payload.encode()));
    }

    @Test
    void anAppliedCombineIsFoundByItsWorkGroupAndNoOtherGroupFindsOne() throws Exception {
        integrateOriginal();
        CombinePayload payload = combine("combine-1", ORIGINAL);

        append(PredictionEventType.COMBINE_REQUIRED, payload.encode());

        TaskCompletionProjection projection = store.taskCompletionProjection();
        assertEquals(List.of(payload), projection.combines());
        assertEquals(payload, projection.combineOfGroup(payload.workGroupId()).orElseThrow());
        assertTrue(projection.combineOfGroup(groupId).isEmpty());
        assertTrue(projection.combineOfGroup(UUID.randomUUID()).isEmpty());
        assertTrue(projection.combineOfGroup(payload.taskId()).isEmpty());

        TaskCompletionProjection replayed = new PredictionEventStore(root, projectId).taskCompletionProjection();
        assertEquals(List.of(payload), replayed.combines());
        assertEquals(payload, replayed.combineOfGroup(payload.workGroupId()).orElseThrow());
        assertTrue(replayed.combineOfGroup(groupId).isEmpty());
    }

    @Test
    void aCombineDoesNotChangeTheNamedSnapshotsOrTheirTask() throws Exception {
        integrateOriginal();

        append(PredictionEventType.COMBINE_REQUIRED, combine("combine-1", ORIGINAL).encode());

        TaskCompletionProjection projection = store.taskCompletionProjection();
        assertEquals(TaskCompletionState.INTEGRATED, projection.snapshotState(ORIGINAL).orElseThrow());
        assertEquals(TaskCompletionState.INTEGRATED, projection.taskState(taskId));
    }

    @Test
    void anUnknownSnapshotIsAMissingReferenceAndNothingIsRecorded() throws Exception {
        integrateOriginal();

        IOException failure = refused(combine("combine-1", ORIGINAL, "snap_unknown"));

        assertTrue(failure instanceof MissingReferenceException, failure.getClass().getName());
        assertEquals("COMBINE_SNAPSHOT_NOT_FOUND", failure.getMessage());
        assertTrue(store.taskCompletionProjection().combines().isEmpty());
        assertTrue(new PredictionEventStore(root, projectId).taskCompletionProjection().combines().isEmpty());
    }

    @Test
    void aKnownButNotIntegratedSnapshotRefusesAnOwnAppend() throws Exception {
        append(PredictionEventType.TASK_SNAPSHOT_CREATED, snapshot(ORIGINAL).encode());

        IOException failure = refused(combine("combine-1", ORIGINAL));

        assertEquals("COMBINE_SNAPSHOT_NOT_INTEGRATED", failure.getMessage());
        assertTrue(failure instanceof CoordinationException);
        assertFalse(failure instanceof MissingReferenceException);
        assertTrue(store.taskCompletionProjection().combines().isEmpty());
    }

    @Test
    void aRepeatedCombineIdIsRefusedEvenWithDifferentContent() throws Exception {
        integrateOriginal();
        append(PredictionEventType.COMBINE_REQUIRED, combine("combine-1", ORIGINAL).encode());

        CoordinationException again = assertThrows(CoordinationException.class,
                () -> append(PredictionEventType.COMBINE_REQUIRED, combine("combine-1", ORIGINAL).encode()));
        assertEquals("COMBINE_EXISTS", again.getMessage());
        CombinePayload different = new CombinePayload("combine-1", "remerge-2", "a", "b", "c", "d", List.of(ORIGINAL));
        assertEquals("COMBINE_EXISTS", refused(different).getMessage());

        assertEquals(1, store.taskCompletionProjection().combines().size());
        append(PredictionEventType.COMBINE_REQUIRED, combine("combine-2", ORIGINAL).encode());
        assertEquals(2, store.taskCompletionProjection().combines().size());
    }
}
