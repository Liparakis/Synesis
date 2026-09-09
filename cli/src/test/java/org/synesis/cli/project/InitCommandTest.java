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
import org.synesis.cli.diagnostics.ReadinessInspector;
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
        paths.sorted(java.util.Comparator.reverseOrder())
            .forEach(path -> {
              try {
                Files.deleteIfExists(path);
              } catch (java.io.IOException ignored) {
                // Best-effort cleanup for the isolated fixture.
              }
            });
      }
    }
  }

  @Test
  void initFailsLoudlyBeforeCreatingSynesisStateOutsideGit() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    ConsoleTerminal terminal = new ConsoleTerminal(stream(output), stream(errors));
    Path profile = root.resolve("profile");
    CliRuntime runtime = new CliRuntime(new Onboarding(profile, new StatusRenderer(terminal)),
        terminal,
        new ReadinessInspector(profile));

    int exitCode = SynesisCli.execute(new String[]{"init", "--project", root.toString()}, runtime);

    assertEquals(ExitCodes.LOCAL_CONFIGURATION, exitCode);
    assertTrue(errors.toString(StandardCharsets.UTF_8)
        .contains("ERROR_CODE=GIT_REQUIRED"));
    assertTrue(errors.toString(StandardCharsets.UTF_8)
        .contains("requires a Git repository"));
    assertFalse(Files.exists(root.resolve(".synesis")));
  }
}
