package org.synesis.cli.command.project;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ErrorShape;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.workspace.application.project.ProjectInitResult;
import org.synesis.workspace.application.project.ProjectInitStatus;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService.ProviderResult;
import org.synesis.workspace.application.provider.ProviderDiscovery;
import org.synesis.workspace.application.provider.ProviderRegistry;
import org.synesis.workspace.application.provider.TouchedFiles;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.provider.ProviderIntegration;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Initializes a Synesis project, finds the agents installed on this computer and connects the ones
 * the person chooses (ADR-0103 stage 2).
 */
@Command(name = "init", description = "Initialize a Synesis project and connect the agents installed on this computer.", mixinStandardHelpOptions = true)
public final class InitCommand implements Callable<Integer> {

    private static final Map<String, String> AGENT_NAMES = Map.of("claude", "Claude Code", "codex", "Codex");
    private static final int CHOICE_ATTEMPTS = 3;

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Project directory.")
    private String project;
    @Option(names = "--agent", split = ",", description = "Connect this agent (claude or codex); repeat or separate with commas for several. Without it, one agent found is connected and several are asked about on a terminal.")
    private List<String> agents = new ArrayList<>();
    @Option(names = "--no-agents", description = "Connect no agent.")
    private boolean noAgents;
    @Option(names = "--git-init", description = "Run git init first when the folder is not a Git repository.")
    private boolean gitInit;

    /**
     * Creates an init command.
     *
     * @param runtime composed CLI runtime
     */
    public InitCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    private static String name(String provider) {
        return AGENT_NAMES.getOrDefault(provider, provider);
    }

