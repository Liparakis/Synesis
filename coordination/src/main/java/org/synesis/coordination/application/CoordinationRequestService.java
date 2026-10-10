package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ClaimHandoff;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.RequestId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationMissingElementException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.CoordinationStateException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Owns coordination-request admission, response, and review-grant transitions.
 */
final class CoordinationRequestService {

    private final ProjectAppends appends;
    private final NodeIdentity signer;

    /**
     * Validates and initializes this coordination request service.
     *
     * @param store  project event store
     * @param signer identity used to sign the event
     */
    CoordinationRequestService(PredictionEventStore store, NodeIdentity signer) {
        this.appends = new ProjectAppends(store);
        this.signer = CoordinationNullException.requireNonNull(signer, "signer");
    }


    /**
     * Finds a request and confirms that the caller remains its non-terminal target.
     *
     * @param current     authoritative project projection
     * @param participant responding participant
     * @param requestId   request identity
     * @return addressed request
     * @throws IOException when the request is missing, misaddressed, or terminally fenced
     */
    private static CoordinationRequest requireAddressedRequest(PredictionEventStore current,
            ParticipantId participant,
            RequestId requestId) throws IOException {
        CoordinationRequest request = current.collaborationProjection().requests().stream()
                .filter(candidate -> candidate.identity().equals(requestId)).findFirst()
                .orElseThrow(() -> new CoordinationException("REQUEST_NOT_FOUND"));
        if (!request.targetIdentity().equals(participant)) {
            throw new CoordinationException("REQUEST_TARGET_MISMATCH");
        }
        if (current.collaborationProjection().isParticipantTerminal(participant)) {
            throw new CoordinationException("SESSION_TERMINAL");
        }
        return request;
    }

    /**
     * Finds an inbox request and confirms that it targets the exact participant.
     *
     * @param current     authoritative project projection
     * @param participant intended inbox recipient
     * @param item        server-issued inbox item identity
     * @return request addressed to the participant
     * @throws IOException when the item is missing or belongs to another participant
     */
    private static CoordinationRequest requireInboxItem(PredictionEventStore current,
            ParticipantId participant,
            RequestId item) throws IOException {
        CoordinationRequest request = current.collaborationProjection().requests().stream()
                .filter(candidate -> candidate.identity().equals(item)).findFirst()
                .orElseThrow(() -> new CoordinationException("INBOX_ITEM_NOT_FOUND"));
        if (!request.targetIdentity().equals(participant)) {
            throw new CoordinationException("INBOX_ITEM_CALLER_MISMATCH");
        }
        return request;
    }

