package org.synesis.cli.command;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.daemon.DaemonClient;
import org.synesis.cli.exit.ExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Delivers one externally supplied Synesis URI to the installed daemon.
 *
 * <p>This command is intentionally a transport boundary. URI validation,
 * project selection, Link verification, and runtime authorization remain in
 * the daemon and project runtime.</p>
 */
@Command(name = "open", description = "Open one supported Synesis URI.",
    mixinStandardHelpOptions = true)
public final class OpenCommand implements Callable<Integer> {

  @FunctionalInterface
  interface UriRequester {
    Map<String, Object> request(CliRuntime runtime, String uri) throws IOException;
  }

  private final CliRuntime runtime;
  private final UriRequester requester;

  @Parameters(index = "0", arity = "1", description = "One synesis:// URI.")
  private String uri;

  /**
   * Creates the production activation command.
   *
   * @param runtime composed CLI runtime
   */
  public OpenCommand(CliRuntime runtime) {
    this(runtime, (value, suppliedUri) -> DaemonClient.request(value, "OPEN_URI", null,
        suppliedUri, true));
  }

  OpenCommand(CliRuntime runtime, UriRequester requester) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.requester = Objects.requireNonNull(requester, "requester");
  }

  /**
   * Sends the exact parsed argument to the daemon and reports its bounded
   * result.
   *
   * @return zero when dispatch or the existing selection UI was initiated;
   *     a stable local-configuration failure otherwise
   */
  @Override
  public Integer call() {
    try {
      Map<String, Object> response = requester.request(runtime, uri);
      String state = String.valueOf(response.getOrDefault("state", "COMPLETED"));
      runtime.terminal().stdout("SYNESIS_OPEN_RESULT=" + state);
      if ("URI_UNRESOLVED".equals(state) || "URI_AMBIGUOUS".equals(state)) {
        return ExitCodes.LOCAL_CONFIGURATION;
      }
      return ExitCodes.OK;
    } catch (IOException failure) {
      runtime.terminal().stderr("SYNESIS_OPEN_ERROR=" + failure.getMessage());
      return ExitCodes.LOCAL_CONFIGURATION;
    }
  }
}
