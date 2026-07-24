package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Parent for prediction lifecycle commands.
 */
@Command(name = "prediction", description = "Create and coordinate capability predictions.", mixinStandardHelpOptions = true)
public final class PredictionCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    /**
     * Creates the prediction command parent.
     */
    public PredictionCommand() {
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
