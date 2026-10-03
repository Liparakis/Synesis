package org.synesis.workspace.application.agent.session;

import java.util.List;
import java.util.UUID;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;

/**
 * Caller-provided task goal, acceptance, and advisory scope for session admission.
 *
 * @param goal                  concise goal
 * @param acceptance            acceptance criteria
 * @param likelyScopes          descriptive scopes only; they do not acquire claims
 * @param knownDependencies     explicitly declared capability dependencies
 * @param workGroupId           optional logical work-group identifier
 * @param role                  semantic producer or reviewer role
 * @param reviewTargetSelectors non-ownership selectors identifying producer work this reviewer may review
 */
public record AgentTaskIntent(String goal, String acceptance, List<String> likelyScopes, List<String> knownDependencies, UUID workGroupId, WorkIntent.Role role, List<ResourceSelector> reviewTargetSelectors) {

    /**
     * Validates task text, dependency identifiers, and selector bounds.
     */
    public AgentTaskIntent {
        if (goal != null && goal.length() > 4096) {
            throw new IllegalArgumentException("goal exceeds 4096 characters");
        }
        if (acceptance != null && acceptance.length() > 4096) {
            throw new IllegalArgumentException("acceptance exceeds 4096 characters");
        }
        if (likelyScopes != null && likelyScopes.size() > 50) {
            throw new IllegalArgumentException("likelyScopes exceeds 50 items");
        }
        if (knownDependencies == null) {
            knownDependencies = List.of();
        }
        if (knownDependencies.size() > 50) {
            throw new IllegalArgumentException("knownDependencies exceeds 50 items");
        }
        if (knownDependencies.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 128)) {
            throw new IllegalArgumentException("knownDependencies entries must be bounded strings");
        }
        knownDependencies = List.copyOf(knownDependencies);
        role = role == null ? WorkIntent.Role.PRODUCER : role;
        reviewTargetSelectors = reviewTargetSelectors == null ? List.of() : List.copyOf(reviewTargetSelectors);
        if (reviewTargetSelectors.size() > 128) {
            throw new IllegalArgumentException("reviewTargetSelectors exceeds 128 items");
        }
    }

    /**
     * Constructs a producer task without an explicit parent work group.
     *
     * @param goal              concise goal
     * @param acceptance        acceptance criteria
     * @param likelyScopes      descriptive scopes
     * @param knownDependencies explicitly declared dependencies
     */
    @SuppressWarnings("unused")
    public AgentTaskIntent(String goal, String acceptance, List<String> likelyScopes, List<String> knownDependencies) {
        this(goal, acceptance, likelyScopes, knownDependencies, null, WorkIntent.Role.PRODUCER, List.of());
    }

    /**
     * Constructs a producer task with an explicit parent work group.
     *
     * @param goal              concise goal
     * @param acceptance        acceptance criteria
     * @param likelyScopes      descriptive scopes
     * @param knownDependencies explicitly declared dependencies
     * @param workGroupId       parent work group
     */
    @SuppressWarnings("unused")
    public AgentTaskIntent(String goal, String acceptance, List<String> likelyScopes, List<String> knownDependencies, UUID workGroupId) {
        this(goal, acceptance, likelyScopes, knownDependencies, workGroupId, WorkIntent.Role.PRODUCER, List.of());
    }
}
