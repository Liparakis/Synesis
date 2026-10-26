package org.synesis.cli.command.agents;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ErrorShape;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.agent.run.AgentTaskInbox;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Gives the agents of a project their task after they started, or replaces it (ADR-0103, stage 4 amendment). The text is
 * handed to agents that wait for a task ({@code agents run --wait-for-task}, or the page's start) as their prompt. It is
 * only a task: it is not written to the coordination log, so it grants no claim, decides no review, merges nothing and
 * changes no policy, whatever it says.
 */
@Command(name = "task", mixinStandardHelpOptions = true,
        description = "Give the agents that wait for a task their task, or replace the task of agents already working.",
        footer = {"", "The task is only text for the agents. It cannot grant claims, approve or skip a review, merge, or change policy:",
                "agents still do all of that through Synesis. It is kept outside the project and is never shared with other computers."})
public final class AgentsTaskCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    private final Supplier<Path> stateRoot;
    @Parameters(arity = "0..1", paramLabel = "<task>", description = "The task, as text. Or use --file.")
    private String text;
    @Option(names = "--file", description = "File holding the task (UTF-8).")
    private Path file;
    @Option(names = "--project", description = "Initialized project directory (default: current directory).")
    private String project;

    /**
     * Creates the command.
     *
     * @param runtime composed CLI runtime
     */
    public AgentsTaskCommand(CliRuntime runtime) {
        this(runtime, AdministrativeStateLocator::applicationStateRoot);
    }

    AgentsTaskCommand(CliRuntime runtime, Supplier<Path> stateRoot) {
        this.runtime = runtime;
        this.stateRoot = stateRoot;
    }

    /**
     * Leaves the task. @return process exit code
     */
    @Override
    public Integer call() {
        try {
            if ((text == null) == (file == null)) {
                return invalid("give the task as text or with --file, not both and not neither");
            }
            var location = ProjectOptions.locate(runtime, project);
            String task = text != null ? text : Files.readString(file, StandardCharsets.UTF_8);
            runtime.terminal().stdout("AGENT_TASK_SENT=" + new AgentTaskInbox(stateRoot.get(), location.projectId()).send(task));
            return ExitCodes.OK;
        } catch (IllegalArgumentException invalid) {
            return invalid(invalid.getMessage());
        } catch (Exception failure) {
            return ProjectOptions.refuse(runtime, failure);
        }
    }

    private int invalid(String message) {
        ErrorShape.print(runtime.terminal()::stderr, "AGENT_TASK_INVALID", message, "pass the task as text or with --file <file>");
        return ExitCodes.USAGE;
    }
}
