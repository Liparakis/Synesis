package org.synesis.cli.daemon;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.TraversalAnswer;
import org.synesis.link.protocol.TraversalInvitation;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.discovery.InstallationHome;
import org.synesis.workspace.discovery.KnownProjectRegistry;

/**
 * Authenticated loopback server for the installation daemon.
 */
final class DaemonServer implements AutoCloseable {

    private static final int ACCEPT_BACKLOG = 32;
    private static final int WORKER_COUNT = 8;
    private static final int MAX_QUEUED_CONNECTIONS = 32;
    private static final int MAX_LINE_BYTES = DaemonProtocol.MAX_REQUEST_BYTES + 1;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private final DaemonLock lock;
    private final ServerSocket server;
    private final String token;
    private final Path endpointFile;
    private final KnownProjectRegistry projectRegistry;
    private final RuntimeStarter runtimeStarter;
    private final LocalProjectSelectionStore selections;
    private final HomeWorkspace home;
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(WORKER_COUNT, WORKER_COUNT, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_QUEUED_CONNECTIONS), runnable -> {
        Thread thread = new Thread(runnable, "synesis-daemon-ipc");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<Path, RuntimeHandle> runtimes = new LinkedHashMap<>();
    /**
     * Where the home workspace is, once it has been needed. Guarded by this object's monitor.
     */
    private Path homeRoot;
    private volatile boolean closed;

    private DaemonServer(DaemonLock lock, ServerSocket server, String token, Path endpointFile, KnownProjectRegistry projectRegistry, RuntimeStarter runtimeStarter, LocalProjectSelectionStore selections,
            HomeWorkspace home) {
        this.lock = lock;
        this.server = server;
        this.token = token;
        this.endpointFile = endpointFile;
        this.projectRegistry = Objects.requireNonNull(projectRegistry, "project registry");
        this.runtimeStarter = Objects.requireNonNull(runtimeStarter, "runtime starter");
        this.selections = Objects.requireNonNull(selections, "selection store");
        this.home = Objects.requireNonNull(home, "home workspace");
    }

    static DaemonServer start() throws IOException {
        return start(DaemonEndpoint.directory());
    }

    static DaemonServer start(Path endpointDirectory) throws IOException {
        return start(endpointDirectory, new KnownProjectRegistry(), ProcessRuntimeHandle::start, new LocalProjectSelectionStore());
    }

    static DaemonServer start(Path endpointDirectory, KnownProjectRegistry projectRegistry) throws IOException {
        return start(endpointDirectory, projectRegistry, ProcessRuntimeHandle::start, new LocalProjectSelectionStore());
    }

    static DaemonServer start(Path endpointDirectory, KnownProjectRegistry projectRegistry, RuntimeStarter runtimeStarter, LocalProjectSelectionStore selections) throws IOException {
        return start(endpointDirectory, projectRegistry, runtimeStarter, selections, InstallationHome::ensure);
    }

    static DaemonServer start(Path endpointDirectory, KnownProjectRegistry projectRegistry, RuntimeStarter runtimeStarter, LocalProjectSelectionStore selections, HomeWorkspace home) throws IOException {
        Path normalizedDirectory = Objects.requireNonNull(endpointDirectory, "endpoint directory").toAbsolutePath().normalize();
        Path endpointFile = DaemonEndpoint.endpointFile(normalizedDirectory);
        DaemonLock lock = DaemonLock.acquire(DaemonEndpoint.lockFile(normalizedDirectory));
        ServerSocket server = null;
        String token = null;
        try {
            server = new ServerSocket(0, ACCEPT_BACKLOG, InetAddress.getLoopbackAddress());
            token = randomToken();
            DaemonEndpoint.write(endpointFile, server.getLocalPort(), token, ProcessHandle.current().pid());
            return new DaemonServer(lock, server, token, endpointFile, projectRegistry, runtimeStarter, selections, home);
        } catch (IOException | RuntimeException failure) {
            if (token != null) {
                DaemonEndpoint.clearIfOwned(endpointFile, token);
            }
            if (server != null) {
                try {
                    server.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                lock.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static String randomToken() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
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
                submit(socket);
            }
        } catch (java.net.SocketException closedSocket) {
            if (!closed) {
                throw closedSocket;
            }
        }
    }

    private synchronized void submit(Socket socket) {
        if (closed) {
            closeQuietly(socket);
            return;
        }
        openSockets.add(socket);
        try {
            workers.execute(() -> handle(socket));
        } catch (RejectedExecutionException overloaded) {
            openSockets.remove(socket);
            closeQuietly(socket);
        }
    }

    private void handle(Socket socket) {
        try (socket;
                InputStream input = socket.getInputStream();
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            try {
                String line = readBoundedLine(socket, input, REQUEST_TIMEOUT);
                if (line == null) {
                    return;
                }
                DaemonProtocol.Request request = DaemonProtocol.parse(line);
                if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), request.token().getBytes(StandardCharsets.UTF_8))) {
                    write(writer, error("AUTH_REQUIRED"));
                    return;
                }
                write(writer, dispatch(request));
                if (request.operation() == DaemonProtocol.Operation.SHUTDOWN) {
                    // The caller has its answer; closing ends serve() and with it the process.
                    close();
                }
            } catch (Exception failure) {
                write(writer, error(failure instanceof IOException ? "REQUEST_REJECTED" : "REQUEST_FAILED"));
            }
        } catch (IOException ignored) {
            // The peer may have disconnected before a bounded response was written.
        } finally {
            openSockets.remove(socket);
        }
    }

    private synchronized Map<String, Object> dispatch(DaemonProtocol.Request request) throws IOException {
        if (closed) {
            throw new IOException("daemon_closed");
        }
        return switch (request.operation()) {
            case STATUS -> status();
            case SHUTDOWN -> response("STOPPING", Map.of());
            case OPEN_UI, OPEN_PROJECT -> openProject(request);
            case OPEN_URI -> openUri(request.uri(), request.openBrowser());
            case GET_SELECTION -> describeSelection(UUID.fromString(request.selectionId()));
            case SELECT_PROJECT -> selectProject(UUID.fromString(request.selectionId()), UUID.fromString(request.projectId()));
        };
    }

    private synchronized Map<String, Object> status() {
        closeUnreachableRuntimes();
        selections.expireStale();
        return response("RUNNING", Map.of("runtimeCount", runtimes.size()));
    }

    private void closeUnreachableRuntimes() {
        Iterator<Map.Entry<Path, RuntimeHandle>> iterator = runtimes.entrySet().iterator();
        while (iterator.hasNext()) {
            RuntimeHandle runtime = iterator.next().getValue();
            if (!runtime.reachable()) {
                runtime.close();
                iterator.remove();
            }
        }
    }

    private synchronized Map<String, Object> openProject(DaemonProtocol.Request request) throws IOException {
        RuntimeHandle runtime = request.project() == null ? knownProjectRuntime() : reachableOrStarted(Path.of(request.project()).toAbsolutePath().normalize());
        if (runtime == null) {
            return error("NO_KNOWN_PROJECT");
        }
        return openUi(runtime, request.openBrowser());
    }

    private static Map<String, Object> openUi(RuntimeHandle runtime, boolean openBrowser) throws IOException {
        boolean browserOpened = openBrowser && BrowserLauncher.open(runtime.uiUri());
        boolean ready = !openBrowser || browserOpened;
        return response(ready ? "READY" : "BROWSER_OPEN_FAILED", Map.of("endpoint", runtime.endpoint().toString(), "browserOpened", browserOpened));
    }

    /**
     * Reports whether a URI only asks for the UI: the bare scheme, or the {@code ui} or
     * {@code open} address. It carries nothing to verify, so it is matched as text and never
     * reaches a project runtime as a deep link.
     */
    static boolean isUiUri(String uriValue) {
        String address = uriValue.strip().toLowerCase(java.util.Locale.ROOT);
        while (address.endsWith("/")) {
            address = address.substring(0, address.length() - 1);
        }
        return Set.of("synesis:", "synesis://ui", "synesis://open").contains(address);
    }

    private synchronized Map<String, Object> openUri(String uriValue, boolean openBrowser) throws IOException {
        if (isUiUri(uriValue)) {
            RuntimeHandle runtime = knownProjectRuntime();
            return runtime == null ? response("URI_UNRESOLVED", Map.of("reason", "NO_KNOWN_PROJECT")) : openUi(runtime, openBrowser);
        }
        validateUriShape(uriValue);
        if (isAnswer(uriValue)) {
            TraversalAnswer answer = TraversalAnswer.fromShareLink(uriValue);
            if (answer.formatVersion() == TraversalAnswer.FORMAT_VERSION_V2) {
                return openTargetedAnswer(uriValue, answer, openBrowser);
            }
            return openLegacy(uriValue, openBrowser);
        }
        if (CompactBootstrapInvitation.isShareLink(uriValue)) {
            CompactBootstrapInvitation.fromShareLink(uriValue);
        } else {
            TraversalInvitation.fromShareLink(uriValue);
        }
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
            return response("URI_AMBIGUOUS", Map.of("reason", "MULTIPLE_LIVE_PROJECT_RUNTIMES", "runtimeCount", eligible.size()));
        }
        return dispatch(eligible.getFirst(), uriValue, openBrowser);
    }

    private Map<String, Object> openTargetedAnswer(String uriValue, TraversalAnswer answer, boolean openBrowser) throws IOException {
        UUID target = answer.returnTarget().orElseThrow(() -> new IOException("URI_INVALID")).returnProjectId();
        List<KnownProjectRegistry.ProjectView> projects;
        try {
            projects = knownProjects();
        } catch (IOException unavailable) {
            return response("URI_UNRESOLVED", Map.of("reason", "PROJECT_REGISTRY_UNAVAILABLE"));
        }
        KnownProjectRegistry.ProjectView view = logicalProjectChoices(projects).stream().filter(project -> project.projectId().equals(target)).findFirst().orElse(null);
        if (view == null) {
            return response("URI_UNRESOLVED", Map.of("reason", "RETURN_TARGET_UNKNOWN", "projectId", target.toString()));
        }
        if (view.status() == KnownProjectRegistry.Status.UNAVAILABLE || view.status() == KnownProjectRegistry.Status.IDENTITY_MISMATCH) {
            return response("URI_UNRESOLVED", Map.of("reason", "RETURN_TARGET_UNAVAILABLE", "projectId", target.toString()));
        }
        try {
            return dispatch(runtimeFor(view), uriValue, openBrowser);
        } catch (IOException unavailable) {
            return response("URI_UNRESOLVED", Map.of("reason", "PROJECT_RUNTIME_UNAVAILABLE", "projectId", target.toString()));
        }
    }

    private Map<String, Object> createSelection(String uriValue, List<KnownProjectRegistry.ProjectView> projects, boolean openBrowser) {
        List<UUID> projectIds = projects.stream().map(KnownProjectRegistry.ProjectView::projectId).toList();
        try {
            LocalProjectSelectionStore.Creation creation = selections.create(uriValue, digest(uriValue), projectIds);
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
        LocalProjectSelectionStore.Claim claim = selections.claim(selectionId, projectId, eligible.stream().map(KnownProjectRegistry.ProjectView::projectId).toList());
        if (!claim.accepted()) {
            return error(claim.error());
        }
        KnownProjectRegistry.ProjectView selected = eligible.stream().filter(project -> project.projectId().equals(claim.selectedProjectId())).findFirst().orElse(null);
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
        Map<UUID, KnownProjectRegistry.ProjectView> byId = projects.stream().collect(java.util.stream.Collectors.toMap(KnownProjectRegistry.ProjectView::projectId, project -> project, (first, ignored) -> first));
        List<Map<String, Object>> safeProjects = description.eligibleProjectIds().stream().map(byId::get).filter(Objects::nonNull).map(DaemonServer::safeProject).toList();
        if (safeProjects.size() != description.eligibleProjectIds().size()) {
            return error("PROJECT_UNAVAILABLE");
        }
        return response("PROJECT_SELECTION_REQUIRED",
                Map.of("selectionId",
                        selectionId.toString(),
                        "expiresAt",
                        description.expiresAt().toString(),
                        "projects",
                        safeProjects,
                        "projectIds",
                        description.eligibleProjectIds().stream().map(UUID::toString).toList()));
    }

    private boolean openSelectionBrowser(UUID selectionId, List<KnownProjectRegistry.ProjectView> projects) {
        try {
            RuntimeHandle host = runtimeFor(projects.getFirst());
            URI base = host.uiUri();
            URI picker = URI.create(base + "&selectionId=" + selectionId);
            return BrowserLauncher.open(picker);
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    private static Map<String, Object> safeProject(KnownProjectRegistry.ProjectView project) {
        String displayName = project.path().getFileName() == null ? project.projectId().toString() : project.path().getFileName().toString();
        return Map.of("projectId", project.projectId().toString(), "displayName", displayName);
    }

    private List<RuntimeHandle> liveRuntimes() {
        closeUnreachableRuntimes();
        return runtimes.values().stream().filter(RuntimeHandle::reachable).toList();
    }

    private List<KnownProjectRegistry.ProjectView> eligibleProjects() throws IOException {
        return logicalProjectChoices(knownProjects().stream().filter(project -> project.status() == KnownProjectRegistry.Status.LIVE || project.status() == KnownProjectRegistry.Status.INACTIVE).toList());
    }

    private List<KnownProjectRegistry.ProjectView> logicalProjectChoices(List<KnownProjectRegistry.ProjectView> replicas) {
        Map<UUID, KnownProjectRegistry.ProjectView> choices = new LinkedHashMap<>();
        for (KnownProjectRegistry.ProjectView candidate : replicas) {
            choices.merge(candidate.projectId(), candidate, this::preferReplica);
        }
        return List.copyOf(choices.values());
    }

    private KnownProjectRegistry.ProjectView preferReplica(KnownProjectRegistry.ProjectView first, KnownProjectRegistry.ProjectView second) {
        boolean firstLive = runtimeReachable(first.path());
        boolean secondLive = runtimeReachable(second.path());
        if (firstLive != secondLive) {
            return secondLive ? second : first;
        }
        return first.path().toString().compareTo(second.path().toString()) <= 0 ? first : second;
    }

    private boolean runtimeReachable(Path path) {
        RuntimeHandle runtime = runtimes.get(path);
        return runtime != null && runtime.reachable();
    }

    private List<KnownProjectRegistry.ProjectView> knownProjects() throws IOException {
        try {
            return projectRegistry.projects();
        } catch (KnownProjectRegistry.RegistryException failure) {
            throw new IOException("PROJECT_REGISTRY_UNAVAILABLE", failure);
        }
    }

    private RuntimeHandle reachableOrStarted(Path path) throws IOException {
        RuntimeHandle runtime = runtimes.get(path);
        if (runtime == null || !runtime.reachable()) {
            if (runtime != null) {
                runtime.close();
            }
            runtime = runtimeStarter.start(path);
            runtimes.put(path, runtime);
        }
        return runtime;
    }

    /**
     * Picks the runtime that serves the UI when the caller names no project. The UI lists every
     * known project whichever runtime serves it, so a project runtime that is already up is
     * preferred, then the first known project that starts. An installation that knows no project
     * is served from its own home workspace, so the page on which a project is added always opens.
     *
     * @return a reachable runtime, or {@code null} when not even the home workspace can serve
     */
    private RuntimeHandle knownProjectRuntime() {
        closeUnreachableRuntimes();
        for (Map.Entry<Path, RuntimeHandle> running : runtimes.entrySet()) {
            if (!running.getKey().equals(homeRoot)) {
                return running.getValue();
            }
        }
        try {
            for (KnownProjectRegistry.ProjectView project : eligibleProjects()) {
                try {
                    return runtimeFor(project);
                } catch (IOException unavailable) {
                    // The next known project may still start.
                }
            }
        } catch (IOException registryUnavailable) {
            // Without a registry no project is known.
        }
        try {
            homeRoot = home.ensure().toAbsolutePath().normalize();
            return reachableOrStarted(homeRoot);
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    private RuntimeHandle runtimeFor(KnownProjectRegistry.ProjectView project) throws IOException {
        Path path = project.path();
        RuntimeHandle runtime = reachableOrStarted(path);
        if (!runtime.reachable()) {
            runtime.close();
            runtimes.remove(path);
            throw new IOException("PROJECT_RUNTIME_UNAVAILABLE");
        }
        return runtime;
    }

    private static Map<String, Object> dispatch(RuntimeHandle runtime, String uriValue, boolean openBrowser) throws IOException {
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
        return value.regionMatches(true, 0, "synesis://answer/", 0, "synesis://answer/".length());
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
        boolean payload = join && path != null && (path.startsWith("/SLO1-") || path.startsWith("/SLB2-")) || answer && path != null && path.startsWith("/SLA2-");
        if (!"synesis".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !payload) {
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

    static String readBoundedLine(Socket socket, InputStream input, Duration timeout) throws IOException {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("request timeout must be positive");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        byte[] buffer = new byte[MAX_LINE_BYTES];
        int count = 0;
        int value;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new SocketTimeoutException("daemon request deadline exceeded");
            }
            long millis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, millis));
            value = input.read();
            if (value < 0) {
                break;
            }
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

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The rejected or shutting-down peer is already unusable.
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException closeFailure = null;
        try {
            server.close();
        } catch (IOException failure) {
            closeFailure = failure;
        }
        for (Socket socket : openSockets) {
            closeQuietly(socket);
        }
        openSockets.clear();
        for (RuntimeHandle runtime : new ArrayList<>(runtimes.values())) {
            try {
                runtime.close();
            } catch (RuntimeException failure) {
                if (closeFailure == null) {
                    closeFailure = new IOException("daemon runtime cleanup failed", failure);
                } else {
                    closeFailure.addSuppressed(failure);
                }
            }
        }
        runtimes.clear();
        workers.shutdownNow();
        workers.getQueue().clear();
        DaemonEndpoint.clearIfOwned(endpointFile, token);
        try {
            lock.close();
        } catch (IOException failure) {
            if (closeFailure == null) {
                closeFailure = failure;
            } else {
                closeFailure.addSuppressed(failure);
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    @FunctionalInterface
    interface HomeWorkspace {

        /**
         * Makes sure the installation's own workspace exists.
         *
         * @return its root directory
         * @throws IOException when it cannot be created or initialized
         */
        Path ensure() throws IOException;
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
         * Returns a URI that opens the runtime UI once.
         *
         * @return local UI URI carrying a fresh one-time bootstrap token
         * @throws IOException when the runtime cannot issue one
         */
        URI uiUri() throws IOException;

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
}
