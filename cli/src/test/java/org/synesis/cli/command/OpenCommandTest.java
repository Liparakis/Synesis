package org.synesis.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.diagnostics.ReadinessInspector;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.onboarding.Onboarding;
import picocli.CommandLine;

/** Tests the short-lived URI activation command as an argv boundary. */
final class OpenCommandTest {

  @Test
  void forwardsOneArgumentByteForByteWithoutChoosingAProject() throws Exception {
    Path profile = Files.createTempDirectory("synesis-open-test").resolve("profile");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    ConsoleTerminal terminal = new ConsoleTerminal(stream(out), stream(err));
    CliRuntime runtime = new CliRuntime(new Onboarding(profile, new StatusRenderer(terminal)),
        terminal, new ReadinessInspector(profile));
    AtomicReference<String> received = new AtomicReference<>();
    String uri = "synesis://join/a%2Fb?x=a%26b+%25#frag with & | ;";
    OpenCommand command = new OpenCommand(runtime, (ignored, supplied) -> {
      received.set(supplied);
      return Map.of("ok", true, "state", "PROJECT_SELECTION_REQUIRED");
    });

    assertEquals(ExitCodes.OK, new CommandLine(command).execute(uri));
    assertEquals(uri, received.get());
    assertTrue(out.toString(StandardCharsets.UTF_8).contains(
        "SYNESIS_OPEN_RESULT=PROJECT_SELECTION_REQUIRED"));
    assertTrue(err.toString(StandardCharsets.UTF_8).isEmpty());
  }

  @Test
  void requiresExactlyOneUriArgument() throws Exception {
    Path profile = Files.createTempDirectory("synesis-open-arity").resolve("profile");
    ConsoleTerminal terminal = new ConsoleTerminal();
    CliRuntime runtime = new CliRuntime(new Onboarding(profile,
        new StatusRenderer(terminal)), terminal, new ReadinessInspector(profile));
    OpenCommand command = new OpenCommand(runtime, (ignored, supplied) ->
        Map.of("ok", true, "state", "DISPATCHED"));

    assertEquals(ExitCodes.USAGE, new CommandLine(command).execute());
    assertEquals(ExitCodes.USAGE, new CommandLine(command).execute("synesis://a",
        "synesis://b"));
  }

  @Test
  void mapsUnresolvedRoutingToBoundedFailure() throws Exception {
    Path profile = Files.createTempDirectory("synesis-open-unresolved").resolve("profile");
    ConsoleTerminal terminal = new ConsoleTerminal();
    CliRuntime runtime = new CliRuntime(new Onboarding(profile,
        new StatusRenderer(terminal)), terminal, new ReadinessInspector(profile));
    OpenCommand command = new OpenCommand(runtime, (ignored, supplied) ->
        Map.of("ok", true, "state", "URI_UNRESOLVED"));

    assertEquals(ExitCodes.LOCAL_CONFIGURATION,
        new CommandLine(command).execute("synesis://join/invalid"));
  }

  private static PrintStream stream(ByteArrayOutputStream target) {
    return new PrintStream(target, true, StandardCharsets.UTF_8);
  }
}
