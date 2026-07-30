package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderProcessSupervisor;
import org.synesis.workspace.lifecycle.reconciliation.ReconciliationService;

/**
 * Bridges provider process supervision to the exact session lease without
 * making process control an authority-transfer mechanism.
 */
public final class ProviderSupervisionService implements AutoCloseable {

    private final ProviderProcessSupervisor processSupervisor;
    private final SessionLeaseService leaseService;
    private final ScheduledExecutorService recoveryScheduler;
    private final Map<String, ScheduledFuture<?>> recoveryTasks = new ConcurrentHashMap<>();
    /** Lanes explicitly closed by the coordinator; their exit is not loss. */
    private final Set<String> cleanlyClosedLanes = ConcurrentHashMap.newKeySet();

    /** Creates a provider supervision service with local process and lease stores. */
    public ProviderSupervisionService() {
        this(new ProviderProcessSupervisor(), new SessionLeaseService());
    }

    /**
     * Creates a service with explicit dependencies.
     *
     * @param processSupervisor process supervisor
     * @param leaseService lease persistence service
     */
    public ProviderSupervisionService(ProviderProcessSupervisor processSupervisor, SessionLeaseService leaseService) {
        this.processSupervisor = Objects.requireNonNull(processSupervisor, "processSupervisor");
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService");
        this.recoveryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "synesis-provider-recovery");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Immutable request for supervised provider startup.
     * @param location project location
     * @param binding exact provider session binding
     * @param integration provider integration
     * @param worktree isolated worktree
     * @param prompt initial provider prompt
     * @param policy lease policy
     */
    public record StartRequest(
            ProjectApplicationService.ProjectLocation location,
            ProviderSessionBindingService.Binding binding,
            ProviderIntegration integration,
            Path worktree,
            String prompt,
            SessionLeasePolicy policy
    ) {
        /** Validates the startup request. */
        public StartRequest {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(integration, "integration");
            Objects.requireNonNull(worktree, "worktree");
            Objects.requireNonNull(prompt, "prompt");
            Objects.requireNonNull(policy, "policy");
        }
    }

    /** Immutable verified process-loss notification.
     * @param laneId lane identifier
     * @param provider provider identifier
     * @param pid exited process ID
     * @param exitCode process exit code
     * @param generation monotonic lane process generation
     * @param observedAtEpochMillis observation timestamp
     */
    public record ProcessExit(String laneId, String provider, long pid, Integer exitCode, long generation,
            long observedAtEpochMillis) {
    }

