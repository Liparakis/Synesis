package org.synesis.cli.command;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Follows coordination events with a durable cursor.
 */
@Command(name = "follow", description = "Follow live coordination events.", mixinStandardHelpOptions = true)
public final class EventsFollowCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project")
    private Path project;
    @Option(names = "--endpoint", required = true)
    private URI endpoint;
    @Option(names = "--cursor", required = true)
    private Path cursor;
    @Option(names = "--duration-seconds", defaultValue = "0")
    private int durationSeconds;

    /**
     * Creates an event follow command.
     *
     * @param runtime composed CLI runtime
     */
    public EventsFollowCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Follows the stream and updates the cursor.
     *
     * @return stable process exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            CoordinationEventFollower.follow(endpoint,
                    cursor,
                    durationSeconds,
                    event -> runtime.terminal()
                            .stdout(
                                    "EVENT sequence=" + event.sequence() + " type=" + event.type() + " prediction="
                                            + event.predictionId()));
            runtime.terminal()
                    .stdout("EVENTS_FOLLOW_COMPLETE project=" + location.projectId()
                            + " cursor=" + CoordinationEventFollower.readCursor(cursor));
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("EVENTS_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
