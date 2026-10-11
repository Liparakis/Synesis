package org.synesis.coordination.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.collaboration.CollaborationProjection;
import org.synesis.coordination.domain.collaboration.WorkGroupProjection;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.identity.CommandId;
import org.synesis.coordination.domain.identity.EventSequence;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.SupervisorId;
import org.synesis.coordination.domain.identity.WorkerId;
import org.synesis.coordination.domain.integration.ValidationContextRecord;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.PredictionContract;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.prediction.PredictionProjection;
import org.synesis.coordination.domain.task.CoordinationProjection;
import org.synesis.coordination.domain.task.CoordinationTask;
import org.synesis.coordination.domain.task.CoordinationTaskView;
import org.synesis.coordination.domain.task.TaskClaim;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationMissingElementException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.CoordinationSecurityException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Coordinates signed commands, durable events, replay, and live subscribers.
 */
public final class CoordinationService {

    private static final int SUBSCRIBER_CAPACITY = 1024;

    private final PredictionEventStore store;
    private final ProjectAppends appends;
    private final NodeIdentity coordinatorIdentity;
    private final CopyOnWriteArrayList<Subscription> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Creates a service over an opened event store.
     *
     * <p>The store names the project log. Other writers append to that log through their own stores, so the service
     * decides and reads on a store replayed from disk for each call and never on the one it was built with.</p>
     *
     * @param store               event store
     * @param coordinatorIdentity identity used to sign durable coordinator events
     */
    public CoordinationService(PredictionEventStore store, NodeIdentity coordinatorIdentity) {
        this.store = CoordinationNullException.requireNonNull(store, "store");
        this.coordinatorIdentity = CoordinationNullException.requireNonNull(coordinatorIdentity,
                "coordinator identity");
        this.appends = new ProjectAppends(store);
    }

    /**
     * Finds the event a command already produced; older or non-command events are replayable but not idempotency keys.
     */
    private static Optional<PredictionEvent> appliedEvent(PredictionEventStore current, CommandId identity) {
        for (PredictionEvent event : current.events()) {
            try {
                if (CoordinationCommand.decode(event.payload()).identity().equals(identity)) {
                    return Optional.of(event);
                }
            } catch (IOException ignored) {
                // Not a command event.
            }
        }
        return Optional.empty();
    }

    /**
     * Checks the command's optional supervisor and worker identity binding.
     *
     * @param command authenticated command to evaluate
     */
    private static boolean logicalMatches(CoordinationCommand command, SupervisorId supervisor, WorkerId worker) {
        Optional<SupervisorId> actorSupervisor = command.actorSupervisor();
        if (actorSupervisor.isEmpty()) {
            return true;
        }
        if (!actorSupervisor.orElseThrow(CoordinationMissingElementException::new).equals(supervisor)) {
            return false;
        }
        return worker == null || command.actorWorker().filter(worker::equals).isPresent();
    }

    /**
     * Decodes the contract payload after validating its format and bounds.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static PredictionContract decodeContract(byte[] payload) throws IOException {
        return PredictionContract.decode(payload);
    }

    /**
     * Validates the creator identity before accepting a task declaration.
     */
    private static void authorizeTaskCreation(CoordinationCommand command)
            throws IOException, GeneralSecurityException {
        CoordinationTask task = CoordinationTask.decode(command.payload());
        if (!task.taskIdentity().equals(command.taskIdentity()) || !task.projectIdentity()
                .equals(command.projectIdentity()) || !task.creatorNode().equals(command.actorNode())
                || !logicalMatches(command, task.creatorSupervisor(), task.creatorWorker())) {
            throw new CoordinationSecurityException("ACTOR_NOT_AUTHORIZED");
        }
    }

    /**
     * Validates the owner identity bound to a task claim.
     */
    private static void authorizeTaskClaim(CoordinationCommand command) throws IOException, GeneralSecurityException {
        TaskClaim claim = TaskClaim.decode(command.payload());
        if (!claim.taskIdentity().equals(command.taskIdentity()) || !claim.ownerNode().equals(command.actorNode())
                || !logicalMatches(command, claim.ownerSupervisor(), claim.ownerWorker())) {
            throw new CoordinationSecurityException("ACTOR_NOT_AUTHORIZED");
        }
    }

    /**
     * Validates the owner identity bound to an ownership claim.
     */
    private static void authorizeOwnershipClaim(CoordinationCommand command)
            throws IOException, GeneralSecurityException {
        OwnershipClaim claim = OwnershipClaim.decode(command.payload());
        if (!claim.taskIdentity().equals(command.taskIdentity()) || !claim.ownerNode().equals(command.actorNode())
                || !logicalMatches(command, claim.ownerSupervisor(), null)) {
            throw new CoordinationSecurityException("ACTOR_NOT_AUTHORIZED");
        }
    }

