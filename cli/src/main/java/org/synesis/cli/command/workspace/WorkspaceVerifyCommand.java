package org.synesis.cli.command.workspace;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostic/agent command to verify workspace trust.
 */
@Command(name = "verify", description = "Verifies assigned workspace trust for a provider session.", mixinStandardHelpOptions = true)
public final class WorkspaceVerifyCommand implements Runnable {

    private final CliRuntime runtime;

    @Option(names = {"--project"}, description = "Project root directory")
    private Path project;

    @Option(names = {"--provider"}, description = "Provider identifier", defaultValue = "codex")
    private String provider;

    @Option(names = {"--session"}, description = "Session ID")
    private String session;

    @Option(names = {"--cwd"}, description = "Declared active working directory")
    private Path cwd;

    /**
     * Creates the workspace verify command.
     *
     * @param runtime CLI runtime
     */
    public WorkspaceVerifyCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void run() {
        try {
            Path root = project == null ? Path.of(".") : project;
            ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(root);
            Path actualCwd = cwd == null ? Path.of(".")
                                           .toAbsolutePath()
                                           .normalize() : cwd;

            ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
            var res = bindingService.verifyWorkspaceTrust(location, provider, session, actualCwd);

            Map<String, Object> jsonMap = new LinkedHashMap<>();
            jsonMap.put("RESULT", res.verified() ? "SUCCESS" : "FAILED");
            jsonMap.put("CODE", res.code());
            jsonMap.put("WORKSPACE_TRUST", res.verified() ? "VERIFIED" : "WORKSPACE_UNVERIFIED");
            jsonMap.put("PROJECT_ID",
                    location.projectId()
                            .toString());
            jsonMap.put("SESSION_ID",
                    res.binding() != null ? res.binding()
                                            .sessionId() : (session == null ? "UNBOUND" : session));
            jsonMap.put("ASSIGNED_WORKTREE",
                    res.binding() != null && res.binding()
                            .worktreePath() != null ? res.binding()
                                                      .worktreePath() : "UNASSIGNED");
            jsonMap.put("EVIDENCE_DIGEST", res.evidenceDigest());

            runtime.terminal()
                    .stdout(ProviderJson.write(jsonMap));
            if (!res.verified()) {
                runtime.terminal()
                        .stderr("Workspace verification failed: " + res.code());
            }
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("Verification failed: " + failure.getMessage());
        }
    }
}
