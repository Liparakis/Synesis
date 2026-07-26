package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.SpeculationWorkspace;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Creates one isolated worktree for a prediction.
 */
@Command(name = "prepare", description = "Prepare an isolated prediction worktree.", mixinStandardHelpOptions = true)
public final class SpeculationPrepareCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project")
    private Path project;
    @Option(names = "--prediction", required = true)
    private UUID prediction;
    @Option(names = "--base-commit", required = true)
    private String baseCommit;

    /**
     * Creates a preparation command.
     *
     * @param runtime composed CLI runtime
     */
    public SpeculationPrepareCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Creates the worktree and records its metadata. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            SpeculationWorkspace workspace = new SpeculationWorkspace(location.root(),
                    location.synesisDirectory()
                            .resolve("local"), prediction, baseCommit);
            workspace.create();
            runtime.terminal()
                    .stdout("SPECULATION_PREPARED=true");
            runtime.terminal()
                    .stdout("PREDICTION_ID=" + prediction);
            runtime.terminal()
                    .stdout("WORKTREE=" + workspace.worktree());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("SPECULATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
