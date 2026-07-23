package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.link.identity.IdentityBootstrap;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Inspects one supervisor profile and its durable event cursor. */
@Command(name = "status", description = "Inspect one supervisor profile.", mixinStandardHelpOptions = true)
public final class SupervisorStatusCommand implements Callable<Integer> {
    @Option(names = "--profile", required = true) private Path profile;
    private final CliRuntime runtime;
    /** Creates a supervisor status command.
     * @param runtime composed CLI runtime
     */
    public SupervisorStatusCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Prints identity and cursor state.
     * @return stable process exit code
     */
    @Override public Integer call() {
        try {
            var inspection = new IdentityBootstrap(profile.toAbsolutePath().normalize().resolve("link")).inspect();
            runtime.terminal().stdout("SUPERVISOR_PROFILE=" + profile.toAbsolutePath().normalize());
            runtime.terminal().stdout("NODE_ID=" + inspection.nodeId());
            runtime.terminal().stdout("IDENTITY=" + inspection.detail());
            runtime.terminal().stdout("CURSOR=" + CoordinationEventFollower.readCursor(profile.resolve("coordination.cursor")));
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("SUPERVISOR_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
