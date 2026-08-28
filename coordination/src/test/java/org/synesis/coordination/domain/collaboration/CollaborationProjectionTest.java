package org.synesis.coordination.domain.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.NodeIdentity;

/** Verifies that terminal participant fences survive projection replay and heartbeat. */
final class CollaborationProjectionTest {
    private static final UUID PROJECT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID WORK_GROUP = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID AUTHORITY = UUID.fromString("10000000-0000-4000-8000-000000000003");

    @Test
    void revokedParticipantRejectsHeartbeatAndReannounceWithoutChangingUnrelatedState()
            throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        CollaborationProjection projection = new CollaborationProjection();
        WorkIntent abandoned = intent("agt_abandoned", "src/abandoned.py");
        WorkIntent unrelated = intent("agt_unrelated", "src/unrelated.py");

        projection.apply(event(identity, 1, PredictionEventType.WORK_INTENT_ANNOUNCED,
                CollaborationCodec.encodeIntent(abandoned)));
        projection.apply(event(identity, 2, PredictionEventType.WORK_INTENT_ANNOUNCED,
                CollaborationCodec.encodeIntent(unrelated)));
        projection.apply(event(identity, 3, PredictionEventType.PARTICIPANT_REVOKED,
                CollaborationCodec.encodeHeartbeat(abandoned.participant())));

        assertEquals(Participant.State.REVOKED,
                projection.participantState(abandoned.participant()).orElseThrow());
        assertTrue(projection.activeIntents().stream()
                .noneMatch(candidate -> candidate.participant().equals(abandoned.participant())));
        Participant unrelatedBefore = projection.participants().stream()
                .filter(candidate -> candidate.id().equals(unrelated.participant()))
                .findFirst()
                .orElseThrow();

        PredictionEvent heartbeat = event(identity, 4, PredictionEventType.PARTICIPANT_HEARTBEAT,
                CollaborationCodec.encodeHeartbeat(abandoned.participant()));
        assertThrows(java.io.IOException.class, () -> projection.validate(heartbeat));
        assertThrows(java.io.IOException.class, () -> projection.apply(heartbeat));
        assertEquals(Participant.State.REVOKED,
                projection.participantState(abandoned.participant()).orElseThrow());

        WorkIntent replacement = intent(abandoned.participant(), "src/replacement.py");
        assertThrows(java.io.IOException.class, () -> projection.apply(
                event(identity, 5, PredictionEventType.WORK_INTENT_ANNOUNCED,
                        CollaborationCodec.encodeIntent(replacement))));
        assertEquals(Participant.State.REVOKED,
                projection.participantState(abandoned.participant()).orElseThrow());

        for (PredictionEventType lifecycle : List.of(
                PredictionEventType.PARTICIPANT_ABANDONED,
                PredictionEventType.PARTICIPANT_SUSPENDED,
                PredictionEventType.PARTICIPANT_CANCELLED,
                PredictionEventType.PARTICIPANT_DETACHED)) {
            PredictionEvent terminalLifecycle = event(identity, 6 + lifecycle.ordinal(), lifecycle,
                    CollaborationCodec.encodeHeartbeat(abandoned.participant()));
            assertEquals("SESSION_EPOCH_FENCED",
                    assertThrows(java.io.IOException.class, () -> projection.validate(terminalLifecycle)).getMessage());
            assertEquals("SESSION_EPOCH_FENCED",
                    assertThrows(java.io.IOException.class, () -> projection.apply(terminalLifecycle)).getMessage());
            assertEquals(Participant.State.REVOKED,
                    projection.participantState(abandoned.participant()).orElseThrow());
        }

        assertEquals(unrelatedBefore, projection.participants().stream()
                .filter(candidate -> candidate.id().equals(unrelated.participant()))
                .findFirst()
                .orElseThrow());
        assertTrue(projection.activeIntents().stream()
                .allMatch(candidate -> candidate.participant().equals(unrelated.participant())));
    }

    @Test
    void activeParticipantHeartbeatStillRefreshesActivityAndClaims() throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        CollaborationProjection projection = new CollaborationProjection();
        WorkIntent active = intent("agt_active", "src/active.py");

        projection.apply(event(identity, 1, PredictionEventType.WORK_INTENT_ANNOUNCED,
                CollaborationCodec.encodeIntent(active)));
        projection.apply(event(identity, 2, PredictionEventType.PARTICIPANT_HEARTBEAT,
                CollaborationCodec.encodeHeartbeat(active.participant())));

        Participant participant = projection.participants().stream()
                .filter(candidate -> candidate.id().equals(active.participant()))
                .findFirst()
                .orElseThrow();
        assertEquals(Participant.State.ACTIVE, participant.state());
        assertEquals(active.selectors(), participant.claims());
        assertEquals(2L, participant.lastVerifiedActivity());
    }

    @Test
    void completedParticipantCannotBeRevivedByLateHeartbeat() throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        CollaborationProjection projection = new CollaborationProjection();
        WorkIntent completed = intent("agt_completed", "src/completed.py");

        projection.apply(event(identity, 1, PredictionEventType.WORK_INTENT_ANNOUNCED,
                CollaborationCodec.encodeIntent(completed)));
        projection.apply(event(identity, 2, PredictionEventType.WORK_INTENT_RELEASED,
                CollaborationCodec.encodeRelease(completed.intentId())));

        PredictionEvent lateHeartbeat = event(identity, 3, PredictionEventType.PARTICIPANT_HEARTBEAT,
                CollaborationCodec.encodeHeartbeat(completed.participant()));
        assertEquals("SESSION_EPOCH_FENCED",
                assertThrows(java.io.IOException.class, () -> projection.validate(lateHeartbeat)).getMessage());
        assertEquals("SESSION_EPOCH_FENCED",
                assertThrows(java.io.IOException.class, () -> projection.apply(lateHeartbeat)).getMessage());
        assertEquals(Participant.State.COMPLETED,
                projection.participantState(completed.participant()).orElseThrow());
    }

    private static WorkIntent intent(String participant, String path) {
        return new WorkIntent(
                UUID.nameUUIDFromBytes((participant + ":" + path).getBytes(StandardCharsets.UTF_8)),
                PROJECT,
                participant,
                "codex",
                UUID.nameUUIDFromBytes(("task:" + participant).getBytes(StandardCharsets.UTF_8)),
                "preserve participant fence",
                "fenced participants cannot resume",
                "base-commit",
                List.of(ResourceSelector.pathExact(path)),
                1,
                WORK_GROUP,
                AUTHORITY,
                WorkIntent.Status.ANNOUNCED);
    }

    private static PredictionEvent event(
            NodeIdentity identity,
            long sequence,
            PredictionEventType type,
            byte[] payload)
            throws Exception {
        return PredictionEvent.create(
                PROJECT,
                UUID.nameUUIDFromBytes(("prediction:" + sequence).getBytes(StandardCharsets.UTF_8)),
                sequence,
                type,
                identity.nodeId(),
                payload,
                new byte[32],
                identity,
                sequence);
    }
}
