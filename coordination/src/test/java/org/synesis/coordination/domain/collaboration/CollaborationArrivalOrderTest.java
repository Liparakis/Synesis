package org.synesis.coordination.domain.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.MissingReferenceException;
import org.synesis.link.identity.NodeIdentity;

/**
 * Lane and request entries written on two computers meet in either order and end in one state.
 */
final class CollaborationArrivalOrderTest {

    private static final UUID PROJECT = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID GROUP = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final String SHARED_PATH = "src/shared.py";

    private final NodeIdentity nodeA = NodeIdentity.generate();
    private final NodeIdentity nodeB = NodeIdentity.generate();
    private long sequence;

    CollaborationArrivalOrderTest() throws Exception {
    }

    private PredictionEvent event(NodeIdentity signer, PredictionEventType type, byte[] payload) throws Exception {
        sequence++;
        return PredictionEvent.create(PROJECT, UUID.randomUUID(), sequence, type, signer.nodeId(), payload,
                new byte[32], signer, sequence);
    }

    private static WorkIntent intent(String participant, String path) {
        UUID lane = UUID.nameUUIDFromBytes(("lane:" + participant + ":" + path).getBytes(StandardCharsets.UTF_8));
        return new WorkIntent(lane, PROJECT, participant, "codex",
                UUID.nameUUIDFromBytes(("task:" + participant).getBytes(StandardCharsets.UTF_8)), "goal",
                "acceptance", "base", List.of(ResourceSelector.pathExact(path)), 1, GROUP,
                WorkIntent.defaultAuthorityLineage(lane), WorkIntent.Status.ANNOUNCED);
    }

    private PredictionEvent announced(NodeIdentity signer, WorkIntent intent) throws Exception {
        return event(signer, PredictionEventType.WORK_INTENT_ANNOUNCED, CollaborationCodec.encodeIntent(intent));
    }

    private PredictionEvent released(NodeIdentity signer, WorkIntent intent) throws Exception {
        return event(signer, PredictionEventType.WORK_INTENT_RELEASED,
                CollaborationCodec.encodeRelease(intent.intentId()));
    }

    private static CoordinationRequest request(WorkIntent target) {
        return new CoordinationRequest(UUID.nameUUIDFromBytes("request".getBytes(StandardCharsets.UTF_8)), PROJECT,
                "agt-peer", target.participant(), target.intentId(), CoordinationRequest.Kind.CONTRACT, "please",
                CoordinationRequest.Status.PENDING);
    }

    private PredictionEvent requested(NodeIdentity signer, CoordinationRequest request) throws Exception {
        return event(signer, PredictionEventType.COORDINATION_REQUESTED, CollaborationCodec.encodeRequest(request));
    }

    private PredictionEvent responded(NodeIdentity signer, CoordinationRequest request,
            CoordinationRequest.Status status) throws Exception {
        return event(signer, PredictionEventType.COORDINATION_RESPONDED,
                CollaborationCodec.encodeResponse(request.identity(), status, "answer " + status));
    }

    private static void arrive(CollaborationProjection projection, PredictionEvent event) throws IOException {
        projection.validate(event, ApplyMode.ARRIVAL);
        projection.apply(event);
    }

    private static CollaborationProjection with(PredictionEvent... events) throws IOException {
        CollaborationProjection projection = new CollaborationProjection();
        for (PredictionEvent event : events) {
            arrive(projection, event);
        }
        return projection;
    }

    private NodeIdentity lower() {
        return nodeA.nodeId().compareTo(nodeB.nodeId()) <= 0 ? nodeA : nodeB;
    }

    private NodeIdentity higher() {
        return lower() == nodeA ? nodeB : nodeA;
    }

