package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.capability.CapabilityActorScope;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewAdmissionRules;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.LaneGrantId;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.link.identity.NodeIdentity;

/**
 * A takeover of another computer's lane is admitted from a snapshot or the base of that lane with no recovery hold, is
 * refused for the taker's own computer's lane or with a reference or actor scope that cannot be right, and ends the
 * source lane wherever it arrives; a review grant revoked by a stalled computer cannot take a verdict (ADR-0098 step
 * 7, designer items 2, 9 to 11 and 17).
 */
final class TakeoverAdmissionTest {

    private static final UUID PROJECT = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID GROUP = UUID.fromString("60000000-0000-4000-8000-000000000002");
    private static final String SNAPSHOT = "snap_takeover";
    private static final String BASE = "base-commit";

    private final NodeIdentity away = NodeIdentity.generate();
    private final NodeIdentity taker = NodeIdentity.generate();
    private final NodeIdentity reviewer = NodeIdentity.generate();
    private final Projections view = new Projections(new ProjectId(PROJECT));
    private final WorkIntent lane;
    private long sequence;

    TakeoverAdmissionTest() throws Exception {
        UUID intentId = UUID.randomUUID();
        lane = new WorkIntent(intentId, PROJECT, "agt-owner", "codex", UUID.randomUUID(), "goal", "acceptance", BASE,
                List.of(ResourceSelector.pathExact("src/a.py")), 1, GROUP, WorkIntent.defaultAuthorityLineage(intentId),
                WorkIntent.Status.ANNOUNCED);
    }

    private PredictionEvent entry(NodeIdentity signer, PredictionEventType type, byte[] payload) throws Exception {
        sequence++;
        return PredictionEvent.create(PROJECT, UUID.randomUUID(), sequence, type, signer.nodeId(), payload,
                new byte[32], signer, sequence);
    }

    private void write(NodeIdentity signer, PredictionEventType type, byte[] payload) throws Exception {
        PredictionEvent event = entry(signer, type, payload);
        view.check(event, sequence);
        view.apply(event, sequence);
    }

    private void arrive(NodeIdentity signer, PredictionEventType type, byte[] payload) throws Exception {
        PredictionEvent event = entry(signer, type, payload);
        Projections.Arrival arrival = view.arrive(event, sequence);
        assertTrue(arrival.applied(), type + " was not applied: " + arrival);
    }

    private void awayWorksOnALane(boolean arriving) throws Exception {
        byte[] group = CollaborationCodec.encodeWorkGroup(new WorkGroup(GROUP, PROJECT, "goal", "acceptance", 1,
                WorkGroup.Status.ACTIVE));
        if (arriving) {
            arrive(away, PredictionEventType.WORK_GROUP_CREATED, group);
            arrive(away, PredictionEventType.WORK_INTENT_ANNOUNCED, CollaborationCodec.encodeIntent(lane));
            arrive(away, PredictionEventType.TASK_SNAPSHOT_CREATED, snapshot());
        } else {
            write(away, PredictionEventType.WORK_GROUP_CREATED, group);
            write(away, PredictionEventType.WORK_INTENT_ANNOUNCED, CollaborationCodec.encodeIntent(lane));
            write(away, PredictionEventType.TASK_SNAPSHOT_CREATED, snapshot());
        }
    }

    private byte[] snapshot() {
        SnapshotProvenance provenance = new SnapshotProvenance(GROUP, lane.intentId(), lane.authorityLineageId(),
                "agt-owner", "session-owner", 1, List.of(), List.of(), List.of("PATH:src/a.py"),
                "refs/synesis/snapshots/" + SNAPSHOT, "integrity", "artifacts");
        return new TaskSnapshotPayload(lane.taskId(), SNAPSHOT, "node", "supervisor", "worker", "session-owner",
                BASE, "commit-1", List.of("src/a.py"), List.of(), SNAPSHOT, provenance, true).encode();
    }

    private LaneContinuation takeover(String reference, CapabilityActorScope source, CapabilityActorScope target) {
        UUID targetId = UUID.nameUUIDFromBytes(("takeover:" + lane.intentId()).getBytes());
        WorkIntent successor = new WorkIntent(targetId, PROJECT, "agt-taker", "codex", lane.taskId(), lane.goal(),
                lane.acceptance(), "taker-base", lane.selectors(), lane.version() + 1, GROUP, lane.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED, lane.role(), lane.reviewTargetSelectors(), lane.knownDependencies());
        return new LaneContinuation(UUID.nameUUIDFromBytes(("grant:" + targetId).getBytes()), lane.intentId(), successor,
                "agt-owner", "agt-taker", lane.version(), reference, source, target);
    }

    private LaneContinuation takeover(String reference) {
        return takeover(reference, null, null);
    }

