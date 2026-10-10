package org.synesis.cli.command.speculation;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.UUID;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.coordination.CoordinationCliSupport;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.speculation.SpeculationWorkspace;

import org.synesis.workspace.application.project.ProjectLocation;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Retires a validated speculation and removes its worktree.
 */
@Command(name = "retire", description = "Retire a validated prediction worktree.", mixinStandardHelpOptions = true)
public final class SpeculationRetireCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory (default: current directory).")
    private Path project;
    @Option(names = "--endpoint", required = true, description = "Coordination server endpoint URI, such as http://127.0.0.1:48123/.")
    private URI endpoint;
    @Option(names = "--profile", required = true, description = "Node profile directory holding the signing identity.")
    private Path profile;
    @Option(names = "--prediction", required = true, description = "ID of the prediction whose worktree is retired.")
    private UUID prediction;

    /**
     * Creates a retirement command.
     *
     * @param runtime composed CLI runtime
     */
    public SpeculationRetireCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    static void close(org.synesis.workspace.application.project.ProjectLocation location, UUID id) throws Exception {
        Path localRoot = location.synesisDirectory().resolve("local");
        String base = SpeculationWorkspace.recordedBaseCommit(localRoot, id).orElseThrow();
        new SpeculationWorkspace(location.root(), localRoot, id, base).close();
    }

    /**
     * Records retirement and closes the worktree. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            var signer = CoordinationCliSupport.Signer.resolve(runtime, project, profile);
            signer.submit(endpoint, prediction, PredictionEventType.SPECULATION_RETIRED, new byte[0]);
            close(signer.location(), prediction);
            runtime.terminal().stdout("SPECULATION_RETIRED=true");
            return ExitCodes.OK;
        } catch (Exception failure) {
            return CoordinationCliSupport.fail(runtime, "SPECULATION_ERROR", failure);
        }
    }
}
