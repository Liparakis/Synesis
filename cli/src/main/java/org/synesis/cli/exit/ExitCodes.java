package org.synesis.cli.exit;

/**
 * Stable process exit codes for the standalone Synesis CLI.
 */
public final class ExitCodes {

    /**
     * Successful command completion.
     */
    public static final int OK = 0;
    /**
     * A valid command could not complete its requested operation.
     */
    public static final int COMMAND_FAILED = 1;
    /**
     * Invalid command syntax.
     */
    public static final int USAGE = 2;
    /**
     * Doctor found a broken local component.
     */
    @SuppressWarnings("unused")
    public static final int DOCTOR_BROKEN = 3;
    /**
     * Local identity or configuration failure.
     */
    public static final int LOCAL_CONFIGURATION = 10;
    /**
     * Invalid invitation.
     */
    public static final int INVITE_INVALID = 11;
    /**
     * Host identity mismatch.
     */
    public static final int HOST_IDENTITY_MISMATCH = 12;
    /**
     * Invalid or replayed human-mediated answer link.
     */
    public static final int ANSWER_INVALID = 14;
    /**
     * The other computer runs a Synesis with a different link compatibility number.
     */
    public static final int INCOMPATIBLE_VERSION = 15;
    /**
     * Bounded session/network failure.
     */
    static final int SESSION_FAILED = 13;
    /**
     * Unexpected redacted internal failure.
     */
    public static final int INTERNAL = 70;

    private ExitCodes() {
    }
}
