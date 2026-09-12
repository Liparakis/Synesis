package org.synesis.cli.daemon;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.synesis.cli.command.coordination.BrowserLauncher;
import org.synesis.link.protocol.TraversalAnswer;
import org.synesis.link.protocol.TraversalInvitation;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.discovery.KnownProjectRegistry;

/**
 * Authenticated loopback server for the installation daemon.
 */
final class DaemonServer implements AutoCloseable {

  private static final int MAX_CONNECTIONS = 32;
  private static final int MAX_LINE_BYTES = DaemonProtocol.MAX_REQUEST_BYTES + 1;
  private static final Duration RUNTIME_START_TIMEOUT = Duration.ofSeconds(20);
  private final DaemonLock lock;
  private final ServerSocket server;
  private final String token;
  private final Path endpointFile;
  private final KnownProjectRegistry projectRegistry;
  private final RuntimeStarter runtimeStarter;
  private final LocalProjectSelectionStore selections;
  private final ExecutorService workers = Executors.newFixedThreadPool(8, runnable -> {
    Thread thread = new Thread(runnable, "synesis-daemon-ipc");
    thread.setDaemon(true);
    return thread;
  });
  private final Map<Path, RuntimeHandle> runtimes = new LinkedHashMap<>();
  private boolean closed;

  private DaemonServer(DaemonLock lock, ServerSocket server, String token, Path endpointFile,
      KnownProjectRegistry projectRegistry, RuntimeStarter runtimeStarter,
      LocalProjectSelectionStore selections) {
    this.lock = lock;
    this.server = server;
    this.token = token;
    this.endpointFile = endpointFile;
    this.projectRegistry = Objects.requireNonNull(projectRegistry, "project registry");
    this.runtimeStarter = Objects.requireNonNull(runtimeStarter, "runtime starter");
    this.selections = Objects.requireNonNull(selections, "selection store");
  }

  static DaemonServer start() throws IOException {
    return start(DaemonEndpoint.directory());
  }

  static DaemonServer start(Path endpointDirectory) throws IOException {
    return start(endpointDirectory, new KnownProjectRegistry(), ProcessRuntimeHandle::start,
        new LocalProjectSelectionStore());
  }

  static DaemonServer start(Path endpointDirectory, KnownProjectRegistry projectRegistry)
      throws IOException {
    return start(endpointDirectory, projectRegistry, ProcessRuntimeHandle::start,
        new LocalProjectSelectionStore());
  }

  static DaemonServer start(Path endpointDirectory, KnownProjectRegistry projectRegistry,
      RuntimeStarter runtimeStarter, LocalProjectSelectionStore selections) throws IOException {
    Path normalizedDirectory = Objects.requireNonNull(endpointDirectory, "endpoint directory")
        .toAbsolutePath().normalize();
    Path endpointFile = DaemonEndpoint.endpointFile(normalizedDirectory);
    DaemonLock lock = DaemonLock.acquire(DaemonEndpoint.lockFile(normalizedDirectory));
    try {
      ServerSocket server = new ServerSocket(0, MAX_CONNECTIONS,
          InetAddress.getLoopbackAddress());
      String token = randomToken();
      DaemonEndpoint.write(endpointFile, server.getLocalPort(), token,
          ProcessHandle.current().pid());
      return new DaemonServer(lock, server, token, endpointFile, projectRegistry, runtimeStarter,
          selections);
    } catch (IOException failure) {
      lock.close();
      throw failure;
    }
  }

