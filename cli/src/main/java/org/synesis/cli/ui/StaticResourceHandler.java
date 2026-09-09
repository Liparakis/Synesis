package org.synesis.cli.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Serves the install-bundled web UI without exposing arbitrary classpath or filesystem resources.
 */
public final class StaticResourceHandler implements HttpHandler {

  private static final String RESOURCE_ROOT = "web-ui/";
  private static final String INDEX = "index.html";
  private static final Map<String, String> CONTENT_TYPES = Map.of(
      "html", "text/html; charset=utf-8",
      "css", "text/css; charset=utf-8",
      "js", "text/javascript; charset=utf-8",
      "json", "application/json; charset=utf-8",
      "svg", "image/svg+xml",
      "png", "image/png",
      "ico", "image/x-icon",
      "woff2", "font/woff2");

  /**
   * Creates a handler backed by the packaged {@code web-ui} resource root.
   */
  public StaticResourceHandler() {
  }

  private static String resourceName(String requested) {
    if (!requested.startsWith("/") || requested.contains("..") || requested.contains("\\")) {
      return null;
    }
    String relative = requested.substring(1);
    if (relative.isBlank()) {
      return INDEX;
    }
    if (relative.startsWith("assets/")) {
      return relative;
    }
    return relative.contains(".") ? relative : INDEX;
  }

  private static boolean reserved(String path) {
    return path.equals("/api") || path.startsWith("/api/") || path.equals("/command")
        || path.startsWith("/command/") || path.equals("/events") || path.startsWith("/events/")
        || path.equals("/codex-lifecycle") || path.startsWith("/codex-lifecycle/");
  }

  private static String contentType(String resource) {
    int dot = resource.lastIndexOf('.');
    if (dot < 0) {
      return "application/octet-stream";
    }
    return CONTENT_TYPES.getOrDefault(
        resource.substring(dot + 1).toLowerCase(java.util.Locale.ROOT),
        "application/octet-stream");
  }

  private static void send(HttpExchange exchange, int status, byte[] body, String contentType,
      String cacheControl) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", cacheControl);
    exchange.getResponseHeaders().set("Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; "
            + "frame-ancestors 'none'; form-action 'self'");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    exchange.sendResponseHeaders(status, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  /**
   * Serves one static UI resource or the SPA entry point for a UI route.
   *
   * @param exchange current HTTP exchange
   * @throws IOException when the response cannot be written
   */
  @Override
  public void handle(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.getResponseHeaders().set("Allow", "GET");
      send(exchange, 405, new byte[0], "text/plain; charset=utf-8", "no-store");
      return;
    }
    String requested = exchange.getRequestURI().getPath();
    if (requested == null || requested.isBlank() || reserved(requested)) {
      send(exchange, 404, new byte[0], "text/plain; charset=utf-8", "no-store");
      return;
    }
    String resource = resourceName(requested);
    if (resource == null) {
      send(exchange, 404, new byte[0], "text/plain; charset=utf-8", "no-store");
      return;
    }
    byte[] body;
    String contentType;
    try (InputStream input = StaticResourceHandler.class.getClassLoader()
        .getResourceAsStream(RESOURCE_ROOT + resource)) {
      if (input == null) {
        send(exchange, 404, new byte[0], "text/plain; charset=utf-8", "no-store");
        return;
      }
      body = input.readAllBytes();
      contentType = contentType(resource);
    }
    String cache = INDEX.equals(resource) ? "no-cache" : "public, max-age=31536000, immutable";
    send(exchange, 200, body, contentType, cache);
  }
}
