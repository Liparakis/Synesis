package org.synesis.cli.command;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.CoordinationCommand;
import org.synesis.coordination.PredictionEventType;
import org.synesis.coordination.SpeculationWorkspace;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Runs the local speculation gate and records requester validation.
 */
@Command(name = "validate", description = "Validate an isolated prediction worktree.", mixinStandardHelpOptions = true)
public final class SpeculationValidateCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project")
    private Path project;
    @Option(names = "--endpoint", required = true)
    private URI endpoint;
    @Option(names = "--profile", required = true)
    private Path profile;
    @Option(names = "--prediction", required = true)
    private UUID prediction;

    /**
     * Creates a validation command.
     *
     * @param runtime composed CLI runtime
     */
    public SpeculationValidateCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    private static String readBaseCommit(org.synesis.workspace.application.ProjectApplicationService.ProjectLocation location,
            UUID prediction) throws java.io.IOException {
        Path metadata = location.synesisDirectory()
                .resolve("local")
                .resolve("speculation")
                .resolve(prediction.toString())
                .resolve("speculation.meta");
        for (String line : java.nio.file.Files.readAllLines(metadata)) {
            if (line.startsWith("baseCommit=")) {
                return line.substring(11);
            }
        }
        throw new IllegalArgumentException("SPECULATION_METADATA_INVALID");
    }

    /**
     * Gates the worktree and appends validation when the gate passes. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            SpeculationWorkspace workspace = new SpeculationWorkspace(location.root(),
                    location.synesisDirectory()
                            .resolve("local"), prediction, readBaseCommit(location, prediction));
            SpeculationWorkspace.GateResult gate = workspace.gate();
            runtime.terminal()
                    .stdout(gate.status());
            if (!gate.accepted()) {
                return ExitCodes.LOCAL_CONFIGURATION;
            }
            var identity = CoordinationCliSupport.loadIdentity(profile);
            CoordinationCliSupport.submit(endpoint, CoordinationCommand.create(UUID.randomUUID(), location.projectId(),
                    prediction, PredictionEventType.VALIDATION_STARTED, identity.nodeId(), new byte[0], identity));
            runtime.terminal()
                    .stdout("VALIDATION_STARTED=true");
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("SPECULATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
