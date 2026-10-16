package org.synesis.cli.command.link;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.synesis.cli.daemon.DaemonClient;

import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.transport.control.ControlPlaneHttpHandler;

/**
 * Reaches a runtime's control plane over loopback with the runtime command token, which the installation daemon
 * hands to a local client that holds the daemon's own token.
 */
final class HttpRuntimeControl implements RuntimeControl {

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(750);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final URI endpoint;
    private final String commandToken;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    HttpRuntimeControl(URI endpoint, String commandToken) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.commandToken = Objects.requireNonNull(commandToken, "commandToken");
    }

    /**
     * The runtime the installation daemon owns for a project; the daemon starts it when it is not running.
     *
     * @return the source used by the commands
     */
    static Source daemon() {
        return project -> {
            Map<String, Object> answer = DaemonClient.request("PROJECT_RUNTIME", project, null, false);
            if (!(answer.get("endpoint") instanceof String endpoint) || !(answer.get("commandToken") instanceof String token)) {
                throw new IOException("project_runtime_unavailable");
            }
            return new HttpRuntimeControl(URI.create(endpoint), token);
        };
    }

    @Override
    public Map<String, Object> command(String name, Map<String, Object> body) throws IOException, Rejected {
        return send(HttpRequest.newBuilder(route("commands/" + name)).header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(body))));
    }

    @Override
    public Map<String, Object> read(String route) throws IOException, Rejected {
        return send(HttpRequest.newBuilder(route(route)).GET());
    }

    private URI route(String path) {
        return endpoint.resolve("/api/v1/" + path);
    }

    private Map<String, Object> send(HttpRequest.Builder request) throws IOException, Rejected {
        HttpResponse<String> response;
        try {
            response = client.send(request.timeout(REQUEST_TIMEOUT).header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, commandToken).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("runtime request interrupted", interrupted);
        }
        Map<String, Object> body = parse(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new Rejected(body.get("error") instanceof Map<?, ?> error && error.get("code") instanceof String code ? code : "RUNTIME_REQUEST_REJECTED");
        }
        return body;
    }

    private static Map<String, Object> parse(String text) throws IOException {
        try {
            if (ProviderJson.parse(text) instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) raw;
                return body;
            }
        } catch (RuntimeException malformed) {
            throw new IOException("runtime response is malformed", malformed);
        }
        throw new IOException("runtime response is malformed");
    }
}
