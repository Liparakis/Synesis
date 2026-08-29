package org.synesis.workspace.lifecycle.command;

/**
 * Stable terminal classifications for durable project commands.
 */
public enum ProjectCommandTerminalResolution {
    /**
     * The command process exited and its terminal result was observed.
     */
    OBSERVED_COMMAND_TERMINAL,
    /**
     * Launch or cleanup failed closed without an observed command outcome.
     */
    FAIL_CLOSED_LAUNCH_CLEANUP,
    /**
     * Human or workspace review made an unknown outcome safe to retain.
     */
    REVIEWED_WORKSPACE_SAFE
}