    @Test
    void twoOverlappingAnnouncementsBothStandAndTheOverlapIsReportedInEitherOrder() throws Exception {
        WorkIntent first = intent("agt-a", SHARED_PATH);
        WorkIntent second = intent("agt-b", SHARED_PATH);
        PredictionEvent byA = announced(nodeA, first);
        PredictionEvent byB = announced(nodeB, second);

        CollaborationProjection firstOrder = with(byA, byB);
        CollaborationProjection secondOrder = with(byB, byA);

        for (CollaborationProjection projection : List.of(firstOrder, secondOrder)) {
            assertEquals(Set.of(first, second), Set.copyOf(projection.activeIntents()));
            List<ClaimConflict> overlaps = projection.conflicts(List.of(ResourceSelector.pathExact(SHARED_PATH)));
            assertEquals(Set.of("agt-a", "agt-b"),
                    Set.copyOf(overlaps.stream().map(ClaimConflict::participant).toList()));
            assertFalse(projection.conflicts(first.selectors(), first.identity()).isEmpty());
            assertFalse(projection.conflicts(second.selectors(), second.identity()).isEmpty());
        }
    }

    @Test
    void checkingTheSecondOverlappingAnnouncementStillRefuses() throws Exception {
        WorkIntent first = intent("agt-a", SHARED_PATH);
        PredictionEvent second = announced(nodeB, intent("agt-b", SHARED_PATH));
        CollaborationProjection projection = with(announced(nodeA, first));

        CoordinationException refused = assertThrows(CoordinationException.class, () -> projection.validate(second));
        assertEquals("OVERLAPPING_CLAIM", refused.getMessage());
        assertEquals(List.of(first), projection.activeIntents());
    }

    @Test
    void aReleaseOfALaneNeverSeenIsHeldBackUntilTheLaneArrives() throws Exception {
        WorkIntent lane = intent("agt-a", "src/a.py");
        PredictionEvent announce = announced(nodeA, lane);
        PredictionEvent release = released(nodeA, lane);
        CollaborationProjection projection = new CollaborationProjection();

        MissingReferenceException held = assertThrows(MissingReferenceException.class,
                () -> projection.validate(release, ApplyMode.ARRIVAL));
        assertEquals("INTENT_NOT_FOUND", held.getMessage());
        assertTrue(projection.activeIntents().isEmpty());

        arrive(projection, announce);
        arrive(projection, release);
        assertTrue(projection.activeIntents().isEmpty());
    }

    @Test
    void aReleaseOfALaneAlreadyEndedHasNoEffectAndDoesNotThrow() throws Exception {
        WorkIntent ended = intent("agt-a", "src/a.py");
        WorkIntent other = intent("agt-b", "src/b.py");
        CollaborationProjection projection = with(announced(nodeA, ended), announced(nodeB, other),
                released(nodeA, ended));
        Set<WorkIntent> before = Set.copyOf(projection.activeIntents());

        PredictionEvent again = released(nodeB, ended);
        projection.validate(again, ApplyMode.ARRIVAL);
        projection.apply(again);

        assertEquals(before, Set.copyOf(projection.activeIntents()));
        assertEquals(Set.of(other), before);
    }

    @Test
    void checkingAReleaseOfALaneAlreadyEndedStillRefuses() throws Exception {
        WorkIntent ended = intent("agt-a", "src/a.py");
        CollaborationProjection projection = with(announced(nodeA, ended), released(nodeA, ended));
        PredictionEvent again = released(nodeA, ended);

        CoordinationException refused = assertThrows(CoordinationException.class, () -> projection.validate(again));
        assertEquals("INTENT_NOT_FOUND", refused.getMessage());
    }

    @Test
    void anAnswerBeforeItsRequestIsHeldBackUntilTheRequestArrives() throws Exception {
        WorkIntent lane = intent("agt-owner", "src/a.py");
        CoordinationRequest request = request(lane);
        PredictionEvent announce = announced(nodeA, lane);
        PredictionEvent ask = requested(nodeB, request);
        PredictionEvent answer = responded(nodeA, request, CoordinationRequest.Status.ACCEPTED);
        CollaborationProjection projection = with(announce);

        MissingReferenceException held = assertThrows(MissingReferenceException.class,
                () -> projection.validate(answer, ApplyMode.ARRIVAL));
        assertEquals("REQUEST_NOT_FOUND", held.getMessage());
        assertTrue(projection.requests().isEmpty());

        arrive(projection, ask);
        arrive(projection, answer);
        assertEquals(CoordinationRequest.Status.ACCEPTED, projection.requests().getFirst().status());
        assertEquals(with(announce, ask, answer).requests(), projection.requests());
    }

