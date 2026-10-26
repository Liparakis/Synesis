package org.synesis.workspace.application.project;

/**
 * The project's signed policy was asked for and its log could not be read or verified (ADR-0104). Unchecked for the
 * same reason as {@link PolicyNotReceivedException}: every gate reads policy through the one door and refuses, and the
 * gate neither runs nor is skipped. Nothing falls back to {@code project.json}.
 */
public final class PolicyUnavailableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** The stable code. */
    public static final String CODE = "POLICY_UNAVAILABLE";

    /**
     * Creates the refusal.
     *
     * @param cause why the log could not be read
     */
    public PolicyUnavailableException(Throwable cause) {
        super(CODE + ": the project's signed policy could not be read from its log, so no gate runs and none is skipped", cause);
    }
}
