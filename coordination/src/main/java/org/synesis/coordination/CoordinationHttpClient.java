package org.synesis.coordination;

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

/** Minimal JDK HTTP client for signed coordination commands and replay. */
public final class CoordinationHttpClient {
    private final URI endpoint;
    private final HttpClient client;

    /** Creates a client for one coordinator endpoint.
     * @param endpoint coordinator base URI
     */
    public CoordinationHttpClient(URI endpoint) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint").resolve("/");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Submits one already signed command.
     * @param command command envelope
     * @return coordinator event
     * @throws IOException transport or coordinator failure
     * @throws InterruptedException interrupted request
     */
    public PredictionEvent submit(CoordinationCommand command) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("command"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(command.encoded())).build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IOException("coordination command rejected: "
                + new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        return PredictionEvent.decode(response.body());
    }

    /** Replays durable events after an exclusive sequence cursor.
     * @param after exclusive sequence cursor
     * @return ordered events
     * @throws IOException transport or malformed-event failure
     * @throws InterruptedException interrupted request
     */
    public List<PredictionEvent> replayAfter(long after) throws IOException, InterruptedException {
        URI uri = URI.create(endpoint + "events?after=" + after + "&once=true");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response = null;
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
                break;
            } catch (IOException failure) {
                last = failure;
                try { Thread.sleep(50L); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw interrupted;
                }
            }
        }
        if (response == null) throw last;
        if (response.statusCode() != 200) throw new IOException("event replay failed: " + response.statusCode());
        List<PredictionEvent> result = new ArrayList<>();
        for (String line : response.body().split("\\R")) {
            if (!line.startsWith("data: ")) continue;
            String encoded = line.substring(6).split(" ", 2)[0];
            result.add(PredictionEvent.decode(Base64.getDecoder().decode(encoded)));
        }
        return result;
    }
}
