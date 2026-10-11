package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.NoChangeCompletion;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.CommitId;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.RequestId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.SnapshotReference;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.CoordinationStateException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.link.identity.NodeIdentity;

/**
 * Application facade for work-intent claims, coordination requests, and participant recovery.
 *
 * <p>Append-log transitions acquire {@link ProjectAppendLock} around the complete
 * read/validate/append sequence. Claims therefore represent durable, authenticated ownership decisions rather than an
 * in-memory
 * reservation; conflicts fail closed and do not grant authority to either contender.</p>
 */
@SuppressWarnings("DuplicatedCode")
public final class WorkIntentService {

    /**
     * Opens the fresh stores whose projections are the source of current claim state.
     */
    private final ProjectAppends appends;
    /**
     * Owns claim announcement, scope extension, and explicit lane release.
     */
    private final LaneClaimService laneClaims;
    /**
     * Owns no-change completion, release after integration, and the work-group completion that follows them.
     */
    private final LaneCompletionService laneCompletion;
    /**
     * Owns repair-lane admission and the ownership-transfer event.
     */
    private final RepairLaneTransfer repairLanes;
    /**
     * Owns coordination-request admission, response, and review-grant transitions.
     */
    private final CoordinationRequestService coordinationRequests;
    /**
     * Owns participant lifecycle changes and recovery continuation.
     */
    private final ParticipantRecoveryService participantRecovery;

    /**
     * Creates a service bound to one project event store and signing identity.
     *
     * @param store  project event store
     * @param signer authenticated signing identity
     */
    public WorkIntentService(PredictionEventStore store, NodeIdentity signer) {
        this.appends = new ProjectAppends(store);
        NodeIdentity eventSigner = CoordinationNullException.requireNonNull(signer, "signer");
        this.laneClaims = new LaneClaimService(store, eventSigner);
        this.laneCompletion = new LaneCompletionService(store, eventSigner);
        this.repairLanes = new RepairLaneTransfer(eventSigner);
        this.coordinationRequests = new CoordinationRequestService(store, eventSigner);
        this.participantRecovery = new ParticipantRecoveryService(store, eventSigner);
    }

    /**
     * Announces an intent and atomically acquires all requested selectors.
     *
     * @param intent intent declaration
     * @return acquisition result
     * @throws IOException              when persistence or validation fails
     * @throws GeneralSecurityException when event signing fails
     */
    public ClaimResult announce(WorkIntent intent) throws IOException, GeneralSecurityException {
        return laneClaims.announce(intent, ReviewPolicy.none());
    }

