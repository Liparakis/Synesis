package org.synesis.workspace.agent;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.synesis.workspace.infrastructure.diagnostics.LocalDiagnostics;

/**
 * Builds the answer for an internal failure and records where it happened (ADR-0091).
 *
 * <p>The answer carries the identifier of a diagnostics entry, not the exception. Whoever reads
 * the run afterwards finds the cause and its stack under that identifier.</p>
 */
public final class AgentFailures {

    /** Result key of the diagnostics entry's identifier. */
    public static final String FAILURE_POINT = "failurePoint";

    private AgentFailures() {
    }

    /**
     * Answers an internal failure, having recorded its cause.
     *
     * @param projectRoot project the failure belongs to, or {@code null} when none was resolved
     * @param operation   what was being done, as {@code component.operation}
     * @param cause       the exception behind the failure
     * @param result      what the answer already says, or {@code null}
     * @return a {@code FAILED} answer that names the recorded failure point
     */
    public static AgentResponse internal(Path projectRoot, String operation, Throwable cause, Map<String, Object> result) {
        int split = operation.indexOf('.');
        String reason = result == null ? String.valueOf(cause) : String.valueOf(result);
        String id = LocalDiagnostics.failurePoint(projectRoot, split < 0 ? operation : operation.substring(0, split), split < 0 ? "" : operation.substring(split + 1), reason, cause);
        Map<String, Object> answer = new LinkedHashMap<>();
        if (result != null) {
            answer.putAll(result);
        }
        if (!id.isEmpty()) {
            answer.put(FAILURE_POINT, id);
        }
        return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, answer.isEmpty() ? null : answer);
    }
}
