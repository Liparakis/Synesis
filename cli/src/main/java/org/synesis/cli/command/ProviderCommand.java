package org.synesis.cli.command;

import picocli.CommandLine.Command;

/** Parent command for project-local provider lifecycle operations. */
@Command(name = "provider", description = "Manage project-local provider integrations.")
public final class ProviderCommand {
    /** Creates the parent command. */
    public ProviderCommand() {
    }
}
