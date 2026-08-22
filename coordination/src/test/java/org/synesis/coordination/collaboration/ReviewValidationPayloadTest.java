package org.synesis.coordination.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/** Verifies grant-bound review decisions and their immutable replay projection. */
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
        store.append(groupId, PredictionEventType.WORK_GROUP_CREATED, identity.nodeId(),
                CollaborationCodec.encodeWorkGroup(new WorkGroup(groupId, project, "review", "accept", 1,
                        WorkGroup.Status.ACTIVE)), identity);
        LaneGrant grant = new LaneGrant(grantId, groupId, intentId, "agt-reviewer", 1, true);
        store.append(grantId, PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant), identity);
        store.append(grantId, PredictionEventType.LANE_GRANT_CONSUMED, identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant), identity);
        ReviewValidationPayload accepted = new ReviewValidationPayload(grantId, groupId, intentId,
                "agt-reviewer", 1, taskId, "snap_accept", "accepted", null, "agt-owner");
        assertEquals(accepted, ReviewValidationPayload.decode(accepted.encode()));
        store.append(grantId, PredictionEventType.REVIEW_VALIDATION_RECORDED, identity.nodeId(),
                accepted.encode(), identity);
        assertEquals("ACCEPTED", new PredictionEventStore(temp, project).workGroupProjection()
                .reviewValidationForGrant(grantId).orElseThrow().result());
    }

    @Test
    void rejectedDecisionRequiresReasonAndPreservesImplementerRoute(@TempDir Path temp) throws Exception {
        UUID grantId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new ReviewValidationPayload(grantId, groupId,
                intentId, "agt-reviewer", 1, taskId, "snap_reject", "rejected", "", "agt-owner"));
        ReviewValidationPayload rejected = new ReviewValidationPayload(grantId, groupId, intentId,
                "agt-reviewer", 1, taskId, "snap_reject", "rejected", "Todo test failed", "agt-owner");
        assertTrue(rejected.result().equals("REJECTED"));
        assertEquals("agt-owner", rejected.sourceParticipant());
    }
}
