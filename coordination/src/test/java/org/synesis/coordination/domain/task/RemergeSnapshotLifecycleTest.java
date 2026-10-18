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
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.MissingReferenceException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * A re-merge snapshot is reviewed like a snapshot but sits outside its task's history (ADR-0098 step 5).
 */
final class RemergeSnapshotLifecycleTest {

    private static final String ORIGINAL = "snap_original";
    private static final String REMERGE = "snap_remerge";

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
        intent = new WorkIntent(laneId,
                projectId,
                "agt-owner",
                "codex",
                taskId,
                "implement",
                "acceptance",
                "base",
                List.of(ResourceSelector.pathExact("src/a.py")),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(laneId),
                WorkIntent.Status.ANNOUNCED);
        store = new PredictionEventStore(temp, projectId);
        assertTrue(new WorkIntentService(store, identity).announce(intent).acquired());
        store = new PredictionEventStore(temp, projectId);
    }

    private TaskSnapshotPayload snapshot(String snapshotId, UUID task, UUID lane, long epoch, boolean reviewRequired) {
        SnapshotProvenance provenance = new SnapshotProvenance(groupId,
                lane,
                intent.authorityLineageId(),
                "agt-owner",
                "session-owner",
                epoch,
                List.of(),
                List.of(),
                List.of("PATH:src/a.py"),
                "refs/synesis/snapshots/" + snapshotId,
                "integrity-" + snapshotId,
                "artifacts-" + snapshotId);
        return new TaskSnapshotPayload(task,
                snapshotId,
                "node",
                "supervisor",
                "worker",
                "session-owner",
                "base",
                "commit-" + snapshotId,
                List.of("src/a.py"),
                List.of(),
                snapshotId,
                provenance,
                reviewRequired);
    }

    private void append(PredictionEventType type, byte[] payload) throws Exception {
        store.append(UUID.randomUUID(), type, identity.nodeId(), payload, identity);
    }

    private void startAttemptAndIntegrateOriginal() throws Exception {
        append(PredictionEventType.INTEGRATION_ATTEMPT_STARTED,
                new IntegrationAttemptPayload("att_original", projectId, List.of(ORIGINAL), "base", "integration",
                        "started", "").encode());
        append(PredictionEventType.TASK_INTEGRATED, snapshot(ORIGINAL, taskId, laneId, 1, false).encode());
        assertEquals(TaskCompletionState.INTEGRATED,
                store.taskCompletionProjection().snapshotState(ORIGINAL).orElseThrow());
    }

    /** The original review-free snapshot, taken through a merge pass to INTEGRATED. */
    private void integrateOriginal() throws Exception {
        append(PredictionEventType.TASK_SNAPSHOT_CREATED, snapshot(ORIGINAL, taskId, laneId, 1, false).encode());
        startAttemptAndIntegrateOriginal();
    }

    private RemergeSnapshotPayload remerge(String snapshotId, String originalId, UUID task, UUID lane, long epoch,
            boolean reviewRequired) {
        return new RemergeSnapshotPayload("remerge-1", originalId, "other-head", "own-head", "result",
                snapshot(snapshotId, task, lane, epoch, reviewRequired));
    }

    private IOException refused(RemergeSnapshotPayload payload) {
        return assertThrows(IOException.class,
                () -> append(PredictionEventType.REMERGE_SNAPSHOT_CREATED, payload.encode()));
    }

    @Test
    void anAppliedReMergeSnapshotIsFoundByIdOutsideTheTaskHistory() throws Exception {
        integrateOriginal();
        RemergeSnapshotPayload payload = remerge(REMERGE, ORIGINAL, taskId, laneId, 1, true);

        append(PredictionEventType.REMERGE_SNAPSHOT_CREATED, payload.encode());

        TaskCompletionProjection projection = store.taskCompletionProjection();
        TaskSnapshotRecord found = projection.findSnapshotById(REMERGE).orElseThrow();
        assertEquals("commit-" + REMERGE, found.commitSha());
        assertEquals(TaskCompletionState.REVIEW_PENDING, projection.snapshotState(REMERGE).orElseThrow());
        assertEquals(List.of(ORIGINAL),
                projection.allSnapshots().stream().map(TaskSnapshotRecord::snapshotId).toList());
        assertFalse(projection.eligibleSnapshots().stream().anyMatch(s -> s.snapshotId().equals(REMERGE)));
        assertEquals(TaskCompletionState.INTEGRATED, projection.taskState(taskId));
        assertEquals(payload, projection.remergeOf(new SnapshotId(REMERGE)).orElseThrow());
        assertTrue(projection.remergeOf(new SnapshotId(ORIGINAL)).isEmpty());
        assertEquals(List.of(payload), projection.remerge("remerge-1"));
        assertTrue(projection.remerge("other").isEmpty());

        TaskCompletionProjection replayed = new PredictionEventStore(root, projectId).taskCompletionProjection();
        assertTrue(replayed.findSnapshotById(REMERGE).isPresent());
        assertEquals(1, replayed.remerge("remerge-1").size());
    }

    @Test
    void aReMergeSnapshotWhoseOriginalIsUnknownIsHeldBackAsAMissingReference() throws Exception {
        integrateOriginal();

        IOException failure = refused(remerge(REMERGE, "snap_unknown", taskId, laneId, 1, true));

        assertTrue(failure instanceof MissingReferenceException, failure.getClass().getName());
        assertEquals("REMERGE_ORIGINAL_NOT_FOUND", failure.getMessage());
        assertTrue(store.taskCompletionProjection().findSnapshotById(REMERGE).isEmpty());
    }

    @Test
    void anOwnAppendNeedsAnIntegratedOriginalOfTheSameLaneEpochAndTaskAndAReviewedSnapshot() throws Exception {
        // The original exists but is not integrated yet.
        append(PredictionEventType.TASK_SNAPSHOT_CREATED, snapshot(ORIGINAL, taskId, laneId, 1, false).encode());
        assertEquals("REMERGE_SNAPSHOT_INVALID",
                refused(remerge(REMERGE, ORIGINAL, taskId, laneId, 1, true)).getMessage());

        startAttemptAndIntegrateOriginal();

        assertEquals("REMERGE_SNAPSHOT_INVALID",
                refused(remerge(REMERGE, ORIGINAL, taskId, UUID.randomUUID(), 1, true)).getMessage(), "other lane");
        assertEquals("REMERGE_SNAPSHOT_INVALID",
                refused(remerge(REMERGE, ORIGINAL, taskId, laneId, 2, true)).getMessage(), "other claim epoch");
        assertEquals("REMERGE_SNAPSHOT_INVALID",
                refused(remerge(REMERGE, ORIGINAL, UUID.randomUUID(), laneId, 1, true)).getMessage(), "other task");
        assertEquals("REMERGE_SNAPSHOT_INVALID",
                refused(remerge(REMERGE, ORIGINAL, taskId, laneId, 1, false)).getMessage(), "review not required");
        assertTrue(store.taskCompletionProjection().findSnapshotById(REMERGE).isEmpty());

        append(PredictionEventType.REMERGE_SNAPSHOT_CREATED,
                remerge(REMERGE, ORIGINAL, taskId, laneId, 1, true).encode());
        assertTrue(store.taskCompletionProjection().findSnapshotById(REMERGE).isPresent());
    }

    @Test
    void aSnapshotIdThatAlreadyExistsIsRefused() throws Exception {
        integrateOriginal();
        append(PredictionEventType.REMERGE_SNAPSHOT_CREATED,
                remerge(REMERGE, ORIGINAL, taskId, laneId, 1, true).encode());

        CoordinationException again = assertThrows(CoordinationException.class,
                () -> append(PredictionEventType.REMERGE_SNAPSHOT_CREATED,
                        remerge(REMERGE, ORIGINAL, taskId, laneId, 1, true).encode()));
        assertEquals("SNAPSHOT_ID_EXISTS", again.getMessage());
    }

    @Test
    void anAcceptedVerdictMovesOnlyTheReMergeSnapshot() throws Exception {
        integrateOriginal();
        append(PredictionEventType.REMERGE_SNAPSHOT_CREATED,
                remerge(REMERGE, ORIGINAL, taskId, laneId, 1, true).encode());
        LaneGrant grant = new LaneGrant(UUID.randomUUID(), groupId, laneId, "agt-reviewer", 1, true, REMERGE);
        append(PredictionEventType.LANE_GRANT_ISSUED, CollaborationCodec.encodeLaneGrant(grant));
        append(PredictionEventType.LANE_GRANT_CONSUMED, CollaborationCodec.encodeLaneGrant(grant));

        append(PredictionEventType.REVIEW_VALIDATION_RECORDED,
                new ReviewValidationPayload(grant.grantId(), groupId, laneId, "agt-reviewer", 1, taskId, REMERGE,
                        "ACCEPTED", "", "agt-owner").encode());

        TaskCompletionProjection projection = store.taskCompletionProjection();
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, projection.snapshotState(REMERGE).orElseThrow());
        assertEquals(TaskCompletionState.INTEGRATED, projection.snapshotState(ORIGINAL).orElseThrow());
        assertEquals(TaskCompletionState.INTEGRATED, projection.taskState(taskId));
        assertEquals("ACCEPTED", projection.verdicts(new SnapshotId(REMERGE)).get("agt-reviewer"));
    }
}
