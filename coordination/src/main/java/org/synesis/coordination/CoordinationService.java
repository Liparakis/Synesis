package org.synesis.coordination;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import org.synesis.link.identity.NodeIdentity;

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
