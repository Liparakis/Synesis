package org.synesis.coordination.transport.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.prediction.PredictionContract;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Pins the status codes, headers, and server-sent-event bytes of the coordination HTTP routes.
 */
final class CoordinationHttpWireTest {

    private static PredictionContract contract(UUID project, UUID prediction, String node) {
        return new PredictionContract(prediction, project, node, "supervisor-a", "worker-a", UUID.randomUUID(),
                "workspace.prediction-status", node, "supervisor-a", List.of("workspace/**"), 0, "HEAD",
                List.of("workspace/**=absent"), 0, "purpose", "inputs", "outputs", "behavior", "errors", "none",
                "invariants", "compatible", "normal", "single-threaded", List.of("test"), 80, 20,
                1_900_000_000_000L);
    }

    @Test
    void routesKeepTheirStatusHeadersAndEventFrameBytes() throws Exception {
        UUID project = UUID.randomUUID();
        UUID prediction = UUID.randomUUID();
        NodeIdentity requester = NodeIdentity.generate();
        CoordinationService service = new CoordinationService(
                new PredictionEventStore(Files.createTempDirectory("synesis-http-wire-"), project),
                NodeIdentity.generate());
        try (CoordinationHttpServer server = new CoordinationHttpServer(service,
                new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.address().getPort());
            HttpClient http = HttpClient.newHttpClient();

            HttpResponse<byte[]> wrongCommandMethod = http.send(HttpRequest.newBuilder(base.resolve("/command"))
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(405, wrongCommandMethod.statusCode());
            assertEquals(0, wrongCommandMethod.body().length);
            HttpResponse<byte[]> wrongEventsMethod = http.send(HttpRequest.newBuilder(base.resolve("/events"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(405, wrongEventsMethod.statusCode());
            assertEquals(0, wrongEventsMethod.body().length);

            HttpResponse<String> empty = http.send(HttpRequest.newBuilder(base.resolve("/events?once=true"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, empty.statusCode());
            assertEquals("text/event-stream; charset=utf-8", empty.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("no-cache", empty.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(": empty\n\n", empty.body());

            CoordinationCommand command = CoordinationCommand.create(UUID.randomUUID(), project, prediction,
                    PredictionEventType.PREDICTION_CREATED, requester.nodeId(),
                    contract(project, prediction, requester.nodeId()).encoded(), requester);
            HttpResponse<byte[]> submitted = http.send(HttpRequest.newBuilder(base.resolve("/command"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(command.encoded())).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, submitted.statusCode());
            assertEquals("application/octet-stream", submitted.headers().firstValue("Content-Type").orElseThrow());

            HttpResponse<String> replay = http.send(HttpRequest.newBuilder(base.resolve("/events?after=0&once=true"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("id: 1\ndata: " + Base64.getEncoder().encodeToString(submitted.body())
                    + " PREDICTION_CREATED\n\n: empty\n\n", replay.body());

            PredictionEvent expected = PredictionEvent.decode(submitted.body());
            List<PredictionEvent> viaClient = new CoordinationHttpClient(base).replayAfter(0);
            assertEquals(1, viaClient.size());
            assertEquals(expected.eventId(), viaClient.get(0).eventId());
            assertEquals(List.of(), new CoordinationHttpClient(base).replayAfter(1));
        }
    }
}