    /**
     * Announces an intent, giving the work group the announcement creates the stated review policy.
     * A group that already exists keeps its own.
     *
     * @param intent         intent declaration
     * @param newGroupPolicy review policy of a work group this announcement creates
     * @return acquisition result
     * @throws IOException              when persistence or validation fails
     * @throws GeneralSecurityException when event signing fails
     */
    public ClaimResult announce(WorkIntent intent, ReviewPolicy newGroupPolicy) throws IOException, GeneralSecurityException {
        return laneClaims.announce(intent, newGroupPolicy);
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
    public ClaimResult extendScope(WorkIntentId intentId,
            ParticipantId participant,
            ClaimEpoch expectedEpoch,
            List<ResourceSelector> requestedSelectors,
            String goal,
            String acceptance,
            List<String> requestedDependencies) throws IOException, GeneralSecurityException {
        return laneClaims.extendScope(intentId,
                participant,
                expectedEpoch,
                requestedSelectors,
                goal,
                acceptance,
                requestedDependencies);
    }

    /**
     * Releases an intent owned by the signing participant.
     *
     * @param intentId    intent identifier
     * @param participant participant handle
     * @throws IOException              when the intent is missing or persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public void release(UUID intentId, String participant) throws IOException, GeneralSecurityException {
        release(new WorkIntentId(intentId), new ParticipantId(participant));
    }

    /**
     * Releases an intent after checking an optimistic mutation precondition.
     *
     * @param intentId     intent identifier
     * @param participant  participant handle
     * @param precondition expected immutable intent state, or {@code null}
     * @throws IOException              when the intent is missing, stale, or persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public void release(UUID intentId, String participant, WorkIntentMutationPrecondition precondition)
            throws IOException, GeneralSecurityException {
        release(new WorkIntentId(intentId), new ParticipantId(participant), precondition);
    }

    /**
     * Releases the intent identified by a typed identity when the typed participant owns it.
     *
     * @param intentId    exact intent identity
     * @param participant exact participant incarnation
     * @throws IOException              when the intent is missing, stale, or persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public void release(WorkIntentId intentId, ParticipantId participant)
            throws IOException, GeneralSecurityException {
        release(intentId, participant, null);
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
    public void release(WorkIntentId intentId,
            ParticipantId participant,
            WorkIntentMutationPrecondition precondition) throws IOException, GeneralSecurityException {
        laneClaims.release(intentId, participant, precondition);
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
    public boolean releaseAfterIntegration(ProjectAppendLock lock, TaskSnapshotRecord snapshot)
            throws IOException, GeneralSecurityException {
        return laneCompletion.releaseAfterIntegration(lock, snapshot);
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
    public NoChangeCompletion completeNoChange(NoChangeCompletion completion)
            throws IOException, GeneralSecurityException {
        return laneCompletion.completeNoChange(completion);
    }

    /**
     * Atomically transfers reserved selectors into a new repair lane.
     *
     * <p>The event is the sole ownership transition: the source remains the
     * owner until the signed event is durably appended, and projection replay removes the source and announces the
     * target as one transition.
     * Repeating the same operation after a lost response is idempotent.</p>
     *
     * @param sourceIntentId      published source lane
     * @param snapshotId          immutable conflicting snapshot identifier
     * @param expectedControlHead control HEAD used for materialization
     * @param targetIntent        new repair lane intent
     * @throws IOException              invalid source, stale epoch, lineage, scope, or target
     * @throws GeneralSecurityException signing failure
     */
    public void createRepairLane(UUID sourceIntentId,
            String snapshotId,
            String expectedControlHead,
            WorkIntent targetIntent) throws IOException, GeneralSecurityException {
        createRepairLane(new WorkIntentId(sourceIntentId),
                new SnapshotId(snapshotId),
                new CommitId(expectedControlHead),
                targetIntent);
    }

    /**
     * Transfers the lane using typed source, snapshot, and control-head identities.
     *
     * @param sourceIntentId      published source lane
     * @param snapshotId          immutable conflicting snapshot
     * @param expectedControlHead control commit used for materialization
     * @param targetIntent        new repair lane intent
     * @throws IOException              invalid source, stale epoch, lineage, scope, or target
     * @throws GeneralSecurityException signing failure
     */
    public void createRepairLane(WorkIntentId sourceIntentId,
            SnapshotId snapshotId,
            CommitId expectedControlHead,
            WorkIntent targetIntent) throws IOException, GeneralSecurityException {
        appends.run(current -> repairLanes.createRepairLaneLocked(current,
                sourceIntentId,
                snapshotId,
                expectedControlHead,
                targetIntent));
    }

    /**
     * Atomically transfers repair scope while the caller holds the project append lock used to serialize Git
     * integration and event
     * publication.
     *
     * @param lock                already-held project append lock
     * @param sourceIntentId      published source lane
     * @param snapshotId          immutable conflicting snapshot identifier
     * @param expectedControlHead control HEAD used for materialization
     * @param targetIntent        new repair lane intent
     * @throws IOException              invalid lock, source, epoch, lineage, scope, or target
     * @throws GeneralSecurityException signing failure
     */
    public void createRepairLane(ProjectAppendLock lock,
            UUID sourceIntentId,
            String snapshotId,
            String expectedControlHead,
            WorkIntent targetIntent) throws IOException, GeneralSecurityException {
        createRepairLane(lock,
                new WorkIntentId(sourceIntentId),
                new SnapshotId(snapshotId),
                new CommitId(expectedControlHead),
                targetIntent);
    }

    /**
     * Transfers repair scope under the held append lock using typed identities.
     *
     * @param lock                already-held project append lock
     * @param sourceIntentId      published source lane
     * @param snapshotId          immutable conflicting snapshot
     * @param expectedControlHead control commit used for materialization
     * @param targetIntent        new repair lane intent
     * @throws IOException              invalid lock, source, epoch, lineage, scope, or target
     * @throws GeneralSecurityException signing failure
     */
    public void createRepairLane(ProjectAppendLock lock,
            WorkIntentId sourceIntentId,
            SnapshotId snapshotId,
            CommitId expectedControlHead,
            WorkIntent targetIntent) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(lock, "project append lock");
        repairLanes.createRepairLaneLocked(appends.freshUnder(lock),
                sourceIntentId,
                snapshotId,
                expectedControlHead,
                targetIntent);
    }