    /**
     * Lists events whose application service already checked payload-level authority.
     */
    private static boolean isCoordinatorManagedEvent(PredictionEventType type) {
        return switch (type) {
            case CAPABILITY_REQUEST_CREATED, CAPABILITY_REQUEST_CONTRACT_REVISED, CAPABILITY_REQUEST_ACCEPTED,
                 CAPABILITY_REQUEST_REJECTED, CAPABILITY_REQUEST_CANCELLED, CAPABILITY_REQUEST_SUPERSEDED,
                 CAPABILITY_IMPLEMENTATION_PUBLISHED, CAPABILITY_VALIDATION_STARTED,
                 CAPABILITY_IMPLEMENTATION_VALIDATED, CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED,
                 TASK_COMPLETION_REQUESTED, TASK_SNAPSHOT_CREATED, TASK_WAITING_FOR_DEPENDENCIES,
                 INTEGRATION_ATTEMPT_STARTED, INTEGRATION_ATTEMPT_FAILED, INTEGRATION_CONFLICTED,
                 INTEGRATION_COMMIT_CREATED, CONTROL_BRANCH_ADVANCED, TASK_INTEGRATED, SESSION_FINALIZED,
                 SESSION_ABANDONED, TASK_CANCELLATION_REQUESTED, TASK_CANCELLED, WORK_INTENT_ANNOUNCED,
                 WORK_INTENT_RELEASED, COORDINATION_REQUESTED, COORDINATION_RESPONDED, PARTICIPANT_HEARTBEAT,
                 CLAIM_HANDOFF_ACCEPTED, PARTICIPANT_ABANDONED, CONTRACT_PUBLISHED, CONTRACT_DEPENDENCY_BOUND,
                 CONTRACT_SUPERSEDED, DEPENDENCY_INVALIDATED, WORK_GROUP_CREATED, LANE_GRANT_ISSUED,
                 LANE_GRANT_CONSUMED, LANE_REVOKED, PARTICIPANT_SUSPENDED, RECOVERY_SNAPSHOT_HELD, PARTICIPANT_REVOKED,
                 INBOX_ITEM_ACKNOWLEDGED, PARTICIPANT_CANCELLED, LANE_CONTINUATION_ACCEPTED, PARTICIPANT_DETACHED,
                 COMPLETION_PREPARED, INTEGRATION_BLOCKED, REPAIR_REQUIRED, REPAIR_LANE_CREATED, COMPLETION_UNWOUND,
                 REVIEW_VALIDATION_RECORDED, PROVIDER_SESSION_TERMINALIZED, WORK_GROUP_STATUS_CHANGED,
                 OPERATOR_REQUEST_RAISED, OPERATOR_REQUEST_CLOSED -> true;
            default -> false;
        };
    }

    /**
     * Authenticates and applies a command exactly once.
     *
     * <p>Duplicate detection, authorization, and the append all run on a store replayed under the project append lock,
     * so a command another writer already applied is recognized and authority is judged on the current log. The
     * service monitor stays so that live fan-out cannot interleave with a subscription's replay and registration.</p>
     *
     * @param command signed command
     * @return resulting event, including the prior result for a duplicate command
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when authentication fails
     */
    public synchronized PredictionEvent submit(CoordinationCommand command)
            throws IOException, GeneralSecurityException {
        if (!command.verify() || !command.projectIdentity().equals(store.projectIdentity())) {
            throw new CoordinationSecurityException("invalid coordination command");
        }
        return appends.call(current -> {
            Optional<PredictionEvent> prior = appliedEvent(current, command.identity());
            if (prior.isPresent()) {
                return prior.get();
            }
            authorize(current, command);
            PredictionEvent event = current.append(command.predictionId(),
                    command.type(),
                    coordinatorIdentity.nodeId(),
                    command.encoded(),
                    coordinatorIdentity);
            subscribers.forEach(subscription -> subscription.offer(event));
            return event;
        });
    }

    /**
     * Replays the durable log for a read; the lock is not needed because events become visible by atomic move.
     */
    // shortcut: every read replays and re-verifies the whole log, as the other services do; keep one replayed
    // store per durable head here if the control plane becomes slow on long logs.
    private PredictionEventStore fresh() {
        try {
            return appends.fresh();
        } catch (IOException | GeneralSecurityException failure) {
            throw new UncheckedIOException(new IOException("coordination log unreadable", failure));
        }
    }

    /**
     * Returns all events after an exclusive sequence cursor.
     *
     * @param sequence cursor
     * @return replay events
     */
    public synchronized List<PredictionEvent> replayAfter(long sequence) {
        return replayAfter(new EventSequence(sequence));
    }

