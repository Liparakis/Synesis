package org.synesis.workspace.cleanup;

import java.util.Objects;
import java.util.Optional;

/**
 * Interface for conservative process liveness and process metadata inspection.
 *
 * @since 1.0
 */
public interface ProcessInspector {

    /**
     * Process details observed for a candidate PID.
     *
     * @param pid               process identifier
     * @param executableName    name of process executable
     * @param commandLine       command line string, if available
     * @param isLive            {@code true} if process handle is active
     */
    record ProcessDetails(long pid, String executableName, String commandLine, boolean isLive) {
        /**
         * Validates non-null invariants.
         */
        public ProcessDetails {
            Objects.requireNonNull(executableName, "executableName");
        }
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
     * @param pid                     candidate PID, or {@code null}
     * @param expectedExecutablePart  expected executable snippet (e.g. "java")
     * @param expectedCommandSnippet  expected command snippet (e.g. "SynesisMcpServer")
     * @return process evidence state
     */
    default ProcessEvidenceState evaluateEvidence(Long pid, String expectedExecutablePart, String expectedCommandSnippet) {
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
        boolean cmdMatches = expectedCommandSnippet == null || (details.commandLine() != null && details.commandLine().toLowerCase(java.util.Locale.ROOT).contains(expectedCommandSnippet.toLowerCase(java.util.Locale.ROOT)));

        if (execMatches && cmdMatches) {
            return ProcessEvidenceState.LIVE_VERIFIED;
        } else if (execMatches) {
            return ProcessEvidenceState.LIVE_UNVERIFIED;
        } else {
            return ProcessEvidenceState.PID_REUSED_OR_MISMATCHED;
        }
    }

    /**
     * Creates a default Java process inspector using ProcessHandle APIs where available.
     *
     * @return system process inspector
     */
    static ProcessInspector system() {
        return new SystemProcessInspector();
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
                return Optional.of(new ProcessDetails(pid, exec, cmd, true));
            } catch (Exception ex) {
                return Optional.empty();
            }
        }
    }
}
