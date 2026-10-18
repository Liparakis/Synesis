package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.link.identity.NodeIdentity;

/**
 * A reviewer may replace its own verdict while the snapshot's reviewers disagree, and only then (ADR-0098 step 7,
 * designer items 6 to 8), through every projection and the admission rules together: two reviewers' verdicts meet on
 * the author's computer as arrivals, and the reviewer's own computer then judges the new verdict as its own entry.
 */
final class DisputedReviewAdmissionTest {

    private static final UUID PROJECT = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID GROUP = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final String SNAPSHOT = "snap_dispute";
    private static final SnapshotId SNAPSHOT_ID = new SnapshotId(SNAPSHOT);

    private final NodeIdentity author = NodeIdentity.generate();
    private final NodeIdentity reviewerOne = NodeIdentity.generate();
    private final NodeIdentity reviewerTwo = NodeIdentity.generate();
    private final Projections view = new Projections(new ProjectId(PROJECT));
    private final WorkIntent lane;
    private final LaneGrant grantOne;
    private final LaneGrant grantTwo;
    private final LaneGrant grantThree;
    private long sequence;

    DisputedReviewAdmissionTest() throws Exception {
        UUID intentId = UUID.randomUUID();
        lane = new WorkIntent(intentId, PROJECT, "agt-owner", "codex", UUID.randomUUID(), "goal", "acceptance", "base",
                List.of(ResourceSelector.pathExact("src/a.py")), 1, GROUP, WorkIntent.defaultAuthorityLineage(intentId),
                WorkIntent.Status.ANNOUNCED);
        grantOne = new LaneGrant(UUID.randomUUID(), GROUP, intentId, "agt-r1", 1, true, SNAPSHOT);
        grantTwo = new LaneGrant(UUID.randomUUID(), GROUP, intentId, "agt-r2", 1, true, SNAPSHOT);
        grantThree = new LaneGrant(UUID.randomUUID(), GROUP, intentId, "agt-r3", 1, true, SNAPSHOT);
    }

    private PredictionEvent entry(NodeIdentity signer, PredictionEventType type, byte[] payload) throws Exception {
        sequence++;
        return PredictionEvent.create(PROJECT, UUID.randomUUID(), sequence, type, signer.nodeId(), payload,
                new byte[32], signer, sequence);
    }

    /** What the signer's own computer does: judges the entry as its own, then applies it. */
    private void write(NodeIdentity signer, PredictionEventType type, byte[] payload) throws Exception {
        PredictionEvent event = entry(signer, type, payload);
        view.check(event, sequence);
        view.apply(event, sequence);
    }

    /** What this computer does with another computer's entry. */
    private void arrive(NodeIdentity signer, PredictionEventType type, byte[] payload) throws Exception {
        PredictionEvent event = entry(signer, type, payload);
        Projections.Arrival arrival = view.arrive(event, sequence);
        assertTrue(arrival.applied(), type + " was not applied: " + arrival);
    }

    private byte[] snapshot() {
        SnapshotProvenance provenance = new SnapshotProvenance(GROUP, lane.intentId(), lane.authorityLineageId(),
                "agt-owner", "session-owner", 1, List.of(), List.of(), List.of("PATH:src/a.py"),
                "refs/synesis/snapshots/" + SNAPSHOT, "integrity", "artifacts");
        return new TaskSnapshotPayload(lane.taskId(), SNAPSHOT, "node", "supervisor", "worker", "session-owner",
                "base", "commit-1", List.of("src/a.py"), List.of(), SNAPSHOT, provenance, true).encode();
    }

    private byte[] verdict(LaneGrant grant, String result) {
        String reason = "ACCEPTED".equals(result) ? "" : "needs work";
        return new ReviewValidationPayload(grant.grantId(), GROUP, lane.intentId(), grant.targetParticipant(), 1,
                lane.taskId(), SNAPSHOT, result, reason, "agt-owner").encode();
    }

