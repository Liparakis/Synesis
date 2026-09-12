package org.synesis.cli.daemon;

import java.io.IOException;

/**
 * Foreground lifecycle wrapper for the per-user installation daemon.
 */
public final class InstallationDaemon {

  /** Creates an installation-daemon runner. */
  public InstallationDaemon() {
  }

  /**
   * Runs one daemon until interrupted or closed by its process.
   *
   * @return stable process exit code
   */
  public int run() {
    DaemonServer server;
    try {
      server = DaemonServer.start();
    } catch (IOException alreadyRunning) {
      return alreadyRunning.getMessage() != null
          && alreadyRunning.getMessage().contains("daemon_already_running") ? 0 : 10;
    }
    Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(server),
        "synesis-daemon-shutdown"));
    try (server) {
      server.serve();
      return 0;
    } catch (IOException failure) {
      return 10;
    }
  }

  private static void closeQuietly(DaemonServer server) {
    try {
      server.close();
    } catch (IOException ignored) {
      // JVM shutdown is already in progress.
    }
  }
}
