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
import org.synesis.cli.command.ErrorShapeAssert;
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
        assertEquals(List.of("ERROR=TASK_REQUIRED", "MESSAGE=agents run needs a task", "FIX=pass --prompt \"<task>\" or --prompt-file <file>"), stderr().stream().distinct().toList(), output());
        assertEquals(List.of(), ensured, "no runtime was asked for, so no agent could start");
        assertEquals(List.of(), stdout(), "no agent log directory or run result was printed");
    }

    @Test
    void aPromptAndAPromptFileTogetherAreRefused() throws Exception {
        CliRuntime cli = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);

        int exit = new CommandLine(new AgentsRunCommand(cli, unavailable())).execute("--project", root.toString(), "--agent", "codex", "--prompt", "work", "--prompt-file", tmp.resolve("p.txt").toString());

        assertEquals(ExitCodes.USAGE, exit, output());
        ErrorShapeAssert.assertShaped(stderr(), "PROMPT_CONFLICT");
    }

    @Test
    void everyOtherUsageRefusalNamesWhatWasTypedAndHowToFixIt() throws Exception {
        CliRuntime cli = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);

        assertEquals(ExitCodes.USAGE, new CommandLine(new AgentsRunCommand(cli, unavailable())).execute("--project", root.toString(), "--agent", "gemini", "--prompt", "work"), output());
        ErrorShapeAssert.assertShaped(stderr(), "UNKNOWN_PROVIDER");
        assertEquals("MESSAGE=unknown provider: gemini", stderr().get(1));
        err.reset();

        assertEquals(ExitCodes.USAGE, new CommandLine(new AgentsRunCommand(cli, unavailable())).execute("--project", root.toString(), "--agent", "claude:a:b:c", "--prompt", "work"), output());
        ErrorShapeAssert.assertShaped(stderr(), "AGENT_INVALID");
        err.reset();

        assertEquals(ExitCodes.USAGE, new CommandLine(new AgentsRunCommand(cli, unavailable())).execute("--project", root.toString(), "--agent", "codex", "--prompt", "work"), output());
        ErrorShapeAssert.assertShaped(stderr(), "PROVIDER_NOT_INSTALLED");
        assertEquals("FIX=run 'synesis provider install codex'", stderr().get(2));
        assertEquals(List.of(), ensured, "no agent could start");
    }

    @Test
    void aPersonAtATerminalIsAskedOnceForTheTaskAndSeesItBack() throws Exception {
        share();
        installedCodex();

        int exit = new CommandLine(new AgentsRunCommand(cliWithInput(true, "  fix the build  \n"), unavailable())).execute("--project", root.toString(), "--agent", "codex");

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, exit, output());
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("What should the agents work on? AGENT_TASK=fix the build"), output());
        assertEquals(List.of(root.toRealPath()), realPaths(), "the task was accepted, so the run went on to the runtime before any agent");
    }

    @Test
    void anEmptyAnswerOrNoAnswerIsTheSameRefusalAsNoTaskAndStartsNothing() throws Exception {
        installedCodex();
        for (String typed : List.of("\n", "   \n", "")) {
            err.reset();

            int exit = new CommandLine(new AgentsRunCommand(cliWithInput(true, typed), unavailable())).execute("--project", root.toString(), "--agent", "codex");

            assertEquals(ExitCodes.USAGE, exit, output());
            assertEquals("ERROR=TASK_REQUIRED", stderr().getFirst(), output());
        }
        assertEquals(List.of(), ensured, "no runtime was asked for, so no agent could start");
    }

    @Test
    void theQuestionIsNotAskedBeforeTheRestIsChecked() throws Exception {
        java.io.ByteArrayInputStream typed = new java.io.ByteArrayInputStream("a task\n".getBytes(StandardCharsets.UTF_8));
        CliRuntime cli = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), typed, true));

        int exit = new CommandLine(new AgentsRunCommand(cli, unavailable())).execute("--project", root.toString(), "--agent", "codex");

        assertEquals(ExitCodes.USAGE, exit, output());
        assertEquals("ERROR=PROVIDER_NOT_INSTALLED", stderr().getFirst(), output());
        assertFalse(out.toString(StandardCharsets.UTF_8).contains("What should"), "a person is not asked a question that cannot be answered yet: " + output());
        assertEquals("a task\n".length(), typed.available(), "nothing was read");
    }

    @Test
    void aScriptIsNeverAskedAndRefusesEvenWhenSomethingCouldBeRead() throws Exception {
        installedCodex();
        java.io.ByteArrayInputStream typed = new java.io.ByteArrayInputStream("a task\n".getBytes(StandardCharsets.UTF_8));
        CliRuntime cli = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), typed, false));

        int exit = new CommandLine(new AgentsRunCommand(cli, unavailable())).execute("--project", root.toString(), "--agent", "codex");

        assertEquals(ExitCodes.USAGE, exit, output());
        assertEquals("ERROR=TASK_REQUIRED", stderr().getFirst(), output());
        assertEquals(List.of(), stdout(), "no question was printed");
        assertEquals("a task\n".length(), typed.available(), "standard input was not read");
    }

    @Test
    void aBlankTaskThatWasGivenIsRefusedEvenAtATerminal() throws Exception {
        installedCodex();

        int exit = new CommandLine(new AgentsRunCommand(cliWithInput(true, "a task\n"), unavailable())).execute("--project", root.toString(), "--agent", "codex", "--prompt", " ");

        assertEquals(ExitCodes.USAGE, exit, output());
        assertEquals("ERROR=TASK_REQUIRED", stderr().getFirst(), output());
        assertFalse(out.toString(StandardCharsets.UTF_8).contains("What should"), output());
    }

    /** ADR-0103 stage 4: waiting for a task is asked for by name; a task and waiting for one together are refused. */
    @Test
    void waitForTaskTakesNeitherAPromptNorAPromptFile() throws Exception {
        installedCodex();
        CliRuntime cli = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);

        assertEquals(ExitCodes.USAGE, new CommandLine(new AgentsRunCommand(cli, unavailable())).execute("--project", root.toString(), "--agent", "codex", "--wait-for-task", "--prompt", "work"), output());
        ErrorShapeAssert.assertShaped(stderr(), "WAIT_FOR_TASK_CONFLICT");
        err.reset();
        assertEquals(ExitCodes.USAGE, new CommandLine(new AgentsRunCommand(cli, unavailable())).execute("--project", root.toString(), "--agent", "codex", "--wait-for-task", "--prompt-file", tmp.resolve("p.txt").toString()),
                output());
        ErrorShapeAssert.assertShaped(stderr(), "WAIT_FOR_TASK_CONFLICT");
        assertEquals(List.of(), ensured, "no agent could start");
    }

    @Test
    void waitForTaskRegistersTheAgentsAsReadyAsksNothingAndStartsNoProcess() throws Exception {
        installedCodex();
        CliRuntime cli = cliWithInput(true, "an answer that must not be read\n");
        int[] exit = {Integer.MIN_VALUE};
        Thread run = new Thread(() -> exit[0] = new CommandLine(new AgentsRunCommand(cli, unavailable())).execute("--project", root.toString(), "--agent", "codex:gpt-6-luna:high", "--wait-for-task"));
        run.start();
        long until = System.currentTimeMillis() + 20_000;
        while (!out.toString(StandardCharsets.UTF_8).contains("AGENT_READY=codex#1") && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        run.interrupt();
        run.join(20_000);

        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("AGENT_READY=codex#1"), output());
        assertTrue(printed.contains("HINT=give the agents their task with: synesis agents task"), output());
        assertFalse(printed.contains("What should"), "a person who asked to wait is not asked for a task");
        assertFalse(printed.contains("AGENT_STARTED"), "no process was started");
        assertEquals(ExitCodes.COMMAND_FAILED, exit[0], output());
        assertTrue(printed.contains("AGENT_RUN_WAITING=TASK_WANTED") || stderr().contains("ERROR=AGENT_RUN_INTERRUPTED"), output());
    }

    private CliRuntime cliWithInput(boolean interactive, String typed) {
        ConsoleTerminal person = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                new java.io.ByteArrayInputStream(typed.getBytes(StandardCharsets.UTF_8)), interactive);
        return new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(person)), person);
    }

    /** Marks the provider installed, as {@code synesis provider install codex} records it, without its hooks. */
    private void installedCodex() throws IOException {
        Path metadata = root.resolve(".synesis/local/providers/codex.json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "{}");
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
