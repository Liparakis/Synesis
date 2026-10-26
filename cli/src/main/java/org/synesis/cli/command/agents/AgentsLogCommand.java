package org.synesis.cli.command.agents;

import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.agent.run.AgentRunLogs;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.provider.AgentLogEntry;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Shows what the agents of a run did: what each said, which tools it called and what they answered, read from the log
 * files {@code agents run} keeps outside the project. The newest run is shown unless another is named.
 *
 * <p>The files are only read. Each entry is shown on one line with secret-looking values replaced and the end cut off
 * ({@link AgentRunLogs}), so the files keep everything and this shows only enough to follow the work.</p>
 */
@Command(name = "log", mixinStandardHelpOptions = true,
        description = "Show what the agents of a run did: what they said, the tools they called and the answers.",
        footer = {"", "Shows the newest run of this project; --run names another (the last part of the AGENT_LOG_DIRECTORY that agents run printed).",
                "Each entry is one line, shortened, with secret-looking values replaced; the log files themselves are not changed."})
public final class AgentsLogCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    private final Supplier<Path> stateRoot;
    @Parameters(arity = "0..1", paramLabel = "<agent>", description = "One agent such as claude#1 (or claude-1), or a provider such as claude for all its agents. Default: every agent of the run.")
    private String agent;
    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;
    @Option(names = "--run", paramLabel = "<run-name>", description = "Show this run, named run-<uuid>, instead of the newest.")
    private String run;

    /**
     * Creates the command.
     *
     * @param runtime composed CLI runtime
     */
    public AgentsLogCommand(CliRuntime runtime) {
        this(runtime, AdministrativeStateLocator::applicationStateRoot);
    }

    AgentsLogCommand(CliRuntime runtime, Supplier<Path> stateRoot) {
        this.runtime = runtime;
        this.stateRoot = stateRoot;
    }

    /**
     * Prints the run's log. @return process exit code
     */
    @Override
    public Integer call() {
        try {
            var location = ProjectOptions.locate(runtime, project);
            Path runDirectory;
            try {
                runDirectory = AgentRunLogs.run(stateRoot.get(), location.projectId(), run).orElse(null);
            } catch (IllegalArgumentException invalid) {
                return fail("RUN_INVALID", invalid.getMessage(), "pass the name agents run printed in AGENT_LOG_DIRECTORY, such as run-<uuid>", ExitCodes.USAGE);
            }
            if (runDirectory == null) {
                return fail("NO_AGENT_RUNS", run == null ? "this project has no run of agents to show" : "this project has no run named " + run,
                        "start agents with: synesis agents run --agent claude --prompt \"<task>\"", ExitCodes.COMMAND_FAILED);
            }
            List<AgentRunLogs.Log> logs = AgentRunLogs.logs(runDirectory, agent);
            if (logs.isEmpty()) {
                TreeSet<String> known = new TreeSet<>();
                AgentRunLogs.logs(runDirectory, null).forEach(log -> known.add(log.agent()));
                return fail("NO_SUCH_AGENT", agent == null ? "the run has no log yet" : "the run has no log of " + agent + "; its agents are " + (known.isEmpty() ? "none" : String.join(", ", known)),
                        "name one of the run's agents, or leave the agent out for all of them", ExitCodes.COMMAND_FAILED);
            }
            runtime.terminal().stdout("AGENT_LOG_RUN=" + runDirectory.getFileName());
            runtime.terminal().stdout("AGENT_LOG_FILES=" + logs.size());
            for (AgentRunLogs.Log log : logs) {
                List<AgentLogEntry> entries = AgentRunLogs.read(log);
                runtime.terminal().stdout("AGENT_LOG_FILE=" + log.agent() + " launch=" + log.launch() + " file=" + log.file().getFileName() + " entries=" + entries.size());
                entries.forEach(this::print);
            }
            return ExitCodes.OK;
        } catch (Exception failure) {
            return ProjectOptions.refuse(runtime, failure);
        }
    }

    private void print(AgentLogEntry entry) {
        runtime.terminal().stdout(switch (entry.kind()) {
            case SAID -> "AGENT_LOG_SAID=" + entry.text();
            case CALLED -> "AGENT_LOG_CALLED=" + (entry.name().isEmpty() ? "" : entry.name() + " ") + entry.text();
            case RESULT -> "AGENT_LOG_RESULT=" + entry.text();
            case FAILED -> "AGENT_LOG_FAILED=" + (entry.name().isEmpty() ? "" : entry.name() + ": ") + entry.text();
        });
    }

    private int fail(String code, String message, String fix, int exit) {
        runtime.terminal().stderr("ERROR=" + code);
        runtime.terminal().stderr("MESSAGE=" + message);
        runtime.terminal().stderr("FIX=" + fix);
        return exit;
    }
}
