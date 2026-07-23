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

/** Coordinates signed commands, durable events, replay, and live subscribers. */
public final class CoordinationService {
    private final PredictionEventStore store;
    private final NodeIdentity coordinatorIdentity;
    private final Map<UUID, PredictionEvent> commandResults = new java.util.HashMap<>();
    private final CopyOnWriteArrayList<LinkedBlockingQueue<PredictionEvent>> subscribers = new CopyOnWriteArrayList<>();

    /** Creates a service over an opened event store.
     * @param store event store
     * @param coordinatorIdentity identity used to sign durable coordinator events
     */
    public CoordinationService(PredictionEventStore store, NodeIdentity coordinatorIdentity) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.coordinatorIdentity = java.util.Objects.requireNonNull(coordinatorIdentity, "coordinator identity");
        store.events().forEach(event -> {
            try {
                CoordinationCommand command = CoordinationCommand.decode(event.payload());
                commandResults.put(command.commandId(), event);
            } catch (IOException ignored) {
                // Older or non-command events remain replayable but are not idempotency keys.
            }
        });
    }

    /**
     * Authenticates and applies a command exactly once.
     * @param command signed command
     * @return resulting event, including the prior result for a duplicate command
     * @throws IOException when persistence fails
     * @throws GeneralSecurityException when authentication fails
     */
    public synchronized PredictionEvent submit(CoordinationCommand command)
            throws IOException, GeneralSecurityException {
        if (!command.verify() || !command.projectId().equals(store.projectId())) {
            throw new GeneralSecurityException("invalid coordination command");
        }
        PredictionEvent prior = commandResults.get(command.commandId());
        if (prior != null) return prior;
        PredictionEvent event = store.append(command.predictionId(), command.type(), coordinatorIdentity.nodeId(),
                command.encoded(), coordinatorIdentity);
        commandResults.put(command.commandId(), event);
        subscribers.forEach(queue -> queue.offer(event));
        return event;
    }

    /**
     * Returns all events after an exclusive sequence cursor.
     * @param sequence cursor
     * @return replay events
     */
    public synchronized List<PredictionEvent> replayAfter(long sequence) {
        return store.events().stream().filter(event -> event.sequence() > sequence).toList();
    }

    /** Opens a live subscription preloaded with durable replay after a cursor.
     * @param sequence exclusive cursor
     * @return subscription
     */
    public synchronized Subscription subscribe(long sequence) {
        LinkedBlockingQueue<PredictionEvent> queue = new LinkedBlockingQueue<>();
        queue.addAll(replayAfter(sequence)); subscribers.add(queue);
        return new Subscription(queue, subscribers);
    }

    /**
     * Returns the current durable sequence.
     * @return sequence
     */
    public long headSequence() { return store.headSequence(); }

    /** Returns the current deterministic prediction projection.
     * @return projection
     */
    public PredictionProjection projection() { return store.projection(); }

    /** A closeable at-least-once event subscription. */
    public static final class Subscription implements AutoCloseable {
        private final BlockingQueue<PredictionEvent> queue;
        private final CopyOnWriteArrayList<LinkedBlockingQueue<PredictionEvent>> owners;
        private Subscription(BlockingQueue<PredictionEvent> queue,
                CopyOnWriteArrayList<LinkedBlockingQueue<PredictionEvent>> owners) {
            this.queue = queue; this.owners = owners;
        }
        /**
         * Takes the next event, waiting as needed.
         * @return next event
         * @throws InterruptedException interrupted
         */
        public PredictionEvent take() throws InterruptedException { return queue.take(); }
        /** Polls one already-queued event without waiting.
         * @return queued event, or null when empty
         */
        public PredictionEvent poll() { return queue.poll(); }
        /** Removes this subscription from the live fan-out. */
        @Override public void close() { owners.remove(queue); }
    }
}
