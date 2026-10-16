package org.synesis.coordination.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies grant-bound review decisions and their immutable replay projection.
 */
final class ReviewValidationPayloadTest {

    @Test
    void acceptedDecisionIsBoundToConsumedGrantAndRoundTrips(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        store.append(groupId,
                PredictionEventType.WORK_GROUP_CREATED,
                identity.nodeId(),
                CollaborationCodec.encodeWorkGroup(new WorkGroup(groupId,
                        project,
                        "review",
                        "accept",
                        1,
                        WorkGroup.Status.ACTIVE)),
                identity);
        LaneGrant grant = new LaneGrant(grantId, groupId, intentId, "agt-reviewer", 1, true);
        store.append(grantId,
                PredictionEventType.LANE_GRANT_ISSUED,
                identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant),
                identity);
        store.append(grantId,
                PredictionEventType.LANE_GRANT_CONSUMED,
                identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant),
                identity);
        // A decision is about a snapshot the log holds; admission refuses one about nothing.
        store.append(UUID.randomUUID(),
                PredictionEventType.TASK_SNAPSHOT_CREATED,
                identity.nodeId(),
                new TaskSnapshotPayload(taskId,
                        "snap_accept",
                        "node",
                        "supervisor",
                        "worker",
                        "session-owner",
                        "base",
                        "commit-accept",
                        List.of("src/a.py"),
                        List.of(),
                        "accepted work",
                        new SnapshotProvenance(groupId,
                                intentId,
                                UUID.randomUUID(),
                                "agt-owner",
                                "session-owner",
                                1,
                                List.of(),
                                List.of(),
                                List.of("PATH:src/a.py"),
                                "refs/synesis/snapshots/snap_accept",
                                "integrity",
                                "artifacts"),
                        true).encode(),
                identity);
        ReviewValidationPayload accepted = new ReviewValidationPayload(grantId,
                groupId,
                intentId,
                "agt-reviewer",
                1,
                taskId,
                "snap_accept",
                "accepted",
                null,
                "agt-owner");
        assertEquals(accepted, ReviewValidationPayload.decode(accepted.encode()));
        store.append(grantId,
                PredictionEventType.REVIEW_VALIDATION_RECORDED,
                identity.nodeId(),
                accepted.encode(),
                identity);
        assertEquals("ACCEPTED",
                new PredictionEventStore(temp, project).workGroupProjection().reviewValidationForGrant(grantId)
                        .orElseThrow().result());
    }

    @Test
    void rejectedDecisionRequiresReasonAndPreservesImplementerRoute() {
        UUID grantId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewValidationPayload(grantId,
                        groupId,
                        intentId,
                        "agt-reviewer",
                        1,
                        taskId,
                        "snap_reject",
                        "rejected",
                        "",
                        "agt-owner"));
        ReviewValidationPayload rejected = new ReviewValidationPayload(grantId,
                groupId,
                intentId,
                "agt-reviewer",
                1,
                taskId,
                "snap_reject",
                "rejected",
                "Todo test failed",
                "agt-owner");
        assertEquals("REJECTED", rejected.result());
        assertEquals("agt-owner", rejected.sourceParticipant());
    }
}
