package org.synesis.cli.command.task;


import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.coordination.CoordinationCliSupport;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CoordinationTask;
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
    @Option(names = "--endpoint", required = true, description = "Coordination server endpoint URI, such as http://127.0.0.1:48123/.")
    private URI endpoint;
    @Option(names = "--profile", required = true, description = "Node profile directory holding the signing identity.")
    private Path profile;
    @Option(names = "--supervisor", required = true, description = "ID of the creating supervisor.")
    private String supervisor;
    @Option(names = "--worker", required = true, description = "ID of the creating worker.")
    private String worker;
    @Option(names = "--title", required = true, description = "Human-readable task title.")
    private String title;
    @Option(names = "--capability", required = true, description = "Capability the task requests.")
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
            var signer = CoordinationCliSupport.Signer.resolve(runtime, project, profile);
            UUID taskId = UUID.randomUUID();
            CoordinationTask task = new CoordinationTask(taskId, signer.location().projectId(), title, capability, signer.identity().nodeId(), supervisor, worker);
            var event = signer.submit(endpoint, taskId, PredictionEventType.TASK_CREATED, supervisor, worker, task.encoded());
            runtime.terminal().stdout("TASK_CREATED=true");
            runtime.terminal().stdout("TASK_ID=" + taskId);
            runtime.terminal().stdout("PROJECT_SEQUENCE=" + event.sequence());
            return ExitCodes.OK;
        } catch (Exception failure) {
            return CoordinationCliSupport.fail(runtime, "TASK_ERROR", failure);
        }
    }
}
