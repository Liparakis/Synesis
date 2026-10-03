package org.synesis.workspace.infrastructure.process;

import java.util.Objects;
import java.util.Optional;
import java.time.Instant;

/**
 * Interface for conservative process liveness and process metadata inspection.
 *
 * @since 1.0
 */
public interface ProcessInspector {

    /**
     * Creates a default Java process inspector using ProcessHandle APIs where available.
     *
     * @return system process inspector
     */
    static ProcessInspector system() {
        return new SystemProcessInspector();
    }

    /**
     * Captures the executable, command line, and start time of a just-started process.
     *
     * @param pid                started process ID
     * @param fallbackExecutable executable identity when process metadata is unavailable
     * @return process metadata with a current-time fallback for unavailable start-time evidence
     */
    static ProcessDetails startedProcessDetails(long pid, String fallbackExecutable) {
        Objects.requireNonNull(fallbackExecutable, "fallbackExecutable");
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        ProcessHandle.Info info = handle == null ? null : handle.info();
        String executable = info == null ? fallbackExecutable : info.command().orElse(fallbackExecutable);
        String commandLine = info == null ? executable : info.commandLine().orElse(executable);
        long startTime = info == null ? System.currentTimeMillis() : info.startInstant().map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        return new ProcessDetails(pid, executable, commandLine, handle != null && handle.isAlive(), startTime);
    }

    /**
     * Inspects a candidate process PID.
     *
     * @param pid candidate process PID
     * @return process details if observed, or empty if process is not observed
     */
    Optional<ProcessDetails> inspectProcess(long pid);

    /**
     * Evaluates process evidence state conservatively.
     *
     * @param pid                    candidate PID, or {@code null}
     * @param expectedExecutablePart expected executable snippet (e.g. "java")
     * @param expectedCommandSnippet expected command snippet (e.g. "SynesisMcpServer")
     * @return process evidence state
     */
    default ProcessEvidenceState evaluateEvidence(Long pid, String expectedExecutablePart, String expectedCommandSnippet) {
        return evaluateEvidence(pid, expectedExecutablePart, expectedCommandSnippet, -1L);
    }

    /**
     * Evaluates process evidence and, when available, fences PID reuse with the expected process
     * start timestamp.
     *
     * <p>The three-argument overload remains useful for older lifecycle records that do not carry
     * start-time evidence. A live process that exposes a start timestamp must match the expected
     * value here; otherwise a reused PID is not evidence that the original process is alive.</p>
     *
     * @param pid                      candidate process PID
     * @param expectedExecutablePart   expected executable identity fragment
     * @param expectedCommandSnippet   expected command-line fragment
     * @param expectedProcessStartTime expected process start epoch milliseconds
     * @return process evidence state
     */
    default ProcessEvidenceState evaluateEvidence(Long pid, String expectedExecutablePart, String expectedCommandSnippet, long expectedProcessStartTime) {
        if (pid == null || pid <= 0) {
            return ProcessEvidenceState.NOT_OBSERVED;
        }
        Optional<ProcessDetails> detailsOpt;
        try {
            detailsOpt = inspectProcess(pid);
        } catch (Exception ex) {
            return ProcessEvidenceState.PROCESS_EVIDENCE_UNAVAILABLE;
        }
        if (detailsOpt.isEmpty()) {
            return ProcessEvidenceState.NOT_OBSERVED;
        }
        ProcessDetails details = detailsOpt.get();
        if (!details.isLive()) {
            return ProcessEvidenceState.NOT_OBSERVED;
        }
        boolean execMatches = expectedExecutablePart == null || details.executableName().toLowerCase(java.util.Locale.ROOT).contains(expectedExecutablePart.toLowerCase(java.util.Locale.ROOT));
        boolean cmdMatches =
                expectedCommandSnippet == null || (details.commandLine() != null && details.commandLine().toLowerCase(java.util.Locale.ROOT).contains(expectedCommandSnippet.toLowerCase(java.util.Locale.ROOT)));

        if (!execMatches) {
            return ProcessEvidenceState.PID_REUSED_OR_MISMATCHED;
        }
        if (!cmdMatches) {
            return ProcessEvidenceState.LIVE_UNVERIFIED;
        }
        if (expectedProcessStartTime >= 0 && details.processStartTime() >= 0 && expectedProcessStartTime != details.processStartTime()) {
            return ProcessEvidenceState.PID_REUSED_OR_MISMATCHED;
        }
        return ProcessEvidenceState.LIVE_VERIFIED;
    }

    /**
     * Process details observed for a candidate PID.
     *
     * @param pid              process identifier
     * @param executableName   name of process executable
     * @param commandLine      command line string, if available
     * @param isLive           {@code true} if process handle is active
     * @param processStartTime process start epoch milliseconds, or {@code -1} when unavailable
     */
    record ProcessDetails(long pid, String executableName, String commandLine, boolean isLive, long processStartTime) {

        /**
         * Creates process details without start-time evidence for compatibility with older callers.
         *
         * @param pid            observed process ID
         * @param executableName executable name or path
         * @param commandLine    observed command line
         * @param isLive         whether the process is live
         */
        public ProcessDetails(long pid, String executableName, String commandLine, boolean isLive) {
            this(pid, executableName, commandLine, isLive, -1L);
        }

        /**
         * Validates non-null invariants.
         */
        public ProcessDetails {
            Objects.requireNonNull(executableName, "executableName");
        }
    }

    /**
     * System process inspector using standard JDK ProcessHandle.
     */
    final class SystemProcessInspector implements ProcessInspector {

        /**
         * Creates a default system process inspector.
         */
        public SystemProcessInspector() {
        }

        @Override
        public Optional<ProcessDetails> inspectProcess(long pid) {
            try {
                Optional<ProcessHandle> phOpt = ProcessHandle.of(pid);
                if (phOpt.isEmpty()) {
                    return Optional.empty();
                }
                ProcessHandle ph = phOpt.get();
                if (!ph.isAlive()) {
                    return Optional.empty();
                }
                ProcessHandle.Info info = ph.info();
                String exec = info.command().orElse("java");
                String cmd = info.commandLine().orElse("");
                long start = info.startInstant().map(java.time.Instant::toEpochMilli).orElse(-1L);
                return Optional.of(new ProcessDetails(pid, exec, cmd, true, start));
            } catch (Exception ex) {
                return Optional.empty();
            }
        }
    }
}
