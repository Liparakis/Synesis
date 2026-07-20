package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** Root Picocli command; no arguments prints help. */
@Command(name = "synesis", mixinStandardHelpOptions = true, version = "synesis 0.1.0-SNAPSHOT")
public final class RootCommand implements Runnable {
    @Spec private CommandSpec spec;

    /** Creates the root command. */
    public RootCommand() { }

    /** Prints root help for an empty invocation. */
    @Override
    public void run() { spec.commandLine().usage(spec.commandLine().getOut()); }
}
