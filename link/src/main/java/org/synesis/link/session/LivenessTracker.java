package org.synesis.link.transport;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.LivenessListener;
import org.synesis.link.session.LivenessMetrics;
import org.synesis.link.session.LivenessState;
import org.synesis.link.session.LivenessTransition;

/**
 * Small synchronized liveness state machine with one one-shot schedule.
 *
 * <p>The transport invokes {@link #validPeerActivity()} only after validating
 * the current session and heartbeat sequence. Local writes never call it.
 * Delayed callbacks derive state from elapsed monotonic time, so scheduler
 * pauses cannot leave an expired session in SUSPECT.</p>
 */
final class LivenessTracker {
    interface Sink {
        void heartbeatDue();
        void expired();
    }

    private final LivenessConfiguration configuration;
    private final MonotonicClock clock;
    private final LivenessScheduler scheduler;
    private final Sink sink;
    private final LivenessEventDispatcher dispatcher;
    private final List<LivenessListener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private LivenessState state = LivenessState.CONNECTING;
    private boolean running;
    private long lastActivity;
    private Cancellable scheduled;
    private long heartbeatSent;
    private long heartbeatReceived;
    private long heartbeatAcknowledged;
    private long duplicateOrStale;
    private long sendFailures;
    private long liveToSuspect;
    private long suspectToLive;
    private long suspectToExpired;
    private long terminalTransitions;
    private long dropped;
    private long rttNanos;

    LivenessTracker(LivenessConfiguration configuration, MonotonicClock clock,
            LivenessScheduler scheduler, Runnable heartbeat, Runnable expiry,
            Consumer<LivenessTransition> event, Function<Runnable, Boolean> dispatch) {
        this(configuration, clock, scheduler, new Sink() {
            @Override public void heartbeatDue() { heartbeat.run(); }
            @Override public void expired() { expiry.run(); }
        }, event, action -> dispatch.apply(action));
    }

    LivenessTracker(LivenessConfiguration configuration, MonotonicClock clock,
            LivenessScheduler scheduler, Sink sink, Consumer<LivenessTransition> event,
            LivenessEventDispatcher dispatcher) {
        this.configuration = configuration;
        this.clock = clock;
        this.scheduler = scheduler;
        this.sink = sink;
        this.dispatcher = dispatcher;
        if (event != null) listeners.add(event::accept);
    }

    void start() {
        LivenessTransition transition;
        synchronized (lock) {
            if (running || state != LivenessState.CONNECTING) return;
            running = true;
            lastActivity = clock.nanoTime();
            transition = transitionLocked(LivenessState.LIVE, 0);
            scheduleLocked();
        }
        emit(transition);
    }

    void validPeerActivity() {
        LivenessTransition transition = null;
        synchronized (lock) {
            if (!running || isTerminal(state)) return;
            lastActivity = clock.nanoTime();
            if (state == LivenessState.SUSPECT) transition = transitionLocked(LivenessState.LIVE, 0);
        }
        if (transition != null) emit(transition);
    }

    void stop(LivenessState terminal) {
        LivenessTransition transition;
        synchronized (lock) {
            if (isTerminal(state)) return;
            if (!isTerminal(terminal)) throw new IllegalArgumentException("stop requires terminal state");
            running = false;
            if (scheduled != null) scheduled.cancel();
            scheduled = null;
            transition = transitionLocked(terminal, 0);
        }
        emit(transition);
    }

    void addListener(LivenessListener listener) {
        if (listener == null) throw new NullPointerException("listener");
        synchronized (lock) {
            if (!isTerminal(state)) listeners.add(listener);
        }
    }

    void removeListener(LivenessListener listener) {
        listeners.remove(listener);
    }

    void recordHeartbeatSent() { synchronized (lock) { heartbeatSent++; } }
    void recordHeartbeatReceived(boolean newest) { synchronized (lock) { if (newest) heartbeatReceived++; else duplicateOrStale++; } }
    void recordAcknowledged(boolean newest) { synchronized (lock) { if (newest) heartbeatAcknowledged++; else duplicateOrStale++; } }
    void recordSendFailure() { synchronized (lock) { sendFailures++; } }
    void recordRtt(long nanos) { synchronized (lock) { if (nanos >= 0) rttNanos = nanos; } }

    LivenessState state() { synchronized (lock) { return state; } }
    boolean isRunning() { synchronized (lock) { return running; } }

    LivenessMetrics metrics() {
        synchronized (lock) {
            long age = running ? Math.max(0, clock.nanoTime() - lastActivity) : 0;
            return new LivenessMetrics(heartbeatSent, heartbeatReceived, heartbeatAcknowledged,
                    duplicateOrStale, sendFailures, Duration.ofNanos(age), Duration.ofNanos(Math.max(0, rttNanos)),
                    liveToSuspect, suspectToLive, suspectToExpired, dropped, terminalTransitions);
        }
    }

    private void tick() {
        LivenessTransition first = null;
        LivenessTransition second = null;
        boolean expire = false;
        boolean heartbeat = false;
        synchronized (lock) {
            scheduled = null;
            if (!running) return;
            long elapsed = Math.max(0, clock.nanoTime() - lastActivity);
            if (elapsed >= configuration.expiryTimeout().toNanos()) {
                if (state == LivenessState.LIVE) first = transitionLocked(LivenessState.SUSPECT, elapsed);
                if (state == LivenessState.SUSPECT) second = transitionLocked(LivenessState.EXPIRED, elapsed);
                running = false;
                expire = true;
            } else {
                if (elapsed >= configuration.suspicionTimeout().toNanos() && state == LivenessState.LIVE) {
                    first = transitionLocked(LivenessState.SUSPECT, elapsed);
                }
                if (running) {
                    scheduleLocked();
                    heartbeat = true;
                }
            }
        }
        if (first != null) emit(first);
        if (second != null) emit(second);
        if (expire) sink.expired();
        if (heartbeat) sink.heartbeatDue();
    }

    private void scheduleLocked() {
        scheduled = scheduler.schedule(this::tick, configuration.heartbeatInterval());
    }

    private LivenessTransition transitionLocked(LivenessState next, long elapsed) {
        LivenessState previous = state;
        state = next;
        if (previous == LivenessState.LIVE && next == LivenessState.SUSPECT) liveToSuspect++;
        if (previous == LivenessState.SUSPECT && next == LivenessState.LIVE) suspectToLive++;
        if (previous == LivenessState.SUSPECT && next == LivenessState.EXPIRED) suspectToExpired++;
        if (isTerminal(next)) terminalTransitions++;
        return new LivenessTransition(previous, next, Duration.ofNanos(Math.max(0, elapsed)));
    }

    private void emit(LivenessTransition transition) {
        for (LivenessListener listener : listeners) {
            boolean accepted = dispatcher.dispatch(() -> {
                try { listener.onTransition(transition); } catch (RuntimeException ignored) { }
            });
            if (!accepted) synchronized (lock) { dropped++; }
        }
    }

    private static boolean isTerminal(LivenessState value) {
        return value == LivenessState.EXPIRED || value == LivenessState.CLOSED_GRACEFULLY
                || value == LivenessState.CLOSED_BY_PEER || value == LivenessState.CLOSED_BY_PROTOCOL
                || value == LivenessState.FAILED;
    }
}
