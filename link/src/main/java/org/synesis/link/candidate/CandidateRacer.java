package org.synesis.link.candidate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;

/** Bounded staggered candidate race with atomic authenticated winner selection. */
public final class CandidateRacer implements AutoCloseable {
    private final ConnectionPolicy policy;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "synesis-link-candidate-race");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Operation, Boolean> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a racer with bounded attempt scheduling.
     *
     * @param policy immutable race limits and deadlines
     */
    public CandidateRacer(ConnectionPolicy policy) { this.policy = policy; }

    /**
     * Starts one ranked race. The attempt factory must perform the existing full
     * authenticated/control-ready handshake before completing successfully.
     *
     * @param pairs ranked candidate pairs
     * @param expectedRemoteNodeId expected authenticated remote identity
     * @param factory attempt factory
     * @return cancellable race operation
     */
    public Operation race(List<CandidatePair> pairs, String expectedRemoteNodeId,
            Function<CandidatePair, ConnectionAttempt> factory) {
        if (closed.get()) throw new IllegalStateException("racer is closed");
        if (pairs == null || expectedRemoteNodeId == null || expectedRemoteNodeId.isBlank() || factory == null) {
            throw new NullPointerException("pairs, expected remote ID, and factory");
        }
        List<CandidatePair> bounded = List.copyOf(pairs.subList(0, Math.min(policy.maxCandidatePairs(), pairs.size())));
        Operation operation = new Operation(bounded, expectedRemoteNodeId, factory);
        active.put(operation, Boolean.TRUE);
        operation.start();
        return operation;
    }

    /** Cancels all races and releases the scheduler. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        active.keySet().forEach(Operation::cancel);
        scheduler.shutdownNow();
    }

    /** Handle for one race; completion is selected exactly once. */
    public final class Operation implements CandidateCancellation {
        private final List<CandidatePair> pairs;
        private final String expectedRemoteNodeId;
        private final Function<CandidatePair, ConnectionAttempt> factory;
        private final CompletableFuture<DirectConnectionResult> completion = new CompletableFuture<>();
        private final Map<String, ConnectionAttempt> attempts = new ConcurrentHashMap<>();
        private final List<ConnectionAttemptDiagnostic> diagnostics = new ArrayList<>();
        private final AtomicBoolean done = new AtomicBoolean();
        private int next;
        private int started;
        private int running;
        private ScheduledFuture<?> deadline;

        private Operation(List<CandidatePair> pairs, String expectedRemoteNodeId,
                Function<CandidatePair, ConnectionAttempt> factory) {
            this.pairs = pairs;
            this.expectedRemoteNodeId = expectedRemoteNodeId;
            this.factory = factory;
        }

        private void start() {
            if (pairs.isEmpty()) { finish(null, ConnectionFailureCategory.NO_COMPATIBLE_CANDIDATE); return; }
            deadline = scheduler.schedule(() -> finish(null, ConnectionFailureCategory.CONNECTION_TIMEOUT),
                    policy.globalRaceTimeout().toNanos(), TimeUnit.NANOSECONDS);
            scheduler.execute(this::launch);
        }

        /**
         * Returns one-winner completion.
         *
         * @return race completion stage
         */
        public java.util.concurrent.CompletionStage<DirectConnectionResult> completion() { return completion; }

        /**
         * Cancels pending attempts.
         *
         * @return true only for the call that cancelled this race
         */
        public boolean cancel() {
            if (!done.compareAndSet(false, true)) return false;
            cancelAttempts();
            complete(new DirectConnectionResult(null, ConnectionFailureCategory.CANCELLED, snapshotDiagnostics()));
            active.remove(this);
            return true;
        }

        @Override public boolean isCancelled() { return done.get(); }

        private void launch() {
            synchronized (this) {
                if (done.get() || running >= policy.maxConcurrentAttempts() || started >= policy.maxAttempts()) return;
                if (next >= pairs.size()) {
                    if (running == 0) finish(null, ConnectionFailureCategory.DIRECT_CONNECTIVITY_UNAVAILABLE);
                    return;
                }
                CandidatePair pair = pairs.get(next++);
                started++;
                running++;
                ConnectionAttempt attempt;
                try { attempt = factory.apply(pair); }
                catch (RuntimeException exception) {
                    running--;
                    addDiagnostic(pair, ConnectionFailureCategory.TRANSPORT_FAILURE);
                    scheduler.execute(this::launch);
                    return;
                }
                if (attempt == null) {
                    running--;
                    addDiagnostic(pair, ConnectionFailureCategory.TRANSPORT_FAILURE);
                    scheduler.execute(this::launch);
                    return;
                }
                attempts.put(pair.identifier(), attempt);
                try {
                    java.util.concurrent.CompletionStage<PeerSession> result = attempt.connect(this);
                    scheduler.schedule(() -> timeout(pair, attempt), policy.perAttemptTimeout().toNanos(), TimeUnit.NANOSECONDS);
                    result.whenComplete((session, failure) -> completed(pair, attempt, session, failure));
                } catch (RuntimeException exception) {
                    completed(pair, attempt, null, exception);
                }
                if (running < policy.maxConcurrentAttempts() && started < policy.maxAttempts()
                        && next < pairs.size()) scheduler.schedule(this::launch,
                        policy.staggerDelay().toNanos(), TimeUnit.NANOSECONDS);
            }
        }

        private void timeout(CandidatePair pair, ConnectionAttempt attempt) {
            synchronized (this) {
                if (done.get() || !attempts.containsKey(pair.identifier())) return;
            }
            completed(pair, attempt, null, new TimeoutException());
        }

        private void completed(CandidatePair pair, ConnectionAttempt attempt, PeerSession session, Throwable failure) {
            synchronized (this) {
                if (done.get() || attempts.remove(pair.identifier()) == null) {
                    if (session != null) closeLate(session);
                    return;
                }
                running--;
                if (failure == null && session != null && session.isUsable()
                        && expectedRemoteNodeId.equals(session.remoteNodeId())) {
                    done.set(true);
                    if (deadline != null) deadline.cancel(false);
                    cancelOtherAttempts(attempt);
                    complete(new DirectConnectionResult(session, null, snapshotDiagnostics()));
                    active.remove(this);
                    return;
                }
                if (failure == null && session != null) closeLate(session);
                addDiagnostic(pair, failure == null ? ConnectionFailureCategory.NOT_CONTROL_READY : category(failure));
                if (running == 0 && (next >= pairs.size() || started >= policy.maxAttempts())) {
                    finish(null, ConnectionFailureCategory.DIRECT_CONNECTIVITY_UNAVAILABLE);
                } else scheduler.schedule(this::launch, policy.staggerDelay().toNanos(), TimeUnit.NANOSECONDS);
            }
        }

        private void finish(PeerSession session, ConnectionFailureCategory category) {
            if (!done.compareAndSet(false, true)) return;
            if (deadline != null) deadline.cancel(false);
            cancelAttempts();
            complete(new DirectConnectionResult(session, category, snapshotDiagnostics()));
            active.remove(this);
        }

        private void cancelOtherAttempts(ConnectionAttempt winner) {
            for (ConnectionAttempt attempt : attempts.values()) if (attempt != winner) cancel(attempt);
            attempts.clear();
        }

        private void cancelAttempts() { attempts.values().forEach(this::cancel); attempts.clear(); }
        private void cancel(ConnectionAttempt attempt) { try { attempt.cancel(); } catch (RuntimeException ignored) { } }
        private void closeLate(PeerSession session) {
            try { session.closeGracefully(SessionCloseReason.LOCAL_REQUEST); } catch (RuntimeException ignored) { }
        }

        private void addDiagnostic(CandidatePair pair, ConnectionFailureCategory category) {
            if (diagnostics.size() < policy.maxDiagnostics()) {
                diagnostics.add(new ConnectionAttemptDiagnostic(pair.identifier(), category, "attempt", Duration.ZERO));
            }
        }

        private List<ConnectionAttemptDiagnostic> snapshotDiagnostics() { return List.copyOf(diagnostics); }
        private void complete(DirectConnectionResult value) { completion.complete(value); }
        private static ConnectionFailureCategory category(Throwable failure) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            if (cause instanceof TimeoutException) return ConnectionFailureCategory.CONNECTION_TIMEOUT;
            if (cause instanceof java.util.concurrent.CancellationException) return ConnectionFailureCategory.CANCELLED;
            return ConnectionFailureCategory.TRANSPORT_FAILURE;
        }
    }
}
