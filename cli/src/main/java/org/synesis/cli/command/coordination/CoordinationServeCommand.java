package org.synesis.cli.command.coordination;


import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Starts the loopback coordination server for one project.
 */
@Command(name = "serve", description = "Serve signed coordination events on loopback.", mixinStandardHelpOptions = true)
public final class CoordinationServeCommand implements Callable<Integer> {

  private final CliRuntime runtime;
  @Option(names = "--project", description = "Initialized project directory.")
  private Path project;
  @Option(names = "--data", description = "Coordinator state directory.")
  private Path data;
  @Option(names = "--identity", description = "Coordinator identity directory.")
  private Path identity;
  @Option(names = "--host", defaultValue = "127.0.0.1")
  private String host;
  @Option(names = "--port", defaultValue = "48123")
  private int port;
  @Option(names = "--duration-seconds", defaultValue = "0")
  private int durationSeconds;
  @Option(names = "--parent-pid", defaultValue = "-1", hidden = true)
  private long parentPid;

  /**
   * Creates a server command.
   *
   * @param runtime composed CLI runtime
   */
  public CoordinationServeCommand(CliRuntime runtime) {
    this.runtime = runtime;
  }

  /**
   * Starts the server and blocks until interrupted or the optional duration elapses.
   *
   * @return stable process exit code
   */
  @Override
  public Integer call() {
    return CoordinationServerLauncher.run(runtime, project, data, identity, host, port,
        durationSeconds,
        false, false, parentPid);
  }
}
