package org.synesis.cli.command.ownership;


import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.coordination.CoordinationCliSupport;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Claims one capability for the owner of a task.
 */
@Command(name = "claim", description = "Claim semantic ownership.", mixinStandardHelpOptions = true)
public final class OwnershipClaimCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project")
    private Path project;
    @Option(names = "--endpoint", required = true)
    private URI endpoint;
    @Option(names = "--profile", required = true)
    private Path profile;
    @Option(names = "--supervisor", required = true)
    private String supervisor;
    @Option(names = "--task", required = true)
    private UUID taskId;
    @Option(names = "--capability", required = true)
    private String capability;
    @Option(names = "--scope", required = true, split = ",")
    private List<String> scopes;
    @Option(names = "--intent-version", defaultValue = "1")
    private long intentVersion;

    /**
     * Creates an ownership claim command.
     *
     * @param runtime composed CLI runtime
     */
    public OwnershipClaimCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Submits the signed ownership claim.
     *
     * @return stable process exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            var identity = CoordinationCliSupport.loadIdentity(profile);
            OwnershipClaim claim = new OwnershipClaim(taskId,
                    capability,
                    identity.nodeId(),
                    supervisor,
                    scopes,
                    intentVersion);
            var command = CoordinationCommand.createAs(UUID.randomUUID(),
                    location.projectId(),
                    taskId,
                    PredictionEventType.OWNERSHIP_CLAIMED,
                    identity.nodeId(),
                    supervisor,
                    "owner",
                    claim.encoded(),
                    identity);
            var event = CoordinationCliSupport.submit(endpoint, command);
            runtime.terminal()
                    .stdout("OWNERSHIP_CLAIMED=true");
            runtime.terminal()
                    .stdout("CAPABILITY=" + capability);
            runtime.terminal()
                    .stdout("PROJECT_SEQUENCE=" + event.sequence());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("OWNERSHIP_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
