package org.synesis.cli.command.task;


import org.synesis.cli.command.coordination.CoordinationCliSupport;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.task.CoordinationTask;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskClaim;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Shows one task reconstructed from coordinator replay.
 */
@Command(name = "show", description = "Show one task.", mixinStandardHelpOptions = true)
public final class TaskShowCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory.")
    private Path project;
    @Option(names = "--endpoint", required = true)
    private URI endpoint;
    @Option(names = "--task", required = true)
    private UUID taskId;

    /**
     * Creates a task show command.
     *
     * @param runtime composed CLI runtime
     */
    public TaskShowCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Replays task events and prints the current claim.
     *
     * @return stable process exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            CoordinationTask task = null;
            TaskClaim claim = null;
            for (var event : CoordinationCliSupport.replay(endpoint, 0)) {
                if (!event.predictionId()
                        .equals(taskId)) {
                    continue;
                }
                var command = CoordinationCommand.decode(event.payload());
                if (event.type() == PredictionEventType.TASK_CREATED) {
                    task = CoordinationTask.decode(command.payload());
                }
                if (event.type() == PredictionEventType.TASK_CLAIMED) {
                    claim = TaskClaim.decode(command.payload());
                }
                if (event.type() == PredictionEventType.TASK_RELEASED) {
                    claim = null;
                }
            }
            if (task == null) {
                throw new IllegalArgumentException("TASK_NOT_FOUND");
            }
            runtime.terminal()
                    .stdout("TASK_ID=" + task.taskId());
            runtime.terminal()
                    .stdout("PROJECT_ID=" + location.projectId());
            runtime.terminal()
                    .stdout("TITLE=" + task.title());
            runtime.terminal()
                    .stdout("CAPABILITY=" + task.capability());
            runtime.terminal()
                    .stdout("OWNER_NODE=" + (claim == null ? "UNCLAIMED" : claim.ownerNodeId()));
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("TASK_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
