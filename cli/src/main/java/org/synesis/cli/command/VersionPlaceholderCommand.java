package org.synesis.cli.command;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;

/** Emits the development version placeholder until SYN-009C injects build metadata. */
@Command(name = "version-placeholder", description = "Show the development version placeholder.", mixinStandardHelpOptions = true)
public final class VersionPlaceholderCommand implements Callable<Integer> {
    private final CliRuntime runtime;
    /**
     * Creates the command.
     * @param runtime composed runtime
     */
    public VersionPlaceholderCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Prints a static development version. @return zero */
    @Override public Integer call() {
        runtime.terminal().stdout("VERSION=DEVELOPMENT");
        return ExitCodes.OK;
    }
}