    private void grant(NodeIdentity reviewer, LaneGrant grant) throws Exception {
        arrive(author, PredictionEventType.LANE_GRANT_ISSUED, CollaborationCodec.encodeLaneGrant(grant));
        arrive(reviewer, PredictionEventType.LANE_GRANT_CONSUMED, CollaborationCodec.encodeLaneGrant(grant));
    }

    /** The author's lane and snapshot, the two grants, and the two verdicts that disagree. */
    private void dispute() throws Exception {
        write(author, PredictionEventType.WORK_GROUP_CREATED,
                CollaborationCodec.encodeWorkGroup(new WorkGroup(GROUP, PROJECT, "goal", "acceptance", 1,
                        WorkGroup.Status.ACTIVE)));
        write(author, PredictionEventType.WORK_INTENT_ANNOUNCED, CollaborationCodec.encodeIntent(lane));
        write(author, PredictionEventType.TASK_SNAPSHOT_CREATED, snapshot());
        grant(reviewerOne, grantOne);
        grant(reviewerTwo, grantTwo);
        arrive(reviewerOne, PredictionEventType.REVIEW_VALIDATION_RECORDED, verdict(grantOne, "ACCEPTED"));
        arrive(reviewerTwo, PredictionEventType.REVIEW_VALIDATION_RECORDED, verdict(grantTwo, "REJECTED"));
    }

    private TaskCompletionState state() {
        return view.taskCompletionProjection().snapshotState(SNAPSHOT).orElseThrow();
    }

    private long laneVersion() {
        return view.collaborationProjection().intent(lane.intentId()).orElseThrow().version();
    }

    private String refusal(NodeIdentity signer, byte[] payload) {
        return assertThrows(IOException.class, () -> write(signer, PredictionEventType.REVIEW_VALIDATION_RECORDED,
                payload)).getMessage();
    }

    @Test
    void theRejectionOfADisputeReopensTheLaneOnceAndTheSnapshotIsDisputed() throws Exception {
        dispute();

        assertEquals(TaskCompletionState.REVIEW_DISPUTED, state());
        assertEquals(List.of(SNAPSHOT_ID), view.taskCompletionProjection().disputedSnapshots());
        assertEquals(2, laneVersion(), "the rejection moved the lane on");
    }

