package org.synesis.link.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.LivenessState;
import org.synesis.link.session.LivenessTransition;

/** Deterministic tests for liveness timing and terminal transitions. */
final class LivenessTrackerTest {

    private static final LivenessConfiguration CONFIG = new LivenessConfiguration(
            Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(5), true);

    @Test
    void timingRelationshipsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new LivenessConfiguration(
                Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(3), true));
        assertThrows(IllegalArgumentException.class, () -> new LivenessConfiguration(
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(3), true));
        assertThrows(IllegalArgumentException.class, () -> new LivenessConfiguration(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2), true));
    }

    @Test
    void readinessStartsLiveOnceAndSchedulesOneHeartbeat() {
        ManualClock clock = new ManualClock();
        ManualScheduler scheduler = new ManualScheduler(clock);
        List<LivenessTransition> events = new ArrayList<>();
        LivenessTracker tracker = tracker(clock, scheduler, events);

        tracker.start();
        tracker.start();

        assertEquals(LivenessState.LIVE, tracker.state());
        assertEquals(1, events.size());
        assertEquals(LivenessState.LIVE, events.get(0).to());
        assertEquals(1, scheduler.pending());
    }

    @Test
    void suspectRecoversFromFreshPeerActivityBeforeExpiry() {
        ManualClock clock = new ManualClock();
        ManualScheduler scheduler = new ManualScheduler(clock);
        List<LivenessTransition> events = new ArrayList<>();
        LivenessTracker tracker = tracker(clock, scheduler, events);
        tracker.start();

        clock.advance(Duration.ofSeconds(3));
        scheduler.runDue();
        assertEquals(LivenessState.SUSPECT, tracker.state());
        tracker.validPeerActivity();

        assertEquals(LivenessState.LIVE, tracker.state());
        assertEquals(1, tracker.metrics().suspectToLiveCount());
    }

    @Test
    void delayedCallbackEmitsSuspectThenIrreversibleExpiryExactlyOnce() {
        ManualClock clock = new ManualClock();
        ManualScheduler scheduler = new ManualScheduler(clock);
        List<LivenessTransition> events = new ArrayList<>();
        int[] expiries = {0};
        LivenessTracker tracker = new LivenessTracker(CONFIG, clock, scheduler,
                () -> { }, () -> expiries[0]++,
                events::add, action -> { action.run(); return true; });
        tracker.start();

        clock.advance(Duration.ofSeconds(10));
        scheduler.runDue();
        tracker.validPeerActivity();
        scheduler.runDue();

        assertEquals(LivenessState.EXPIRED, tracker.state());
        assertEquals(1, expiries[0]);
        assertEquals(1, tracker.metrics().suspectToExpiredCount());
        assertEquals(List.of(LivenessState.LIVE, LivenessState.SUSPECT, LivenessState.EXPIRED),
                events.stream().map(LivenessTransition::to).toList());
        assertFalse(tracker.isRunning());
    }

    @Test
    void terminalStopCancelsScheduleAndLateActivityCannotRecover() {
        ManualClock clock = new ManualClock();
        ManualScheduler scheduler = new ManualScheduler(clock);
        List<LivenessTransition> events = new ArrayList<>();
        LivenessTracker tracker = tracker(clock, scheduler, events);
        tracker.start();
        tracker.stop(LivenessState.CLOSED_GRACEFULLY);
        tracker.stop(LivenessState.FAILED);
        tracker.validPeerActivity();

        assertEquals(LivenessState.CLOSED_GRACEFULLY, tracker.state());
        assertEquals(0, scheduler.pending());
        assertEquals(1, events.stream().filter(event -> event.to() == LivenessState.CLOSED_GRACEFULLY).count());
        assertTrue(tracker.metrics().terminalTransitionCount() == 1);
    }

    private static LivenessTracker tracker(ManualClock clock, ManualScheduler scheduler,
            List<LivenessTransition> events) {
        return new LivenessTracker(CONFIG, clock, scheduler, () -> { }, () -> { },
                events::add, action -> { action.run(); return true; });
    }

    private static final class ManualClock implements MonotonicClock {
        private long nanos;

        @Override
        public long nanoTime() { return nanos; }

        private void advance(Duration amount) { nanos += amount.toNanos(); }
    }

    private static final class ManualScheduler implements LivenessScheduler {
        private final ManualClock clock;
        private final List<Task> tasks = new ArrayList<>();

        private ManualScheduler(ManualClock clock) { this.clock = clock; }

        @Override
        public Cancellable schedule(Runnable action, Duration delay) {
            Task task = new Task(clock.nanoTime() + delay.toNanos(), action);
            tasks.add(task);
            return task;
        }

        private void runDue() {
            List<Task> due = tasks.stream().filter(task -> !task.cancelled && task.at <= clock.nanoTime()).toList();
            tasks.removeAll(due);
            due.forEach(task -> task.action.run());
        }

        private int pending() { return (int) tasks.stream().filter(task -> !task.cancelled).count(); }

        private static final class Task implements Cancellable {
            private final long at;
            private final Runnable action;
            private boolean cancelled;

            private Task(long at, Runnable action) { this.at = at; this.action = action; }

            @Override
            public void cancel() { cancelled = true; }
        }
    }
}
