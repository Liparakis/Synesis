package org.synesis.workspace.lifecycle.codex;

import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Long-lived Codex lifecycle owner retained by {@code coordination serve}.
 *
 * <p>One host instance owns the project lock, signed Codex loopback route,
 * lifecycle idempotency ledger, attachment locks, state stores, and in-memory
 * services for all exact Codex bindings. It is thread-safe. START authority is
 * read-only verified here; this class never calls binding/session/collaboration
 * creation. Owner shutdown wakes waiters and closes attachments. A host must be
 * closed to release its project lock.
 *
 * @since 1.0
 */
public final class ProjectRuntimeHost implements AutoCloseable {

    /** Codex-only loopback route mounted by the existing coordination listener. */
    public static final String CODEX_ROUTE = "/codex-lifecycle/v1";

    private final ProjectApplicationService.ProjectLocation location;
    private final NodeIdentity ownerIdentity;
    private final String hostInstanceId;
    private final Path runtimeRoot;
    private final Path ownerRecord;
    private final FileChannel hostLockChannel;
    private final FileLock hostLock;
    private final LifecycleIdempotencyLedger ledger;
    private final ProcessTreeTerminator terminator;
    private final CodexAppServerLifecycleService.ProcessLauncher launcher;
    private final Map<String, BindingRuntime> bindings = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<CodexLifecycleHttpClient.Response>> inFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Future<?>> executionTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Object> requestLocks = new ConcurrentHashMap<>();
    private final ExecutorService controlExecutor;
    private final ExecutorService waitExecutor;
    private final CodexLifecycleHttpAdapter adapter;
    private volatile boolean closed;

    /**
     * Creates and claims the production host for one project.
     *
     * @param location initialized project location
     * @param ownerIdentity local node identity used to authenticate loopback calls
     * @throws IOException when ownership or durable state cannot be established
     */
    public ProjectRuntimeHost(ProjectApplicationService.ProjectLocation location, NodeIdentity ownerIdentity)
            throws IOException {
        this(location, ownerIdentity, CodexAppServerLifecycleService.defaultLauncher(), new ProcessTreeTerminator());
    }

