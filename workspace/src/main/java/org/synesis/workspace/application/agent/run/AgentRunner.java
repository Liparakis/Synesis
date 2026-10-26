package org.synesis.workspace.application.agent.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.application.provider.ProviderRegistry;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderIntegration.HeadlessOptions;

/**
 * The one way agents are started on a project (ADR-0103, stage 4 amendment): it turns an {@code provider[:model[:effort]]}
 * specification into an agent the project may run, wires an {@link AgentRunController} to the project, and runs it. {@code
 * synesis agents run} runs it in the foreground of the CLI process; the project runtime's control plane runs the same code
 * in the background for the browser page, and can stop what it started.
 *
 * <p>What a caller may choose is the provider, a model and an effort, a prompt or to wait for one, and the limits. It
 * cannot choose a command line, a working directory or an environment: those come from the provider's headless
 * contract. A provider that is not installed in the project is refused.</p>
 */
public final class AgentRunner implements AutoCloseable {

    /** Most agents one start may ask for. */
    public static final int MAX_AGENTS = 8;
    /** Most runs this runner keeps alive at once. */
    public static final int MAX_LIVE_RUNS = 4;
    /** Default time limit of a run, in minutes. */
    public static final long DEFAULT_TIMEOUT_MINUTES = 30;
    /** Default seconds between observations of a run. */
    public static final long DEFAULT_POLL_SECONDS = 2;
    /** Default number of idle resumes in a row of one agent. */
    public static final int DEFAULT_MAX_RESUMES = 8;
    private static final int RECENT_LINES = 40;
    private static final int MAX_LISTED_RUNS = 20;

    private final ProviderApplicationService providers;
    private final Path stateRoot;
    private final Map<String, BackgroundRun> runs = new LinkedHashMap<>();

    /**
     * Creates a runner.
     *
     * @param providers tells which providers are installed in a project and which launcher their hooks run
     */
    public AgentRunner(ProviderApplicationService providers) {
        this(providers, AdministrativeStateLocator.applicationStateRoot());
    }

