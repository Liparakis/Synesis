package org.synesis.coordination.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.link.identity.NodeIdentity;

/**
 * While a snapshot's reviewers disagree, a reviewer may record a new verdict that replaces its own (ADR-0098 step 7,
 * designer items 6 to 8). Anywhere else a second verdict is still refused.
 */
final class DisputedVerdictReplacementTest {

    private static final UUID PROJECT = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID GROUP = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID LANE = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID TASK = UUID.fromString("30000000-0000-4000-8000-000000000004");
    private static final UUID GRANT_1 = UUID.fromString("30000000-0000-4000-8000-000000000005");
    private static final UUID GRANT_2 = UUID.fromString("30000000-0000-4000-8000-000000000006");
    private static final UUID GRANT_3 = UUID.fromString("30000000-0000-4000-8000-000000000007");
    private static final UUID LINEAGE = UUID.fromString("30000000-0000-4000-8000-000000000008");
    private static final String SNAPSHOT = "snap_d1";
    private static final SnapshotId SNAPSHOT_ID = new SnapshotId(SNAPSHOT);

    private final NodeIdentity writer = NodeIdentity.generate();
    private long sequence;

    DisputedVerdictReplacementTest() throws Exception {
    }

    private PredictionEvent event(PredictionEventType type, byte[] payload) throws Exception {
        sequence++;
        return PredictionEvent.create(PROJECT, UUID.randomUUID(), sequence, type, writer.nodeId(), payload,
                new byte[32], writer, sequence);
    }

    private static TaskSnapshotPayload snapshot(String snapshotId, long epoch) {
        SnapshotProvenance provenance = new SnapshotProvenance(GROUP, LANE, LINEAGE, "agt-owner", "session-owner",
                epoch, List.of(), List.of(), List.of("PATH:src/a.py"), "refs/synesis/snapshots/" + snapshotId,
                "integrity", "artifacts");
        return new TaskSnapshotPayload(TASK, snapshotId, "node", "supervisor", "worker", "session-owner", "base",
                "commit-" + snapshotId, List.of("src/a.py"), List.of(), snapshotId, provenance, true);
    }

    private PredictionEvent created() throws Exception {
        return event(PredictionEventType.TASK_SNAPSHOT_CREATED, snapshot(SNAPSHOT, 1).encode());
    }

    private PredictionEvent verdict(UUID grant, String reviewer, String result) throws Exception {
        String reason = "ACCEPTED".equals(result) ? "" : "needs work";
        return event(PredictionEventType.REVIEW_VALIDATION_RECORDED, new ReviewValidationPayload(grant, GROUP, LANE,
                reviewer, 1, TASK, SNAPSHOT, result, reason, "agt-owner").encode());
    }

    private static void arrive(TaskCompletionProjection projection, PredictionEvent event) throws IOException {
        projection.validate(event, ApplyMode.ARRIVAL);
        projection.apply(event);
    }

    /** What happened on another computer: both verdicts of a dispute can only be recorded as arrivals. */
    private static void record(TaskCompletionProjection projection, PredictionEvent event) throws IOException {
        arrive(projection, event);
    }

    /** What the reviewer's own computer does when it appends its new verdict: judged as a new entry. */
    private static void recordHere(TaskCompletionProjection projection, PredictionEvent event) throws IOException {
        projection.validate(event);
        projection.apply(event);
    }

    private static TaskCompletionProjection arrived(PredictionEvent... events) throws IOException {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        for (PredictionEvent event : events) {
            arrive(projection, event);
        }
        return projection;
    }

    private static TaskCompletionProjection replayed(PredictionEvent... events) throws IOException {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        for (PredictionEvent event : events) {
            projection.apply(event);
        }
        return projection;
    }

    private static TaskCompletionState state(TaskCompletionProjection projection) {
        return projection.snapshotState(SNAPSHOT).orElseThrow();
    }

