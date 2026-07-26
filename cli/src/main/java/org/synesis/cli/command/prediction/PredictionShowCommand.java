package org.synesis.cli.command.prediction;


import org.synesis.cli.command.coordination.CoordinationCliSupport;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.prediction.PredictionContract;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.prediction.PredictionProjection;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Shows one prediction and its reconstructed lifecycle state.
 */
@Command(name = "show", description = "Show one prediction.", mixinStandardHelpOptions = true)
public final class PredictionShowCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project")
    private Path project;
    @Option(names = "--endpoint", required = true)
    private URI endpoint;
    @Option(names = "--prediction", required = true)
    private UUID prediction;

    /**
     * Creates a prediction show command.
     *
     * @param runtime composed CLI runtime
     */
    public PredictionShowCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Replays and prints one prediction.
     *
     * @return stable process exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            PredictionProjection projection = new PredictionProjection();
            PredictionContract contract = null;
            long sequence = 0;
            for (var event : CoordinationCliSupport.replay(endpoint, 0)) {
                if (!event.predictionId()
                        .equals(prediction)) {
                    continue;
                }
                sequence = event.sequence();
                projection.apply(event);
                if (event.type() == PredictionEventType.PREDICTION_CREATED) {
                    contract = PredictionContract.decode(CoordinationCommand.decode(event.payload())
                            .payload());
                }
            }
            if (contract == null) {
                throw new IllegalArgumentException("PREDICTION_NOT_FOUND");
            }
            runtime.terminal()
                    .stdout("PREDICTION_ID=" + prediction);
            runtime.terminal()
                    .stdout("PROJECT_ID=" + location.projectId());
            runtime.terminal()
                    .stdout("STATE=" + projection.state(prediction)
                            .orElseThrow());
            runtime.terminal()
                    .stdout("CAPABILITY=" + contract.owningCapability());
            runtime.terminal()
                    .stdout("OWNER_NODE=" + contract.ownerNodeId());
            runtime.terminal()
                    .stdout("OWNER_SUPERVISOR=" + contract.ownerSupervisorId());
            runtime.terminal()
                    .stdout("PROJECT_SEQUENCE=" + sequence);
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("PREDICTION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