  private static String randomToken() {
    byte[] value = new byte[32];
    new SecureRandom().nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  static URI normalizeRuntimeEndpoint(URI endpoint) {
    return ProcessRuntimeHandle.normalizeEndpoint(endpoint);
  }

  /**
   * Serves authenticated requests until shutdown.
   *
   * @throws IOException when the listening socket fails
   */
  void serve() throws IOException {
    try {
      while (!closed) {
        Socket socket = server.accept();
        workers.submit(() -> handle(socket));
      }
    } catch (java.net.SocketException closedSocket) {
      if (!closed) {
        throw closedSocket;
      }
    }
  }

  private void handle(Socket socket) {
    try {
      socket.setSoTimeout(5_000);
      try (socket;
          InputStream input = socket.getInputStream();
          BufferedWriter writer = new BufferedWriter(
              new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
        try {
          String line = readBoundedLine(input);
          if (line == null) {
            return;
          }
          DaemonProtocol.Request request = DaemonProtocol.parse(line);
          if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
              request.token().getBytes(StandardCharsets.UTF_8))) {
            write(writer, error("AUTH_REQUIRED"));
            return;
          }
          write(writer, dispatch(request));
        } catch (Exception failure) {
          write(writer, error(failure instanceof IOException ? "REQUEST_REJECTED"
              : "REQUEST_FAILED"));
        }
      }
    } catch (IOException ignored) {
      // The peer may have disconnected before a bounded response was written.
    }
  }

  private Map<String, Object> dispatch(DaemonProtocol.Request request) throws IOException {
    return switch (request.operation()) {
      case STATUS -> status();
      case OPEN_UI, OPEN_PROJECT -> openProject(request);
      case OPEN_URI -> openUri(request.uri(), request.openBrowser());
      case GET_SELECTION -> describeSelection(UUID.fromString(request.selectionId()));
      case SELECT_PROJECT -> selectProject(UUID.fromString(request.selectionId()),
          UUID.fromString(request.projectId()));
    };
  }

  private synchronized Map<String, Object> status() {
    runtimes.values().removeIf(handle -> !handle.reachable());
    selections.size();
    return response("RUNNING", Map.of("runtimeCount", runtimes.size()));
  }

  private synchronized Map<String, Object> openProject(DaemonProtocol.Request request)
      throws IOException {
    Path project = Path.of(Objects.requireNonNull(request.project(), "project"))
        .toAbsolutePath().normalize();
    RuntimeHandle runtime = runtimes.get(project);
    if (runtime == null || !runtime.reachable()) {
      if (runtime != null) {
        runtime.close();
      }
      runtime = runtimeStarter.start(project);
      runtimes.put(project, runtime);
    }
    boolean browserOpened = request.openBrowser() && BrowserLauncher.open(runtime.uiUri());
    boolean ready = !request.openBrowser() || browserOpened;
    return response(ready ? "READY" : "BROWSER_OPEN_FAILED",
        Map.of("endpoint", runtime.endpoint().toString(), "browserOpened", browserOpened));
  }

  private synchronized Map<String, Object> openUri(String uriValue, boolean openBrowser)
      throws IOException {
    validateUriShape(uriValue);
    if (isAnswer(uriValue)) {
      TraversalAnswer answer = TraversalAnswer.fromShareLink(uriValue);
      if (answer.formatVersion() == TraversalAnswer.FORMAT_VERSION_V2) {
        return openTargetedAnswer(uriValue, answer, openBrowser);
      }
      return openLegacy(uriValue, openBrowser);
    }
    TraversalInvitation.fromShareLink(uriValue);
    List<KnownProjectRegistry.ProjectView> eligibleProjects;
    try {
      eligibleProjects = eligibleProjects();
    } catch (IOException unavailable) {
      return response("URI_UNRESOLVED", Map.of("reason", "PROJECT_REGISTRY_UNAVAILABLE"));
    }
    if (eligibleProjects.isEmpty()) {
      return response("URI_UNRESOLVED", Map.of("reason", "NO_ELIGIBLE_LOCAL_PROJECT"));
    }
    if (eligibleProjects.size() > 1) {
      return createSelection(uriValue, eligibleProjects, openBrowser);
    }
    try {
      RuntimeHandle runtime = runtimeFor(eligibleProjects.getFirst());
      return dispatch(runtime, uriValue, openBrowser);
    } catch (IOException unavailable) {
      return response("URI_UNRESOLVED", Map.of("reason", "PROJECT_RUNTIME_UNAVAILABLE"));
    }
  }

