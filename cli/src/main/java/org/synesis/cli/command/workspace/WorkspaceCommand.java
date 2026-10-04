package org.synesis.cli.command.workspace;

import picocli.CommandLine.Command;

/**
 * Parent command for workspace operations.
 */
@Command(name = "workspace", description = "Perform workspace verification and mutation operations.", mixinStandardHelpOptions = true)
public final class WorkspaceCommand {

    /**
     * Creates the workspace command group.
     */
    public WorkspaceCommand() {
    }
}
