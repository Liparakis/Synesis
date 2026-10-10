package org.synesis.cli.command.task;


import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.CoordinationCliSupport;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskClaim;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Claims one unassigned coordination task.
 */
@Command(name = "claim", description = "Claim a coordination task.", mixinStandardHelpOptions = true)
public final class TaskClaimCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory.")
    private Path project;
    @Option(names = "--endpoint", required = true, description = "Coordination server endpoint URI, such as http://127.0.0.1:48123/.")
    private URI endpoint;
    @Option(names = "--profile", required = true, description = "Node profile directory holding the signing identity.")
    private Path profile;
    @Option(names = "--supervisor", required = true, description = "ID of the claiming supervisor.")
    private String supervisor;
    @Option(names = "--worker", required = true, description = "ID of the claiming worker.")
    private String worker;
    @Option(names = "--task", required = true, description = "ID of the task to claim.")
    private UUID taskId;

    /**
     * Creates a task claim command.
     *
     * @param runtime composed CLI runtime
     */
    public TaskClaimCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Claims the task through a signed command.
     *
     * @return stable process exit code
     */
    @Override
    public Integer call() {
        try {
            var signer = CoordinationCliSupport.Signer.resolve(runtime, project, profile);
            TaskClaim claim = new TaskClaim(taskId, signer.identity().nodeId(), supervisor, worker);
            var event = signer.submit(endpoint, taskId, PredictionEventType.TASK_CLAIMED, supervisor, worker, claim.encoded());
            runtime.terminal().stdout("TASK_CLAIMED=true");
            runtime.terminal().stdout("TASK_ID=" + taskId);
            runtime.terminal().stdout("PROJECT_SEQUENCE=" + event.sequence());
            return ExitCodes.OK;
        } catch (Exception failure) {
            return CoordinationCliSupport.fail(runtime, "TASK_ERROR", failure);
        }
    }
}