    /**
     * Creates a runner that keeps run logs and tasks under an explicit application state root, so a test never
     * touches the state of the person who runs it.
     *
     * @param providers tells which providers are installed in a project and which launcher their hooks run
     * @param stateRoot the application state root
     */
    public AgentRunner(ProviderApplicationService providers, Path stateRoot) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.stateRoot = Objects.requireNonNull(stateRoot, "stateRoot");
    }

    /**
     * A start that was refused, with the stable code {@code synesis agents run} prints and a fix when the code's
     * standard one would not name what was typed.
     */
    public static final class Refusal extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /** The stable error code. */
        private final String code;
        /** What to do, or {@code null}. */
        private final String fix;

        /**
         * Creates a refusal.
         *
         * @param code    stable error code
         * @param message what was typed and why it is refused, or {@code null} for the code's standard message
         * @param fix     what to do, or {@code null} for the code's standard fix
         */
        public Refusal(String code, String message, String fix) {
            super(message);
            this.code = code;
            this.fix = fix;
        }

        /**
         * Returns the error code.
         *
         * @return stable code
         */
        public String code() {
            return code;
        }

        /**
         * Returns the fix.
         *
         * @return what to do, or {@code null} for the code's standard fix
         */
        public String fix() {
            return fix;
        }
    }

    /**
     * Parses one {@code provider[:model[:effort]]} specification against the provider registry.
     *
     * @param specification text of one agent specification
     * @return the agent to run
     * @throws Refusal when the provider is unknown, has no headless mode, or the specification is malformed
     */
    public static AgentRunController.AgentSpec parse(String specification) {
        String[] parts = specification == null ? new String[0] : specification.split(":", -1);
        if (parts.length < 1 || parts.length > 3 || parts[0].isBlank()) {
            throw new Refusal("AGENT_INVALID", "agent must be provider[:model[:effort]]: " + specification, null);
        }
        ProviderIntegration integration = ProviderRegistry.find(parts[0]);
        if (integration == null) {
            throw new Refusal("UNKNOWN_PROVIDER", "unknown provider: " + parts[0], null);
        }
        String model = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
        String effort = parts.length > 2 && !parts[2].isBlank() ? parts[2] : null;
        return AgentRunController.AgentSpec.of(integration, new HeadlessOptions(model, effort));
    }

    /**
     * Parses a specification and checks the project may run it: the provider is installed in it. A provider whose hooks
     * are given per session (Codex) is told to run the launcher its installed hooks run.
     *
     * @param location      project
     * @param specification one agent specification
     * @return the agent to run
     * @throws Refusal when the specification is not valid or the provider is not installed
     */
    public AgentRunController.AgentSpec resolve(ProjectLocation location, String specification) {
        AgentRunController.AgentSpec agent = parse(specification);
        boolean installed = providers.list(location).stream().anyMatch(row -> row.id().equals(agent.provider()) && "INSTALLED".equals(row.status()));
        if (!installed) {
            throw new Refusal("PROVIDER_NOT_INSTALLED", "provider is not installed in this project: " + agent.provider(), "run 'synesis provider install " + agent.provider() + "'");
        }
        Path hookLauncher = providers.launcherPath();
        return Files.isRegularFile(hookLauncher) ? new AgentRunController.AgentSpec(agent.provider(), agent.session(), agent.options().withHookLauncher(hookLauncher)) : agent;
    }

    /**
     * Names the directory a new run keeps its agents' logs in, outside the project.
     *
     * @param location project
     * @return a directory that does not exist yet
     */
    public Path newLogDirectory(ProjectLocation location) {
        return stateRoot.resolve("agent-runs").resolve(location.projectId().toString()).resolve("run-" + UUID.randomUUID());
    }

    /**
     * Runs the agents until the run ends, in the calling thread.
     *
     * @param location     project
     * @param request      agents, prompt, limits and where tasks arrive
     * @param logDirectory where the agents' logs go
     * @param listener     receives one stable {@code KEY=VALUE} line per controller event
     * @return how it ended
     * @throws IOException          when an agent cannot be started or the project cannot be observed
     * @throws InterruptedException when interrupted, after the agents were stopped
     */
    public AgentRunController.Result run(ProjectLocation location, AgentRunController.Request request, Path logDirectory, Consumer<String> listener) throws IOException, InterruptedException {
        return new AgentRunController(new ProviderAgentProcesses(logDirectory), new EventLogObserver(location), new ControllerConnections(location.root()), System::currentTimeMillis, Thread::sleep)
                .run(request, listener);
    }

    /**
     * Starts a run in the background and returns at once.
     *
     * @param location project
     * @param agents   specifications as {@link #resolve} takes them
     * @param prompt   the task every agent starts with, or {@code null} to start none until a person gives one
     * @return the run's identifier
     * @throws Refusal when a specification is not valid, a provider is not installed, there are too many agents or runs
     */
    public String start(ProjectLocation location, List<String> agents, String prompt) {
        Objects.requireNonNull(location, "location");
        if (agents == null || agents.isEmpty() || agents.size() > MAX_AGENTS) {
            throw new Refusal("AGENT_INVALID", "start between 1 and " + MAX_AGENTS + " agents", null);
        }
        if (prompt != null && prompt.isBlank()) {
            throw new Refusal("TASK_REQUIRED", null, null);
        }
        List<AgentRunController.AgentSpec> specifications = new ArrayList<>();
        for (String specification : agents) {
            specifications.add(resolve(location, specification));
        }
        AgentRunController.Request request = new AgentRunController.Request(location.root(), specifications, prompt, Duration.ofMinutes(DEFAULT_TIMEOUT_MINUTES), Duration.ofSeconds(DEFAULT_POLL_SECONDS),
                DEFAULT_MAX_RESUMES, new AgentTaskInbox(stateRoot, location.projectId()));
        Path logDirectory = newLogDirectory(location);
        BackgroundRun run = new BackgroundRun("run-" + UUID.randomUUID(), agents);
        // The thread exists before the run is listed, so a stop that finds the run always has a thread to interrupt.
        run.thread = Thread.ofPlatform().name("synesis-agent-run-" + run.id).daemon().unstarted(() -> run.execute(this, location, request, logDirectory));
        synchronized (runs) {
            if (runs.values().stream().filter(BackgroundRun::live).count() >= MAX_LIVE_RUNS) {
                throw new Refusal("AGENT_RUN_LIMIT", "this project runtime already runs " + MAX_LIVE_RUNS + " sets of agents", "stop one first");
            }
            // The page lists the newest finished runs only.
            while (runs.size() >= MAX_LISTED_RUNS) {
                String oldestEnded = runs.values().stream().filter(candidate -> !candidate.live()).map(candidate -> candidate.id).findFirst().orElse(null);
                if (oldestEnded == null) {
                    break;
                }
                runs.remove(oldestEnded);
            }
            runs.put(run.id, run);
        }
        run.thread.start();
        return run.id;
    }

    /**
     * Stops a run this runner started: its processes are stopped and their connections ended.
     *
     * @param runId identifier {@link #start} returned
     * @return {@code false} when this runner started no run with that identifier
     */
    public boolean stop(String runId) {
        BackgroundRun run;
        synchronized (runs) {
            run = runs.get(runId);
        }
        if (run == null) {
            return false;
        }
        run.thread.interrupt();
        return true;
    }

    /**
     * Describes the runs this runner started, oldest first, for the page.
     *
     * @return one map per run: its id, state, agents and the latest lines it printed
     */
    public List<Map<String, Object>> runs() {
        synchronized (runs) {
            return runs.values().stream().map(BackgroundRun::describe).toList();
        }
    }

    /** Stops every run this runner started, as the project runtime does when it closes. */
    @Override
    public void close() {
        List<BackgroundRun> started;
        synchronized (runs) {
            started = List.copyOf(runs.values());
        }
        started.forEach(run -> run.thread.interrupt());
        started.forEach(run -> {
            try {
                run.thread.join(10_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** One run in the background: what it was asked, how it stands, and what it last printed. */
    private static final class BackgroundRun {

        private final String id;
        private final List<String> agents;
        private final List<String> lines = new ArrayList<>();
        private volatile String state = "RUNNING";
        private Thread thread;

        private BackgroundRun(String id, List<String> agents) {
            this.id = id;
            this.agents = List.copyOf(agents);
        }

        private boolean live() {
            return "RUNNING".equals(state);
        }

        private void execute(AgentRunner runner, ProjectLocation location, AgentRunController.Request request, Path logDirectory) {
            try {
                state = runner.run(location, request, logDirectory, this::add).outcome().name();
            } catch (InterruptedException stopped) {
                state = "STOPPED";
            } catch (IOException | RuntimeException failed) {
                add("REASON=" + failed.getMessage());
                state = "FAILED";
            }
        }

        private void add(String line) {
            synchronized (lines) {
                lines.add(line);
                if (lines.size() > RECENT_LINES) {
                    lines.removeFirst();
                }
            }
        }

        private Map<String, Object> describe() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("state", state);
            result.put("agents", agents);
            synchronized (lines) {
                result.put("recent", List.copyOf(lines));
            }
            return result;
        }
    }
}
