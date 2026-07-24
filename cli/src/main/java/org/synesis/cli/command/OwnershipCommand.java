package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Parent for semantic ownership commands.
 */
@Command(name = "ownership", description = "Claim and release semantic capability ownership.", mixinStandardHelpOptions = true)
public final class OwnershipCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    /**
     * Creates the ownership command parent.
     */
    public OwnershipCommand() {
    }

    /**
     * Prints child command help.
     */
    @Override
    public void run() {
        spec.commandLine()
                .usage(spec.commandLine()
                        .getOut());
    }
}
