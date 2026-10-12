package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.contract.ContractCodec;
import org.synesis.coordination.domain.collaboration.NoChangeCompletion;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.link.identity.NodeIdentity;

/**
 * Owns the transitions that end a lane: no-change completion, release after integration, cancellation, and the
 * work-group closure that can follow any of them.
 *
 * <p>Kept apart from {@link WorkIntentService} so the terminal-lane events and the group-completion reevaluation that
 * must follow them in the same append-lock critical section live in one place, next to the
 * {@link LaneCompletionRules} they consult. {@link WorkIntentService} keeps the public entry points.</p>
 */
final class LaneCompletionService {

    private final ProjectAppends appends;
    private final NodeIdentity signer;

    /**
     * Validates and initializes this lane completion service.
     *
     * @param store  project event store
     * @param signer identity used to sign the event
     */
    LaneCompletionService(PredictionEventStore store, NodeIdentity signer) {
        this.appends = new ProjectAppends(store);
        this.signer = CoordinationNullException.requireNonNull(signer, "signer");
    }

    /**
     * Releases an exactly integrated lane while the caller holds the project append lock.
     *
     * <p>The snapshot provenance is the optimistic authority proof. A stale
     * revision, different participant, different lineage, or non-integrated snapshot fails closed. The event ordering
     * is deliberately
     * integration first, then intent release, so a rejected or pending snapshot cannot terminalize its lane.</p>
     *
     * @param lock     already-held project append lock
     * @param snapshot exact snapshot whose integration succeeded
     * @return {@code true} when an active intent was released; {@code false} when the lane was already released
     * @throws IOException              stale or unauthorized snapshot/lane
     * @throws GeneralSecurityException signing failure
     */
    boolean releaseAfterIntegration(ProjectAppendLock lock, TaskSnapshotRecord snapshot)
            throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(lock, "project append lock");
        CoordinationNullException.requireNonNull(snapshot, "snapshot");
        PredictionEventStore current = appends.freshUnder(lock);
        TaskCompletionState snapshotState = current.taskCompletionProjection()
                .snapshotState(snapshot.snapshotIdentity()).orElse(TaskCompletionState.ACTIVE);
        if (snapshotState != TaskCompletionState.INTEGRATED) {
            throw new CoordinationException("INTEGRATED_SNAPSHOT_REQUIRED");
        }
        WorkIntent exactIntent = current.collaborationProjection().intent(snapshot.provenance().laneIdentity())
                .orElse(null);
        WorkIntent intent = exactIntent == null ? LaneCompletionRules.successorForSnapshot(current, snapshot)
                : exactIntent;
        if (intent == null) {
            return false;
        }
        boolean successor = exactIntent == null;
        if (intent.status() != WorkIntent.Status.ANNOUNCED || !intent.taskId().equals(snapshot.taskId())
                || !intent.workGroupId().equals(snapshot.provenance().workGroupId()) || !intent.authorityLineageId()
                .equals(snapshot.provenance().authorityLineageId()) || (successor ? intent.version()
                                                                                    <= snapshot.provenance()
                                                                                       .claimEpoch()
                : !intent.participant().equals(snapshot.provenance().participant())
                  || intent.version() != snapshot.provenance().claimEpoch())) {
            throw new CoordinationException("INTEGRATED_LANE_BINDING_MISMATCH");
        }
        current.append(intent.intentId(),
                PredictionEventType.WORK_INTENT_RELEASED,
                signer.nodeId(),
                CollaborationCodec.encodeRelease(intent.intentId()),
                signer);
        endOwnedContracts(current, intent.authorityLineageId(), true);
        closeSettledWorkGroup(current, intent.group());
        return true;
    }

    /**
     * Ends what a lane held as owner of contracts, under the lock that ends the lane (ADR-0092).
     *
     * <p>Its contracts are frozen, the change requests still waiting for it are declined, and the
     * change requests it filed are withdrawn: one event, read by the contract projection. The
     * bindings to its contracts that it can no longer serve are closed, so their consumers are
     * told the owner is gone and judge the dependency afresh. A binding or a request can arrive
     * until the moment the lane ends; one that arrived after a fast owner had asked to finish
     * waited for good for an owner that no longer existed. Done here no arrival can fall in
     * between.</p>
     *
     * @param integrated whether the lane's work was integrated. A validated binding then stands:
     *                   what its consumer checked is in the control branch. A cancelled lane
     *                   leaves nothing to stand on, and every binding to it is closed.
     */
    private void endOwnedContracts(PredictionEventStore current, UUID lineageId, boolean integrated) throws IOException, GeneralSecurityException {
        current.append(UUID.randomUUID(), PredictionEventType.CONTRACT_LANE_ENDED, signer.nodeId(), ContractCodec.encodeLaneEnded(lineageId, integrated), signer);
        for (CapabilityRequestRecord request : List.copyOf(current.capabilityRequestProjection().records().values())) {
            boolean stands = integrated && request.state() == CapabilityLifecycleState.VALIDATED;
            if (!request.state().bindsLanes() || stands || !request.authorityLineageId().equals(lineageId)) {
                continue;
            }
            CapabilityRequestPayload superseded = new CapabilityRequestPayload(request.handle(),
                    request.capability(),
                    request.requesterNodeId(),
                    request.ownerNodeId(),
                    request.authorityLineageId(),
                    request.contract(),
                    CapabilityLifecycleState.SUPERSEDED,
                    // The reason is what later lets the consumer through or does not: only a
                    // dependency whose owner's work is in the control branch is satisfied.
                    integrated ? CapabilityRequestRecord.OWNER_FINISHED_WITHOUT_ANSWER : CapabilityRequestRecord.OWNER_LANE_ENDED_WITHOUT_WORK,
                    request.contractRevision());
            current.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_SUPERSEDED, signer.nodeId(), superseded.encode(), signer);
        }
    }

    /**
     * Completes one explicitly requested no-change outcome and reevaluates its group.
     *
     * <p>This is the only coordination transition that may release an intent
     * without a snapshot. The caller supplies optimistic evidence, while this service verifies the durable intent,
     * group, epoch, and event
     * revision under the project append lock. A repeated identical completion is a read-only replay and never appends a
     * second terminal
     * event.</p>
     *
     * @param completion exact no-change completion evidence
     * @return the durable completion evidence
     * @throws IOException              stale, unauthorized, or pending lifecycle state
     * @throws GeneralSecurityException signing failure
     */
    NoChangeCompletion completeNoChange(NoChangeCompletion completion) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(completion, "completion");
        return appends.call(current -> {
            NoChangeCompletion previous = current.collaborationProjection()
                    .noChangeCompletion(completion.intentIdentity()).orElse(null);
            if (previous != null) {
                if (!previous.equals(completion)) {
                    throw new CoordinationException("NO_CHANGE_COMPLETION_CONFLICT");
                }
                closeSettledWorkGroup(current, completion.workGroupIdentity());
                return previous;
            }
            if (!current.unchangedSince(completion.expectedRevision())) {
                throw new CoordinationException("COMPLETION_REVISION_STALE");
            }
            WorkIntent intent = LaneCompletionRules.validateNoChangeIntent(current, completion);
            LaneCompletionRules.validateNoChangeGroup(current, intent, completion);
            current.append(intent.intentId(),
                    PredictionEventType.WORK_INTENT_RELEASED,
                    signer.nodeId(),
                    CollaborationCodec.encodeNoChangeCompletion(completion),
                    signer);
            // A lane that finishes without a change built nothing to a contract it published.
            // Left active, the contract named an owner no lane could reach.
            endOwnedContracts(current, intent.authorityLineageId(), false);
            closeSettledWorkGroup(current, intent.group());
            return completion;
        });
    }

    /**
     * Cancels a participant's lane and closes its work group when nothing else can add work to it.
     *
     * <p>Cancellation ends a lane for good, as a completion does, so the same closure has to follow it under the
     * same append lock. Without it a group whose last open lane was cancelled stayed active with no lane left to
     * close it.</p>
     *
     * @param participant participant handle
     * @throws IOException              unknown or revoked participant, or durable I/O failure
     * @throws GeneralSecurityException signing failure
     */
    void cancel(ParticipantId participant) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(participant, "participant");
        appends.run(current -> {
            ParticipantId durableParticipant = current.collaborationProjection().participantKey(participant)
                    .orElseThrow(() -> new CoordinationException("PARTICIPANT_NOT_FOUND"));
            List<WorkGroupId> groups = current.collaborationProjection().activeIntents().stream()
                    .filter(intent -> intent.participantIdentity().equals(durableParticipant))
                    .map(WorkIntent::group).distinct().toList();
            List<UUID> lineages = current.collaborationProjection().activeIntents().stream()
                    .filter(intent -> intent.participantIdentity().equals(durableParticipant))
                    .map(WorkIntent::authorityLineageId).distinct().toList();
            current.append(ParticipantRecoveryService.participantStreamId(durableParticipant),
                    PredictionEventType.PARTICIPANT_CANCELLED,
                    signer.nodeId(),
                    CollaborationCodec.encodeHeartbeat(durableParticipant),
                    signer);
            for (UUID lineage : lineages) {
                endOwnedContracts(current, lineage, false);
            }
            for (WorkGroupId group : groups) {
                closeSettledWorkGroup(current, group);
            }
        });
    }

    /**
     * Closes the group only after active intents and all lifecycle obligations are resolved. A group that
     * integrated a snapshot or recorded a no-change completion is completed; one whose lanes were all cancelled
     * is cancelled.
     *
     * @param current fresh authoritative project projection
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private void closeSettledWorkGroup(PredictionEventStore current, WorkGroupId workGroupId)
            throws IOException, GeneralSecurityException {
        WorkGroup group = current.workGroupProjection().group(workGroupId).orElse(null);
        if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
            return;
        }
        if (LaneCompletionRules.hasActiveGroupIntents(current, workGroupId)
                || LaneCompletionRules.hasUnresolvedGroupGrants(current, workGroupId)
                || LaneCompletionRules.hasPendingGroupRequests(current, workGroupId)
                || !LaneCompletionRules.allGroupSnapshotsIntegrated(current, workGroupId)
                || LaneCompletionRules.hasUnintegratedPreparedCompletion(current, workGroupId)) {
            return;
        }
        WorkGroup completed = new WorkGroup(group.identity().value(),
                group.projectId(),
                group.goal(),
                group.acceptance(),
                group.version() + 1L,
                LaneCompletionRules.hasFinishedWork(current, workGroupId) ? WorkGroup.Status.COMPLETED
                        : WorkGroup.Status.CANCELLED,
                group.reviewPolicy());
        current.append(group.identity().value(),
                PredictionEventType.WORK_GROUP_STATUS_CHANGED,
                signer.nodeId(),
                CollaborationCodec.encodeWorkGroup(completed),
                signer);
    }
}
