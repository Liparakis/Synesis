package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpJsonRpcResponses.object;
import static org.synesis.mcp.application.McpJsonRpcResponses.policyDenied;
import static org.synesis.mcp.application.McpToolArguments.strings;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.synesis.coordination.domain.collaboration.ResourceSelector;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.integration.IntegrationCompatibilityService;
import org.synesis.workspace.application.integration.WorkspaceIntegrationReadinessService;

/**
 * Evaluates the {@code integrationCheck} of a {@code get_next_action} call and interprets the
 * validation evidence it carries.
 *
 * <p>Kept apart from {@link McpProtocolHandler} because it is a compatibility rule over
 * provider-supplied JSON that decides whether tests count as passed, and it needs no connection state.
 */
final class McpIntegrationCheckEvidence {

    /**
     * Wording that marks a test report as failed, whatever else it says.
     */
    private static final Pattern FAILURE_WORDING = Pattern.compile("\\b(?:failed|failure|error|errors|exit\\s*code\\s*[1-9]\\d*)\\b");

    /** Utility class; these rules are exposed through static operations. */
    private McpIntegrationCheckEvidence() {
    }

    /**
     * Judges one provider-supplied integration candidate. A missing {@code controlHead} or {@code base}
     * becomes the text {@code null} and is judged as a stale base rather than rejected.
     *
     * @param check provider-supplied integration evidence
     * @return a completed response when the candidate is compatible, otherwise a blocked one
     */
    static AgentResponse check(Map<?, ?> check) {
        try {
            String head = String.valueOf(check.get("controlHead"));
            String base = String.valueOf(check.get("base"));
            List<String> paths = strings(check.get("paths"));
            List<String> claims = strings(check.get("claims"));
            var snapshot = new IntegrationCompatibilityService.SnapshotInput("mcp-candidate", base, paths, claims.stream().map(ResourceSelector::pathExact).toList(), List.of(), List.of());
            var result = new WorkspaceIntegrationReadinessService().check(new IntegrationCompatibilityService.CheckRequest(head, List.of(snapshot), List.of(), testsPassed(check)));
            List<String> failureCodes = result.failures().stream().map(Enum::name).toList();
            return new AgentResponse(result.accepted() ? AgentStatus.COMPLETED : AgentStatus.BLOCKED,
                    result.accepted() ? null : AgentReason.INTEGRATION_CONFLICT,
                    result.accepted() ? null : AgentNextAction.REQUEST_HUMAN_HELP,
                    object("accepted", result.accepted(), "failures", failureCodes, "actions", result.actions()));
        } catch (Exception failure) {
            return policyDenied(AgentNextAction.RETRY, "error", failure.getMessage());
        }
    }

    /**
     * Reads the bounded validation evidence accepted by the integration-check compatibility adapter.
     * Explicit structured results remain authoritative; the legacy provider text forms are accepted
     * only for an unambiguous passing count and are rejected when failure wording is present.
     *
     * @param check provider-supplied integration evidence
     * @return whether the supplied validation evidence is a pass
     */
    private static boolean testsPassed(Map<?, ?> check) {
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
                    .noneMatch(entry -> entry instanceof String text && FAILURE_WORDING.matcher(text.toLowerCase(Locale.ROOT)).find());
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
        return normalized.matches("(?s).*\\b\\d+\\s+passed\\b.*") && !FAILURE_WORDING.matcher(normalized).find();
    }
}
