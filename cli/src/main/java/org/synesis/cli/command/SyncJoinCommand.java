package org.synesis.cli.command;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Joins one bounded project synchronization invitation.
 */
@Command(name = "join", description = "Join project synchronization.", mixinStandardHelpOptions = true)
public final class SyncJoinCommand implements Callable<Integer> {
    @Option(names = "--project")
    private String project;
    @Option(names = "--record")
    private String record;
    @Option(names = "--expect-host")
    private String expectedHost;
    @Option(names = "--profile", description = "Advanced local profile override.")
    private String profile;
    @Parameters(index = "0", description = "Signed project invitation link.")
    private String invitation;
    private final CliRuntime runtime;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public SyncJoinCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Joins sync. @return operation exit code
     */
    @Override
    public Integer call() {
        try {
            String projectId = project != null && !Files.isDirectory(Path.of(project)) ? UUID.fromString(project).toString() : null;
            if (record != null) UUID.fromString(record);
            Path candidate = project != null && Files.isDirectory(Path.of(project)) ? Path.of(project) : Path.of(".");
            var location = runtime.projectService().require(candidate);
            Path resolved = profile == null ? location.profile() : Path.of(profile);
            return runtime.syncService().join(resolved, projectId, record, expectedHost, invitation).exitCode();
        } catch (ProjectApplicationService.ProjectApplicationException | IllegalArgumentException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_INVALID");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
