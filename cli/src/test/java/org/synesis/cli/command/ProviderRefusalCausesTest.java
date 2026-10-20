package org.synesis.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;

/**
 * Each way {@code provider install|status|list} (and {@code uninstall}, which shares the project lookup) refuses
 * names its own cause; none is reported as a project that was never set up, except one that is not.
 */
class ProviderRefusalCausesTest {

    private static final String[][] PROJECT_COMMANDS = {{"provider", "list"}, {"provider", "install", "codex"}, {"provider", "status", "codex"}, {"provider", "uninstall", "codex"}};

    @TempDir
    Path tmp;

    @Test
    void aProjectDirectoryThatDoesNotExistIsInvalidNotUnconfigured() {
        Path missing = tmp.resolve("missing");
        for (String[] command : PROJECT_COMMANDS) {
            CliHarness.run(tmp.resolve("profile"), withProject(command, missing)).assertIs(10, List.of(), CliHarness.shaped("PROJECT_INVALID", "REASON=Invalid project directory"));
        }
    }

    @Test
    void aProjectWhoseMetadataCannotBeReadIsMalformedNotUnconfigured() throws Exception {
        Path project = initialized("damaged");
        Files.writeString(project.resolve(".synesis/project.json"), "{ not json");
        for (String[] command : PROJECT_COMMANDS) {
            CliHarness.run(tmp.resolve("profile"), withProject(command, project)).assertIs(10, List.of(), CliHarness.shaped("MALFORMED", "REASON=Project metadata is malformed"));
        }
    }

    @Test
    void anUnknownProviderIsNamedUnknown() throws Exception {
        Path project = initialized("unknown-provider");
        CliHarness.run(tmp.resolve("profile"), withProject(new String[]{"provider", "install", "nope"}, project)).assertIs(2,
                List.of("PROVIDER_INSTALL_RESULT=UNKNOWN_PROVIDER", "ERROR=UNKNOWN_PROVIDER", "PROVIDER=nope", "~MESSAGE=.{6,}", "~FIX=.{6,}"), List.of());
        CliHarness.run(tmp.resolve("profile"), withProject(new String[]{"provider", "status", "nope"}, project)).assertIs(2,
                List.of("PROVIDER_STATUS=UNKNOWN_PROVIDER", "ERROR=UNKNOWN_PROVIDER", "PROVIDER=nope", "SESSION_BINDING=UNBOUND", "CONTINUITY_MODE=SESSION_BOUND", "~MESSAGE=.{6,}", "~FIX=.{6,}"), List.of());
    }

    @Test
    void aMissingLauncherIsNamedAsTheLauncher() throws Exception {
        Assumptions.assumeFalse(Files.isRegularFile(new ProviderApplicationService().launcherPath()), "a Synesis launcher is installed or on the PATH");
        Path project = initialized("no-launcher");
        CliHarness.run(tmp.resolve("profile"), withProject(new String[]{"provider", "install", "codex"}, project)).assertIs(10,
                List.of("PROVIDER_INSTALL_RESULT=LAUNCHER_MISSING", "ERROR=LAUNCHER_MISSING", "PROVIDER=codex", "~MESSAGE=.{6,}", "~FIX=.{6,}"), List.of());
    }

    @Test
    void aFailureThatIsNotAboutTheProjectNamesItsReason() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        CliRuntime runtime = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);

        assertEquals(10, ProjectOptions.refuse(runtime, new IOException("disk gone")));

        ErrorShapeAssert.assertShaped(err.toString(StandardCharsets.UTF_8).lines().toList(), "PROJECT_ACCESS_FAILED", "REASON=disk gone");
    }

    private Path initialized(String name) throws Exception {
        Path project = Files.createDirectories(tmp.resolve(name));
        new ProjectApplicationService().init(project);
        return project;
    }

    private static String[] withProject(String[] command, Path project) {
        return Stream.concat(Arrays.stream(command), Stream.of("--project", project.toString())).toArray(String[]::new);
    }
}
