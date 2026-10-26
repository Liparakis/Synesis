package org.synesis.workspace.application.project.execution.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The command line and environment to start for one confined run.
 *
 * @param argv        executable and arguments
 * @param environment complete environment of the started process
 * @param hosting     how to start the process and what to release when the run is over, or {@code null} when the
 *                    caller starts {@code argv} itself (a sandbox that wraps the command line, such as bubblewrap)
 */
public record WrappedCommand(List<String> argv, Map<String, String> environment, Hosting hosting) {

    /**
     * Copies the collections.
     */
    public WrappedCommand {
        argv = List.copyOf(argv);
        environment = Map.copyOf(environment);
    }

    /**
     * Creates a command the caller starts itself.
     *
     * @param argv        executable and arguments
     * @param environment complete environment
     */
    public WrappedCommand(List<String> argv, Map<String, String> environment) {
        this(argv, environment, null);
    }

    /**
     * A sandbox that confines by how the process is created, not by wrapping the command line: Windows starts the
     * process with a lowered token, which no command-line prefix can do.
     */
    public interface Hosting {

        /**
         * Starts the confined process.
         *
         * @param argv             executable and arguments
         * @param workingDirectory working directory
         * @param environment      complete environment
         * @return the running process, whose kill ends everything it started
         * @throws IOException if the process cannot be started
         */
        Process start(List<String> argv, Path workingDirectory, Map<String, String> environment) throws IOException;

        /**
         * Ends the run: stops whatever the command left behind and undoes what the sandbox set up for it. Called
         * once, after the process ended or was killed, whether or not it was started.
         */
        void finish();
    }
}
