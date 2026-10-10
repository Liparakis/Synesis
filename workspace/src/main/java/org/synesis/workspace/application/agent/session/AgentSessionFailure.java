package org.synesis.workspace.application.agent.session;

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

    private AgentSessionFailure(Code code, String message, String diagnostic) {
        super(message);
        this.code = code;
        this.diagnostic = diagnostic;
    }

    static AgentSessionFailure of(Code code) {
        return new AgentSessionFailure(code, code.name(), null);
    }

    /**
     * The workspace is not ready for a reason other than provider configuration. Only a code-shaped
     * reason is surfaced as the diagnostic.
     */
    static AgentSessionFailure workspaceNotReady(String internalReason) {
        String diagnostic = String.valueOf(internalReason).matches("[A-Z][A-Z0-9_]{2,63}") ? internalReason : "WORKSPACE_UNVERIFIED";
        return new AgentSessionFailure(Code.WORKSPACE_NOT_READY, READINESS_PREFIX + internalReason, diagnostic);
    }

    Code code() {
        return code;
    }

    String diagnostic() {
        return diagnostic;
    }
}
