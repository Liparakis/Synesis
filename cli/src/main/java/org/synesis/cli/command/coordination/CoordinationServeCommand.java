package org.synesis.cli.command.coordination;


import org.synesis.cli.command.coordination.CoordinationCliSupport;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.transport.http.CoordinationHttpServer;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.lifecycle.codex.ProjectRuntimeHost;
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
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            var projectData = CoordinationCliSupport.data(location, data);
            var identityDirectory = CoordinationCliSupport.identity(location, identity);
            var node = CoordinationCliSupport.loadIdentity(identityDirectory);
            var store = new PredictionEventStore(projectData, location.projectId());
            var service = new CoordinationService(store, node);
            if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) {
                runtime.terminal()
                        .stderr("COORDINATION_ERROR=LOOPBACK_ONLY");
                return ExitCodes.LOCAL_CONFIGURATION;
            }
            try (var lifecycleHost = new ProjectRuntimeHost(location, node);
                    var server = new CoordinationHttpServer(service, new InetSocketAddress(host, port),
                            lifecycleHost.handler())) {
                server.start();
                runtime.terminal()
                        .stdout("COORDINATION_SERVE_READY endpoint=http://"
                                + server.address()
                                .getHostString() + ":" + server.address()
                                .getPort()
                                + "/ project=" + location.projectId() + " nodeId=" + node.nodeId()
                                + " hostInstanceId=" + lifecycleHost.hostInstanceId()
                                + " codexLifecycleRoute=" + lifecycleHost.route());
                if (durationSeconds > 0) {
                    Thread.sleep(Duration.ofSeconds(durationSeconds)
                            .toMillis());
                } else {
                    while (!Thread.currentThread()
                            .isInterrupted()) {
                        Thread.sleep(1000L);
                    }
                }
            }
            return ExitCodes.OK;
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                    .interrupt();
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("COORDINATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
