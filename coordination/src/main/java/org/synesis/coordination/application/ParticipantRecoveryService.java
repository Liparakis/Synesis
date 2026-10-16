package org.synesis.coordination.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.AppendAdmission;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.RecoveryEvidence;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.SnapshotReference;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationMissingElementException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Owns participant lifecycle and append-locked recovery continuation.
 */
final class ParticipantRecoveryService {

    private final ProjectAppends appends;
    private final NodeIdentity signer;

    /**
     * Validates and initializes this participant recovery service.
     *
     * @param store  project event store
     * @param signer identity used to sign the event
     */
    ParticipantRecoveryService(PredictionEventStore store, NodeIdentity signer) {
        this.appends = new ProjectAppends(store);
        this.signer = CoordinationNullException.requireNonNull(signer, "signer");
    }

    /**
     * Revalidates the continuation before it is appended. The rules that guard authority are the projections'
     * ({@link AppendAdmission}); this early answer runs the same ones, then checks the successor itself.
     *
     * @param current      fresh authoritative project projection
     * @param continuation proposed successor continuation
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void validateContinuation(PredictionEventStore current,
            LaneContinuation continuation,
            boolean requireGrant) throws IOException {
        AppendAdmission.requireLawfulContinuation(current, continuation, requireGrant);
        WorkIntent source = current.collaborationProjection().intent(continuation.sourceIntent())
                .orElseThrow(() -> new CoordinationException("CONTINUATION_SOURCE_NOT_FOUND"));
        validateContinuationTarget(current, source, continuation);
    }

    /**
     * Resolves the durable successor and reports whether this caller owns it.
     */
    private static ContinuationResult continuationResult(PredictionEventStore current,
            LaneContinuation continuation,
            WorkIntentId successorIntentId) throws IOException {
        WorkIntent successor = current.collaborationProjection().intent(successorIntentId)
                .orElseThrow(() -> new CoordinationException("CONTINUATION_SUCCESSOR_NOT_FOUND"));
        boolean callerOwnsSuccessor =
                successor.identity().equals(continuation.targetIntent().identity()) && successor.participantIdentity()
                        .equals(continuation.targetOwner());
        return ContinuationResult.observed(successor, callerOwnsSuccessor);
    }

    /**
     * Ensures a successor preserves the source lane and does not overlap another claim.
     *
     * @param current      fresh authoritative project projection
     * @param source       current source intent
     * @param continuation proposed successor continuation
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void validateContinuationTarget(PredictionEventStore current,
            WorkIntent source,
            LaneContinuation continuation) throws IOException {
        WorkIntent target = continuation.targetIntent();
        if (current.collaborationProjection().intent(target.identity()).isPresent()
                || target.status() != WorkIntent.Status.ANNOUNCED || target.participantIdentity()
                .equals(source.participantIdentity()) || !target.project().equals(source.project()) || !target.task()
                .equals(source.task()) || !target.goal().equals(source.goal()) || !target.acceptance()
                .equals(source.acceptance()) || !target.group().equals(source.group()) || !target.lineage()
                .equals(source.lineage()) || target.intentVersion().value() != source.intentVersion().value() + 1L
                || target.role() != source.role() || !target.reviewTargetSelectors()
                .equals(source.reviewTargetSelectors()) || !target.knownDependencies()
                .equals(source.knownDependencies()) || !target.selectors().equals(source.selectors())) {
            throw new CoordinationException("CONTINUATION_TARGET_INVALID");
        }
        if (current.collaborationProjection().isParticipantTerminal(continuation.targetOwner())) {
            throw new CoordinationException("SESSION_TERMINAL");
        }
        if (current.collaborationProjection().activeIntents().stream()
                .filter(intent -> !intent.identity().equals(source.identity()))
                .filter(intent -> intent.participantIdentity().equals(target.participantIdentity()))
                .anyMatch(intent -> selectorsOverlap(intent.selectors(), target.selectors()))) {
            throw new CoordinationException("OVERLAPPING_CLAIM");
        }
    }

    /**
     * Returns whether any selector pair overlaps.
     */
    private static boolean selectorsOverlap(List<ResourceSelector> left, List<ResourceSelector> right) {
        return left.stream().anyMatch(existing -> right.stream().anyMatch(existing::overlaps));
    }

    /**
     * Checks that a verified call may be recorded for the participant: it must exist and be active.
     *
     * <p>Nothing is appended. The time of the call is current state, not history, and is kept by the
     * caller outside the log; a participant that is suspended, recovery-held or terminal is fenced here
     * exactly as the old heartbeat event fenced it. A fresh replay without the append lock decides,
     * since the check changes nothing and a later fence only affects the next call.</p>
     *
     * @param participant participant ID
     * @throws IOException              when the participant is unknown or fenced, or replay fails
     * @throws GeneralSecurityException when signature verification fails
     */
    void heartbeat(ParticipantId participant) throws IOException, GeneralSecurityException {
        Optional<Participant.State> participantState = appends.fresh().collaborationProjection().participantState(participant);
        if (participantState.isEmpty()) {
            throw new CoordinationException("PARTICIPANT_NOT_FOUND");
        }
        if (participantState.orElseThrow() != Participant.State.ACTIVE) {
            throw new CoordinationException("SESSION_EPOCH_FENCED");
        }
    }

