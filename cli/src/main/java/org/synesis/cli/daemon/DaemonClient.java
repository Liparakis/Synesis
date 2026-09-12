package org.synesis.cli.daemon;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Client used by CLI launchers to attach to the per-user installation daemon.
 */
public final class DaemonClient {

  private static final int CONNECT_TIMEOUT_MILLIS = 750;
  private static final int START_TIMEOUT_MILLIS = 10_000;
  private static final int MAX_RESPONSE_BYTES = 32 * 1024;

  private DaemonClient() {
  }

  /**
   * Ensures the daemon is running and sends one bounded operation.
   *
   * @param runtime CLI runtime used for diagnostics
   * @param operationName operation name to send
   * @param project project path, when required
   * @param uri deep-link URI, when required
   * @param openBrowser whether the daemon may open the browser
   * @return daemon response
   * @throws IOException when startup or IPC fails
   */
  public static Map<String, Object> request(CliRuntime runtime, String operationName,
      Path project, String uri, boolean openBrowser) throws IOException {
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(operationName, "operationName");
    final DaemonProtocol.Operation operation;
    try {
      operation = DaemonProtocol.Operation.valueOf(operationName);
    } catch (IllegalArgumentException failure) {
      throw new IOException("daemon operation is unsupported", failure);
    }
    DaemonEndpoint.Record endpoint = connectExisting();
    if (endpoint == null) {
      startDaemonProcess();
      long deadline = System.nanoTime()
          + Duration.ofMillis(START_TIMEOUT_MILLIS).toNanos();
      while ((endpoint = connectExisting()) == null && System.nanoTime() < deadline) {
        try {
          Thread.sleep(50L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("daemon startup interrupted", interrupted);
        }
      }
    }
    if (endpoint == null) {
      throw new IOException("daemon_not_ready");
    }
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("token", endpoint.token());
    request.put("operation", operation.name());
    request.put("openBrowser", openBrowser);
    if (project != null) {
      request.put("project", project.toAbsolutePath().normalize().toString());
    }
    if (uri != null) {
      request.put("uri", uri);
    }
    return exchange(endpoint, request);
  }

  /**
   * Reads one pending selection through an already running daemon.
   *
   * @param selectionId pending selection identifier
   * @return safe selection projection
   * @throws IOException when the daemon is unavailable or rejects the request
   */
  public static Map<String, Object> describeSelection(UUID selectionId) throws IOException {
    return selectionRequest("GET_SELECTION", selectionId, null);
  }

  /**
   * Claims one pending selection through an already running daemon.
   *
   * @param selectionId pending selection identifier
   * @param projectId selected project identifier
   * @return daemon routing result
   * @throws IOException when the daemon is unavailable or rejects the request
   */
  public static Map<String, Object> selectProject(UUID selectionId, UUID projectId)
      throws IOException {
    return selectionRequest("SELECT_PROJECT", selectionId, projectId);
  }

  private static Map<String, Object> selectionRequest(String operation, UUID selectionId,
      UUID projectId) throws IOException {
    DaemonEndpoint.Record endpoint = DaemonEndpoint.read();
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("token", endpoint.token());
    request.put("operation", operation);
    request.put("selectionId", Objects.requireNonNull(selectionId, "selectionId").toString());
    if (projectId != null) {
      request.put("projectId", projectId.toString());
    }
    return exchangeAllowError(endpoint, request);
  }

  private static DaemonEndpoint.Record connectExisting() {
    try {
      DaemonEndpoint.Record endpoint = DaemonEndpoint.read();
      exchange(endpoint, Map.of("token", endpoint.token(), "operation", "STATUS"));
      return endpoint;
    } catch (IOException failure) {
      return null;
    }
  }

  private static Map<String, Object> exchange(DaemonEndpoint.Record endpoint,
      Map<String, Object> values) throws IOException {
    Map<String, Object> typed = exchangeAllowError(endpoint, values);
    if (Boolean.FALSE.equals(typed.get("ok"))) {
      throw new IOException(String.valueOf(typed.getOrDefault("error", "daemon request failed")));
    }
    return typed;
  }

  private static Map<String, Object> exchangeAllowError(DaemonEndpoint.Record endpoint,
      Map<String, Object> values) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()),
          CONNECT_TIMEOUT_MILLIS);
      socket.setSoTimeout(30_000);
      try (BufferedWriter writer = new BufferedWriter(
          new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
          BufferedReader reader = new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
        writer.write(DaemonProtocol.writeRequest(values));
        writer.newLine();
        writer.flush();
        String response = reader.readLine();
        if (response == null || response.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
          throw new IOException("daemon response is missing or oversized");
        }
        Object parsed = ProviderJson.parse(response);
        if (!(parsed instanceof Map<?, ?> map)) {
          throw new IOException("daemon response is malformed");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return Map.copyOf(typed);
      }
    } catch (java.net.SocketTimeoutException failure) {
      throw new IOException("daemon_timeout", failure);
    } catch (RuntimeException failure) {
      throw new IOException("daemon response is malformed", failure);
    }
  }

  private static void startDaemonProcess() throws IOException {
    String java = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java")
        .toString();
    ProcessBuilder builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
        "org.synesis.cli.SynesisCli", "daemon", "--foreground");
    builder.redirectInput(ProcessBuilder.Redirect.from(Path.of(
        System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null").toFile()));
    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.start();
  }
}
