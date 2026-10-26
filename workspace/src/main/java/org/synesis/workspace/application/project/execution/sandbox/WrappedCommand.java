package org.synesis.workspace.application.project.execution.sandbox;

import java.util.List;
import java.util.Map;

/**
 * The command line and environment to start for one confined run.
 *
 * @param argv        executable and arguments
 * @param environment complete environment of the started process
 */
public record WrappedCommand(List<String> argv, Map<String, String> environment) {

    /**
     * Copies the collections.
     */
    public WrappedCommand {
        argv = List.copyOf(argv);
        environment = Map.copyOf(environment);
    }
}
