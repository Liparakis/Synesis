package org.synesis.cli.command;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.ProjectApplicationService;

/** Hosts one bounded project synchronization invitation. */
@Command(name = "host", description = "Host project synchronization.", mixinStandardHelpOptions = true)
public final class SyncHostCommand implements Callable<Integer> {
    @Option(names = "--project") private String project;
    @Option(names = "--record") private String record;
    @Option(names = "--profile", description = "Advanced local profile override.") private String profile;
    private final CliRuntime runtime;
    /**
     * Creates the command.
     * @param runtime composed runtime
     */
    public SyncHostCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Hosts sync. @return operation exit code */
    @Override public Integer call() {
        try {
            Path candidate = project != null && Files.isDirectory(Path.of(project)) ? Path.of(project) : Path.of(".");
            var location = runtime.projectService().require(candidate);
            Path resolved = profile == null ? location.profile() : Path.of(profile);
            String projectId = project == null || Files.isDirectory(Path.of(project)) ? null : UUID.fromString(project).toString();
            if (record != null) UUID.fromString(record);
            var result = runtime.syncService().host(resolved, projectId, record,
                    invitation -> runtime.terminal().stdout("INVITATION=" + invitation));
            if (result.values().containsKey("ERROR")) runtime.terminal().stderr("ERROR=" + result.values().get("ERROR"));
            return result.exitCode();
        } catch (ProjectApplicationService.ProjectApplicationException | IllegalArgumentException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_INVALID");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
