package org.synesis.cli.command.coordination;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.ui.StaticResourceHandler;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.transport.http.CoordinationHttpServer;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.lifecycle.codex.ProjectRuntimeHost;
import org.synesis.workspace.transport.control.ControlPlaneEventHub;
import org.synesis.workspace.transport.control.ControlPlaneHttpHandler;
import org.synesis.workspace.transport.control.ControlPlaneReadModel;
import org.synesis.workspace.transport.control.LinkNetworkProjection;
import org.synesis.workspace.transport.control.LinkRuntimeOwner;

/**
 * Shared composition for the coordination server and the installed browser
 * UI command.
 */
final class CoordinationServerLauncher {

    private CoordinationServerLauncher() {
    }

    /**
     * Starts one local coordination server.
     *
     * @param runtime       composed CLI runtime
     * @param project       project directory, or {@code null}
     * @param data          coordination data override, or {@code null}
     * @param identity      identity directory override, or {@code null}
     * @param host          loopback host
     * @param port          listener port, with zero selecting an ephemeral port
     * @param duration      optional bounded duration in seconds
     * @param openBrowser   whether to open the packaged UI
     * @param browserOpen   whether browser opening is enabled
     * @return stable process exit code
     */
    static Integer run(CliRuntime runtime, Path project, Path data, Path identity, String host, int port,
            int duration, boolean openBrowser, boolean browserOpen) {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            var projectData = CoordinationCliSupport.data(location, data);
            var identityDirectory = CoordinationCliSupport.identity(location, identity);
            var node = CoordinationCliSupport.loadIdentity(identityDirectory);
            var store = new PredictionEventStore(projectData, location.projectId());
            var service = new CoordinationService(store, node);
            if (!isLoopback(host)) {
                runtime.terminal().stderr("COORDINATION_ERROR=LOOPBACK_ONLY");
                return ExitCodes.LOCAL_CONFIGURATION;
            }
            var eventHub = new ControlPlaneEventHub();
            var controlOnboarding = new Onboarding(identityDirectory.resolve("link"), eventHub::publish);
            var linkOwner = new LinkRuntimeOwner(controlOnboarding, node, Optional.empty(), eventHub::publish);
            var readModel = new ControlPlaneReadModel(location, service, runtime.providerService(),
                    new DoctorService(), new LinkNetworkProjection(linkOwner));
            var control = new ControlPlaneHttpHandler(readModel, service, linkOwner, eventHub);
            try (control;
                    linkOwner;
                    var lifecycleHost = new ProjectRuntimeHost(location, node);
                    var server = new CoordinationHttpServer(service, new InetSocketAddress(host, port),
                            lifecycleHost.handler(), control, new StaticResourceHandler())) {
                server.start();
                String endpoint = "http://" + hostForUrl(server.address().getHostString()) + ":"
                        + server.address().getPort();
                String uiUrl = endpoint + "/#bootstrap="
                        + URLEncoder.encode(control.bootstrapToken(), StandardCharsets.UTF_8);
                runtime.terminal().stdout("COORDINATION_SERVE_READY endpoint=" + endpoint + "/ project="
                        + location.projectId() + " nodeId=" + node.nodeId() + " hostInstanceId="
                        + lifecycleHost.hostInstanceId() + " codexLifecycleRoute=" + lifecycleHost.route()
                        + " controlPlaneRoute=/api/v1 controlBootstrap=" + control.bootstrapToken()
                        + " uiRoute=/");
                if (openBrowser && browserOpen) {
                    if (!BrowserLauncher.open(URI.create(uiUrl))) {
                        runtime.terminal().stdout("SYNESIS_UI_OPEN_FAILED url=" + uiUrl);
                    } else {
                        runtime.terminal().stdout("SYNESIS_UI_OPENED");
                    }
                }
                if (duration > 0) {
                    Thread.sleep(Duration.ofSeconds(duration).toMillis());
                } else {
                    while (!Thread.currentThread().isInterrupted()) {
                        LockSupport.parkNanos(java.util.concurrent.TimeUnit.SECONDS.toNanos(1L));
                    }
                }
            }
            return ExitCodes.OK;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("COORDINATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }

    private static String hostForUrl(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }
}
