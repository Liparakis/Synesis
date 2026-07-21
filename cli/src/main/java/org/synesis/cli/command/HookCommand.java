package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Parent for provider hook adapters. */
@Command(name = "hook", description = "Run a provider hook adapter.")
public final class HookCommand implements Runnable {
    @Spec private CommandSpec spec;
    /** Creates the hook parent command. */
    public HookCommand() { }
    /** Prints hook help. */
    @Override public void run() { spec.commandLine().usage(spec.commandLine().getOut()); }
}
