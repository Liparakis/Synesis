package org.synesis.workspace.transport.control;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Dials a peer again after a session to it ended, with a delay that doubles from {@code first} up to {@code cap}
 * between attempts, for as long as the caller still wants the peer (ADR-0098: laptops sleep, and a dropped link
 * must come back without a person creating a new invitation).
 *
 * <p>One loop runs at a time. {@link #start()} while it runs asks for another loop to follow it, so a session that
 * ends while its own dial is still being handed over is not forgotten. A dial that succeeds ends the loop: the new
 * session ends in turn, and its owner calls {@link #start()} again. The caller's {@code wanted} rule is read
 * before every attempt; once it says no the loop ends and nothing dials until {@link #start()} is called again,
 * so a peer that is no longer the leader or a member is never dialed.
 *
 * <p>Thread-safe. Attempts run one at a time on a thread of their own, which a dial may block.
 */
final class Redialer implements AutoCloseable {

    /**
     * One attempt: makes and hands over a new session or throws.
     */
    interface Dial {
        void dial() throws Exception;
    }

    private final BooleanSupplier wanted;
    private final Dial dial;
    private final Duration first;
    private final Duration cap;
    private final Consumer<String> journal;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "synesis-link-redial");
        thread.setDaemon(true);
        return thread;
    });
    private boolean running;
    private boolean again;
    private boolean closed;
    private Duration delay;
    private int attempts;

    Redialer(BooleanSupplier wanted, Dial dial, Duration first, Duration cap, Consumer<String> journal) {
        this.wanted = wanted;
        this.dial = dial;
        this.first = first;
        this.cap = cap;
        this.journal = journal;
    }

    /**
     * Begins redialing, or asks for another round of it when one is running.
     */
    synchronized void start() {
        if (closed) {
            return;
        }
        if (running) {
            again = true;
            return;
        }
        running = true;
        delay = first;
        attempts = 0;
        schedule();
    }

    private void schedule() {
        scheduler.schedule(this::attempt, delay.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void attempt() {
        if (!isWanted()) {
            journal.accept("REDIAL_STOPPED reason=no longer wanted");
            finish();
            return;
        }
        attempts++;
        try {
            dial.dial();
        } catch (Exception failure) {
            retry(failure);
            return;
        }
        journal.accept("REDIAL_OK attempts=" + attempts);
        finish();
    }

    private synchronized void retry(Exception failure) {
        if (attempts == 1) {
            journal.accept("REDIAL_FAILED attempt=1 cause=" + describe(failure));
        }
        if (closed) {
            return;
        }
        Duration doubled = delay.multipliedBy(2);
        delay = doubled.compareTo(cap) > 0 ? cap : doubled;
        schedule();
    }

    private synchronized void finish() {
        running = false;
        if (again) {
            again = false;
            start();
        }
    }

    private boolean isWanted() {
        synchronized (this) {
            if (closed) {
                return false;
            }
        }
        try {
            return wanted.getAsBoolean();
        } catch (RuntimeException unreadable) {
            // The rule is read from settings; one that cannot be read right now is retried, not obeyed.
            return true;
        }
    }

    private static String describe(Exception failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message.lines().findFirst().orElse(""));
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        scheduler.shutdownNow();
    }
}