    @Test
    void theRejectingReviewerAcceptsOnItsOwnGrantAfterTheRejectionMovedTheLaneOn() throws Exception {
        dispute();

        write(reviewerTwo, PredictionEventType.REVIEW_VALIDATION_RECORDED, verdict(grantTwo, "ACCEPTED"));

        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, state());
        assertTrue(view.taskCompletionProjection().everDisputed(SNAPSHOT_ID));
        assertEquals(Map.of("agt-r1", "ACCEPTED", "agt-r2", "ACCEPTED"),
                view.taskCompletionProjection().verdicts(SNAPSHOT_ID));
        assertEquals("ACCEPTED", view.workGroupProjection().reviewValidationForGrant(grantTwo.grantId())
                .orElseThrow().result());
        assertEquals(2, laneVersion());
        assertTrue(view.taskCompletionProjection().rejectionWithdrawn(
                view.taskCompletionProjection().findSnapshotById(SNAPSHOT).orElseThrow(), laneVersion()));
    }

    @Test
    void theAcceptingReviewerRejectsWithoutReopeningTheLaneASecondTime() throws Exception {
        dispute();

        write(reviewerOne, PredictionEventType.REVIEW_VALIDATION_RECORDED, verdict(grantOne, "REJECTED"));

        assertEquals(TaskCompletionState.REVIEW_REJECTED, state());
        assertEquals(2, laneVersion(), "the correction was already under way");
        assertEquals(WorkIntent.Status.ANNOUNCED,
                view.collaborationProjection().intent(lane.intentId()).orElseThrow().status());
    }

    @Test
    void aReviewerCannotReplaceItsVerdictWhenTheSnapshotIsNotDisputed() throws Exception {
        write(author, PredictionEventType.WORK_GROUP_CREATED,
                CollaborationCodec.encodeWorkGroup(new WorkGroup(GROUP, PROJECT, "goal", "acceptance", 1,
                        WorkGroup.Status.ACTIVE)));
        write(author, PredictionEventType.WORK_INTENT_ANNOUNCED, CollaborationCodec.encodeIntent(lane));
        write(author, PredictionEventType.TASK_SNAPSHOT_CREATED, snapshot());
        grant(reviewerOne, grantOne);
        write(reviewerOne, PredictionEventType.REVIEW_VALIDATION_RECORDED, verdict(grantOne, "ACCEPTED"));

        assertEquals("REVIEW_DECISION_ALREADY_RECORDED",
                refusal(reviewerOne, verdict(grantOne, "REJECTED")));
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, state());
        assertEquals(1, laneVersion());
    }

    @Test
    void aSoleRejectionCannotBeTakenBack() throws Exception {
        write(author, PredictionEventType.WORK_GROUP_CREATED,
                CollaborationCodec.encodeWorkGroup(new WorkGroup(GROUP, PROJECT, "goal", "acceptance", 1,
                        WorkGroup.Status.ACTIVE)));
        write(author, PredictionEventType.WORK_INTENT_ANNOUNCED, CollaborationCodec.encodeIntent(lane));
        write(author, PredictionEventType.TASK_SNAPSHOT_CREATED, snapshot());
        grant(reviewerOne, grantOne);
        write(reviewerOne, PredictionEventType.REVIEW_VALIDATION_RECORDED, verdict(grantOne, "REJECTED"));
        assertEquals(TaskCompletionState.REVIEW_REJECTED, state());

        assertEquals("REVIEW_DECISION_ALREADY_RECORDED", refusal(reviewerOne, verdict(grantOne, "ACCEPTED")));
        assertEquals(TaskCompletionState.REVIEW_REJECTED, state());
    }

    @Test
    void aThirdReviewerCannotJoinTheDisputeAndAGrantNeverVotedOnStaysStale() throws Exception {
        dispute();
        grant(reviewerOne, grantThree);

        String refused = refusal(reviewerOne, verdict(grantThree, "ACCEPTED"));

        assertEquals("REVIEW_DECISION_ALREADY_RECORDED", refused);
        assertEquals(TaskCompletionState.REVIEW_DISPUTED, state());
        assertEquals(Map.of("agt-r1", "ACCEPTED", "agt-r2", "REJECTED"),
                view.taskCompletionProjection().verdicts(SNAPSHOT_ID));
    }

    @Test
    void aReviewerCannotReplaceTheVerdictOfAnotherReviewersGrant() throws Exception {
        dispute();

        // Reviewer one's node writes a verdict on reviewer two's grant, claiming to be reviewer one.
        ReviewValidationPayload forged = new ReviewValidationPayload(grantTwo.grantId(), GROUP, lane.intentId(),
                "agt-r1", 1, lane.taskId(), SNAPSHOT, "ACCEPTED", "", "agt-owner");
        String refused = refusal(reviewerOne, forged.encode());

        assertEquals("REVIEW_GRANT_BINDING_MISMATCH", refused);
        assertEquals(TaskCompletionState.REVIEW_DISPUTED, state());
        assertEquals("REJECTED", view.workGroupProjection().reviewValidationForGrant(grantTwo.grantId())
                .orElseThrow().result());
    }

    @Test
    void aReplacementAfterTheIntegrationIsRefused() throws Exception {
        dispute();
        write(reviewerTwo, PredictionEventType.REVIEW_VALIDATION_RECORDED, verdict(grantTwo, "ACCEPTED"));
        write(author, PredictionEventType.TASK_INTEGRATED, snapshot());
        assertEquals(TaskCompletionState.INTEGRATED, state());

        assertEquals("REVIEW_DECISION_ALREADY_RECORDED", refusal(reviewerTwo, verdict(grantTwo, "REJECTED")));
        assertEquals(TaskCompletionState.INTEGRATED, state());
    }
}
