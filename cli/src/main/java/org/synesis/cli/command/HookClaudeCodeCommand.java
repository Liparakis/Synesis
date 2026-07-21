package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.ProjectApplicationService;

/** Runs the Claude Code hook adapter with clean JSON stdout. */
@Command(name = "claude-code", description = "Process a Claude Code PreToolUse event.", mixinStandardHelpOptions = true)
public final class HookClaudeCodeCommand implements Callable<Integer> {
    @Option(names = "--project") private String project;
    @Option(names = "--profile", description = "Advanced local profile override.") private String profile;
    private final CliRuntime runtime;
    /**
     * Creates the command.
     * @param runtime composed runtime
     */
    public HookClaudeCodeCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Processes one event. @return stable exit code */
    @Override public Integer call() {
        try {
            var location = runtime.projectService().require(Path.of(project == null ? "." : project));
            Path resolved = profile == null ? location.profile() : Path.of(profile);
            var result = runtime.hookService().claudeCode(resolved, System.in);
            runtime.terminal().stdout(result.responseJson());
            if (result.humanReason() != null && !result.humanReason().isBlank()) runtime.terminal().stderr("HINT=" + result.humanReason());
            return ExitCodes.OK;
        } catch (ProjectApplicationService.ProjectApplicationException | RuntimeException failure) {
            runtime.terminal().stdout("{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"Project is not initialized.\"}}");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
