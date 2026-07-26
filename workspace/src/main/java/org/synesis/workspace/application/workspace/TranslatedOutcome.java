package org.synesis.workspace.application.workspace;
import org.synesis.workspace.application.workspace.WorkspaceMutationBroker;

import java.util.Objects;
import org.synesis.workspace.agent.AgentResponse;

/**
 * Result of translating an internal application outcome into a public agent response.
 *
 * <p>Holds both the public concise {@link AgentResponse} and internal correlation metadata.
 * Internal diagnostic details (decision ID, evidence hash, decision enum) are retained
 * here for logging and correlation but are omitted from {@link #publicResponse()}.
 *
 * @param publicResponse            concise agent-facing response
 * @param internalDecision          internal broker decision (or {@code null})
 * @param internalReasonCode        internal reason code (or {@code null})
 * @param decisionId                internal decision record identifier (or {@code null})
 * @param evidenceHash              internal SHA-256 evidence hash (or {@code null})
 * @param safeToRetry               whether retrying after session refresh is safe
 * @param waitRequired              whether the agent should wait for a background process
 * @param humanInterventionRequired whether human intervention is required
 * @since 1.0
 */
public record TranslatedOutcome(
        AgentResponse publicResponse,
        WorkspaceMutationBroker.Decision internalDecision,
        String internalReasonCode,
        String decisionId,
        String evidenceHash,
        boolean safeToRetry,
        boolean waitRequired,
        boolean humanInterventionRequired
) {

    /**
     * Validates required outcome fields.
     */
    public TranslatedOutcome {
        Objects.requireNonNull(publicResponse, "publicResponse");
    }
}