    private String refusal(NodeIdentity writer, LaneContinuation continuation) {
        return assertThrows(IOException.class, () -> write(writer, PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                CollaborationCodec.encodeContinuation(continuation))).getMessage();
    }

    @Test
    void aTakeoverFromTheLastSnapshotIsAdmittedWithNoRecoveryHold() throws Exception {
        awayWorksOnALane(false);
        assertEquals(Participant.State.ACTIVE, view.collaborationProjection().participantState("agt-owner").orElseThrow());

        LaneContinuation continuation = takeover(LaneContinuation.TAKEOVER_FROM_SNAPSHOT + SNAPSHOT);
        assertTrue(continuation.takeover());
        write(taker, PredictionEventType.LANE_CONTINUATION_ACCEPTED, CollaborationCodec.encodeContinuation(continuation));

        assertEquals(continuation.targetIntent().intentId(),
                view.collaborationProjection().successorIntent(lane.intentId()).orElseThrow());
        assertTrue(view.collaborationProjection().intent(lane.intentId()).isEmpty(), "the source lane ended");
        assertTrue(view.collaborationProjection().intent(continuation.targetIntent().intentId()).isPresent());
        assertEquals(Participant.State.DETACHED, view.collaborationProjection().participantState("agt-owner").orElseThrow());
        assertEquals(Participant.State.ACTIVE, view.collaborationProjection().participantState("agt-taker").orElseThrow());
        assertTrue(view.collaborationProjection().lane(lane.intentId()).isPresent(), "an ended lane is still named");
    }

    @Test
    void aTakeoverFromTheLanesBaseIsAdmitted() throws Exception {
        awayWorksOnALane(false);

        write(taker, PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                CollaborationCodec.encodeContinuation(takeover(LaneContinuation.TAKEOVER_FROM_BASE + BASE)));

        assertTrue(view.collaborationProjection().successorIntent(lane.intentId()).isPresent());
    }

    @Test
    void aTakeoverIsRefusedForTheTakersOwnComputersLane() throws Exception {
        awayWorksOnALane(false);

        assertEquals("TAKEOVER_SOURCE_NOT_ANOTHER_COMPUTERS",
                refusal(away, takeover(LaneContinuation.TAKEOVER_FROM_SNAPSHOT + SNAPSHOT)));

        assertTrue(view.collaborationProjection().intent(lane.intentId()).isPresent());
        assertTrue(view.collaborationProjection().successorIntent(lane.intentId()).isEmpty());
    }

    @Test
    void aTakeoverWhoseReferenceNamesNeitherASnapshotOfTheLaneNorItsBaseIsRefused() throws Exception {
        awayWorksOnALane(false);

        assertEquals("TAKEOVER_REFERENCE_INVALID", refusal(taker, takeover(LaneContinuation.TAKEOVER_FROM_SNAPSHOT + "snap_unknown")));
        assertEquals("TAKEOVER_REFERENCE_INVALID", refusal(taker, takeover(LaneContinuation.TAKEOVER_FROM_BASE + "other-base")));
        assertTrue(view.collaborationProjection().successorIntent(lane.intentId()).isEmpty());
    }

    @Test
    void aTakeoverCarryingActorScopesIsRefused() throws Exception {
        awayWorksOnALane(false);
        CapabilityActorScope scope = new CapabilityActorScope("n", "sup", "wrk", "ses");

        assertEquals("TAKEOVER_ACTOR_SCOPE_INVALID",
                refusal(taker, takeover(LaneContinuation.TAKEOVER_FROM_SNAPSHOT + SNAPSHOT, scope, scope)));
    }

    @Test
    void aRecoveryContinuationOfALaneNobodyHoldsIsStillRefused() throws Exception {
        awayWorksOnALane(false);

        assertEquals("RECOVERY_NOT_HELD", refusal(taker, takeover("/some/recovery#hash")));
    }

    @Test
    void aTakeoverStandsOnEveryComputerItArrivesOnAndDetachesTheSourceOwner() throws Exception {
        awayWorksOnALane(true);
        LaneContinuation continuation = takeover(LaneContinuation.TAKEOVER_FROM_SNAPSHOT + SNAPSHOT);

        arrive(taker, PredictionEventType.LANE_CONTINUATION_ACCEPTED, CollaborationCodec.encodeContinuation(continuation));

        assertTrue(view.collaborationProjection().intent(lane.intentId()).isEmpty());
        assertEquals(Participant.State.DETACHED, view.collaborationProjection().participantState("agt-owner").orElseThrow());
        assertEquals(continuation.targetIntent().intentId(),
                view.collaborationProjection().successorIntent(lane.intentId()).orElseThrow());
    }

