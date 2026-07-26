package org.synesis.cli.command.task;


import org.synesis.cli.command.coordination.CoordinationCliSupport;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.CoordinationCommand;
import org.synesis.coordination.domain.CoordinationTask;
import org.synesis.coordination.domain.PredictionEventType;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Creates one signed coordination task.
 */
@Command(name = "create", description = "Create a claimable coordination task.", mixinStandardHelpOptions = true)
public final class TaskCreateCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory.")
    private Path project;
    @Option(names = "--endpoint", required = true)
    private URI endpoint;
    @Option(names = "--profile", required = true)
    private Path profile;
    @Option(names = "--supervisor", required = true)
    private String supervisor;
    @Option(names = "--worker", required = true)
    private String worker;
    @Option(names = "--title", required = true)
    private String title;
    @Option(names = "--capability", required = true)
    private String capability;

    /**
     * Creates a task command.
     *
     * @param runtime composed CLI runtime
     */
    public TaskCreateCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Creates the task through the coordinator.
     *
     * @return stable process exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            var identity = CoordinationCliSupport.loadIdentity(profile);
            UUID taskId = UUID.randomUUID();
            CoordinationTask task = new CoordinationTask(taskId, location.projectId(), title, capability,
                    identity.nodeId(), supervisor, worker);
            var command = CoordinationCommand.createAs(UUID.randomUUID(), location.projectId(), taskId,
                    PredictionEventType.TASK_CREATED, identity.nodeId(), supervisor, worker, task.encoded(), identity);
            var event = CoordinationCliSupport.submit(CoordinationCliSupport.endpoint(endpoint), command);
            runtime.terminal()
                    .stdout("TASK_CREATED=true");
            runtime.terminal()
                    .stdout("TASK_ID=" + taskId);
            runtime.terminal()
                    .stdout("PROJECT_SEQUENCE=" + event.sequence());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("TASK_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