  private Map<String, Object> openLegacy(String uriValue, boolean openBrowser) throws IOException {
    List<RuntimeHandle> eligible = liveRuntimes();
    if (eligible.isEmpty()) {
      return response("URI_UNRESOLVED", Map.of("reason", "NO_LIVE_PROJECT_RUNTIME"));
    }
    if (eligible.size() != 1) {
      return response("URI_AMBIGUOUS", Map.of("reason", "MULTIPLE_LIVE_PROJECT_RUNTIMES",
          "runtimeCount", eligible.size()));
    }
    return dispatch(eligible.getFirst(), uriValue, openBrowser);
  }

  private Map<String, Object> openTargetedAnswer(String uriValue, TraversalAnswer answer,
      boolean openBrowser) throws IOException {
    UUID target = answer.returnTarget().orElseThrow(() -> new IOException("URI_INVALID"))
        .returnProjectId();
    List<KnownProjectRegistry.ProjectView> projects;
    try {
      projects = knownProjects();
    } catch (IOException unavailable) {
      return response("URI_UNRESOLVED", Map.of("reason", "PROJECT_REGISTRY_UNAVAILABLE"));
    }
    KnownProjectRegistry.ProjectView view = projects.stream()
        .filter(project -> project.projectId().equals(target))
        .findFirst()
        .orElse(null);
    if (view == null) {
      return response("URI_UNRESOLVED", Map.of("reason", "RETURN_TARGET_UNKNOWN",
          "projectId", target.toString()));
    }
    if (view.status() == KnownProjectRegistry.Status.UNAVAILABLE
        || view.status() == KnownProjectRegistry.Status.IDENTITY_MISMATCH) {
      return response("URI_UNRESOLVED", Map.of("reason", "RETURN_TARGET_UNAVAILABLE",
          "projectId", target.toString()));
    }
    try {
      return dispatch(runtimeFor(view), uriValue, openBrowser);
    } catch (IOException unavailable) {
      return response("URI_UNRESOLVED", Map.of("reason", "PROJECT_RUNTIME_UNAVAILABLE",
          "projectId", target.toString()));
    }
  }

  private Map<String, Object> createSelection(String uriValue,
      List<KnownProjectRegistry.ProjectView> projects, boolean openBrowser) {
    List<UUID> projectIds = projects.stream().map(KnownProjectRegistry.ProjectView::projectId)
        .toList();
    try {
      LocalProjectSelectionStore.Creation creation = selections.create(uriValue,
          digest(uriValue), projectIds);
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("selectionId", creation.selectionId().toString());
      fields.put("projectIds", projectIds.stream().map(UUID::toString).toList());
      fields.put("projects", projects.stream().map(DaemonServer::safeProject).toList());
      fields.put("expiresAt", creation.expiresAt().toString());
      if (openBrowser) {
        fields.put("browserOpened", openSelectionBrowser(creation.selectionId(), projects));
      }
      return response("PROJECT_SELECTION_REQUIRED", fields);
    } catch (LocalProjectSelectionStore.CapacityExceededException full) {
      return response("URI_UNRESOLVED", Map.of("reason", "SELECTION_CAPACITY"));
    }
  }

  synchronized Map<String, Object> selectProject(UUID selectionId, UUID projectId) throws IOException {
    List<KnownProjectRegistry.ProjectView> eligible;
    try {
      eligible = eligibleProjects();
    } catch (IOException unavailable) {
      return response("URI_UNRESOLVED", Map.of("reason", "PROJECT_REGISTRY_UNAVAILABLE"));
    }
    LocalProjectSelectionStore.Claim claim = selections.claim(selectionId, projectId,
        eligible.stream().map(KnownProjectRegistry.ProjectView::projectId).toList());
    if (!claim.accepted()) {
      return error(claim.error());
    }
    KnownProjectRegistry.ProjectView selected = eligible.stream()
        .filter(project -> project.projectId().equals(claim.selectedProjectId()))
        .findFirst().orElse(null);
    if (selected == null) {
      return response("URI_UNRESOLVED", Map.of("reason", "PROJECT_UNAVAILABLE"));
    }
    try {
      return dispatch(runtimeFor(selected), claim.invitation(), false);
    } catch (IOException unavailable) {
      return response("URI_UNRESOLVED", Map.of("reason", "PROJECT_RUNTIME_UNAVAILABLE"));
    }
  }

