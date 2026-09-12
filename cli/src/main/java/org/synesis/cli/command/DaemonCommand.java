package org.synesis.cli.command;

import java.util.concurrent.Callable;
import org.synesis.cli.daemon.InstallationDaemon;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Starts the per-user installation daemon foreground entrypoint. */
@Command(name = "daemon", description = "Run the local Synesis installation daemon.",
    mixinStandardHelpOptions = true)
public final class DaemonCommand implements Callable<Integer> {

  @Option(names = "--foreground", hidden = true, description = "Run as a foreground child.")
  private boolean foreground;

  /** Creates the daemon command. */
  public DaemonCommand() {
  }

  /**
   * Runs the daemon.
   *
   * @return stable process exit code
   */
  @Override
  public Integer call() {
    return new InstallationDaemon().run();
  }
}
