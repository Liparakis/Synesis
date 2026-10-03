package org.synesis.workspace.agent;

import java.util.List;

/**
 * Bounded result payload for semantic capability ownership queries.
 *
 * <p>Contains concise capability request fields without internal worker, supervisor,
 * or prediction identifiers.
 *
 * @param capability     optional semantic capability name
 * @param requiredFields list of contract fields requested from the agent
 * @since 1.0
 */
public record AgentCapabilityResult(String capability, List<String> requiredFields) {

    /**
     * Creates an unmodifiable list of required fields.
     */
    public AgentCapabilityResult {
        requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
    }
}
