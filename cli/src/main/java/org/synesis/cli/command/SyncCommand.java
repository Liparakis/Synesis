package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Parent for bounded project synchronization. */
@Command(name = "sync", description = "Synchronize a configured project.")
public final class SyncCommand implements Runnable {
    @Spec private CommandSpec spec;
    /** Creates the sync parent command. */
    public SyncCommand() { }
    /** Prints sync help. */
    @Override public void run() { spec.commandLine().usage(spec.commandLine().getOut()); }
}
