package org.synesis.cli.command;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.CoordinationCommand;
import org.synesis.coordination.domain.PredictionEventType;
import org.synesis.coordination.domain.SpeculationWorkspace;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Retires a validated speculation and removes its worktree.
 */
@Command(name = "retire", description = "Retire a validated prediction worktree.", mixinStandardHelpOptions = true)
public final class SpeculationRetireCommand implements Callable<Integer> {

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
     * Creates a retirement command.
     *
     * @param runtime composed CLI runtime
     */
    public SpeculationRetireCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    static void close(org.synesis.workspace.application.ProjectApplicationService.ProjectLocation location, UUID id)
            throws Exception {
        Path metadata = location.synesisDirectory()
                .resolve("local")
                .resolve("speculation")
                .resolve(id.toString())
                .resolve("speculation.meta");
        String base = java.nio.file.Files.readAllLines(metadata)
                .stream()
                .filter(s -> s.startsWith("baseCommit="))
                .findFirst()
                .orElseThrow()
                .substring(11);
        new SpeculationWorkspace(location.root(),
                location.synesisDirectory()
                        .resolve("local"),
                id,
                base).close();
    }

    /**
     * Records retirement and closes the worktree. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            var identity = CoordinationCliSupport.loadIdentity(profile);
            CoordinationCliSupport.submit(endpoint, CoordinationCommand.create(UUID.randomUUID(), location.projectId(),
                    prediction, PredictionEventType.SPECULATION_RETIRED, identity.nodeId(), new byte[0], identity));
            close(location, prediction);
            runtime.terminal()
                    .stdout("SPECULATION_RETIRED=true");
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("SPECULATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
