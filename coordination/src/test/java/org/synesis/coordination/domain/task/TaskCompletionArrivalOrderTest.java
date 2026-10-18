package org.synesis.coordination.domain.task;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.integration.ValidationRefusalPayload;
import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.MissingReferenceException;
import org.synesis.link.identity.NodeIdentity;

/**
 * Entries written on two computers meet in either order and the task completion projection ends in one state.
 */
final class TaskCompletionArrivalOrderTest {

    private static final UUID PROJECT = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID GROUP = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID LANE = UUID.fromString("20000000-0000-4000-8000-000000000003");
    private static final UUID TASK = UUID.fromString("20000000-0000-4000-8000-000000000004");
    private static final UUID GRANT_1 = UUID.fromString("20000000-0000-4000-8000-000000000005");
    private static final UUID GRANT_2 = UUID.fromString("20000000-0000-4000-8000-000000000006");
    private static final UUID LINEAGE = UUID.fromString("20000000-0000-4000-8000-000000000007");
    private static final String SNAPSHOT = "snap_s1";

    private final NodeIdentity writer = NodeIdentity.generate();
    private final NodeIdentity reviewerOne = NodeIdentity.generate();
    private final NodeIdentity reviewerTwo = NodeIdentity.generate();
    private long sequence;

    TaskCompletionArrivalOrderTest() throws Exception {
    }

    private PredictionEvent event(NodeIdentity signer, PredictionEventType type, byte[] payload) throws Exception {
        sequence++;
        return PredictionEvent.create(PROJECT, UUID.randomUUID(), sequence, type, signer.nodeId(), payload,
                new byte[32], signer, sequence);
    }

    private static TaskSnapshotPayload snapshot() {
        SnapshotProvenance provenance = new SnapshotProvenance(GROUP, LANE, LINEAGE, "agt-owner",
                "session-owner", 1, List.of(), List.of(), List.of("PATH:src/a.py"),
                "refs/synesis/snapshots/" + SNAPSHOT, "integrity", "artifacts");
        return new TaskSnapshotPayload(TASK, SNAPSHOT, "node", "supervisor", "worker", "session-owner", "base",
                "commit-1", List.of("src/a.py"), List.of(), SNAPSHOT, provenance, true);
    }

    private PredictionEvent snapshotEvent() throws Exception {
        return event(writer, PredictionEventType.TASK_SNAPSHOT_CREATED, snapshot().encode());
    }

    private PredictionEvent verdict(NodeIdentity signer, UUID grant, String reviewer, String result)
            throws Exception {
        String reason = "ACCEPTED".equals(result) ? "" : "needs work";
        return event(signer, PredictionEventType.REVIEW_VALIDATION_RECORDED,
                new ReviewValidationPayload(grant, GROUP, LANE, reviewer, 1, TASK, SNAPSHOT, result, reason,
                        "agt-owner").encode());
    }

    private static void arrive(TaskCompletionProjection projection, PredictionEvent event) throws IOException {
        projection.validate(event, ApplyMode.ARRIVAL);
        projection.apply(event);
    }

    private static TaskCompletionProjection with(PredictionEvent... events) throws IOException {
        TaskCompletionProjection projection = new TaskCompletionProjection();
        for (PredictionEvent event : events) {
            arrive(projection, event);
        }
        return projection;
    }

    private static Optional<TaskCompletionState> state(TaskCompletionProjection projection) {
        return projection.snapshotState(SNAPSHOT);
    }

    @Test
    void twoReviewersWhoDisagreeEndDisputedInEitherOrder() throws Exception {
        PredictionEvent created = snapshotEvent();
        PredictionEvent accepted = verdict(reviewerOne, GRANT_1, "agt-r1", "ACCEPTED");
        PredictionEvent rejected = verdict(reviewerTwo, GRANT_2, "agt-r2", "REJECTED");

        TaskCompletionProjection firstOrder = with(created, accepted, rejected);
        TaskCompletionProjection secondOrder = with(created, rejected, accepted);

        for (TaskCompletionProjection projection : List.of(firstOrder, secondOrder)) {
            assertEquals(TaskCompletionState.REVIEW_DISPUTED, state(projection).orElseThrow());
            assertEquals(TaskCompletionState.REVIEW_DISPUTED, projection.taskState(TASK));
            assertEquals(Map.of("agt-r1", "ACCEPTED", "agt-r2", "REJECTED"),
                    projection.reviewDispute(new SnapshotId(SNAPSHOT)));
            assertTrue(projection.eligibleSnapshots().isEmpty());
        }
    }

