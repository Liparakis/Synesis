package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Parent command for non-mutating and bootstrap identity operations.
 */
@Command(name = "identity", description = "Inspect the local Synesis identity.")
public final class IdentityCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    /**
     * Creates the identity parent command.
     */
    public IdentityCommand() {
    }

    /**
     * Prints command help when {@code identity} has no subcommand.
     */
    @Override
    public void run() {
        spec.commandLine()
                .usage(spec.commandLine()
                        .getOut());
    }
}
