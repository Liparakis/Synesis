package org.synesis.cli.command.status;

import java.net.URI;
import java.time.Clock;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.HumanTerminal;
import org.synesis.coordination.persistence.LoopbackExchange;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.transport.http.CoordinationHttpClient;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.transport.control.ProjectStatus;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Answers "what is going on in this project": the project, its runtime, each agent and the work it holds, what waits for
 * a person, and the project's other computers.
 *
 * <p>It only reads files ({@link ProjectStatus}), so it works while the project's runtime is stopped and changes
 * nothing. A person at a terminal gets one screen; {@code --porcelain}, a pipe or {@code SYNESIS_OUTPUT=porcelain}
 * gets the {@code STATUS_*} keys.</p>
 */
@Command(name = "status", mixinStandardHelpOptions = true,
        description = "Show what is going on: the agents and their work, what needs you, and the other computers.",
        footer = {"", "Reads files only, so it works while the project's runtime is stopped. --watch needs the runtime,",
                "whose event stream says when to draw again."})
public final class StatusCommand implements Callable<Integer> {

    /**
     * Follows a running server's event stream.
     */
    @FunctionalInterface
    interface EventWatch {

        /**
         * Calls {@code onEvent} for each event after a position until the stream ends.
         *
         * @param server  the server's loopback address
         * @param after   the last position already seen
         * @param onEvent what to do for each event
         * @throws Exception when the stream cannot be followed
         */
        void follow(URI server, long after, Runnable onEvent) throws Exception;
    }

    private static final EventWatch SERVER_EVENTS = (server, after, onEvent) -> new CoordinationHttpClient(server).follow(after, 0, _ -> onEvent.run());

    private final CliRuntime runtime;
    private final EventWatch events;
    private final Clock clock;
    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;
    @Option(names = "--watch", description = "Draw again whenever the project's event log gets an entry, until interrupted.")
    private boolean watch;

    /**
     * Creates the command.
     *
     * @param runtime composed CLI runtime
     */
    public StatusCommand(CliRuntime runtime) {
        this(runtime, SERVER_EVENTS, Clock.systemUTC());
    }

    StatusCommand(CliRuntime runtime, EventWatch events, Clock clock) {
        this.runtime = runtime;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Prints the status, and with {@code --watch} prints it again after each entry of the event log.
     *
     * @return process exit code
     */
    @Override
    public Integer call() {
        ProjectLocation location;
        try {
            location = ProjectOptions.locate(runtime, project);
            draw(location, false);
        } catch (Exception failure) {
            return ProjectOptions.refuse(runtime, failure);
        }
        if (!watch) {
            return ExitCodes.OK;
        }
        var server = new LoopbackExchange(location.coordinationDirectory()).runningServer();
        if (server.isEmpty()) {
            runtime.terminal().stderr("ERROR=WATCH_NEEDS_RUNTIME");
            runtime.terminal().stderr("MESSAGE=--watch follows the project's event stream, and the project's runtime is not running");
            runtime.terminal().stderr("FIX=synesis ui --no-browser");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
        try {
            events.follow(server.get(), PredictionEventStore.openReadOnly(location.coordinationDirectory(), location.projectId()).headSequence(), () -> {
                try {
                    draw(location, true);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("ERROR=STATUS_WATCH_FAILED");
            runtime.terminal().stderr("MESSAGE=" + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            return ExitCodes.COMMAND_FAILED;
        }
    }

    private void draw(ProjectLocation location, boolean clear) throws Exception {
        ProjectStatus status = ProjectStatus.read(location);
        boolean person = runtime.terminal() instanceof HumanTerminal;
        if (clear && person) {
            runtime.terminal().stdoutRaw("\u001B[H\u001B[2J");
        }
        (person ? StatusLines.screen(status, clock.instant()) : StatusLines.porcelain(status)).forEach(runtime.terminal()::stdout);
    }
}
