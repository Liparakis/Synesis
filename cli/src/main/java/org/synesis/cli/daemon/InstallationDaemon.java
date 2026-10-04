package org.synesis.cli.daemon;

import java.io.IOException;
import org.synesis.cli.exit.ExitCodes;

/**
 * Foreground lifecycle wrapper for the per-user installation daemon.
 */
public final class InstallationDaemon {

    /**
     * Creates an installation-daemon runner.
     */
    public InstallationDaemon() {
    }

    private static void closeQuietly(DaemonServer server) {
        try {
            server.close();
        } catch (IOException ignored) {
            // JVM shutdown is already in progress.
        }
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
            return alreadyRunning.getMessage() != null && alreadyRunning.getMessage().contains("daemon_already_running") ? ExitCodes.OK : ExitCodes.LOCAL_CONFIGURATION;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(server), "synesis-daemon-shutdown"));
        try (server) {
            server.serve();
            return ExitCodes.OK;
        } catch (IOException failure) {
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
