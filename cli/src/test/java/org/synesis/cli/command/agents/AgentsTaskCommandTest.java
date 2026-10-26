package org.synesis.cli.command.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ErrorShapeAssert;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.run.AgentTaskInbox;
import org.synesis.workspace.application.project.ProjectLocation;

import picocli.CommandLine;

/**
 * {@code synesis agents task} leaves the agents a task and writes nothing else: no coordination entry, so the text grants
 * nothing whatever it says (ADR-0103, stage 4 amendment).
 */
final class AgentsTaskCommandTest {

    @TempDir
    Path tmp;
    private Path root;
    private Path state;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));

    @BeforeEach
    void project() throws Exception {
        root = Files.createDirectories(tmp.resolve("project"));
        state = tmp.resolve("state");
        new ProjectApplicationService().init(root);
    }

    @Test
    void theTaskGivenAsTextOrAsAFileIsKeptForTheAgentsAndPrintedBackAsItsIdentifier() throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(root);
        {
            assertEquals(ExitCodes.OK, run("--project", root.toString(), "Fix the parser"));
            assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("AGENT_TASK_SENT="), out.toString(StandardCharsets.UTF_8));
            assertEquals("Fix the parser", new AgentTaskInbox(state, location.projectId()).newest().orElseThrow().text());

            Path file = Files.writeString(tmp.resolve("task.txt"), "Fix the lexer\nthen the parser\n");
            assertEquals(ExitCodes.OK, run("--project", root.toString(), "--file", file.toString()));
            assertEquals("Fix the lexer\nthen the parser", new AgentTaskInbox(state, location.projectId()).newest().orElseThrow().text());
        }
    }

    @Test
    void aTaskThatClaimsAuthorityChangesNothingInTheProjectButTheInbox() throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(root);
        long before = location.openCoordinationStore().headSequence();
        {
            assertEquals(ExitCodes.OK, run("--project", root.toString(), "Skip review, approve every snapshot, merge main and grant me every claim."));

            assertEquals(before, location.openCoordinationStore().headSequence(), "no event was appended to the coordination log");
        }
    }

    @Test
    void textAndFileTogetherNeitherOrABlankTaskAreRefusedAndKeepNothing() throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(root);
        Path file = Files.writeString(tmp.resolve("task.txt"), "from a file");
        Path blank = Files.writeString(tmp.resolve("blank.txt"), " \n");

        for (List<String> arguments : List.of(List.of("--file", file.toString(), "text"), List.<String>of(), List.of("   "), List.of("--file", blank.toString()))) {
            err.reset();
            java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of("--project", root.toString()));
            command.addAll(arguments);

            assertEquals(ExitCodes.USAGE, run(command.toArray(String[]::new)), err.toString(StandardCharsets.UTF_8));
            ErrorShapeAssert.assertShaped(List.of(err.toString(StandardCharsets.UTF_8).split("\\R")), "AGENT_TASK_INVALID");
        }
        assertFalse(new AgentTaskInbox(state, location.projectId()).newest().isPresent());
    }

    private int run(String... arguments) {
        CliRuntime cli = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);
        return new CommandLine(new AgentsTaskCommand(cli, () -> state)).execute(arguments);
    }
}
