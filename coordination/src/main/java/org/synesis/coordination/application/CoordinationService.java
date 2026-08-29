package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.task.CoordinationProjection;
import org.synesis.coordination.domain.task.CoordinationTask;
import org.synesis.coordination.domain.integration.ImplementationRevisionRecord;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.PredictionContract;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.prediction.PredictionProjection;
import org.synesis.coordination.domain.task.TaskClaim;
import org.synesis.coordination.domain.task.TaskCompletionProjection;
import org.synesis.coordination.domain.integration.ValidationContextRecord;
import org.synesis.coordination.persistence.PredictionEventStore;

/**
 * Coordinates signed commands, durable events, replay, and live subscribers.
 */
public final class CoordinationService {

    private final PredictionEventStore store;
    private final NodeIdentity coordinatorIdentity;
    private final Map<UUID, PredictionEvent> commandResults = new java.util.HashMap<>();
    private final CopyOnWriteArrayList<LinkedBlockingQueue<PredictionEvent>> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Creates a service over an opened event store.
     *
     * @param store               event store
     * @param coordinatorIdentity identity used to sign durable coordinator events
     */
    public CoordinationService(PredictionEventStore store, NodeIdentity coordinatorIdentity) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.coordinatorIdentity = java.util.Objects.requireNonNull(coordinatorIdentity, "coordinator identity");
        store.events()
                .forEach(event -> {
                    try {
                        CoordinationCommand command = CoordinationCommand.decode(event.payload());
                        commandResults.put(command.commandId(), event);
                    } catch (IOException ignored) {
                        // Older or non-command events remain replayable but are not idempotency keys.
                    }
                });
    }

    private static boolean logicalMatches(CoordinationCommand command, String supervisor, String worker) {
        if (command.actorSupervisorId() == null) {
            return true;
        }
        if (!command.actorSupervisorId()
                .equals(supervisor)) {
            return false;
        }
        return worker == null || command.actorWorkerId()
                .equals(worker);
    }

    private static PredictionContract decodeContract(byte[] payload) throws IOException {
        try {
            return PredictionContract.decode(payload);
        } catch (IOException failure) {
            throw failure;
        }
    }

    /**
     * Authenticates and applies a command exactly once.
     *
     * @param command signed command
     * @return resulting event, including the prior result for a duplicate command
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when authentication fails
     */
    public synchronized PredictionEvent submit(CoordinationCommand command)
            throws IOException, GeneralSecurityException {
        if (!command.verify() || !command.projectId()
                .equals(store.projectId())) {
            throw new GeneralSecurityException("invalid coordination command");
        }
        PredictionEvent prior = commandResults.get(command.commandId());
        if (prior != null) {
            return prior;
        }
        authorize(command);
        PredictionEvent event = store.append(command.predictionId(), command.type(), coordinatorIdentity.nodeId(),
                command.encoded(), coordinatorIdentity);
        commandResults.put(command.commandId(), event);
        subscribers.forEach(queue -> queue.offer(event));
        return event;
    }

    /**
     * Returns all events after an exclusive sequence cursor.
     *
     * @param sequence cursor
     * @return replay events
     */
    public synchronized List<PredictionEvent> replayAfter(long sequence) {
        return store.events()
                .stream()
                .filter(event -> event.sequence() > sequence)
                .toList();
    }

    /**
     * Opens a live subscription preloaded with durable replay after a cursor.
     *
     * @param sequence exclusive cursor
     * @return subscription
     */
    public synchronized Subscription subscribe(long sequence) {
        LinkedBlockingQueue<PredictionEvent> queue = new LinkedBlockingQueue<>();
        queue.addAll(replayAfter(sequence));
        subscribers.add(queue);
        return new Subscription(queue, subscribers);
    }

    /**
     * Returns the current durable sequence.
     *
     * @return sequence
     */
    public long headSequence() {
        return store.headSequence();
    }

    /**
     * Returns the current deterministic prediction projection.
     *
     * @return projection
     */
    public PredictionProjection projection() {
        return store.projection();
    }

    /**
     * Returns the durable task and ownership projection.
     *
     * @return coordination projection
     */
    public CoordinationProjection coordinationProjection() {
        return store.coordinationProjection();
    }

    /**
     * Returns the current durable capability request projection.
     *
     * @return capability request projection
     */
    public CapabilityRequestProjection capabilityRequestProjection() {
        return store.capabilityRequestProjection();
    }

    /**
     * Returns the latest implementation revision record for a request handle, if published.
     *
     * @param handleValue public request handle string
     * @return latest revision record when found
     */
    public java.util.Optional<ImplementationRevisionRecord> latestImplementationRevision(String handleValue) {
        return store.capabilityRequestProjection().findLatestImplementation(handleValue);
    }

    /**
     * Returns the active validation context for a request handle, if one is open.
     *
     * @param handleValue public request handle string
     * @return active validation context when found
     */
    public java.util.Optional<ValidationContextRecord> validationContext(String handleValue) {
        return store.capabilityRequestProjection().findValidationContext(handleValue);
    }

    /**
     * Returns the task completion and integration projection reconstructed from the event log.
     *
     * @return task completion projection
     */
    public TaskCompletionProjection taskCompletionProjection() {
        return store.taskCompletionProjection();
    }

    private void authorize(CoordinationCommand command) throws IOException, GeneralSecurityException {
        if (command.type() == PredictionEventType.TASK_CREATED) {
            CoordinationTask task = CoordinationTask.decode(command.payload());
            if (!task.taskId()
                    .equals(command.predictionId()) || !task.projectId()
                    .equals(command.projectId())
                    || !task.creatorNodeId()
                    .equals(command.actorNodeId())
                    || !logicalMatches(command, task.creatorSupervisorId(), task.creatorWorkerId())) {
                throw new GeneralSecurityException("ACTOR_NOT_AUTHORIZED");
            }
            return;
        }
        if (command.type() == PredictionEventType.TASK_CLAIMED) {
            TaskClaim claim = TaskClaim.decode(command.payload());
            if (!claim.taskId()
                    .equals(command.predictionId()) || !claim.ownerNodeId()
                    .equals(command.actorNodeId())
                    || !logicalMatches(command, claim.ownerSupervisorId(), claim.ownerWorkerId())) {
                throw new GeneralSecurityException("ACTOR_NOT_AUTHORIZED");
            }
            return;
        }
        if (command.type() == PredictionEventType.OWNERSHIP_CLAIMED) {
            OwnershipClaim claim = OwnershipClaim.decode(command.payload());
            if (!claim.taskId()
                    .equals(command.predictionId()) || !claim.ownerNodeId()
                    .equals(command.actorNodeId())
                    || !logicalMatches(command, claim.ownerSupervisorId(), null)) {
                throw new GeneralSecurityException("ACTOR_NOT_AUTHORIZED");
            }
            return;
        }
        if (command.type() == PredictionEventType.TASK_RELEASED) {
            TaskClaim claim = TaskClaim.decode(command.payload());
            CoordinationProjection.TaskView task = coordinationProjection().task(command.predictionId())
                    .orElseThrow(() -> new GeneralSecurityException("TASK_NOT_FOUND"));
            if (!claim.taskId()
                    .equals(command.predictionId()) || !command.actorNodeId()
                    .equals(task.ownerNodeId())) {
                throw new GeneralSecurityException("ACTOR_NOT_AUTHORIZED");
            }
            return;
        }
        if (command.type() == PredictionEventType.OWNERSHIP_RELEASED) {
            OwnershipClaim claim = OwnershipClaim.decode(command.payload());
            OwnershipClaim current = coordinationProjection().ownership(claim.capability())
                    .orElseThrow(() -> new GeneralSecurityException("OWNERSHIP_NOT_FOUND"));
            if (!claim.taskId()
                    .equals(command.predictionId())
                    || !command.actorNodeId()
                    .equals(current.ownerNodeId())) {
                throw new GeneralSecurityException("ACTOR_NOT_AUTHORIZED");
            }
            return;
        }
        if (command.type() == PredictionEventType.CAPABILITY_REQUEST_CREATED
                || command.type() == PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED
                || command.type() == PredictionEventType.CAPABILITY_REQUEST_ACCEPTED
                || command.type() == PredictionEventType.CAPABILITY_REQUEST_REJECTED
                || command.type() == PredictionEventType.CAPABILITY_REQUEST_CANCELLED
                || command.type() == PredictionEventType.CAPABILITY_REQUEST_SUPERSEDED
                || command.type() == PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED
                || command.type() == PredictionEventType.CAPABILITY_VALIDATION_STARTED
                || command.type() == PredictionEventType.CAPABILITY_IMPLEMENTATION_VALIDATED
                || command.type() == PredictionEventType.CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED
                || command.type() == PredictionEventType.TASK_COMPLETION_REQUESTED
                || command.type() == PredictionEventType.TASK_SNAPSHOT_CREATED
                || command.type() == PredictionEventType.TASK_WAITING_FOR_DEPENDENCIES
                || command.type() == PredictionEventType.INTEGRATION_ATTEMPT_STARTED
                || command.type() == PredictionEventType.INTEGRATION_ATTEMPT_FAILED
                || command.type() == PredictionEventType.INTEGRATION_CONFLICTED
                || command.type() == PredictionEventType.INTEGRATION_COMMIT_CREATED
                || command.type() == PredictionEventType.CONTROL_BRANCH_ADVANCED
                || command.type() == PredictionEventType.TASK_INTEGRATED
                || command.type() == PredictionEventType.SESSION_FINALIZED
                || command.type() == PredictionEventType.SESSION_ABANDONED
                || command.type() == PredictionEventType.TASK_CANCELLATION_REQUESTED
                || command.type() == PredictionEventType.TASK_CANCELLED
                || command.type() == PredictionEventType.WORK_INTENT_ANNOUNCED
                || command.type() == PredictionEventType.WORK_INTENT_RELEASED
                || command.type() == PredictionEventType.COORDINATION_REQUESTED
                || command.type() == PredictionEventType.COORDINATION_RESPONDED
                || command.type() == PredictionEventType.PARTICIPANT_HEARTBEAT
                || command.type() == PredictionEventType.CLAIM_HANDOFF_ACCEPTED
                || command.type() == PredictionEventType.PARTICIPANT_ABANDONED
                || command.type() == PredictionEventType.CONTRACT_PUBLISHED
                || command.type() == PredictionEventType.CONTRACT_DEPENDENCY_BOUND
                || command.type() == PredictionEventType.CONTRACT_SUPERSEDED
                || command.type() == PredictionEventType.DEPENDENCY_INVALIDATED
                || command.type() == PredictionEventType.WORK_GROUP_CREATED
                || command.type() == PredictionEventType.LANE_GRANT_ISSUED
                || command.type() == PredictionEventType.LANE_GRANT_CONSUMED
                || command.type() == PredictionEventType.LANE_REVOKED
                || command.type() == PredictionEventType.PARTICIPANT_SUSPENDED
                || command.type() == PredictionEventType.RECOVERY_SNAPSHOT_HELD
                || command.type() == PredictionEventType.PARTICIPANT_REVOKED
                || command.type() == PredictionEventType.INBOX_ITEM_ACKNOWLEDGED
                || command.type() == PredictionEventType.PARTICIPANT_CANCELLED
                || command.type() == PredictionEventType.LANE_CONTINUATION_ACCEPTED
                || command.type() == PredictionEventType.PARTICIPANT_DETACHED
                || command.type() == PredictionEventType.COMPLETION_PREPARED
                || command.type() == PredictionEventType.INTEGRATION_BLOCKED
                || command.type() == PredictionEventType.REPAIR_REQUIRED
                || command.type() == PredictionEventType.REPAIR_LANE_CREATED
                || command.type() == PredictionEventType.COMPLETION_UNWOUND
                || command.type() == PredictionEventType.REVIEW_VALIDATION_RECORDED
                || command.type() == PredictionEventType.PROVIDER_SESSION_TERMINALIZED
                || command.type() == PredictionEventType.WORK_GROUP_STATUS_CHANGED) {
            // Payload-level authorization is enforced in application services before signing.
            return;
        }
        PredictionContract contract = contractFor(command);
        String actor = command.actorNodeId();
        boolean requester = actor.equals(contract.requesterNodeId())
                && logicalMatches(command, contract.requesterSupervisorId(), contract.requesterWorkerId());
        boolean owner = actor.equals(contract.ownerNodeId())
                && logicalMatches(command, contract.ownerSupervisorId(), null);
        boolean allowed = switch (command.type()) {
            case PREDICTION_CREATED -> requester;
            case PREDICTION_ROUTED, VALIDATION_STARTED, SPECULATION_RETIRED, PREDICTION_INVALIDATED -> requester;
            case REQUEST_RECEIVED, ACCEPTED_EXACT, ACCEPTED_EQUIVALENT, CONTRACT_REVISED,
                 IMPLEMENTATION_STARTED, PATCH_READY, CAPABILITY_AVAILABLE -> owner;
            case REQUEST_REJECTED -> requester || owner;
            case PREDICTION_EXPIRED -> false;
            case TASK_CREATED, TASK_CLAIMED, TASK_RELEASED, OWNERSHIP_CLAIMED, OWNERSHIP_RELEASED -> false;
            case CAPABILITY_REQUEST_CREATED, CAPABILITY_REQUEST_CONTRACT_REVISED, CAPABILITY_REQUEST_ACCEPTED,
                 CAPABILITY_REQUEST_REJECTED, CAPABILITY_REQUEST_CANCELLED, CAPABILITY_REQUEST_SUPERSEDED,
                 CAPABILITY_IMPLEMENTATION_PUBLISHED, CAPABILITY_VALIDATION_STARTED,
                 CAPABILITY_IMPLEMENTATION_VALIDATED, CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED,
                 TASK_COMPLETION_REQUESTED, TASK_SNAPSHOT_CREATED, TASK_WAITING_FOR_DEPENDENCIES,
                 INTEGRATION_ATTEMPT_STARTED, INTEGRATION_ATTEMPT_FAILED, INTEGRATION_CONFLICTED,
                 INTEGRATION_COMMIT_CREATED, CONTROL_BRANCH_ADVANCED, TASK_INTEGRATED, SESSION_FINALIZED,
                 SESSION_ABANDONED, TASK_CANCELLATION_REQUESTED, TASK_CANCELLED,
                 COMPLETION_PREPARED, INTEGRATION_BLOCKED, REPAIR_REQUIRED, REPAIR_LANE_CREATED,
                 COMPLETION_UNWOUND,
                 WORK_INTENT_ANNOUNCED, WORK_INTENT_RELEASED, COORDINATION_REQUESTED,
                 COORDINATION_RESPONDED, PARTICIPANT_HEARTBEAT, CLAIM_HANDOFF_ACCEPTED,
                 PARTICIPANT_ABANDONED,
                 CONTRACT_PUBLISHED, CONTRACT_DEPENDENCY_BOUND, CONTRACT_SUPERSEDED,
                 DEPENDENCY_INVALIDATED, WORK_GROUP_CREATED, LANE_GRANT_ISSUED,
                 LANE_GRANT_CONSUMED, LANE_REVOKED, WORK_GROUP_STATUS_CHANGED,
                 PARTICIPANT_SUSPENDED, RECOVERY_SNAPSHOT_HELD, PARTICIPANT_REVOKED,
                 INBOX_ITEM_ACKNOWLEDGED, PARTICIPANT_CANCELLED, LANE_CONTINUATION_ACCEPTED,
                 PARTICIPANT_DETACHED, REVIEW_VALIDATION_RECORDED,
                 PROVIDER_SESSION_TERMINALIZED -> true;
        };
        if (!allowed) {
            throw new GeneralSecurityException("ACTOR_NOT_AUTHORIZED");
        }
    }

    private PredictionContract contractFor(CoordinationCommand command) throws IOException {
        if (command.type() == PredictionEventType.PREDICTION_CREATED) {
            PredictionContract contract = decodeContract(command.payload());
            if (!contract.predictionId()
                    .equals(command.predictionId())
                    || !contract.projectId()
                    .equals(command.projectId())) {
                throw new IOException("INVALID_PREDICTION_CONTRACT");
            }
            return contract;
        }
        for (PredictionEvent event : store.events()) {
            if (event.predictionId()
                    .equals(command.predictionId())
                    && event.type() == PredictionEventType.PREDICTION_CREATED) {
                return decodeContract(CoordinationCommand.decode(event.payload())
                        .payload());
            }
        }
        throw new IOException("PREDICTION_NOT_FOUND");
    }

    /**
     * A closeable at-least-once event subscription.
     */
    public static final class Subscription implements AutoCloseable {

        private final BlockingQueue<PredictionEvent> queue;
        private final CopyOnWriteArrayList<LinkedBlockingQueue<PredictionEvent>> owners;

        private Subscription(BlockingQueue<PredictionEvent> queue,
                CopyOnWriteArrayList<LinkedBlockingQueue<PredictionEvent>> owners) {
            this.queue = queue;
            this.owners = owners;
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
         * Removes this subscription from the live fan-out.
         */
        @Override
        public void close() {
            owners.remove(queue);
        }
    }
}
