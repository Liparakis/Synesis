package org.synesis.cli.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.cli.SynesisCli;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.constraint.ConstraintApplicationService;
import org.synesis.workspace.application.guardrail.GuardrailApplicationService;
import org.synesis.workspace.application.hook.HookApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.application.provider.ProviderDiscovery;
import org.synesis.workspace.application.sync.SyncApplicationService;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * ADR-0103 stage 2: {@code synesis init} finds the agents on this computer, connects the chosen ones, lists every
 * file it wrote, and explains each refusal with ERROR, MESSAGE and FIX. The agents are fakes on a private search
 * path and the home folder is private, so nothing of this computer's is inspected or written.
 */
final class InitAgentSetupTest {

    private record Run(int exit, String out, String err) {

    }

    @TempDir
    Path tmp;

    private Path home;
    private Path project;
    private Path bin;
    private String previousHome;
    private String previousLauncher;
    private String previousMcpLauncher;

    @BeforeEach
    void isolate() throws Exception {
        home = Files.createDirectories(tmp.resolve("home"));
        project = Files.createDirectories(tmp.resolve("project"));
        bin = Files.createDirectories(tmp.resolve("bin"));
        previousHome = System.getProperty("user.home");
        previousLauncher = System.getProperty("synesis.launcher");
        previousMcpLauncher = System.getProperty("synesis.mcp.launcher");
        System.setProperty("user.home", home.toString());
        // Launchers that exist but start no server keep a connection independent of what is installed here.
        System.setProperty("synesis.launcher", Files.createFile(tmp.resolve("synesis-launcher.bat")).toString());
        System.setProperty("synesis.mcp.launcher", Files.createFile(tmp.resolve("synesis-mcp.exe")).toString());
        GitProcessRunner.run(project, "init", "-q");
    }