    /**
     * Fences a participant after verified process loss without releasing its claims.
     *
     * @param participant participant handle
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    void suspend(ParticipantId participant) throws IOException, GeneralSecurityException {
        appends.run(current -> {
            ParticipantId durableParticipant = current.collaborationProjection().participantKey(participant)
                    .orElse(null);
            if (durableParticipant == null) {
                return;
            }
            current.append(participantStreamId(durableParticipant),
                    PredictionEventType.PARTICIPANT_SUSPENDED,
                    signer.nodeId(),
                    CollaborationCodec.encodeHeartbeat(participant),
                    signer);
        });
    }

    /**
     * Records verified recovery evidence with an immutable snapshot reference.
     *
     * @param participant       participant handle
     * @param snapshotReference immutable snapshot reference
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    void holdRecovery(ParticipantId participant, SnapshotReference snapshotReference)
            throws IOException, GeneralSecurityException {
        appends.run(current -> {
            ParticipantId durableParticipant = current.collaborationProjection().participantKey(participant)
                    .orElseThrow(() -> new CoordinationException("PARTICIPANT_NOT_FOUND"));
            if (snapshotReference.value().isBlank()) {
                throw new CoordinationException("RECOVERY_SNAPSHOT_REQUIRED");
            }
            current.append(participantStreamId(durableParticipant),
                    PredictionEventType.RECOVERY_SNAPSHOT_HELD,
                    signer.nodeId(),
                    CollaborationCodec.encodeRecovery(new RecoveryEvidence(durableParticipant, snapshotReference)),
                    signer);
        });
    }

    /**
     * Records a clean connection shutdown and releases the lane without completing its task.
     *
     * @param participant participant handle
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    void detach(ParticipantId participant) throws IOException, GeneralSecurityException {
        appendParticipantEvent(participant, PredictionEventType.PARTICIPANT_DETACHED);
    }

    /**
     * Atomically transfers a held recovery lane to a newly authenticated participant.
     *
     * @param continuation continuation payload
     * @return durable continuation result, including replay state
     * @throws IOException              stale grant, epoch, participant, or projection state
     * @throws GeneralSecurityException signing failure
     */
    ContinuationResult continueFromRecovery(LaneContinuation continuation)
            throws IOException, GeneralSecurityException {
        return continueFromRecovery(continuation, true);
    }

    /**
     * Explicitly acquires a recovery-held lane for a fresh authenticated participant.
     *
     * <p>The grant is derived and issued only inside the same append-lock critical section as the
     * continuation event. Read-only eligibility therefore never assigns a successor, while two callers racing on one
     * source epoch cannot both
     * transfer its claims.</p>
     *
     * @param continuation successor request with a deterministic single-use grant identity
     * @return durable continuation result, including replay state
     * @throws IOException              stale, conflicting, or invalid continuation
     * @throws GeneralSecurityException signing failure
     */
    ContinuationResult continueEligibleFromRecovery(LaneContinuation continuation)
            throws IOException, GeneralSecurityException {
        return continueFromRecovery(continuation, false);
    }

    /**
     * Applies either continuation policy inside one append-lock critical section.
     *
     * @param continuation proposed successor continuation
     * @param requireGrant whether the caller must present an already issued grant
     * @return durable continuation result, including replay state
     * @throws IOException              stale grant, epoch, participant, or projection state
     * @throws GeneralSecurityException signing failure
     */
    private ContinuationResult continueFromRecovery(LaneContinuation continuation, boolean requireGrant)
            throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(continuation, "continuation");
        return appends.call(current -> {
            Optional<WorkIntentId> successor = current.collaborationProjection()
                    .successorIntent(continuation.sourceIntent());
            if (successor.isPresent()) {
                return continuationResult(current,
                        continuation,
                        successor.orElseThrow(CoordinationMissingElementException::new));
            }
            if (requireGrant && !current.workGroupProjection().grantAvailable(continuation.grant())) {
                throw new CoordinationException("LANE_GRANT_REPLAYED");
            }
            validateContinuation(current, continuation, requireGrant);
            current.append(continuation.targetIntent().intentId(),
                    PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                    signer.nodeId(),
                    CollaborationCodec.encodeContinuation(continuation),
                    signer);
            return new ContinuationResult(ContinuationStatus.COMMITTED,
                    continuation.targetIntent().identity(),
                    continuation.targetOwner(),
                    true);
        });
    }

    /**
     * Derives the stream every lifecycle event of one participant is appended to.
     */
    static UUID participantStreamId(ParticipantId durableParticipant) {
        return UUID.nameUUIDFromBytes(durableParticipant.value().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Appends a participant lifecycle event under the project append lock.
     *
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private void appendParticipantEvent(ParticipantId participant, PredictionEventType type)
            throws IOException, GeneralSecurityException {
        appends.run(current -> {
            ParticipantId durableParticipant = current.collaborationProjection().participantKey(participant)
                    .orElseThrow(() -> new CoordinationException("PARTICIPANT_NOT_FOUND"));
            current.append(participantStreamId(durableParticipant),
                    type,
                    signer.nodeId(),
                    CollaborationCodec.encodeHeartbeat(durableParticipant),
                    signer);
        });
    }

}
