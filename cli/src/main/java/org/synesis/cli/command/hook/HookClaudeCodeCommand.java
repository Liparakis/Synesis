package org.synesis.cli.command.hook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.hook.HookApplicationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Runs the Claude Code hook adapter with clean JSON stdout.
 */
@Command(name = "claude", description = "Process a Claude Code PreToolUse or Stop event.", mixinStandardHelpOptions = true)
public final class HookClaudeCodeCommand implements Callable<Integer> {

    /** Claude Code blocks the tool call on exit code 2 and shows stderr to the agent. */
    private static final int BLOCK_EXIT_CODE = 2;

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory (default: current directory).")
    private String project;
    @Option(names = "--profile", description = "Advanced local profile override.")
    private String profile;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public HookClaudeCodeCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Processes one event. A failure to process anything but a Stop event blocks the tool call (exit code 2 with the
     * reason on stderr, the one failure Claude Code treats as a refusal: every other non-zero exit lets the call run).
     * @return stable exit code
     */
    @Override
    public Integer call() {
        String event;
        try {
            event = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException unreadable) {
            event = "";
        }
        try {
            var location = ProjectOptions.locate(runtime, project);
            Path resolved = ProjectOptions.profile(location, profile);
            var result = runtime.hookService().claudeCode(resolved, new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)));
            runtime.terminal().stdout(result.responseJson());
            if (result.humanReason() != null && !result.humanReason().isBlank()) {
                runtime.terminal().stderr("HINT=" + result.humanReason());
            }
            return ExitCodes.OK;
        } catch (ProjectApplicationService.ProjectApplicationException | IOException | RuntimeException failure) {
            if (HookApplicationService.blocksWhenUndecided(event)) {
                runtime.terminal().stderr("Synesis could not process this tool call (project not initialized or hook failure), so it is refused.");
                return BLOCK_EXIT_CODE;
            }
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
