package org.synesis.mcp.application;

import java.util.LinkedHashMap;
import java.util.Map;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * Makes sure no refusal leaves the server without saying what to do about it.
 *
 * <p>Refusals are built in many places and several carry only a code. An agent that mistyped one
 * identifier was answered with such a code, stopped, and left its peer waiting. One rule at the
 * exit covers every refusal; a refusal that already explains itself, or that is a projected action
 * with its own envelope, is left as it is.</p>
 */
final class McpRefusalGuidance {

    private static final String INSTRUCTION = "instruction";

    private static final String GENERAL = "This call was refused and changed nothing. If this answer names a field, value or identifier, correct it and call again; that needs no human. "
            + "Otherwise call get_next_action and follow the action it projects, copying its arguments exactly instead of retyping identifiers.";

    private McpRefusalGuidance() {
    }

    /**
     * Adds the general instruction to a refusal that carries none.
     *
     * @param response the answer of a tool
     * @return the answer, with an instruction when it is a refusal without one
     */
    static AgentResponse of(AgentResponse response) {
        boolean refused = response.status() == AgentStatus.BLOCKED || response.status() == AgentStatus.FAILED || response.status() == AgentStatus.RETRY_REQUIRED;
        if (!refused || response.result() != null && !(response.result() instanceof Map<?, ?>)) {
            return response;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (response.result() instanceof Map<?, ?> given) {
            if (given.containsKey(INSTRUCTION) || given.containsKey("workflow") || given.containsKey("guidance") || given.containsKey("message")) {
                return response;
            }
            given.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        result.put(INSTRUCTION, GENERAL);
        return new AgentResponse(response.status(), response.reason(), response.nextAction(), result);
    }

    /**
     * Refuses a malformed argument, keeping what the parser said was wrong with it.
     *
     * @param nextAction the next action the refusal has always carried
     * @param key        result key of the code
     * @param code       stable refusal code
     * @param failure    the parser's failure
     * @return the refusal, with the parser's message as {@code detail} when it says more than the code
     */
    static AgentResponse malformed(AgentNextAction nextAction, String key, String code, IllegalArgumentException failure) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, code);
        if (failure.getMessage() != null && !failure.getMessage().equals(code)) {
            result.put("detail", failure.getMessage());
        }
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, nextAction, result);
    }
}
