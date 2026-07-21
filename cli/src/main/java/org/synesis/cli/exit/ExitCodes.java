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
     * Invalid command syntax.
     */
    public static final int USAGE = 2;
    /**
     * Doctor found a broken local component.
     */
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
     * Bounded session/network failure.
     */
    public static final int SESSION_FAILED = 13;
    /**
     * Unexpected redacted internal failure.
     */
    public static final int INTERNAL = 70;

    private ExitCodes() {
    }
}
