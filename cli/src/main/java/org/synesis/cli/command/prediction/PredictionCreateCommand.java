package org.synesis.cli.command.prediction;


import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.coordination.CoordinationCliSupport;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.prediction.PredictionContract;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Creates and routes one exact capability prediction.
 */
@Command(name = "create", description = "Create and route a prediction.", mixinStandardHelpOptions = true)
public final class PredictionCreateCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory (default: current directory).")
    private Path project;
    @Option(names = "--endpoint", required = true, description = "Coordination server endpoint URI, such as http://127.0.0.1:48123/.")
    private URI endpoint;
    @Option(names = "--profile", required = true, description = "Node profile directory holding the signing identity.")
    private Path profile;
    @Option(names = "--supervisor", required = true, description = "ID of the requesting supervisor.")
    private String supervisor;
    @Option(names = "--worker", required = true, description = "ID of the requesting worker.")
    private String worker;
    @Option(names = "--task", required = true, description = "ID of the requesting task.")
    private UUID taskId;
    @Option(names = "--capability", required = true, description = "Semantic capability name the prediction is about.")
    private String capability;
    @Option(names = "--owner-node", required = true, description = "Node ID of the capability owner.")
    private String ownerNode;
    @Option(names = "--owner-supervisor", required = true, description = "Supervisor ID of the capability owner.")
    private String ownerSupervisor;
    @Option(names = "--scope", required = true, split = ",", description = "Comma-separated protected scope patterns.")
    private List<String> scopes;
    @Option(names = "--base-commit", required = true, description = "Base commit the prediction is made against.")
    private String baseCommit;
    @Option(names = "--base-scope-hash", required = true, split = ",", description = "Comma-separated scope hashes recorded as the prediction base.")
    private List<String> baseScopeHashes;
    @Option(names = "--project-sequence", defaultValue = "0", description = "Project event sequence used as the base (default: 0).")
    private long projectSequence;
    @Option(names = "--intent-version", defaultValue = "1", description = "Owner's intent version the prediction is based on (default: 1).")
    private long intentVersion;
    @Option(names = "--purpose", required = true, description = "Purpose of the predicted capability.")
    private String purpose;
    @Option(names = "--inputs", required = true, description = "Input contract of the capability.")
    private String inputs;
    @Option(names = "--outputs", required = true, description = "Output contract of the capability.")
    private String outputs;
    @Option(names = "--behavior", required = true, description = "Behavior contract of the capability.")
    private String behavior;
    @Option(names = "--errors", required = true, description = "Error semantics of the capability.")
    private String errors;
    @Option(names = "--side-effects", required = true, description = "Side effects of the capability.")
    private String sideEffects;
    @Option(names = "--invariants", required = true, description = "Invariants the capability must hold.")
    private String invariants;
    @Option(names = "--compatibility", required = true, description = "Compatibility requirements of the capability.")
    private String compatibility;
    @Option(names = "--performance", required = true, description = "Performance requirements of the capability.")
    private String performance;
    @Option(names = "--concurrency", required = true, description = "Concurrency requirements of the capability.")
    private String concurrency;
    @Option(names = "--acceptance-test", required = true, split = ",", description = "Comma-separated acceptance tests.")
    private List<String> acceptanceTests;
    @Option(names = "--confidence", defaultValue = "80", description = "Confidence score from 0 to 100 (default: 80).")
    private int confidence;
    @Option(names = "--risk", defaultValue = "20", description = "Speculation risk score from 0 to 100 (default: 20).")
    private int risk;
    @Option(names = "--expires-at", description = "Epoch milliseconds; defaults to ten minutes from now.")
    private long expiresAt;

    /**
     * Creates a prediction command.
     *
     * @param runtime composed CLI runtime
     */
    public PredictionCreateCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Submits the signed prediction and route commands.
     *
     * @return stable process exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            var identity = CoordinationCliSupport.loadIdentity(profile);
            if (expiresAt == 0) {
                expiresAt = System.currentTimeMillis() + 600_000L;
            }
            UUID predictionId = UUID.randomUUID();
            PredictionContract contract = new PredictionContract(predictionId,
                    location.projectId(),
                    identity.nodeId(),
                    supervisor,
                    worker,
                    taskId,
                    capability,
                    ownerNode,
                    ownerSupervisor,
                    scopes,
                    projectSequence,
                    baseCommit,
                    baseScopeHashes,
                    intentVersion,
                    purpose,
                    inputs,
                    outputs,
                    behavior,
                    errors,
                    sideEffects,
                    invariants,
                    compatibility,
                    performance,
                    concurrency,
                    acceptanceTests,
                    confidence,
                    risk,
                    expiresAt);
            var created = CoordinationCliSupport.submit(endpoint,
                    CoordinationCommand.createAs(UUID.randomUUID(), location.projectId(), predictionId, PredictionEventType.PREDICTION_CREATED, identity.nodeId(), supervisor, worker, contract.encoded(), identity));
            var routed = CoordinationCliSupport.submit(endpoint,
                    CoordinationCommand.createAs(UUID.randomUUID(), location.projectId(), predictionId, PredictionEventType.PREDICTION_ROUTED, identity.nodeId(), supervisor, worker, new byte[0], identity));
            runtime.terminal().stdout("PREDICTION_CREATED=true");
            runtime.terminal().stdout("PREDICTION_ID=" + predictionId);
            runtime.terminal().stdout("REQUEST_ID=" + created.eventId());
            runtime.terminal().stdout("CREATE_SEQUENCE=" + created.sequence());
            runtime.terminal().stdout("ROUTED_SEQUENCE=" + routed.sequence());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("PREDICTION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
