package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Parent for typed project constraints. */
@Command(name = "constraint", description = "Manage typed project constraints.")
public final class ConstraintCommand implements Runnable {
    @Spec private CommandSpec spec;
    /** Creates the constraint parent command. */
    public ConstraintCommand() { }
    /** Prints constraint help. */
    @Override public void run() { spec.commandLine().usage(spec.commandLine().getOut()); }
}