    /**
     * Returns whether a participant owns a compatible selector.
     *
     * @param participant participant handle
     * @param selector    target selector
     * @return true when the participant owns an overlapping active claim
     */
    public boolean owns(String participant, ResourceSelector selector) {
        return owns(new ParticipantId(participant), selector);
    }

    /**
     * Returns whether this typed participant owns a claim overlapping the selector.
     *
     * @param participant exact participant identity
     * @param selector    resource selector to compare with active claims
     * @return whether this typed participant owns a claim overlapping the selector.
     */
    public boolean owns(ParticipantId participant, ResourceSelector selector) {
        ParticipantId owner = CoordinationNullException.requireNonNull(participant, "participant");
        return freshIntents().stream().filter(intent -> intent.participantIdentity().equals(owner))
                .anyMatch(intent -> intent.selectors().stream().anyMatch(selector::overlaps));
    }

    /**
     * Returns the active intents for discovery.
     *
     * @return active intent snapshot
     */
    public List<WorkIntent> activeIntents() {
        return freshIntents();
    }

    /**
     * Opens a negotiation request against the owner of a conflicting intent.
     *
     * @param requester           requester ID
     * @param conflictingIntentId conflicting intent
     * @param kind                request kind
     * @param proposal            proposal
     * @return durable request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public CoordinationRequest request(String requester,
            UUID conflictingIntentId,
            CoordinationRequest.Kind kind,
            String proposal) throws IOException, GeneralSecurityException {
        return request(new ParticipantId(requester), new WorkIntentId(conflictingIntentId), kind, proposal);
    }

    /**
     * Opens a request using the exact requester and conflicting-intent identities.
     *
     * @param requester           authenticated requesting participant
     * @param conflictingIntentId conflicting work-intent lane
     * @param kind                request kind
     * @param proposal            request proposal
     * @return durable request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public CoordinationRequest request(ParticipantId requester,
            WorkIntentId conflictingIntentId,
            CoordinationRequest.Kind kind,
            String proposal) throws IOException, GeneralSecurityException {
        return coordinationRequests.request(requester, conflictingIntentId, kind, proposal);
    }

    /**
     * Opens a negotiation request, optionally bound to one exact immutable review snapshot.
     *
     * @param requester           requester ID
     * @param conflictingIntentId conflicting intent
     * @param kind                request kind
     * @param proposal            proposal
     * @param snapshotId          exact review snapshot
     * @return durable request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public CoordinationRequest request(String requester,
            UUID conflictingIntentId,
            CoordinationRequest.Kind kind,
            String proposal,
            String snapshotId) throws IOException, GeneralSecurityException {
        return request(new ParticipantId(requester),
                new WorkIntentId(conflictingIntentId),
                kind,
                proposal,
                snapshotId == null ? null : new SnapshotId(snapshotId));
    }

    /**
     * Opens a request optionally bound to one exact typed snapshot identity.
     *
     * @param requester           authenticated requesting participant
     * @param conflictingIntentId conflicting work-intent lane
     * @param kind                request kind
     * @param proposal            request proposal
     * @param snapshotId          exact review snapshot, or {@code null}
     * @return durable request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public CoordinationRequest request(ParticipantId requester,
            WorkIntentId conflictingIntentId,
            CoordinationRequest.Kind kind,
            String proposal,
            SnapshotId snapshotId) throws IOException, GeneralSecurityException {
        return coordinationRequests.request(requester, conflictingIntentId, kind, proposal, snapshotId);
    }

    /**
     * Offers an atomic handoff to another active participant.
     *
     * @param owner    current owner
     * @param intentId intent ID
     * @param target   target participant
     * @param proposal handoff proposal
     * @return pending request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public CoordinationRequest offerHandoff(String owner, UUID intentId, String target, String proposal)
            throws IOException, GeneralSecurityException {
        return offerHandoff(new ParticipantId(owner), new WorkIntentId(intentId), new ParticipantId(target), proposal);
    }

    /**
     * Offers an atomic handoff between exact participant and intent identities.
     *
     * @param owner    current owning participant
     * @param intentId intent to hand off
     * @param target   participant receiving the handoff
     * @param proposal handoff proposal
     * @return durable pending request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public CoordinationRequest offerHandoff(ParticipantId owner,
            WorkIntentId intentId,
            ParticipantId target,
            String proposal) throws IOException, GeneralSecurityException {
        return coordinationRequests.offerHandoff(owner, intentId, target, proposal);
    }

    /**
     * Responds idempotently to a request addressed to the participant.
     *
     * @param participant target participant
     * @param requestId   request ID
     * @param status      response status
     * @param proposal    revised proposal
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public void respond(String participant, UUID requestId, CoordinationRequest.Status status, String proposal)
            throws IOException, GeneralSecurityException {
        respond(new ParticipantId(participant), new RequestId(requestId), status, proposal);
    }

    /**
     * Responds idempotently using typed participant and request identities.
     *
     * @param participant exact participant identity
     * @param requestId   coordination request identity
     * @param status      projected status
     * @param proposal    proposed resolution or updated terms
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void respond(ParticipantId participant,
            RequestId requestId,
            CoordinationRequest.Status status,
            String proposal) throws IOException, GeneralSecurityException {
        coordinationRequests.respond(participant, requestId, status, proposal);
    }

    /**
     * Acknowledges a server-issued inbox item for the exact participant.
     *
     * @param participant exact participant handle
     * @param itemId      server-issued request/inbox item ID
     * @throws IOException              persistence or authorization failure
     * @throws GeneralSecurityException signing failure
     */
    public void acknowledgeInbox(String participant, UUID itemId) throws IOException, GeneralSecurityException {
        acknowledgeInbox(new ParticipantId(participant), new RequestId(itemId));
    }

