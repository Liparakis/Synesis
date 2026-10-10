package org.synesis.coordination.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies durable review policy and the work-group, request, and grant wire formats.
 */
final class WorkGroupReviewPolicyTest {

    @Test
    void mandatoryIndependentPolicySurvivesGroupEncoding() throws Exception {
        WorkGroup group = new WorkGroup(UUID.randomUUID(),
                UUID.randomUUID(),
                "Build a board",
                "CRUD, persistence, tests",
                1,
                WorkGroup.Status.ACTIVE,
                ReviewPolicy.mandatoryIndependent());

        WorkGroup decoded = CollaborationCodec.decodeWorkGroup(CollaborationCodec.encodeWorkGroup(group));

        assertEquals(group, decoded);
        assertTrue(decoded.reviewPolicy().mandatory());
        assertTrue(decoded.reviewPolicy().independenceRequired());
    }

    @Test
    void terminalStatusUpdatePreservesReviewPolicy(@TempDir Path temp) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        PredictionEventStore store = new PredictionEventStore(temp, projectId);
        WorkGroupService service = new WorkGroupService(store, NodeIdentity.generate());
        service.create(new WorkGroup(groupId,
                projectId,
                "goal",
                "acceptance",
                1,
                WorkGroup.Status.ACTIVE,
                ReviewPolicy.mandatoryIndependent()));

        service.close(groupId, WorkGroup.Status.COMPLETED, 1);

        assertEquals(ReviewPolicy.mandatoryIndependent(),
                new PredictionEventStore(temp, projectId).workGroupProjection().group(groupId).orElseThrow()
                        .reviewPolicy());
    }

    @Test
    void statusEventCannotChangeReviewPolicy(@TempDir Path temp) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, projectId);
        new WorkGroupService(store, identity).create(new WorkGroup(groupId,
                projectId,
                "goal",
                "acceptance",
                1,
                WorkGroup.Status.ACTIVE,
                ReviewPolicy.mandatoryIndependent()));
        WorkGroup weakened = new WorkGroup(groupId,
                projectId,
                "goal",
                "acceptance",
                2,
                WorkGroup.Status.ACTIVE,
                ReviewPolicy.none());

        IOException failure = assertThrows(IOException.class,
                () -> new PredictionEventStore(temp, projectId).append(groupId,
                        PredictionEventType.WORK_GROUP_STATUS_CHANGED,
                        identity.nodeId(),
                        CollaborationCodec.encodeWorkGroup(weakened),
                        identity));

        assertEquals("WORK_GROUP_REVIEW_POLICY_IMMUTABLE", failure.getMessage());
        assertEquals(ReviewPolicy.mandatoryIndependent(),
                new PredictionEventStore(temp, projectId).workGroupProjection().group(groupId).orElseThrow()
                        .reviewPolicy());
    }

    @Test
    void reviewDecisionMustMatchItsSnapshotBoundGrant(@TempDir Path temp) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, projectId);
        WorkGroupService service = new WorkGroupService(store, identity);
        service.create(new WorkGroup(groupId,
                projectId,
                "goal",
                "acceptance",
                1,
                WorkGroup.Status.ACTIVE,
                ReviewPolicy.mandatoryIndependent()));
        SnapshotProvenance provenance = new SnapshotProvenance(groupId,
                intentId,
                UUID.nameUUIDFromBytes(("intent-lineage:" + intentId).getBytes(StandardCharsets.UTF_8)),
                "producer",
                "producer-session",
                1,
                List.of(),
                List.of(),
                List.of("PATH_EXACT:src/board.py"),
                "refs/synesis/snapshots/snap-approved",
                "integrity-snap-approved",
                "manifest-snap-approved");
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(taskId,
                "snap-approved",
                "node",
                "supervisor",
                "worker",
                "producer-session",
                "base",
                "commit",
                List.of("src/board.py"),
                List.of(),
                "Published reviewed candidate",
                provenance,
                true);
        store.append(taskId, PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);
        LaneGrant grant = new LaneGrant(grantId, groupId, intentId, "reviewer", 1, true, "snap-approved");
        service.issue(grant);
        service.consume(grantId, "reviewer", intentId, 1);
        ReviewValidationPayload wrongCandidate = new ReviewValidationPayload(grantId,
                groupId,
                intentId,
                "reviewer",
                1,
                taskId,
                "snap-other",
                "ACCEPTED",
                "",
                "producer");

        IOException failure = assertThrows(IOException.class,
                () -> new PredictionEventStore(temp, projectId).append(grantId,
                        PredictionEventType.REVIEW_VALIDATION_RECORDED,
                        identity.nodeId(),
                        wrongCandidate.encode(),
                        identity));

        assertEquals("REVIEW_GRANT_BINDING_MISMATCH", failure.getMessage());
        assertEquals(0, new PredictionEventStore(temp, projectId).workGroupProjection().reviewValidations().size());
    }

    @Test
    void snapshotBoundRequestsAndGrantsRoundTrip() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        CoordinationRequest request = new CoordinationRequest(requestId,
                projectId,
                "reviewer",
                "producer",
                intentId,
                CoordinationRequest.Kind.REVIEW,
                "review",
                CoordinationRequest.Status.PENDING,
                "snap_exact");
        assertEquals(request, CollaborationCodec.decodeRequest(CollaborationCodec.encodeRequest(request)));

        UUID grantId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        LaneGrant grant = new LaneGrant(grantId, groupId, intentId, "reviewer", 3, true, "snap_exact");
        assertEquals(grant, CollaborationCodec.decodeLaneGrant(CollaborationCodec.encodeLaneGrant(grant)));
    }

    @Test
    void supersededFormatVersionsAreRejectedAsUnknown() {
        assertEquals("unsupported group format", assertThrows(IOException.class,
                () -> CollaborationCodec.decodeWorkGroup(magic(0x53474731))).getMessage());
        assertEquals("unsupported request format", assertThrows(IOException.class,
                () -> CollaborationCodec.decodeRequest(magic(0x53525131))).getMessage());
        assertEquals("unsupported grant format", assertThrows(IOException.class,
                () -> CollaborationCodec.decodeLaneGrant(magic(0x53475231))).getMessage());
        assertEquals("unsupported continuation format", assertThrows(IOException.class,
                () -> CollaborationCodec.decodeContinuation(magic(0x53434e31))).getMessage());
    }

    private static byte[] magic(int magic) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(magic).array();
    }
}