    private static String key(String provider) {
        return provider.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private static String oneLine(String text) {
        return text == null || text.isBlank() ? "No further detail." : text.strip().lines().findFirst().orElse("");
    }

    private int refuseProject(String code, String message) {
        runtime.terminal().stdout("INIT_RESULT=CONFLICT");
        runtime.terminal().stderr("ERROR_CODE=" + code);
        runtime.terminal().stderr("REASON=" + message);
        ErrorShape.print(runtime.terminal()::stderr, code, oneLine(message));
        return ExitCodes.LOCAL_CONFIGURATION;
    }

    /**
     * What a newcomer does after the project exists: connect an agent, start it, look at what happens. The
     * agent finds its instructions in the installed {@code synesis-provider-manual} skill, so none is written
     * into the project (ADR-0103 decision 9). Printed on a re-run too, because running {@code init} again is
     * how one learns the way back to connecting an agent.
     */
    private void printNextSteps(ProjectInitResult result, List<String> connected, Set<String> signedOut, List<String> advice) {
        if (result.retiredInstructions().contains("AGENTS.md")) {
            runtime.terminal().stdout("NEXT_STEP=Commit the change to AGENTS.md if Git tracks it: Synesis removed its old section from your file and kept your text");
        }
        advice.forEach(step -> runtime.terminal().stdout("NEXT_STEP=" + step));
        if (connected.isEmpty()) {
            if (advice.isEmpty()) {
                runtime.terminal().stdout("NEXT_COMMAND=synesis provider install claude");
                runtime.terminal().stdout("NEXT_STEP=Connect an agent to this project: synesis provider install claude (or: synesis provider install codex)");
                runtime.terminal().stdout("NEXT_STEP=Start the agent in this folder (claude or codex) and tell it what to do");
            }
        } else {
            runtime.terminal().stdout("NEXT_COMMAND=" + connected.getFirst());
            for (String provider : connected) {
                if (signedOut.contains(provider)) {
                    runtime.terminal().stdout("NEXT_STEP=Sign in to " + name(provider) + " first: run " + provider + " and follow its sign-in");
                }
                runtime.terminal().stdout("NEXT_STEP=Start " + name(provider) + " in this folder: " + provider + ", and tell it what to do; Synesis starts no work of its own");
            }
        }
        runtime.terminal().stdout("NEXT_STEP=See what is happening: synesis status (or synesis ui in the browser); what needs you: synesis operator list");
    }

    /**
     * Reads one line for a question, or {@code null} when the input has ended.
     */
    private String ask(String prompt) throws IOException {
        runtime.terminal().stdout(prompt);
        return runtime.terminal().readLine();
    }

    private boolean offerGitInit(Path root) throws ProjectApplicationService.ProjectApplicationException, IOException {
        if (runtime.projectService().hasGitRepository(root)) {
            return false;
        }
        if (!gitInit) {
            if (!runtime.terminal().interactive()) {
                return false;
            }
            String answer = ask("GIT_INIT_PROMPT=This folder is not a Git repository. Run git init here? [y/N]");
            if (answer == null || !answer.strip().toLowerCase(Locale.ROOT).startsWith("y")) {
                return false;
            }
        }
        runtime.projectService().initGit(root);
        runtime.terminal().stdout("GIT_INIT=DONE");
        return true;
    }

    /**
     * Asks which of several found agents to connect: numbers or names separated by commas, {@code all} or {@code none}; Enter
     * means all. Input that ends answers none, so nothing is written to a person's global files on a guess.
     */
    private List<String> choose(List<String> found) throws IOException {
        StringBuilder options = new StringBuilder();
        for (int i = 0; i < found.size(); i++) {
            options.append(i == 0 ? "" : "  ").append(i + 1).append(") ").append(found.get(i));
        }
        for (int attempt = 0; attempt < CHOICE_ATTEMPTS; attempt++) {
            String answer = ask("AGENT_CHOICE_PROMPT=Connect which agents? " + options + " (numbers or names separated by commas, all, none; Enter = all)");
            if (answer == null) {
                return List.of();
            }
            String text = answer.strip().toLowerCase(Locale.ROOT);
            if (text.isEmpty() || text.equals("all")) {
                return found;
            }
            Set<String> chosen = new LinkedHashSet<>();
            boolean valid = !text.equals("none");
            for (String token : text.equals("none") ? new String[0] : text.split("[,\\s]+")) {
                if (token.matches("\\d+") && Integer.parseInt(token) >= 1 && Integer.parseInt(token) <= found.size()) {
                    chosen.add(found.get(Integer.parseInt(token) - 1));
                } else if (found.contains(token)) {
                    chosen.add(token);
                } else {
                    valid = false;
                }
            }
            if (valid || text.equals("none")) {
                return List.copyOf(chosen);
            }
            runtime.terminal().stdout("AGENT_CHOICE_INVALID=Not understood: " + answer.strip());
        }
        return List.of();
    }

    /**
     * Finds the installed agents and connects the chosen ones with {@code provider install}, printing what was found and what
     * each connection came to.
     *
     * @return the exit code of the first failed connection, or {@link ExitCodes#OK}
     */
    private int connectAgents(ProjectLocation location, Set<String> requested, List<String> connected, Set<String> signedOut, List<String> advice) throws IOException {
        if (noAgents) {
            runtime.terminal().stdout("AGENTS=NOT_REQUESTED");
            return ExitCodes.OK;
        }
        List<ProviderDiscovery.Finding> findings = runtime.providerService().discoverAgents();
        List<String> found = new ArrayList<>();
        for (ProviderDiscovery.Finding finding : findings) {
            String prefix = "AGENT_" + key(finding.provider());
            runtime.terminal().stdout(prefix + "=" + (finding.found() ? "FOUND" : "NOT_FOUND"));
            if (finding.found()) {
                found.add(finding.provider());
                runtime.terminal().stdout(prefix + "_VERSION=" + finding.version().orElse("UNKNOWN"));
                runtime.terminal().stdout(prefix + "_SIGN_IN=" + finding.signIn());
                if (finding.signIn() == ProviderIntegration.SignIn.SIGNED_OUT) {
                    signedOut.add(finding.provider());
                }
            }
        }
        List<String> chosen;
        if (!requested.isEmpty()) {
            chosen = List.copyOf(requested);
        } else if (found.isEmpty()) {
            advice.add("No agent was found on this computer: install Claude Code (claude) or Codex (codex), then run synesis init again");
            chosen = List.of();
        } else if (found.size() == 1) {
            chosen = found;
        } else if (runtime.terminal().interactive()) {
            chosen = choose(found);
        } else {
            advice.add("Several agents were found (" + String.join(", ", found) + "): run synesis init --agent <id> for each one to connect (several: --agent " + String.join(",", found) + "), or --no-agents");
            chosen = List.of();
        }
        int exit = ExitCodes.OK;
        for (String provider : chosen) {
            if (!found.contains(provider)) {
                runtime.terminal().stdout("AGENT_" + key(provider) + "_CONNECT=FAILED");
                ErrorShape.print(runtime.terminal()::stderr, "AGENT_NOT_INSTALLED", name(provider) + " (" + provider + ") is not on this computer's PATH, so it cannot be connected",
                        "install " + name(provider) + " or add its folder to the PATH, then run 'synesis init --agent " + provider + "' again");
                exit = exit == ExitCodes.OK ? ExitCodes.LOCAL_CONFIGURATION : exit;
                continue;
            }
            ProviderResult result = runtime.providerService().install(location, provider);
            Map<String, String> values = result.values();
            String error = values.get("ERROR");
            if (error != null) {
                runtime.terminal().stdout("AGENT_" + key(provider) + "_CONNECT=FAILED");
                ErrorShape.print(runtime.terminal()::stderr, error);
                exit = exit == ExitCodes.OK ? result.exitCode() : exit;
                continue;
            }
            String state = values.getOrDefault("PROVIDER_INSTALL_RESULT", "UNKNOWN");
            runtime.terminal().stdout("AGENT_" + key(provider) + "_CONNECT=" + state);
            connected.add(provider);
            String worktree = values.getOrDefault("ASSIGNED_WORKTREE", "UNASSIGNED");
            if (!"UNASSIGNED".equals(worktree)) {
                runtime.terminal().stdout("AGENT_" + key(provider) + "_WORKTREE=" + worktree);
            }
            if ("DEGRADED".equals(state)) {
                runtime.terminal().stdout("NEXT_STEP=" + name(provider) + " is connected but degraded (MCP_HEALTH=" + values.getOrDefault("MCP_HEALTH", "UNKNOWN") + "): run synesis provider status " + provider);
            }
            if (values.containsKey("TRUST_NOTE")) {
                runtime.terminal().stdout("NEXT_STEP=" + values.get("TRUST_NOTE"));
            }
        }
        return exit;
    }

    /**
     * Runs initialization. @return stable exit code
     */
    @Override
    public Integer call() {
        Set<String> requested = new LinkedHashSet<>();
        for (String id : agents) {
            ProviderIntegration provider = ProviderRegistry.find(id.strip());
            if (provider == null) {
                ErrorShape.print(runtime.terminal()::stderr, "UNKNOWN_PROVIDER", "Unknown agent: " + id, "use --agent with one of: " + String.join(", ", ProviderRegistry.providers().stream().map(ProviderIntegration::id).toList()));
                return ExitCodes.USAGE;
            }
            requested.add(provider.id());
        }
        if (noAgents && !requested.isEmpty()) {
            ErrorShape.print(runtime.terminal()::stderr, "OPTIONS_CONFLICT", "--agent and --no-agents ask for opposite things");
            return ExitCodes.USAGE;
        }
        try {
            Path root = project == null ? Path.of(".") : Path.of(project);
            TouchedFiles touched = TouchedFiles.watch(root, KnownProjectRegistry.defaultWrittenFiles());
            offerGitInit(root);
            runtime.projectService().requireGitRepository(root);
            ProjectInitResult result = runtime.projectService().init(root);
            var location = result.location();
            try {
                new KnownProjectRegistry().observe(location);
                runtime.terminal().stdout("PROJECT_DISCOVERY=RECORDED");
            } catch (KnownProjectRegistry.RegistryException discoveryFailure) {
                runtime.terminal().stderr("PROJECT_DISCOVERY_ERROR=" + discoveryFailure.code());
            }
            runtime.terminal().stdout("INIT_RESULT=" + result.status());
            runtime.terminal().stdout("PROJECT_ROOT=" + location.root());
            runtime.terminal().stdout("SYNESIS_DIRECTORY=" + location.synesisDirectory());
            runtime.terminal().stdout("PROJECT_ID=" + location.projectId());
            runtime.terminal().stdout("LOCAL_PROFILE=" + location.profile());
            runtime.terminal().stdout("NODE_ID=" + result.identity().nodeId());
            runtime.terminal().stdout("GIT_HEAD_STATUS=" + result.gitHeadStatus());
            result.retiredInstructions().forEach(path -> runtime.terminal().stdout("INSTRUCTIONS_REMOVED=" + path));
            if (result.status() == ProjectInitStatus.SUCCESS) {
                runtime.terminal().stdout("GITIGNORE_RECOMMENDATION=.synesis/local/");
            }
            List<String> connected = new ArrayList<>();
            Set<String> signedOut = new LinkedHashSet<>();
            List<String> advice = new ArrayList<>();
            int exit;
            try {
                exit = connectAgents(location, requested, connected, signedOut, advice);
            } catch (IOException | RuntimeException failure) {
                ErrorShape.print(runtime.terminal()::stderr, "AGENT_SETUP_FAILED", "Finding or connecting the agents failed: " + failure.getClass().getSimpleName());
                exit = ExitCodes.LOCAL_CONFIGURATION;
            }
            touched.changes().forEach(entry -> runtime.terminal().stdout("FILE_" + entry.kind() + "=" + entry.path()));
            printNextSteps(result, connected, signedOut, advice);
            return exit;
        } catch (ProjectApplicationService.ProjectApplicationException failure) {
            return refuseProject(failure.code(), failure.getMessage());
        } catch (IOException | RuntimeException failure) {
            return refuseProject("PROJECT_INVALID", "Invalid project path.");
        }
    }
}
