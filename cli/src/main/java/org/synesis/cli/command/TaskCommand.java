package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Parent for task lifecycle commands. */
@Command(name = "task", description = "Create and claim coordination tasks.", mixinStandardHelpOptions = true)
public final class TaskCommand implements Runnable {
    /** Creates the task command parent. */
    public TaskCommand() { }
    @Spec private CommandSpec spec;

    /** Prints child command help. */
    @Override public void run() { spec.commandLine().usage(spec.commandLine().getOut()); }
}
