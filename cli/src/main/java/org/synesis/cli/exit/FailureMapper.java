package org.synesis.cli.exit;

import org.synesis.cli.terminal.Terminal;
import org.synesis.link.transport.OnboardingFailure;
import org.synesis.link.transport.OnboardingFailureCode;

/** Maps typed Link failures to stable numeric exits and redacted diagnostics. */
public final class FailureMapper {
    private FailureMapper() { }

    /**
     * Writes one stable failure label and returns its numeric process code.
     *
     * @param failure typed Link failure
     * @param terminal output boundary
     * @return stable exit code
     */
    public static int map(OnboardingFailure failure, Terminal terminal) {
        OnboardingFailureCode code = failure.code();
        terminal.stdout("FAILURE=" + code.name());
        terminal.stderr(human(code));
        return switch (code) {
            case IDENTITY_FAILED -> ExitCodes.LOCAL_CONFIGURATION;
            case INVITE_INVALID -> ExitCodes.INVITE_INVALID;
            case HOST_IDENTITY_MISMATCH -> ExitCodes.HOST_IDENTITY_MISMATCH;
            case NO_USABLE_CANDIDATE, HOST_TIMEOUT, CONNECTION_FAILED -> ExitCodes.SESSION_FAILED;
            case INTERNAL -> ExitCodes.INTERNAL;
        };
    }

    /**
     * Maps an unexpected fault without exposing its message.
     *
     * @param failure unexpected fault
     * @param terminal output boundary
     * @return internal exit code
     */
    public static int internal(Throwable failure, Terminal terminal) {
        terminal.stdout("FAILURE=INTERNAL");
        terminal.stderr("Unexpected internal failure.");
        return ExitCodes.INTERNAL;
    }

    private static String human(OnboardingFailureCode code) {
        return switch (code) {
            case IDENTITY_FAILED -> "Local identity configuration failed.";
            case INVITE_INVALID -> "The invitation is invalid or expired.";
            case HOST_IDENTITY_MISMATCH -> "The authenticated host identity did not match the invitation.";
            case NO_USABLE_CANDIDATE -> "No usable direct candidate was available.";
            case HOST_TIMEOUT -> "The host wait expired.";
            case CONNECTION_FAILED -> "The bounded session connection failed.";
            case INTERNAL -> "Unexpected internal failure.";
        };
    }
}