    @AfterEach
    void restore() {
        restoreProperty("user.home", previousHome);
        restoreProperty("synesis.launcher", previousLauncher);
        restoreProperty("synesis.mcp.launcher", previousMcpLauncher);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private void install(String... agents) throws IOException {
        for (String agent : agents) {
            assertTrue(Files.writeString(bin.resolve(agent), "#!/bin/sh\n").toFile().setExecutable(true));
        }
    }

    private static ProviderDiscovery.Runner answers() {
        return command -> {
            String joined = String.join(" ", command);
            if (joined.contains("--version")) {
                return new ProviderDiscovery.Answer(0, joined.contains("claude") ? "2.1.302 (Claude Code)\n" : "codex-cli 0.163.0\n");
            }
            return joined.contains("claude") ? new ProviderDiscovery.Answer(0, "{\"loggedIn\": false}") : new ProviderDiscovery.Answer(0, "Logged in using ChatGPT\n");
        };
    }

    private Run run(boolean interactive, String input, String... arguments) {
        return run(interactive, input, new ProviderDiscovery(bin.toString(), false, answers()), arguments);
    }

    private Run run(boolean interactive, String input, ProviderDiscovery discovery, String... arguments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), interactive);
        CliRuntime runtime = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal, new ProjectApplicationService(), new ConstraintApplicationService(),
                new GuardrailApplicationService(), new HookApplicationService(), new SyncApplicationService(), new ProviderApplicationService(discovery));
        String[] full = Stream.concat(Stream.of("init", "--project", project.toString()), Stream.of(arguments)).toArray(String[]::new);
        int exit = SynesisCli.execute(full, runtime);
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Set<String> files(Path root, java.util.function.Predicate<String> keep) throws IOException {
        if (!Files.isDirectory(root)) {
            return Set.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).map(path -> root.relativize(path).toString().replace('\\', '/')).filter(keep).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** The registry of known projects init records the project in: Synesis's own per-computer state, listed like any other file. */
    private static Set<String> registryFiles() {
        return KnownProjectRegistry.defaultWrittenFiles().stream().map(path -> path.toAbsolutePath().normalize().toString()).collect(Collectors.toSet());
    }

    private Set<String> homeFilesExceptRegistry(java.util.function.Predicate<String> keep) throws IOException {
        Set<String> registry = registryFiles();
        return files(home, path -> keep.test(path) && !registry.contains(home.resolve(path).toAbsolutePath().normalize().toString()));
    }

    private static Set<String> listed(Run run) {
        return run.out().lines().filter(line -> line.startsWith("FILE_")).map(line -> line.substring(line.indexOf('=') + 1)).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void noAgentFoundConnectsNothingAndWritesNothingOutsideTheProject() throws Exception {
        Run run = run(false, "");

        assertEquals(ExitCodes.OK, run.exit(), run.out() + run.err());
        assertTrue(run.out().contains("AGENT_CLAUDE=NOT_FOUND"), run.out());
        assertTrue(run.out().contains("AGENT_CODEX=NOT_FOUND"), run.out());
        assertTrue(run.out().contains("NEXT_STEP=No agent was found on this computer"), run.out());
        assertEquals(Set.of(), homeFilesExceptRegistry(path -> true), "only Synesis's own registry of known projects is written outside the project");
        Set<String> registry = registryFiles();
        assertEquals(Set.of(".synesis/"), listed(run).stream().filter(path -> !path.equals(".git/info/exclude") && !registry.contains(path)).collect(Collectors.toSet()), run.out());
        assertTrue(listed(run).containsAll(registry.stream().filter(path -> Files.isRegularFile(Path.of(path)) && path.startsWith(home.toString())).toList()), "the registry file is listed: " + run.out());
    }

    @Test
    void oneAgentFoundIsConnectedAndEveryFileItWroteIsListed() throws Exception {
        install("claude");
        Set<String> homeBefore = files(home, path -> true);
        String excludeBefore = Files.readString(project.resolve(".git/info/exclude"));

        Run run = run(false, "");

        assertEquals(ExitCodes.OK, run.exit(), run.out() + run.err());
        assertTrue(run.out().contains("AGENT_CLAUDE=FOUND"), run.out());
        assertTrue(run.out().contains("AGENT_CLAUDE_VERSION=2.1.302 (Claude Code)"), run.out());
        assertTrue(run.out().contains("AGENT_CLAUDE_SIGN_IN=SIGNED_OUT"), run.out());
        assertTrue(run.out().contains("AGENT_CODEX=NOT_FOUND"), run.out());
        assertFalse(run.out().contains("AGENT_CLAUDE_CONNECT=FAILED"), run.out());
        assertTrue(run.out().contains("AGENT_CLAUDE_CONNECT="), run.out());
        assertTrue(run.out().contains("NEXT_COMMAND=claude"), run.out());
        assertTrue(run.out().contains("NEXT_STEP=Sign in to Claude Code first"), run.out());
        assertFalse(Files.exists(project.resolve("AGENTS.md")), "decision 9: no instruction file is written");
        assertFalse(Files.exists(home.resolve(".codex")), "decision 2: Codex's files are written only when Codex is connected");

        // The snapshot: what init created, project and global, is exactly what it listed (the agent's worktree has its own line).
        Set<String> projectCreated = files(project, path -> !path.startsWith(".git/") && !path.startsWith(".synesis/"));
        Set<String> homeCreated = homeFilesExceptRegistry(path -> !homeBefore.contains(path) && !path.startsWith(".synesis/Synesis/workspaces/"));
        assertEquals(Set.of(".claude/settings.json", ".mcp.json"), projectCreated);
        assertEquals(Set.of(".claude/skills/synesis-provider-manual/SKILL.md", ".claude/skills/synesis-provider-manual/manifest.json", ".claude/skills/synesis-provider-manual.lock"), homeCreated);
        Set<String> expected = new TreeSet<>(projectCreated);
        expected.add(".synesis/");
        if (!excludeBefore.equals(Files.readString(project.resolve(".git/info/exclude")))) {
            expected.add(".git/info/exclude");
        }
        homeCreated.forEach(path -> expected.add(home.resolve(path).toAbsolutePath().normalize().toString()));
        Set<String> printed = new TreeSet<>(listed(run));
        printed.removeAll(registryFiles());
        assertEquals(expected, printed, run.out());
    }

    @Test
    void severalAgentsWithoutATerminalConnectNoneUntilTheyAreNamed() throws Exception {
        install("claude", "codex");

        Run first = run(false, "");

        assertEquals(ExitCodes.OK, first.exit(), first.out() + first.err());
        assertTrue(first.out().contains("NEXT_STEP=Several agents were found (claude, codex)"), first.out());
        assertFalse(first.out().contains("_CONNECT="), first.out());
        assertEquals(Set.of(), homeFilesExceptRegistry(path -> true));

        Run named = run(false, "", "--agent", "codex");

        assertEquals(ExitCodes.OK, named.exit(), named.out() + named.err());
        assertTrue(named.out().contains("AGENT_CODEX_CONNECT="), named.out());
        assertFalse(named.out().contains("AGENT_CLAUDE_CONNECT="), named.out());
        assertTrue(Files.isRegularFile(home.resolve(".codex/config.toml")), "Codex is connected, so its global entry is written");
        assertTrue(Files.isRegularFile(project.resolve(".codex/hooks.json")));
        assertFalse(Files.exists(home.resolve(".claude")));
        assertFalse(Files.exists(project.resolve(".claude")));
        assertTrue(listed(named).contains(home.resolve(".codex/config.toml").toAbsolutePath().normalize().toString()), named.out());
    }

    @Test
    void severalAgentsOnATerminalAreAskedOnceAndEnterMeansAll() throws Exception {
        install("claude", "codex");

        Run second = run(true, "2\n");

        assertEquals(ExitCodes.OK, second.exit(), second.out() + second.err());
        assertEquals(1, second.out().lines().filter(line -> line.startsWith("AGENT_CHOICE_PROMPT=")).count(), second.out());
        assertTrue(second.out().contains("AGENT_CODEX_CONNECT="), second.out());
        assertFalse(second.out().contains("AGENT_CLAUDE_CONNECT="), second.out());

        Run all = run(true, "\n");

        assertTrue(all.out().contains("AGENT_CLAUDE_CONNECT="), all.out());
        assertTrue(all.out().contains("AGENT_CODEX_CONNECT="), all.out());
    }

    @Test
    void aTerminalAnswerThatEndsOrIsNoneConnectsNothing() throws Exception {
        install("claude", "codex");

        assertFalse(run(true, "").out().contains("_CONNECT="), "input that ends is no answer, so nothing is written on a guess");
        assertFalse(run(true, "none\n").out().contains("_CONNECT="));
        Run unclear = run(true, "banana\nclaude, codex\n");
        assertTrue(unclear.out().contains("AGENT_CHOICE_INVALID=Not understood: banana"), unclear.out());
        assertTrue(unclear.out().contains("AGENT_CLAUDE_CONNECT=") && unclear.out().contains("AGENT_CODEX_CONNECT="), unclear.out());
    }

    @Test
    void noAgentsNeverLooksForAgents() throws Exception {
        install("claude", "codex");
        ProviderDiscovery forbidden = new ProviderDiscovery(bin.toString(), false, command -> {
            throw new AssertionError("--no-agents must not run an agent's command: " + command);
        });

        Run run = run(false, "", forbidden, "--no-agents");

        assertEquals(ExitCodes.OK, run.exit(), run.out() + run.err());
        assertTrue(run.out().contains("AGENTS=NOT_REQUESTED"), run.out());
        assertTrue(run.out().contains("NEXT_COMMAND=synesis provider install claude"), run.out());
        assertEquals(Set.of(), homeFilesExceptRegistry(path -> true));
    }

    @Test
    void anExistingCodexConfigurationIsLeftAloneWhenCodexIsNotConnected() throws Exception {
        install("claude", "codex");
        Files.createDirectories(home.resolve(".codex"));
        String mine = "model = \"mine\"\n\n[mcp_servers.synesis]\ncommand = \"/somewhere/else\"\n";
        Files.writeString(home.resolve(".codex/config.toml"), mine);

        assertEquals(ExitCodes.OK, run(false, "", "--agent", "claude").exit());
        assertEquals(ExitCodes.OK, run(false, "", "--no-agents").exit());

        assertEquals(mine, Files.readString(home.resolve(".codex/config.toml")));
    }

    @Test
    void anAgentThatCannotBeConnectedIsRefusedWithErrorMessageAndFix() throws Exception {
        install("claude");
        assertEquals(ExitCodes.OK, run(false, "", "--no-agents").exit());
        Files.createDirectories(project.resolve(".claude"));
        Files.writeString(project.resolve(".claude/settings.json"), "{broken");

        Run run = run(false, "");

        assertEquals(10, run.exit(), run.out() + run.err());
        assertTrue(run.out().contains("AGENT_CLAUDE_CONNECT=FAILED"), run.out());
        List<String> errors = run.err().lines().toList();
        assertTrue(errors.contains("ERROR=INVALID_CONFIG"), run.err());
        assertTrue(errors.stream().anyMatch(line -> line.startsWith("MESSAGE=") && line.contains("configuration file cannot be read")), run.err());
        assertTrue(errors.stream().anyMatch(line -> line.startsWith("FIX=") && line.contains("synesis provider status <provider>")), run.err());
        assertEquals("{broken", Files.readString(project.resolve(".claude/settings.json")));
    }

    @Test
    void aFolderThatIsNotAGitRepositoryIsRefusedWithErrorMessageAndFixAndKeepsTheOldKeys() throws Exception {
        Path plain = Files.createDirectories(tmp.resolve("plain"));
        project = plain;

        Run run = run(false, "");

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, run.exit());
        assertTrue(run.out().contains("INIT_RESULT=CONFLICT"), run.out());
        List<String> errors = run.err().lines().toList();
        assertTrue(errors.contains("ERROR=GIT_REQUIRED") && errors.contains("ERROR_CODE=GIT_REQUIRED"), run.err());
        assertTrue(errors.stream().anyMatch(line -> line.startsWith("REASON=") && line.contains("requires a Git repository")), run.err());
        assertTrue(errors.stream().anyMatch(line -> line.startsWith("MESSAGE=") && line.contains("requires a Git repository")), run.err());
        assertTrue(errors.contains("FIX=run 'git init' in this folder, or run 'synesis init --git-init'"), run.err());
        assertFalse(Files.exists(plain.resolve(".synesis")));
    }

    @Test
    void aFolderThatDoesNotExistAndPartialStateAreRefusedWithAFix() throws Exception {
        project = tmp.resolve("missing");
        Run missing = run(false, "");
        assertEquals(ExitCodes.LOCAL_CONFIGURATION, missing.exit());
        assertTrue(missing.err().lines().anyMatch(line -> line.equals("ERROR=PROJECT_INVALID")), missing.err());
        assertTrue(missing.err().lines().anyMatch(line -> line.startsWith("MESSAGE=")) && missing.err().lines().anyMatch(line -> line.startsWith("FIX=")), missing.err());

        project = Files.createDirectories(tmp.resolve("partial"));
        GitProcessRunner.run(project, "init", "-q");
        Files.createDirectories(project.resolve(".synesis"));
        Run partial = run(false, "");
        assertEquals(ExitCodes.LOCAL_CONFIGURATION, partial.exit());
        assertTrue(partial.err().lines().anyMatch(line -> line.equals("ERROR=CONFLICT")), partial.err());
        assertTrue(partial.err().lines().anyMatch(line -> line.startsWith("MESSAGE=") && line.contains("partial or malformed")), partial.err());
        assertTrue(partial.err().lines().anyMatch(line -> line.startsWith("FIX=") && line.contains("synesis doctor")), partial.err());
    }

    @Test
    void optionMistakesAreRefusedBeforeAnythingIsWritten() throws Exception {
        Run unknown = run(false, "", "--agent", "gemini");
        assertEquals(ExitCodes.USAGE, unknown.exit());
        assertTrue(unknown.err().lines().anyMatch(line -> line.equals("ERROR=UNKNOWN_PROVIDER")), unknown.err());
        assertTrue(unknown.err().lines().anyMatch(line -> line.startsWith("FIX=") && line.contains("claude, codex")), unknown.err());

        Run conflict = run(false, "", "--agent", "claude", "--no-agents");
        assertEquals(ExitCodes.USAGE, conflict.exit());
        assertTrue(conflict.err().lines().anyMatch(line -> line.equals("ERROR=OPTIONS_CONFLICT")), conflict.err());
        assertTrue(conflict.err().lines().anyMatch(line -> line.startsWith("MESSAGE=")) && conflict.err().lines().anyMatch(line -> line.startsWith("FIX=")), conflict.err());

        assertFalse(Files.exists(project.resolve(".synesis")));
    }

    @Test
    void gitInitMakesARepositoryForScriptsAndAsksOnATerminal() throws Exception {
        project = Files.createDirectories(tmp.resolve("scripted"));
        Run scripted = run(false, "", "--git-init", "--no-agents");
        assertEquals(ExitCodes.OK, scripted.exit(), scripted.out() + scripted.err());
        assertTrue(scripted.out().contains("GIT_INIT=DONE"), scripted.out());
        assertTrue(Files.isDirectory(project.resolve(".git")));

        project = Files.createDirectories(tmp.resolve("declined"));
        Run declined = run(true, "n\n", "--no-agents");
        assertEquals(ExitCodes.LOCAL_CONFIGURATION, declined.exit());
        assertTrue(declined.out().contains("GIT_INIT_PROMPT="), declined.out());
        assertFalse(Files.exists(project.resolve(".git")));

        project = Files.createDirectories(tmp.resolve("accepted"));
        Run accepted = run(true, "y\n", "--no-agents");
        assertEquals(ExitCodes.OK, accepted.exit(), accepted.out() + accepted.err());
        assertTrue(Files.isDirectory(project.resolve(".git")));

        project = Files.createDirectories(tmp.resolve("piped"));
        assertEquals(ExitCodes.LOCAL_CONFIGURATION, run(false, "y\n", "--no-agents").exit(), "a script never gets the question");
    }
}
