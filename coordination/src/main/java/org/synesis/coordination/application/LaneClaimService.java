package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ClaimConflict;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.collaboration.WorkIntentScopeExtension;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Owns the transitions that open, widen, and explicitly release a lane's claim on resource selectors.
 *
 * <p>Kept apart from {@link WorkIntentService} because each transition is one read/validate/append sequence under the
 * project append lock, taken here at the start of the sequence exactly as before. {@link WorkIntentService} keeps the
 * public entry points.</p>
 */
final class LaneClaimService {

    private final PredictionEventStore store;
    private final ProjectAppends appends;
    private final NodeIdentity signer;

    /**
     * Validates and initializes this lane claim service.
     *
     * @param store  project event store
     * @param signer identity used to sign the event
     */
    LaneClaimService(PredictionEventStore store, NodeIdentity signer) {
        this.store = CoordinationNullException.requireNonNull(store, "store");
        this.appends = new ProjectAppends(store);
        this.signer = CoordinationNullException.requireNonNull(signer, "signer");
    }

    /** Refusal of a reviewer lane announced while its work group has no producer lane for it to review. */
    static final String REVIEWER_REQUIRES_PRODUCER = "REVIEWER_REQUIRES_PRODUCER";

    /**
     * Announces an intent and atomically acquires all requested selectors.
     *
     * @param intent intent declaration
     * @return acquisition result
     * @throws IOException              when persistence or validation fails
     * @throws GeneralSecurityException when event signing fails
     */
    ClaimResult announce(WorkIntent intent) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(intent, "intent");
        if (!store.projectId().equals(intent.projectId())) {
            throw new CoordinationArgumentException("intent project mismatch");
        }
        return appends.call(current -> {
            List<ClaimConflict> conflicts = current.collaborationProjection().conflicts(intent.selectors());
            if (!conflicts.isEmpty()) {
                return new ClaimResult(false, intent, conflicts);
            }
            WorkGroup existingGroup = current.workGroupProjection().group(intent.group()).orElse(null);
            if (existingGroup != null && existingGroup.status() != WorkGroup.Status.ACTIVE) {
                throw new CoordinationException("WORK_GROUP_NOT_ACTIVE");
            }
            if (intent.role() == WorkIntent.Role.REVIEWER && !ReviewAdmissionRules.hasProducerToReview(current, intent)) {
                throw new CoordinationException(REVIEWER_REQUIRES_PRODUCER);
            }
            if (existingGroup == null) {
                WorkGroup group = new WorkGroup(intent.group().value(),
                        intent.project().value(),
                        intent.goal(),
                        intent.acceptance(),
                        1,
                        WorkGroup.Status.ACTIVE);
                current.append(group.identity().value(),
                        PredictionEventType.WORK_GROUP_CREATED,
                        signer.nodeId(),
                        CollaborationCodec.encodeWorkGroup(group),
                        signer);
            }
            current.append(intent.identity().value(),
                    PredictionEventType.WORK_INTENT_ANNOUNCED,
                    signer.nodeId(),
                    CollaborationCodec.encodeIntent(intent),
                    signer);
            return new ClaimResult(true, intent, List.of());
        });
    }

    /**
     * Extends an active lane under its exact intent and claim-epoch preconditions.
     * Existing claims and dependencies are retained; task text changes only when supplied.
     *
     * @param intentId exact active intent
     * @param participant exact authenticated participant incarnation
     * @param expectedEpoch current claim epoch observed by the caller
     * @param requestedSelectors selectors requested by this declaration
     * @param goal replacement goal, or {@code null} to retain the current value
     * @param acceptance replacement acceptance, or {@code null} to retain the current value
     * @param requestedDependencies explicitly requested capability dependencies
     * @return acquisition result; conflicts reject the complete extension
     * @throws IOException persistence or stale-authority failure
     * @throws GeneralSecurityException event signing failure
     */
    ClaimResult extendScope(WorkIntentId intentId,
            ParticipantId participant,
            ClaimEpoch expectedEpoch,
            List<ResourceSelector> requestedSelectors,
            String goal,
            String acceptance,
            List<String> requestedDependencies) throws IOException, GeneralSecurityException {
        WorkIntentId exactIntent = CoordinationNullException.requireNonNull(intentId, "intent ID");
        ParticipantId exactParticipant = CoordinationNullException.requireNonNull(participant, "participant");
        ClaimEpoch exactEpoch = CoordinationNullException.requireNonNull(expectedEpoch, "claim epoch");
        List<ResourceSelector> selectors =
                List.copyOf(CoordinationNullException.requireNonNull(requestedSelectors, "selectors"));
        List<String> dependencies = List.copyOf(CoordinationNullException.requireNonNull(requestedDependencies,
                "dependencies"));
        return appends.call(currentStore -> {
            WorkIntent current = currentStore.collaborationProjection().intent(exactIntent)
                    .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
            if (!current.participantIdentity().equals(exactParticipant)) {
                throw new CoordinationException("INTENT_OWNER_MISMATCH");
            }
            if (currentStore.collaborationProjection().participantState(exactParticipant)
                    .filter(state -> state == Participant.State.ACTIVE)
                    .isEmpty()) {
                throw new CoordinationException("INTENT_NOT_ACTIVE");
            }
            if (current.claimEpoch().value() != exactEpoch.value()) {
                throw new CoordinationException("CLAIM_EPOCH_STALE");
            }
            List<ResourceSelector> mergedSelectors = new ArrayList<>(current.selectors());
            selectors.stream().filter(selector -> !mergedSelectors.contains(selector)).forEach(mergedSelectors::add);
            List<String> mergedDependencies = new ArrayList<>(current.knownDependencies());
            dependencies.stream().filter(dependency -> !mergedDependencies.contains(dependency))
                    .forEach(mergedDependencies::add);
            if (mergedSelectors.size() > 128) {
                throw new CoordinationException("SCOPE_EXTENSION_CLAIM_LIMIT_EXCEEDED");
            }
            if (mergedDependencies.size() > 50) {
                throw new CoordinationException("SCOPE_EXTENSION_DEPENDENCY_LIMIT_EXCEEDED");
            }
            String updatedGoal = goal == null ? current.goal() : goal;
            String updatedAcceptance = acceptance == null ? current.acceptance() : acceptance;
            if (mergedSelectors.equals(current.selectors()) && mergedDependencies.equals(current.knownDependencies())
                    && updatedGoal.equals(current.goal()) && updatedAcceptance.equals(current.acceptance())) {
                return new ClaimResult(true, current, List.of());
            }
            WorkIntent amended = current.nextVersion()
                    .withScope(updatedGoal, updatedAcceptance, mergedSelectors, mergedDependencies);
            List<ClaimConflict> conflicts = currentStore.collaborationProjection()
                    .conflicts(amended.selectors(), current.identity());
            if (!conflicts.isEmpty()) {
                return new ClaimResult(false, current, conflicts);
            }
            WorkIntentScopeExtension extension = new WorkIntentScopeExtension(amended, current.version());
            currentStore.append(current.identity().value(),
                    PredictionEventType.WORK_INTENT_SCOPE_EXTENDED,
                    signer.nodeId(),
                    extension.encode(),
                    signer);
            return new ClaimResult(true, amended, List.of());
        });
    }

    /**
     * Releases an intent after checking its typed identity and optimistic mutation precondition.
     *
     * @param intentId     exact intent identity
     * @param participant  exact participant incarnation
     * @param precondition expected immutable intent state, or {@code null}
     * @throws IOException              when the intent is missing, stale, or persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    void release(WorkIntentId intentId, ParticipantId participant, WorkIntentMutationPrecondition precondition)
            throws IOException, GeneralSecurityException {
        WorkIntentId intentIdentity = CoordinationNullException.requireNonNull(intentId, "intent ID");
        UUID intentUuid = intentIdentity.value();
        ParticipantId participantIdentity = CoordinationNullException.requireNonNull(participant, "participant");
        appends.run(current -> {
            WorkIntent intent = current.collaborationProjection().intent(intentIdentity)
                    .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
            intent.requireOwnedBy(participantIdentity);
            if (precondition != null) {
                precondition.requireMatches(intent);
            }
            current.append(intentUuid,
                    PredictionEventType.WORK_INTENT_RELEASED,
                    signer.nodeId(),
                    CollaborationCodec.encodeRelease(intentUuid),
                    signer);
        });
    }
}
