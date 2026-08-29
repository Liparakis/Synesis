package org.synesis.cli.command.collaboration;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Parent for active collaboration commands.
 */
@Command(name = "collaboration", description = "Announce and inspect active work claims.", mixinStandardHelpOptions = true)
public final class CollaborationCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    /**
     * Creates the collaboration command parent.
     */
    public CollaborationCommand() {
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
