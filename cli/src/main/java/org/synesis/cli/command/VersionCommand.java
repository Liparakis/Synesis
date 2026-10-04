package org.synesis.cli.command;

import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import picocli.CommandLine.Command;

/**
 * Emits build metadata embedded in the application bundle.
 */
@Command(name = "version", description = "Show build and protocol metadata.", mixinStandardHelpOptions = true)
public final class VersionCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public VersionCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Prints bounded build metadata without requiring Git or a project. @return zero
     */
    @Override
    public Integer call() {
        runtime.terminal().stdout(BuildInfo.text());
        return ExitCodes.OK;
    }
}