    @Test
    void aTakeoverOfAnAlreadyTakenLaneArrivesBesideTheFirstWithoutEndingItTwice() throws Exception {
        awayWorksOnALane(true);
        arrive(taker, PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                CollaborationCodec.encodeContinuation(takeover(LaneContinuation.TAKEOVER_FROM_SNAPSHOT + SNAPSHOT)));
        NodeIdentity second = NodeIdentity.generate();
        UUID targetId = UUID.randomUUID();
        WorkIntent other = new WorkIntent(targetId, PROJECT, "agt-second", "codex", lane.taskId(), lane.goal(),
                lane.acceptance(), "b", lane.selectors(), 2, GROUP, lane.authorityLineageId(), WorkIntent.Status.ANNOUNCED,
                lane.role(), lane.reviewTargetSelectors(), lane.knownDependencies());
        LaneContinuation again = new LaneContinuation(UUID.randomUUID(), lane.intentId(), other, "agt-owner", "agt-second", 1,
                LaneContinuation.TAKEOVER_FROM_BASE + BASE);

        arrive(second, PredictionEventType.LANE_CONTINUATION_ACCEPTED, CollaborationCodec.encodeContinuation(again));

        assertTrue(view.collaborationProjection().intent(targetId).isPresent(), "both successors stand");
    }

    // ---- revoked review grants (designer item 2) ----

    private LaneGrant reviewGrant() throws Exception {
        LaneGrant grant = new LaneGrant(UUID.randomUUID(), GROUP, lane.intentId(), "agt-rev", 1, true, SNAPSHOT);
        arrive(away, PredictionEventType.LANE_GRANT_ISSUED, CollaborationCodec.encodeLaneGrant(grant));
        arrive(reviewer, PredictionEventType.LANE_GRANT_CONSUMED, CollaborationCodec.encodeLaneGrant(grant));
        return grant;
    }

    private byte[] verdict(LaneGrant grant, String result) {
        return new ReviewValidationPayload(grant.grantId(), GROUP, lane.intentId(), grant.targetParticipant(), 1,
                lane.taskId(), SNAPSHOT, result, "ACCEPTED".equals(result) ? "" : "needs work", "agt-owner").encode();
    }

    @Test
    void aRevokedReviewGrantNoLongerHoldsTheReviewAndTakesNoVerdict() throws Exception {
        awayWorksOnALane(false);
        LaneGrant grant = reviewGrant();
        assertTrue(ReviewAdmissionRules.holdsReview(view, grant));

        arrive(taker, PredictionEventType.LANE_REVOKED, CollaborationCodec.encodeLaneGrant(grant));

        assertTrue(view.workGroupProjection().grantRevoked(new LaneGrantId(grant.grantId())));
        assertFalse(ReviewAdmissionRules.holdsReview(view, grant));
        assertEquals("REVIEW_GRANT_REVOKED", assertThrows(IOException.class,
                () -> write(reviewer, PredictionEventType.REVIEW_VALIDATION_RECORDED, verdict(grant, "ACCEPTED"))).getMessage());
        assertEquals(TaskCompletionState.REVIEW_PENDING,
                view.taskCompletionProjection().snapshotState(SNAPSHOT).orElseThrow());
    }

    @Test
    void aNewReviewerIsAdmittedAfterTheRevocationAndItsVerdictStands() throws Exception {
        awayWorksOnALane(false);
        LaneGrant grant = reviewGrant();
        arrive(taker, PredictionEventType.LANE_REVOKED, CollaborationCodec.encodeLaneGrant(grant));
        NodeIdentity newReviewer = NodeIdentity.generate();
        LaneGrant replacement = new LaneGrant(UUID.randomUUID(), GROUP, lane.intentId(), "agt-rev2", 1, true, SNAPSHOT);
        arrive(taker, PredictionEventType.LANE_GRANT_ISSUED, CollaborationCodec.encodeLaneGrant(replacement));
        arrive(newReviewer, PredictionEventType.LANE_GRANT_CONSUMED, CollaborationCodec.encodeLaneGrant(replacement));

        write(newReviewer, PredictionEventType.REVIEW_VALIDATION_RECORDED, verdict(replacement, "ACCEPTED"));

        assertTrue(ReviewAdmissionRules.holdsReview(view, replacement));
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, view.taskCompletionProjection().snapshotState(SNAPSHOT).orElseThrow());
    }

    @Test
    void aVerdictThatWasWrittenBeforeTheRevocationArrivedStandsOnItsGrant() throws Exception {
        awayWorksOnALane(false);
        LaneGrant grant = reviewGrant();
        write(reviewer, PredictionEventType.REVIEW_VALIDATION_RECORDED, verdict(grant, "ACCEPTED"));

        arrive(taker, PredictionEventType.LANE_REVOKED, CollaborationCodec.encodeLaneGrant(grant));

        assertEquals("ACCEPTED", view.workGroupProjection().reviewValidationForGrant(grant.grantId()).orElseThrow().result());
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, view.taskCompletionProjection().snapshotState(SNAPSHOT).orElseThrow());
    }
}
