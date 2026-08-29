package org.synesis.cli.lifecycle;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.cli.SynesisCli;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.lifecycle.CleanupCommand;
import org.synesis.cli.diagnostics.ReadinessInspector;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.workspace.application.ProjectApplicationService;

class CleanupCommandTest {

    private static Invocation createInvocation(Path profile) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(stream(out), stream(err));
        StatusRenderer renderer = new StatusRenderer(terminal);
        CliRuntime runtime = new CliRuntime(new Onboarding(profile, renderer),
                terminal,
                new ReadinessInspector(profile));
        return new Invocation(runtime, out, err);
    }

    private static PrintStream stream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }

    @Test
    void failsSafelyWithoutDryRunFlag(@TempDir Path tempDir) {
        Invocation invocation = createInvocation(tempDir);

        CleanupCommand command = new CleanupCommand(invocation.runtime());
        int exitCode = command.call();

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, exitCode);
        assertTrue(invocation.errorOutput()
                .contains("Cleanup execution is not available in this version"));
    }

    @Test
    void outputsConciseSummaryWithDryRunFlag(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("my-project");
        Files.createDirectories(projectRoot);
        new ProjectApplicationService().init(projectRoot);

        Invocation invocation = createInvocation(tempDir);

        int exitCode = SynesisCli.execute(new String[]{"cleanup", "--dry-run", "--project", projectRoot.toString()},
                invocation.runtime());

        assertEquals(ExitCodes.OK, exitCode);
        String stdout = invocation.output();
        assertTrue(stdout.contains("CLEANUP_RESULT=DRY_RUN"));
        assertTrue(stdout.contains("PROJECT=READY"));
        assertTrue(stdout.contains("MUTATIONS_PERFORMED=0"));
        assertTrue(stdout.contains("NEXT_ACTION=review_with_verbose_output"));
    }

    @Test
    void outputsValidJsonWithJsonFlag(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("my-project");
        Files.createDirectories(projectRoot);
        new ProjectApplicationService().init(projectRoot);

        Invocation invocation = createInvocation(tempDir);

        int exitCode = SynesisCli.execute(new String[]{"cleanup", "--dry-run", "--json", "--project",
                projectRoot.toString()}, invocation.runtime());

        assertEquals(ExitCodes.OK, exitCode);
        String stdout = invocation.output();
        assertTrue(stdout.contains("\"cleanupResult\":\"DRY_RUN\""));
        assertTrue(stdout.contains("\"mutationsPerformed\":0"));
    }

    private record Invocation(CliRuntime runtime, ByteArrayOutputStream out, ByteArrayOutputStream err) {

        private String output() {
            return out.toString(StandardCharsets.UTF_8);
        }

        private String errorOutput() {
            return err.toString(StandardCharsets.UTF_8);
        }
    }
}