  synchronized Map<String, Object> describeSelection(UUID selectionId) throws IOException {
    LocalProjectSelectionStore.Description description = selections.describe(selectionId);
    if (!description.available()) {
      return error(description.error());
    }
    List<KnownProjectRegistry.ProjectView> projects;
    try {
      projects = knownProjects();
    } catch (IOException unavailable) {
      return response("URI_UNRESOLVED", Map.of("reason", "PROJECT_REGISTRY_UNAVAILABLE"));
    }
    Map<UUID, KnownProjectRegistry.ProjectView> byId = projects.stream().collect(
        java.util.stream.Collectors.toMap(KnownProjectRegistry.ProjectView::projectId,
            project -> project, (first, ignored) -> first));
    List<Map<String, Object>> safeProjects = description.eligibleProjectIds().stream()
        .map(byId::get).filter(Objects::nonNull).map(DaemonServer::safeProject).toList();
    if (safeProjects.size() != description.eligibleProjectIds().size()) {
      return error("PROJECT_UNAVAILABLE");
    }
    return response("PROJECT_SELECTION_REQUIRED", Map.of("selectionId", selectionId.toString(),
        "expiresAt", description.expiresAt().toString(), "projects", safeProjects,
        "projectIds", description.eligibleProjectIds().stream().map(UUID::toString).toList()));
  }

  private boolean openSelectionBrowser(UUID selectionId,
      List<KnownProjectRegistry.ProjectView> projects) {
    try {
      RuntimeHandle host = runtimeFor(projects.getFirst());
      URI base = host.uiUri();
      String separator = base.toString().contains("?") ? "&" : "&";
      URI picker = URI.create(base + separator + "selectionId=" + selectionId);
      return BrowserLauncher.open(picker);
    } catch (IOException | RuntimeException unavailable) {
      return false;
    }
  }

  private static Map<String, Object> safeProject(KnownProjectRegistry.ProjectView project) {
    String displayName = project.path().getFileName() == null
        ? project.projectId().toString() : project.path().getFileName().toString();
    return Map.of("projectId", project.projectId().toString(), "displayName", displayName);
  }

  private List<RuntimeHandle> liveRuntimes() {
    return runtimes.values().stream().filter(RuntimeHandle::reachable).toList();
  }

  private List<KnownProjectRegistry.ProjectView> eligibleProjects() throws IOException {
    return knownProjects().stream()
        .filter(project -> project.status() == KnownProjectRegistry.Status.LIVE
            || project.status() == KnownProjectRegistry.Status.INACTIVE)
        .toList();
  }

  private List<KnownProjectRegistry.ProjectView> knownProjects() throws IOException {
    try {
      return projectRegistry.projects();
    } catch (KnownProjectRegistry.RegistryException failure) {
      throw new IOException("PROJECT_REGISTRY_UNAVAILABLE", failure);
    }
  }

  private RuntimeHandle runtimeFor(KnownProjectRegistry.ProjectView project) throws IOException {
    Path path = project.path();
    RuntimeHandle runtime = runtimes.get(path);
    if (runtime == null || !runtime.reachable()) {
      if (runtime != null) {
        runtime.close();
      }
      runtime = runtimeStarter.start(path);
      runtimes.put(path, runtime);
    }
    if (!runtime.reachable()) {
      runtime.close();
      runtimes.remove(path);
      throw new IOException("PROJECT_RUNTIME_UNAVAILABLE");
    }
    return runtime;
  }

