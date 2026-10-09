package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpToolArguments.nullableStringList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.workspace.application.agent.session.AgentTaskIntent;

/**
 * Decodes the task declaration and the ownership claims carried by {@code ensure_session} arguments.
 *
 * <p>Kept apart from {@link McpProtocolHandler} because the nested-versus-flattened task shapes, the
 * bounded dependency list, and the claim and review-target selector forms are wire-contract rules that
 * depend on no connection state. Selectors parsed here only record what the caller asked for; claim
 * admission and ownership stay with the application services.
 */
final class McpTaskArguments {

    /**
     * Maximum number of {@code knownDependencies} entries.
     */
    private static final int MAX_KNOWN_DEPENDENCIES = 50;
    /**
     * Maximum length of one {@code knownDependencies} entry.
     */
    private static final int MAX_DEPENDENCY_LENGTH = 128;

    /** Utility class; these rules are exposed through static operations. */
    private McpTaskArguments() {
    }

    /**
     * Reads a nonblank string field from the nested task object when present.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param field     field name inside the {@code task} object
     * @return the field value, or {@code null} when absent, blank, or not a string
     */
    static String taskField(Map<String, Object> arguments, String field) {
        if (arguments == null || !(arguments.get("task") instanceof Map<?, ?> taskMap)) {
            return null;
        }
        Object value = taskMap.get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * Decodes nested task intent fields while preserving the bounded flattened-provider form.
     *
     * @param arguments validated top-level tool arguments, or {@code null}
     * @return typed task intent, or {@code null} when the tool call declares no task
     * @throws IllegalArgumentException when nested task shape or field types violate the wire contract
     */
    static AgentTaskIntent parseTaskIntent(Map<String, Object> arguments) {
        if (arguments == null) {
            return null;
        }
        Object taskObj = arguments.get("task");
        Map<?, ?> map;
        if (taskObj instanceof Map<?, ?> taskMap) {
            if (Stream.of("goal", "acceptance", "likelyScopes", "knownDependencies", "workGroupId", "role", "reviewTargets", "intentId", "claimEpoch", "claims").anyMatch(arguments::containsKey)) {
                throw new IllegalArgumentException("TASK_FIELDS_MUST_BE_NESTED");
            }
            map = taskMap;
        } else if (arguments.containsKey("goal") || arguments.containsKey("acceptance") || arguments.containsKey("claims") || arguments.containsKey("role") || arguments.containsKey("reviewTargets") || arguments.containsKey("intentId") || arguments.containsKey("claimEpoch")) {
            // A few provider renderers flatten the task object.  Normalize
            // that bounded shape without changing the advertised schema.
            map = arguments;
        } else {
            return null;
        }
        Object rawGoal = map.get("goal");
        Object rawAcceptance = map.get("acceptance");
        if (rawGoal != null && !(rawGoal instanceof String) || rawAcceptance != null && !(rawAcceptance instanceof String)) {
            throw new IllegalArgumentException("goal and acceptance must be strings");
        }
        String goal = (String) rawGoal;
        String acceptance = (String) rawAcceptance;
        List<String> likelyScopes = nullableStringList(map.get("likelyScopes"), "likelyScopes");
        List<String> knownDependencies = parseKnownDependencies(map.get("knownDependencies"));
        UUID workGroupId = null;
        if (map.get("workGroupId") instanceof String value && !value.isBlank()) {
            try {
                workGroupId = UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (map.containsKey("completionMode")) {
            throw new IllegalArgumentException("completionMode was removed; use get_next_action completionRequested");
        }
        WorkIntent.Role role = WorkIntent.Role.PRODUCER;
        Object rawRole = map.get("role");
        if (rawRole != null) {
            if (!(rawRole instanceof String value)) {
                throw new IllegalArgumentException("role must be a string");
            }
            role = WorkIntent.Role.fromWire(value);
        }
        if (map.containsKey("reviewTargets") && map.get("reviewTargets") == null) {
            throw new IllegalArgumentException("reviewTargets must be an array");
        }
        List<ResourceSelector> reviewTargetSelectors = parseSelectorList(map.get("reviewTargets"));
        UUID intentId = null;
        if (map.get("intentId") != null) {
            if (!(map.get("intentId") instanceof String value)) {
                throw new IllegalArgumentException("intentId must be a UUID string");
            }
            try {
                intentId = UUID.fromString(value);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("invalid intentId", invalid);
            }
        }
        Long claimEpoch = null;
        if (map.get("claimEpoch") != null) {
            if (!(map.get("claimEpoch") instanceof Number value) || value.longValue() < 1
                    || value.doubleValue() != value.longValue()) {
                throw new IllegalArgumentException("claimEpoch must be a positive integer");
            }
            claimEpoch = value.longValue();
        }
        return new AgentTaskIntent(goal,
                acceptance,
                likelyScopes,
                knownDependencies,
                workGroupId,
                role,
                reviewTargetSelectors,
                intentId,
                claimEpoch);
    }

    /**
     * Parses the bounded structured capability identifiers supplied at admission.
     *
     * @param rawDependencies raw task field
     * @return immutable dependency identifiers
     * @throws IllegalArgumentException when the field is not a bounded string list
     */
    private static List<String> parseKnownDependencies(Object rawDependencies) {
        if (rawDependencies == null) {
            return List.of();
        }
        if (!(rawDependencies instanceof List<?> entries)) {
            throw new IllegalArgumentException("knownDependencies must be an array");
        }
        if (entries.size() > MAX_KNOWN_DEPENDENCIES) {
            throw new IllegalArgumentException("knownDependencies exceeds 50 items");
        }
        List<String> dependencies = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof String dependency) || dependency.isBlank() || dependency.length() > MAX_DEPENDENCY_LENGTH) {
                throw new IllegalArgumentException("knownDependencies entries must be bounded strings");
            }
            dependencies.add(dependency);
        }
        return List.copyOf(dependencies);
    }

