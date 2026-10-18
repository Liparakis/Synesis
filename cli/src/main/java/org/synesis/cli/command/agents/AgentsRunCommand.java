package org.synesis.cli.command.agents;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.workspace.application.agent.run.AgentRunController;
import org.synesis.workspace.application.agent.run.ControllerConnections;
import org.synesis.workspace.application.agent.run.EventLogObserver;
import org.synesis.workspace.application.agent.run.ProviderAgentProcesses;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderIntegration.HeadlessOptions;
import org.synesis.workspace.application.provider.ProviderRegistry;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Starts headless agents on the project and resumes each one when coordination has moved on
 * without it.
 */
@Command(name = "run", mixinStandardHelpOptions = true,
        description = "Start headless agents on this project and resume any that stopped when another lane has made progress.",
        footer = {"",
                "Every agent receives the same prompt. A resumed agent is told only to continue; this command never",
                "reads agent conversations, assigns work, or writes coordination state. Agent output is kept as log",
                "files outside the project."})
public final class AgentsRunCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--agent", required = true, paramLabel = "<provider[:model[:effort]]>",
            description = "Agent to start; repeat for several. Example: claude:sonnet:low or codex.")
    private List<String> agents;
    @Option(names = "--prompt", description = "Prompt every agent starts with.")
    private String prompt;
    @Option(names = "--prompt-file", description = "File holding the prompt every agent starts with (UTF-8).")
    private Path promptFile;
    @Option(names = "--project", description = "Initialized project directory (default: current directory).")
    private String project;
    @Option(names = "--timeout-minutes", defaultValue = "30", description = "Stop after this many minutes if work is unfinished (default: 30).")
    private long timeoutMinutes;
    @Option(names = "--max-resumes", defaultValue = "8", description = "How many times in a row one agent may be resumed with nothing recorded in the log while it ran; any progress resets the count (default: 8).")
    private int maxResumes;
    @Option(names = "--poll-seconds", defaultValue = "2", description = "Seconds between checks of coordination state (default: 2).")
    private long pollSeconds;

    /**
     * Creates the command.
     *
     * @param runtime composed CLI runtime
     */
    public AgentsRunCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Parses one {@code provider[:model[:effort]]} specification against the provider registry.
     *
     * @param specification text of one {@code --agent} option
     * @return the agent to run
     * @throws IllegalArgumentException when the provider is unknown, has no headless mode, or the
     *                                  specification is malformed
     */
    static AgentRunController.AgentSpec parseAgent(String specification) {
        String[] parts = specification == null ? new String[0] : specification.split(":", -1);
        if (parts.length < 1 || parts.length > 3 || parts[0].isBlank()) {
            throw new IllegalArgumentException("agent must be provider[:model[:effort]]: " + specification);
        }
        ProviderIntegration integration = ProviderRegistry.find(parts[0]);
        if (integration == null) {
            throw new IllegalArgumentException("unknown provider: " + parts[0]);
        }
        String model = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
        String effort = parts.length > 2 && !parts[2].isBlank() ? parts[2] : null;
        return AgentRunController.AgentSpec.of(integration, new HeadlessOptions(model, effort));
    }

    /**
     * Runs the agents. @return process exit code
     */
    @Override
    public Integer call() {
        ProjectLocation location;
        List<AgentRunController.AgentSpec> specifications = new ArrayList<>();
        String startPrompt;
        try {
            location = ProjectOptions.locate(runtime, project);
            if ((prompt == null) == (promptFile == null)) {
                throw new IllegalArgumentException("give exactly one of --prompt and --prompt-file");
            }
            startPrompt = prompt != null ? prompt : Files.readString(promptFile, StandardCharsets.UTF_8);
            for (String specification : agents) {
                AgentRunController.AgentSpec agent = parseAgent(specification);
                boolean installed = runtime.providerService().list(location).stream().anyMatch(row -> row.id().equals(agent.provider()) && "INSTALLED".equals(row.status()));
                if (!installed) {
                    throw new IllegalArgumentException("provider is not installed in this project; run: synesis provider install " + agent.provider());
                }
                // A provider whose hooks are given per session (Codex) runs the launcher its installed hooks run.
                Path hookLauncher = runtime.providerService().launcherPath();
                specifications.add(Files.isRegularFile(hookLauncher)
                        ? new AgentRunController.AgentSpec(agent.provider(), agent.session(), agent.options().withHookLauncher(hookLauncher)) : agent);
            }
        } catch (IllegalArgumentException invalid) {
            runtime.terminal().stderr("ERROR=" + invalid.getMessage());
            return ExitCodes.USAGE;
        } catch (Exception failure) {
            runtime.terminal().stderr("ERROR=PROJECT_NOT_CONFIGURED");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
        Path logDirectory = AdministrativeStateLocator.applicationStateRoot().resolve("agent-runs").resolve(location.projectId().toString()).resolve("run-" + UUID.randomUUID());
        try {
            AgentRunController.Request request = new AgentRunController.Request(location.root(), specifications, startPrompt, Duration.ofMinutes(timeoutMinutes), Duration.ofSeconds(pollSeconds), maxResumes);
            runtime.terminal().stdout("AGENT_LOG_DIRECTORY=" + logDirectory);
            long started = System.currentTimeMillis();
            AgentRunController controller = new AgentRunController(new ProviderAgentProcesses(logDirectory), new EventLogObserver(location), new ControllerConnections(location.root()), System::currentTimeMillis,
                    Thread::sleep);
            AgentRunController.Result result = controller.run(request, runtime.terminal()::stdout);
            runtime.terminal().stdout("AGENT_RUN_RESULT=" + result.outcome());
            if (result.outcome() == AgentRunController.Outcome.NEEDS_OPERATOR) {
                runtime.terminal().stdout("HINT=synesis operator list");
            }
            runtime.terminal().stdout("AGENT_RESUMES=" + result.resumes());
            runtime.terminal().stdout("ELAPSED_SECONDS=" + (System.currentTimeMillis() - started) / 1000L);
            return result.outcome() == AgentRunController.Outcome.COMPLETED ? ExitCodes.OK : ExitCodes.COMMAND_FAILED;
        } catch (IllegalArgumentException invalid) {
            runtime.terminal().stderr("ERROR=" + invalid.getMessage());
            return ExitCodes.USAGE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            runtime.terminal().stderr("ERROR=AGENT_RUN_INTERRUPTED");
            return ExitCodes.COMMAND_FAILED;
        } catch (Exception failure) {
            runtime.terminal().stderr("ERROR=AGENT_RUN_FAILED");
            runtime.terminal().stderr("REASON=" + failure.getMessage());
            return ExitCodes.COMMAND_FAILED;
        }
    }
}
