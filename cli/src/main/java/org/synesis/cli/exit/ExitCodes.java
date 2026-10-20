package org.synesis.cli.exit;

import org.synesis.workspace.application.provider.ProviderApplicationService;

/**
 * Stable process exit codes for the standalone Synesis CLI. Every code a command can return is a constant here, and
 * {@code docs/cli/command-reference.md} documents each one (a test fails when it does not). A code has one meaning in
 * every command.
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
     * {@code synesis doctor --strict} found a project that is not healthy.
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
    public static final int SESSION_FAILED = 13;
    /**
     * A provider result names a provider that does not exist, or one without the thing asked for.
     */
    public static final int PROVIDER_UNAVAILABLE = ProviderApplicationService.EXIT_PROVIDER_UNAVAILABLE;
    /**
     * A provider status found the integration broken.
     */
    public static final int PROVIDER_BROKEN = ProviderApplicationService.EXIT_PROVIDER_BROKEN;
    /**
     * Unexpected redacted internal failure.
     */
    public static final int INTERNAL = 70;

    private ExitCodes() {
    }
}
