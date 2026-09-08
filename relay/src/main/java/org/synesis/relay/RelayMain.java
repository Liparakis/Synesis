package org.synesis.relay;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayRelay;
import org.synesis.link.overlay.OverlayRelayPolicy;

/**
 * Minimal standalone `synesis-relay` process entry point.
 */
public final class RelayMain {

    private RelayMain() {
    }

    /**
     * Starts one live relay from a small explicit operator allowlist.
     *
     * @param arguments {@code --project UUID --node sl1-...} plus optional
     *                 {@code --port}, {@code --identity-dir}, and
     *                 {@code --workers}
     * @throws Exception if identity loading or binding fails
     */
    public static void main(String[] arguments) throws Exception {
        Arguments options = Arguments.parse(arguments);
        NodeIdentity identity = new IdentityBootstrap(options.identityDirectory()).loadOrCreate().identity();
        OverlayRelayPolicy policy = new OverlayRelayPolicy(
                Map.of(options.projectId(), Set.copyOf(options.allowedNodes())));
        OverlayRelay relay = new OverlayRelay(policy);
        OverlayRelayServer server = new OverlayRelayServer(
                new InetSocketAddress(options.bindHost(), options.port()), identity, relay, options.workers());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "synesis-relay-shutdown"));
        System.out.println("RELAY_NODE_ID=" + identity.nodeId());
        System.out.println("RELAY_PORT=" + server.localAddress().getPort());
        System.out.println("RELAY_PROJECT=" + options.projectId());
        new CountDownLatch(1).await();
    }

    private record Arguments(String bindHost, int port, int workers, UUID projectId, Set<String> allowedNodes,
            Path identityDirectory) {

        private static Arguments parse(String[] arguments) {
            String host = "0.0.0.0";
            int port = 0;
            int workers = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            UUID project = null;
            Set<String> nodes = new HashSet<>();
            Path identityDirectory = IdentityBootstrap.defaultDirectory().resolve("relay");
            for (int index = 0; index < arguments.length; index++) {
                String option = arguments[index];
                String value = switch (option) {
                    case "--host", "--port", "--workers", "--project", "--node", "--identity-dir" ->
                            index + 1 < arguments.length ? arguments[++index] : usage();
                    default -> throw new IllegalArgumentException(usage());
                };
                switch (option) {
                    case "--host" -> host = value;
                    case "--port" -> port = boundedInt(value, 0, 65_535, "port");
                    case "--workers" -> workers = boundedInt(value, 1, 256, "workers");
                    case "--project" -> project = UUID.fromString(value);
                    case "--node" -> nodes.add(value);
                    case "--identity-dir" -> identityDirectory = Path.of(value);
                    default -> throw new IllegalArgumentException(usage());
                }
            }
            if (project == null || nodes.isEmpty()) {
                throw new IllegalArgumentException(usage());
            }
            return new Arguments(host, port, workers, project, Set.copyOf(nodes), identityDirectory);
        }

        private static int boundedInt(String value, int minimum, int maximum, String name) {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(name + " is outside the supported range");
            }
            return parsed;
        }

        private static String usage() {
            return "usage: synesis-relay --project UUID --node sl1-... [--node sl1-...] "
                    + "[--host HOST] [--port PORT] [--workers N] [--identity-dir PATH]";
        }
    }
}