    /**
     * Parses the bounded non-ownership selectors used to identify review targets.
     *
     * @param rawSelectors untrusted review target array, possibly {@code null}
     * @return immutable review-target selectors, empty when absent
     * @throws IllegalArgumentException when the shape, path, or kind is invalid
     */
    private static List<ResourceSelector> parseSelectorList(Object rawSelectors) {
        if (rawSelectors == null) {
            return List.of();
        }
        if (!(rawSelectors instanceof List<?> entries)) {
            throw new IllegalArgumentException("reviewTargets must be an array");
        }
        List<ResourceSelector> selectors = new ArrayList<>();
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?> selector)) {
                throw new IllegalArgumentException("review target selector must be an object");
            }
            Object path = selector.get("path");
            if (!(path instanceof String)) {
                path = selector.get("relativePath");
            }
            if (!(path instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException("review target selector path is required");
            }
            Object rawKind = selector.get("kind");
            String kind = rawKind == null ? "path_exact" : rawKind instanceof String kindValue ? kindValue.toLowerCase(Locale.ROOT) : null;
            if (kind == null) {
                throw new IllegalArgumentException("review target selector kind must be a string");
            }
            switch (kind) {
                case "path_exact" -> selectors.add(ResourceSelector.pathExact(value));
                case "path_subtree" -> selectors.add(ResourceSelector.pathSubtree(value));
                default -> throw new IllegalArgumentException("unknown review target selector kind: " + rawKind);
            }
        }
        return List.copyOf(selectors);
    }

    /**
     * Parses explicitly requested ownership claims without treating likelyScopes as authority.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @return immutable claim selectors, empty when none were supplied
     */
    static List<ResourceSelector> parseClaimSelectors(Map<String, Object> arguments) {
        if (arguments == null) {
            return List.of();
        }
        Object taskObj = arguments.get("task");
        Object claimsObj = taskObj instanceof Map<?, ?> taskMap ? taskMap.get("claims") : arguments.get("claims");
        if (!(claimsObj instanceof List<?> claims)) {
            return List.of();
        }
        List<ResourceSelector> selectors = new ArrayList<>();
        for (Object item : claims) {
            if (item instanceof Map<?, ?> claim) {
                Object path = claim.get("path");
                if (!(path instanceof String)) {
                    path = claim.get("relativePath");
                }
                String kind = claim.get("kind") instanceof String value ? value.toLowerCase(Locale.ROOT) : "path_exact";
                if (path instanceof String value) {
                    selectors.add("path_subtree".equals(kind) ? ResourceSelector.pathSubtree(value) : ResourceSelector.pathExact(value));
                }
            }
        }
        return List.copyOf(selectors);
    }

    /**
     * Distinguishes an omitted claims field from an explicitly supplied claims array.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @return {@code true} when a {@code claims} key is present, in the nested task object if there is one
     */
    static boolean claimsFieldSpecified(Map<String, Object> arguments) {
        if (arguments == null) {
            return false;
        }
        return arguments.get("task") instanceof Map<?, ?> taskMap ? taskMap.containsKey("claims") : arguments.containsKey("claims");
    }
}
