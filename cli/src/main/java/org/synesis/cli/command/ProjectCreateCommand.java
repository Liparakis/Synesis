package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.ProjectApplicationService;

/** Creates the existing bounded one-peer project configuration. */
@Command(name = "create", description = "Create a project for one authenticated peer.", mixinStandardHelpOptions = true)
public final class ProjectCreateCommand implements Callable<Integer> {
    @Option(names = "--peer", required = true, description = "Allowed peer node ID.") private String peer;
    @Option(names = "--project", description = "Explicit initialized project directory.") private String project;
    @Option(names = "--profile", description = "Advanced local profile override.") private String profile;
    private final CliRuntime runtime;

    /**
     * Creates the command.
     * @param runtime composed runtime
     */
    public ProjectCreateCommand(CliRuntime runtime) { this.runtime = runtime; }

    /** Creates project configuration. @return stable exit code */
    @Override public Integer call() {
        try {
            var location = runtime.projectService().require(project == null ? Path.of(".") : Path.of(project));
            if (profile != null) {
                location = new ProjectApplicationService.ProjectLocation(location.root(), location.synesisDirectory(),
                        location.metadataFile(), Path.of(profile), location.projectId(), location.createdAt());
            }
            var result = runtime.projectService().createProject(location, peer);
            runtime.terminal().stdout("NODE_ID=" + result.nodeId());
            runtime.terminal().stdout("PROJECT_ID=" + result.projectId());
            runtime.terminal().stdout("PEER_NODE_ID=" + result.peerNodeId());
            runtime.terminal().stdout("PROJECT_CONFIGURED=true");
            return ExitCodes.OK;
        } catch (ProjectApplicationService.ProjectApplicationException failure) {
            runtime.terminal().stderr("ERROR=" + failure.code());
            return ExitCodes.LOCAL_CONFIGURATION;
        } catch (RuntimeException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_INVALID");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