    /**
     * Acknowledges a server-issued item for one exact participant incarnation.
     *
     * @param participant exact participant identity
     * @param itemId      server-issued inbox item identity
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void acknowledgeInbox(ParticipantId participant, RequestId itemId)
            throws IOException, GeneralSecurityException {
        coordinationRequests.acknowledgeInbox(participant, itemId);
    }

    /**
     * Resolves and acknowledges a coordination inbox item atomically.
     *
     * @param participant exact participant handle
     * @param itemId      server-issued inbox item ID
     * @param status      terminal response status
     * @param proposal    response or resolution proposal
     * @throws IOException              authorization or transition failure
     * @throws GeneralSecurityException signing failure
     */
    public void resolveInbox(String participant, UUID itemId, CoordinationRequest.Status status, String proposal)
            throws IOException, GeneralSecurityException {
        resolveInbox(new ParticipantId(participant), new RequestId(itemId), status, proposal);
    }

    /**
     * Resolves and acknowledges an inbox item using typed participant and item identities.
     *
     * @param participant exact participant identity
     * @param itemId      server-issued inbox item identity
     * @param status      projected status
     * @param proposal    proposed resolution or updated terms
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void resolveInbox(ParticipantId participant,
            RequestId itemId,
            CoordinationRequest.Status status,
            String proposal) throws IOException, GeneralSecurityException {
        coordinationRequests.resolveInbox(participant, itemId, status, proposal);
    }

    /**
     * Returns durable coordination requests for discovery.
     *
     * @return requests
     */
    public List<CoordinationRequest> requests() {
        return coordinationRequests.requests();
    }

