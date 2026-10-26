package org.synesis.cli.command.agents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ErrorShape;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.daemon.ProjectRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.Terminal;

import org.synesis.coordination.persistence.ReplicationSettings;

import org.synesis.workspace.application.agent.run.AgentRunController;
import org.synesis.workspace.application.agent.run.AgentRunner;
import org.synesis.workspace.application.agent.run.AgentTaskInbox;
import org.synesis.workspace.application.project.ProjectLocation;

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
    private final ProjectRuntime projectRuntime;
    @Option(names = "--agent", required = true, paramLabel = "<provider[:model[:effort]]>",
            description = "Agent to start; repeat for several. Example: claude:sonnet:low or codex.")
    private List<String> agents;
    @Option(names = "--prompt", description = "Prompt every agent starts with.")
    private String prompt;
    @Option(names = "--prompt-file", description = "File holding the prompt every agent starts with (UTF-8).")
    private Path promptFile;
    @Option(names = "--wait-for-task", description = "Start no agent yet: register them as ready and start them with the task a person gives later through 'synesis agents task' or the page. Takes neither --prompt nor --prompt-file.")
    private boolean waitForTask;
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
        this(runtime, ProjectRuntime.DAEMON);
    }

    AgentsRunCommand(CliRuntime runtime, ProjectRuntime projectRuntime) {
        this.runtime = runtime;
        this.projectRuntime = projectRuntime;
    }

    /**
     * Makes sure the project's runtime runs before agents start. A shared project's agents reach the other computer only
     * through it: without it their log is not replicated, their claims are not checked with that computer, and no
     * code comes over Link. Nothing else starts it after a reboot, so a shared project refuses to start agents without
     * it. A role file that cannot be read still marks the project shared. A single computer's agents need nothing
     * from it, so there it is only reported.
     *
     * @param location       project
     * @param projectRuntime the daemon's runtime of the project
     * @param terminal       where the outcome is reported
     * @return whether agents may start
     */
    static boolean ensureRuntime(ProjectLocation location, ProjectRuntime projectRuntime, Terminal terminal) {
        try {
            projectRuntime.ensure(location.root());
            terminal.stdout("PROJECT_RUNTIME=READY");
            return true;
        } catch (IOException unavailable) {
            if (Files.exists(location.coordinationDirectory().resolve(ReplicationSettings.FILE))) {
                ErrorShape.print(terminal::stderr, "PROJECT_RUNTIME_UNAVAILABLE");
                terminal.stderr("REASON=" + unavailable.getMessage());
                terminal.stderr("HINT=this project is shared with another computer, which its agents reach only through the runtime; no agent was started");
                return false;
            }
            terminal.stderr("PROJECT_RUNTIME=UNAVAILABLE");
            terminal.stderr("PROJECT_RUNTIME_REASON=" + unavailable.getMessage());
            return true;
        }
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
        return AgentRunner.parse(specification);
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
            if (prompt != null && promptFile != null) {
                throw new AgentRunner.Refusal("PROMPT_CONFLICT", null, null);
            }
            if (waitForTask && (prompt != null || promptFile != null)) {
                throw new AgentRunner.Refusal("WAIT_FOR_TASK_CONFLICT", null, null);
            }
            location = ProjectOptions.locate(runtime, project);
            startPrompt = prompt != null ? prompt : promptFile == null ? null : Files.readString(promptFile, StandardCharsets.UTF_8);
            // A person at a terminal who gave no task is asked once, after the rest is checked so the question is not wasted.
            // A task that was given but is blank, and any script, is refused: Synesis never invents the task.
            boolean ask = startPrompt == null && !waitForTask && runtime.terminal().interactive();
            if (!ask && !waitForTask && (startPrompt == null || startPrompt.isBlank())) {
                throw new AgentRunner.Refusal("TASK_REQUIRED", null, null);
            }
            AgentRunner agentRunner = new AgentRunner(runtime.providerService());
            for (String specification : agents) {
                specifications.add(agentRunner.resolve(location, specification));
            }
            if (ask) {
                startPrompt = askForTask();
            }
        } catch (AgentRunner.Refusal refused) {
            print(refused, runtime.terminal()::stderr);
            return ExitCodes.USAGE;
        } catch (Exception failure) {
            return ProjectOptions.refuse(runtime, failure);
        }
        if (!ensureRuntime(location, projectRuntime, runtime.terminal())) {
            return ExitCodes.LOCAL_CONFIGURATION;
        }
        Path logDirectory = AgentRunner.newLogDirectory(location);
        try {
            AgentRunController.Request request = new AgentRunController.Request(location.root(), specifications, startPrompt, Duration.ofMinutes(timeoutMinutes), Duration.ofSeconds(pollSeconds), maxResumes, new AgentTaskInbox(location));
            runtime.terminal().stdout("AGENT_LOG_DIRECTORY=" + logDirectory);
            if (waitForTask) {
                runtime.terminal().stdout("HINT=give the agents their task with: synesis agents task \"<task>\"");
            }
            long started = System.currentTimeMillis();
            AgentRunController.Result result = new AgentRunner(runtime.providerService()).run(location, request, logDirectory, runtime.terminal()::stdout);
            runtime.terminal().stdout("AGENT_RUN_RESULT=" + result.outcome());
            if (result.outcome() == AgentRunController.Outcome.NEEDS_OPERATOR) {
                runtime.terminal().stdout("HINT=synesis operator list");
            }
            runtime.terminal().stdout("AGENT_RESUMES=" + result.resumes());
            runtime.terminal().stdout("ELAPSED_SECONDS=" + (System.currentTimeMillis() - started) / 1000L);
            return result.outcome() == AgentRunController.Outcome.COMPLETED ? ExitCodes.OK : ExitCodes.COMMAND_FAILED;
        } catch (IllegalArgumentException invalid) {
            ErrorShape.print(runtime.terminal()::stderr, "AGENT_RUN_INVALID", invalid.getMessage(), "check the options with 'synesis agents run --help'");
            return ExitCodes.USAGE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ErrorShape.print(runtime.terminal()::stderr, "AGENT_RUN_INTERRUPTED");
            return ExitCodes.COMMAND_FAILED;
        } catch (Exception failure) {
            ErrorShape.print(runtime.terminal()::stderr, "AGENT_RUN_FAILED");
            runtime.terminal().stderr("REASON=" + failure.getMessage());
            return ExitCodes.COMMAND_FAILED;
        }
    }

    /**
     * Asks a person at a terminal, once, what the agents should work on, and shows the answer back. Nothing is filled in
     * for them: an empty answer, or none, is the same refusal as a missing {@code --prompt}.
     */
    private String askForTask() throws IOException {
        runtime.terminal().stdoutRaw("What should the agents work on? ");
        String answer = runtime.terminal().readLine();
        if (answer == null || answer.isBlank()) {
            throw new AgentRunner.Refusal("TASK_REQUIRED", null, null);
        }
        runtime.terminal().stdout("AGENT_TASK=" + answer.strip());
        return answer.strip();
    }

    /** Prints a usage refusal: its code, and a message and fix when the catalog's standard ones would not name what the person typed. */
    private static void print(AgentRunner.Refusal refusal, Consumer<String> line) {
        if (refusal.getMessage() == null) {
            ErrorShape.print(line, refusal.code());
        } else if (refusal.fix() == null) {
            ErrorShape.print(line, refusal.code(), refusal.getMessage());
        } else {
            ErrorShape.print(line, refusal.code(), refusal.getMessage(), refusal.fix());
        }
    }
}
