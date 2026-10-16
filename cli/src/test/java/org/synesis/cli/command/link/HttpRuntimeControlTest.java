package org.synesis.cli.command.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What goes over loopback to the runtime: the route, the runtime command token, the JSON body, and the code of a
 * refusal.
 */
@Timeout(30)
class HttpRuntimeControlTest {

    private HttpServer server;
    private final List<String> seen = new ArrayList<>();
    private int status = 201;
    private String body = "{\"operationId\":\"op-1\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " token=" + exchange.getRequestHeaders().getFirst("X-Synesis-Control-Command") + " body="
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpRuntimeControl control() {
        return new HttpRuntimeControl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "secret");
    }

    @Test
    void aCommandIsPostedToItsRouteWithTheRuntimeCommandTokenAndAReadIsAGet() throws Exception {
        assertEquals(Map.of("operationId", "op-1"), control().command("invite", Map.of("expectedPeer", "sl1-x")));
        status = 200;
        assertEquals(Map.of("operationId", "op-1"), control().read("network"));

        assertEquals(List.of("POST /api/v1/commands/invite token=secret body={\"expectedPeer\":\"sl1-x\"}", "GET /api/v1/network token=secret body="), seen);
    }

    @Test
    void anErrorBodyBecomesARejectionWithItsCode() {
        status = 400;
        body = "{\"apiVersion\":\"1\",\"error\":{\"code\":\"INVITE_INVALID\",\"message\":\"no\"}}";

        assertEquals("INVITE_INVALID", assertThrows(RuntimeControl.Rejected.class, () -> control().command("join", Map.of())).code());
    }

    @Test
    void aBodyThatIsNotAJsonObjectIsAnIoFailure() {
        body = "<html>";

        assertThrows(IOException.class, () -> control().read("snapshot"));
    }
}
