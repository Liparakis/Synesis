package org.synesis.coordination.transport.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.synesis.coordination.domain.CoordinationCommand;
import org.synesis.coordination.domain.PredictionEvent;

/**
 * Minimal JDK HTTP client for signed coordination commands and replay.
 */
public final class CoordinationHttpClient {

    private final URI endpoint;
    private final HttpClient client;

    /**
     * Creates a client for one coordinator endpoint.
     *
     * @param endpoint coordinator base URI
     */
    public CoordinationHttpClient(URI endpoint) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint")
                .resolve("/");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Submits one already signed command.
     *
     * @param command command envelope
     * @return coordinator event
     * @throws IOException          transport or coordinator failure
     * @throws InterruptedException interrupted request
     */
    public PredictionEvent submit(CoordinationCommand command) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("command"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(command.encoded()))
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("coordination command rejected: "
                    + new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return PredictionEvent.decode(response.body());
    }

    /**
     * Replays durable events after an exclusive sequence cursor.
     *
     * @param after exclusive sequence cursor
     * @return ordered events
     * @throws IOException          transport or malformed-event failure
     * @throws InterruptedException interrupted request
     */
    public List<PredictionEvent> replayAfter(long after) throws IOException, InterruptedException {
        URI uri = URI.create(endpoint + "events?after=" + after + "&once=true");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = null;
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
                break;
            } catch (IOException failure) {
                last = failure;
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread()
                            .interrupt();
                    throw interrupted;
                }
            }
        }
        if (response == null) {
            throw last;
        }
        if (response.statusCode() != 200) {
            throw new IOException("event replay failed: " + response.statusCode());
        }
        List<PredictionEvent> result = new ArrayList<>();
        for (String line : response.body()
                .split("\\R")) {
            if (!line.startsWith("data: ")) {
                continue;
            }
            String encoded = line.substring(6)
                    .split(" ", 2)[0];
            result.add(PredictionEvent.decode(Base64.getDecoder()
                    .decode(encoded)));
        }
        return result;
    }

    /**
     * Follows the live server-sent event stream from an exclusive cursor.
     *
     * @param after    exclusive sequence cursor
     * @param consumer ordered event consumer
     * @throws IOException          transport or malformed-event failure
     * @throws InterruptedException interrupted stream
     */
    public void follow(long after, Consumer<PredictionEvent> consumer) throws IOException, InterruptedException {
        follow(after, 0, consumer);
    }

    /**
     * Follows the live event stream with an optional bounded duration.
     *
     * @param after           exclusive sequence cursor
     * @param durationSeconds maximum stream duration, or zero for a long-lived stream
     * @param consumer        ordered event consumer
     * @throws IOException          transport or malformed-event failure
     * @throws InterruptedException interrupted stream
     */
    public void follow(long after, int durationSeconds, Consumer<PredictionEvent> consumer)
            throws IOException, InterruptedException {
        Objects.requireNonNull(consumer, "consumer");
        URI uri = URI.create(endpoint + "events?after=" + after);
        Duration timeout =
                durationSeconds > 0 ? Duration.ofSeconds(Math.max(1, durationSeconds) + 1L) : Duration.ofDays(1);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<java.util.stream.Stream<String>> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (java.net.http.HttpTimeoutException timeoutFailure) {
            if (durationSeconds > 0) {
                return;
            }
            throw timeoutFailure;
        }
        if (response.statusCode() != 200) {
            throw new IOException("event stream failed: " + response.statusCode());
        }
        try (var lines = response.body()) {
            lines.filter(line -> line.startsWith("data: "))
                    .forEach(line -> {
                        try {
                            String encoded = line.substring(6)
                                    .split(" ", 2)[0];
                            consumer.accept(PredictionEvent.decode(Base64.getDecoder()
                                    .decode(encoded)));
                        } catch (IOException failure) {
                            throw new EventStreamFailure(failure);
                        }
                    });
        } catch (EventStreamFailure failure) {
            throw failure.cause;
        }
    }

    private static final class EventStreamFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final IOException cause;

        private EventStreamFailure(IOException cause) {
            this.cause = cause;
        }
    }
}