    @Test
    void disagreeingReviewersLeaveTheSnapshotDisputedAndOnTheDisputeRecord() throws Exception {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        record(projection, created());
        record(projection, verdict(GRANT_1, "agt-r1", "ACCEPTED"));
        assertTrue(projection.disputedSnapshots().isEmpty());
        assertFalse(projection.everDisputed(SNAPSHOT_ID));
        record(projection, verdict(GRANT_2, "agt-r2", "REJECTED"));

        assertEquals(TaskCompletionState.REVIEW_DISPUTED, state(projection));
        assertEquals(List.of(SNAPSHOT_ID), projection.disputedSnapshots());
        assertTrue(projection.everDisputed(SNAPSHOT_ID));
        assertTrue(projection.eligibleSnapshots().isEmpty());
    }

    @Test
    void theRejectingReviewerAcceptsOnItsOwnGrantAndTheSnapshotIsAccepted() throws Exception {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        record(projection, created());
        record(projection, verdict(GRANT_1, "agt-r1", "ACCEPTED"));
        record(projection, verdict(GRANT_2, "agt-r2", "REJECTED"));

        recordHere(projection, verdict(GRANT_2, "agt-r2", "ACCEPTED"));

        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, state(projection));
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, projection.taskState(TASK));
        assertEquals(Map.of("agt-r1", "ACCEPTED", "agt-r2", "ACCEPTED"), projection.verdicts(SNAPSHOT_ID));
        assertTrue(projection.everDisputed(SNAPSHOT_ID), "how the dispute ended stays on record");
        assertEquals(List.of(SNAPSHOT_ID), projection.disputedSnapshots());
        assertTrue(projection.reviewDispute(SNAPSHOT_ID).isEmpty());
        assertEquals(1, projection.eligibleSnapshots().size());
    }

    @Test
    void theAcceptingReviewerRejectsOnItsOwnGrantAndTheSnapshotIsRejected() throws Exception {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        record(projection, created());
        record(projection, verdict(GRANT_1, "agt-r1", "ACCEPTED"));
        record(projection, verdict(GRANT_2, "agt-r2", "REJECTED"));

        recordHere(projection, verdict(GRANT_1, "agt-r1", "REJECTED"));

        assertEquals(TaskCompletionState.REVIEW_REJECTED, state(projection));
        assertEquals(Map.of("agt-r1", "REJECTED", "agt-r2", "REJECTED"), projection.verdicts(SNAPSHOT_ID));
        assertTrue(projection.everDisputed(SNAPSHOT_ID));
        assertTrue(projection.reviewDispute(SNAPSHOT_ID).isEmpty());
    }

    @Test
    void aReplacementThatChangesNothingKeepsTheSnapshotDisputed() throws Exception {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        record(projection, created());
        record(projection, verdict(GRANT_1, "agt-r1", "ACCEPTED"));
        record(projection, verdict(GRANT_2, "agt-r2", "REJECTED"));

        recordHere(projection, verdict(GRANT_2, "agt-r2", "REJECTED"));

        assertEquals(TaskCompletionState.REVIEW_DISPUTED, state(projection));
        assertEquals(List.of(SNAPSHOT_ID), projection.disputedSnapshots());
    }

    @Test
    void aDisputeCanBeReplacedMoreThanOnce() throws Exception {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        record(projection, created());
        record(projection, verdict(GRANT_1, "agt-r1", "ACCEPTED"));
        record(projection, verdict(GRANT_2, "agt-r2", "REJECTED"));
        recordHere(projection, verdict(GRANT_2, "agt-r2", "ACCEPTED"));
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, state(projection));

        // Once accepted the snapshot is no longer disputed: nobody may take the acceptance back.
        CoordinationException refused = assertThrows(CoordinationException.class,
                () -> projection.validate(verdict(GRANT_2, "agt-r2", "REJECTED")));
        assertEquals("REVIEW_DECISION_ALREADY_RECORDED", refused.getMessage());
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, state(projection));
    }

    @Test
    void aReviewerCannotChangeItsVerdictOnceTheSnapshotIsAccepted() throws Exception {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        record(projection, created());
        record(projection, verdict(GRANT_1, "agt-r1", "ACCEPTED"));

        CoordinationException refused = assertThrows(CoordinationException.class,
                () -> projection.validate(verdict(GRANT_1, "agt-r1", "REJECTED")));
        assertEquals("REVIEW_DECISION_ALREADY_RECORDED", refused.getMessage());
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, state(projection));
        assertFalse(projection.everDisputed(SNAPSHOT_ID));
    }

    @Test
    void aSoleRejectionIsNotADisputeAndCannotBeTakenBack() throws Exception {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        record(projection, created());
        recordHere(projection, verdict(GRANT_1, "agt-r1", "REJECTED"));
        assertEquals(TaskCompletionState.REVIEW_REJECTED, state(projection));

        CoordinationException refused = assertThrows(CoordinationException.class,
                () -> projection.validate(verdict(GRANT_1, "agt-r1", "ACCEPTED")));
        assertEquals("REVIEW_DECISION_ALREADY_RECORDED", refused.getMessage());
        assertEquals(TaskCompletionState.REVIEW_REJECTED, state(projection));
    }

    @Test
    void aReviewerWhoNeverVotedCannotJoinADispute() throws Exception {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        record(projection, created());
        record(projection, verdict(GRANT_1, "agt-r1", "ACCEPTED"));
        record(projection, verdict(GRANT_2, "agt-r2", "REJECTED"));

        CoordinationException refused = assertThrows(CoordinationException.class,
                () -> projection.validate(verdict(GRANT_3, "agt-r3", "ACCEPTED")));
        assertEquals("REVIEW_DECISION_ALREADY_RECORDED", refused.getMessage());
        assertEquals(TaskCompletionState.REVIEW_DISPUTED, state(projection));
        assertEquals(Map.of("agt-r1", "ACCEPTED", "agt-r2", "REJECTED"), projection.verdicts(SNAPSHOT_ID));
    }

    @Test
    void anIntegrationIsNotUndoneByAReplacedVerdict() throws Exception {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        record(projection, created());
        record(projection, verdict(GRANT_1, "agt-r1", "ACCEPTED"));
        record(projection, verdict(GRANT_2, "agt-r2", "REJECTED"));
        recordHere(projection, verdict(GRANT_2, "agt-r2", "ACCEPTED"));
        record(projection, event(PredictionEventType.TASK_INTEGRATED, snapshot(SNAPSHOT, 1).encode()));
        assertEquals(TaskCompletionState.INTEGRATED, state(projection));

        assertThrows(CoordinationException.class, () -> projection.validate(verdict(GRANT_2, "agt-r2", "REJECTED")));
        assertEquals(TaskCompletionState.INTEGRATED, state(projection));
    }

    @Test
    void replayAndArrivalOfTheReplacedVerdictEndInTheSameState() throws Exception {
        PredictionEvent created = created();
        PredictionEvent accepted = verdict(GRANT_1, "agt-r1", "ACCEPTED");
        PredictionEvent rejected = verdict(GRANT_2, "agt-r2", "REJECTED");
        PredictionEvent replaced = verdict(GRANT_2, "agt-r2", "ACCEPTED");

        TaskCompletionProjection replay = replayed(created, accepted, rejected, replaced);
        TaskCompletionProjection arrival = arrived(created, accepted, rejected, replaced);
        TaskCompletionProjection otherOrder = arrived(created, rejected, accepted, replaced);

        for (TaskCompletionProjection projection : List.of(replay, arrival, otherOrder)) {
            assertEquals(TaskCompletionState.REVIEW_ACCEPTED, state(projection));
            assertEquals(Map.of("agt-r1", "ACCEPTED", "agt-r2", "ACCEPTED"), projection.verdicts(SNAPSHOT_ID));
            assertTrue(projection.everDisputed(SNAPSHOT_ID));
            assertEquals(List.of(SNAPSHOT_ID), projection.disputedSnapshots());
        }
    }

    @Test
    void aCopiedProjectionKeepsTheDisputeRecord() throws Exception {
        TaskCompletionProjection projection = arrived(created(), verdict(GRANT_1, "agt-r1", "ACCEPTED"),
                verdict(GRANT_2, "agt-r2", "REJECTED"));

        // validate works on a copy; the copy must carry the record and the original must not be touched by it.
        projection.validate(verdict(GRANT_2, "agt-r2", "ACCEPTED"));

        assertEquals(TaskCompletionState.REVIEW_DISPUTED, state(projection));
        assertEquals(List.of(SNAPSHOT_ID), projection.disputedSnapshots());
        assertEquals(Map.of("agt-r1", "ACCEPTED", "agt-r2", "REJECTED"), projection.verdicts(SNAPSHOT_ID));
    }

    @Test
    void aDisputeResolvedToAcceptedWithdrawsTheRejectionTheLaneMovedOnFor() throws Exception {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        record(projection, created());
        record(projection, verdict(GRANT_1, "agt-r1", "ACCEPTED"));
        record(projection, verdict(GRANT_2, "agt-r2", "REJECTED"));
        TaskSnapshotRecord disputed = projection.findSnapshotById(SNAPSHOT).orElseThrow();

        assertFalse(projection.rejectionWithdrawn(disputed, 2), "still disputed");
        recordHere(projection, verdict(GRANT_2, "agt-r2", "ACCEPTED"));

        assertTrue(projection.rejectionWithdrawn(disputed, 2));
        assertTrue(projection.rejectionWithdrawn(disputed, 3));
        assertFalse(projection.rejectionWithdrawn(disputed, 1), "the lane did not move on");
    }

    @Test
    void aRejectionIsNotWithdrawnForASnapshotThatWasNeverDisputedOrWasRejected() throws Exception {
        TaskCompletionProjection accepted = new TaskCompletionProjection();
        record(accepted, created());
        record(accepted, verdict(GRANT_1, "agt-r1", "ACCEPTED"));
        assertFalse(accepted.rejectionWithdrawn(accepted.findSnapshotById(SNAPSHOT).orElseThrow(), 2),
                "never disputed: a lane that moved on belongs to some other change");

        TaskCompletionProjection rejected = new TaskCompletionProjection();
        record(rejected, created());
        record(rejected, verdict(GRANT_1, "agt-r1", "ACCEPTED"));
        record(rejected, verdict(GRANT_2, "agt-r2", "REJECTED"));
        recordHere(rejected, verdict(GRANT_1, "agt-r1", "REJECTED"));
        assertFalse(rejected.rejectionWithdrawn(rejected.findSnapshotById(SNAPSHOT).orElseThrow(), 2));
    }

    @Test
    void aRejectionIsNotWithdrawnOnceTheLaneHasPublishedALaterSnapshot() throws Exception {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        record(projection, created());
        record(projection, verdict(GRANT_1, "agt-r1", "ACCEPTED"));
        record(projection, verdict(GRANT_2, "agt-r2", "REJECTED"));
        recordHere(projection, verdict(GRANT_2, "agt-r2", "ACCEPTED"));
        TaskSnapshotRecord first = projection.findSnapshotById(SNAPSHOT).orElseThrow();
        assertTrue(projection.rejectionWithdrawn(first, 2));

        record(projection, event(PredictionEventType.TASK_SNAPSHOT_CREATED, snapshot("snap_d2", 2).encode()));

        assertFalse(projection.rejectionWithdrawn(first, 2), "the correction was published; the old snapshot is stale");
    }
}