    @Test
    void aRequestAboutALaneNotSeenYetIsHeldBack() throws Exception {
        WorkIntent lane = intent("agt-owner", "src/a.py");
        PredictionEvent ask = requested(nodeB, request(lane));
        CollaborationProjection projection = new CollaborationProjection();

        assertThrows(MissingReferenceException.class, () -> projection.validate(ask, ApplyMode.ARRIVAL));
        assertTrue(projection.requests().isEmpty());
    }

    @Test
    void twoDifferentAnswersToOneRequestTakeTheLowerNodesStatusInEitherOrder() throws Exception {
        WorkIntent lane = intent("agt-owner", "src/a.py");
        CoordinationRequest request = request(lane);
        PredictionEvent announce = announced(nodeA, lane);
        PredictionEvent ask = requested(nodeB, request);
        PredictionEvent fromLower = responded(lower(), request, CoordinationRequest.Status.REJECTED);
        PredictionEvent fromHigher = responded(higher(), request, CoordinationRequest.Status.ACCEPTED);

        CollaborationProjection firstOrder = with(announce, ask, fromLower, fromHigher);
        CollaborationProjection secondOrder = with(announce, ask, fromHigher, fromLower);

        for (CollaborationProjection projection : List.of(firstOrder, secondOrder)) {
            assertEquals(1, projection.requests().size());
            assertEquals(CoordinationRequest.Status.REJECTED, projection.requests().getFirst().status());
        }
        assertEquals(firstOrder.requests(), secondOrder.requests());

        CollaborationProjection own = with(announce, ask, fromLower);
        CoordinationException refused = assertThrows(CoordinationException.class, () -> own.validate(fromHigher));
        assertEquals("REQUEST_ALREADY_RESOLVED", refused.getMessage());
    }

    private PredictionEvent lifecycle(NodeIdentity signer, PredictionEventType type, WorkIntent intent) throws Exception {
        return event(signer, type, CollaborationCodec.encodeHeartbeat(intent.participant()));
    }

    /**
     * Designer item 18 case 1: the agent's own computer detached it cleanly while a taker apart suspended it. The state
     * was whichever arrived last, and the suspension made an ended participant recoverable again; an end stands now.
     */
    @Test
    void aSuspensionArrivingAfterACleanDetachLeavesTheParticipantDetachedInEitherOrder() throws Exception {
        WorkIntent lane = intent("agt-a", SHARED_PATH);
        PredictionEvent announce = announced(nodeA, lane);
        PredictionEvent detach = lifecycle(nodeA, PredictionEventType.PARTICIPANT_DETACHED, lane);
        PredictionEvent suspend = lifecycle(nodeB, PredictionEventType.PARTICIPANT_SUSPENDED, lane);

        for (CollaborationProjection projection : List.of(with(announce, detach, suspend), with(announce, suspend, detach))) {
            assertEquals(java.util.Optional.of(Participant.State.DETACHED), projection.participantState(new org.synesis.coordination.domain.identity.ParticipantId("agt-a")));
            assertTrue(projection.activeIntents().isEmpty());
        }
    }

    /** Designer item 18 case 1: of two ends, the higher rank stands (revoked, cancelled, completed, detached). */
    @Test
    void ofACancelAndADetachTheCancelStandsInEitherOrder() throws Exception {
        WorkIntent lane = intent("agt-a", SHARED_PATH);
        PredictionEvent announce = announced(nodeA, lane);
        PredictionEvent detach = lifecycle(nodeA, PredictionEventType.PARTICIPANT_DETACHED, lane);
        PredictionEvent cancel = lifecycle(nodeB, PredictionEventType.PARTICIPANT_CANCELLED, lane);

        for (CollaborationProjection projection : List.of(with(announce, detach, cancel), with(announce, cancel, detach))) {
            assertEquals(java.util.Optional.of(Participant.State.CANCELLED), projection.participantState(new org.synesis.coordination.domain.identity.ParticipantId("agt-a")));
        }
    }
}
