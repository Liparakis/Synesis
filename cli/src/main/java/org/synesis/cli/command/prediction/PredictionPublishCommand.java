package org.synesis.cli.command.prediction;


import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.coordination.CoordinationCliSupport;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Publishes owner implementation milestones for one prediction.
 */
@Command(name = "publish", description = "Publish implementation, patch-ready, or available capability state.", mixinStandardHelpOptions = true)
public final class PredictionPublishCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory (default: current directory).")
    private Path project;
    @Option(names = "--endpoint", required = true, description = "Coordination server endpoint URI, such as http://127.0.0.1:48123/.")
    private URI endpoint;
    @Option(names = "--profile", required = true, description = "Node profile directory holding the signing identity.")
    private Path profile;
    @Option(names = "--supervisor", required = true, description = "ID of the publishing supervisor.")
    private String supervisor;
    @Option(names = "--worker", required = true, description = "ID of the publishing worker.")
    private String worker;
    @Option(names = "--prediction", required = true, description = "ID of the prediction to publish state for.")
    private UUID prediction;
    @Option(names = "--stage", required = true, description = "implementation-started|patch-ready|available")
    private String stage;
    @Option(names = "--commit", required = true, description = "Commit identifier recorded with the published state.")
    private String commit;

    /**
     * Creates a publish command.
     *
     * @param runtime composed CLI runtime
     */
    public PredictionPublishCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Publishes one owner milestone.
     *
     * @return stable process exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            var identity = CoordinationCliSupport.loadIdentity(profile);
            PredictionEventType type = switch (stage.toLowerCase(java.util.Locale.ROOT)) {
                case "implementation-started" -> PredictionEventType.IMPLEMENTATION_STARTED;
                case "patch-ready" -> PredictionEventType.PATCH_READY;
                case "available" -> PredictionEventType.CAPABILITY_AVAILABLE;
                default -> throw new IllegalArgumentException("INVALID_PUBLISH_STAGE");
            };
            var event = CoordinationCliSupport.submit(endpoint,
                    CoordinationCommand.createAs(UUID.randomUUID(), location.projectId(), prediction, type, identity.nodeId(), supervisor, worker, commit.getBytes(StandardCharsets.UTF_8), identity));
            runtime.terminal().stdout("PREDICTION_PUBLISHED=" + type);
            runtime.terminal().stdout("PREDICTION_ID=" + prediction);
            runtime.terminal().stdout("PROJECT_SEQUENCE=" + event.sequence());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("PREDICTION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
