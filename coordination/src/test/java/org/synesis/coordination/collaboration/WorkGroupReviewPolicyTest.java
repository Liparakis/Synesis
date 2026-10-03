package org.synesis.coordination.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
 * Verifies durable review policy and compatibility with historical work-group events.
 */
final class WorkGroupReviewPolicyTest {

    private static byte[] legacyPayload(UUID groupId, UUID projectId) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x53474731);
            writeUuid(output, groupId);
            writeUuid(output, projectId);
            writeText(output, "legacy goal");
            writeText(output, "legacy acceptance");
            output.writeLong(1);
            output.writeByte(WorkGroup.Status.ACTIVE.ordinal());
        }
        return bytes.toByteArray();
    }

    private static byte[] legacyRequestPayload(UUID requestId, UUID projectId, UUID intentId) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x53525131);
            writeUuid(output, requestId);
            writeUuid(output, projectId);
            writeText(output, "reviewer");
            writeText(output, "producer");
            writeUuid(output, intentId);
            output.writeByte(CoordinationRequest.Kind.REVIEW.ordinal());
            writeText(output, "legacy review");
        }
        return bytes.toByteArray();
    }

    private static byte[] legacyGrantPayload(UUID grantId, UUID groupId, UUID intentId) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x53475231);
            writeUuid(output, grantId);
            writeUuid(output, groupId);
            writeUuid(output, intentId);
            writeText(output, "reviewer");
            output.writeLong(3);
            output.writeBoolean(true);
        }
        return bytes.toByteArray();
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws Exception {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static void writeText(DataOutputStream output, String value) throws Exception {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

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
    void historicalWorkGroupPayloadDefaultsToNoReviewPolicy() throws Exception {
        UUID groupId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        WorkGroup decoded = CollaborationCodec.decodeWorkGroup(legacyPayload(groupId, projectId));

        assertEquals(groupId, decoded.workGroupId());
        assertEquals(projectId, decoded.projectId());
        assertEquals(ReviewPolicy.none(), decoded.reviewPolicy());
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
    void snapshotBoundRequestsAndGrantsRoundTripAndLegacyPayloadsStillDecode() throws Exception {
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
        assertNull(CollaborationCodec.decodeRequest(legacyRequestPayload(requestId, projectId, intentId)).snapshotId());

        UUID grantId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        LaneGrant grant = new LaneGrant(grantId, groupId, intentId, "reviewer", 3, true, "snap_exact");
        assertEquals(grant, CollaborationCodec.decodeLaneGrant(CollaborationCodec.encodeLaneGrant(grant)));
        assertNull(CollaborationCodec.decodeLaneGrant(legacyGrantPayload(grantId, groupId, intentId)).snapshotId());
    }
}