    /**
     * Creates an injectable host for deterministic tests.
     *
     * @param location initialized project location
     * @param ownerIdentity owner identity
     * @param launcher App Server process launcher
     * @param terminator process-tree terminator
     * @throws IOException when ownership or durable state cannot be established
     */
    public ProjectRuntimeHost(ProjectApplicationService.ProjectLocation location, NodeIdentity ownerIdentity,
            CodexAppServerLifecycleService.ProcessLauncher launcher, ProcessTreeTerminator terminator)
            throws IOException {
        this.location = Objects.requireNonNull(location, "location");
        this.ownerIdentity = Objects.requireNonNull(ownerIdentity, "ownerIdentity");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.terminator = Objects.requireNonNull(terminator, "terminator");
        this.runtimeRoot = location.synesisDirectory().resolve("local").resolve("runtime").resolve("codex-lifecycle")
                .toAbsolutePath().normalize();
        Files.createDirectories(runtimeRoot);
        this.ownerRecord = runtimeRoot.resolve("owner.json");
        Path lockPath = runtimeRoot.resolve("project-host.lock");
        this.hostLockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            this.hostLock = hostLockChannel.tryLock();
        } catch (OverlappingFileLockException failure) {
            hostLockChannel.close();
            throw new IOException("lifecycle_owner_already_running", failure);
        }
        if (hostLock == null) {
            hostLockChannel.close();
            throw new IOException("lifecycle_owner_already_running");
        }
        this.hostInstanceId = "host-" + UUID.randomUUID();
        writeOwner("LIVE");
        this.ledger = new LifecycleIdempotencyLedger(runtimeRoot.resolve("idempotency-ledger.json"));
        this.controlExecutor = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "synesis-codex-lifecycle-control");
            thread.setDaemon(true);
            return thread;
        });
        this.waitExecutor = Executors.newFixedThreadPool(32, runnable -> {
            Thread thread = new Thread(runnable, "synesis-codex-lifecycle-wait-http");
            thread.setDaemon(true);
            return thread;
        });
        this.adapter = new CodexLifecycleHttpAdapter(this);
        reconcileOnStartup();
    }

    /**
     * Returns the exact owner instance ID.
     *
     * @return owner instance ID
     */
    public String hostInstanceId() {
        return hostInstanceId;
    }

    /**
     * Returns the Codex-only HTTP handler retained by this host.
     *
     * @return lifecycle HTTP handler
     */
    public HttpHandler handler() {
        return adapter;
    }

    /**
     * Returns the route path registered on the existing coordination server.
     *
     * @return Codex lifecycle route
     */
    public String route() {
        return CODEX_ROUTE;
    }

    /**
     * Passively reconciles persisted attachment checkpoints after owner start.
     *
     * <p>No model work is started here. An exactly proven orphan attachment is
     * terminated through the ownership-verified process tree; a live process
     * whose ownership cannot be proven is left untouched and recorded as an
     * untrusted orphan. Exact-thread continuation remains an explicit RESUME
     * request.
     */
    public void reconcileOnStartup() {
        Path bindingsRoot = runtimeRoot.resolve("bindings");
        if (!Files.isDirectory(bindingsRoot)) {
            return;
        }
        try (var files = Files.list(bindingsRoot)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(this::reconcileCheckpoint);
        } catch (IOException ignored) {
            // Status remains authoritative through the owner record and ledger.
        }
    }

    private void reconcileCheckpoint(Path file) {
        try {
            String bindingSessionId = file.getFileName().toString().replaceFirst("\\.json$", "");
            CodexLifecycleStateStore store = new CodexLifecycleStateStore(runtimeRoot.resolve("bindings"));
            CodexLifecycleStateStore.Checkpoint prior = store.read(bindingSessionId, location.projectId().toString());
            if (prior.state() == CodexLifecycleStateStore.State.NEW
                    || prior.state() == CodexLifecycleStateStore.State.STOPPED
                    || prior.state() == CodexLifecycleStateStore.State.FAILED) {
                return;
            }
            if (prior.rootPid() <= 0) {
                store.write(new CodexLifecycleStateStore.Checkpoint(prior.bindingSessionId(), prior.projectId(),
                        prior.provider(), prior.revision() + 1L, CodexLifecycleStateStore.State.AMBIGUOUS,
                        hostInstanceId, prior.attachmentGeneration(), prior.connectionGeneration(), -1L,
                        prior.rootStartEpochMillis(), prior.rootExecutable(), prior.rootCommandIdentity(),
                        prior.threadId(), prior.turnId(), "owner_missing_after_restart", prior.evidenceComplete(),
                        System.currentTimeMillis()));
                return;
            }
            ProcessTreeTerminator.AttachmentIdentity identity = new ProcessTreeTerminator.AttachmentIdentity(
                    prior.rootPid(), prior.rootExecutable(), prior.rootCommandIdentity(),
                    prior.rootStartEpochMillis(), prior.attachmentGeneration());
            ProcessTreeTerminator.Result result = terminator.terminate(identity, prior.attachmentGeneration(),
                    Duration.ofMillis(500), Instant.now().plusSeconds(2));
            CodexLifecycleStateStore.State state = result.outcome() == ProcessTreeTerminator.Outcome.CLEAN_GRACEFUL
                    || result.outcome() == ProcessTreeTerminator.Outcome.FORCED
                    || result.outcome() == ProcessTreeTerminator.Outcome.ROOT_ALREADY_EXITED
                    ? CodexLifecycleStateStore.State.STOPPED : CodexLifecycleStateStore.State.AMBIGUOUS;
            store.write(new CodexLifecycleStateStore.Checkpoint(prior.bindingSessionId(), prior.projectId(),
                    prior.provider(), prior.revision() + 1L, state, hostInstanceId, prior.attachmentGeneration(),
                    prior.connectionGeneration(), state == CodexLifecycleStateStore.State.STOPPED ? -1L : prior.rootPid(),
                    prior.rootStartEpochMillis(), prior.rootExecutable(), prior.rootCommandIdentity(), prior.threadId(),
                    prior.turnId(), result.diagnostic(), prior.evidenceComplete(), System.currentTimeMillis()));
        } catch (Exception ignored) {
            // A malformed or untrusted record is surfaced by STATUS/doctor; never adopt by PID alone.
        }
    }

    /**
     * Handles one verified signed lifecycle envelope.
     *
     * @param signed signed request envelope
     * @return bounded lifecycle response
     * @throws Exception when validation, persistence, or lifecycle execution fails
     */
    public CodexLifecycleHttpClient.Response handle(LifecycleControlRequestEnvelope.SignedEnvelope signed)
            throws Exception {
        Objects.requireNonNull(signed, "signed");
        verifyEnvelope(signed);
        if (closed) {
            throw new IOException("owner_shutdown");
        }
        LifecycleControlRequestEnvelope request = signed.request();
        Object requestLock = requestLocks.computeIfAbsent(request.requestId(), ignored -> new Object());
        CompletableFuture<CodexLifecycleHttpClient.Response> execution;
        BindingRuntime runtime;
        ExecutorService executor;
        synchronized (requestLock) {
            LifecycleIdempotencyLedger.Entry existing = ledger.find(request.requestId()).orElse(null);
            if (existing != null && !existing.digest().equals(request.digest())) {
                throw new IOException("lifecycle_idempotency_conflict");
            }
            if (existing != null && (existing.state() == LifecycleIdempotencyLedger.State.COMPLETED
                    || existing.state() == LifecycleIdempotencyLedger.State.FAILED)) {
                return replay(existing);
            }
            CompletableFuture<CodexLifecycleHttpClient.Response> prior = inFlight.get(request.requestId());
            if (prior != null) {
                return prior.get();
            }
            if (existing == null || existing.state() == LifecycleIdempotencyLedger.State.AMBIGUOUS) {
                verifyAuthority(request);
            }
            runtime = runtime(request.authority().bindingSessionId(), request.authority());
            if (!runtime.authority().equals(request.authority())) {
                throw new IOException("lifecycle_binding_mismatch");
            }
            CodexLifecycleStateStore.Checkpoint before = runtime.stateStore().read(
                    request.authority().bindingSessionId(), request.authority().projectId());
            if (request.expectedLifecycleRevision() != before.revision()) {
                throw new IOException("lifecycle_revision_stale");
            }
            LifecycleIdempotencyLedger.PrepareResult prepared = ledger.prepare(request, before.revision());
            if (prepared.disposition() == LifecycleIdempotencyLedger.Disposition.IN_PROGRESS) {
                CompletableFuture<CodexLifecycleHttpClient.Response> concurrent = inFlight.get(request.requestId());
                if (concurrent != null) return concurrent.get();
                throw new IOException("lifecycle_request_ambiguous");
            }
            if (prepared.disposition() == LifecycleIdempotencyLedger.Disposition.AMBIGUOUS) {
                throw new IOException("lifecycle_request_ambiguous");
            }
            if (prepared.disposition() == LifecycleIdempotencyLedger.Disposition.NEW
                    && !ledger.verifyCommitted(request.requestId(), request.digest(), before.revision())) {
                try {
                    ledger.complete(request.requestId(), LifecycleIdempotencyLedger.State.FAILED, before.revision(),
                            "lifecycle_idempotency_persistence_failed");
                } catch (IOException ignored) {
                    // The original durability failure is the bounded caller diagnostic.
                }
                throw new IOException("lifecycle_idempotency_persistence_failed");
            }
            execution = new CompletableFuture<>();
            inFlight.put(request.requestId(), execution);
            executor = request.operation() == LifecycleControlRequestEnvelope.Operation.WAIT
                    ? waitExecutor : controlExecutor;
        }
        try {
            Future<?> task = executor.submit(() -> execute(request, runtime, execution));
            executionTasks.put(request.requestId(), task);
        } catch (RuntimeException rejected) {
            inFlight.remove(request.requestId(), execution);
            throw rejected;
        }
        try {
            return execution.get();
        } catch (InterruptedException interrupted) {
            // A disconnected/cancelled HTTP WAIT interrupts the bridge
            // thread.  Interrupt the actual bounded wait task as well so its
            // finally block removes the registered waiter immediately rather
            // than retaining capacity until the caller deadline.
            if (request.operation() == LifecycleControlRequestEnvelope.Operation.WAIT) {
                Future<?> task = executionTasks.get(request.requestId());
                if (task != null) {
                    task.cancel(true);
                }
            }
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    /**
     * Closes this owner, waking waits and closing all owned App Server attachments.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // Stop queued/in-flight dispatcher tasks from issuing a new mutation
        // while the binding services perform their own exact-turn graceful
        // interruption and bounded attachment shutdown.
        executionTasks.values().forEach(task -> task.cancel(true));
        executionTasks.clear();
        bindings.values().forEach(runtime -> {
            runtime.service().close();
            runtime.cleanupEvidence();
            runtime.releaseLock();
        });
        bindings.clear();
        inFlight.values().forEach(future -> future.completeExceptionally(new IOException("owner_shutdown")));
        inFlight.clear();
        controlExecutor.shutdownNow();
        waitExecutor.shutdownNow();
        try {
            writeOwner("STOPPED");
            hostLock.release();
            hostLockChannel.close();
        } catch (IOException ignored) {
            // Shutdown is bounded and diagnostic state is already durable where possible.
        }
    }

    private void execute(LifecycleControlRequestEnvelope request, BindingRuntime runtime,
            CompletableFuture<CodexLifecycleHttpClient.Response> result) {
        boolean mutationStarted = false;
        try {
            if (closed) {
                throw new IOException("owner_shutdown");
            }
            if (request.classification() == LifecycleControlRequestEnvelope.Classification.STATE_CHANGING) {
                runtime.acquireAttachmentLock();
                verifyAuthority(request);
                runtime.claimHostCheckpoint();
                runtime.recordOwner();
            }
            mutationStarted = request.classification() == LifecycleControlRequestEnvelope.Classification.STATE_CHANGING;
            CodexLifecycleHttpClient.Response response = switch (request.operation()) {
                case START -> runtime.service().start(request);
                case NOTIFY -> runtime.service().notify(request);
                case STEER -> runtime.service().steer(request);
                case WAIT -> runtime.service().waitForTurn(request);
                case INTERRUPT -> runtime.service().interrupt(request);
                case HARD_STOP -> runtime.service().hardStop(request);
                case RESUME -> runtime.service().resume(request);
                case STATUS -> runtime.service().status(request);
            };
            if (request.operation() == LifecycleControlRequestEnvelope.Operation.STATUS) {
                Map<String, Object> diagnostics = new LinkedHashMap<>(response.result());
                diagnostics.put("hostInstanceId", hostInstanceId);
                diagnostics.put("bindingSessionId", runtime.bindingSessionId());
                diagnostics.put("ledgerEntries", ledger.entries().size());
                diagnostics.put("ledgerAmbiguousEntries", ledger.entries().stream()
                        .filter(entry -> entry.state() == LifecycleIdempotencyLedger.State.AMBIGUOUS).count());
                ledger.entries().stream().filter(LifecycleIdempotencyLedger.Entry::activeOrAmbiguous)
                        .min(java.util.Comparator.comparingLong(LifecycleIdempotencyLedger.Entry::startedAtEpochMillis))
                        .ifPresent(oldest -> {
                            diagnostics.put("ledgerOldestActiveRequestId", oldest.requestId().toString());
                            diagnostics.put("ledgerOldestActiveStartedAt", oldest.startedAtEpochMillis());
                        });
                diagnostics.put("ledgerEvictions", ledger.evictionCount());
                diagnostics.put("ledgerConflicts", ledger.conflictCount());
                long bindingEntries = ledger.entries().stream()
                        .filter(entry -> entry.bindingSessionId().equals(runtime.bindingSessionId())).count();
                diagnostics.put("bindingLedgerEntries", bindingEntries);
                diagnostics.put("ledgerUtilization", ledger.entries().size() + "/"
                        + LifecycleIdempotencyLedger.MAX_HOST_ENTRIES);
                diagnostics.put("bindingLedgerUtilization", bindingEntries + "/"
                        + LifecycleIdempotencyLedger.MAX_BINDING_ENTRIES);
                diagnostics.put("idempotencyInitialPersistenceFailures",
                        ledger.initialPersistenceFailureCount());
                diagnostics.put("idempotencyResultStorageFailures", ledger.resultStorageFailureCount());
                diagnostics.put("oversizedLifecycleControlRequestFailures", adapter.oversizedRequestCount());
                diagnostics.putAll(runtime.service().diagnostics());
                response = new CodexLifecycleHttpClient.Response(response.success(), response.diagnostic(),
                        response.state(), response.lifecycleRevision(), response.threadId(), response.turnId(),
                        diagnostics);
            }
            long revision = runtime.service().statusUnchecked().lifecycleRevision();
            runtime.recordOwner();
            ledger.complete(request.requestId(), LifecycleIdempotencyLedger.State.COMPLETED, revision,
                    durableResult(response));
            result.complete(response);
        } catch (Exception failure) {
            String diagnostic = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            CodexLifecycleHttpClient.Response failureResponse;
            try {
                CodexLifecycleStateStore.Checkpoint current = runtime.stateStore().read(
                        request.authority().bindingSessionId(), request.authority().projectId());
                LifecycleIdempotencyLedger.State state = mutationStarted
                        && current.state() != CodexLifecycleStateStore.State.FAILED
                        && current.state() != CodexLifecycleStateStore.State.STOPPED
                        ? LifecycleIdempotencyLedger.State.AMBIGUOUS : LifecycleIdempotencyLedger.State.FAILED;
                failureResponse = new CodexLifecycleHttpClient.Response(false, diagnostic,
                        current.state().name(), current.revision(), current.threadId(), current.turnId(), Map.of());
                ledger.complete(request.requestId(), state, current.revision(), durableResult(failureResponse));
            } catch (IOException ignored) {
                diagnostic = diagnostic + ":lifecycle_idempotency_result_persistence_failed";
                failureResponse = new CodexLifecycleHttpClient.Response(false, diagnostic,
                        safeState(runtime), safeRevision(runtime), null, null, Map.of());
            }
            result.complete(failureResponse);
        } finally {
            inFlight.remove(request.requestId());
            executionTasks.remove(request.requestId());
            pruneRequestLocks();
            if (request.operation() == LifecycleControlRequestEnvelope.Operation.HARD_STOP
                    || !runtime.isAlive()) {
                runtime.cleanupEvidence();
                runtime.releaseLock();
            }
        }
    }

    private void pruneRequestLocks() {
        if (requestLocks.size() <= LifecycleIdempotencyLedger.MAX_HOST_ENTRIES * 2) {
            return;
        }
        requestLocks.keySet().stream().sorted()
                .filter(id -> !inFlight.containsKey(id) && ledger.find(id).isEmpty())
                .limit(requestLocks.size() - LifecycleIdempotencyLedger.MAX_HOST_ENTRIES)
                .forEach(requestLocks::remove);
    }

    private BindingRuntime runtime(String bindingSessionId,
            LifecycleControlRequestEnvelope.AuthorityContext authority) throws IOException {
        BindingRuntime existing = bindings.get(bindingSessionId);
        if (existing != null) return existing;
        CodexLifecycleStateStore stateStore = new CodexLifecycleStateStore(runtimeRoot.resolve("bindings"));
        CodexLifecycleStateStore.Checkpoint prior = stateStore.read(bindingSessionId, authority.projectId());
        Path evidence = runtimeRoot.resolve("evidence").resolve(bindingSessionId);
        CodexAppServerLifecycleService service = new CodexAppServerLifecycleService(authority, stateStore, ledger,
                launcher, terminator, evidence);
        BindingRuntime created = new BindingRuntime(bindingSessionId, authority, stateStore, service,
                runtimeRoot.resolve("bindings").resolve(bindingSessionId + ".attachment.lock"),
                runtimeRoot.resolve("bindings").resolve(bindingSessionId + ".attachment.owner.json"));
        BindingRuntime raced = bindings.putIfAbsent(bindingSessionId, created);
        return raced == null ? created : raced;
    }

    private void verifyEnvelope(LifecycleControlRequestEnvelope.SignedEnvelope signed) throws Exception {
        if (!signed.verify() || !ownerIdentity.nodeId().equals(signed.signerNodeId())) {
            throw new IOException("lifecycle_signature_invalid");
        }
        LifecycleControlRequestEnvelope request = signed.request();
        if (!hostInstanceId.equals(request.hostInstanceId())) {
            throw new IOException("lifecycle_owner_mismatch");
        }
        if (!location.projectId().toString().equals(request.authority().projectId())
                || !"codex".equals(request.authority().provider())) {
            throw new IOException("lifecycle_project_or_provider_mismatch");
        }
    }

    private void verifyAuthority(LifecycleControlRequestEnvelope request) throws Exception {
        ProjectApplicationService.ProjectLocation current = new ProjectApplicationService().locate(location.root());
        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        ProviderSessionBindingService.Binding binding = bindingService.find(current, "codex",
                request.authority().connectionInstanceId()).orElseThrow(() -> new IOException("lifecycle_binding_missing"));
        LifecycleControlRequestEnvelope.AuthorityContext expected = request.authority();
        if (!location.root().toAbsolutePath().normalize().toString().equals(expected.controlProjectRoot())) {
            throw new IOException("lifecycle_project_root_mismatch");
        }
        if (!binding.projectId().equals(expected.projectId()) || !"codex".equals(binding.provider())
                || !binding.sessionId().equals(expected.bindingSessionId())
                || !binding.providerInstanceFingerprint().equals(expected.bindingFingerprint())
                || binding.bindingVersion() != expected.bindingVersion()
                || !"BOUND".equals(binding.status()) || !"VERIFIED".equals(binding.verificationState())
                || !"VERIFIED".equals(binding.providerTrustState()) || binding.worktreePath() == null) {
            throw new IOException("lifecycle_binding_stale");
        }
        if (binding.controlCheckoutPath() == null
                || !Path.of(binding.controlCheckoutPath()).toAbsolutePath().normalize()
                        .equals(location.root().toAbsolutePath().normalize())) {
            throw new IOException("lifecycle_project_root_mismatch");
        }
        if (!expected.participant().equals(org.synesis.workspace.application.collaboration.WorkspaceCollaborationService
                .participantHandle(binding.sessionId()))) {
            throw new IOException("lifecycle_participant_mismatch");
        }
        Path assigned;
        try {
            assigned = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
            if (!assigned.toString().equals(expected.canonicalWorktree())
                    || !assigned.toRealPath().toString().equals(expected.realWorktree())) {
                throw new IOException("lifecycle_worktree_mismatch");
            }
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IOException io && "lifecycle_worktree_mismatch".equals(io.getMessage())) {
                throw io;
            }
            throw new IOException("lifecycle_worktree_mismatch", failure);
        }
        if (binding.gitCommonDir() == null || binding.branch() == null || binding.baseCommit() == null
                || !binding.gitCommonDir().equals(expected.gitCommonDirectory())
                || !binding.branch().equals(expected.branch()) || !binding.baseCommit().equals(expected.baseCommit())) {
            throw new IOException("lifecycle_worktree_identity_mismatch");
        }
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"),
                location.projectId());
        Participant participant = store.collaborationProjection().participants().stream()
                .filter(item -> item.id().equals(expected.participant())).findFirst().orElse(null);
        if (participant == null || participant.state() != Participant.State.ACTIVE) {
            throw new IOException("lifecycle_participant_inactive");
        }
        WorkIntent intent;
        try {
            intent = store.collaborationProjection().intent(UUID.fromString(expected.workIntentId())).orElse(null);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("lifecycle_claim_not_acquired", malformed);
        }
        if (intent == null || intent.status() != WorkIntent.Status.ANNOUNCED
                || !intent.projectId().toString().equals(expected.projectId())
                || !"codex".equals(intent.provider())
                || !intent.participant().equals(expected.participant())
                || !intent.baseCommit().equals(expected.baseCommit())
                || intent.version() != expected.laneEpoch()) {
            throw new IOException("lifecycle_claim_not_acquired");
        }
        new ProviderManualService().requireAttested("codex");
    }

    private CodexLifecycleHttpClient.Response replay(LifecycleIdempotencyLedger.Entry entry) {
        if (entry.result() != null && entry.result().startsWith("{")) {
            try {
                return CodexLifecycleHttpClient.Response.decode(
                        entry.result().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Older or truncated entries fall through to the bounded
                // replay reference below; they remain non-replayable as a
                // mutation, but the caller still receives a durable result
                // reference rather than an invented success.
            }
        }
        Map<String, Object> result = entry.result() == null ? Map.of()
                : Map.of("resultReference", entry.result());
        return new CodexLifecycleHttpClient.Response(entry.state() == LifecycleIdempotencyLedger.State.COMPLETED,
                "idempotency_replay", entry.state().name(), entry.revisionAfter(), entry.threadId(), entry.turnId(), result);
    }

    private static String durableResult(CodexLifecycleHttpClient.Response response) {
        byte[] encoded = response.encoded();
        if (encoded.length <= 15_000) {
            return new String(encoded, StandardCharsets.UTF_8);
        }
        try {
            return "response-ref:sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required", impossible);
        }
    }

    private static String safeState(BindingRuntime runtime) {
        try {
            return runtime.stateStore().read(runtime.bindingSessionId(), runtime.authority().projectId()).state().name();
        } catch (IOException failure) {
            return CodexLifecycleStateStore.State.FAILED.name();
        }
    }

    private static long safeRevision(BindingRuntime runtime) {
        try {
            return runtime.stateStore().read(runtime.bindingSessionId(), runtime.authority().projectId()).revision();
        } catch (IOException failure) {
            return 0L;
        }
    }

    private void writeOwner(String status) throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("hostInstanceId", hostInstanceId == null ? "initializing" : hostInstanceId);
        value.put("projectId", location.projectId().toString());
        value.put("ownerNodeId", ownerIdentity.nodeId());
        value.put("pid", ProcessHandle.current().pid());
        value.put("commandIdentity", ProcessHandle.current().info().commandLine().orElse("synesis coordination serve"));
        value.put("startEpochMillis", ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli)
                .orElse(System.currentTimeMillis()));
        value.put("status", status);
        value.put("updatedAtEpochMillis", System.currentTimeMillis());
        Path temporary = ownerRecord.resolveSibling(ownerRecord.getFileName() + ".tmp");
        Files.writeString(temporary, ProviderJson.write(value) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, ownerRecord, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, ownerRecord, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeState(CodexLifecycleStateStore.Checkpoint checkpoint) {
        return checkpoint.state().name();
    }

    private final class BindingRuntime {
        private final String bindingSessionId;
        private final LifecycleControlRequestEnvelope.AuthorityContext authority;
        private final CodexLifecycleStateStore stateStore;
        private final CodexAppServerLifecycleService service;
        private final Path evidenceDirectory;
        private final Path lockPath;
        private final Path ownerPath;
        private FileChannel channel;
        private FileLock lock;

        private BindingRuntime(String bindingSessionId, LifecycleControlRequestEnvelope.AuthorityContext authority,
                CodexLifecycleStateStore stateStore, CodexAppServerLifecycleService service, Path lockPath,
                Path ownerPath) {
            this.bindingSessionId = bindingSessionId;
            this.authority = authority;
            this.stateStore = stateStore;
            this.service = service;
            this.evidenceDirectory = runtimeRoot.resolve("evidence").resolve(bindingSessionId);
            this.lockPath = lockPath;
            this.ownerPath = ownerPath;
        }

        private synchronized void recordOwner() throws IOException {
            CodexLifecycleStateStore.Checkpoint checkpoint = stateStore.readUnchecked(bindingSessionId,
                    authority.projectId());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("hostInstanceId", hostInstanceId);
            value.put("projectId", authority.projectId());
            value.put("bindingSessionId", bindingSessionId);
            value.put("pid", ProcessHandle.current().pid());
            value.put("startEpochMillis", ProcessHandle.current().info().startInstant()
                    .map(Instant::toEpochMilli).orElse(System.currentTimeMillis()));
            value.put("attachmentGeneration", checkpoint.attachmentGeneration());
            value.put("connectionGeneration", checkpoint.connectionGeneration());
            value.put("updatedAtEpochMillis", System.currentTimeMillis());
            Files.createDirectories(ownerPath.getParent());
            Path temporary = ownerPath.resolveSibling(ownerPath.getFileName() + ".tmp");
            Files.writeString(temporary, ProviderJson.write(value) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, ownerPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, ownerPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private synchronized void claimHostCheckpoint() throws IOException {
            CodexLifecycleStateStore.Checkpoint checkpoint = stateStore.read(bindingSessionId,
                    authority.projectId());
            if (hostInstanceId.equals(checkpoint.ownerHostInstanceId())) {
                return;
            }
            stateStore.write(new CodexLifecycleStateStore.Checkpoint(bindingSessionId, authority.projectId(),
                    checkpoint.provider(), checkpoint.revision(), checkpoint.state(), hostInstanceId,
                    checkpoint.attachmentGeneration(), checkpoint.connectionGeneration(), checkpoint.rootPid(),
                    checkpoint.rootStartEpochMillis(), checkpoint.rootExecutable(), checkpoint.rootCommandIdentity(),
                    checkpoint.threadId(), checkpoint.turnId(), checkpoint.terminalDiagnostic(),
                    checkpoint.evidenceComplete(), System.currentTimeMillis()));
        }

        private void cleanupEvidence() {
            CodexLifecycleStateStore.Checkpoint checkpoint = stateStore.readUnchecked(bindingSessionId,
                    authority.projectId());
            CodexEvidenceRetention.cleanup(evidenceDirectory, checkpoint.connectionGeneration(), location.root());
        }

        private synchronized void acquireAttachmentLock() throws IOException {
            if (lock != null && lock.isValid()) return;
            Files.createDirectories(lockPath.getParent());
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException failure) {
                channel.close();
                throw new IOException(existingOwnerDiagnostic(), failure);
            }
            if (lock == null) {
                channel.close();
                throw new IOException(existingOwnerDiagnostic());
            }
        }

        private synchronized void releaseLock() {
            try {
                if (lock != null) lock.release();
                if (channel != null) channel.close();
            } catch (IOException ignored) {
                // Diagnostic cleanup only; lifecycle record remains authoritative.
            } finally {
                lock = null;
                channel = null;
                try {
                    Files.deleteIfExists(ownerPath);
                } catch (IOException ignored) {
                    // Diagnostic cleanup only; stale metadata is checked by the next owner.
                }
            }
        }

        private String existingOwnerDiagnostic() {
            try {
                if (!Files.isRegularFile(ownerPath)) {
                    return "lifecycle_attachment_owner_already_running";
                }
                Object parsed = ProviderJson.parse(Files.readString(ownerPath, StandardCharsets.UTF_8));
                if (parsed instanceof Map<?, ?> raw && raw.get("pid") instanceof Number pid
                        && raw.get("startEpochMillis") instanceof Number start) {
                    ProcessHandle.Info info = ProcessHandle.of(pid.longValue()).map(ProcessHandle::info).orElse(null);
                    if (info != null && info.startInstant().map(Instant::toEpochMilli)
                            .map(value -> value == start.longValue()).orElse(false)) {
                        return "lifecycle_attachment_owner_already_running";
                    }
                }
            } catch (RuntimeException | IOException ignored) {
                return "lifecycle_attachment_owner_untrusted";
            }
            return "lifecycle_attachment_lock_stale";
        }

        private boolean isAlive() {
            return service.attachmentAlive();
        }

        private String bindingSessionId() { return bindingSessionId; }
        private LifecycleControlRequestEnvelope.AuthorityContext authority() { return authority; }
        private CodexLifecycleStateStore stateStore() { return stateStore; }
        private CodexAppServerLifecycleService service() { return service; }
    }
}
