package org.synesis.cli.daemon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Process-local handle for one existing project runtime.
 */
final class ProcessRuntimeHandle implements DaemonServer.RuntimeHandle {

    private static final Duration RUNTIME_START_TIMEOUT = Duration.ofSeconds(20);

    private final Process process;
    private final URI endpoint;
    private final String commandToken;

    private ProcessRuntimeHandle(Process process, URI endpoint, String commandToken) {
        this.process = process;
        this.endpoint = normalizeEndpoint(endpoint);
        this.commandToken = commandToken;
    }

    static URI normalizeEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String value = endpoint.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return URI.create(value);
    }

    static boolean runtimeReachable(URI endpoint) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) endpoint.resolve("/api/v1/health").toURL().openConnection();
            connection.setConnectTimeout(500);
            connection.setReadTimeout(1_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() == HttpURLConnection.HTTP_OK;
        } catch (IOException failure) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static DaemonServer.RuntimeHandle start(Path project) throws IOException {
        Process process = CliChildJvm.builder(true, List.of("coordination", "serve", "--project", project.toString(), "--port", "0", "--parent-pid", Long.toString(ProcessHandle.current().pid()))).start();
        try {
            RuntimeReadyLine.Parsed ready = waitForReady(process);
            Thread drain = new Thread(() -> drain(process.getInputStream()), "synesis-project-runtime-output");
            drain.setDaemon(true);
            drain.start();
            return new ProcessRuntimeHandle(process, ready.endpoint(), ready.commandToken());
        } catch (IOException failure) {
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure.addSuppressed(interrupted);
            }
            throw failure;
        }
    }

    private static RuntimeReadyLine.Parsed waitForReady(Process process) throws IOException {
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "synesis-project-runtime-ready");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<RuntimeReadyLine.Parsed> ready = readerExecutor.submit(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (RuntimeReadyLine.isReady(line)) {
                            return RuntimeReadyLine.parse(line);
                        }
                    }
                }
                throw new IOException("project_runtime_exited_before_ready");
            });
            return ready.get(RUNTIME_START_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException failure) {
            throw new IOException("project_runtime_start_timeout", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("project_runtime_start_failed", cause);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("project_runtime_start_interrupted", failure);
        } finally {
            readerExecutor.shutdownNow();
        }
    }

    private static void drain(InputStream input) {
        try {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // Runtime termination closes the stream.
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
                // Runtime termination is already complete.
            }
        }
    }

    @Override
    public boolean reachable() {
        if (!process.isAlive()) {
            return false;
        }
        return runtimeReachable(endpoint);
    }

    /**
     * Asks the runtime for a fresh one-time bootstrap token. The runtime outlives every browser
     * tab, so a link built from an earlier token would be spent or expired.
     */
    @Override
    public URI uiUri() throws IOException {
        HttpResponse<String> response = command(endpoint, commandToken, "/api/v1/commands/bootstrap", Map.of());
        if (response.statusCode() != 201 || !(body(response).get("bootstrapToken") instanceof String bootstrap)) {
            throw new IOException("runtime_bootstrap_unavailable");
        }
        return URI.create(endpoint + "/#bootstrap=" + java.net.URLEncoder.encode(bootstrap, StandardCharsets.UTF_8));
    }

    @Override
    public Map<String, Object> dispatchUri(String uri) throws IOException {
        HttpResponse<String> response = command(endpoint, commandToken, "/api/v1/commands/deep-link", Map.of("uri", uri));
        Map<String, Object> body = body(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return Map.of("ok", false, "error", "URI_REJECTED", "authorityCode", errorCode(body));
        }
        body.put("ok", true);
        return body;
    }

    /**
     * Sends one runtime-owned command to a runtime.
     *
     * @param endpoint     runtime endpoint without a trailing slash
     * @param commandToken the runtime's command token
     * @param path         command route
     * @param payload      JSON body
     * @return the runtime's response
     * @throws IOException when the runtime cannot be reached
     */
    private static HttpResponse<String> command(URI endpoint, String commandToken, String path, Map<String, Object> payload) throws IOException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(750)).build();
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json; charset=utf-8")
                .header(org.synesis.workspace.transport.control.ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, commandToken).POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(payload))).build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("runtime command interrupted", interrupted);
        }
    }

    private static Map<String, Object> body(HttpResponse<String> response) throws IOException {
        try {
            if (ProviderJson.parse(response.body()) instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked") Map<String, Object> body = new LinkedHashMap<>((Map<String, Object>) raw);
                return body;
            }
        } catch (RuntimeException malformed) {
            throw new IOException("runtime command response is malformed", malformed);
        }
        throw new IOException("runtime command response is malformed");
    }

    private static String errorCode(Map<String, Object> body) {
        Object error = body.get("error");
        if (error instanceof Map<?, ?> value && value.get("code") instanceof String code) {
            return code;
        }
        return "RUNTIME_COMMAND_REJECTED";
    }

    @Override
    public URI endpoint() {
        return endpoint;
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
