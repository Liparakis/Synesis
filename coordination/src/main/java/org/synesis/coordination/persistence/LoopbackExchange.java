package org.synesis.coordination.persistence;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * How a process that opened a member's log reaches its computer's coordination server: an HTTP POST to the
 * loopback address the server wrote to {@code replication.endpoint} in the coordination folder when it started.
 *
 * <p>Only the coordination server holds the Link session to the leader, so every process relays through it. A
 * missing, stale or non-loopback endpoint is "nothing sent": the proposal is refused as {@code LEADER_UNREACHABLE}
 * and nothing is appended. The file is never trusted to name a host that is not loopback.</p>
 */
public final class LoopbackExchange implements ReplicationExchange {

    /**
     * Name of the file the coordination server writes in the coordination folder.
     */
    public static final String ENDPOINT_FILE = "replication.endpoint";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    // A relayed request waits for one Link exchange, which gives up after five seconds.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private static final class Client {
        private static final HttpClient INSTANCE = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    private final Path root;

    /**
     * Binds the exchange to one coordination folder.
     *
     * @param root the folder holding {@code events}
     */
    public LoopbackExchange(Path root) {
        this.root = CoordinationNullException.requireNonNull(root, "root");
    }

    @Override
    public byte[] exchange(byte[] request) throws IOException {
        URI endpoint = endpoint();
        HttpRequest post = HttpRequest.newBuilder(endpoint.resolve("/replication")).timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(request)).build();
        HttpResponse<byte[]> response;
        try {
            response = Client.INSTANCE.send(post, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException notConnected) {
            throw new ConnectException(notConnected.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for the coordination server", interrupted);
        }
        if (response.statusCode() == 503) {
            // The server has no relay attached: nothing was forwarded.
            throw new ConnectException("coordination server has no leader link");
        }
        if (response.statusCode() != 200) {
            throw new IOException("coordination server answered " + response.statusCode());
        }
        return response.body();
    }

    private URI endpoint() throws IOException {
        String text;
        try {
            text = Files.readString(root.resolve(ENDPOINT_FILE), StandardCharsets.UTF_8).trim();
        } catch (IOException missing) {
            throw new ConnectException("no coordination server is running for this project");
        }
        int colon = text.lastIndexOf(':');
        try {
            String host = text.substring(0, colon);
            int port = Integer.parseInt(text.substring(colon + 1));
            if (port < 1 || port > 65_535 || !(host.equals("127.0.0.1") || host.equals("[::1]"))
                    || !InetAddress.getByName(host.replace("[", "").replace("]", "")).isLoopbackAddress()) {
                throw new ConnectException("coordination endpoint is not loopback");
            }
            return URI.create("http://" + host + ":" + port + "/");
        } catch (RuntimeException malformed) {
            throw new ConnectException("coordination endpoint is malformed");
        }
    }
}