  private static Map<String, Object> dispatch(RuntimeHandle runtime, String uriValue,
      boolean openBrowser) throws IOException {
    Map<String, Object> result = new LinkedHashMap<>(runtime.dispatchUri(uriValue));
    if (!Boolean.TRUE.equals(result.get("ok"))) {
      return result;
    }
    boolean browserOpened = openBrowser && BrowserLauncher.open(runtime.uiUri());
    result.put("browserOpened", browserOpened);
    result.put("runtimeEndpoint", runtime.endpoint().toString());
    return result;
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static boolean isAnswer(String value) {
    return value.regionMatches(true, 0, "synesis://answer/", 0,
        "synesis://answer/".length());
  }

  private static boolean isJoin(String value) {
    return value.regionMatches(true, 0, "synesis://join/", 0, "synesis://join/".length());
  }

  private static void validateUriShape(String uriValue) throws IOException {
    URI uri;
    try {
      uri = URI.create(Objects.requireNonNull(uriValue, "uri"));
    } catch (IllegalArgumentException failure) {
      throw new IOException("URI_INVALID", failure);
    }
    String host = uri.getHost();
    String path = uri.getPath();
    boolean join = "join".equalsIgnoreCase(host);
    boolean answer = "answer".equalsIgnoreCase(host);
    boolean payload = join && path != null && path.startsWith("/SLO1-")
        || answer && path != null && path.startsWith("/SLA2-");
    if (!"synesis".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
        || uri.getQuery() != null || uri.getFragment() != null || !payload) {
      throw new IOException("URI_UNSUPPORTED");
    }
  }

  private static Map<String, Object> response(String state, Map<String, Object> fields) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", true);
    result.put("state", state);
    result.putAll(fields);
    return result;
  }

  private static Map<String, Object> error(String code) {
    return Map.of("ok", false, "error", code);
  }

  private static void write(BufferedWriter writer, Map<String, Object> value) throws IOException {
    writer.write(ProviderJson.write(value));
    writer.newLine();
    writer.flush();
  }

  private static String readBoundedLine(InputStream input) throws IOException {
    byte[] buffer = new byte[MAX_LINE_BYTES];
    int count = 0;
    int value;
    while ((value = input.read()) >= 0) {
      if (value == '\n') {
        return new String(buffer, 0, count, StandardCharsets.UTF_8).replaceFirst("\\r$", "");
      }
      if (count == buffer.length) {
        throw new IOException("daemon request is oversized");
      }
      buffer[count++] = (byte) value;
    }
    return count == 0 ? null : new String(buffer, 0, count, StandardCharsets.UTF_8);
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    server.close();
    for (RuntimeHandle runtime : new ArrayList<>(runtimes.values())) {
      runtime.close();
    }
    runtimes.clear();
    workers.shutdownNow();
    DaemonEndpoint.clearIfOwned(endpointFile, token);
    lock.close();
  }

  @FunctionalInterface
  interface RuntimeStarter {
    /**
     * Starts or attaches to one project runtime.
     *
     * @param project validated local project path
     * @return process-local runtime handle
     * @throws IOException when the runtime cannot be started
     */
    RuntimeHandle start(Path project) throws IOException;
  }

  interface RuntimeHandle extends AutoCloseable {
    /**
     * Reports whether the runtime is alive and healthy.
     *
     * @return whether the runtime is reachable
     */
    boolean reachable();

    /**
     * Returns the runtime UI URI.
     *
     * @return local UI URI
     */
    URI uiUri();

    /**
     * Dispatches an untouched deep-link URI to the runtime.
     *
     * @param uri exact incoming URI
     * @return runtime response
     * @throws IOException when dispatch fails
     */
    Map<String, Object> dispatchUri(String uri) throws IOException;

    /**
     * Returns the runtime control endpoint.
     *
     * @return local endpoint
     */
    URI endpoint();

    /**
     * Stops the process-local runtime handle.
     */
    @Override
    void close();
  }

  /** Process-local handle for one existing project runtime. */
  private static final class ProcessRuntimeHandle implements RuntimeHandle {

    private final Process process;
    private final URI endpoint;
    private final String bootstrap;
    private final String commandToken;

