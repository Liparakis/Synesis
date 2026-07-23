package org.synesis.cli.command;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.CoordinationCommand;
import org.synesis.coordination.PredictionEventType;
import org.synesis.coordination.PredictionIntegrationGate;
import org.synesis.coordination.PredictionProjection;
import org.synesis.coordination.SpeculationWorkspace;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Evaluates prediction resolution plus the local Git safety gate. */
@Command(name = "gate", description = "Evaluate the prediction integration gate.", mixinStandardHelpOptions = true)
public final class IntegrationGateCommand implements Callable<Integer> {
    @Option(names = "--project") private Path project;
    @Option(names = "--endpoint", required = true) private URI endpoint;
    @Option(names = "--prediction", required = true) private UUID prediction;
    private final CliRuntime runtime;
    /** Creates an integration gate command.
     * @param runtime composed CLI runtime
     */
    public IntegrationGateCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Evaluates and prints the gate result. @return zero only when integration is accepted */
    @Override public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            PredictionProjection projection = new PredictionProjection();
            for (var event : CoordinationCliSupport.replay(endpoint, 0)) {
                if (event.predictionId().equals(prediction)) projection.apply(event);
            }
            String base = java.nio.file.Files.readAllLines(location.synesisDirectory().resolve("local").resolve("speculation")
                    .resolve(prediction.toString()).resolve("speculation.meta")).stream()
                    .filter(s -> s.startsWith("baseCommit=")).findFirst().orElseThrow().substring(11);
            SpeculationWorkspace workspace = new SpeculationWorkspace(location.root(), location.synesisDirectory().resolve("local"), prediction, base);
            PredictionIntegrationGate.Result result = PredictionIntegrationGate.evaluate(
                    projection.state(prediction).map(Enum::name).filter("AVAILABLE"::equals).isPresent(), workspace.gate());
            runtime.terminal().stdout(result.status());
            runtime.terminal().stdout("REASON=" + result.reason());
            return result.accepted() ? ExitCodes.OK : ExitCodes.LOCAL_CONFIGURATION;
        } catch (Exception failure) {
            runtime.terminal().stderr("INTEGRATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
