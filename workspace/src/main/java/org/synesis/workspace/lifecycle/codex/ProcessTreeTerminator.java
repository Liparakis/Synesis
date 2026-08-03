package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Ownership-verified, bounded repeated-discovery process-tree terminator.
 *
 * <p>One descendant snapshot is never treated as complete. The terminator
 * re-enumerates observable descendants after graceful and forced phases and
 * never targets a process whose root attachment identity cannot be proven.
 * Detached or re-parented processes are reported as unobservable rather than
 * claimed clean. The default implementation is thread-safe for independent
 * calls; one invocation owns its deadline and process observations.
 *
 * @since 1.0
 */
public final class ProcessTreeTerminator {

    /** Structured hard-stop outcomes. */
    public enum Outcome {
        /** Root and descendants exited during graceful shutdown. */
        CLEAN_GRACEFUL,
        /** At least one verified process required forced termination. */
        FORCED,
        /** The verified root had already exited. */
        ROOT_ALREADY_EXITED,
        /** Observable descendants remained at the deadline. */
        DESCENDANTS_SURVIVED,
        /** The verified root remained at the deadline. */
        ROOT_SURVIVED,
        /** Recorded attachment generation did not match. */
        ATTACHMENT_GENERATION_MISMATCH,
        /** Root identity could not be proven. */
        PROCESS_OWNERSHIP_UNPROVEN,
        /** A process escaped observable ownership. */
        DETACHED_OR_REPARENTED_UNOBSERVABLE
    }

    /**
     * Exact root ownership evidence captured before termination.
     *
     * @param pid root PID
     * @param executable verified executable
     * @param commandIdentity verified command identity
     * @param startEpochMillis verified start instant
     * @param attachmentGeneration lifecycle attachment generation
     */
    public record AttachmentIdentity(long pid, String executable, String commandIdentity,
            long startEpochMillis, long attachmentGeneration) {
        /** Validates process identity evidence. */
        public AttachmentIdentity {
            if (pid <= 0 || executable == null || executable.isBlank() || commandIdentity == null
                    || commandIdentity.isBlank() || startEpochMillis <= 0 || attachmentGeneration < 0) {
                throw new IllegalArgumentException("invalid attachment identity");
            }
        }
    }

