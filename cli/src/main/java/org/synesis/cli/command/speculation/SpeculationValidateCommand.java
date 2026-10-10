package org.synesis.cli.command.speculation;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.UUID;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.coordination.CoordinationCliSupport;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.speculation.SpeculationGateResult;
import org.synesis.coordination.domain.speculation.SpeculationWorkspace;

import org.synesis.workspace.application.project.ProjectLocation;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Runs the local speculation gate and records requester validation.
 */
@Command(name = "validate", description = "Validate an isolated prediction worktree.", mixinStandardHelpOptions = true)
@SuppressWarnings("resource")
public final class SpeculationValidateCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory (default: current directory).")
    private Path project;
    @Option(names = "--endpoint", required = true, description = "Coordination server endpoint URI, such as http://127.0.0.1:48123/.")
    private URI endpoint;
    @Option(names = "--profile", required = true, description = "Node profile directory holding the signing identity.")
    private Path profile;
    @Option(names = "--prediction", required = true, description = "ID of the prediction whose worktree is validated.")
    private UUID prediction;

    /**
     * Creates a validation command.
     *
     * @param runtime composed CLI runtime
     */
    public SpeculationValidateCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    private static String readBaseCommit(ProjectLocation location, UUID prediction) throws java.io.IOException {
        return SpeculationWorkspace.recordedBaseCommit(location.synesisDirectory().resolve("local"), prediction)
                .orElseThrow(() -> new IllegalArgumentException("SPECULATION_METADATA_INVALID"));
    }

    /**
     * Gates the worktree and appends validation when the gate passes. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            SpeculationWorkspace workspace = new SpeculationWorkspace(location.root(), location.synesisDirectory().resolve("local"), prediction, readBaseCommit(location, prediction));
            SpeculationGateResult gate = workspace.gate();
            runtime.terminal().stdout(gate.status());
            if (!gate.accepted()) {
                return ExitCodes.LOCAL_CONFIGURATION;
            }
            new CoordinationCliSupport.Signer(location, CoordinationCliSupport.loadIdentity(profile)).submit(endpoint, prediction, PredictionEventType.VALIDATION_STARTED, new byte[0]);
            runtime.terminal().stdout("VALIDATION_STARTED=true");
            return ExitCodes.OK;
        } catch (Exception failure) {
            return CoordinationCliSupport.fail(runtime, "SPECULATION_ERROR", failure);
        }
    }
}
