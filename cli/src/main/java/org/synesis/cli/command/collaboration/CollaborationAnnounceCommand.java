package org.synesis.cli.command.collaboration;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Announces one work intent and atomically acquires path claims.
 */
@Command(name = "announce", description = "Announce intended work and claim paths.", mixinStandardHelpOptions = true)
public final class CollaborationAnnounceCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private Path project;
    @Option(names = "--provider", defaultValue = "codex", description = "Provider ID of the calling session (default: codex).")
    private String provider;
    @Option(names = "--connection-instance-id", required = true, description = "Connection instance ID of the calling provider session.")
    private String connectionInstanceId;
    @Option(names = "--goal", required = true, description = "Goal of the work being announced.")
    private String goal;
    @Option(names = "--acceptance", required = true, description = "Acceptance criteria for the work.")
    private String acceptance;
    @Option(names = "--claim", required = true, split = ",", description = "Comma-separated repository-relative paths to claim.")
    private List<String> claims;
    @Option(names = "--subtree", description = "Claim each path as a directory subtree instead of an exact file.")
    private boolean subtree;
    @Option(names = "--work-group", description = "Logical work-group UUID")
    private UUID workGroupId;

    /**
     * Creates the command.
     *
     * @param runtime composed CLI runtime
     */
    public CollaborationAnnounceCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Executes the announcement. @return process exit code
     */
    @Override
    public Integer call() {
        try {
            List<ResourceSelector> selectors = claims.stream().map(path -> subtree ? ResourceSelector.pathSubtree(path) : ResourceSelector.pathExact(path)).toList();
            ClaimResult result = new WorkspaceCollaborationService().announce(project.toAbsolutePath().normalize(), provider, connectionInstanceId, goal, acceptance, selectors, workGroupId);
            if (!result.acquired()) {
                runtime.terminal().stderr("OVERLAPPING_CLAIM=" + result.conflicts());
                return ExitCodes.LOCAL_CONFIGURATION;
            }
            runtime.terminal().stdout("INTENT_ANNOUNCED=" + result.intent().intentId());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("COLLABORATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
