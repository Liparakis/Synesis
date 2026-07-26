package org.synesis.cli.command.prediction;


import org.synesis.cli.command.coordination.CoordinationCliSupport;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
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
    @Option(names = "--project")
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
    @Option(names = "--capability", required = true)
    private String capability;
    @Option(names = "--owner-node", required = true)
    private String ownerNode;
    @Option(names = "--owner-supervisor", required = true)
    private String ownerSupervisor;
    @Option(names = "--scope", required = true, split = ",")
    private List<String> scopes;
    @Option(names = "--base-commit", required = true)
    private String baseCommit;
    @Option(names = "--base-scope-hash", required = true, split = ",")
    private List<String> baseScopeHashes;
    @Option(names = "--project-sequence", defaultValue = "0")
    private long projectSequence;
    @Option(names = "--intent-version", defaultValue = "1")
    private long intentVersion;
    @Option(names = "--purpose", required = true)
    private String purpose;
    @Option(names = "--inputs", required = true)
    private String inputs;
    @Option(names = "--outputs", required = true)
    private String outputs;
    @Option(names = "--behavior", required = true)
    private String behavior;
    @Option(names = "--errors", required = true)
    private String errors;
    @Option(names = "--side-effects", required = true)
    private String sideEffects;
    @Option(names = "--invariants", required = true)
    private String invariants;
    @Option(names = "--compatibility", required = true)
    private String compatibility;
    @Option(names = "--performance", required = true)
    private String performance;
    @Option(names = "--concurrency", required = true)
    private String concurrency;
    @Option(names = "--acceptance-test", required = true, split = ",")
    private List<String> acceptanceTests;
    @Option(names = "--confidence", defaultValue = "80")
    private int confidence;
    @Option(names = "--risk", defaultValue = "20")
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
            PredictionContract contract = new PredictionContract(predictionId, location.projectId(), identity.nodeId(),
                    supervisor, worker, taskId, capability, ownerNode, ownerSupervisor, scopes, projectSequence,
                    baseCommit, baseScopeHashes, intentVersion, purpose, inputs, outputs, behavior, errors,
                    sideEffects, invariants, compatibility, performance, concurrency, acceptanceTests, confidence,
                    risk, expiresAt);
            var created = CoordinationCliSupport.submit(endpoint, CoordinationCommand.createAs(UUID.randomUUID(),
                    location.projectId(), predictionId, PredictionEventType.PREDICTION_CREATED, identity.nodeId(),
                    supervisor, worker, contract.encoded(), identity));
            var routed = CoordinationCliSupport.submit(endpoint, CoordinationCommand.createAs(UUID.randomUUID(),
                    location.projectId(), predictionId, PredictionEventType.PREDICTION_ROUTED, identity.nodeId(),
                    supervisor, worker, new byte[0], identity));
            runtime.terminal()
                    .stdout("PREDICTION_CREATED=true");
            runtime.terminal()
                    .stdout("PREDICTION_ID=" + predictionId);
            runtime.terminal()
                    .stdout("REQUEST_ID=" + created.eventId());
            runtime.terminal()
                    .stdout("CREATE_SEQUENCE=" + created.sequence());
            runtime.terminal()
                    .stdout("ROUTED_SEQUENCE=" + routed.sequence());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("PREDICTION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
