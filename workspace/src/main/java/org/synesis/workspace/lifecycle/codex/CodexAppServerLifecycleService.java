package org.synesis.workspace.lifecycle.codex;

import org.synesis.workspace.lifecycle.codex.state.CodexLifecycleStateStore;

import org.synesis.workspace.lifecycle.codex.state.CodexEvidenceJournal;

import org.synesis.workspace.lifecycle.codex.protocol.LifecycleControlRequestEnvelope;

import org.synesis.workspace.lifecycle.codex.http.CodexLifecycleHttpClient;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.synesis.workspace.lifecycle.codex.protocol.CodexAppServerProtocolClient;
import org.synesis.workspace.lifecycle.codex.protocol.CodexAppServerProtocolSchema;
import org.synesis.workspace.lifecycle.codex.protocol.LifecycleControlRequestEnvelope.AuthorityContext;

import static org.synesis.workspace.lifecycle.codex.CodexAppServerPayloadReader.diagnostic;
import static org.synesis.workspace.lifecycle.codex.CodexAppServerPayloadReader.optionalResponseId;
import static org.synesis.workspace.lifecycle.codex.CodexAppServerPayloadReader.protocolParams;
import static org.synesis.workspace.lifecycle.codex.CodexAppServerPayloadReader.reconcileReadState;
import static org.synesis.workspace.lifecycle.codex.CodexAppServerPayloadReader.requireSuccessful;
import static org.synesis.workspace.lifecycle.codex.CodexAppServerPayloadReader.responseId;
import static org.synesis.workspace.lifecycle.codex.CodexAppServerProcessStreams.closeStreams;
import static org.synesis.workspace.lifecycle.codex.CodexAppServerProcessStreams.drainStderr;
import static org.synesis.workspace.lifecycle.codex.CodexAppServerProcessTermination.ownedTermination;
import static org.synesis.workspace.lifecycle.codex.CodexAppServerProcessTermination.terminateProcessTree;

/**
 * Codex-only App Server lifecycle state machine.
 *
 * <p>The service owns one App Server process and protocol connection for one
 * exact provider binding. It does not create Synesis bindings, participants, WorkIntents, claims,
 * lanes, epochs, or worktrees; those identities must be verified before the host calls it. It is
 * thread-safe. Protocol reading, lifecycle transitions, waiter completion, and evidence persistence
 * remain separate so a slow evidence writer cannot block control.
 *
 * @since 1.0
 */
@SuppressWarnings({"resource", "DuplicatedCode"})
public final class CodexAppServerLifecycleService implements AutoCloseable {

    private static final int MAX_WAITERS = 4;

    private final LifecycleControlRequestEnvelope.AuthorityContext authority;
    private final CodexLifecycleStateStore stateStore;
    private final ProcessLauncher launcher;
    private final ProcessTreeTerminator terminator;
    private final Path evidenceDirectory;
    private final Object stateLock = new Object();
    private final Map<String, Waiter> waiters = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler;
    private volatile Attachment active;
    private volatile AppServerProcess startingProcess;
    private volatile boolean closed;