    /**
     * Starts a provider, records its process identity in the exact session
     * lease, and registers a policy-neutral exit callback.
     *
     * @param request startup request
     * @param onExit callback for verified process exit
     * @return process start result
     * @throws IOException if launch or lease persistence fails
     */
    public ProviderProcessSupervisor.StartResult start(StartRequest request, Consumer<ProcessExit> onExit)
            throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onExit, "onExit");
        String laneId = request.binding().sessionId();
        ScheduledFuture<?> priorRecovery = recoveryTasks.remove(laneId);
        if (priorRecovery != null) {
            priorRecovery.cancel(false);
        }
        cleanlyClosedLanes.remove(laneKey(laneId, processSupervisor.generation(laneId)));
        var started = processSupervisor.start(request.integration(), laneId, request.worktree(), request.prompt());
        ProcessHandle.Info info = ProcessHandle.of(started.pid()).map(ProcessHandle::info).orElse(null);
        String executable = info == null ? request.integration().id() : info.command().orElse(request.integration().id());
        String command = info == null ? executable : info.commandLine().orElse(executable);
        long startTime = info == null ? System.currentTimeMillis()
                : info.startInstant().map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        try {
            leaseService.createOrRenewSupervisedLease(request.location().root(), request.binding().projectId(),
                    request.binding().provider(), request.binding().sessionId(), request.binding().nodeId(),
                    request.binding().sessionId(), new SessionProcessIdentity(started.pid(), executable, command,
                            startTime, "supervised-" + started.pid()), request.policy());
        } catch (IOException | RuntimeException failure) {
            // Do not leak a provider process when its exact lease cannot be
            // persisted. The caller still receives the original failure.
            processSupervisor.close(laneId);
            throw failure;
        }
        try {
            processSupervisor.onExit(laneId, request.integration().id(), observation -> {
                if (cleanlyClosedLanes.contains(laneKey(laneId, observation.generation()))) {
                    return;
                }
                onExit.accept(new ProcessExit(laneId, observation.provider(), observation.pid(), observation.exitCode(),
                        observation.generation(), System.currentTimeMillis()));
            });
        } catch (IOException | RuntimeException failure) {
            processSupervisor.close(laneId);
            leaseService.markClosedCleanly(request.location().root(), request.binding().sessionId());
            throw failure;
        }
        return started;
    }

    /**
     * Starts a provider and schedules automatic reconciliation after verified
     * process loss and the configured grace period.
     *
     * <p>The scheduled operation only invokes the existing durable
     * reconciliation plan. It does not infer abandonment, bypass an operator
     * decision, or directly release claims.
     *
     * @param request startup request
     * @param onRecovery callback receiving the reconciliation result
     * @return process start result
     * @throws IOException when launch or lease persistence fails
     */
    public ProviderProcessSupervisor.StartResult startWithAutomaticRecovery(
            StartRequest request, Consumer<ReconciliationService.ReconciliationExecutionSummary> onRecovery) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onRecovery, "onRecovery");
        return start(request, exit -> {
            ScheduledFuture<?> scheduled = recoveryScheduler.schedule(() -> {
                if (processSupervisor.generation(exit.laneId()) != exit.generation()) {
                    return;
                }
                try {
                    ReconciliationService reconciliation = new ReconciliationService();
                    var plan = reconciliation.preparePlan(request.location().root());
                    onRecovery.accept(reconciliation.executePlan(request.location().root(), plan.planId()));
                } catch (Exception failure) {
                    // Reconciliation is durable and remains available to the next
                    // operator/provider invocation; a callback cannot change that state.
                } finally {
                    recoveryTasks.remove(exit.laneId());
                }
            }, request.policy().abandonmentGracePeriod().toMillis(), TimeUnit.MILLISECONDS);
            recoveryTasks.put(exit.laneId(), scheduled);
        });
    }

    /**
     * Launches a provider in a continuation lane after the caller has
     * completed the signed grant, snapshot restoration, and target-binding
     * checks. Process supervision deliberately does not perform those
     * authority checks itself.
     *
     * @param sourceLaneId suspended source lane identifier
     * @param request target lane startup request
     * @param onExit process-loss callback
     * @return target process start result
     * @throws IOException when the target cannot be started or leased
     */
    public ProviderProcessSupervisor.StartResult continueLane(String sourceLaneId, StartRequest request,
            Consumer<ProcessExit> onExit) throws IOException {
        Objects.requireNonNull(sourceLaneId, "sourceLaneId");
        Objects.requireNonNull(request, "request");
        new ProviderProcessSupervisor.ContinuationRequest(sourceLaneId,
                new ProviderProcessSupervisor.StartRequest(request.binding().sessionId(),
                        request.integration().id(), request.worktree(),
                        request.integration().autonomousCommand(request.worktree(), request.prompt())
                                .orElseThrow(() -> new IOException(
                                        "PROVIDER_AUTONOMOUS_DRIVER_UNAVAILABLE:" + request.integration().id()))));
        return start(request, onExit);
    }

    /**
     * Closes a supervised provider and marks its exact lease cleanly closed.
     *
     * @param location project location
     * @param laneId exact supervised lane identifier
     * @param connectionInstanceId exact connection identifier
     * @return process observation
     */
    public ProviderProcessSupervisor.Observation close(ProjectApplicationService.ProjectLocation location,
            String laneId, String connectionInstanceId) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(laneId, "laneId");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        long generation = processSupervisor.generation(laneId);
        ScheduledFuture<?> recovery = recoveryTasks.remove(laneId);
        if (recovery != null) {
            recovery.cancel(false);
        }
        cleanlyClosedLanes.add(laneKey(laneId, generation));
        ProviderProcessSupervisor.Observation observation = processSupervisor.close(laneId);
        leaseService.markClosedCleanly(location.root(), connectionInstanceId, observation.pid());
        return observation;
    }

    /** Closes all supervised processes without changing unrelated project state. */
    @Override
    public void close() {
        recoveryScheduler.shutdownNow();
        recoveryTasks.values().forEach(task -> task.cancel(false));
        recoveryTasks.clear();
        processSupervisor.lanes().forEach(lane -> cleanlyClosedLanes.add(laneKey(lane, processSupervisor.generation(lane))));
        processSupervisor.close();
    }

    private static String laneKey(String laneId, long generation) {
        return laneId + "#" + generation;
    }
}
