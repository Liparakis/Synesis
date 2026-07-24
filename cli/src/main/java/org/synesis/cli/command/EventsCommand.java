package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Parent for coordination event commands.
 */
@Command(name = "events", description = "Follow ordered coordination events.", mixinStandardHelpOptions = true)
public final class EventsCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    /**
     * Creates the events command parent.
     */
    public EventsCommand() {
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
