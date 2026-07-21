package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** Prints the unified command help. */
@Command(name = "help", description = "Show Synesis command help.")
public final class HelpCommand implements Runnable {
    @Spec private CommandSpec spec;
    /** Creates the help command. */
    public HelpCommand() { }
    /** Prints root help. */
    @Override public void run() { spec.commandLine().getParent().usage(spec.commandLine().getOut()); }
}
