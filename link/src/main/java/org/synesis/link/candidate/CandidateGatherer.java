package org.synesis.link.candidate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns bounded provider workers and gathers candidates without global threads. */
public final class CandidateGatherer implements AutoCloseable {
    private final CandidateGatheringPolicy policy;
    private final ExecutorService workers;
    private final ScheduledExecutorService timer;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a gatherer with one bounded worker per configured provider slot.
     *
     * @param policy immutable gathering bounds and privacy policy
     */
    public CandidateGatherer(CandidateGatheringPolicy policy) {
        this.policy = policy;
        this.workers = Executors.newFixedThreadPool(policy.maxProviders(), runnable -> daemon("candidate-provider", runnable));
        this.timer = Executors.newSingleThreadScheduledExecutor(runnable -> daemon("candidate-deadline", runnable));
    }

    /**
     * Starts concurrent bounded gathering. Provider instances remain caller-owned.
     *
     * @param providers configured providers
     * @return cancellable operation
     * @throws IllegalStateException after this gatherer is closed
     */
    public CandidateGatheringOperation gather(Collection<CandidateProvider> providers) {
        if (closed.get()) throw new IllegalStateException("gatherer is closed");
        List<CandidateProvider> selected = List.copyOf(providers);
        Operation operation = new Operation(selected);
        operation.start();
        return operation.handle;
    }

    /** Stops worker and timer resources; active operations complete as cancelled. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        workers.shutdownNow();
        timer.shutdownNow();
    }

    private static Thread daemon(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, "synesis-link-" + name);
        thread.setDaemon(true);
        return thread;
    }

    private final class Operation implements CandidateCancellation {
        private final List<CandidateProvider> providers;
        private final CompletableFuture<CandidateGatheringResult> result = new CompletableFuture<>();
        private final List<Candidate> candidates = new ArrayList<>();
        private final List<CandidateProviderDiagnostic> diagnostics = new ArrayList<>();
        private final List<Future<?>> tasks = new ArrayList<>();
        private final AtomicBoolean done = new AtomicBoolean();
        private final AtomicInteger remaining;
        private final CandidateGatheringOperation handle;
        private ScheduledFuture<?> deadline;

        private Operation(List<CandidateProvider> providers) {
            this.providers = providers;
            this.remaining = new AtomicInteger(Math.min(providers.size(), policy.maxProviders()));
            this.handle = new CandidateGatheringOperation(result, this::cancel);
        }

        private void start() {
            if (providers.isEmpty()) { finish(false); return; }
            deadline = timer.schedule(() -> finish(true), policy.totalTimeout().toNanos(), TimeUnit.NANOSECONDS);
            int count = Math.min(providers.size(), policy.maxProviders());
            for (int index = 0; index < count; index++) {
                CandidateProvider provider = providers.get(index);
                tasks.add(workers.submit(() -> run(provider)));
            }
            for (int index = count; index < providers.size(); index++) {
                diagnostics.add(new CandidateProviderDiagnostic(providers.get(index).id(),
                        CandidateProviderFailureCategory.RESOURCE_LIMIT_EXCEEDED, 0, 0, Duration.ZERO));
            }
        }

        private void run(CandidateProvider provider) {
            long started = System.nanoTime();
            try {
                java.util.concurrent.CompletionStage<List<Candidate>> stage = provider.gather(this);
                List<Candidate> supplied = stage.toCompletableFuture().get(policy.providerTimeout().toNanos(), TimeUnit.NANOSECONDS);
                if (supplied == null || supplied.size() > policy.maxCandidatesPerProvider()) {
                    diagnostic(provider, CandidateProviderFailureCategory.INVALID_RESULT, 0, 0, started);
                } else {
                    List<Candidate> normalized = CandidateNormalizer.normalize(supplied, policy);
                    synchronized (this) {
                        if (!done.get()) {
                            int room = policy.maxTotalCandidates() - candidates.size();
                            int accepted = Math.min(room, normalized.size());
                            candidates.addAll(normalized.subList(0, accepted));
                            diagnostic(provider, accepted, normalized.size() - accepted, started);
                        }
                    }
                }
            } catch (TimeoutException exception) {
                diagnostic(provider, CandidateProviderFailureCategory.TIMEOUT, 0, 0, started);
            } catch (java.util.concurrent.CancellationException exception) {
                diagnostic(provider, CandidateProviderFailureCategory.CANCELLED, 0, 0, started);
            } catch (IllegalArgumentException exception) {
                diagnostic(provider, CandidateProviderFailureCategory.INVALID_RESULT, 0, 0, started);
            } catch (Exception exception) {
                diagnostic(provider, CandidateProviderFailureCategory.FAILED, 0, 0, started);
            } finally {
                if (remaining.decrementAndGet() == 0) finish(false);
            }
        }

        private void diagnostic(CandidateProvider provider, CandidateProviderFailureCategory category,
                int accepted, int rejected, long started) {
            synchronized (this) {
                if (!done.get()) diagnostics.add(new CandidateProviderDiagnostic(provider.id(), category,
                        accepted, rejected, Duration.ofNanos(Math.max(0, System.nanoTime() - started))));
            }
        }

        private void diagnostic(CandidateProvider provider, int accepted, int rejected, long started) {
            diagnostic(provider, accepted == 0 && rejected > 0
                    ? CandidateProviderFailureCategory.RESOURCE_LIMIT_EXCEEDED : CandidateProviderFailureCategory.SUCCESS,
                    accepted, rejected, started);
        }

        private void finish(boolean timedOut) {
            if (!done.compareAndSet(false, true)) return;
            if (deadline != null) deadline.cancel(false);
            if (timedOut) {
                synchronized (this) {
                    diagnostics.add(new CandidateProviderDiagnostic("gathering", CandidateProviderFailureCategory.TIMEOUT,
                            0, 0, policy.totalTimeout()));
                }
            }
            result.complete(new CandidateGatheringResult(candidates, diagnostics, timedOut));
        }

        private void cancel() {
            if (!done.compareAndSet(false, true)) return;
            if (deadline != null) deadline.cancel(false);
            synchronized (this) {
                diagnostics.add(new CandidateProviderDiagnostic("gathering", CandidateProviderFailureCategory.CANCELLED,
                        0, 0, Duration.ZERO));
            }
            for (Future<?> task : tasks) task.cancel(true);
            result.complete(new CandidateGatheringResult(candidates, diagnostics, false));
        }

        @Override
        public boolean isCancelled() { return done.get(); }
    }
}
