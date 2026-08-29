package org.synesis.workspace.application.workspace;

import java.util.List;
import org.synesis.workspace.agent.AgentCapabilityResult;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.agent.AgentStatusResult;
import org.synesis.workspace.application.workspace.WorkspaceMutationBroker.Decision;
import org.synesis.workspace.application.workspace.WorkspaceMutationBroker.MutationResult;

/**
 * Central translator converting internal domain outcomes into concise agent-facing responses.
 *
 * <p>Shared across CLI agent operations, provider adapters, and future MCP transport layers.
 * Ensures internal protocol details (decision record IDs, SHA-256 evidence hashes, absolute
 * worktree paths, worker IDs) are retained inside {@link TranslatedOutcome} diagnostic correlation
 * metadata, while {@link AgentResponse} exposes only safe, actionable outcomes.
 *
 * @since 1.0
 */
public final class AgentOutcomeTranslator {

    /**
     * Creates an outcome translator.
     */
    public AgentOutcomeTranslator() {
    }

    private static boolean isProtectedTarget(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/')
                .toLowerCase();
        return normalized.startsWith(".synesis/") || normalized.startsWith(".codex/")
                || normalized.startsWith(".agents/") || normalized.startsWith(".git/")
                || normalized.equals(".synesis") || normalized.equals(".codex")
                || normalized.equals(".agents") || normalized.equals(".git");
    }

    private static String extractCapability(String message) {
        if (message == null || !message.contains("capability:")) {
            return null;
        }
        int index = message.indexOf("capability:");
        String substring = message.substring(index + "capability:".length())
                .trim();
        int space = substring.indexOf(' ');
        return space > 0 ? substring.substring(0, space) : substring;
    }

    /**
     * Translates an internal workspace mutation result.
     *
     * @param result       internal mutation result
     * @param relativePath target repository-relative path
     * @return translated outcome containing concise public response and internal diagnostic context
     */
    public TranslatedOutcome translateMutationResult(MutationResult result, String relativePath) {
        if (result == null || result.decision() == null) {
            return translateException(new IllegalArgumentException("Invalid internal mutation result"));
        }
        String path = relativePath == null ? "" : relativePath.replace('\\', '/');
        Decision decision = result.decision();
        String reasonCode = result.reasonCode();

        return switch (decision) {
            case ALLOW -> new TranslatedOutcome(
                    AgentResponse.completed(path, result.updatedRevision()),
                    decision,
                    reasonCode,
                    result.decisionId(),
                    result.interceptionEvidence(),
                    false, false, false
            );
            case DENY_POLICY -> {
                boolean isProtected = "PROTECTED_CONFIGURATION_TARGET".equalsIgnoreCase(reasonCode)
                        || isProtectedTarget(path);
                AgentReason reason = isProtected ? AgentReason.PROTECTED_CONFIGURATION : AgentReason.POLICY_DENIED;
                yield new TranslatedOutcome(
                        AgentResponse.blocked(reason),
                        decision,
                        reasonCode,
                        result.decisionId(),
                        result.interceptionEvidence(),
                        false, false, false
                );
            }
            case REQUEST_OWNER -> {
                String capability = extractCapability(result.message());
                List<String> requiredFields = List.of("inputs", "output", "behavior", "acceptanceTest");
                AgentResponse response = new AgentResponse(
                        AgentStatus.NEEDS_CAPABILITY,
                        AgentReason.OWNER_REQUIRED,
                        AgentNextAction.REQUEST_COORDINATION,
                        new AgentCapabilityResult(capability, requiredFields)
                );
                yield new TranslatedOutcome(
                        response,
                        decision,
                        reasonCode,
                        result.decisionId(),
                        result.interceptionEvidence(),
                        false, true, false
                );
            }
            case WORKSPACE_UNVERIFIED -> new TranslatedOutcome(
                    new AgentResponse(AgentStatus.RETRY_REQUIRED,
                            AgentReason.WORKSPACE_NOT_READY,
                            AgentNextAction.ENSURE_SESSION,
                            null),
                    decision,
                    reasonCode,
                    result.decisionId(),
                    result.interceptionEvidence(),
                    true, false, false
            );
            case SESSION_UNBOUND -> new TranslatedOutcome(
                    new AgentResponse(AgentStatus.RETRY_REQUIRED,
                            AgentReason.SESSION_NOT_READY,
                            AgentNextAction.ENSURE_SESSION,
                            null),
                    decision,
                    reasonCode,
                    result.decisionId(),
                    result.interceptionEvidence(),
                    true, false, false
            );
            case STALE_CONTEXT -> new TranslatedOutcome(
                    new AgentResponse(AgentStatus.RETRY_REQUIRED,
                            AgentReason.WORKSPACE_STALE,
                            AgentNextAction.ENSURE_SESSION,
                            null),
                    decision,
                    reasonCode,
                    result.decisionId(),
                    result.interceptionEvidence(),
                    true, false, false
            );
            case INVALID_TARGET -> new TranslatedOutcome(
                    new AgentResponse(AgentStatus.BLOCKED, AgentReason.INVALID_PATH, null, null),
                    decision,
                    reasonCode,
                    result.decisionId(),
                    result.interceptionEvidence(),
                    false, false, false
            );
            case INTERCEPTION_MISSING -> new TranslatedOutcome(
                    new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTERCEPTION_REQUIRED, null, null),
                    decision,
                    reasonCode,
                    result.decisionId(),
                    result.interceptionEvidence(),
                    false, false, false
            );
            default -> translateException(new IllegalArgumentException("Unknown broker decision: " + decision));
        };
    }

    /**
     * Translates a pending owner response state.
     *
     * @return translated waiting outcome
     */
    public TranslatedOutcome translatePendingOwner() {
        AgentResponse response = new AgentResponse(
                AgentStatus.WAITING,
                AgentReason.OWNER_RESPONSE_PENDING,
                AgentNextAction.WAIT,
                null
        );
        return new TranslatedOutcome(response, null, "OWNER_RESPONSE_PENDING", null, null, false, true, false);
    }

    /**
     * Translates a workspace readiness state.
     *
     * @param workspaceStatus workspace status identifier (e.g. "isolated")
     * @param pendingCount    pending items count
     * @return translated ready outcome
     */
    @SuppressWarnings("unused")
    public TranslatedOutcome translateReady(String workspaceStatus, int pendingCount) {
        String ws = workspaceStatus == null ? "isolated" : workspaceStatus;
        AgentResponse response = new AgentResponse(
                AgentStatus.READY,
                null,
                null,
                new AgentStatusResult(ws, Math.max(0, pendingCount))
        );
        return new TranslatedOutcome(response, null, "READY", null, null, false, false, false);
    }

    /**
     * Translates an internal exception into a safe failure response without leaking raw exception details.
     *
     * @param failure internal exception or error
     * @return translated failure outcome
     */
    public TranslatedOutcome translateException(Throwable failure) {
        String reasonCode = failure == null ? "UNKNOWN_FAILURE" : failure.getClass()
                                                                  .getSimpleName();
        AgentResponse response = new AgentResponse(
                AgentStatus.FAILED,
                AgentReason.INTERNAL_FAILURE,
                AgentNextAction.REQUEST_HUMAN_HELP,
                null
        );
        return new TranslatedOutcome(response, null, reasonCode, null, null, false, false, true);
    }
}
