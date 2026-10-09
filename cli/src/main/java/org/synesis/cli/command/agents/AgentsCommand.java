package org.synesis.cli.command.agents;

import picocli.CommandLine.Command;

/**
 * Parent command for running provider agents on a project.
 */
@Command(name = "agents", description = "Run headless agents on this project and keep them moving.", mixinStandardHelpOptions = true)
public final class AgentsCommand {

    /**
     * Creates the parent command.
     */
    public AgentsCommand() {
    }
}
