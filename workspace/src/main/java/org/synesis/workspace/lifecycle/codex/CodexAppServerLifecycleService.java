package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Codex-only App Server lifecycle state machine.
 *
 * <p>The service owns one App Server process and protocol connection for one
 * exact provider binding. It does not create Synesis bindings, participants,
 * WorkIntents, claims, lanes, epochs, or worktrees; those identities must be
 * verified before the host calls it. It is thread-safe. Protocol reading,
 * lifecycle transitions, waiter completion, and evidence persistence remain
 * separate so a slow evidence writer cannot block control.
 *
 * @since 1.0
 */
@SuppressWarnings({"resource", "DuplicatedCode"})
public final class CodexAppServerLifecycleService implements AutoCloseable {

    private final LifecycleControlRequestEnvelope.AuthorityContext authority;
    private final CodexLifecycleStateStore stateStore;
    private final ProcessLauncher launcher;
    private final ProcessTreeTerminator terminator;
    private final Path evidenceDirectory;
    private final Object stateLock = new Object();
    private final Map<String, Waiter> waiters = new LinkedHashMap<>();
    private final ExecutorService waitExecutor;
    private final ScheduledExecutorService scheduler;
    private volatile Attachment active;
    private volatile AppServerProcess startingProcess;
    private volatile boolean closed;
    /**
     * Creates one binding-scoped lifecycle service.
     *
     * @param authority         exact verified binding authority
     * @param stateStore        durable checkpoint store
     * @param ledger            durable lifecycle idempotency ledger
     * @param launcher          direct App Server process launcher
     * @param terminator        ownership-verified process terminator
     * @param evidenceDirectory generation journal directory
     */
    public CodexAppServerLifecycleService(LifecycleControlRequestEnvelope.AuthorityContext authority,
            CodexLifecycleStateStore stateStore, LifecycleIdempotencyLedger ledger, ProcessLauncher launcher,
            ProcessTreeTerminator terminator, Path evidenceDirectory) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        Objects.requireNonNull(ledger, "ledger");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.terminator = Objects.requireNonNull(terminator, "terminator");
        this.evidenceDirectory = Objects.requireNonNull(evidenceDirectory, "evidenceDirectory")
                .toAbsolutePath()
                .normalize();
        this.waitExecutor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "synesis-codex-lifecycle-wait");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "synesis-codex-lifecycle-deadline");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Creates a service using the real direct {@code codex app-server} command.
     *
     * @return direct process launcher
     */
    public static ProcessLauncher defaultLauncher() {
        return (authority, _) -> {
            List<String> command = configuredCommand(authority);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(Path.of(authority.realWorktree())
                            .toFile())
                    .redirectError(ProcessBuilder.Redirect.PIPE);
            builder.environment()
                    .put("SYNESIS_MCP_PROJECT", authority.controlProjectRoot());
            builder.environment()
                    .put("SYNESIS_MCP_CONNECTION_INSTANCE_ID", authority.connectionInstanceId());
            builder.environment()
                    .put("SYNESIS_MCP_PROVIDER", authority.provider());
            Process process = builder.start();
            var info = ProcessHandle.of(process.pid())
                    .map(ProcessHandle::info)
                    .orElse(null);
            String executable = info == null ? command.getFirst() : info.command()
                                                                    .orElse(command.getFirst());
            // Windows commonly exposes the executable and start instant but
            // omits ProcessHandle.Info.commandLine().  Persist the same
            // identity that the repeated-discovery inspector can observe in
            // that case; retaining a synthetic argv string would make a
            // verified attachment look unowned during shutdown.
            String commandIdentity = info == null ? executable
                    : info.commandLine()
                      .orElse(executable);
            long started = info == null ? System.currentTimeMillis()
                    : info.startInstant()
                      .map(Instant::toEpochMilli)
                      .orElse(System.currentTimeMillis());
            return new AppServerProcess(process, executable, commandIdentity, started);
        };
    }

    private static List<String> configuredCommand(LifecycleControlRequestEnvelope.AuthorityContext authority) {
        List<String> command = new ArrayList<>(configuredCommand());
        // Codex 0.145.0 does not reliably propagate arbitrary parent
        // environment variables into configured MCP subprocesses.  Keep the
        // inherited variables as a compatibility fallback, but also provide
        // the exact binding values through Codex's supported per-server env
        // overrides.  This remains one App Server process and one existing
        // Synesis MCP server; it only makes the already-required binding
        // explicit at the App Server boundary.
        command.add("-c");
        command.add("mcp_servers.synesis.env.SYNESIS_MCP_PROJECT="
                + codexConfigString(authority.controlProjectRoot()));
        command.add("-c");
        command.add("mcp_servers.synesis.env.SYNESIS_MCP_CONNECTION_INSTANCE_ID="
                + codexConfigString(authority.connectionInstanceId()));
        command.add("-c");
        command.add("mcp_servers.synesis.env.SYNESIS_MCP_PROVIDER="
                + codexConfigString(authority.provider()));
        return List.copyOf(command);
    }

    private static List<String> configuredCommand() {
        String configured = System.getenv("SYNESIS_CODEX_APP_SERVER_COMMAND");
        if (configured == null || configured.isBlank()) {
            return List.of("codex", "app-server");
        }
        String[] tokens = configured.trim()
                .split("\\s+");
        return List.of(tokens);
    }

    private static String codexConfigString(String value) {
        String normalized = value.replace('\\', '/');
        return "\"" + normalized.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private static CodexLifecycleStateStore.State reconcileReadState(
            CodexLifecycleStateStore.Checkpoint prior, CodexAppServerProtocolClient.Response read) {
        String status = null;
        if (read.result() instanceof Map<?, ?> raw) {
            Object value = raw.get("status");
            status = statusText(value);
            if (status == null && raw.get("thread") instanceof Map<?, ?> thread) {
                status = statusText(thread.get("status"));
            }
        }
        if ("interrupted".equalsIgnoreCase(status)) {
            return CodexLifecycleStateStore.State.INTERRUPTED;
        }
        if ("completed".equalsIgnoreCase(status)) {
            return CodexLifecycleStateStore.State.COMPLETED;
        }
        if ("idle".equalsIgnoreCase(status)) {
            return CodexLifecycleStateStore.State.IDLE;
        }
        if ("running".equalsIgnoreCase(status)) {
            return CodexLifecycleStateStore.State.RUNNING;
        }
        return prior.state() == CodexLifecycleStateStore.State.RUNNING
                || prior.state() == CodexLifecycleStateStore.State.INTERRUPTING
                || prior.state() == CodexLifecycleStateStore.State.AMBIGUOUS
                ? CodexLifecycleStateStore.State.AMBIGUOUS : prior.state();
    }

    private static boolean ownedTermination(ProcessTreeTerminator.Outcome outcome) {
        return outcome == ProcessTreeTerminator.Outcome.CLEAN_GRACEFUL
                || outcome == ProcessTreeTerminator.Outcome.FORCED
                || outcome == ProcessTreeTerminator.Outcome.ROOT_ALREADY_EXITED;
    }

    private static void closeStreams(AppServerProcess process) {
        try {
            process.process()
                    .getInputStream()
                    .close();
        } catch (IOException ignored) {
            // Best effort after ownership was lost.
        }
        try {
            process.process()
                    .getOutputStream()
                    .close();
        } catch (IOException ignored) {
            // Best effort after ownership was lost.
        }
    }

    private static Map<String, Object> protocolParams(LifecycleControlRequestEnvelope request,
            Map<String, ?> base) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>(base);
        String approvalPolicy = request.options()
                .get("codexApprovalPolicy");
        if (approvalPolicy == null || approvalPolicy.isBlank()) {
            return params;
        }
        if (!Set.of("never", "on-request", "untrusted")
                .contains(approvalPolicy)) {
            throw new IOException("codex_approval_policy_invalid");
        }
        params.put("approvalPolicy", approvalPolicy);
        return params;
    }

    private static void requireActiveTurn(CodexLifecycleStateStore.Checkpoint checkpoint) throws IOException {
        if (checkpoint.state() != CodexLifecycleStateStore.State.RUNNING
                && checkpoint.state() != CodexLifecycleStateStore.State.INTERRUPTING) {
            throw new IOException("lifecycle_turn_not_active");
        }
    }

    private static boolean waitObservable(CodexLifecycleStateStore.State state) {
        return switch (state) {
            case IDLE, INTERRUPTED, COMPLETED, FAILED, INTERACTION_REQUIRED, STOPPED, AMBIGUOUS -> true;
            default -> false;
        };
    }

    private static String responseId(CodexAppServerProtocolClient.Response response, String key) throws IOException {
        if (!(response.result() instanceof Map<?, ?> raw)) {
            throw new IOException("missing " + key + " response");
        }
        Object value = raw.get(key);
        if (value == null && raw.get("thread") instanceof Map<?, ?> thread) {
            value = thread.get(key);
            if (value == null && key.endsWith("Id")) {
                value = thread.get("id");
            }
        }
        if (value == null && raw.get("turn") instanceof Map<?, ?> turn) {
            value = turn.get(key);
            if (value == null && key.endsWith("Id")) {
                value = turn.get("id");
            }
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IOException("missing " + key + " response");
        }
        return text;
    }

    private static String optionalResponseId(CodexAppServerProtocolClient.Response response) {
        if (!(response.result() instanceof Map<?, ?> raw)) {
            return null;
        }
        Object value = raw.get("threadId");
        if (value == null && raw.get("thread") instanceof Map<?, ?> thread) {
            value = thread.get("threadId");
            if (value == null) {
                value = thread.get("id");
            }
        }
        if (value == null && raw.get("turn") instanceof Map<?, ?> turn) {
            value = turn.get("threadId");
            if (value == null) {
                value = turn.get("id");
            }
        }
        return value == null ? null : String.valueOf(value);
    }

    private static CodexAppServerProtocolClient.Response requireSuccessful(
            CodexAppServerProtocolClient.Response response, String method) throws IOException {
        if (response.failed()) {
            throw new IOException("codex_request_failed:" + method);
        }
        return response;
    }

    private static void requireOperation(LifecycleControlRequestEnvelope request,
            LifecycleControlRequestEnvelope.Operation operation) {
        Objects.requireNonNull(request, "request");
        if (request.operation() != operation) {
            throw new IllegalArgumentException("operation mismatch");
        }
    }

    private static CodexLifecycleHttpClient.Response alreadyStartedResponse(
            CodexLifecycleStateStore.State state, long revision) {
        return response(true, "already_started", state, revision, null, null);
    }

    private static CodexLifecycleHttpClient.Response response(boolean success, String diagnostic,
            CodexLifecycleStateStore.State state, long revision, String threadId, String turnId) {
        return response(success, diagnostic, state, revision, threadId, turnId, Map.of());
    }

    private static CodexLifecycleHttpClient.Response response(boolean success, String diagnostic,
            CodexLifecycleStateStore.State state, long revision, String threadId, String turnId,
            Map<String, Object> result) {
        return new CodexLifecycleHttpClient.Response(success, diagnostic, state.name(), revision, threadId, turnId,
                result);
    }

    private static String diagnostic(Throwable failure) {
        String value = failure.getMessage() == null ? failure.getClass()
                                                      .getSimpleName() : failure.getMessage();
        return value.length() > 1024 ? value.substring(0, 1024) : value;
    }

    private static String statusText(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (type != null) {
                return statusText(type);
            }
            Object status = map.get("status");
            if (status != null) {
                return statusText(status);
            }
        }
        return value == null ? null : String.valueOf(value);
    }

    private static void drainStderr(InputStream stderr, CodexEvidenceJournal journal) {
        Thread thread = new Thread(() -> {
            try (stderr) {
                byte[] buffer = new byte[4096];
                int read;
                StringBuilder text = new StringBuilder();
                while ((read = stderr.read(buffer)) >= 0) {
                    text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    if (text.length() > 64 * 1024) {
                        text.delete(0, text.length() - 64 * 1024);
                    }
                    if (text.indexOf("\n") >= 0) {
                        journal.offer("process_stderr", Map.of("text", text.toString()), false);
                        text.setLength(0);
                    }
                }
                if (!text.isEmpty()) {
                    journal.offer("process_stderr", Map.of("text", text.toString()), false);
                }
            } catch (IOException failure) {
                journal.offer("process_stderr_failure", Map.of("diagnostic", diagnostic(failure)), true);
            }
        }, "synesis-codex-stderr-reader");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Starts a verified attachment, initializes it, creates an exact thread,
     * and explicitly starts the initial turn.
     *
     * @param request immutable START request
     * @return bounded lifecycle response
     * @throws Exception when App Server startup or protocol validation fails
     */
    public CodexLifecycleHttpClient.Response start(LifecycleControlRequestEnvelope request) throws Exception {
        requireOperation(request, LifecycleControlRequestEnvelope.Operation.START);
        synchronized (stateLock) {
            if (active != null && active.process()
                    .process()
                    .isAlive()) {
                return alreadyStartedResponse(checkpoint().state(), checkpoint().revision());
            }
        }
        CodexLifecycleStateStore.Checkpoint prior = checkpoint();
        long attachmentGeneration = prior.attachmentGeneration() + 1L;
        long connectionGeneration = prior.connectionGeneration() + 1L;
        transition(prior, CodexLifecycleStateStore.State.STARTING, attachmentGeneration, connectionGeneration,
                -1L, "none", "none", null, null, null);
        AppServerProcess process = null;
        CodexEvidenceJournal journal = null;
        CodexAppServerProtocolClient protocol = null;
        try {
            process = launcher.launch(authority, attachmentGeneration);
            startingProcess = process;
            if (closed) {
                throw new IOException("owner_shutdown");
            }
            journal = new CodexEvidenceJournal(evidenceDirectory.resolve(
                    "generation-" + connectionGeneration + ".jsonl"));
            monitorProcessExit(process, journal, attachmentGeneration, connectionGeneration);
            CompletableFuture<String> threadStarted = new CompletableFuture<>();
            CompletableFuture<String> turnStarted = new CompletableFuture<>();
            AppServerProcess ownedProcess = process;
            protocol = new CodexAppServerProtocolClient(connectionGeneration, process.stdout(), process.stdin(),
                    new Listener(threadStarted, turnStarted, journal, ownedProcess, attachmentGeneration,
                            connectionGeneration), journal);
            drainStderr(process.stderr(), journal);
            Duration timeout = remaining(request);
            requireSuccessful(protocol.request("initialize", CodexAppServerProtocolSchema.initializeParams(),
                    LifecycleControlRequestEnvelope.Classification.STATE_CHANGING, request.digest(), null, null,
                    timeout), "initialize");
            protocol.notify("initialized", Map.of());
            CodexAppServerProtocolClient.Response threadResponse = requireSuccessful(protocol.request("thread/start",
                    protocolParams(request, Map.of("cwd", authority.realWorktree())),
                    LifecycleControlRequestEnvelope.Classification.STATE_CHANGING, request.digest(), null, null,
                    remaining(request)), "thread/start");
            String threadId = responseId(threadResponse, "threadId");
            threadStarted.get(Math.max(1L, remaining(request).toMillis()), TimeUnit.MILLISECONDS);
            if (!threadStarted.getNow("")
                    .equals(threadId)) {
                throw new IOException("thread/started identity mismatch");
            }
            synchronized (stateLock) {
                active = new Attachment(process, protocol, journal, attachmentGeneration, connectionGeneration);
            }
            CodexLifecycleStateStore.Checkpoint threadCheckpoint = checkpoint();
            transition(threadCheckpoint,
                    CodexLifecycleStateStore.State.IDLE,
                    attachmentGeneration,
                    connectionGeneration,
                    process.process()
                            .pid(),
                    process.executable(),
                    process.commandIdentity(),
                    threadId,
                    null,
                    null);
            CodexAppServerProtocolClient.Response turnResponse = requireSuccessful(protocol.request("turn/start",
                    protocolParams(request, Map.of("threadId", threadId, "input",
                            CodexAppServerProtocolSchema.textInput(request.input()))),
                    LifecycleControlRequestEnvelope.Classification.STATE_CHANGING, request.digest(), threadId, null,
                    remaining(request)), "turn/start");
            String turnId = responseId(turnResponse, "turnId");
            turnStarted.get(Math.max(1L, remaining(request).toMillis()), TimeUnit.MILLISECONDS);
            if (!turnStarted.getNow("")
                    .equals(turnId)) {
                throw new IOException("turn/started identity mismatch");
            }
            startingProcess = null;
            CodexLifecycleStateStore.Checkpoint latest = checkpoint();
            if (latest.state() != CodexLifecycleStateStore.State.COMPLETED
                    && latest.state() != CodexLifecycleStateStore.State.INTERRUPTED) {
                transition(latest,
                        CodexLifecycleStateStore.State.RUNNING,
                        attachmentGeneration,
                        connectionGeneration,
                        process.process()
                                .pid(),
                        process.executable(),
                        process.commandIdentity(),
                        threadId,
                        turnId,
                        null);
                latest = checkpoint();
            }
            return response(true, "started", latest.state(), latest.revision(),
                    threadId, turnId);
        } catch (Exception failure) {
            if (protocol != null) {
                protocol.close();
            }
            if (journal != null) {
                try {
                    journal.close();
                } catch (IOException ignored) {
                    // Preserve the authoritative lifecycle failure.
                    journal.markIncomplete();
                }
                persistEvidenceCompleteness(checkpointUnchecked(), journal);
            }
            terminateStartupProcess(process, attachmentGeneration);
            startingProcess = null;
            CodexLifecycleStateStore.Checkpoint latest = checkpoint();
            transition(latest, CodexLifecycleStateStore.State.FAILED, attachmentGeneration, connectionGeneration,
                    process == null ? -1L : process.process()
                                            .pid(), process == null ? "none" : process.executable(),
                    process == null ? "none" : process.commandIdentity(), latest.threadId(), latest.turnId(),
                    diagnostic(failure));
            throw failure;
        }
    }

    /**
     * Sends continuation input to the exact stored thread.
     *
     * @param request immutable NOTIFY request
     * @return bounded lifecycle response
     * @throws Exception when the exact attachment or protocol fails
     */
    public CodexLifecycleHttpClient.Response notify(LifecycleControlRequestEnvelope request) throws Exception {
        requireOperation(request, LifecycleControlRequestEnvelope.Operation.NOTIFY);
        return startTurn(request);
    }

    /**
     * Steers the exact active turn.
     *
     * @param request immutable STEER request
     * @return bounded lifecycle response
     * @throws Exception when the exact attachment or protocol fails
     */
    public CodexLifecycleHttpClient.Response steer(LifecycleControlRequestEnvelope request) throws Exception {
        requireOperation(request, LifecycleControlRequestEnvelope.Operation.STEER);
        Attachment attachment = requireActive();
        requireExact(request, attachment);
        requireActiveTurn(checkpoint());
        requireSuccessful(attachment.protocol()
                .request("turn/steer", Map.of("threadId", request.expectedThreadId(),
                                "expectedTurnId", request.expectedTurnId(),
                                "input", CodexAppServerProtocolSchema.textInput(request.input())),
                        LifecycleControlRequestEnvelope.Classification.STATE_CHANGING, request.digest(),
                        request.expectedThreadId(), request.expectedTurnId(), remaining(request)), "turn/steer");
        return response(true, "steered", checkpoint().state(), checkpoint().revision(), request.expectedThreadId(),
                request.expectedTurnId());
    }

    /**
     * Registers and awaits one exact-thread transition outside the state lock.
     *
     * @param request immutable WAIT request with a positive deadline
     * @return terminal or deadline response
     * @throws Exception when registration, cancellation, or waiting fails
     */
    public CodexLifecycleHttpClient.Response waitForTurn(LifecycleControlRequestEnvelope request) throws Exception {
        requireOperation(request, LifecycleControlRequestEnvelope.Operation.WAIT);
        long remaining = remaining(request).toMillis();
        if (remaining <= 0) {
            throw new TimeoutException("lifecycle wait deadline expired");
        }
        CompletableFuture<CodexLifecycleHttpClient.Response> future = new CompletableFuture<>();
        String waiterId = request.requestId()
                .toString();
        synchronized (stateLock) {
            if (waiters.size() >= 32 || activeWaitersForBinding() >= 4) {
                throw new IOException("lifecycle_wait_capacity_exceeded");
            }
            CodexLifecycleStateStore.Checkpoint current = checkpoint();
            if (request.expectedThreadId() != null && !request.expectedThreadId()
                    .equals(current.threadId())) {
                throw new IOException("lifecycle_thread_identity_mismatch");
            }
            if (waitObservable(current.state())
                    && (request.expectedTurnId() == null || request.expectedTurnId()
                    .equals(current.turnId()))) {
                future.complete(response(true, "state_changed", current.state(), current.revision(),
                        current.threadId(), current.turnId()));
            } else {
                waiters.put(waiterId, new Waiter(request.expectedThreadId(), request.expectedTurnId(), future));
            }
        }
        try {
            scheduler.schedule(() -> future.complete(response(true, "wait_deadline", checkpoint().state(),
                            checkpoint().revision(), checkpoint().threadId(), checkpoint().turnId())), remaining,
                    TimeUnit.MILLISECONDS);
            return future.get(remaining + 100L, TimeUnit.MILLISECONDS);
        } finally {
            synchronized (stateLock) {
                waiters.remove(waiterId);
            }
        }
    }

    /**
     * Interrupts the exact active turn and waits for interrupted completion.
     *
     * @param request immutable INTERRUPT request
     * @return response after the exact interrupted terminal event
     * @throws Exception when acknowledgement or terminal completion fails
     */
    public CodexLifecycleHttpClient.Response interrupt(LifecycleControlRequestEnvelope request) throws Exception {
        requireOperation(request, LifecycleControlRequestEnvelope.Operation.INTERRUPT);
        Attachment attachment = requireActive();
        requireExact(request, attachment);
        CodexLifecycleStateStore.Checkpoint current = checkpoint();
        requireActiveTurn(current);
        transition(current,
                CodexLifecycleStateStore.State.INTERRUPTING,
                current.attachmentGeneration(),
                current.connectionGeneration(),
                current.rootPid(),
                current.rootExecutable(),
                current.rootCommandIdentity(),
                current.threadId(),
                current.turnId(),
                null);
        requireSuccessful(attachment.protocol()
                .request("turn/interrupt", Map.of("threadId", request.expectedThreadId(),
                                "turnId", request.expectedTurnId()),
                        LifecycleControlRequestEnvelope.Classification.STATE_CHANGING, request.digest(),
                        request.expectedThreadId(), request.expectedTurnId(), remaining(request)), "turn/interrupt");
        return waitForTerminal(request);
    }

    /**
     * Hard-stops the verified App Server process tree.
     *
     * @param request immutable HARD_STOP request
     * @return structured termination response
     * @throws Exception when checkpoint or evidence persistence fails
     */
    public CodexLifecycleHttpClient.Response hardStop(LifecycleControlRequestEnvelope request) throws Exception {
        requireOperation(request, LifecycleControlRequestEnvelope.Operation.HARD_STOP);
        CodexLifecycleStateStore.Checkpoint current = checkpoint();
        if (current.rootPid() <= 0) {
            transition(current, CodexLifecycleStateStore.State.STOPPED, current.attachmentGeneration(),
                    current.connectionGeneration(), -1L, "none", "none", current.threadId(), current.turnId(), null);
            return response(true, "root_already_exited", CodexLifecycleStateStore.State.STOPPED,
                    checkpoint().revision(), current.threadId(), current.turnId());
        }
        ProcessTreeTerminator.AttachmentIdentity identity = new ProcessTreeTerminator.AttachmentIdentity(
                current.rootPid(),
                current.rootExecutable(),
                current.rootCommandIdentity(),
                current.rootStartEpochMillis(),
                current.attachmentGeneration());
        ProcessTreeTerminator.Result result = terminator.terminate(identity, current.attachmentGeneration(),
                Duration.ofSeconds(2), Instant.ofEpochMilli(request.callerDeadlineEpochMillis()));
        Attachment attachment = active;
        if (attachment != null) {
            attachment.journal()
                    .offer("hard_stop_result",
                            Map.of("outcome",
                                    result.outcome()
                                            .name(),
                                    "diagnostic",
                                    result.diagnostic(),
                                    "survivors",
                                    result.survivors()),
                            true);
            attachment.protocol()
                    .close();
            try {
                attachment.journal()
                        .close();
            } catch (IOException failure) {
                attachment.journal()
                        .markIncomplete();
                current = new CodexLifecycleStateStore.Checkpoint(current.bindingSessionId(), current.projectId(),
                        current.provider(), current.revision(), current.state(), current.ownerHostInstanceId(),
                        current.attachmentGeneration(), current.connectionGeneration(), current.rootPid(),
                        current.rootStartEpochMillis(), current.rootExecutable(), current.rootCommandIdentity(),
                        current.threadId(), current.turnId(), current.terminalDiagnostic(), false,
                        System.currentTimeMillis());
            }
            current = persistEvidenceCompleteness(current, attachment.journal());
            if (active == attachment) {
                active = null;
            }
        }
        CodexLifecycleStateStore.State state = result.outcome() == ProcessTreeTerminator.Outcome.CLEAN_GRACEFUL
                || result.outcome() == ProcessTreeTerminator.Outcome.FORCED
                || result.outcome() == ProcessTreeTerminator.Outcome.ROOT_ALREADY_EXITED
                ? CodexLifecycleStateStore.State.STOPPED : CodexLifecycleStateStore.State.FAILED;
        transition(current, state, current.attachmentGeneration(), current.connectionGeneration(),
                result.outcome() == ProcessTreeTerminator.Outcome.ROOT_SURVIVED ? current.rootPid() : -1L,
                current.rootExecutable(), current.rootCommandIdentity(), current.threadId(), current.turnId(),
                result.diagnostic());
        return response(state == CodexLifecycleStateStore.State.STOPPED, result.diagnostic(), state,
                checkpoint().revision(), current.threadId(), current.turnId());
    }

    /**
     * Resumes the exact stored thread, starting model work only with continuation input.
     *
     * @param request immutable RESUME request
     * @return bounded resume response
     * @throws Exception when exact-thread verification fails
     */
    public CodexLifecycleHttpClient.Response resume(LifecycleControlRequestEnvelope request) throws Exception {
        requireOperation(request, LifecycleControlRequestEnvelope.Operation.RESUME);
        CodexLifecycleStateStore.Checkpoint current = checkpoint();
        if (active == null || !active.process()
                .process()
                .isAlive()) {
            resumeAttachment(request, current);
            current = checkpoint();
        }
        if (request.continuation()) {
            return startTurn(request);
        }
        requireExact(request, active);
        return response(true, "thread_resumed_without_turn", current.state(), current.revision(), current.threadId(),
                current.turnId());
    }

    private void resumeAttachment(LifecycleControlRequestEnvelope request,
            CodexLifecycleStateStore.Checkpoint prior) throws Exception {
        if (prior.threadId() == null || request.expectedThreadId() == null
                || !prior.threadId()
                .equals(request.expectedThreadId())) {
            throw new IOException("lifecycle_thread_identity_mismatch");
        }
        long attachmentGeneration = prior.attachmentGeneration() + 1L;
        long connectionGeneration = prior.connectionGeneration() + 1L;
        transition(prior, CodexLifecycleStateStore.State.STARTING, attachmentGeneration, connectionGeneration,
                -1L, "none", "none", prior.threadId(), prior.turnId(), "explicit_resume");
        AppServerProcess process = null;
        CodexEvidenceJournal journal = null;
        CodexAppServerProtocolClient protocol = null;
        CompletableFuture<String> threadResumed = new CompletableFuture<>();
        try {
            process = launcher.launch(authority, attachmentGeneration);
            startingProcess = process;
            journal = new CodexEvidenceJournal(evidenceDirectory.resolve(
                    "generation-" + connectionGeneration + ".jsonl"));
            monitorProcessExit(process, journal, attachmentGeneration, connectionGeneration);
            protocol = new CodexAppServerProtocolClient(connectionGeneration, process.stdout(), process.stdin(),
                    new Listener(threadResumed, new CompletableFuture<>(), journal, process,
                            attachmentGeneration, connectionGeneration), journal);
            drainStderr(process.stderr(), journal);
            requireSuccessful(protocol.request("initialize", CodexAppServerProtocolSchema.initializeParams(),
                    LifecycleControlRequestEnvelope.Classification.STATE_CHANGING, request.digest(), null, null,
                    remaining(request)), "initialize");
            protocol.notify("initialized", Map.of());
            CodexAppServerProtocolClient.Response resumeResponse = requireSuccessful(
                    protocol.request("thread/resume",
                            protocolParams(request, Map.of("threadId", prior.threadId())),
                            LifecycleControlRequestEnvelope.Classification.STATE_CHANGING,
                            request.digest(),
                            prior.threadId(),
                            prior.turnId(),
                            remaining(request)), "thread/resume");
            // Codex 0.145.0 acknowledges thread/resume and emits the exact
            // thread/status/changed event, but does not emit a separate
            // thread/resumed notification.  Treat the response identity (when
            // present) and the mandatory thread/read result as authoritative;
            // never wait indefinitely for an optional notification.
            String resumed = optionalResponseId(resumeResponse);
            if (resumed != null && !prior.threadId()
                    .equals(resumed)) {
                throw new IOException("thread/resume identity mismatch");
            }
            CodexAppServerProtocolClient.Response read = requireSuccessful(protocol.request("thread/read",
                    Map.of("threadId", prior.threadId()), LifecycleControlRequestEnvelope.Classification.READ_ONLY,
                    request.digest(), prior.threadId(), prior.turnId(), remaining(request)), "thread/read");
            String readThread = optionalResponseId(read);
            if (readThread != null && !prior.threadId()
                    .equals(readThread)) {
                throw new IOException("thread/read identity mismatch");
            }
            active = new Attachment(process, protocol, journal, attachmentGeneration, connectionGeneration);
            startingProcess = null;
            CodexLifecycleStateStore.State reconciled = reconcileReadState(prior, read);
            CodexLifecycleStateStore.Checkpoint latest = checkpoint();
            transition(latest,
                    reconciled,
                    attachmentGeneration,
                    connectionGeneration,
                    process.process()
                            .pid(),
                    process.executable(),
                    process.commandIdentity(),
                    prior.threadId(),
                    prior.turnId(),
                    "explicit_thread_resume");
        } catch (Exception failure) {
            if (protocol != null) {
                protocol.close();
            }
            if (journal != null) {
                try {
                    journal.close();
                } catch (IOException ignored) {
                    // Preserve authoritative resume failure.
                    journal.markIncomplete();
                }
                persistEvidenceCompleteness(checkpointUnchecked(), journal);
            }
            terminateStartupProcess(process, attachmentGeneration);
            active = null;
            startingProcess = null;
            CodexLifecycleStateStore.Checkpoint latest = checkpointUnchecked();
            transition(latest, CodexLifecycleStateStore.State.AMBIGUOUS, attachmentGeneration, connectionGeneration,
                    process == null ? -1L : process.process()
                                            .pid(), process == null ? "none" : process.executable(),
                    process == null ? "none" : process.commandIdentity(), prior.threadId(), prior.turnId(),
                    diagnostic(failure));
            throw failure;
        }
    }

    /**
     * Returns the authoritative checkpoint without mutation.
     *
     * @param request immutable STATUS request
     * @return bounded status response
     * @throws IOException when the checkpoint is unreadable
     */
    public CodexLifecycleHttpClient.Response status(LifecycleControlRequestEnvelope request) throws IOException {
        requireOperation(request, LifecycleControlRequestEnvelope.Operation.STATUS);
        return statusUnchecked();
    }

    /**
     * Returns the checkpoint for the owner dispatcher without operation validation.
     *
     * @return bounded authoritative response
     */
    public CodexLifecycleHttpClient.Response statusUnchecked() {
        CodexLifecycleStateStore.Checkpoint current = checkpointUnchecked();
        return response(true, "status", current.state(), current.revision(), current.threadId(), current.turnId());
    }

    /**
     * Returns bounded owner diagnostics for status and doctor projections.
     *
     * @return immutable diagnostic fields
     */
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("waiters", waiters.size());
        Attachment attachment = active;
        result.put("connectionGeneration", attachment == null ? 0L : attachment.connectionGeneration());
        result.put("tombstones",
                attachment == null ? 0 : attachment.protocol()
                                         .tombstones()
                                         .size());
        result.put("protocolFailed",
                attachment != null && attachment.protocol()
                        .failed());
        result.put("evidenceComplete",
                attachment == null || attachment.journal()
                        .evidenceComplete());
        result.put("evidenceEventsDropped",
                attachment == null ? 0L : attachment.journal()
                                          .eventsDropped());
        result.put("evidencePersistedBytes",
                attachment == null ? 0L : attachment.journal()
                                          .persistedBytes());
        result.put("evidenceOverflow",
                attachment != null && attachment.journal()
                        .overflowMarker());
        if (attachment != null) {
            result.putAll(attachment.protocol()
                    .diagnostics());
        }
        return Map.copyOf(result);
    }

    /**
     * Reports whether this service still retains a live App Server attachment.
     *
     * @return live attachment state
     */
    public boolean attachmentAlive() {
        Attachment attachment = active;
        return attachment != null && attachment.process()
                .process()
                .isAlive();
    }

    /**
     * Wakes all WAIT callers and closes the owned attachment.
     */
    @Override
    public void close() {
        closed = true;
        synchronized (stateLock) {
            waiters.values()
                    .forEach(waiter -> waiter.future()
                            .complete(response(false, "owner_shutdown",
                                    CodexLifecycleStateStore.State.STOPPED, checkpointUnchecked().revision(),
                                    checkpointUnchecked().threadId(), checkpointUnchecked().turnId())));
            waiters.clear();
        }
        try {
            shutdownAttachment();
        } catch (IOException ignored) {
            // The authoritative owner shutdown path remains bounded.
        }
        waitExecutor.shutdownNow();
        scheduler.shutdownNow();
    }

    private void shutdownAttachment() throws IOException {
        Attachment attachment = active;
        AppServerProcess launching = startingProcess;
        if (attachment == null && launching != null) {
            ProcessTreeTerminator.AttachmentIdentity identity = new ProcessTreeTerminator.AttachmentIdentity(
                    launching.process()
                            .pid(), launching.executable(), launching.commandIdentity(),
                    launching.startEpochMillis(), checkpointUnchecked().attachmentGeneration());
            ProcessTreeTerminator.Result result = terminator.terminate(identity,
                    identity.attachmentGeneration(),
                    Duration.ofMillis(500),
                    Instant.now()
                            .plusSeconds(2));
            if (ownedTermination(result.outcome())) {
                launching.close();
            } else {
                closeStreams(launching);
            }
            startingProcess = null;
            return;
        }
        if (attachment == null) {
            return;
        }
        CodexLifecycleStateStore.Checkpoint current = checkpointUnchecked();
        if (current.threadId() != null && current.turnId() != null
                && (current.state() == CodexLifecycleStateStore.State.RUNNING
                || current.state() == CodexLifecycleStateStore.State.INTERRUPTING)) {
            try {
                transition(current, CodexLifecycleStateStore.State.INTERRUPTING, current.attachmentGeneration(),
                        current.connectionGeneration(), current.rootPid(), current.rootExecutable(),
                        current.rootCommandIdentity(), current.threadId(), current.turnId(), "owner_shutdown");
                attachment.protocol()
                        .request("turn/interrupt",
                                Map.of("threadId", current.threadId(),
                                        "turnId", current.turnId()),
                                LifecycleControlRequestEnvelope.Classification.STATE_CHANGING,
                                "owner-shutdown",
                                current.threadId(),
                                current.turnId(),
                                Duration.ofMillis(500));
                long interruptionDeadline = System.currentTimeMillis() + 500L;
                while (System.currentTimeMillis() < interruptionDeadline
                        && checkpointUnchecked().state() == CodexLifecycleStateStore.State.INTERRUPTING) {
                    java.util.concurrent.locks.LockSupport.parkNanos(
                            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(25L));
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }
            } catch (Exception ignored) {
                // Hard-stop escalation below is the bounded fallback.
            }
        }
        ProcessTreeTerminator.Result termination = null;
        if (attachment.process()
                .process()
                .isAlive() && current.rootPid() > 0) {
            ProcessTreeTerminator.AttachmentIdentity identity = new ProcessTreeTerminator.AttachmentIdentity(
                    current.rootPid(), current.rootExecutable(), current.rootCommandIdentity(),
                    current.rootStartEpochMillis(), current.attachmentGeneration());
            ProcessTreeTerminator.Result result = terminator.terminate(identity,
                    current.attachmentGeneration(),
                    Duration.ofMillis(500),
                    Instant.now()
                            .plusSeconds(2));
            termination = result;
            if (ownedTermination(result.outcome())) {
                attachment.process()
                        .process()
                        .destroyForcibly();
            }
        }
        attachment.protocol()
                .close();
        try {
            attachment.journal()
                    .close();
        } catch (IOException failure) {
            attachment.journal()
                    .markIncomplete();
            current = new CodexLifecycleStateStore.Checkpoint(current.bindingSessionId(), current.projectId(),
                    current.provider(), current.revision(), current.state(), current.ownerHostInstanceId(),
                    current.attachmentGeneration(), current.connectionGeneration(), current.rootPid(),
                    current.rootStartEpochMillis(), current.rootExecutable(), current.rootCommandIdentity(),
                    current.threadId(), current.turnId(), current.terminalDiagnostic(), false,
                    System.currentTimeMillis());
        }
        persistEvidenceCompleteness(current, attachment.journal());
        active = null;
        current = checkpointUnchecked();
        try {
            CodexLifecycleStateStore.State shutdownState =
                    termination == null || ownedTermination(termination.outcome())
                            ? CodexLifecycleStateStore.State.STOPPED : CodexLifecycleStateStore.State.FAILED;
            transition(current,
                    shutdownState,
                    current.attachmentGeneration(),
                    current.connectionGeneration(),
                    -1L,
                    current.rootExecutable(),
                    current.rootCommandIdentity(),
                    current.threadId(),
                    current.turnId(),
                    termination == null ? "owner_shutdown" : termination.diagnostic());
        } catch (IOException ignored) {
            // Preserve the last durable checkpoint when shutdown is already failing.
        }
    }

    private void terminateStartupProcess(AppServerProcess process, long attachmentGeneration) {
        if (process == null || !process.process()
                .isAlive()) {
            return;
        }
        try {
            ProcessTreeTerminator.AttachmentIdentity identity = new ProcessTreeTerminator.AttachmentIdentity(
                    process.process()
                            .pid(), process.executable(), process.commandIdentity(),
                    process.startEpochMillis(), attachmentGeneration);
            ProcessTreeTerminator.Result result = terminator.terminate(identity,
                    attachmentGeneration,
                    Duration.ofMillis(250),
                    Instant.now()
                            .plusSeconds(2));
            if (ownedTermination(result.outcome())) {
                process.process()
                        .destroyForcibly();
            }
        } catch (RuntimeException ignored) {
            // An unproven startup process is never targeted by fallback PID.
        }
    }

    private CodexLifecycleHttpClient.Response startTurn(LifecycleControlRequestEnvelope request) throws Exception {
        Attachment attachment = requireActive();
        CodexLifecycleStateStore.Checkpoint current = checkpoint();
        if (current.state() == CodexLifecycleStateStore.State.RUNNING
                || current.state() == CodexLifecycleStateStore.State.INTERRUPTING
                || current.state() == CodexLifecycleStateStore.State.STARTING) {
            throw new IOException("lifecycle_turn_already_active");
        }
        String threadId = request.expectedThreadId() == null ? current.threadId() : request.expectedThreadId();
        if (threadId == null || !threadId.equals(current.threadId())) {
            throw new IOException("lifecycle_thread_identity_mismatch");
        }
        CodexAppServerProtocolClient.Response response = requireSuccessful(attachment.protocol()
                .request("turn/start",
                        protocolParams(request, Map.of("threadId", threadId,
                                "input", CodexAppServerProtocolSchema.textInput(request.input()))),
                        LifecycleControlRequestEnvelope.Classification.STATE_CHANGING, request.digest(), threadId,
                        request.expectedTurnId(), remaining(request)), "turn/start");
        String turnId = responseId(response, "turnId");
        waitForTurnStarted(threadId, turnId, request);
        CodexLifecycleStateStore.Checkpoint latest = checkpoint();
        return response(true, "continued", latest.state(),
                latest.revision(), threadId, turnId);
    }

    private void waitForTurnStarted(String threadId, String turnId,
            LifecycleControlRequestEnvelope request) throws Exception {
        long deadline = request.callerDeadlineEpochMillis();
        while (System.currentTimeMillis() < deadline) {
            CodexLifecycleStateStore.Checkpoint current = checkpoint();
            if (threadId.equals(current.threadId()) && turnId.equals(current.turnId())
                    && (current.state() == CodexLifecycleStateStore.State.RUNNING
                    || current.state() == CodexLifecycleStateStore.State.COMPLETED
                    || current.state() == CodexLifecycleStateStore.State.INTERRUPTED)) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                            Math.clamp(deadline - System.currentTimeMillis(), 1L, 25L)));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
        throw new TimeoutException("turn/started event deadline expired");
    }

    private CodexLifecycleHttpClient.Response waitForTerminal(LifecycleControlRequestEnvelope request)
            throws Exception {
        long remaining = remaining(request).toMillis();
        while (remaining > 0) {
            CodexLifecycleStateStore.Checkpoint current = checkpoint();
            if (current.state() == CodexLifecycleStateStore.State.INTERRUPTED) {
                return response(true, "turn_interrupted", CodexLifecycleStateStore.State.INTERRUPTED,
                        current.revision(), current.threadId(),
                        current.turnId(), Map.of("interruptionClassification",
                                "turn_interrupted_command_state_unconfirmed"));
            }
            Thread.sleep(Math.min(25L, remaining));
            remaining = remaining(request).toMillis();
        }
        throw new TimeoutException("turn terminal event deadline expired");
    }

    private Attachment requireActive() throws IOException {
        Attachment attachment = active;
        if (attachment == null || !attachment.process()
                .process()
                .isAlive()) {
            throw new IOException("lifecycle_attachment_missing");
        }
        return attachment;
    }

    private void requireExact(LifecycleControlRequestEnvelope request, Attachment attachment) throws IOException {
        CodexLifecycleStateStore.Checkpoint current = checkpoint();
        if (request.expectedThreadId() == null || !request.expectedThreadId()
                .equals(current.threadId())
                || (request.expectedTurnId() != null && !request.expectedTurnId()
                .equals(current.turnId()))) {
            throw new IOException("lifecycle_thread_or_turn_identity_mismatch");
        }
        if (attachment.connectionGeneration() != current.connectionGeneration()) {
            throw new IOException("connection_generation_mismatch");
        }
    }

    private CodexLifecycleStateStore.Checkpoint checkpoint() throws IOException {
        return stateStore.read(authority.bindingSessionId(), authority.projectId());
    }

    private CodexLifecycleStateStore.Checkpoint checkpointUnchecked() {
        try {
            return checkpoint();
        } catch (IOException failure) {
            return new CodexLifecycleStateStore.Checkpoint(authority.bindingSessionId(), authority.projectId(), "codex",
                    0L, CodexLifecycleStateStore.State.FAILED, "unknown", 0L, 0L, -1L, 0L, "none", "none", null,
                    null, "checkpoint_unreadable", false, System.currentTimeMillis());
        }
    }

    private void monitorProcessExit(AppServerProcess process, CodexEvidenceJournal journal,
            long attachmentGeneration, long connectionGeneration) {
        // Deterministic Process test doubles often implement waitFor() as an
        // immediate state transition and do not have an operating-system
        // ProcessHandle.  Their protocol fixtures report process/exited
        // explicitly; only observe a real OS-owned process here.
        if (ProcessHandle.of(process.process()
                        .pid())
                .isEmpty()) {
            return;
        }
        Thread monitor = new Thread(() -> {
            try {
                process.process()
                        .waitFor();
                if (closed) {
                    return;
                }
                CodexLifecycleStateStore.Checkpoint current = checkpoint();
                if (current.attachmentGeneration() != attachmentGeneration
                        || current.connectionGeneration() != connectionGeneration
                        || current.state() == CodexLifecycleStateStore.State.STOPPED) {
                    return;
                }
                CodexLifecycleStateStore.State next = switch (current.state()) {
                    case COMPLETED, INTERRUPTED, FAILED, INTERACTION_REQUIRED -> current.state();
                    default -> CodexLifecycleStateStore.State.FAILED;
                };
                transition(current, next, attachmentGeneration, connectionGeneration, -1L,
                        process.executable(), process.commandIdentity(), current.threadId(), current.turnId(),
                        "process_exit");
                journal.offer("process_exit",
                        Map.of("pid",
                                process.process()
                                        .pid()),
                        true);
                scheduler.execute(() -> closeExitedAttachment(attachmentGeneration, journal));
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                        .interrupt();
            } catch (IOException ignored) {
                // The owner retains the last durable checkpoint on a failed
                // process-observation read; protocol failure remains visible.
            }
        }, "synesis-codex-process-exit-" + attachmentGeneration);
        monitor.setDaemon(true);
        monitor.start();
    }

    private void closeExitedAttachment(long attachmentGeneration, CodexEvidenceJournal journal) {
        Attachment attachment = active;
        if (attachment == null || attachment.attachmentGeneration() != attachmentGeneration) {
            return;
        }
        active = null;
        attachment.protocol()
                .close();
        try {
            attachment.journal()
                    .close();
        } catch (IOException failure) {
            attachment.journal()
                    .markIncomplete();
        }
        try {
            persistEvidenceCompleteness(checkpointUnchecked(), journal);
        } catch (IOException ignored) {
            // The process-exit checkpoint remains authoritative.
        }
    }

    private void transition(CodexLifecycleStateStore.Checkpoint prior, CodexLifecycleStateStore.State state,
            long attachmentGeneration, long connectionGeneration, long rootPid, String executable,
            String commandIdentity, String threadId, String turnId, String diagnostic) throws IOException {
        synchronized (stateLock) {
            CodexLifecycleStateStore.Checkpoint next = new CodexLifecycleStateStore.Checkpoint(
                    authority.bindingSessionId(), authority.projectId(), "codex", prior.revision() + 1L, state,
                    prior.ownerHostInstanceId(), attachmentGeneration, connectionGeneration, rootPid,
                    rootPid > 0 && active != null ? active.process()
                                                    .startEpochMillis() : prior.rootStartEpochMillis(),
                    executable == null ? "none" : executable, commandIdentity == null ? "none" : commandIdentity,
                    threadId, turnId, diagnostic, prior.evidenceComplete()
                    && (active == null || active.journal()
                    .evidenceComplete()), System.currentTimeMillis());
            stateStore.write(next);
            notifyWaiters(next);
        }
    }

    private CodexLifecycleStateStore.Checkpoint persistEvidenceCompleteness(
            CodexLifecycleStateStore.Checkpoint prior, CodexEvidenceJournal journal) throws IOException {
        if (journal == null || journal.evidenceComplete() || !prior.evidenceComplete()) {
            return prior;
        }
        CodexLifecycleStateStore.Checkpoint incomplete = new CodexLifecycleStateStore.Checkpoint(
                prior.bindingSessionId(), prior.projectId(), prior.provider(), prior.revision() + 1L,
                prior.state(), prior.ownerHostInstanceId(), prior.attachmentGeneration(), prior.connectionGeneration(),
                prior.rootPid(), prior.rootStartEpochMillis(), prior.rootExecutable(), prior.rootCommandIdentity(),
                prior.threadId(), prior.turnId(), prior.terminalDiagnostic(), false, System.currentTimeMillis());
        stateStore.write(incomplete);
        return incomplete;
    }

    private void notifyWaiters(CodexLifecycleStateStore.Checkpoint checkpoint) {
        if (checkpoint.state() == CodexLifecycleStateStore.State.STARTING
                || checkpoint.state() == CodexLifecycleStateStore.State.RUNNING
                || checkpoint.state() == CodexLifecycleStateStore.State.INTERRUPTING) {
            return;
        }
        synchronized (stateLock) {
            java.util.Iterator<Map.Entry<String, Waiter>> iterator = waiters.entrySet()
                    .iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Waiter> item = iterator.next();
                Waiter waiter = item.getValue();
                boolean threadMatches = waiter.expectedThreadId() == null
                        || waiter.expectedThreadId()
                        .equals(checkpoint.threadId());
                boolean turnMatches = waiter.expectedTurnId() == null
                        || waiter.expectedTurnId()
                        .equals(checkpoint.turnId());
                if (threadMatches && turnMatches) {
                    waiter.future()
                            .complete(response(true, "state_changed", checkpoint.state(),
                                    checkpoint.revision(), checkpoint.threadId(), checkpoint.turnId()));
                    iterator.remove();
                }
            }
        }
    }

    private int activeWaitersForBinding() {
        return waiters.size();
    }

    private Duration remaining(LifecycleControlRequestEnvelope request) throws TimeoutException {
        long remaining = request.callerDeadlineEpochMillis() - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new TimeoutException("lifecycle caller deadline expired");
        }
        return Duration.ofMillis(remaining);
    }

    private void terminateFailedAttachment(CodexLifecycleStateStore.Checkpoint checkpoint) {
        if (checkpoint.rootPid() <= 0) {
            return;
        }
        try {
            ProcessTreeTerminator.AttachmentIdentity identity = new ProcessTreeTerminator.AttachmentIdentity(
                    checkpoint.rootPid(), checkpoint.rootExecutable(), checkpoint.rootCommandIdentity(),
                    checkpoint.rootStartEpochMillis(), checkpoint.attachmentGeneration());
            ProcessTreeTerminator.Result result = terminator.terminate(identity,
                    checkpoint.attachmentGeneration(),
                    Duration.ofMillis(250),
                    Instant.now()
                            .plusSeconds(2));
            if (ownedTermination(result.outcome()) && active != null
                    && active.attachmentGeneration() == checkpoint.attachmentGeneration()) {
                Attachment failed = active;
                failed.process()
                        .process()
                        .destroyForcibly();
                failed.protocol()
                        .close();
                try {
                    failed.journal()
                            .close();
                } catch (IOException closeFailure) {
                    failed.journal()
                            .markIncomplete();
                }
                persistEvidenceCompleteness(checkpointUnchecked(), failed.journal());
                active = null;
            }
        } catch (RuntimeException ignored) {
            // An unproven process is never targeted during protocol failure cleanup.
        } catch (IOException ignored) {
            // The authoritative FAILED checkpoint remains; status/doctor
            // exposes any evidence or termination cleanup failure.
        }
    }

    /**
     * Injectable direct-process launch seam for deterministic fake servers.
     */
    @FunctionalInterface
    public interface ProcessLauncher {

        /**
         * Launches one App Server in the exact assigned worktree.
         *
         * @param authority            verified authority context
         * @param attachmentGeneration new local attachment generation
         * @return launched process attachment
         * @throws IOException when launch fails
         */
        AppServerProcess launch(LifecycleControlRequestEnvelope.AuthorityContext authority,
                long attachmentGeneration) throws IOException;
    }

    /**
     * Process and stream ownership retained by the lifecycle service.
     *
     * @param process          launched process
     * @param executable       verified executable identity
     * @param commandIdentity  verified command identity
     * @param startEpochMillis verified process start instant
     */
    public record AppServerProcess(Process process, String executable, String commandIdentity,
                                   long startEpochMillis) implements AutoCloseable {

        /**
         * Validates process ownership evidence.
         */
        public AppServerProcess {
            Objects.requireNonNull(process, "process");
            if (executable == null || executable.isBlank() || commandIdentity == null || commandIdentity.isBlank()
                    || startEpochMillis <= 0) {
                throw new IllegalArgumentException("invalid App Server process identity");
            }
        }

        /**
         * Returns process stdout.
         *
         * @return process stdout
         */
        public InputStream stdout() {
            return process.getInputStream();
        }

        /**
         * Returns process stdin.
         *
         * @return process stdin
         */
        public OutputStream stdin() {
            return process.getOutputStream();
        }

        /**
         * Returns process stderr.
         *
         * @return process stderr
         */
        public InputStream stderr() {
            return process.getErrorStream();
        }

        /**
         * Closes streams and requests normal process termination.
         */
        @Override
        public void close() {
            try {
                process.getInputStream()
                        .close();
            } catch (IOException ignored) {
                // Best effort during lifecycle shutdown.
            }
            try {
                process.getOutputStream()
                        .close();
            } catch (IOException ignored) {
                // Best effort during lifecycle shutdown.
            }
            process.destroy();
        }
    }

    /** Couples one owned App Server process with its protocol client. */
    private record Attachment(AppServerProcess process, CodexAppServerProtocolClient protocol,
                              CodexEvidenceJournal journal, long attachmentGeneration, long connectionGeneration) {

    }

    /** Tracks one bounded caller wait for an exact Codex lifecycle turn. */
    private record Waiter(String expectedThreadId, String expectedTurnId,
                          CompletableFuture<CodexLifecycleHttpClient.Response> future) {

    }

    /** Routes protocol events into the owning lifecycle state machine. */
    private final class Listener implements CodexAppServerProtocolClient.Listener {

        private final CompletableFuture<String> threadStarted;
        private final CompletableFuture<String> turnStarted;
        private final CodexEvidenceJournal journal;
        private final AppServerProcess process;
        private final long attachmentGeneration;
        private final long connectionGeneration;

        private Listener(CompletableFuture<String> threadStarted, CompletableFuture<String> turnStarted,
                CodexEvidenceJournal journal, AppServerProcess process, long attachmentGeneration,
                long connectionGeneration) {
            this.threadStarted = threadStarted;
            this.turnStarted = turnStarted;
            this.journal = journal;
            this.process = process;
            this.attachmentGeneration = attachmentGeneration;
            this.connectionGeneration = connectionGeneration;
        }

        @Override
        public void onEvent(String method, Map<String, Object> params) {
            try {
                switch (method) {
                    case "error" -> throw new IOException("codex_server_error");
                    case "thread/started", "thread/resumed" -> {
                        String id = value(params, "threadId", "thread_id");
                        if (id != null) {
                            threadStarted.complete(id);
                            CodexLifecycleStateStore.Checkpoint current = checkpoint();
                            if (current.threadId() != null && !current.threadId()
                                    .equals(id)) {
                                throw new IOException("thread event identity mismatch");
                            }
                            transition(current,
                                    CodexLifecycleStateStore.State.IDLE,
                                    attachmentGeneration,
                                    connectionGeneration,
                                    process.process()
                                            .pid(),
                                    process.executable(),
                                    process.commandIdentity(),
                                    id,
                                    current.turnId(),
                                    null);
                        }
                        journal.offer("thread_started", Map.of("threadId", id == null ? "" : id), true);
                    }
                    case "turn/started" -> {
                        String id = value(params, "turnId", "turn_id");
                        String eventThread = value(params, "threadId", "thread_id");
                        CodexLifecycleStateStore.Checkpoint current = checkpoint();
                        if (eventThread != null && current.threadId() != null
                                && !current.threadId()
                                .equals(eventThread)) {
                            throw new IOException("turn event thread identity mismatch");
                        }
                        if (id != null) {
                            turnStarted.complete(id);
                            transition(current,
                                    CodexLifecycleStateStore.State.RUNNING,
                                    attachmentGeneration,
                                    connectionGeneration,
                                    process.process()
                                            .pid(),
                                    process.executable(),
                                    process.commandIdentity(),
                                    current.threadId(),
                                    id,
                                    null);
                        }
                        journal.offer("turn_started", Map.of("turnId", id == null ? "" : id), true);
                    }
                    case "turn/completed" -> {
                        String status = value(params, "status", "turnStatus");
                        CodexLifecycleStateStore.Checkpoint current = checkpoint();
                        String eventThread = value(params, "threadId", "thread_id");
                        String eventTurn = value(params, "turnId", "turn_id");
                        if (eventThread != null && current.threadId() != null
                                && !current.threadId()
                                .equals(eventThread)) {
                            throw new IOException("completed event thread identity mismatch");
                        }
                        if (eventTurn != null && current.turnId() != null && !current.turnId()
                                .equals(eventTurn)) {
                            throw new IOException("completed event turn identity mismatch");
                        }
                        CodexLifecycleStateStore.State next = "interrupted".equalsIgnoreCase(status)
                                ? CodexLifecycleStateStore.State.INTERRUPTED : CodexLifecycleStateStore.State.COMPLETED;
                        transition(current,
                                next,
                                attachmentGeneration,
                                connectionGeneration,
                                process.process()
                                        .pid(),
                                process.executable(),
                                process.commandIdentity(),
                                current.threadId(),
                                current.turnId(),
                                null);
                        journal.offer("turn_completed",
                                Map.of("turnId", current.turnId() == null ? "" : current.turnId(),
                                        "status", status == null ? "" : status),
                                true);
                    }
                    case "thread/closed" -> {
                        CodexLifecycleStateStore.Checkpoint current = checkpoint();
                        transition(current, CodexLifecycleStateStore.State.STOPPED, attachmentGeneration,
                                connectionGeneration, -1L, process.executable(), process.commandIdentity(),
                                current.threadId(), current.turnId(), null);
                    }
                    case "thread/status/changed" -> {
                        String status = value(params, "status", "threadStatus");
                        String eventThread = value(params, "threadId", "thread_id");
                        CodexLifecycleStateStore.Checkpoint current = checkpoint();
                        if (eventThread != null && current.threadId() != null
                                && !current.threadId()
                                .equals(eventThread)) {
                            throw new IOException("status event thread identity mismatch");
                        }
                        if ("interaction_required".equalsIgnoreCase(status)
                                || "interactionRequired".equalsIgnoreCase(status)) {
                            transition(current,
                                    CodexLifecycleStateStore.State.INTERACTION_REQUIRED,
                                    attachmentGeneration,
                                    connectionGeneration,
                                    process.process()
                                            .pid(),
                                    process.executable(),
                                    process.commandIdentity(),
                                    current.threadId(),
                                    current.turnId(),
                                    "interaction_required");
                        } else if ("idle".equalsIgnoreCase(status)) {
                            transition(current,
                                    CodexLifecycleStateStore.State.IDLE,
                                    attachmentGeneration,
                                    connectionGeneration,
                                    process.process()
                                            .pid(),
                                    process.executable(),
                                    process.commandIdentity(),
                                    current.threadId(),
                                    current.turnId(),
                                    null);
                        } else if ("active".equalsIgnoreCase(status)) {
                            transition(current,
                                    CodexLifecycleStateStore.State.RUNNING,
                                    attachmentGeneration,
                                    connectionGeneration,
                                    process.process()
                                            .pid(),
                                    process.executable(),
                                    process.commandIdentity(),
                                    current.threadId(),
                                    current.turnId(),
                                    null);
                        } else if ("systemError".equalsIgnoreCase(status)) {
                            transition(current,
                                    CodexLifecycleStateStore.State.FAILED,
                                    attachmentGeneration,
                                    connectionGeneration,
                                    process.process()
                                            .pid(),
                                    process.executable(),
                                    process.commandIdentity(),
                                    current.threadId(),
                                    current.turnId(),
                                    "system_error");
                        }
                        journal.offer("thread_status_changed", params, false);
                    }
                    case "auth/required", "interaction_required" -> {
                        CodexLifecycleStateStore.Checkpoint current = checkpoint();
                        String eventThread = value(params, "threadId", "thread_id");
                        if (eventThread != null && current.threadId() != null
                                && !current.threadId()
                                .equals(eventThread)) {
                            throw new IOException("interaction event thread identity mismatch");
                        }
                        transition(current,
                                CodexLifecycleStateStore.State.INTERACTION_REQUIRED,
                                attachmentGeneration,
                                connectionGeneration,
                                process.process()
                                        .pid(),
                                process.executable(),
                                process.commandIdentity(),
                                current.threadId(),
                                current.turnId(),
                                method);
                        journal.offer("interaction_required", params, true);
                    }
                    // This notification describes a Codex-managed child process, not the App Server root.
                    // Root exit is authoritative only from the owned ProcessHandle monitor.
                    case "process/exited" -> journal.offer("process_child_exit", params, true);
                    default -> {
                        // Visible messages and deltas are retained by the protocol event evidence path.
                    }
                }
            } catch (Exception failure) {
                onFailure(failure);
            }
        }

        @Override
        public void onFailure(Throwable failure) {
            try {
                CodexLifecycleStateStore.Checkpoint current = checkpoint();
                transition(current,
                        CodexLifecycleStateStore.State.FAILED,
                        attachmentGeneration,
                        connectionGeneration,
                        process.process()
                                .isAlive() ? process.process()
                                             .pid() : -1L,
                        process.executable(),
                        process.commandIdentity(),
                        current.threadId(),
                        current.turnId(),
                        diagnostic(failure));
                journal.offer("protocol_failure", Map.of("diagnostic", diagnostic(failure)), true);
                scheduler.execute(() -> terminateFailedAttachment(current));
            } catch (IOException ignored) {
                // The protocol failure is already surfaced through the journal.
            }
        }

        private String value(Map<String, Object> params, String first, String second) {
            Object value = params.get(first);
            if (value == null) {
                value = params.get(second);
            }
            if (value == null && params.get("thread") instanceof Map<?, ?> thread) {
                value = thread.get(first);
                if (value == null) {
                    value = thread.get(second);
                }
                if (value == null && first.endsWith("Id")) {
                    value = thread.get("id");
                }
            }
            if (value == null && params.get("turn") instanceof Map<?, ?> turn) {
                value = turn.get(first);
                if (value == null) {
                    value = turn.get(second);
                }
                if (value == null && first.endsWith("Id")) {
                    value = turn.get("id");
                }
            }
            return statusText(value);
        }
    }
}