    private ProcessRuntimeHandle(Process process, URI endpoint, String bootstrap,
        String commandToken) {
      this.process = process;
      this.endpoint = normalizeEndpoint(endpoint);
      this.bootstrap = bootstrap;
      this.commandToken = commandToken;
    }

    private static URI normalizeEndpoint(URI endpoint) {
      Objects.requireNonNull(endpoint, "endpoint");
      String value = endpoint.toString();
      while (value.endsWith("/")) {
        value = value.substring(0, value.length() - 1);
      }
      return URI.create(value);
    }

    private static RuntimeHandle start(Path project) throws IOException {
      String java = Path.of(System.getProperty("java.home"), "bin",
          System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java")
          .toString();
      ProcessBuilder builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
          "org.synesis.cli.SynesisCli", "coordination", "serve", "--project",
          project.toString(), "--port", "0", "--parent-pid",
          Long.toString(ProcessHandle.current().pid()));
      builder.redirectInput(ProcessBuilder.Redirect.from(Path.of(
          System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null")
          .toFile()));
      Process process = builder.start();
      try {
        Ready ready = waitForReady(process);
        Thread drain = new Thread(() -> drain(process.getInputStream()),
            "synesis-project-runtime-output");
        drain.setDaemon(true);
        drain.start();
        return new ProcessRuntimeHandle(process, ready.endpoint(), ready.bootstrap(),
            ready.commandToken());
      } catch (IOException failure) {
        process.destroyForcibly();
        throw failure;
      }
    }

    private static Ready waitForReady(Process process) throws IOException {
      ExecutorService readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "synesis-project-runtime-ready");
        thread.setDaemon(true);
        return thread;
      });
      try {
        Future<Ready> ready = readerExecutor.submit(() -> {
          try (BufferedReader reader = new BufferedReader(new InputStreamReader(
              process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
              if (line.startsWith("COORDINATION_SERVE_READY ")) {
                String endpoint = field(line, "endpoint");
                String bootstrap = field(line, "controlBootstrap");
                String commandToken = field(line, "controlCommand");
                return new Ready(URI.create(endpoint), bootstrap, commandToken);
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

    private static String field(String line, String name) throws IOException {
      String prefix = name + "=";
      for (String token : line.split(" ")) {
        if (token.startsWith(prefix)) {
          return token.substring(prefix.length());
        }
      }
      throw new IOException("project_runtime_ready_missing_" + name);
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
      try {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/api/v1/health"))
            .timeout(Duration.ofSeconds(1)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      } catch (IOException failure) {
        return false;
      }
    }

    @Override
    public URI uiUri() {
      return URI.create(endpoint + "/#bootstrap="
          + java.net.URLEncoder.encode(bootstrap, StandardCharsets.UTF_8));
    }

    @Override
    public Map<String, Object> dispatchUri(String uri) throws IOException {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(750)).build();
      HttpRequest request = HttpRequest.newBuilder(
              endpoint.resolve("/api/v1/commands/deep-link"))
          .timeout(Duration.ofSeconds(15))
          .header("Content-Type", "application/json; charset=utf-8")
          .header(org.synesis.workspace.transport.control.ControlPlaneHttpHandler
              .RUNTIME_COMMAND_HEADER, commandToken)
          .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("uri", uri))))
          .build();
      try {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Object parsed = ProviderJson.parse(response.body());
        if (!(parsed instanceof Map<?, ?> raw)) {
          throw new IOException("runtime deep-link response is malformed");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = new LinkedHashMap<>((Map<String, Object>) raw);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          return Map.of("ok", false, "error", "URI_REJECTED",
              "authorityCode", errorCode(body));
        }
        body.put("ok", true);
        return body;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("runtime deep-link dispatch interrupted", interrupted);
      } catch (RuntimeException malformed) {
        throw new IOException("runtime deep-link response is malformed", malformed);
      }
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
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          process.destroyForcibly();
        }
      }
    }

    private record Ready(URI endpoint, String bootstrap, String commandToken) {
    }
  }
}
