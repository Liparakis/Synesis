package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.RequestId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.task.CompletionPreparedPayload;
import org.synesis.coordination.domain.task.CompletionUnwoundPayload;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.exception.CoordinationException;

/**
 * An entry acting for a participant is admitted only from the node that signed the entry that introduced that
 * participant (ADR-0098, trust model, guard 2). It stops one computer's build from releasing, finishing or
 * answering for another computer's lane. It guards against accidents, not against a member that signs as someone
 * else: a member that is not trusted is outside this design.
 *
 * <p>The participant an entry acts for is named inside its payload, in a different field for each type. Only
 * types that name one are covered; types keyed by task, prediction, contract, capability or work group carry no
 * participant and are not.</p>
 *
 * <table>
 *   <caption>The participant each covered type acts for</caption>
 *   <tr><th>Event type</th><th>Participant</th></tr>
 *   <tr><td>WORK_INTENT_ANNOUNCED</td><td>the intent's owner (introduces it when new)</td></tr>
 *   <tr><td>WORK_INTENT_SCOPE_EXTENDED</td><td>the amended intent's owner</td></tr>
 *   <tr><td>WORK_INTENT_RELEASED</td><td>the released intent's owner; for a no-change completion, its participant</td></tr>
 *   <tr><td>COORDINATION_REQUESTED</td><td>the requester</td></tr>
 *   <tr><td>COORDINATION_RESPONDED, INBOX_ITEM_ACKNOWLEDGED</td><td>the request's target</td></tr>
 *   <tr><td>CLAIM_HANDOFF_ACCEPTED</td><td>the handoff's target participant, who accepts</td></tr>
 *   <tr><td>PARTICIPANT_HEARTBEAT, _SUSPENDED, _ABANDONED, _REVOKED, _CANCELLED, _DETACHED</td><td>the named participant</td></tr>
 *   <tr><td>RECOVERY_SNAPSHOT_HELD</td><td>the recovery-held participant</td></tr>
 *   <tr><td>LANE_CONTINUATION_ACCEPTED</td><td>the successor's owner (introduces it when new)</td></tr>
 *   <tr><td>REPAIR_LANE_CREATED</td><td>the repair lane's owner (introduces it when new)</td></tr>
 *   <tr><td>LANE_GRANT_CONSUMED</td><td>the grant's target participant, who consumes it</td></tr>
 *   <tr><td>REVIEW_VALIDATION_RECORDED</td><td>the reviewer</td></tr>
 *   <tr><td>TASK_SNAPSHOT_CREATED</td><td>the snapshot's publishing participant</td></tr>
 *   <tr><td>COMPLETION_PREPARED</td><td>the owner of the lane being completed</td></tr>
 *   <tr><td>COMPLETION_UNWOUND</td><td>the replacement intent's owner</td></tr>
 *   <tr><td>PROVIDER_SESSION_TERMINALIZED</td><td>the session's participant</td></tr>
 * </table>
 */
final class ParticipantAttribution {

    private static final String NOT_OWNED = "PARTICIPANT_NOT_OWNED_BY_SIGNER";

    private ParticipantAttribution() {
    }

    /**
     * Refuses the event when it acts for a participant that another node introduced.
     *
     * @param event signed event about to be written
     * @param view  projections as they stand before the event
     * @throws IOException with {@code PARTICIPANT_NOT_OWNED_BY_SIGNER}
     */
    static void require(PredictionEvent event, ProjectionView view) throws IOException {
        if (view.leaderEntry()) {
            return;
        }
        for (ParticipantId participant : actingFor(event, view)) {
            Optional<String> introducer = view.collaborationProjection().introducingNode(participant);
            if (introducer.isPresent() && !introducer.get().equals(event.actorNodeId())) {
                throw new CoordinationException(NOT_OWNED);
            }
        }
    }

    private static List<ParticipantId> actingFor(PredictionEvent event, ProjectionView view) throws IOException {
        byte[] payload = event.payload();
        CollaborationProjection collaboration = view.collaborationProjection();
        return switch (event.type()) {
            case WORK_INTENT_ANNOUNCED -> List.of(CollaborationCodec.decodeIntent(payload).participantIdentity());
            case WORK_INTENT_SCOPE_EXTENDED ->
                    List.of(WorkIntentScopeExtension.decode(payload).intent().participantIdentity());
            case WORK_INTENT_RELEASED -> CollaborationCodec.isNoChangeCompletion(payload)
                    ? List.of(new ParticipantId(CollaborationCodec.decodeNoChangeCompletion(payload).participant()))
                    : collaboration.intent(CollaborationCodec.decodeRelease(payload))
                            .map(intent -> List.of(intent.participantIdentity())).orElse(List.of());
            case COORDINATION_REQUESTED -> List.of(CollaborationCodec.decodeRequest(payload).requesterIdentity());
            case COORDINATION_RESPONDED ->
                    targetOf(collaboration, CollaborationCodec.decodeResponse(payload).request());
            case INBOX_ITEM_ACKNOWLEDGED ->
                    targetOf(collaboration, new RequestId(CollaborationCodec.decodeUuidText(payload)));
            case CLAIM_HANDOFF_ACCEPTED -> List.of(CollaborationCodec.decodeHandoff(payload).targetParticipant());
            case PARTICIPANT_HEARTBEAT, PARTICIPANT_SUSPENDED, PARTICIPANT_ABANDONED, PARTICIPANT_REVOKED,
                 PARTICIPANT_CANCELLED, PARTICIPANT_DETACHED ->
                    List.of(new ParticipantId(CollaborationCodec.decodeHeartbeat(payload)));
            case RECOVERY_SNAPSHOT_HELD -> List.of(CollaborationCodec.decodeRecovery(payload).participantId());
            case LANE_CONTINUATION_ACCEPTED -> List.of(CollaborationCodec.decodeContinuation(payload).targetOwner());
            case REPAIR_LANE_CREATED -> List.of(RepairLanePayload.decode(payload).targetIntent().participantIdentity());
            case LANE_GRANT_CONSUMED -> {
                // The grant is read from the log, not trusted from the entry.
                LaneGrant named = CollaborationCodec.decodeLaneGrant(payload);
                yield view.workGroupProjection().grants().stream()
                        .filter(grant -> grant.identity().equals(named.identity())).findFirst()
                        .map(grant -> List.of(new ParticipantId(grant.targetParticipant()))).orElse(List.of());
            }
            case REVIEW_VALIDATION_RECORDED ->
                    List.of(new ParticipantId(ReviewValidationPayload.decode(payload).targetParticipant()));
            case TASK_SNAPSHOT_CREATED ->
                    List.of(TaskSnapshotPayload.decode(payload).provenance().participantIdentity());
            case COMPLETION_PREPARED -> collaboration.intent(CompletionPreparedPayload.decode(payload).laneIdentity())
                    .map(intent -> List.of(intent.participantIdentity())).orElse(List.of());
            case COMPLETION_UNWOUND ->
                    List.of(CompletionUnwoundPayload.decode(payload).replacementIntent().participantIdentity());
            case PROVIDER_SESSION_TERMINALIZED ->
                    List.of(new ParticipantId(ProviderSessionTerminalPayload.decode(payload).participant()));
            default -> List.of();
        };
    }

    private static List<ParticipantId> targetOf(CollaborationProjection collaboration, RequestId request) {
        return collaboration.requests().stream().filter(candidate -> candidate.identity().equals(request)).findFirst()
                .map(found -> List.of(found.targetIdentity())).orElse(List.of());
    }
}