    @Test
    void twoAcceptancesEndAcceptedAndHaveNoDispute() throws Exception {
        PredictionEvent created = snapshotEvent();
        PredictionEvent first = verdict(reviewerOne, GRANT_1, "agt-r1", "ACCEPTED");
        PredictionEvent second = verdict(reviewerTwo, GRANT_2, "agt-r2", "ACCEPTED");

        TaskCompletionProjection firstOrder = with(created, first, second);
        TaskCompletionProjection secondOrder = with(created, second, first);

        for (TaskCompletionProjection projection : List.of(firstOrder, secondOrder)) {
            assertEquals(TaskCompletionState.REVIEW_ACCEPTED, state(projection).orElseThrow());
            assertTrue(projection.reviewDispute(new SnapshotId(SNAPSHOT)).isEmpty());
        }
    }

    @Test
    void aVerdictBeforeItsSnapshotIsHeldBackOnArrivalAndSilentOnReplay() throws Exception {
        PredictionEvent created = snapshotEvent();
        PredictionEvent accepted = verdict(reviewerOne, GRANT_1, "agt-r1", "ACCEPTED");
        TaskCompletionProjection projection = new TaskCompletionProjection();

        MissingReferenceException held = assertThrows(MissingReferenceException.class,
                () -> projection.validate(accepted, ApplyMode.ARRIVAL));
        assertTrue(held instanceof CoordinationException);
        assertTrue(state(projection).isEmpty());
        assertDoesNotThrow(() -> projection.apply(accepted));
        assertTrue(state(projection).isEmpty());
        assertTrue(projection.reviewDispute(new SnapshotId(SNAPSHOT)).isEmpty());

        TaskCompletionProjection afterHold = new TaskCompletionProjection();
        assertThrows(MissingReferenceException.class, () -> afterHold.validate(accepted, ApplyMode.ARRIVAL));
        assertTrue(state(afterHold).isEmpty());
        arrive(afterHold, created);
        arrive(afterHold, accepted);
        assertEquals(state(with(created, accepted)), state(afterHold));
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, state(afterHold).orElseThrow());
    }

    @Test
    void anIntegrationOutranksALateRejectedVerdictInEitherOrder() throws Exception {
        PredictionEvent created = snapshotEvent();
        PredictionEvent accepted = verdict(reviewerOne, GRANT_1, "agt-r1", "ACCEPTED");
        PredictionEvent integrated = event(writer, PredictionEventType.TASK_INTEGRATED, snapshot().encode());
        PredictionEvent rejected = verdict(reviewerTwo, GRANT_2, "agt-r2", "REJECTED");

        TaskCompletionProjection firstOrder = with(created, accepted, integrated, rejected);
        TaskCompletionProjection secondOrder = with(created, accepted, rejected, integrated);

        for (TaskCompletionProjection projection : List.of(firstOrder, secondOrder)) {
            assertEquals(TaskCompletionState.INTEGRATED, state(projection).orElseThrow());
            assertEquals(TaskCompletionState.INTEGRATED, projection.taskState(TASK));
        }
    }

    @Test
    void aValidationRefusalOutranksASecondVerdictInEitherOrder() throws Exception {
        PredictionEvent created = snapshotEvent();
        PredictionEvent first = verdict(reviewerOne, GRANT_1, "agt-r1", "ACCEPTED");
        PredictionEvent second = verdict(reviewerTwo, GRANT_2, "agt-r2", "ACCEPTED");
        PredictionEvent refused = event(writer, PredictionEventType.INTEGRATION_VALIDATION_REFUSED,
                new ValidationRefusalPayload("att_1", SNAPSHOT, TASK, GROUP, LINEAGE, 1, LANE, 1, "agt-owner",
                        "integration", "[\"git\"]", ".", "non_zero_exit", 1, "FAIL").encode());

        TaskCompletionProjection firstOrder = with(created, first, refused, second);
        TaskCompletionProjection secondOrder = with(created, first, second, refused);

        for (TaskCompletionProjection projection : List.of(firstOrder, secondOrder)) {
            assertEquals(TaskCompletionState.VALIDATION_REFUSED, state(projection).orElseThrow());
            assertTrue(projection.validationRefusal(SNAPSHOT).isPresent());
        }
    }

    @Test
    void checkingASecondVerdictAfterAnAcceptanceStillRefuses() throws Exception {
        PredictionEvent created = snapshotEvent();
        PredictionEvent accepted = verdict(reviewerOne, GRANT_1, "agt-r1", "ACCEPTED");
        PredictionEvent second = verdict(reviewerTwo, GRANT_2, "agt-r2", "REJECTED");
        TaskCompletionProjection projection = with(created, accepted);

        CoordinationException refused = assertThrows(CoordinationException.class, () -> projection.validate(second));
        assertEquals("REVIEW_DECISION_ALREADY_RECORDED", refused.getMessage());
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, state(projection).orElseThrow());
        assertTrue(projection.reviewDispute(new SnapshotId(SNAPSHOT)).isEmpty());
    }
}
