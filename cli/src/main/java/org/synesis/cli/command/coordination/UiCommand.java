package org.synesis.cli.command.coordination;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.Map;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.daemon.DaemonClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Starts the local Synesis runtime and opens the installed browser UI.
 */
@Command(name = "ui", description = "Start Synesis locally and open the browser UI.", mixinStandardHelpOptions = true)
public final class UiCommand implements Callable<Integer> {

  private final CliRuntime runtime;
  @Option(names = "--project", description = "Initialized project directory.")
  private Path project;
  @Option(names = "--data", description = "Coordinator state directory.")
  private Path data;
  @Option(names = "--identity", description = "Coordinator identity directory.")
  private Path identity;
  @Option(names = "--host", defaultValue = "127.0.0.1")
  private String host;
  @Option(names = "--port", defaultValue = "0")
  private int port;
  @Option(names = "--duration-seconds", defaultValue = "0", description = "Bounded duration for smoke tests.")
  private int durationSeconds;
  @Option(names = "--no-browser", description = "Start the UI server without opening a browser.")
  private boolean noBrowser;

  /**
   * Creates the UI command.
   *
   * @param runtime composed CLI runtime
   */
  public UiCommand(CliRuntime runtime) {
    this.runtime = runtime;
  }

  /**
   * Starts the local UI server.
   *
   * @return stable process exit code
   */
  @Override
  public Integer call() {
    if (durationSeconds <= 0) {
      try {
        Map<String, Object> response = DaemonClient.request(runtime, "OPEN_UI", project, null,
            !noBrowser);
        if (Boolean.TRUE.equals(response.get("browserOpened"))) {
          runtime.terminal().stdout("SYNESIS_UI_OPENED");
        } else if ("BROWSER_OPEN_FAILED".equals(response.get("state"))) {
          runtime.terminal().stdout("SYNESIS_UI_OPEN_FAILED");
        }
        return 0;
      } catch (java.io.IOException failure) {
        runtime.terminal().stderr("SYNESIS_DAEMON_ERROR=" + failure.getMessage());
        return org.synesis.cli.exit.ExitCodes.LOCAL_CONFIGURATION;
      }
    }
    return CoordinationServerLauncher.run(runtime, project, data, identity, host, port,
        durationSeconds,
        true, !noBrowser);
  }
}