    /**
     * Structured bounded hard-stop result.
     *
     * @param outcome structured outcome
     * @param attachmentGeneration attachment generation
     * @param rootPid verified root PID
     * @param survivors observable survivors at deadline
     * @param observableDescendants last observable descendants
     * @param forced whether forced termination was requested
     * @param diagnostic bounded diagnostic
     */
    public record Result(Outcome outcome, long attachmentGeneration, long rootPid,
            List<Long> survivors, List<Long> observableDescendants, boolean forced, String diagnostic) {
        /** Validates and freezes survivor lists. */
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            survivors = List.copyOf(Objects.requireNonNull(survivors, "survivors"));
            observableDescendants = List.copyOf(Objects.requireNonNull(observableDescendants,
                    "observableDescendants"));
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    /** Process inspection seam used by deterministic fixture tests. */
    public interface Inspector {
        /**
         * Looks up the owned root process.
         *
         * @param pid root PID
         * @return observed process, or empty
         */
        Optional<ObservedProcess> process(long pid);

        /**
         * Enumerates observable live descendants.
         *
         * @param pid owned root PID
         * @return observable live descendants
         */
        List<ObservedProcess> descendants(long pid);

        /**
         * Requests graceful termination.
         *
         * @param process process to terminate
         */
        void graceful(ObservedProcess process);

        /**
         * Requests forced termination.
         *
         * @param process process to terminate forcibly
         */
        void force(ObservedProcess process);

        /**
         * Checks whether a process remains live.
         *
         * @param process process
         * @return whether it remains live
         */
        boolean alive(ObservedProcess process);
    }

    /**
     * Immutable process observation.
     *
     * @param pid observed PID
     * @param executable observed executable
     * @param commandIdentity observed command identity
     * @param startEpochMillis observed start instant
     * @param depth descendant depth below the root
     */
    public record ObservedProcess(long pid, String executable, String commandIdentity,
            long startEpochMillis, int depth) {
        /** Validates process observation. */
        public ObservedProcess {
            if (pid <= 0 || executable == null || executable.isBlank() || commandIdentity == null
                    || commandIdentity.isBlank() || startEpochMillis <= 0 || depth < 0) {
                throw new IllegalArgumentException("invalid observed process");
            }
        }
    }

    private final Inspector inspector;

    /** Creates a terminator using the JDK process-handle inspector. */
    public ProcessTreeTerminator() {
        this(new JdkInspector());
    }

    /**
     * Creates a terminator with an injected process inspector.
     *
     * @param inspector deterministic or operating-system process inspector
     */
    public ProcessTreeTerminator(Inspector inspector) {
        this.inspector = Objects.requireNonNull(inspector, "inspector");
    }

    /**
     * Terminates the exact owned attachment tree through repeated discovery.
     *
     * @param identity exact verified attachment identity
     * @param expectedGeneration generation recorded by lifecycle state
     * @param grace bounded graceful phase
     * @param deadline absolute termination deadline
     * @return structured result with all observable survivors
     */
    public Result terminate(AttachmentIdentity identity, long expectedGeneration, Duration grace,
            Instant deadline) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(grace, "grace");
        Objects.requireNonNull(deadline, "deadline");
        if (grace.isNegative()) {
            throw new IllegalArgumentException("grace must not be negative");
        }
        if (identity.attachmentGeneration() != expectedGeneration) {
            return result(Outcome.ATTACHMENT_GENERATION_MISMATCH, identity, List.of(), false,
                    "attachment_generation_mismatch");
        }
        ObservedProcess root = inspector.process(identity.pid()).orElse(null);
        if (root != null && !matches(identity, root)) {
            return result(Outcome.PROCESS_OWNERSHIP_UNPROVEN, identity, List.of(), false,
                    "process_ownership_unproven");
        }
        // If the root cannot be observed at the initial ownership check, its
        // descendants cannot be proven to belong to this attachment.  Do not
        // enumerate or target arbitrary processes by a reused PID; report the
        // root as already exited and leave any unobservable descendants to the
        // documented platform limitation.
        if (root == null) {
            return result(Outcome.ROOT_ALREADY_EXITED, identity, List.of(), false,
                    "root_already_exited");
        }
        boolean rootPresent = root != null;
        boolean forced = false;
        List<ObservedProcess> lastObservedDescendants = List.of();
        long graceDeadline = Math.min(deadline.toEpochMilli(), System.currentTimeMillis() + grace.toMillis());
        while (System.currentTimeMillis() < graceDeadline) {
            Optional<ObservedProcess> refreshed = inspector.process(identity.pid());
            if (refreshed.isPresent() && !matches(identity, refreshed.get())) {
                return result(Outcome.PROCESS_OWNERSHIP_UNPROVEN, identity, List.of(), forced,
                        "process_ownership_unproven");
            }
            rootPresent = refreshed.isPresent();
            if (rootPresent) root = refreshed.get();
            boolean rootAlive = rootPresent && inspector.alive(root);
            List<ObservedProcess> descendants = ownedDescendants(identity, root);
            lastObservedDescendants = descendants;
            descendants.forEach(inspector::graceful);
            if (rootAlive) inspector.graceful(root);
            waitUntil(descendants, root, graceDeadline);
            Optional<ObservedProcess> afterGrace = inspector.process(identity.pid());
            if (afterGrace.isPresent() && !matches(identity, afterGrace.get())) {
                return result(Outcome.PROCESS_OWNERSHIP_UNPROVEN, identity, List.of(), forced,
                        "process_ownership_unproven");
            }
            if (afterGrace.isPresent()) root = afterGrace.get();
            rootPresent = afterGrace.isPresent();
            List<ObservedProcess> afterGraceDescendants = ownedDescendants(identity, root);
            if ((!rootPresent || !inspector.alive(root)) && afterGraceDescendants.isEmpty()) {
                if (hasLiveUnobservableDescendant(lastObservedDescendants)) {
                    return result(Outcome.DETACHED_OR_REPARENTED_UNOBSERVABLE, identity,
                            lastObservedDescendants.stream().filter(inspector::alive)
                                    .map(ObservedProcess::pid).toList(),
                            lastObservedDescendants.stream().map(ObservedProcess::pid).toList(), forced,
                            "detached_or_reparented_unobservable");
                }
                return result(rootPresent ? Outcome.CLEAN_GRACEFUL : Outcome.ROOT_ALREADY_EXITED,
                        identity, List.of(), false, rootPresent ? "clean_graceful" : "root_already_exited");
            }
        }
        while (System.currentTimeMillis() < deadline.toEpochMilli()) {
            Optional<ObservedProcess> refreshed = inspector.process(identity.pid());
            if (refreshed.isPresent() && !matches(identity, refreshed.get())) {
                return result(Outcome.PROCESS_OWNERSHIP_UNPROVEN, identity, List.of(), forced,
                        "process_ownership_unproven");
            }
            rootPresent = refreshed.isPresent();
            if (rootPresent) root = refreshed.get();
            List<ObservedProcess> descendants = ownedDescendants(identity, root);
            lastObservedDescendants = descendants;
            for (ObservedProcess process : descendants) {
                if (inspector.alive(process)) {
                    inspector.force(process);
                    forced = true;
                }
            }
            if (rootPresent && inspector.alive(root)) {
                inspector.force(root);
                forced = true;
            }
            waitUntil(descendants, root, deadline.toEpochMilli());
            Optional<ObservedProcess> afterForce = inspector.process(identity.pid());
            if (afterForce.isPresent() && !matches(identity, afterForce.get())) {
                return result(Outcome.PROCESS_OWNERSHIP_UNPROVEN, identity, List.of(), forced,
                        "process_ownership_unproven");
            }
            if (afterForce.isPresent()) root = afterForce.get();
            rootPresent = afterForce.isPresent();
            List<ObservedProcess> remaining = ownedDescendants(identity, root);
            if ((!rootPresent || !inspector.alive(root)) && remaining.isEmpty()) {
                if (hasLiveUnobservableDescendant(lastObservedDescendants)) {
                    return result(Outcome.DETACHED_OR_REPARENTED_UNOBSERVABLE, identity,
                            lastObservedDescendants.stream().filter(inspector::alive)
                                    .map(ObservedProcess::pid).toList(),
                            lastObservedDescendants.stream().map(ObservedProcess::pid).toList(), forced,
                            "detached_or_reparented_unobservable");
                }
                Outcome outcome = !rootPresent && !forced ? Outcome.ROOT_ALREADY_EXITED
                        : forced ? Outcome.FORCED : Outcome.CLEAN_GRACEFUL;
                return result(outcome, identity, List.of(), forced, outcome == Outcome.ROOT_ALREADY_EXITED
                        ? "root_already_exited" : outcome == Outcome.FORCED ? "forced" : "clean_graceful");
            }
        }
        Optional<ObservedProcess> finalRoot = inspector.process(identity.pid());
        if (finalRoot.isPresent() && !matches(identity, finalRoot.get())) {
            return result(Outcome.PROCESS_OWNERSHIP_UNPROVEN, identity, List.of(), forced,
                    "process_ownership_unproven");
        }
        if (finalRoot.isPresent()) root = finalRoot.get();
        rootPresent = finalRoot.isPresent();
        List<ObservedProcess> remaining = ownedDescendants(identity, root);
        List<Long> survivors = new ArrayList<>();
        remaining.stream().filter(inspector::alive).map(ObservedProcess::pid).forEach(survivors::add);
        if (rootPresent && inspector.alive(root)) {
            survivors.add(root.pid());
        }
        if (!rootPresent && remaining.isEmpty() && hasLiveUnobservableDescendant(lastObservedDescendants)) {
            return result(Outcome.DETACHED_OR_REPARENTED_UNOBSERVABLE, identity,
                    lastObservedDescendants.stream().filter(inspector::alive)
                            .map(ObservedProcess::pid).toList(),
                    lastObservedDescendants.stream().map(ObservedProcess::pid).toList(), forced,
                    "detached_or_reparented_unobservable");
        }
        Outcome outcome = rootPresent && inspector.alive(root) ? Outcome.ROOT_SURVIVED
                : survivors.isEmpty() ? forced ? Outcome.FORCED : Outcome.CLEAN_GRACEFUL
                : Outcome.DESCENDANTS_SURVIVED;
        return result(outcome, identity, survivors,
                remaining.stream().map(ObservedProcess::pid).toList(), forced,
                outcome.name().toLowerCase(java.util.Locale.ROOT));
    }

