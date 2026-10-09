package org.synesis.mcp.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Interprets the validation evidence supplied with a {@code get_next_action} integration check.
 *
 * <p>Kept apart from {@link McpProtocolHandler} because it is a compatibility rule over
 * provider-supplied JSON that decides whether tests count as passed, and it needs no connection state.
 */
final class McpIntegrationCheckEvidence {

    /** Utility class; these rules are exposed through static operations. */
    private McpIntegrationCheckEvidence() {
    }

    /**
     * Reads the bounded validation evidence accepted by the integration-check compatibility adapter.
     * Explicit structured results remain authoritative; the legacy provider text forms are accepted
     * only for an unambiguous passing count and are rejected when failure wording is present.
     *
     * @param check provider-supplied integration evidence
     * @return whether the supplied validation evidence is a pass
     */
    static boolean testsPassed(Map<?, ?> check) {
        Object explicit = check.get("testsPassed");
        if (explicit instanceof Boolean value) {
            return value;
        }
        Object testResult = check.get("testResult");
        if (testResult instanceof Map<?, ?> result) {
            Object outcome = result.get("outcome");
            Object exitCode = result.get("exitCode");
            return "completed".equalsIgnoreCase(String.valueOf(outcome)) && exitCode instanceof Number number && number.intValue() == 0;
        }
        if (testResult instanceof String text && passingTestText(text)) {
            return true;
        }
        Object tests = check.get("tests");
        if (tests instanceof List<?> entries) {
            return entries.stream().anyMatch(entry -> entry instanceof String text && passingTestText(text)) && entries.stream()
                    .noneMatch(entry -> entry instanceof String text && text.toLowerCase(Locale.ROOT).matches("(?s).*\\b(?:failed|failure|error|errors|exit\\s*code\\s*[1-9]\\d*)\\b.*"));
        }
        return false;
    }

    /**
     * Accepts a legacy test-output string only when it reports passes without failure wording.
     *
     * @param text provider-supplied test output
     * @return whether the text unambiguously reports a passing run
     */
    private static boolean passingTestText(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.matches("(?s).*\\b\\d+\\s+passed\\b.*") && !normalized.matches("(?s).*\\b(?:failed|failure|error|errors|exit\\s*code\\s*[1-9]\\d*)\\b.*");
    }
}
