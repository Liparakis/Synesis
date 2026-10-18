package org.synesis.cli.command.log;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Parent for commands that read the coordination logs this computer holds.
 */
@Command(name = "log", description = "Read the coordination logs this computer holds.", mixinStandardHelpOptions = true)
public final class LogCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    /**
     * Creates the log command parent.
     */
    public LogCommand() {
    }

    /**
     * Prints child command help.
     */
    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