    /**
     * Opens a negotiation request against the owner of a conflicting intent.
     *
     * @param requester           requester ID
     * @param conflictingIntentId conflicting intent
     * @param kind                request kind
     * @param proposal            proposal
     * @return request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    CoordinationRequest request(ParticipantId requester,
            WorkIntentId conflictingIntentId,
            CoordinationRequest.Kind kind,
            String proposal) throws IOException, GeneralSecurityException {
        return request(requester, conflictingIntentId, kind, proposal, null);
    }

    /**
     * Opens a negotiation request, optionally bound to one exact immutable review snapshot.
     *
     * @param requesterIdentity requester participant identity
     * @param conflictingIntent target intent identity
     * @param kind              request kind
     * @param proposal          proposal
     * @param snapshotIdentity  exact review snapshot, required by mandatory-review groups
     * @return request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    CoordinationRequest request(ParticipantId requesterIdentity,
            WorkIntentId conflictingIntent,
            CoordinationRequest.Kind kind,
            String proposal,
            SnapshotId snapshotIdentity) throws IOException, GeneralSecurityException {
        ParticipantId requester = CoordinationNullException.requireNonNull(requesterIdentity, "requester");
        WorkIntentId target = CoordinationNullException.requireNonNull(conflictingIntent, "conflicting intent");
        CoordinationRequest.Kind requestKind = CoordinationNullException.requireNonNull(kind, "request kind");
        String requestProposal = CoordinationNullException.requireNonNull(proposal, "proposal");
        return appends.call(current -> {
            if (requestKind == CoordinationRequest.Kind.REVIEW) {
                Optional<CoordinationRequest> replay = ReviewAdmissionRules.existingReviewRequest(current,
                        requester,
                        target,
                        snapshotIdentity);
                if (replay.isPresent()) {
                    return replay.orElseThrow(CoordinationMissingElementException::new);
                }
            }
            WorkIntent conflict = current.collaborationProjection().intent(target)
                    .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
            CoordinationRequest request = new CoordinationRequest(UUID.randomUUID(),
                    current.projectId(),
                    requester.value(),
                    conflict.participantIdentity().value(),
                    target.value(),
                    requestKind,
                    requestProposal,
                    CoordinationRequest.Status.PENDING,
                    snapshotIdentity == null ? null : snapshotIdentity.value());
            current.append(request.requestId(),
                    PredictionEventType.COORDINATION_REQUESTED,
                    signer.nodeId(),
                    CollaborationCodec.encodeRequest(request),
                    signer);
            return request;
        });
    }


    /**
     * Offers an atomic handoff to another active participant.
     *
     * @param ownerIdentity  current owner identity
     * @param intentIdentity intent identity being handed off
     * @param targetIdentity target participant identity
     * @param proposal       handoff proposal
     * @return pending request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    CoordinationRequest offerHandoff(ParticipantId ownerIdentity,
            WorkIntentId intentIdentity,
            ParticipantId targetIdentity,
            String proposal) throws IOException, GeneralSecurityException {
        ParticipantId owner = CoordinationNullException.requireNonNull(ownerIdentity, "owner");
        WorkIntentId handoffIntent = CoordinationNullException.requireNonNull(intentIdentity, "intent ID");
        ParticipantId target = CoordinationNullException.requireNonNull(targetIdentity, "target participant");
        String handoffProposal = CoordinationNullException.requireNonNull(proposal, "proposal");
        return appends.call(current -> {
            WorkIntent intent = current.collaborationProjection().intent(handoffIntent)
                    .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
            intent.requireOwnedBy(owner);
            if (current.collaborationProjection().isParticipantTerminal(owner) || current.collaborationProjection()
                    .isParticipantTerminal(target)) {
                throw new CoordinationException("SESSION_TERMINAL");
            }
            boolean activeTarget = current.collaborationProjection().participants().stream()
                    .anyMatch(participant -> participant.identity().equals(target)
                            && participant.state() == Participant.State.ACTIVE) || current.collaborationProjection()
                    .activeIntents().stream().anyMatch(candidate -> candidate.participantIdentity().equals(target));
            if (!activeTarget) {
                throw new CoordinationException("HANDOFF_TARGET_NOT_ACTIVE");
            }
            CoordinationRequest request = new CoordinationRequest(UUID.randomUUID(),
                    current.projectId(),
                    owner.value(),
                    target.value(),
                    handoffIntent.value(),
                    CoordinationRequest.Kind.HANDOFF,
                    handoffProposal,
                    CoordinationRequest.Status.PENDING);
            current.append(request.requestId(),
                    PredictionEventType.COORDINATION_REQUESTED,
                    signer.nodeId(),
                    CollaborationCodec.encodeRequest(request),
                    signer);
            return request;
        });
    }

    /**
     * Responds idempotently to a request addressed to the participant.
     *
     * @param participantIdentity target participant identity
     * @param requestIdentity     request identity
     * @param status              response status
     * @param proposal            revised proposal
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    void respond(ParticipantId participantIdentity,
            RequestId requestIdentity,
            CoordinationRequest.Status status,
            String proposal) throws IOException, GeneralSecurityException {
        ParticipantId participant = CoordinationNullException.requireNonNull(participantIdentity, "participant");
        RequestId requestId = CoordinationNullException.requireNonNull(requestIdentity, "request ID");
        if (status == CoordinationRequest.Status.PENDING) {
            throw new CoordinationArgumentException("pending is not a response");
        }
        appends.run(current -> {
            CoordinationRequest request = requireAddressedRequest(current, participant, requestId);
            ReviewAdmissionRules.validateReviewApproval(current, request, status);
            if (replayResolvedResponse(current, request, status)) {
                return;
            }
            current.append(requestId.value(),
                    PredictionEventType.COORDINATION_RESPONDED,
                    signer.nodeId(),
                    CollaborationCodec.encodeResponse(requestId, status, proposal),
                    signer);
            if (status == CoordinationRequest.Status.ACCEPTED && request.kind() == CoordinationRequest.Kind.HANDOFF) {
                acceptHandoff(current, request);
            }
            if (status == CoordinationRequest.Status.ACCEPTED && request.kind() == CoordinationRequest.Kind.REVIEW) {
                issueReviewGrantIfAbsent(current, request);
            }
        });
    }


    /**
     * Replays a matching resolved response and repairs any missing accepted-review grant.
     *
     * @param current authoritative project projection
     * @param request addressed request
     * @param status  requested response status
     * @return true when the existing response was replayed
     * @throws IOException              when the request already has a different response
     * @throws GeneralSecurityException when repairing the review grant fails
     */
    private boolean replayResolvedResponse(PredictionEventStore current,
            CoordinationRequest request,
            CoordinationRequest.Status status) throws IOException, GeneralSecurityException {
        if (request.status() == CoordinationRequest.Status.PENDING) {
            return false;
        }
        if (request.status() != status) {
            throw new CoordinationException("REQUEST_ALREADY_RESOLVED");
        }
        if (status == CoordinationRequest.Status.ACCEPTED && request.kind() == CoordinationRequest.Kind.REVIEW) {
            issueReviewGrantIfAbsent(current, request);
        }
        return true;
    }

