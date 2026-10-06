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
 * @param intentId              exact current intent required for a scope extension
 * @param claimEpoch            exact current claim epoch required for a scope extension
 */
public record AgentTaskIntent(String goal,
        String acceptance,
        List<String> likelyScopes,
        List<String> knownDependencies,
        UUID workGroupId,
        WorkIntent.Role role,
        List<ResourceSelector> reviewTargetSelectors,
        UUID intentId,
        Long claimEpoch) {

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
        if ((intentId == null) != (claimEpoch == null) || claimEpoch != null && claimEpoch < 1) {
            throw new IllegalArgumentException("intentId and positive claimEpoch must be supplied together");
        }
    }

    /**
     * Creates a producer intent without an explicit work group or review target.
     *
     * @param goal concise task goal
     * @param acceptance conditions that define completion
     * @param likelyScopes advisory scopes that do not acquire claims
     * @param knownDependencies explicitly declared capability dependencies
     */
    public AgentTaskIntent(String goal, String acceptance, List<String> likelyScopes, List<String> knownDependencies) {
        this(goal, acceptance, likelyScopes, knownDependencies, null, WorkIntent.Role.PRODUCER, List.of(), null, null);
    }

    /**
     * Preserves the original task-declaration constructor for provider-side callers.
     *
     * @param goal concise task goal
     * @param acceptance conditions that define completion
     * @param likelyScopes advisory scopes that do not acquire claims
     * @param knownDependencies explicitly declared capability dependencies
     * @param workGroupId work group to join, or {@code null} to start one
     * @param role lane role, defaulting to producer when {@code null}
     * @param reviewTargetSelectors selectors a reviewer lane intends to review
     */
    public AgentTaskIntent(String goal,
            String acceptance,
            List<String> likelyScopes,
            List<String> knownDependencies,
            UUID workGroupId,
            WorkIntent.Role role,
            List<ResourceSelector> reviewTargetSelectors) {
        this(goal, acceptance, likelyScopes, knownDependencies, workGroupId, role, reviewTargetSelectors, null, null);
    }
}
