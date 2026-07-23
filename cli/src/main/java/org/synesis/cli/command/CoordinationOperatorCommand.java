package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Parent command for the public coordination operator surface. */
@Command(name = "coordination", description = "Operate the local coordination plane.", mixinStandardHelpOptions = true)
public final class CoordinationOperatorCommand implements Runnable {
    /** Creates the coordination command parent. */
    public CoordinationOperatorCommand() { }
    @Spec private CommandSpec spec;

    /** Prints child command help. */
    @Override public void run() { spec.commandLine().usage(spec.commandLine().getOut()); }
}
