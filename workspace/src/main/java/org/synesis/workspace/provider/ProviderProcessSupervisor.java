package org.synesis.workspace.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Supervises explicitly supplied provider processes without introducing a
 * broker or a provider-specific transport.
 *
 * <p>The supervisor accepts an already tokenized executable command. It never
 * invokes a shell, never shares a worker worktree, and keeps process control
 * separate from collaboration authority. A provider integration may opt out
 * by not supplying a command; manual provider chats remain pull-safe.
 */
public final class ProviderProcessSupervisor implements AutoCloseable {

    private final Map<String, Registration> processes = new ConcurrentHashMap<>();
    private final Map<String, Object> laneLocks = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();

    /**
     * Creates an empty process supervisor.
     */
    public ProviderProcessSupervisor() {
    }

    private static void rejectShellWrapper(List<String> command) {
        String executable = Path.of(command.getFirst())
                .getFileName()
                .toString()
                .toLowerCase(java.util.Locale.ROOT);
        if (List.of("cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "sh", "bash", "zsh")
                .contains(executable)) {
            throw new IllegalArgumentException("PROVIDER_COMMAND_MUST_NOT_USE_SHELL_WRAPPER");
        }
    }

    /**
     * Starts one provider process for one isolated lane.
     *
     * @param request launch request
     * @return launch result
     * @throws IOException when the worktree or process cannot be started
     */
    public StartResult start(StartRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        Path worktree = request.worktree()
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(worktree)) {
            throw new IOException("PROVIDER_WORKTREE_NOT_FOUND");
        }
        rejectShellWrapper(request.command());
        Object laneLock = laneLocks.computeIfAbsent(request.laneId(), ignored -> new Object());
        synchronized (laneLock) {
            Registration prior = processes.get(request.laneId());
            if (prior != null && prior.process()
                    .isAlive()) {
                throw new IOException("PROVIDER_LANE_ALREADY_RUNNING");
            }
            Process process = new ProcessBuilder(new ArrayList<>(request.command()))
                    .directory(worktree.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            long generation = generations.incrementAndGet();
            processes.put(request.laneId(), new Registration(process, request.provider(), generation));
            return new StartResult(request.laneId(), request.provider(), process.pid(), generation, true);
        }
    }

    /**
     * Starts a provider using its declared noninteractive driver capability.
     *
     * @param integration provider integration
     * @param laneId      isolated lane identifier
     * @param worktree    isolated lane worktree
     * @param prompt      initial task prompt
     * @return launch result
     * @throws IOException when the provider is unsupported or cannot start
     */
    public StartResult start(ProviderIntegration integration, String laneId, Path worktree, String prompt)
            throws IOException {
        Objects.requireNonNull(integration, "integration");
        List<String> command = integration.autonomousCommand(worktree, prompt)
                .orElseThrow(() -> new IOException("PROVIDER_AUTONOMOUS_DRIVER_UNAVAILABLE:" + integration.id()));
        return start(new StartRequest(laneId, integration.id(), worktree, command));
    }

    /**
     * Observes a lane process without changing it.
     *
     * @param laneId   lane identifier
     * @param provider provider identifier
     * @return current process observation
     */
    public Observation observe(String laneId, String provider) {
        Objects.requireNonNull(laneId, "laneId");
        Objects.requireNonNull(provider, "provider");
        Registration registration = processes.get(laneId);
        if (registration == null) {
            return new Observation(laneId, provider, State.NOT_FOUND, -1L, null, -1L);
        }
        Process process = registration.process();
        if (process.isAlive()) {
            return new Observation(laneId, registration.provider(), State.RUNNING, process.pid(), null,
                    registration.generation());
        }
        return new Observation(laneId, registration.provider(), State.EXITED, process.pid(), process.exitValue(),
                registration.generation());
    }

    /**
     * Registers a one-shot verified process-loss callback.
     *
     * <p>The callback receives evidence that the supervised process exited. It
     * does not infer abandonment, release claims, or alter signed state; the
     * caller must route the observation through lease and recovery policy.
     *
     * @param laneId   lane identifier
     * @param provider provider identifier used in the observation
     * @param callback callback invoked after process exit
     * @throws IOException when no process is registered for the lane
     */
    public void onExit(String laneId, String provider, Consumer<Observation> callback) throws IOException {
        Objects.requireNonNull(laneId, "laneId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(callback, "callback");
        Registration registration = processes.get(laneId);
        if (registration == null) {
            throw new IOException("PROVIDER_LANE_NOT_FOUND");
        }
        registration.process()
                .onExit()
                .thenAccept(ignored -> callback.accept(new Observation(
                        laneId,
                        registration.provider(),
                        State.EXITED,
                        registration.process()
                                .pid(),
                        registration.process()
                                .exitValue(),
                        registration.generation())));
    }

    /**
     * Starts a provider continuation in a distinct isolated lane.
     *
     * <p>This method does not transfer claims or recovery authority. The
     * caller must complete the signed continuation-grant and snapshot flow
     * before invoking it; the supervisor only controls the new process.
     *
     * @param request continuation launch request
     * @return launch result for the new lane
     * @throws IOException when the new process cannot be started
     */
    public StartResult continueLane(ContinuationRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        return start(request.target());
    }

    /**
     * Requests an interrupt and waits for bounded clean shutdown.
     *
     * @param laneId  lane identifier
     * @param timeout maximum wait duration
     * @return observation after the request
     * @throws InterruptedException if the caller is interrupted
     */
    public Observation interrupt(String laneId, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(laneId, "laneId");
        Objects.requireNonNull(timeout, "timeout");
        Registration registration = processes.get(laneId);
        if (registration != null && registration.process()
                .isAlive()) {
            registration.process()
                    .destroy();
            registration.process()
                    .waitFor(Math.max(0L, timeout.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        return observe(laneId, "unknown");
    }

    /**
     * Forcefully closes a supervised process after an explicit operator or
     * supervisor decision.
     *
     * @param laneId lane identifier
     * @return observation after termination
     */
    public Observation close(String laneId) {
        Objects.requireNonNull(laneId, "laneId");
        Registration registration = processes.get(laneId);
        if (registration != null && registration.process()
                .isAlive()) {
            registration.process()
                    .destroyForcibly();
            try {
                registration.process()
                        .waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                        .interrupt();
            }
        }
        return observe(laneId, "unknown");
    }

    /**
     * Returns the currently registered lane identifiers.
     *
     * @return immutable registered lane identifiers
     */
    public java.util.Set<String> lanes() {
        return java.util.Set.copyOf(processes.keySet());
    }

    /**
     * Returns the current process generation for a lane.
     *
     * @param laneId lane identifier
     * @return generation, or {@code -1} when no lane is registered
     */
    public long generation(String laneId) {
        Registration registration = processes.get(laneId);
        return registration == null ? -1L : registration.generation();
    }

    /**
     * Stops all supervised processes without touching collaboration state.
     */
    @Override
    public void close() {
        for (String laneId : List.copyOf(processes.keySet())) {
            close(laneId);
        }
        laneLocks.clear();
    }

    /**
     * Provider process states visible to the coordinator.
     */
    public enum State {
        /**
         * Process is currently running.
         */
        RUNNING,
        /**
         * Process exited and its exit status is available.
         */
        EXITED,
        /**
         * No process is registered for the lane.
         */
        NOT_FOUND
    }

    /** Couples a supervised provider process with its generation identity. */
    private record Registration(Process process, String provider, long generation) {

    }

    /**
     * Immutable provider process launch request.
     *
     * @param laneId   isolated lane identifier
     * @param provider provider identifier
     * @param worktree isolated provider worktree
     * @param command  direct executable argv
     */
    public record StartRequest(String laneId, String provider, Path worktree, List<String> command) {

        /**
         * Validates and freezes the launch request.
         */
        public StartRequest {
            Objects.requireNonNull(laneId, "laneId");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(worktree, "worktree");
            command = List.copyOf(Objects.requireNonNull(command, "command"));
            if (laneId.isBlank() || provider.isBlank() || command.isEmpty()
                    || command.getFirst()
                    .isBlank()) {
                throw new IllegalArgumentException("provider launch request is incomplete");
            }
        }
    }

    /**
     * Immutable process launch result.
     *
     * @param laneId     lane identifier
     * @param provider   provider identifier
     * @param pid        operating-system process ID
     * @param generation monotonic lane process generation
     * @param started    whether the process started
     */
    public record StartResult(String laneId, String provider, long pid, long generation, boolean started) {

    }

    /**
     * Immutable request for starting a continuation in a new lane.
     *
     * @param sourceLaneId suspended source lane
     * @param target       new lane launch request
     */
    public record ContinuationRequest(String sourceLaneId, StartRequest target) {

        /**
         * Validates the source and target lane relationship.
         */
        public ContinuationRequest {
            Objects.requireNonNull(sourceLaneId, "sourceLaneId");
            Objects.requireNonNull(target, "target");
            if (sourceLaneId.isBlank() || sourceLaneId.equals(target.laneId())) {
                throw new IllegalArgumentException("continuation requires a distinct target lane");
            }
        }
    }

    /**
     * Immutable process observation.
     *
     * @param laneId     lane identifier
     * @param provider   provider identifier
     * @param state      observed process state
     * @param pid        operating-system process ID
     * @param exitCode   process exit code when exited
     * @param generation monotonic lane process generation
     */
    public record Observation(String laneId, String provider, State state, long pid, Integer exitCode,
                              long generation) {

    }
}
