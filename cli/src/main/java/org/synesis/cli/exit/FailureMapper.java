package org.synesis.cli.exit;

import org.synesis.cli.terminal.Terminal;
import org.synesis.link.onboarding.OnboardingFailure;
import org.synesis.link.onboarding.OnboardingFailureCode;

/**
 * Maps typed Link failures to stable numeric exits and redacted diagnostics.
 */
public final class FailureMapper {

    private FailureMapper() {
    }

    /**
     * Writes one stable failure label and returns its numeric process code.
     *
     * @param failure  typed Link failure
     * @param terminal output boundary
     * @return stable exit code
     */
    public static int map(OnboardingFailure failure, Terminal terminal) {
        OnboardingFailureCode code = failure.code();
        terminal.stdout("FAILURE=" + code.name());
        terminal.stderr(human(code));
        return switch (code) {
            case IDENTITY_FAILED -> ExitCodes.LOCAL_CONFIGURATION;
            case INVITE_INVALID, OWN_INVITATION -> ExitCodes.INVITE_INVALID;
            case ANSWER_INVALID -> ExitCodes.ANSWER_INVALID;
            case HOST_IDENTITY_MISMATCH -> ExitCodes.HOST_IDENTITY_MISMATCH;
            case NO_USABLE_CANDIDATE, HOST_TIMEOUT, CONNECTION_FAILED -> ExitCodes.SESSION_FAILED;
            case INTERNAL -> ExitCodes.INTERNAL;
        };
    }

    /**
     * Maps an unexpected fault without exposing its message.
     *
     * @param terminal output boundary
     * @return internal exit code
     */
    public static int internal(Terminal terminal) {
        terminal.stdout("FAILURE=INTERNAL");
        terminal.stderr("Unexpected internal failure.");
        return ExitCodes.INTERNAL;
    }

    private static String human(OnboardingFailureCode code) {
        return switch (code) {
            case IDENTITY_FAILED -> "Local identity configuration failed.";
            case INVITE_INVALID -> "The invitation is invalid or expired.";
            case ANSWER_INVALID -> "The answer is invalid, expired, mismatched, or already used.";
            case HOST_IDENTITY_MISMATCH -> "The authenticated host identity did not match the invitation.";
            case OWN_INVITATION -> "This invitation was created by this identity. Join it from another identity or machine.";
            case NO_USABLE_CANDIDATE -> "The invitation names no address this computer can reach, for example only IPv6 addresses when this computer has no IPv6.";
            case HOST_TIMEOUT -> "Nobody connected before the invitation expired. Create a new one; if the other computer is on another network, paste the answer link it shows, or start with synesis host --open-router-port.";
            case CONNECTION_FAILED -> "No connection was made. If the computers are on different networks, the host must paste the answer link shown here, or start again with synesis host --open-router-port.";
            case INTERNAL -> "Unexpected internal failure.";
        };
    }
}
