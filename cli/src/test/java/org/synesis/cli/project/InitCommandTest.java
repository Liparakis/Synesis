package org.synesis.cli.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.cli.SynesisCli;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.onboarding.Onboarding;

/**
 * Verifies the user-facing Git precondition for project initialization.
 */
final class InitCommandTest {

    private Path root;

    private static PrintStream stream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("synesis-cli-init-");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (root != null) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                        // Best-effort cleanup for the isolated fixture.
                    }
                });
            }
        }
    }

    /**
     * ADR-0103 stage 1: init names the steps that make Synesis useful (connect an agent, start it, look at
     * what happens), not the constraint command it named before, and writes no instruction file (decision 9).
     */
    @Test
    void initPrintsTheRightNextStepsAndWritesNoInstructionFile() throws Exception {
        org.synesis.workspace.lifecycle.process.GitProcessRunner.run(root, "init", "-q");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(stream(output), stream(new ByteArrayOutputStream()));
        CliRuntime runtime = new CliRuntime(new Onboarding(root.resolve("profile"), new StatusRenderer(terminal)), terminal);

        int exitCode = SynesisCli.execute(new String[]{"init", "--no-agents", "--project", root.toString()}, runtime);

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(ExitCodes.OK, exitCode, text);
        assertTrue(text.contains("INIT_RESULT=SUCCESS"), text);
        assertTrue(text.contains("NEXT_COMMAND=synesis provider install claude"), text);
        assertTrue(text.contains("NEXT_STEP=Connect an agent to this project: synesis provider install claude (or: synesis provider install codex)"), text);
        assertTrue(text.contains("NEXT_STEP=Start the agent in this folder (claude or codex) and tell it what to do"), text);
        assertTrue(text.contains("synesis status"), text);
        assertFalse(text.contains("constraint create"), text);
        assertFalse(text.contains("INSTRUCTIONS_REMOVED"), text);
        assertFalse(Files.exists(root.resolve("AGENTS.md")));
    }

    @Test
    void initReportsTheOldSectionItRemovedFromAgentsMd() throws Exception {
        org.synesis.workspace.lifecycle.process.GitProcessRunner.run(root, "init", "-q");
        new org.synesis.workspace.application.ProjectApplicationService().init(root, false);
        // A project initialized by an earlier version carries the section its init wrote.
        Files.writeString(root.resolve("AGENTS.md"), "# Mine\n\n<!-- SYNESIS-BEGIN -->\nold\n<!-- SYNESIS-END -->\n");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(stream(output), stream(new ByteArrayOutputStream()));
        CliRuntime runtime = new CliRuntime(new Onboarding(root.resolve("profile"), new StatusRenderer(terminal)), terminal);

        assertEquals(ExitCodes.OK, SynesisCli.execute(new String[]{"init", "--no-agents", "--project", root.toString()}, runtime));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("INSTRUCTIONS_REMOVED=AGENTS.md"), text);
        assertTrue(text.contains("NEXT_STEP=Commit the change to AGENTS.md"), text);
        assertEquals("# Mine\n", Files.readString(root.resolve("AGENTS.md")));
    }

    @Test
    void initFailsLoudlyBeforeCreatingSynesisStateOutsideGit() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(stream(output), stream(errors));
        Path profile = root.resolve("profile");
        CliRuntime runtime = new CliRuntime(new Onboarding(profile, new StatusRenderer(terminal)), terminal);

        int exitCode = SynesisCli.execute(new String[]{"init", "--project", root.toString()}, runtime);

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, exitCode);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("ERROR_CODE=GIT_REQUIRED"));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("requires a Git repository"));
        assertFalse(Files.exists(root.resolve(".synesis")));
    }
}
