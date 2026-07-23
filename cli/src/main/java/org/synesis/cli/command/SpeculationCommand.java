package org.synesis.cli.command;

import picocli.CommandLine.Command;

/** Parent for isolated speculative worktree lifecycle commands. */
@Command(name = "speculation", description = "Prepare and gate isolated prediction worktrees.",
        mixinStandardHelpOptions = true)
public final class SpeculationCommand implements Runnable {
    /** Creates the speculation command parent. */
    public SpeculationCommand() { }
    /** Runs the parent help command. */
    @Override public void run() { }
}