    /**
     * Returns events after the supplied typed exclusive log cursor.
     *
     * @param sequence exclusive event cursor
     * @return replay events after the cursor
     */
    public synchronized List<PredictionEvent> replayAfter(EventSequence sequence) {
        long cursor = CoordinationNullException.requireNonNull(sequence, "event sequence").value();
        return fresh().events().stream().filter(event -> event.sequence() > cursor).toList();
    }

    /**
     * Opens a live subscription preloaded with durable replay after a cursor.
     *
     * @param sequence exclusive cursor
     * @return subscription
     */
    public synchronized Subscription subscribe(long sequence) {
        return subscribe(new EventSequence(sequence));
    }

    /**
     * Opens a bounded live subscription from a typed exclusive log cursor.
     *
     * @param sequence exclusive event cursor
     * @return bounded live subscription with durable replay preloaded
     */
    public synchronized Subscription subscribe(EventSequence sequence) {
        List<PredictionEvent> replay = replayAfter(sequence);
        LinkedBlockingQueue<PredictionEvent> queue = new LinkedBlockingQueue<>(SUBSCRIBER_CAPACITY);
        Subscription subscription = new Subscription(queue, subscribers);
        replay.stream().limit(SUBSCRIBER_CAPACITY).forEach(queue::offer);
        if (replay.size() > SUBSCRIBER_CAPACITY) {
            subscription.markOverflowed();
        }
        subscribers.add(subscription);
        return subscription;
    }

    /**
     * Opens a live-only bounded subscription without durable replay.
     *
     * @return live subscription
     */
    public synchronized Subscription subscribeLive() {
        Subscription subscription = new Subscription(new LinkedBlockingQueue<>(SUBSCRIBER_CAPACITY), subscribers);
        subscribers.add(subscription);
        return subscription;
    }

    /**
     * Returns the current durable sequence.
     *
     * @return sequence
     */
    public long headSequence() {
        return fresh().headSequence();
    }

    /**
     * Returns the current deterministic prediction projection.
     *
     * @return projection
     */
    public PredictionProjection projection() {
        return fresh().projection();
    }

    /**
     * Returns the durable task and ownership projection.
     *
     * @return coordination projection
     */
    public CoordinationProjection coordinationProjection() {
        return fresh().coordinationProjection();
    }

    /**
     * Returns the durable collaboration projection.
     *
     * @return collaboration projection
     */
    public CollaborationProjection collaborationProjection() {
        return fresh().collaborationProjection();
    }

    /**
     * Returns the durable work-group projection.
     *
     * @return work-group projection
     */
    public WorkGroupProjection workGroupProjection() {
        return fresh().workGroupProjection();
    }

    /**
     * Returns the current durable capability request projection.
     *
     * @return capability request projection
     */
    public CapabilityRequestProjection capabilityRequestProjection() {
        return fresh().capabilityRequestProjection();
    }

    /**
     * Returns the active validation context for a request handle, if one is open.
     *
     * @param handleValue public request handle string
     * @return active validation context when found
     */
    Optional<ValidationContextRecord> validationContext(String handleValue) {
        if (handleValue == null || handleValue.isBlank()) {
            return Optional.empty();
        }
        try {
            return validationContext(CapabilityRequestHandle.parse(handleValue));
        } catch (IllegalArgumentException invalidHandle) {
            return Optional.empty();
        }
    }

    /**
     * Returns the active validation context for a typed capability-request handle, if one is open.
     *
     * @param handle validated capability-request identity
     * @return active validation context when found
     */
    Optional<ValidationContextRecord> validationContext(CapabilityRequestHandle handle) {
        return fresh().capabilityRequestProjection()
                .findValidationContext(CoordinationNullException.requireNonNull(handle, "handle"));
    }

    /**
     * Authenticates a command according to the authority owner of its event type.
     */
    private void authorize(PredictionEventStore current, CoordinationCommand command)
            throws IOException, GeneralSecurityException {
        switch (command.type()) {
            case TASK_CREATED -> authorizeTaskCreation(command);
            case TASK_CLAIMED -> authorizeTaskClaim(command);
            case OWNERSHIP_CLAIMED -> authorizeOwnershipClaim(command);
            case TASK_RELEASED -> authorizeTaskRelease(current, command);
            case OWNERSHIP_RELEASED -> authorizeOwnershipRelease(current, command);
            default -> {
                if (!isCoordinatorManagedEvent(command.type())) {
                    authorizePredictionCommand(current, command);
                }
            }
        }
    }

    /**
     * Validates the current owner before releasing a task claim.
     */
    private void authorizeTaskRelease(PredictionEventStore current, CoordinationCommand command)
            throws IOException, GeneralSecurityException {
        TaskClaim claim = TaskClaim.decode(command.payload());
        CoordinationTaskView task = current.coordinationProjection().task(command.taskIdentity())
                .orElseThrow(() -> new CoordinationSecurityException("TASK_NOT_FOUND"));
        if (!claim.taskIdentity().equals(command.taskIdentity()) || task.ownerNode().filter(command.actorNode()::equals)
                .isEmpty()) {
            throw new CoordinationSecurityException("ACTOR_NOT_AUTHORIZED");
        }
    }