    /**
     * Appends the handoff-specific transition following an accepted request response.
     *
     * @param current authoritative project projection
     * @param request accepted handoff request
     * @throws IOException              when the source intent no longer exists
     * @throws GeneralSecurityException when event signing fails
     */
    private void acceptHandoff(PredictionEventStore current, CoordinationRequest request)
            throws IOException, GeneralSecurityException {
        WorkIntent intent = current.collaborationProjection().intent(request.conflictingIntent())
                .orElseThrow(CoordinationMissingElementException::new);
        current.append(request.conflictingIntent().value(),
                PredictionEventType.CLAIM_HANDOFF_ACCEPTED,
                signer.nodeId(),
                CollaborationCodec.encodeHandoff(new ClaimHandoff(intent.identity(),
                        request.targetIdentity(),
                        intent.claimEpoch())),
                signer);
    }

    /**
     * Issues the deterministic single-use grant after an accepted review request.
     *
     * @param current fresh authoritative project projection
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private void issueReviewGrantIfAbsent(PredictionEventStore current, CoordinationRequest request)
            throws IOException, GeneralSecurityException {
        WorkIntent intent = current.collaborationProjection().intent(request.conflictingIntent())
                .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
        if (current.collaborationProjection().successorIntent(intent.identity()).isPresent()) {
            throw new CoordinationException("REVIEW_TARGET_STALE");
        }
        ReviewAdmissionRules.validateReviewRequest(current, request, intent);
        if (!request.targetIdentity().equals(intent.participantIdentity())) {
            throw new CoordinationException("REVIEW_TARGET_PARTICIPANT_MISMATCH");
        }
        UUID grantId = LaneGrant.reviewGrantId(request.requestId());
        boolean exists = current.workGroupProjection().grants().stream()
                .anyMatch(grant -> grant.grantId().equals(grantId));
        if (!exists) {
            UUID reviewedIntentId = intent.intentId();
            long reviewedClaimEpoch = intent.version();
            if (request.snapshot().isPresent()) {
                TaskSnapshotRecord snapshot = current.taskCompletionProjection()
                        .findSnapshotById(request.snapshot().orElseThrow(CoordinationMissingElementException::new))
                        .orElseThrow(() -> new CoordinationException("REVIEW_SNAPSHOT_NOT_FOUND"));
                reviewedIntentId = snapshot.provenance().laneId();
                reviewedClaimEpoch = snapshot.provenance().claimEpoch();
            }
            LaneGrant grant = new LaneGrant(grantId,
                    intent.workGroupId(),
                    reviewedIntentId,
                    request.requesterIdentity().value(),
                    reviewedClaimEpoch,
                    true,
                    request.snapshot().map(SnapshotId::value).orElse(null));
            current.append(grantId,
                    PredictionEventType.LANE_GRANT_ISSUED,
                    signer.nodeId(),
                    CollaborationCodec.encodeLaneGrant(grant),
                    signer);
        }
    }

    /**
     * Acknowledges a server-issued inbox item for the exact participant, idempotently.
     *
     * @param participantIdentity exact participant identity
     * @param itemIdentity        server-issued request/inbox item identity
     * @throws IOException              persistence or authorization failure
     * @throws GeneralSecurityException signing failure
     */
    void acknowledgeInbox(ParticipantId participantIdentity, RequestId itemIdentity)
            throws IOException, GeneralSecurityException {
        ParticipantId participant = CoordinationNullException.requireNonNull(participantIdentity, "participant");
        RequestId item = CoordinationNullException.requireNonNull(itemIdentity, "inbox item ID");
        appends.run(current -> {
            requireInboxItem(current, participant, item);
            if (!current.collaborationProjection().inboxAcknowledged(item)) {
                current.append(item.value(),
                        PredictionEventType.INBOX_ITEM_ACKNOWLEDGED,
                        signer.nodeId(),
                        CollaborationCodec.encodeUuidText(item),
                        signer);
            }
        });
    }

