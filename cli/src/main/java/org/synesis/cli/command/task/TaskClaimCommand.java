package org.synesis.cli.command.task;


import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.coordination.CoordinationCliSupport;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskClaim;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Claims one unassigned coordination task.
 */
@Command(name = "claim", description = "Claim a coordination task.", mixinStandardHelpOptions = true)
public final class TaskClaimCommand implements Callable<Integer> {

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
    @Option(names = "--task", required = true)
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
            var location = CoordinationCliSupport.project(runtime, project);
            var identity = CoordinationCliSupport.loadIdentity(profile);
            TaskClaim claim = new TaskClaim(taskId, identity.nodeId(), supervisor, worker);
            var command = CoordinationCommand.createAs(UUID.randomUUID(), location.projectId(), taskId,
                    PredictionEventType.TASK_CLAIMED, identity.nodeId(), supervisor, worker, claim.encoded(), identity);
            var event = CoordinationCliSupport.submit(CoordinationCliSupport.endpoint(endpoint), command);
            runtime.terminal()
                    .stdout("TASK_CLAIMED=true");
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