    /**
     * Validates the current owner before releasing an ownership claim.
     */
    private void authorizeOwnershipRelease(PredictionEventStore current, CoordinationCommand command)
            throws IOException, GeneralSecurityException {
        OwnershipClaim claim = OwnershipClaim.decode(command.payload());
        OwnershipClaim held = current.coordinationProjection().ownership(claim.capabilityIdentity())
                .orElseThrow(() -> new CoordinationSecurityException("OWNERSHIP_NOT_FOUND"));
        if (!claim.taskIdentity().equals(command.taskIdentity()) || !command.actorNode().equals(held.ownerNode())) {
            throw new CoordinationSecurityException("ACTOR_NOT_AUTHORIZED");
        }
    }

    /**
     * Applies requester/owner authority for the prediction-specific lifecycle.
     */
    private void authorizePredictionCommand(PredictionEventStore current, CoordinationCommand command)
            throws IOException, GeneralSecurityException {
        PredictionContract contract = contractFor(current, command);
        NodeId actor = command.actorNode();
        boolean requester = actor.equals(contract.requesterNode()) && logicalMatches(command,
                contract.requesterSupervisor(),
                contract.requesterWorker());
        boolean owner = actor.equals(contract.ownerNode()) && logicalMatches(command, contract.ownerSupervisor(), null);
        boolean allowed = switch (command.type()) {
            case PREDICTION_CREATED, PREDICTION_ROUTED, VALIDATION_STARTED, SPECULATION_RETIRED,
                 PREDICTION_INVALIDATED -> requester;
            case REQUEST_RECEIVED, ACCEPTED_EXACT, ACCEPTED_EQUIVALENT, CONTRACT_REVISED, IMPLEMENTATION_STARTED,
                 PATCH_READY, CAPABILITY_AVAILABLE -> owner;
            case REQUEST_REJECTED -> requester || owner;
            default -> false;
        };
        if (!allowed) {
            throw new CoordinationSecurityException("ACTOR_NOT_AUTHORIZED");
        }
    }

    /**
     * Loads the authoritative prediction contract from the event log.
     *
     * @param command authenticated command to evaluate
     * @throws IOException when payload validation or durable I/O fails
     */
    private PredictionContract contractFor(PredictionEventStore current, CoordinationCommand command)
            throws IOException {
        if (command.type() == PredictionEventType.PREDICTION_CREATED) {
            PredictionContract contract = decodeContract(command.payload());
            if (!contract.predictionIdentity().equals(command.predictionIdentity()) || !contract.projectIdentity()
                    .equals(command.projectIdentity())) {
                throw new CoordinationException("INVALID_PREDICTION_CONTRACT");
            }
            return contract;
        }
        for (PredictionEvent event : current.events()) {
            if (event.predictionId().equals(command.predictionIdentity().value())
                    && event.type() == PredictionEventType.PREDICTION_CREATED) {
                return decodeContract(CoordinationCommand.decode(event.payload()).payload());
            }
        }
        throw new CoordinationException("PREDICTION_NOT_FOUND");
    }

    /**
     * A closeable at-least-once event subscription.
     */
    public static final class Subscription implements AutoCloseable {

        private final BlockingQueue<PredictionEvent> queue;
        private final CopyOnWriteArrayList<Subscription> owners;
        private volatile boolean overflowed;

        /**
         * Validates and initializes this subscription.
         */
        private Subscription(BlockingQueue<PredictionEvent> queue, CopyOnWriteArrayList<Subscription> owners) {
            this.queue = queue;
            this.owners = owners;
        }

        /**
         * Updates the bounded subscription queue and its overflow state.
         *
         * @param event event to apply
         */
        private void offer(PredictionEvent event) {
            if (!queue.offer(event)) {
                overflowed = true;
            }
        }

        /**
         * Updates the bounded subscription queue and its overflow state.
         */
        private void markOverflowed() {
            overflowed = true;
        }

        /**
         * Takes the next event, waiting as needed.
         *
         * @return next event
         * @throws InterruptedException interrupted
         */
        public PredictionEvent take() throws InterruptedException {
            return queue.take();
        }

        /**
         * Polls one already-queued event without waiting.
         *
         * @return queued event, or null when empty
         */
        public PredictionEvent poll() {
            return queue.poll();
        }

        /**
         * Returns whether this subscriber missed events because its bounded queue overflowed.
         *
         * @return true when the subscriber must refresh from a durable cursor
         */
        public boolean overflowed() {
            return overflowed;
        }

        /**
         * Removes this subscription from the live fan-out.
         */
        @Override
        public void close() {
            owners.remove(this);
        }
    }
}
