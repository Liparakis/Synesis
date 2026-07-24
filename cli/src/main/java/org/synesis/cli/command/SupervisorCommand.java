package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Parent for local supervisor commands.
 */
@Command(name = "supervisor", description = "Run and inspect a local supervisor.", mixinStandardHelpOptions = true)
public final class SupervisorCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    /**
     * Creates the supervisor command parent.
     */
    public SupervisorCommand() {
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
