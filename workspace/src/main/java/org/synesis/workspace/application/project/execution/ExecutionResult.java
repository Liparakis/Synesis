package org.synesis.workspace.application.project.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

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
 * @param sandbox             how the command was confined: the sandbox's name, {@code NONE_ALLOWED_BY_DECISION}, or {@code NONE} when it was refused; empty when no command was started
 */
public record ExecutionResult(ProjectProcessExecutor.Outcome outcome, Integer exitCode, String stdout, String stderr, long stdoutBytesRead, long stderrBytesRead, long stdoutBytesRetained, long stderrBytesRetained,
                              boolean stdoutTruncated, boolean stderrTruncated, String sandbox) {

    /**
     * Creates evidence without sandbox information.
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
    public ExecutionResult(ProjectProcessExecutor.Outcome outcome, Integer exitCode, String stdout, String stderr, long stdoutBytesRead, long stderrBytesRead, long stdoutBytesRetained,
                           long stderrBytesRetained, boolean stdoutTruncated, boolean stderrTruncated) {
        this(outcome, exitCode, stdout, stderr, stdoutBytesRead, stderrBytesRead, stdoutBytesRetained, stderrBytesRetained, stdoutTruncated, stderrTruncated, "");
    }

    /**
     * Validates result values.
     */
    public ExecutionResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(sandbox, "sandbox");
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
        if (!sandbox.isEmpty()) {
            result.put("sandbox", sandbox);
        }
        return result;
    }

    /**
     * Maps the outcome to the agent-facing response status, reason, and next action.
     *
     * @param payload result object to return with the response
     * @return agent response carrying {@code payload}
     */
    public AgentResponse toAgentResponse(Map<String, Object> payload) {
        AgentStatus status = switch (outcome) {
            case COMPLETED -> AgentStatus.COMPLETED;
            case NON_ZERO_EXIT, COMMAND_WORKING_DIRECTORY_INVALID, COMMAND_EXECUTABLE_NOT_FOUND, COMMAND_PERMISSION_DENIED, COMMAND_SANDBOX_UNAVAILABLE, COMMAND_SANDBOX_SETUP_FAILED -> AgentStatus.BLOCKED;
            case COMMAND_TIMED_OUT, COMMAND_CANCELLED, COMMAND_START_FAILED, COMMAND_TERMINATED -> AgentStatus.FAILED;
        };
        AgentReason reason = switch (outcome) {
            case COMPLETED -> null;
            case NON_ZERO_EXIT -> AgentReason.COMMAND_FAILED;
            case COMMAND_WORKING_DIRECTORY_INVALID -> AgentReason.COMMAND_WORKING_DIRECTORY_INVALID;
            case COMMAND_EXECUTABLE_NOT_FOUND -> AgentReason.COMMAND_EXECUTABLE_NOT_FOUND;
            case COMMAND_PERMISSION_DENIED -> AgentReason.COMMAND_PERMISSION_DENIED;
            case COMMAND_TIMED_OUT -> AgentReason.COMMAND_TIMEOUT;
            case COMMAND_CANCELLED -> AgentReason.COMMAND_CANCELLED;
            case COMMAND_START_FAILED -> AgentReason.COMMAND_START_FAILED;
            case COMMAND_TERMINATED -> AgentReason.COMMAND_TERMINATED;
            case COMMAND_SANDBOX_UNAVAILABLE -> AgentReason.SANDBOX_UNAVAILABLE;
            case COMMAND_SANDBOX_SETUP_FAILED -> AgentReason.SANDBOX_SETUP_FAILED;
        };
        AgentNextAction nextAction = switch (outcome) {
            case COMPLETED, NON_ZERO_EXIT, COMMAND_WORKING_DIRECTORY_INVALID, COMMAND_EXECUTABLE_NOT_FOUND, COMMAND_PERMISSION_DENIED, COMMAND_SANDBOX_UNAVAILABLE, COMMAND_SANDBOX_SETUP_FAILED -> null;
            case COMMAND_TIMED_OUT, COMMAND_CANCELLED, COMMAND_START_FAILED, COMMAND_TERMINATED -> AgentNextAction.REQUEST_HUMAN_HELP;
        };
        return new AgentResponse(status, reason, nextAction, payload);
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
