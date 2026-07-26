package org.synesis.cli.command.prediction;


import org.synesis.cli.command.coordination.CoordinationCliSupport;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.CoordinationCommand;
import org.synesis.coordination.domain.PredictionEventType;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Publishes owner implementation milestones for one prediction.
 */
@Command(name = "publish", description = "Publish implementation, patch-ready, or available capability state.", mixinStandardHelpOptions = true)
public final class PredictionPublishCommand implements Callable<Integer> {

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
    @Option(names = "--prediction", required = true)
    private UUID prediction;
    @Option(names = "--stage", required = true, description = "implementation-started|patch-ready|available")
    private String stage;
    @Option(names = "--commit", required = true)
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
            var event = CoordinationCliSupport.submit(endpoint, CoordinationCommand.createAs(UUID.randomUUID(),
                    location.projectId(), prediction, type, identity.nodeId(), supervisor, worker,
                    commit.getBytes(StandardCharsets.UTF_8), identity));
            runtime.terminal()
                    .stdout("PREDICTION_PUBLISHED=" + type);
            runtime.terminal()
                    .stdout("PREDICTION_ID=" + prediction);
            runtime.terminal()
                    .stdout("PROJECT_SEQUENCE=" + event.sequence());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("PREDICTION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
