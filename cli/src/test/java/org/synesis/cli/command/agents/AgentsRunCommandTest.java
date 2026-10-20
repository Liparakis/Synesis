package org.synesis.cli.command.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.daemon.ProjectRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;

import org.synesis.coordination.persistence.ReplicationSettings;

import org.synesis.link.onboarding.Onboarding;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.run.AgentRunController;
import org.synesis.workspace.application.project.ProjectLocation;

import picocli.CommandLine;

/**
 * Verifies how an {@code --agent} specification names a provider and its model, and that agents start only once the
 * project's runtime, the process that holds its Link sessions, is running or known to be unneeded.
 */
final class AgentsRunCommandTest {

    @TempDir
    Path tmp;
    private Path root;
    private final List<Path> ensured = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));

    @BeforeEach
    void project() throws Exception {
        root = Files.createDirectories(tmp.resolve("project"));
        new ProjectApplicationService().init(root);
    }

    @Test
    void agentSpecificationNamesProviderThenOptionalModelAndEffort() {
        AgentRunController.AgentSpec bare = AgentsRunCommand.parseAgent("codex");
        assertEquals("codex", bare.provider());
        assertNull(bare.options().model());
        assertNull(bare.options().effort());

        AgentRunController.AgentSpec full = AgentsRunCommand.parseAgent("claude:sonnet:low");
        assertEquals("claude", full.provider());
        assertEquals("sonnet", full.options().model());
        assertEquals("low", full.options().effort());

        assertEquals("medium", AgentsRunCommand.parseAgent("codex::medium").options().effort(), "a model may be left to the provider default");
    }

    @Test
    void malformedOrUnknownSpecificationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AgentsRunCommand.parseAgent("gemini"));
        assertThrows(IllegalArgumentException.class, () -> AgentsRunCommand.parseAgent(""));
        assertThrows(IllegalArgumentException.class, () -> AgentsRunCommand.parseAgent("claude:sonnet:low:extra"));
        assertThrows(IllegalArgumentException.class, () -> AgentsRunCommand.parseAgent("claude:sonnet --dangerously-skip-permissions"));
        assertThrows(IllegalArgumentException.class, () -> AgentsRunCommand.parseAgent(null));
    }

    @Test
    void aSharedProjectWhoseRuntimeCannotStartStartsNoAgent() throws Exception {
        share();
        // Marks the provider installed, as `synesis provider install codex` records it, without its hooks.
        Path metadata = root.resolve(".synesis/local/providers/codex.json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "{}");
        CliRuntime cli = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);

        int exit = new CommandLine(new AgentsRunCommand(cli, unavailable())).execute("--project", root.toString(), "--agent", "codex", "--prompt", "work");

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, exit, output());
        assertEquals(List.of(root.toRealPath()), realPaths(), "the runtime is asked for before any agent");
        assertTrue(stderr().contains("ERROR=PROJECT_RUNTIME_UNAVAILABLE"), output());
        assertTrue(stderr().contains("REASON=daemon_not_ready"), output());
        assertFalse(stdout().stream().anyMatch(line -> line.startsWith("AGENT_LOG_DIRECTORY=")), "no agent was started: " + output());
    }

    @Test
    void theRuntimeIsStartedBeforeAgentsOfASharedProject() throws Exception {
        share();

        assertTrue(AgentsRunCommand.ensureRuntime(location(), ensured::add, terminal));

        assertEquals(List.of(root.toRealPath()), realPaths());
        assertEquals(List.of("PROJECT_RUNTIME=READY"), stdout(), output());
        assertEquals(List.of(), stderr(), output());
    }

    @Test
    void aSingleComputerProjectWhoseRuntimeCannotStartIsWarnedAndGoesOn() throws Exception {
        assertTrue(AgentsRunCommand.ensureRuntime(location(), unavailable(), terminal));

        assertEquals(List.of("PROJECT_RUNTIME=UNAVAILABLE", "PROJECT_RUNTIME_REASON=daemon_not_ready"), stderr(), output());
        assertEquals(List.of(), stdout(), output());
    }

    @Test
    void aSharedProjectWhoseRuntimeCannotStartIsRefused() throws Exception {
        share();

        assertFalse(AgentsRunCommand.ensureRuntime(location(), unavailable(), terminal));

        assertEquals("ERROR=PROJECT_RUNTIME_UNAVAILABLE", stderr().getFirst(), output());
        assertTrue(stderr().contains("REASON=daemon_not_ready"), output());
    }

    @Test
    void aRoleFileThatCannotBeReadCountsAsShared() throws Exception {
        Path coordination = Files.createDirectories(root.resolve(".synesis/coordination"));
        Files.writeString(coordination.resolve(ReplicationSettings.FILE), "role=unknown\n");

        assertFalse(AgentsRunCommand.ensureRuntime(location(), unavailable(), terminal), output());
    }

    @Test
    void aRunWithoutATaskIsRefusedNamingTheFixAndStartsNoAgent() throws Exception {
        Path emptyFile = Files.writeString(tmp.resolve("empty.txt"), "  \n");
        CliRuntime cli = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);
        for (List<String> task : List.of(List.<String>of(), List.of("--prompt", " "), List.of("--prompt-file", emptyFile.toString()))) {
            List<String> arguments = new ArrayList<>(List.of("--project", root.toString(), "--agent", "codex"));
            arguments.addAll(task);

            int exit = new CommandLine(new AgentsRunCommand(cli, unavailable())).execute(arguments.toArray(String[]::new));

            assertEquals(ExitCodes.USAGE, exit, output());
        }
        assertEquals(List.of("ERROR=agents run needs a task; pass --prompt \"<task>\" or --prompt-file <file>"), stderr().stream().distinct().toList(), output());
        assertEquals(List.of(), ensured, "no runtime was asked for, so no agent could start");
        assertEquals(List.of(), stdout(), "no agent log directory or run result was printed");
    }

    @Test
    void aPromptAndAPromptFileTogetherAreRefused() throws Exception {
        CliRuntime cli = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);

        int exit = new CommandLine(new AgentsRunCommand(cli, unavailable())).execute("--project", root.toString(), "--agent", "codex", "--prompt", "work", "--prompt-file", tmp.resolve("p.txt").toString());

        assertEquals(ExitCodes.USAGE, exit, output());
        assertEquals(List.of("ERROR=give only one of --prompt and --prompt-file"), stderr(), output());
    }

    private void share() throws IOException {
        ReplicationSettings.leader(Set.of("member-node")).write(Files.createDirectories(root.resolve(".synesis/coordination")));
    }

    private ProjectRuntime unavailable() {
        return project -> {
            ensured.add(project);
            throw new IOException("daemon_not_ready");
        };
    }

    private ProjectLocation location() throws Exception {
        return new ProjectApplicationService().locate(root);
    }

    private List<Path> realPaths() throws IOException {
        List<Path> real = new ArrayList<>();
        for (Path path : ensured) {
            real.add(path.toRealPath());
        }
        return real;
    }

    private List<String> stdout() {
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private List<String> stderr() {
        return err.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private String output() {
        return "stdout=" + stdout() + " stderr=" + stderr();
    }
}