    /**
     * Appends a signed verified-activity heartbeat for an active participant.
     *
     * @param participant participant ID
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public void heartbeat(String participant) throws IOException, GeneralSecurityException {
        heartbeat(new ParticipantId(participant));
    }

    /**
     * Appends a verified-activity heartbeat for the exact typed participant identity.
     *
     * @param participant exact participant identity
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void heartbeat(ParticipantId participant) throws IOException, GeneralSecurityException {
        participantRecovery.heartbeat(participant);
    }

    /**
     * Fences a participant after verified process loss without releasing its claims.
     *
     * @param participant participant handle
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void suspend(String participant) throws IOException, GeneralSecurityException {
        suspend(new ParticipantId(participant));
    }

    /**
     * Suspends one exact participant incarnation while retaining its claims.
     *
     * @param participant exact participant identity
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void suspend(ParticipantId participant) throws IOException, GeneralSecurityException {
        participantRecovery.suspend(participant);
    }

    /**
     * Records verified recovery evidence with an immutable snapshot reference.
     *
     * @param participant       participant handle
     * @param snapshotReference immutable snapshot reference
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void holdRecovery(String participant, String snapshotReference)
            throws IOException, GeneralSecurityException {
        holdRecovery(new ParticipantId(participant), new SnapshotReference(snapshotReference));
    }

    /**
     * Holds recovery evidence for an exact participant and snapshot reference.
     *
     * @param participant       exact participant identity
     * @param snapshotReference immutable snapshot reference supporting recovery evidence
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void holdRecovery(ParticipantId participant, SnapshotReference snapshotReference)
            throws IOException, GeneralSecurityException {
        participantRecovery.holdRecovery(participant, snapshotReference);
    }

    /**
     * Returns whether any lane is open or any work group that had a lane is still active. A group
     * that was created and never received a lane holds no work: nothing can finish it, and no
     * agent has a way to close it.
     *
     * @param store current project projection
     * @return whether work remains that a lane has to finish
     */
    public static boolean hasOpenWork(PredictionEventStore store) {
        CoordinationNullException.requireNonNull(store, "store");
        return !store.collaborationProjection().activeIntents().isEmpty() || store.workGroupProjection().groups().stream()
                .anyMatch(group -> group.status() == WorkGroup.Status.ACTIVE && store.collaborationProjection().groupHasHadLane(group.identity()));
    }

    /**
     * Returns whether any lane was ever announced in the project.
     *
     * @param store current project projection
     * @return whether a work group of the project has had a lane
     */
    public static boolean hasHadWork(PredictionEventStore store) {
        CoordinationNullException.requireNonNull(store, "store");
        return store.workGroupProjection().groups().stream().anyMatch(group -> store.collaborationProjection().groupHasHadLane(group.identity()));
    }

    /**
     * Explicitly cancels a participant lane and releases its claims.
     *
     * @param participant participant handle
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void cancel(String participant) throws IOException, GeneralSecurityException {
        cancel(new ParticipantId(participant));
    }

    /**
     * Cancels one exact participant incarnation.
     *
     * @param participant exact participant identity
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void cancel(ParticipantId participant) throws IOException, GeneralSecurityException {
        laneCompletion.cancel(participant);
    }

    /**
     * Records a clean connection shutdown and releases the lane without completing its task.
     *
     * @param participant participant handle
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void detach(String participant) throws IOException, GeneralSecurityException {
        detach(new ParticipantId(participant));
    }

    /**
     * Records clean detachment of one exact participant incarnation.
     *
     * @param participant exact participant identity
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void detach(ParticipantId participant) throws IOException, GeneralSecurityException {
        participantRecovery.detach(participant);
    }

    /**
     * Atomically transfers a held recovery lane to a newly authenticated participant.
     *
     * @param continuation continuation payload
     * @return durable continuation result, including replay state
     * @throws IOException              stale grant, epoch, participant, or projection state
     * @throws GeneralSecurityException signing failure
     */
    public ContinuationResult continueFromRecovery(LaneContinuation continuation)
            throws IOException, GeneralSecurityException {
        return participantRecovery.continueFromRecovery(continuation);
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
    public ContinuationResult continueEligibleFromRecovery(LaneContinuation continuation)
            throws IOException, GeneralSecurityException {
        return participantRecovery.continueEligibleFromRecovery(continuation);
    }

    /**
     * Returns active intents from a fresh replay of the durable project event log.
     */
    private List<WorkIntent> freshIntents() {
        try {
            return appends.fresh().collaborationProjection().activeIntents();
        } catch (Exception failure) {
            throw new CoordinationStateException("COLLABORATION_STATE_UNAVAILABLE", failure);
        }
    }

}