    /**
     * Resolves and acknowledges a coordination inbox item atomically.
     *
     * @param participantIdentity exact participant identity
     * @param itemIdentity        server-issued inbox item identity
     * @param status              terminal response status
     * @param proposal            response or resolution proposal
     * @throws IOException              authorization or transition failure
     * @throws GeneralSecurityException signing failure
     */
    void resolveInbox(ParticipantId participantIdentity,
            RequestId itemIdentity,
            CoordinationRequest.Status status,
            String proposal) throws IOException, GeneralSecurityException {
        if (status == null || status == CoordinationRequest.Status.PENDING) {
            throw new CoordinationArgumentException("inbox resolution requires terminal status");
        }
        ParticipantId participant = CoordinationNullException.requireNonNull(participantIdentity, "participant");
        RequestId item = CoordinationNullException.requireNonNull(itemIdentity, "inbox item ID");
        appends.run(current -> {
            CoordinationRequest request = requireInboxItem(current, participant, item);
            if (request.status() == CoordinationRequest.Status.PENDING) {
                current.append(item.value(),
                        PredictionEventType.COORDINATION_RESPONDED,
                        signer.nodeId(),
                        CollaborationCodec.encodeResponse(item, status, proposal),
                        signer);
            } else if (request.status() != status) {
                throw new CoordinationException("REQUEST_ALREADY_RESOLVED");
            }
            if (!current.collaborationProjection().inboxAcknowledged(item)) {
                current.append(item.value(),
                        PredictionEventType.INBOX_ITEM_ACKNOWLEDGED,
                        signer.nodeId(),
                        CollaborationCodec.encodeUuidText(item),
                        signer);
            }
        });
    }

    /**
     * Returns durable coordination requests for discovery.
     *
     * @return requests
     */
    List<CoordinationRequest> requests() {
        try {
            return appends.fresh().collaborationProjection().requests();
        } catch (Exception failure) {
            throw new CoordinationStateException("COLLABORATION_STATE_UNAVAILABLE", failure);
        }
    }
}
