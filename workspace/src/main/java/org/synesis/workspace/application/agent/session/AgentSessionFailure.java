package org.synesis.workspace.application.agent.session;

import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.workspace.ReadinessResult;

/**
 * A refusal raised while resolving a session context that {@link AgentSessionService#ensureSession}
 * reports with its own public reason.
 *
 * <p>It stays an {@link IllegalStateException} with the historical message because callers outside
 * this package read that message.</p>
 */
final class AgentSessionFailure extends IllegalStateException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Refusals with a public reason of their own.
     */
    enum Code {
        REPOSITORY_NOT_PORTABLE,
        PROVIDER_INTEGRATION_REQUIRED,
        PROVIDER_CONFIGURATION_CONFLICT,
        WORKSPACE_NOT_READY
    }

    private static final String READINESS_PREFIX = "Workspace readiness failed: ";

    private final Code code;
    private final String diagnostic;
    private final transient AgentResponse answer;

    private AgentSessionFailure(Code code, String message, String diagnostic, AgentResponse answer) {
        super(message);
        this.code = code;
        this.diagnostic = diagnostic;
        this.answer = answer;
    }

    static AgentSessionFailure of(Code code) {
        return new AgentSessionFailure(code, code.name(), null, null);
    }

    /**
     * The workspace is not ready for a reason other than provider configuration. Only a code-shaped
     * reason is surfaced as the diagnostic. A readiness answer that asks for a person ({@code blocked})
     * is kept whole, so that ensuring the session says what every other operation says.
     */
    static AgentSessionFailure workspaceNotReady(ReadinessResult readiness) {
        String internalReason = readiness.internalReason();
        String diagnostic = internalReason.matches("[A-Z][A-Z0-9_]{2,63}") ? internalReason : "WORKSPACE_UNVERIFIED";
        AgentResponse answer = readiness.response().status() == AgentStatus.BLOCKED ? readiness.response() : null;
        return new AgentSessionFailure(Code.WORKSPACE_NOT_READY, READINESS_PREFIX + internalReason, diagnostic, answer);
    }

    Code code() {
        return code;
    }

    String diagnostic() {
        return diagnostic;
    }

    /** The readiness answer to return as it is, or {@code null} to build one from the diagnostic. */
    AgentResponse answer() {
        return answer;
    }
}