    private List<ObservedProcess> ownedDescendants(AttachmentIdentity identity, ObservedProcess root) {
        return inspector.descendants(root.pid()).stream()
                .filter(process -> process.pid() != root.pid())
                .filter(process -> process.startEpochMillis() > 0)
                .sorted(Comparator.comparingInt(ObservedProcess::depth).reversed()
                        .thenComparingLong(ObservedProcess::pid))
                .toList();
    }

    private boolean hasLiveUnobservableDescendant(List<ObservedProcess> prior) {
        return prior.stream().anyMatch(inspector::alive);
    }

    private static boolean matches(AttachmentIdentity expected, ObservedProcess actual) {
        return expected.pid() == actual.pid()
                && expected.executable().equals(actual.executable())
                && expected.commandIdentity().equals(actual.commandIdentity())
                && expected.startEpochMillis() == actual.startEpochMillis();
    }

    private static void waitUntil(List<ObservedProcess> descendants, ObservedProcess root, long deadlineMillis) {
        long remaining = deadlineMillis - System.currentTimeMillis();
        if (remaining <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(25L, remaining));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Result result(Outcome outcome, AttachmentIdentity identity, List<Long> survivors,
            boolean forced, String diagnostic) {
        return result(outcome, identity, survivors, survivors, forced, diagnostic);
    }

    private static Result result(Outcome outcome, AttachmentIdentity identity, List<Long> survivors,
            List<Long> observableDescendants, boolean forced, String diagnostic) {
        return new Result(outcome, identity.attachmentGeneration(), identity.pid(), survivors,
                observableDescendants, forced, diagnostic);
    }

    private static final class JdkInspector implements Inspector {
        @Override
        public Optional<ObservedProcess> process(long pid) {
            return ProcessHandle.of(pid).map(handle -> observed(handle, 0));
        }

        @Override
        public List<ObservedProcess> descendants(long pid) {
            return ProcessHandle.of(pid).map(handle -> handle.descendants()
                    .map(child -> observed(child, depth(child, pid)))
                    .toList()).orElseGet(List::of);
        }

        @Override
        public void graceful(ObservedProcess process) {
            ProcessHandle.of(process.pid()).ifPresent(ProcessHandle::destroy);
        }

        @Override
        public void force(ObservedProcess process) {
            ProcessHandle.of(process.pid()).ifPresent(ProcessHandle::destroyForcibly);
        }

        @Override
        public boolean alive(ObservedProcess process) {
            return ProcessHandle.of(process.pid()).map(ProcessHandle::isAlive).orElse(false);
        }

        private static ObservedProcess observed(ProcessHandle handle, int depth) {
            var info = handle.info();
            String executable = info.command().orElse("unknown");
            String command = info.commandLine().orElse(executable);
            long started = info.startInstant().map(Instant::toEpochMilli).orElse(0L);
            return new ObservedProcess(handle.pid(), executable, command, started, depth);
        }

        private static int depth(ProcessHandle handle, long root) {
            int depth = 1;
            ProcessHandle current = handle;
            while (current.parent().isPresent() && current.parent().get().pid() != root) {
                depth++;
                current = current.parent().get();
            }
            return depth;
        }
    }
}
