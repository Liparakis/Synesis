package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.NoChangeCompletion;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
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
 * Owns the transitions that end a lane: no-change completion, release after integration, and the work-group
 * completion that can follow either.
 *
 * <p>Kept apart from {@link WorkIntentService} so the terminal-lane events and the group-completion reevaluation that
 * must follow them in the same append-lock critical section live in one place, next to the
 * {@link LaneCompletionRules} they consult. {@link WorkIntentService} keeps the public entry points and their
 * {@code synchronized} monitor.</p>
 */
final class LaneCompletionService {

    private final PredictionEventStore store;
    private final NodeIdentity signer;

    /**
     * Validates and initializes this lane completion service.
     *
     * @param store  project event store
     * @param signer identity used to sign the event
     */
    LaneCompletionService(PredictionEventStore store, NodeIdentity signer) {
        this.store = CoordinationNullException.requireNonNull(store, "store");
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
        if (!lock.isHeld()) {
            throw new CoordinationException("event append lock unavailable");
        }
        PredictionEventStore current = freshStore();
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
        reevaluateNoChangeWorkGroup(current, intent.group());
        return true;
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
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            NoChangeCompletion previous = current.collaborationProjection()
                    .noChangeCompletion(completion.intentIdentity()).orElse(null);
            if (previous != null) {
                if (!previous.equals(completion)) {
                    throw new CoordinationException("NO_CHANGE_COMPLETION_CONFLICT");
                }
                reevaluateNoChangeWorkGroup(current, completion.workGroupIdentity());
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
            reevaluateNoChangeWorkGroup(current, intent.group());
            return completion;
        }
    }

    /**
     * Completes the group only after active intents and all lifecycle obligations are resolved.
     *
     * @param current fresh authoritative project projection
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private void reevaluateNoChangeWorkGroup(PredictionEventStore current, WorkGroupId workGroupId)
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
                WorkGroup.Status.COMPLETED,
                group.reviewPolicy());
        current.append(group.identity().value(),
                PredictionEventType.WORK_GROUP_STATUS_CHANGED,
                signer.nodeId(),
                CollaborationCodec.encodeWorkGroup(completed),
                signer);
    }

    /**
     * Replays a fresh view of the project's durable coordination state.
     *
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private PredictionEventStore freshStore() throws IOException, GeneralSecurityException {
        return new PredictionEventStore(store.rootDirectory(), store.projectId());
    }
}
