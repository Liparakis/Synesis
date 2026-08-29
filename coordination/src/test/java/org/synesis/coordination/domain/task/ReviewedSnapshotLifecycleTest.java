package org.synesis.coordination.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Focused tests for the reviewed immutable-snapshot continuation lifecycle.
 */
final class ReviewedSnapshotLifecycleTest {

    private static void appendGrantAndConsume(PredictionEventStore store, NodeIdentity identity,
            LaneGrant grant) throws Exception {
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED,
                identity.nodeId(), org.synesis.coordination.domain.collaboration.CollaborationCodec
                        .encodeLaneGrant(grant), identity);
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_CONSUMED,
                identity.nodeId(), org.synesis.coordination.domain.collaboration.CollaborationCodec
                        .encodeLaneGrant(grant), identity);
    }

    private static TaskSnapshotPayload snapshot(UUID taskId, String snapshotId, UUID laneId,
            UUID groupId, UUID lineageId, long epoch, String label) {
        SnapshotProvenance provenance = new SnapshotProvenance(groupId, laneId, lineageId,
                "agt-owner", "session-owner", epoch, List.of(), List.of(), List.of("PATH:src/a.py"),
                "refs/synesis/snapshots/" + snapshotId, "integrity-" + label, "artifacts-" + label);
        return new TaskSnapshotPayload(taskId, snapshotId, "node", "supervisor", "worker",
                "session-owner", "base", "commit-" + snapshotId, List.of("src/a.py"), List.of(),
                label, provenance, true);
    }

    private static ReviewValidationPayload review(LaneGrant grant, UUID taskId, String snapshotId,
            String result, String reason) {
        return new ReviewValidationPayload(grant.grantId(), grant.workGroupId(), grant.targetIntentId(),
                grant.targetParticipant(), grant.claimEpoch(), taskId, snapshotId, result, reason, "agt-owner");
    }

    @Test
    void rejectionRetainsS1CreatesARevisionAndOnlyAcceptedS2IsEligible(@TempDir Path temp) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID laneId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID grantOneId = UUID.randomUUID();
        UUID grantTwoId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent intent = new WorkIntent(laneId, projectId, "agt-owner", "codex", taskId,
                "implement", "acceptance", "base", List.of(ResourceSelector.pathExact("src/a.py")),
                1, groupId, WorkIntent.defaultAuthorityLineage(laneId), WorkIntent.Status.ANNOUNCED,
                WorkIntent.CompletionMode.SNAPSHOT_REQUIRED);

        PredictionEventStore store = new PredictionEventStore(temp, projectId);
        assertTrue(new WorkIntentService(store, identity).announce(intent)
                .acquired());
        store = new PredictionEventStore(temp, projectId);
        LaneGrant grantOne = new LaneGrant(grantOneId, groupId, laneId, "agt-reviewer", 1, true);
        appendGrantAndConsume(store, identity, grantOne);

        TaskSnapshotPayload firstPayload = snapshot(taskId, "snap_s1", laneId, groupId,
                intent.authorityLineageId(), 1, "S1");
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                identity.nodeId(), firstPayload.encode(), identity);
        assertEquals(TaskCompletionState.REVIEW_PENDING,
                store.taskCompletionProjection()
                        .taskState(taskId));

        ReviewValidationPayload rejected = review(grantOne, taskId, "snap_s1", "REJECTED", "missing test");
        store.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED,
                identity.nodeId(), rejected.encode(), identity);

        PredictionEventStore afterReject = new PredictionEventStore(temp, projectId);
        WorkIntent revision = afterReject.collaborationProjection()
                .intent(laneId)
                .orElseThrow();
        assertEquals(2, revision.version());
        assertEquals(intent.authorityLineageId(), revision.authorityLineageId());
        assertEquals("agt-owner", revision.participant());
        assertEquals(TaskCompletionState.REVIEW_REJECTED,
                afterReject.taskCompletionProjection()
                        .snapshotState("snap_s1")
                        .orElseThrow());
        assertTrue(afterReject.taskCompletionProjection()
                .eligibleSnapshots()
                .isEmpty());
        assertThrows(Exception.class, () -> afterReject.append(UUID.randomUUID(),
                PredictionEventType.INTEGRATION_ATTEMPT_STARTED, identity.nodeId(),
                new IntegrationAttemptPayload("att_rejected", projectId, List.of("snap_s1"),
                        "base", "integration", "started", "").encode(), identity));

        LaneGrant grantTwo = new LaneGrant(grantTwoId, groupId, laneId, "agt-reviewer", 2, true);
        appendGrantAndConsume(afterReject, identity, grantTwo);
        TaskSnapshotPayload secondPayload = snapshot(taskId, "snap_s2", laneId, groupId,
                intent.authorityLineageId(), 2, "S2");
        afterReject.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                identity.nodeId(), secondPayload.encode(), identity);
        assertEquals(TaskCompletionState.REVIEW_PENDING,
                afterReject.taskCompletionProjection()
                        .snapshotState("snap_s2")
                        .orElseThrow());

        ReviewValidationPayload accepted = review(grantTwo, taskId, "snap_s2", "ACCEPTED", "");
        afterReject.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED,
                identity.nodeId(), accepted.encode(), identity);
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED,
                afterReject.taskCompletionProjection()
                        .snapshotState("snap_s2")
                        .orElseThrow());
        assertEquals(List.of("snap_s2"),
                afterReject.taskCompletionProjection()
                        .eligibleSnapshots()
                        .stream()
                        .map(TaskSnapshotRecord::snapshotId)
                        .toList());

        afterReject.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_STARTED,
                identity.nodeId(), new IntegrationAttemptPayload("att_accepted", projectId,
                        List.of("snap_s2"), "base", "integration", "started", "").encode(), identity);
        assertEquals(TaskCompletionState.INTEGRATING,
                afterReject.taskCompletionProjection()
                        .snapshotState("snap_s2")
                        .orElseThrow());
        afterReject.append(UUID.randomUUID(), PredictionEventType.TASK_INTEGRATED,
                identity.nodeId(), secondPayload.encode(), identity);
        assertEquals(TaskCompletionState.INTEGRATED,
                afterReject.taskCompletionProjection()
                        .snapshotState("snap_s2")
                        .orElseThrow());

        List<TaskSnapshotRecord> history = afterReject.taskCompletionProjection()
                .allSnapshots();
        assertEquals(2, history.size());
        assertEquals("snap_s1",
                history.getFirst()
                        .snapshotId());
        assertEquals("commit-snap_s1",
                history.getFirst()
                        .commitSha());
        assertNotEquals(history.getFirst()
                        .snapshotId(),
                history.getLast()
                        .snapshotId());
        assertNotEquals(grantOneId, grantTwoId);
        assertThrows(Exception.class, () -> afterReject.append(UUID.randomUUID(),
                PredictionEventType.REVIEW_VALIDATION_RECORDED, identity.nodeId(),
                review(grantOne, taskId, "snap_s2", "ACCEPTED", "").encode(), identity));
    }

    @Test
    void repeatedRejectionsRetainEveryImmutableRevision(@TempDir Path temp) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID laneId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent intent = new WorkIntent(laneId, projectId, "agt-owner", "codex", taskId,
                "implement", "acceptance", "base", List.of(ResourceSelector.pathExact("src/a.py")),
                1, groupId, WorkIntent.defaultAuthorityLineage(laneId), WorkIntent.Status.ANNOUNCED,
                WorkIntent.CompletionMode.SNAPSHOT_REQUIRED);

        PredictionEventStore store = new PredictionEventStore(temp, projectId);
        assertTrue(new WorkIntentService(store, identity).announce(intent)
                .acquired());
        store = new PredictionEventStore(temp, projectId);
        for (int epoch = 1; epoch <= 3; epoch++) {
            LaneGrant grant = new LaneGrant(UUID.randomUUID(), groupId, laneId, "agt-reviewer", epoch, true);
            appendGrantAndConsume(store, identity, grant);
            String snapshotId = "snap_s" + epoch;
            store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                    identity.nodeId(), snapshot(taskId, snapshotId, laneId, groupId,
                            intent.authorityLineageId(), epoch, "S" + epoch).encode(), identity);
            if (epoch == 1) {
                PredictionEventStore duplicateStore = store;
                assertThrows(Exception.class, () -> duplicateStore.append(UUID.randomUUID(),
                        PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(),
                        snapshot(taskId, "snap_s1_duplicate", laneId, groupId,
                                intent.authorityLineageId(), 1, "S1 duplicate").encode(), identity));
            }
            String result = epoch == 3 ? "ACCEPTED" : "REJECTED";
            store.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED,
                    identity.nodeId(), review(grant, taskId, snapshotId, result,
                            epoch == 3 ? "" : "correction " + epoch).encode(), identity);
            if (epoch < 3) {
                assertEquals(epoch + 1L,
                        store.collaborationProjection()
                                .intent(laneId)
                                .orElseThrow()
                                .version());
            }
        }

        List<TaskSnapshotRecord> history = store.taskCompletionProjection()
                .allSnapshots();
        assertEquals(3, history.size());
        assertEquals(List.of("snap_s1", "snap_s2", "snap_s3"),
                history.stream()
                        .map(TaskSnapshotRecord::snapshotId)
                        .toList());
        assertEquals(TaskCompletionState.REVIEW_REJECTED,
                store.taskCompletionProjection()
                        .snapshotState("snap_s1")
                        .orElseThrow());
        assertEquals(TaskCompletionState.REVIEW_REJECTED,
                store.taskCompletionProjection()
                        .snapshotState("snap_s2")
                        .orElseThrow());
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED,
                store.taskCompletionProjection()
                        .snapshotState("snap_s3")
                        .orElseThrow());
        assertEquals(List.of("snap_s3"),
                store.taskCompletionProjection()
                        .eligibleSnapshots()
                        .stream()
                        .map(TaskSnapshotRecord::snapshotId)
                        .toList());
        assertEquals(3,
                store.workGroupProjection()
                        .reviewValidations()
                        .size());
        assertNotEquals(history.get(0)
                        .commitSha(),
                history.get(1)
                        .commitSha());
        assertNotEquals(history.get(1)
                        .commitSha(),
                history.get(2)
                        .commitSha());
    }
}
