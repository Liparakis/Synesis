package org.synesis.cli.command.lifecycle;


import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.coordination.CoordinationCliSupport;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.prediction.PredictionIntegrationGate;
import org.synesis.coordination.domain.prediction.PredictionProjection;
import org.synesis.coordination.domain.speculation.SpeculationWorkspace;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.synesis.coordination.domain.prediction.PredictionIntegrationResult;

/**
 * Evaluates prediction resolution plus the local Git safety gate.
 */
@Command(name = "gate", description = "Evaluate the prediction integration gate.", mixinStandardHelpOptions = true)
@SuppressWarnings("resource")
public final class IntegrationGateCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory (default: current directory).")
    private Path project;
    @Option(names = "--endpoint", required = true, description = "Coordination server endpoint URI, such as http://127.0.0.1:48123/.")
    private URI endpoint;
    @Option(names = "--prediction", required = true, description = "ID of the prediction to gate.")
    private UUID prediction;

    /**
     * Creates an integration gate command.
     *
     * @param runtime composed CLI runtime
     */
    public IntegrationGateCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Evaluates and prints the gate result. @return zero only when integration is accepted
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            PredictionProjection projection = new PredictionProjection();
            for (var event : CoordinationCliSupport.replay(endpoint)) {
                if (event.predictionId().equals(prediction)) {
                    projection.apply(event);
                }
            }
            Path localRoot = location.synesisDirectory().resolve("local");
            String base = SpeculationWorkspace.recordedBaseCommit(localRoot, prediction).orElseThrow();
            SpeculationWorkspace workspace = new SpeculationWorkspace(location.root(), localRoot, prediction, base);
            PredictionIntegrationResult result = PredictionIntegrationGate.evaluate(projection.state(prediction).map(Enum::name).filter("AVAILABLE"::equals).isPresent(), workspace.gate());
            runtime.terminal().stdout(result.status());
            runtime.terminal().stdout("REASON=" + result.reason());
            return result.accepted() ? ExitCodes.OK : ExitCodes.LOCAL_CONFIGURATION;
        } catch (Exception failure) {
            return CoordinationCliSupport.fail(runtime, "INTEGRATION_ERROR", failure);
        }
    }
}