    /**
     * Creates one binding-scoped lifecycle service.
     *
     * @param authority         exact verified binding authority
     * @param stateStore        durable checkpoint store
     * @param launcher          direct App Server process launcher
     * @param terminator        ownership-verified process terminator
     * @param evidenceDirectory generation journal directory
     */
    public CodexAppServerLifecycleService(LifecycleControlRequestEnvelope.AuthorityContext authority,
            CodexLifecycleStateStore stateStore,
            ProcessLauncher launcher,
            ProcessTreeTerminator terminator,
            Path evidenceDirectory) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.terminator = Objects.requireNonNull(terminator, "terminator");
        this.evidenceDirectory = Objects.requireNonNull(evidenceDirectory, "evidenceDirectory").toAbsolutePath().normalize();
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
        return (authority, _) -> CodexAppServerDirectLaunch.launch(authority);
    }

    private static void requireActiveTurn(CodexLifecycleStateStore.Checkpoint checkpoint) throws IOException {
        if (checkpoint.state() != CodexLifecycleStateStore.State.RUNNING && checkpoint.state() != CodexLifecycleStateStore.State.INTERRUPTING) {
            throw new IOException("lifecycle_turn_not_active");
        }
    }

    private static boolean waitObservable(CodexLifecycleStateStore.State state) {
        return switch (state) {
            case IDLE, INTERRUPTED, COMPLETED, FAILED, INTERACTION_REQUIRED, STOPPED, AMBIGUOUS -> true;
            default -> false;
        };
    }

    private static void requireOperation(LifecycleControlRequestEnvelope request, LifecycleControlRequestEnvelope.Operation operation) {
        Objects.requireNonNull(request, "request");
        if (request.operation() != operation) {
            throw new IllegalArgumentException("operation mismatch");
        }
    }

    private static CodexLifecycleHttpClient.Response alreadyStartedResponse(CodexLifecycleStateStore.State state, long revision) {
        return response(true, "already_started", state, revision, null, null);
    }

    private static CodexLifecycleHttpClient.Response response(boolean success, String diagnostic, CodexLifecycleStateStore.State state, long revision, String threadId, String turnId) {
        return response(success, diagnostic, state, revision, threadId, turnId, Map.of());
    }

    private static CodexLifecycleHttpClient.Response response(boolean success, String diagnostic, CodexLifecycleStateStore.State state, long revision, String threadId, String turnId, Map<String, Object> result) {
        return new CodexLifecycleHttpClient.Response(success, diagnostic, state.name(), revision, threadId, turnId, result);
    }

    /**
     * Starts a verified attachment, initializes it, creates an exact thread, and explicitly starts
     * the initial turn.
     *
     * @param request immutable START request
     * @return bounded lifecycle response
     * @throws Exception when App Server startup or protocol validation fails
     */
    public CodexLifecycleHttpClient.Response start(LifecycleControlRequestEnvelope request) throws Exception {
        requireOperation(request, LifecycleControlRequestEnvelope.Operation.START);
        synchronized (stateLock) {
            if (active != null && active.process().process().isAlive()) {
                return alreadyStartedResponse(checkpoint().state(), checkpoint().revision());
            }
        }
        CodexLifecycleStateStore.Checkpoint prior = checkpoint();
        long attachmentGeneration = prior.attachmentGeneration() + 1L;
        long connectionGeneration = prior.connectionGeneration() + 1L;
        transition(prior.advance(CodexLifecycleStateStore.State.STARTING, null).attachedTo(attachmentGeneration, connectionGeneration).withoutRoot().withThread(null, null));
        Startup startup = new Startup(attachmentGeneration, connectionGeneration);
        try {
            connect(startup, request, true);
            AppServerProcess process = startup.process;
            CodexAppServerProtocolClient protocol = startup.protocol;
            String expectedManagedThread = launcher.expectedInitialThreadId(authority, attachmentGeneration);
            String initialMethod = expectedManagedThread == null ? "thread/start" : "thread/resume";
            Map<String, Object> initialParams = expectedManagedThread == null ? Map.of("cwd", authority.realWorktree()) : Map.of("threadId", expectedManagedThread);
            CodexAppServerProtocolClient.Response threadResponse = requireSuccessful(protocol.request(initialMethod,
                    protocolParams(request, initialParams),
                    LifecycleControlRequestEnvelope.Classification.STATE_CHANGING,
                    request.digest(),
                    null,
                    null,
                    remaining(request)), initialMethod);
            String threadId = expectedManagedThread == null ? responseId(threadResponse, "threadId") : expectedManagedThread;
            String returnedThreadId = optionalResponseId(threadResponse);
            if (returnedThreadId != null && !threadId.equals(returnedThreadId)) {
                throw new IOException("thread response identity mismatch");
            }
            if (expectedManagedThread == null) {
                startup.threadStarted.get(Math.max(1L, remaining(request).toMillis()), TimeUnit.MILLISECONDS);
                if (!startup.threadStarted.getNow("").equals(threadId)) {
                    throw new IOException("thread/started identity mismatch");
                }
            } else {
                CodexAppServerProtocolClient.Response read = requireSuccessful(protocol.request("thread/read",
                        protocolParams(request, Map.of("threadId", expectedManagedThread)),
                        LifecycleControlRequestEnvelope.Classification.READ_ONLY,
                        request.digest(),
                        expectedManagedThread,
                        null,
                        remaining(request)), "thread/read");
                String readThreadId = optionalResponseId(read);
                if (readThreadId != null && !expectedManagedThread.equals(readThreadId)) {
                    throw new IOException("thread/read identity mismatch");
                }
            }
            launcher.verifyReturnedThread(authority, attachmentGeneration, threadId);
            launcher.finalizeManagedThread(authority, attachmentGeneration, threadId);
            activate(startup);
            transition(onProcess(checkpoint().advance(CodexLifecycleStateStore.State.IDLE, null).attachedTo(attachmentGeneration, connectionGeneration), process, process.process().pid()).withThread(threadId, null));
            CodexAppServerProtocolClient.Response turnResponse = requireSuccessful(protocol.request("turn/start",
                    protocolParams(request, Map.of("threadId", threadId, "input", CodexAppServerProtocolSchema.textInput(request.input()))),
                    LifecycleControlRequestEnvelope.Classification.STATE_CHANGING,
                    request.digest(),
                    threadId,
                    null,
                    remaining(request)), "turn/start");
            String turnId = responseId(turnResponse, "turnId");
            startup.turnStarted.get(Math.max(1L, remaining(request).toMillis()), TimeUnit.MILLISECONDS);
            if (!startup.turnStarted.getNow("").equals(turnId)) {
                throw new IOException("turn/started identity mismatch");
            }
            startingProcess = null;
            CodexLifecycleStateStore.Checkpoint latest = checkpoint();
            if (latest.state() != CodexLifecycleStateStore.State.COMPLETED && latest.state() != CodexLifecycleStateStore.State.INTERRUPTED) {
                transition(onProcess(latest.advance(CodexLifecycleStateStore.State.RUNNING, null).attachedTo(attachmentGeneration, connectionGeneration), process, process.process().pid()).withThread(threadId, turnId));
                latest = checkpoint();
            }
            return response(true, "started", latest.state(), latest.revision(), threadId, turnId);
        } catch (Exception failure) {
            abandon(startup, failure, CodexLifecycleStateStore.State.FAILED, null, false);
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
        requireSuccessful(attachment.protocol().request("turn/steer",
                Map.of("threadId", request.expectedThreadId(), "expectedTurnId", request.expectedTurnId(), "input", CodexAppServerProtocolSchema.textInput(request.input())),
                LifecycleControlRequestEnvelope.Classification.STATE_CHANGING,
                request.digest(),
                request.expectedThreadId(),
                request.expectedTurnId(),
                remaining(request)), "turn/steer");
        return response(true, "steered", checkpoint().state(), checkpoint().revision(), request.expectedThreadId(), request.expectedTurnId());
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
        String waiterId = request.requestId().toString();
        synchronized (stateLock) {
            if (waiters.size() >= MAX_WAITERS) {
                throw new IOException("lifecycle_wait_capacity_exceeded");
            }
            CodexLifecycleStateStore.Checkpoint current = checkpoint();
            if (request.expectedThreadId() != null && !request.expectedThreadId().equals(current.threadId())) {
                throw new IOException("lifecycle_thread_identity_mismatch");
            }
            if (waitObservable(current.state()) && (request.expectedTurnId() == null || request.expectedTurnId().equals(current.turnId()))) {
                future.complete(response(true, "state_changed", current.state(), current.revision(), current.threadId(), current.turnId()));
            } else {
                waiters.put(waiterId, new Waiter(request.expectedThreadId(), request.expectedTurnId(), future));
            }
        }
        try {
            scheduler.schedule(() -> future.complete(response(true, "wait_deadline", checkpoint().state(), checkpoint().revision(), checkpoint().threadId(), checkpoint().turnId())), remaining, TimeUnit.MILLISECONDS);
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
        transition(current.advance(CodexLifecycleStateStore.State.INTERRUPTING, null));
        requireSuccessful(attachment.protocol().request("turn/interrupt",
                Map.of("threadId", request.expectedThreadId(), "turnId", request.expectedTurnId()),
                LifecycleControlRequestEnvelope.Classification.STATE_CHANGING,
                request.digest(),
                request.expectedThreadId(),
                request.expectedTurnId(),
                remaining(request)), "turn/interrupt");
        return waitForTerminal(request);
    }

    /**
     * Hard-stops the verified App Server process tree.
     *
     * @param request immutable HARD_STOP request
     * @return structured termination response
     * @throws IOException if ownership, termination evidence, or lifecycle state cannot be verified
     */
    public CodexLifecycleHttpClient.Response hardStop(LifecycleControlRequestEnvelope request) throws IOException {
        requireOperation(request, LifecycleControlRequestEnvelope.Operation.HARD_STOP);
        synchronized (stateLock) {
            CodexLifecycleStateStore.Checkpoint current = checkpoint();
            if (current.rootPid() <= 0) {
                transition(current.advance(CodexLifecycleStateStore.State.STOPPED, null).withoutRoot());
                return response(true, "root_already_exited", CodexLifecycleStateStore.State.STOPPED, checkpoint().revision(), current.threadId(), current.turnId());
            }
            Attachment attachment = active;
            if (attachment != null) {
                closeAttachmentStreams(attachment);
            }
            ProcessTreeTerminator.Result result = terminateProcessTree(launcher, authority, terminator, attachment == null ? startingProcess : attachment.process(),
                    current.attachmentGeneration(),
                    Duration.ofSeconds(2),
                    Instant.ofEpochMilli(request.callerDeadlineEpochMillis()),
                    current);
            if (attachment != null) {
                attachment.journal().offer("hard_stop_result", Map.of("outcome", result.outcome().name(), "diagnostic", result.diagnostic(), "survivors", result.survivors()), true);
                // A journal that failed to close is not recorded in the checkpoint on this path.
                if (closeJournal(attachment.journal())) {
                    persistEvidenceCompleteness(current, attachment.journal());
                }
                if (active == attachment) {
                    active = null;
                }
            }
            CodexLifecycleStateStore.State state =
                    result.outcome() == ProcessTreeTerminator.Outcome.CLEAN_GRACEFUL || result.outcome() == ProcessTreeTerminator.Outcome.FORCED || result.outcome() == ProcessTreeTerminator.Outcome.ROOT_ALREADY_EXITED
                            ? CodexLifecycleStateStore.State.STOPPED : CodexLifecycleStateStore.State.FAILED;
            current = checkpoint();
            transition(current.advance(state, result.diagnostic()).withRootPid(result.outcome() == ProcessTreeTerminator.Outcome.ROOT_SURVIVED ? current.rootPid() : -1L));
            return response(state == CodexLifecycleStateStore.State.STOPPED, result.diagnostic(), state, checkpoint().revision(), current.threadId(), current.turnId());
        }
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
        if (active == null || !active.process().process().isAlive()) {
            resumeAttachment(request, current);
            current = checkpoint();
        }
        if (request.continuation()) {
            return startTurn(request);
        }
        requireExact(request, active);
        return response(true, "thread_resumed_without_turn", current.state(), current.revision(), current.threadId(), current.turnId());
    }

    private void resumeAttachment(LifecycleControlRequestEnvelope request, CodexLifecycleStateStore.Checkpoint prior) throws Exception {
        if (prior.threadId() == null || request.expectedThreadId() == null || !prior.threadId().equals(request.expectedThreadId())) {
            throw new IOException("lifecycle_thread_identity_mismatch");
        }
        long attachmentGeneration = prior.attachmentGeneration() + 1L;
        long connectionGeneration = prior.connectionGeneration() + 1L;
        transition(prior.advance(CodexLifecycleStateStore.State.STARTING, "explicit_resume").attachedTo(attachmentGeneration, connectionGeneration).withoutRoot());
        Startup startup = new Startup(attachmentGeneration, connectionGeneration);
        try {
            connect(startup, request, false);
            AppServerProcess process = startup.process;
            CodexAppServerProtocolClient protocol = startup.protocol;
            CodexAppServerProtocolClient.Response resumeResponse = requireSuccessful(protocol.request("thread/resume",
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
            if (resumed != null && !prior.threadId().equals(resumed)) {
                throw new IOException("thread/resume identity mismatch");
            }
            CodexAppServerProtocolClient.Response read = requireSuccessful(protocol.request("thread/read",
                    Map.of("threadId", prior.threadId()),
                    LifecycleControlRequestEnvelope.Classification.READ_ONLY,
                    request.digest(),
                    prior.threadId(),
                    prior.turnId(),
                    remaining(request)), "thread/read");
            String readThread = optionalResponseId(read);
            if (readThread != null && !prior.threadId().equals(readThread)) {
                throw new IOException("thread/read identity mismatch");
            }
            launcher.verifyReturnedThread(authority, attachmentGeneration, prior.threadId());
            activate(startup);
            startingProcess = null;
            CodexLifecycleStateStore.State reconciled = reconcileReadState(prior, read);
            transition(onProcess(checkpoint().advance(reconciled, "explicit_thread_resume").attachedTo(attachmentGeneration, connectionGeneration), process, process.process().pid())
                    .withThread(prior.threadId(), prior.turnId()));
        } catch (Exception failure) {
            // A failed resume keeps the thread and turn the caller asked to resume, and releases the attachment.
            abandon(startup, failure, CodexLifecycleStateStore.State.AMBIGUOUS, prior, true);
            throw failure;
        }
    }

    /**
     * Launches the App Server for {@code startup} and completes the {@code initialize} handshake.
     * Only an initial start refuses to continue once the owner is closed.
     */
    private void connect(Startup startup, LifecycleControlRequestEnvelope request, boolean refuseWhenClosed) throws Exception {
        AppServerProcess process = launcher.launch(authority, startup.attachmentGeneration);
        startup.process = process;
        startingProcess = process;
        if (refuseWhenClosed && closed) {
            throw new IOException("owner_shutdown");
        }
        CodexEvidenceJournal journal = new CodexEvidenceJournal(evidenceDirectory.resolve("generation-" + startup.connectionGeneration + ".jsonl"));
        startup.journal = journal;
        monitorProcessExit(process, journal, startup.attachmentGeneration, startup.connectionGeneration);
        CodexAppServerProtocolClient protocol = new CodexAppServerProtocolClient(startup.connectionGeneration,
                process.stdout(),
                process.stdin(),
                new CodexAppServerEventListener(this, startup.threadStarted, startup.turnStarted, journal, process, startup.attachmentGeneration, startup.connectionGeneration),
                journal);
        startup.protocol = protocol;
        drainStderr(process.stderr(), journal);
        requireSuccessful(protocol.request("initialize", CodexAppServerProtocolSchema.initializeParams(), LifecycleControlRequestEnvelope.Classification.STATE_CHANGING, request.digest(), null, null, remaining(request)),
                "initialize");
        protocol.notify("initialized", Map.of());
    }

    private void activate(Startup startup) throws IOException {
        launcher.activateManagedAttachment(authority, startup.attachmentGeneration);
        synchronized (stateLock) {
            active = new Attachment(startup.process, startup.protocol, startup.journal, startup.attachmentGeneration, startup.connectionGeneration);
        }
    }

    /**
     * Tears down a startup that did not reach a proven thread and records {@code failedState}.
     *
     * @param pinned        checkpoint whose thread and turn the failed state keeps, or {@code null} to keep the latest
     * @param releaseActive whether to also drop the active attachment
     */
    private void abandon(Startup startup, Exception failure, CodexLifecycleStateStore.State failedState, CodexLifecycleStateStore.Checkpoint pinned, boolean releaseActive) throws IOException {
        if (startup.protocol != null) {
            startup.protocol.close();
        }
        if (startup.journal != null) {
            closeJournalAndRecord(startup.journal);
        }
        terminateStartupProcess(startup.process, startup.attachmentGeneration);
        if (releaseActive) {
            active = null;
        }
        startingProcess = null;
        CodexLifecycleStateStore.Checkpoint latest = checkpointUnchecked();
        CodexLifecycleStateStore.Checkpoint kept = pinned == null ? latest : pinned;
        CodexLifecycleStateStore.Checkpoint failed = latest.advance(failedState, diagnostic(failure))
                .attachedTo(startup.attachmentGeneration, startup.connectionGeneration)
                .withThread(kept.threadId(), kept.turnId());
        transition(startup.process == null ? failed.withoutRoot() : onProcess(failed, startup.process, startup.process.process().pid()));
    }

    /**
     * Returns the authoritative checkpoint without mutation.
     *
     * @param request immutable STATUS request
     * @return bounded status response
     */
    public CodexLifecycleHttpClient.Response status(LifecycleControlRequestEnvelope request) {
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
        result.put("tombstones", attachment == null ? 0 : attachment.protocol().tombstones().size());
        result.put("protocolFailed", attachment != null && attachment.protocol().failed());
        result.put("evidenceComplete", attachment == null || attachment.journal().evidenceComplete());
        result.put("evidenceEventsDropped", attachment == null ? 0L : attachment.journal().eventsDropped());
        result.put("evidencePersistedBytes", attachment == null ? 0L : attachment.journal().persistedBytes());
        result.put("evidenceOverflow", attachment != null && attachment.journal().overflowMarker());
        if (attachment != null) {
            result.putAll(attachment.protocol().diagnostics());
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
        return attachment != null && attachment.process().process().isAlive();
    }

    /**
     * Wakes all WAIT callers and closes the owned attachment.
     */
    @Override
    public void close() {
        closed = true;
        synchronized (stateLock) {
            waiters.values().forEach(waiter -> waiter.future()
                    .complete(response(false, "owner_shutdown", CodexLifecycleStateStore.State.STOPPED, checkpointUnchecked().revision(), checkpointUnchecked().threadId(), checkpointUnchecked().turnId())));
            waiters.clear();
        }
        try {
            shutdownAttachment();
        } catch (IOException ignored) {
            // The authoritative owner shutdown path remains bounded.
        }
        scheduler.shutdownNow();
    }

    private void shutdownAttachment() throws IOException {
        Attachment attachment = active;
        AppServerProcess launching = startingProcess;
        if (attachment == null && launching != null) {
            CodexLifecycleStateStore.Checkpoint snapshot = checkpointUnchecked();
            ProcessTreeTerminator.Result result = terminateProcessTree(launcher, authority, terminator, launching, snapshot.attachmentGeneration(), Duration.ofMillis(500), Instant.now().plusSeconds(2), snapshot);
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
        if (current.threadId() != null && current.turnId() != null && (current.state() == CodexLifecycleStateStore.State.RUNNING || current.state() == CodexLifecycleStateStore.State.INTERRUPTING)) {
            try {
                transition(current.advance(CodexLifecycleStateStore.State.INTERRUPTING, "owner_shutdown"));
                attachment.protocol().request("turn/interrupt",
                        Map.of("threadId", current.threadId(), "turnId", current.turnId()),
                        LifecycleControlRequestEnvelope.Classification.STATE_CHANGING,
                        "owner-shutdown",
                        current.threadId(),
                        current.turnId(),
                        Duration.ofMillis(500));
                long interruptionDeadline = System.currentTimeMillis() + 500L;
                while (System.currentTimeMillis() < interruptionDeadline && checkpointUnchecked().state() == CodexLifecycleStateStore.State.INTERRUPTING) {
                    java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(25L));
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }
            } catch (Exception ignored) {
                // Hard-stop escalation below is the bounded fallback.
            }
        }
        ProcessTreeTerminator.Result termination = null;
        if (attachment.process().process().isAlive() && current.rootPid() > 0) {
            ProcessTreeTerminator.Result result = terminateProcessTree(launcher, authority, terminator, attachment.process(), current.attachmentGeneration(), Duration.ofMillis(500), Instant.now().plusSeconds(2), current);
            termination = result;
            if (ownedTermination(result.outcome())) {
                attachment.process().process().destroyForcibly();
            }
        }
        attachment.protocol().close();
        // A journal that failed to close is not recorded in the checkpoint on this path.
        if (closeJournal(attachment.journal())) {
            persistEvidenceCompleteness(current, attachment.journal());
        }
        active = null;
        current = checkpointUnchecked();
        try {
            CodexLifecycleStateStore.State shutdownState = termination == null || ownedTermination(termination.outcome()) ? CodexLifecycleStateStore.State.STOPPED : CodexLifecycleStateStore.State.FAILED;
            transition(current.advance(shutdownState, termination == null ? "owner_shutdown" : termination.diagnostic()).withRootPid(-1L));
        } catch (IOException ignored) {
            // Preserve the last durable checkpoint when shutdown is already failing.
        }
    }

    private void terminateStartupProcess(AppServerProcess process, long attachmentGeneration) {
        if (process == null || !process.process().isAlive()) {
            return;
        }
        try {
            ProcessTreeTerminator.Result result = terminateProcessTree(launcher, authority, terminator, process, attachmentGeneration, Duration.ofMillis(250), Instant.now().plusSeconds(2), checkpointUnchecked());
            if (ownedTermination(result.outcome())) {
                process.process().destroyForcibly();
            }
        } catch (RuntimeException ignored) {
            // An unproven startup process is never targeted by fallback PID.
        } catch (IOException ignored) {
            // An unproven managed tree remains fail-closed.
        }
    }

    private CodexLifecycleHttpClient.Response startTurn(LifecycleControlRequestEnvelope request) throws Exception {
        Attachment attachment = requireActive();
        CodexLifecycleStateStore.Checkpoint current = checkpoint();
        if (current.state() == CodexLifecycleStateStore.State.RUNNING || current.state() == CodexLifecycleStateStore.State.INTERRUPTING || current.state() == CodexLifecycleStateStore.State.STARTING) {
            throw new IOException("lifecycle_turn_already_active");
        }
        String threadId = request.expectedThreadId() == null ? current.threadId() : request.expectedThreadId();
        if (threadId == null || !threadId.equals(current.threadId())) {
            throw new IOException("lifecycle_thread_identity_mismatch");
        }
        CodexAppServerProtocolClient.Response response = requireSuccessful(attachment.protocol().request("turn/start",
                protocolParams(request, Map.of("threadId", threadId, "input", CodexAppServerProtocolSchema.textInput(request.input()))),
                LifecycleControlRequestEnvelope.Classification.STATE_CHANGING,
                request.digest(),
                threadId,
                request.expectedTurnId(),
                remaining(request)), "turn/start");
        String turnId = responseId(response, "turnId");
        waitForTurnStarted(threadId, turnId, request);
        CodexLifecycleStateStore.Checkpoint latest = checkpoint();
        return response(true, "continued", latest.state(), latest.revision(), threadId, turnId);
    }

    private void waitForTurnStarted(String threadId, String turnId, LifecycleControlRequestEnvelope request) throws Exception {
        long deadline = request.callerDeadlineEpochMillis();
        while (System.currentTimeMillis() < deadline) {
            CodexLifecycleStateStore.Checkpoint current = checkpoint();
            if (threadId.equals(current.threadId()) && turnId.equals(current.turnId()) && (current.state() == CodexLifecycleStateStore.State.RUNNING || current.state() == CodexLifecycleStateStore.State.COMPLETED
                    || current.state() == CodexLifecycleStateStore.State.INTERRUPTED)) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.clamp(deadline - System.currentTimeMillis(), 1L, 25L)));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
        throw new TimeoutException("turn/started event deadline expired");
    }

    private CodexLifecycleHttpClient.Response waitForTerminal(LifecycleControlRequestEnvelope request) throws Exception {
        long remaining = remaining(request).toMillis();
        while (remaining > 0) {
            CodexLifecycleStateStore.Checkpoint current = checkpoint();
            if (current.state() == CodexLifecycleStateStore.State.INTERRUPTED) {
                return response(true,
                        "turn_interrupted",
                        CodexLifecycleStateStore.State.INTERRUPTED,
                        current.revision(),
                        current.threadId(),
                        current.turnId(),
                        Map.of("interruptionClassification", "turn_interrupted_command_state_unconfirmed"));
            }
            Thread.sleep(Math.min(25L, remaining));
            remaining = remaining(request).toMillis();
        }
        throw new TimeoutException("turn terminal event deadline expired");
    }

    private Attachment requireActive() throws IOException {
        Attachment attachment = active;
        if (attachment == null || !attachment.process().process().isAlive()) {
            throw new IOException("lifecycle_attachment_missing");
        }
        return attachment;
    }

    private void requireExact(LifecycleControlRequestEnvelope request, Attachment attachment) throws IOException {
        CodexLifecycleStateStore.Checkpoint current = checkpoint();
        if (request.expectedThreadId() == null || !request.expectedThreadId().equals(current.threadId()) || (request.expectedTurnId() != null && !request.expectedTurnId().equals(current.turnId()))) {
            throw new IOException("lifecycle_thread_or_turn_identity_mismatch");
        }
        if (attachment.connectionGeneration() != current.connectionGeneration()) {
            throw new IOException("connection_generation_mismatch");
        }
    }

    CodexLifecycleStateStore.Checkpoint checkpoint() throws IOException {
        return stateStore.read(authority.bindingSessionId(), authority.projectId());
    }

    private CodexLifecycleStateStore.Checkpoint checkpointUnchecked() {
        return stateStore.readUnchecked(authority.bindingSessionId(), authority.projectId());
    }

    private void monitorProcessExit(AppServerProcess process, CodexEvidenceJournal journal, long attachmentGeneration, long connectionGeneration) {
        // Deterministic Process test doubles often implement waitFor() as an
        // immediate state transition and do not have an operating-system
        // ProcessHandle.  Their protocol fixtures report process/exited
        // explicitly; only observe a real OS-owned process here.
        if (ProcessHandle.of(process.process().pid()).isEmpty()) {
            return;
        }
        Thread monitor = new Thread(() -> {
            try {
                process.process().waitFor();
                if (closed) {
                    return;
                }
                CodexLifecycleStateStore.Checkpoint current = checkpoint();
                if (current.attachmentGeneration() != attachmentGeneration || current.connectionGeneration() != connectionGeneration || current.state() == CodexLifecycleStateStore.State.STOPPED) {
                    return;
                }
                CodexLifecycleStateStore.State next = switch (current.state()) {
                    case COMPLETED, INTERRUPTED, FAILED, INTERACTION_REQUIRED -> current.state();
                    default -> CodexLifecycleStateStore.State.FAILED;
                };
                transition(onProcess(current.advance(next, "process_exit").attachedTo(attachmentGeneration, connectionGeneration), process, -1L));
                journal.offer("process_exit", Map.of("pid", process.process().pid()), true);
                scheduler.execute(() -> closeExitedAttachment(attachmentGeneration, journal));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The owner retains the last durable checkpoint on a failed
                // process-observation read; protocol failure remains visible.
            }
        }, "synesis-codex-process-exit-" + attachmentGeneration);
        monitor.setDaemon(true);
        monitor.start();
    }

    private void closeExitedAttachment(long attachmentGeneration, CodexEvidenceJournal journal) {
        synchronized (stateLock) {
            Attachment attachment = active;
            if (attachment == null || attachment.attachmentGeneration() != attachmentGeneration) {
                return;
            }
            closeAttachmentStreams(attachment);
            if (attachment.process().supervisor() != null) {
                try {
                    ManagedProcessTreeSupervisor.DeathEvidence evidence = attachment.process().supervisor().teardownAndProveEmptyWithEvidence(attachment.process().process(),
                            attachmentGeneration,
                            attachment.process().executable(),
                            attachment.process().commandIdentity(),
                            attachment.process().startEpochMillis());
                    launcher.managedTreeStopped(authority, attachmentGeneration, evidence);
                    journal.offer("managed_tree_empty", Map.of("generation", attachmentGeneration), true);
                } catch (IOException failure) {
                    journal.offer("managed_tree_death_ambiguous", Map.of("generation", attachmentGeneration), true);
                    return;
                }
            }
            active = null;
            try {
                closeJournalAndRecord(journal);
            } catch (IOException ignored) {
                // The process-exit checkpoint remains authoritative.
            }
        }
    }

    private void closeAttachmentStreams(Attachment attachment) {
        attachment.protocol().close();
        Thread stderrCloser = new Thread(() -> {
            try {
                attachment.process().stderr().close();
            } catch (IOException ignored) {
                // Best effort; the managed supervisor retains final ownership.
            }
        }, "synesis-codex-stderr-closer-" + attachment.connectionGeneration());
        stderrCloser.setDaemon(true);
        stderrCloser.start();
        try {
            stderrCloser.join(500L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static CodexLifecycleStateStore.Checkpoint onProcess(CodexLifecycleStateStore.Checkpoint checkpoint, AppServerProcess process, long rootPid) {
        return checkpoint.withRoot(rootPid, process.executable(), process.commandIdentity());
    }

    /** Persists {@code next}, folding in what only the live attachment knows, and wakes matching waiters. */
    void transition(CodexLifecycleStateStore.Checkpoint next) throws IOException {
        synchronized (stateLock) {
            Attachment attachment = active;
            if (attachment != null) {
                if (next.rootPid() > 0) {
                    next = next.withRootStart(attachment.process().startEpochMillis());
                }
                if (!attachment.journal().evidenceComplete()) {
                    next = next.withEvidenceIncomplete();
                }
            }
            stateStore.write(next);
            notifyWaiters(next);
        }
    }

    /**
     * Closes the journal. A journal that cannot close is marked incomplete.
     *
     * @return whether the journal closed cleanly
     */
    private static boolean closeJournal(CodexEvidenceJournal journal) {
        try {
            journal.close();
            return true;
        } catch (IOException failure) {
            journal.markIncomplete();
            return false;
        }
    }

    private void closeJournalAndRecord(CodexEvidenceJournal journal) throws IOException {
        closeJournal(journal);
        persistEvidenceCompleteness(checkpointUnchecked(), journal);
    }

    private void persistEvidenceCompleteness(CodexLifecycleStateStore.Checkpoint prior, CodexEvidenceJournal journal) throws IOException {
        if (journal == null || journal.evidenceComplete() || !prior.evidenceComplete()) {
            return;
        }
        stateStore.write(prior.advance(prior.state(), prior.terminalDiagnostic()).withEvidenceIncomplete());
    }

    private void notifyWaiters(CodexLifecycleStateStore.Checkpoint checkpoint) {
        if (checkpoint.state() == CodexLifecycleStateStore.State.STARTING || checkpoint.state() == CodexLifecycleStateStore.State.RUNNING || checkpoint.state() == CodexLifecycleStateStore.State.INTERRUPTING) {
            return;
        }
        synchronized (stateLock) {
            java.util.Iterator<Map.Entry<String, Waiter>> iterator = waiters.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Waiter> item = iterator.next();
                Waiter waiter = item.getValue();
                boolean threadMatches = waiter.expectedThreadId() == null || waiter.expectedThreadId().equals(checkpoint.threadId());
                boolean turnMatches = waiter.expectedTurnId() == null || waiter.expectedTurnId().equals(checkpoint.turnId());
                if (threadMatches && turnMatches) {
                    waiter.future().complete(response(true, "state_changed", checkpoint.state(), checkpoint.revision(), checkpoint.threadId(), checkpoint.turnId()));
                    iterator.remove();
                }
            }
        }
    }

    private Duration remaining(LifecycleControlRequestEnvelope request) throws TimeoutException {
        long remaining = request.callerDeadlineEpochMillis() - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new TimeoutException("lifecycle caller deadline expired");
        }
        return Duration.ofMillis(remaining);
    }

    void providerTurnCompleted(long attachmentGeneration, String threadId, String turnId) throws IOException {
        launcher.providerTurnCompleted(authority, attachmentGeneration, threadId, turnId);
    }

    void cleanUpFailedAttachment(CodexLifecycleStateStore.Checkpoint checkpoint) {
        scheduler.execute(() -> terminateFailedAttachment(checkpoint));
    }

    private void terminateFailedAttachment(CodexLifecycleStateStore.Checkpoint checkpoint) {
        if (checkpoint.rootPid() <= 0) {
            return;
        }
        try {
            Attachment current = active;
            ProcessTreeTerminator.Result result = terminateProcessTree(launcher, authority, terminator, current == null ? null : current.process(), checkpoint.attachmentGeneration(), Duration.ofMillis(250), Instant.now().plusSeconds(2), checkpoint);
            if (ownedTermination(result.outcome()) && active != null && active.attachmentGeneration() == checkpoint.attachmentGeneration()) {
                Attachment failed = active;
                failed.process().process().destroyForcibly();
                failed.protocol().close();
                closeJournalAndRecord(failed.journal());
                active = null;
            }
        } catch (RuntimeException ignored) {
            // An unproven process is never targeted during protocol failure cleanup.
        } catch (IOException ignored) {
            // The authoritative FAILED checkpoint remains; status/doctor
            // exposes any evidence or termination cleanup failure.
        }
    }

    /** Rejects managed-tree death evidence that does not prove the requested generation stopped. */
    static void requireManagedTreeDeathEvidence(long attachmentGeneration, ManagedProcessTreeSupervisor.DeathEvidence evidence) throws IOException {
        CodexAppServerProcessTermination.requireManagedTreeDeathEvidence(attachmentGeneration, evidence);
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
        AppServerProcess launch(LifecycleControlRequestEnvelope.AuthorityContext authority, long attachmentGeneration) throws IOException;

        /**
         * Returns the exact thread that a managed initial launch must resume. Ordinary launchers return
         * {@code null} and use {@code thread/start}.
         *
         * @param authority            verified authority context
         * @param attachmentGeneration local attachment generation
         * @return exact managed thread, or {@code null} for ordinary start
         * @throws IOException when managed pin state is unavailable
         */
        @SuppressWarnings("RedundantThrows") // Managed launchers can fail when their exact persisted thread pin is unavailable.
        default String expectedInitialThreadId(LifecycleControlRequestEnvelope.AuthorityContext authority, long attachmentGeneration) throws IOException {
            return null;
        }

        /**
         * Verifies the provider result before the lifecycle joins managed authority to this
         * generation.
         *
         * @param authority            verified authority context
         * @param attachmentGeneration local attachment generation
         * @param returnedThreadId     provider-returned thread selector
         * @throws IOException when the returned selector is not trusted
         */
        @SuppressWarnings("EmptyMethod") // Ordinary launchers have no managed provider pin to verify.
        default void verifyReturnedThread(LifecycleControlRequestEnvelope.AuthorityContext authority, long attachmentGeneration, String returnedThreadId) throws IOException {
            // Ordinary launchers have no managed provider pin to verify.
        }

        /**
         * Finalizes the exact managed provider-thread binding after the provider returned it and before
         * authority activation.
         *
         * @param authority            verified authority context
         * @param attachmentGeneration local attachment generation
         * @param returnedThreadId     exact provider-returned thread selector
         * @throws IOException when the binding cannot be finalized
         */
        @SuppressWarnings("EmptyMethod") // Ordinary launchers have no managed provider-thread record.
        default void finalizeManagedThread(LifecycleControlRequestEnvelope.AuthorityContext authority, long attachmentGeneration, String returnedThreadId) throws IOException {
            // Ordinary launchers have no managed provider-thread record.
        }

        /**
         * Records the trusted provider completion boundary for the exact managed provider thread.
         *
         * @param authority            verified authority context
         * @param attachmentGeneration local attachment generation
         * @param threadId             exact completed-turn thread
         * @param turnId               exact completed turn, when supplied by the provider
         * @throws IOException when the completion is outside the active scope
         */
        @SuppressWarnings("EmptyMethod") // Ordinary launchers have no managed persistence boundary.
        default void providerTurnCompleted(LifecycleControlRequestEnvelope.AuthorityContext authority, long attachmentGeneration, String threadId, String turnId) throws IOException {
            // Ordinary launchers have no managed persistence boundary.
        }

        /**
         * Activates a pending managed proof only after exact thread verification.
         *
         * @param authority            verified authority context
         * @param attachmentGeneration local attachment generation
         * @throws IOException when managed activation cannot be committed
         */
        @SuppressWarnings("EmptyMethod") // Ordinary launchers have no managed activation gate.
        default void activateManagedAttachment(LifecycleControlRequestEnvelope.AuthorityContext authority, long attachmentGeneration) throws IOException {
            // Ordinary launchers have no managed activation gate.
        }

        /**
         * Records definitive managed-tree death at the trusted lifecycle boundary.
         *
         * @param authority            verified authority context
         * @param attachmentGeneration stopped managed generation
         */
        @SuppressWarnings({"EmptyMethod", "unused"})
        default void managedTreeStopped(AuthorityContext authority, long attachmentGeneration) {
            // Ordinary launchers have no managed runtime record.
        }

        /**
         * Records definitive managed-tree death with supervisor evidence.
         *
         * @param authority            verified authority context
         * @param attachmentGeneration stopped managed generation
         * @param evidence             trusted supervisor-produced death evidence
         * @throws IOException when managed state cannot be updated
         */
        default void managedTreeStopped(LifecycleControlRequestEnvelope.AuthorityContext authority, long attachmentGeneration, ManagedProcessTreeSupervisor.DeathEvidence evidence) throws IOException {
            requireManagedTreeDeathEvidence(attachmentGeneration, evidence);
            managedTreeStopped(authority, attachmentGeneration);
        }
    }

    /**
     * Process and stream ownership retained by the lifecycle service.
     *
     * @param process          launched process
     * @param executable       verified executable identity
     * @param commandIdentity  verified command identity
     * @param startEpochMillis verified process start instant
     * @param supervisor       optional managed process-tree supervisor
     */
    public record AppServerProcess(Process process, String executable, String commandIdentity, long startEpochMillis, ManagedProcessTreeSupervisor supervisor) implements AutoCloseable {

        /**
         * Creates an ordinary process attachment without a managed supervisor.
         *
         * @param process          launched process
         * @param executable       verified executable identity
         * @param commandIdentity  verified command identity
         * @param startEpochMillis verified process start instant
         */
        public AppServerProcess(Process process, String executable, String commandIdentity, long startEpochMillis) {
            this(process, executable, commandIdentity, startEpochMillis, null);
        }

        /**
         * Validates process ownership evidence.
         */
        public AppServerProcess {
            Objects.requireNonNull(process, "process");
            if (executable == null || executable.isBlank() || commandIdentity == null || commandIdentity.isBlank() || startEpochMillis <= 0) {
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
                process.getInputStream().close();
            } catch (IOException ignored) {
                // Best effort during lifecycle shutdown.
            }
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // Best effort during lifecycle shutdown.
            }
            process.destroy();
        }
    }

    /**
     * Couples one owned App Server process with its protocol client.
     */
    private record Attachment(AppServerProcess process, CodexAppServerProtocolClient protocol, CodexEvidenceJournal journal, long attachmentGeneration, long connectionGeneration) {

    }

    /**
     * One in-flight attachment startup. The fields fill in as the launch proceeds so a failure
     * can release exactly what was acquired.
     */
    private static final class Startup {

        private final long attachmentGeneration;
        private final long connectionGeneration;
        private final CompletableFuture<String> threadStarted = new CompletableFuture<>();
        private final CompletableFuture<String> turnStarted = new CompletableFuture<>();
        private AppServerProcess process;
        private CodexEvidenceJournal journal;
        private CodexAppServerProtocolClient protocol;

        private Startup(long attachmentGeneration, long connectionGeneration) {
            this.attachmentGeneration = attachmentGeneration;
            this.connectionGeneration = connectionGeneration;
        }
    }

    /**
     * Tracks one bounded caller wait for an exact Codex lifecycle turn.
     */
    private record Waiter(String expectedThreadId, String expectedTurnId, CompletableFuture<CodexLifecycleHttpClient.Response> future) {

    }
}
