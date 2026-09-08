package org.synesis.workspace.transport.control;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.synesis.link.onboarding.OnboardingEvent;
import org.synesis.link.onboarding.OnboardingEventType;

/**
 * Bounded in-process event fan-out for safe onboarding lifecycle facts.
 *
 * <p>Secret-bearing share-link values are intentionally discarded before an
 * event enters a subscriber queue. A slow subscriber is marked for refresh
 * instead of causing the publisher to block or retaining unbounded state.
 */
public final class ControlPlaneEventHub {

    private static final int QUEUE_CAPACITY = 256;
    private final AtomicLong sequence = new AtomicLong();
    private final CopyOnWriteArrayList<Subscription> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Creates an empty bounded event hub.
     */
    public ControlPlaneEventHub() {
    }

    /**
     * Publishes one onboarding fact after redacting secret-bearing values.
     *
     * @param event onboarding event
     */
    public void publish(OnboardingEvent event) {
        String value = safeValue(event);
        Event safe = new Event(sequence.incrementAndGet(), event.type().name(), value);
        subscribers.forEach(subscription -> subscription.offer(safe));
    }

    /**
     * Opens a bounded live subscription.
     *
     * @return event subscription
     */
    public Subscription subscribe() {
        Subscription subscription = new Subscription(new ArrayBlockingQueue<>(QUEUE_CAPACITY), subscribers);
        subscribers.add(subscription);
        return subscription;
    }

    private static String safeValue(OnboardingEvent event) {
        OnboardingEventType type = event.type();
        if (type == OnboardingEventType.SHARE_LINK || type == OnboardingEventType.ANSWER_LINK) {
            return "";
        }
        return switch (type) {
            case CANDIDATES_GATHERED, PATH_SELECTED, WORK_RESULT, NODE_ID, PEER_IDENTITY_VERIFIED, LIVENESS ->
                    event.value();
            default -> "";
        };
    }

    /**
     * Immutable safe event delivered to the control plane.
     *
     * @param sequence local event sequence
     * @param type event type
     * @param value redacted bounded value
     */
    public record Event(long sequence, String type, String value) {
    }

    /**
     * Closeable bounded event subscription.
     */
    public static final class Subscription implements AutoCloseable {

        private final ArrayBlockingQueue<Event> queue;
        private final CopyOnWriteArrayList<Subscription> owners;
        private volatile boolean overflowed;

        private Subscription(ArrayBlockingQueue<Event> queue, CopyOnWriteArrayList<Subscription> owners) {
            this.queue = queue;
            this.owners = owners;
        }

        private void offer(Event event) {
            if (!queue.offer(event)) {
                overflowed = true;
            }
        }

        /**
         * Polls one already-queued event.
         *
         * @return event, or {@code null} when no event is queued
         */
        public Event poll() {
            return queue.poll();
        }

        /**
         * Returns whether the subscriber must refresh its snapshot.
         *
         * @return true when an event was dropped due to backpressure
         */
        public boolean overflowed() {
            return overflowed;
        }

        /**
         * Removes this subscription from the fan-out.
         */
        @Override
        public void close() {
            owners.remove(this);
        }
    }
}
