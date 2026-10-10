package org.synesis.cli.command.ownership;


import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.coordination.CoordinationCliSupport;
import org.synesis.cli.exit.ExitCodes;
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
    @Option(names = "--project", description = "Initialized project directory (default: current directory).")
    private Path project;
    @Option(names = "--endpoint", required = true, description = "Coordination server endpoint URI, such as http://127.0.0.1:48123/.")
    private URI endpoint;
    @Option(names = "--profile", required = true, description = "Node profile directory holding the signing identity.")
    private Path profile;
    @Option(names = "--supervisor", required = true, description = "ID of the supervisor that will own the capability.")
    private String supervisor;
    @Option(names = "--task", required = true, description = "ID of the task the claim belongs to.")
    private UUID taskId;
    @Option(names = "--capability", required = true, description = "Semantic capability name to claim.")
    private String capability;
    @Option(names = "--scope", required = true, split = ",", description = "Comma-separated protected scope patterns.")
    private List<String> scopes;
    @Option(names = "--intent-version", defaultValue = "1", description = "Non-negative ownership intent version (default: 1).")
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
            var signer = CoordinationCliSupport.Signer.resolve(runtime, project, profile);
            OwnershipClaim claim = new OwnershipClaim(taskId, capability, signer.identity().nodeId(), supervisor, scopes, intentVersion);
            var event = signer.submit(endpoint, taskId, PredictionEventType.OWNERSHIP_CLAIMED, supervisor, "owner", claim.encoded());
            runtime.terminal().stdout("OWNERSHIP_CLAIMED=true");
            runtime.terminal().stdout("CAPABILITY=" + capability);
            runtime.terminal().stdout("PROJECT_SEQUENCE=" + event.sequence());
            return ExitCodes.OK;
        } catch (Exception failure) {
            return CoordinationCliSupport.fail(runtime, "OWNERSHIP_ERROR", failure);
        }
    }
}
