package org.synesis.workspace.application.project.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded structured evidence from one process execution.
 *
 * @param outcome             stable outcome classification
 * @param exitCode            process exit code, or {@code null} when no exit exists
 * @param stdout              sanitized retained stdout text
 * @param stderr              sanitized retained stderr text
 * @param stdoutBytesRead     raw stdout bytes drained before decoding
 * @param stderrBytesRead     raw stderr bytes drained before decoding
 * @param stdoutBytesRetained raw stdout bytes represented in returned text
 * @param stderrBytesRetained raw stderr bytes represented in returned text
 * @param stdoutTruncated     whether stdout evidence is incomplete
 * @param stderrTruncated     whether stderr evidence is incomplete
 */
public record ExecutionResult(ProjectProcessExecutor.Outcome outcome, Integer exitCode, String stdout, String stderr, long stdoutBytesRead, long stderrBytesRead, long stdoutBytesRetained, long stderrBytesRetained,
                              boolean stdoutTruncated, boolean stderrTruncated) {

    /**
     * Validates result values.
     */
    public ExecutionResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        if (stdoutBytesRetained < 0 || stderrBytesRetained < 0 || stdoutBytesRetained > stdoutBytesRead || stderrBytesRetained > stderrBytesRead || stdoutBytesRetained > ProjectProcessExecutor.MAX_RETAINED_BYTES
                || stderrBytesRetained > ProjectProcessExecutor.MAX_RETAINED_BYTES) {
            throw new IllegalArgumentException("invalid output evidence counts");
        }
    }

    /**
     * Converts this evidence to the stable agent-facing result object.
     *
     * @return insertion-ordered result map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", outcome.value());
        if (exitCode != null) {
            result.put("exitCode", exitCode);
        }
        result.put("stdout", stdout);
        result.put("stderr", stderr);
        result.put("stdoutTruncated", stdoutTruncated);
        result.put("stderrTruncated", stderrTruncated);
        result.put("stdoutBytesRead", stdoutBytesRead);
        result.put("stderrBytesRead", stderrBytesRead);
        result.put("stdoutBytesRetained", stdoutBytesRetained);
        result.put("stderrBytesRetained", stderrBytesRetained);
        return result;
    }

    /**
     * Returns whether the process started and exited with code zero.
     *
     * @return true only for a completed zero exit
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean succeeded() {
        return outcome == ProjectProcessExecutor.Outcome.COMPLETED;
    }
}
