package org.synesis.cli.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class StaticResourceHandlerTest {

    @Test
    void servesIndexAndSpaRoutesWithSafeHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        try {
            server.createContext("/", new StaticResourceHandler());
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> index = client.send(request(server, "/"), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> route = client.send(request(server, "/projects/current"),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, index.statusCode());
            assertEquals(200, route.statusCode());
            assertTrue(index.body().contains("<title>Synesis</title>"));
            assertEquals("no-cache", index.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(index.headers().firstValue("Content-Security-Policy").orElseThrow()
                    .contains("script-src 'self'"));
            assertEquals("nosniff", index.headers().firstValue("X-Content-Type-Options").orElseThrow());
            assertTrue(route.body().contains("<div id=\"root\"></div>"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void neverTurnsMissingAssetsIntoTheSpaEntryPoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        try {
            server.createContext("/", new StaticResourceHandler());
            server.start();
            HttpResponse<String> missing = HttpClient.newHttpClient().send(
                    request(server, "/assets/not-present.js"), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
        } finally {
            server.stop(0);
        }
    }

    private static HttpRequest request(HttpServer server, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .GET().build();
    }
}
