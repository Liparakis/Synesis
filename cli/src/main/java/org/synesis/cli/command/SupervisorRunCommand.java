package org.synesis.cli.command;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.link.identity.IdentityBootstrap;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Runs one foreground supervisor event consumer with a durable cursor. */
@Command(name = "run", description = "Follow coordination events for one supervisor.", mixinStandardHelpOptions = true)
public final class SupervisorRunCommand implements Callable<Integer> {
    @Option(names = "--project") private Path project;
    @Option(names = "--endpoint", required = true) private URI endpoint;
    @Option(names = "--profile", required = true) private Path profile;
    @Option(names = "--supervisor", required = true) private String supervisor;
    @Option(names = "--worker", required = true) private String worker;
    @Option(names = "--cursor") private Path cursor;
    @Option(names = "--duration-seconds", defaultValue = "0") private int durationSeconds;
    private final CliRuntime runtime;
    /** Creates a supervisor run command.
     * @param runtime composed CLI runtime
     */
    public SupervisorRunCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Follows ordered events and persists the cursor.
     * @return stable process exit code
     */
    @Override public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            var identity = CoordinationCliSupport.loadIdentity(profile);
            Path cursorPath = cursor == null ? profile.resolve("coordination.cursor") : cursor;
            runtime.terminal().stdout("SUPERVISOR_READY supervisor=" + supervisor + " worker=" + worker
                    + " nodeId=" + identity.nodeId() + " cursor=" + CoordinationEventFollower.readCursor(cursorPath));
            CoordinationEventFollower.follow(endpoint, cursorPath, durationSeconds, event -> runtime.terminal().stdout(
                    "EVENT sequence=" + event.sequence() + " type=" + event.type() + " prediction=" + event.predictionId()));
            runtime.terminal().stdout("SUPERVISOR_STOPPED project=" + location.projectId()
                    + " cursor=" + CoordinationEventFollower.readCursor(cursorPath));
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("SUPERVISOR_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
