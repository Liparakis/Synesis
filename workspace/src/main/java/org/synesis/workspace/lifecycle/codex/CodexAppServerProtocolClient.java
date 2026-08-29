package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Bounded JSONL Codex App Server protocol client.
 *
 * <p>The reader parses frames and applies response/event state before evidence
 * enqueue. Pending request completion and WAIT notifications do not depend on
 * journal throughput. Request IDs are connection-generation local. Valid late
 * responses match bounded tombstones; never-issued and duplicate terminal
 * responses fail the generation. The client is thread-safe and owns its reader
 * thread and output writes; callers must close it exactly once.
 *
 * @since 1.0
 */
public final class CodexAppServerProtocolClient implements AutoCloseable {

    /**
     * Maximum pending requests for one connection generation.
     */
    public static final int MAX_PENDING_REQUESTS = 128;
    /**
     * Maximum late-response tombstones for one connection generation.
     */
    public static final int MAX_TOMBSTONES = 256;
    /**
     * Maximum issued request IDs retained for duplicate detection.
     */
    public static final int MAX_TERMINAL_IDS = 65_536;
    /**
     * Tombstone retention bound.
     */
    public static final Duration TOMBSTONE_RETENTION = Duration.ofMinutes(15);
    private final long connectionGeneration;
    private final InputStream stdout;
    private final OutputStream stdin;
    private final Listener listener;
    private final CodexEvidenceJournal evidence;
    private final BoundedProtocolFrameReader frames;
    private final AtomicLong ids = new AtomicLong();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Tombstone> tombstones = new LinkedHashMap<>();
    private final java.util.LinkedHashSet<String> terminalIds = new java.util.LinkedHashSet<>();
    private final java.util.LinkedHashSet<String> issuedIds = new java.util.LinkedHashSet<>();
    private final Object writeLock = new Object();
    private final Object tombstoneLock = new Object();
    private final Thread reader;
    private final AtomicLong lateResponses = new AtomicLong();
    private final AtomicLong tombstoneEvictions = new AtomicLong();
    private final AtomicLong tombstoneSaturations = new AtomicLong();
    private final AtomicLong correlationFailures = new AtomicLong();
    private final AtomicLong oversizedFrames = new AtomicLong();
    private volatile boolean closed;
    private volatile Throwable failure;
    /**
     * Creates and starts a protocol reader.
     *
     * @param connectionGeneration local connection generation
     * @param stdout               App Server stdout
     * @param stdin                App Server stdin
     * @param listener             event/failure listener
     * @param evidence             asynchronous evidence journal
     */
    public CodexAppServerProtocolClient(long connectionGeneration, InputStream stdout, OutputStream stdin,
            Listener listener, CodexEvidenceJournal evidence) {
        if (connectionGeneration < 0) {
            throw new IllegalArgumentException("connection generation must not be negative");
        }
        this.connectionGeneration = connectionGeneration;
        this.stdout = Objects.requireNonNull(stdout, "stdout");
        this.stdin = Objects.requireNonNull(stdin, "stdin");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.frames = new BoundedProtocolFrameReader(stdout);
        this.reader = new Thread(this::readLoop, "synesis-codex-protocol-reader-" + connectionGeneration);
        this.reader.setDaemon(true);
        this.reader.start();
    }

    private static boolean isTerminalEvent(String method, Map<String, Object> params) {
        if (CodexAppServerProtocolSchema.isLifecycleEvent(method)
                && (method.equals("thread/started") || method.equals("thread/resumed")
                || method.equals("thread/closed") || method.equals("turn/completed")
                || method.equals("turn/interrupt_acknowledged") || method.equals("process/exited"))) {
            return true;
        }
        // A final agent message is diagnostic evidence that must survive queue
        // pressure even though the corresponding item delta stream is
        // intentionally low priority.
        if ("item/completed".equals(method) && params.get("item") instanceof Map<?, ?> item) {
            Object type = item.get("type");
            Object phase = item.get("phase");
            return "agentMessage".equals(String.valueOf(type))
                    && (phase == null || "final_answer".equals(String.valueOf(phase)));
        }
        return false;
    }

    private static String boundedSummary(Object result, Object error) {
        String value = String.valueOf(error == null ? result : error);
        return value.length() > 1024 ? value.substring(0, 1024) : value;
    }

    private static String digest(byte[] value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required", impossible);
        }
    }

    /**
     * Sends one bounded request and waits outside lifecycle locks.
     *
     * @param method           protocol method
     * @param params           bounded JSON parameters
     * @param classification   request classification
     * @param digest           bounded request identity/digest
     * @param expectedThreadId exact expected thread
     * @param expectedTurnId   exact expected turn
     * @param timeout          caller-relative timeout
     * @return parsed response
     * @throws IOException          when the generation fails or write is rejected
     * @throws TimeoutException     when the caller deadline expires
     * @throws InterruptedException when interrupted
     */
    public Response request(String method, Map<String, ?> params,
            LifecycleControlRequestEnvelope.Classification classification, String digest,
            String expectedThreadId, String expectedTurnId, Duration timeout)
            throws IOException, TimeoutException, InterruptedException {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(timeout, "timeout");
        CodexAppServerProtocolSchema.validateRequest(method, params);
        if (closed || failure != null) {
            throw new IOException("codex protocol generation failed", failure);
        }
        if (pending.size() >= MAX_PENDING_REQUESTS) {
            throw new IOException("codex pending request bound exceeded");
        }
        String id = "synesis-" + connectionGeneration + "-" + ids.incrementAndGet();
        CompletableFuture<Response> future = new CompletableFuture<>();
        Pending request = new Pending(id, method, classification, digest == null ? "" : digest,
                expectedThreadId, expectedTurnId, System.currentTimeMillis(), future);
        pending.put(id, request);
        boolean writeAttempted = false;
        try {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("id", id);
            frame.put("method", method);
            frame.put("params", new LinkedHashMap<>(params));
            byte[] encoded = (ProviderJson.write(frame) + "\n").getBytes(StandardCharsets.UTF_8);
            if (encoded.length > LifecycleControlRequestEnvelope.MAX_ENVELOPE_BYTES) {
                pending.remove(id);
                throw new IOException("codex request exceeds bound");
            }
            synchronized (writeLock) {
                writeAttempted = true;
                rememberIssued(id);
                stdin.write(encoded);
                stdin.flush();
            }
            long millis = Math.max(1L, timeout.toMillis());
            return future.get(millis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException failure) {
            pending.remove(id);
            addTombstone(request, failure instanceof TimeoutException ? "timeout" : "caller_cancellation");
            throw failure;
        } catch (ExecutionException failure) {
            pending.remove(id);
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("codex request failed", cause);
        } catch (IOException failure) {
            pending.remove(id);
            if (writeAttempted) {
                addTombstone(request, "connection_ambiguity");
            }
            throw failure;
        }
    }

    /**
     * Sends a bounded, schema-validated client notification without creating a
     * pending request or response correlation entry.
     *
     * @param method installed Codex client notification method
     * @param params bounded notification parameters
     * @throws IOException when the generation is failed or the notification is invalid
     */
    public void notify(String method, Map<String, ?> params) throws IOException {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(params, "params");
        CodexAppServerProtocolSchema.validateNotification(method, params);
        if (closed || failure != null) {
            throw new IOException("codex protocol generation failed", failure);
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("method", method);
        frame.put("params", new LinkedHashMap<>(params));
        byte[] encoded = (ProviderJson.write(frame) + "\n").getBytes(StandardCharsets.UTF_8);
        if (encoded.length > LifecycleControlRequestEnvelope.MAX_ENVELOPE_BYTES) {
            throw new IOException("codex notification exceeds bound");
        }
        synchronized (writeLock) {
            stdin.write(encoded);
            stdin.flush();
        }
    }

    /**
     * Returns this connection generation.
     *
     * @return connection generation
     */
    public long connectionGeneration() {
        return connectionGeneration;
    }

    /**
     * Returns whether this connection generation has failed.
     *
     * @return failure state
     */
    public boolean failed() {
        return failure != null;
    }

    /**
     * Returns immutable current tombstones.
     *
     * @return current tombstones
     */
    public List<Tombstone> tombstones() {
        synchronized (tombstoneLock) {
            return List.copyOf(tombstones.values());
        }
    }

    /**
     * Returns bounded protocol-correlation diagnostics for status and doctor.
     *
     * @return immutable diagnostic counters
     */
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lateResponseCount", lateResponses.get());
        result.put("tombstoneEvictions", tombstoneEvictions.get());
        result.put("tombstoneSaturations", tombstoneSaturations.get());
        result.put("correlationFailures", correlationFailures.get());
        result.put("oversizedProtocolFrameFailures", oversizedFrames.get());
        result.put("tombstoneUtilization", tombstones().size());
        return Map.copyOf(result);
    }

    /**
     * Closes the connection and wakes all pending requests.
     */
    @SuppressWarnings("ExtractMethodRecommender")
    @Override
    public void close() {
        closed = true;
        IOException cause = new IOException("codex connection closed");
        pending.values()
                .forEach(item -> {
                    addTombstone(item, "owner_shutdown");
                    item.future()
                            .completeExceptionally(cause);
                });
        pending.clear();
        reader.interrupt();
        // Process-pipe streams can hold a monitor while a reader is blocked in
        // native I/O.  Closing them synchronously would let owner shutdown
        // wait forever when ownership is intentionally unproven.  Delegate
        // the close and bound the join; a verified hard stop normally releases
        // the pipe, while an unverified attachment is reported rather than
        // stalling the production host.
        Thread streamCloser = new Thread(() -> {
            try {
                stdout.close();
            } catch (IOException ignored) {
                // Best effort after authoritative failure is recorded.
            }
            try {
                stdin.close();
            } catch (IOException ignored) {
                // Best effort after authoritative failure is recorded.
            }
        }, "synesis-codex-protocol-stream-closer-" + connectionGeneration);
        streamCloser.setDaemon(true);
        streamCloser.start();
        try {
            reader.join(500L);
            streamCloser.join(500L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                    .interrupt();
        }
    }

    private void readLoop() {
        try {
            while (!closed) {
                String frame = frames.readFrame();
                Object parsed = ProviderJson.parse(frame);
                if (!(parsed instanceof Map<?, ?> raw)) {
                    throw new IOException("codex_protocol_malformed_frame");
                }
                Map<String, Object> value = new LinkedHashMap<>();
                raw.forEach((key, item) -> value.put(String.valueOf(key), item));
                CodexAppServerProtocolSchema.validateFrame(value);
                if (value.containsKey("method")) {
                    if (value.containsKey("id")) {
                        handleServerRequest(value);
                    } else {
                        handleEvent(value);
                    }
                } else if (value.containsKey("id")) {
                    handleResponse(value);
                } else {
                    throw new IOException("codex_protocol_malformed_frame");
                }
            }
        } catch (BoundedProtocolFrameReader.CleanEofException eof) {
            if (!closed) {
                fail(new IOException("codex_connection_eof", eof));
            }
        } catch (BoundedProtocolFrameReader.OversizedFrameException oversized) {
            oversizedFrames.incrementAndGet();
            evidence.offer("codex_protocol_oversized", Map.of(
                    "observedBytes", oversized.observedBytes(),
                    "prefixDigest", digest(oversized.prefix()),
                    "prefixBytes", oversized.prefix().length), true);
            if (!closed) {
                fail(oversized);
            }
        } catch (Throwable failure) {
            if (!closed) {
                fail(failure);
            }
        }
    }

    private void handleEvent(Map<String, Object> value) {
        String method = String.valueOf(value.get("method"));
        Object rawParams = value.get("params");
        Map<String, Object> params = new LinkedHashMap<>();
        if (rawParams instanceof Map<?, ?> map) {
            map.forEach((key, item) -> params.put(String.valueOf(key), item));
        }
        listener.onEvent(method, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(params)));
        evidence.offer("protocol_event", Map.of("method", method, "params", params),
                isTerminalEvent(method, params));
    }

    private void handleServerRequest(Map<String, Object> value) throws IOException {
        String method = String.valueOf(value.get("method"));
        Object rawRequestId = value.get("id");
        String requestId = String.valueOf(rawRequestId);
        Map<String, Object> params = new LinkedHashMap<>();
        if (value.get("params") instanceof Map<?, ?> map) {
            map.forEach((key, item) -> params.put(String.valueOf(key), item));
        }
        params.put("serverRequestId", requestId);
        params.put("serverRequestMethod", method);
        if ("currentTime/read".equals(method)) {
            sendServerResponse(rawRequestId, Map.of("currentTimeAt", System.currentTimeMillis() / 1_000L));
            evidence.offer("server_request_response", Map.of("requestId", requestId, "method", method), true);
            return;
        }
        // SYN-038 deliberately has no approval or elicitation operation. The
        // request is therefore authoritative interaction-required state, not
        // protocol corruption; the caller can observe STATUS and choose the
        // documented hard-stop/recovery path. Do not guess an approval.
        listener.onEvent("interaction_required", java.util.Collections.unmodifiableMap(params));
        evidence.offer("interaction_required", Map.of("requestId", requestId, "method", method,
                "params", params), true);
    }

    private void sendServerResponse(Object id, Map<String, ?> result) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("id", id);
        frame.put("result", new LinkedHashMap<>(result));
        byte[] encoded = (ProviderJson.write(frame) + "\n").getBytes(StandardCharsets.UTF_8);
        if (encoded.length > LifecycleControlRequestEnvelope.MAX_ENVELOPE_BYTES) {
            throw new IOException("codex server response exceeds bound");
        }
        synchronized (writeLock) {
            if (closed || failure != null) {
                throw new IOException("codex protocol generation failed", failure);
            }
            stdin.write(encoded);
            stdin.flush();
        }
    }

    private void handleResponse(Map<String, Object> value) throws IOException {
        String id = String.valueOf(value.get("id"));
        Pending request = pending.remove(id);
        Object result = value.get("result");
        Object error = value.get("error");
        if (request != null) {
            rememberTerminal(id);
            request.future()
                    .complete(new Response(id, result, error));
            evidence.offer("request_response", Map.of("requestId", id, "method", request.method(),
                    "success", error == null), true);
            return;
        }
        synchronized (tombstoneLock) {
            Tombstone tombstone = tombstones.get(id);
            if (tombstone != null) {
                if (tombstone.responseArrived()) {
                    throw new IOException("duplicate_response_id");
                }
                tombstones.put(id, new Tombstone(tombstone.connectionGeneration(), tombstone.requestId(),
                        tombstone.method(), tombstone.classification(), tombstone.digest(),
                        tombstone.sentAtEpochMillis(), tombstone.endedAtEpochMillis(), tombstone.reason(),
                        tombstone.expectedThreadId(), tombstone.expectedTurnId(), true,
                        boundedSummary(result, error)));
                lateResponses.incrementAndGet();
                String reason = "caller_cancellation".equals(tombstone.reason())
                        ? "cancellation" : tombstone.reason();
                evidence.offer("late_response_after_" + reason,
                        Map.of("requestId", id, "method", tombstone.method()), true);
                return;
            }
        }
        synchronized (tombstoneLock) {
            if (terminalIds.contains(id)) {
                throw new IOException("duplicate_response_id");
            }
            if (issuedIds.contains(id)) {
                // The request was sent, but its tombstone was evicted before
                // this response arrived.  The response is not impossible
                // protocol traffic; it is evidence that can no longer prove
                // the caller's original outcome.  Preserve that ambiguity
                // explicitly so STATUS/reconciliation, rather than replay,
                // becomes the next safe action.
                evidence.markIncomplete();
                evidence.offer("late_response_after_tombstone_eviction",
                        Map.of("requestId", id, "requiresReconciliation", true), true);
                return;
            }
        }
        if (isReplacedGeneration(id)) {
            evidence.offer("late_response_replaced_generation", Map.of("requestId", id), true);
            lateResponses.incrementAndGet();
            return;
        }
        correlationFailures.incrementAndGet();
        throw new IOException("codex_request_correlation_failed");
    }

    private void fail(Throwable cause) {
        if (failure != null) {
            return;
        }
        failure = cause;
        closed = true;
        IOException error = cause instanceof IOException io ? io : new IOException("codex protocol failure", cause);
        pending.values()
                .forEach(item -> {
                    addTombstone(item, "connection_ambiguity");
                    item.future()
                            .completeExceptionally(error);
                });
        pending.clear();
        String diagnostic = String.valueOf(error.getMessage());
        String category = diagnostic.contains("duplicate_response_id") ? "duplicate_response_id"
                : diagnostic.contains("codex_request_correlation_failed") ? "never_issued_response_id"
                        : "protocol_failure";
        evidence.offer(category, Map.of("diagnostic", diagnostic), true);
        listener.onFailure(error);
        try {
            stdout.close();
        } catch (IOException ignored) {
            // Failure cleanup is best effort; the owner performs verified hard stop.
        }
        try {
            stdin.close();
        } catch (IOException ignored) {
            // Failure cleanup is best effort; the owner performs verified hard stop.
        }
    }

    private void addTombstone(Pending request, String reason) {
        synchronized (tombstoneLock) {
            expireTombstones();
            if (tombstones.size() >= MAX_TOMBSTONES) {
                String candidate = tombstones.values()
                        .stream()
                        .filter(item -> item.classification()
                                == LifecycleControlRequestEnvelope.Classification.READ_ONLY)
                        .map(Tombstone::requestId)
                        .findFirst()
                        .orElse(null);
                if (candidate == null) {
                    tombstoneSaturations.incrementAndGet();
                    evidence.markIncomplete();
                    evidence.offer("tombstone_saturation", Map.of("requestId", request.id()), true);
                    return;
                }
                tombstones.remove(candidate);
                tombstoneEvictions.incrementAndGet();
                evidence.offer("tombstone_evicted", Map.of("requestId", candidate), true);
            }
            tombstones.put(request.id(), new Tombstone(connectionGeneration,
                    request.id(),
                    request.method(),
                    request.classification(),
                    request.digest(),
                    request.sentAtEpochMillis(),
                    System.currentTimeMillis(),
                    reason,
                    request.expectedThreadId(),
                    request.expectedTurnId(),
                    false,
                    ""));
            String category = request.classification()
                    == LifecycleControlRequestEnvelope.Classification.STATE_CHANGING
                    ? "ambiguous_state_changing_request" : "request_tombstone";
            evidence.offer(category, Map.of("requestId", request.id(), "method", request.method(),
                    "reason", reason), request.classification()
                    == LifecycleControlRequestEnvelope.Classification.STATE_CHANGING);
        }
    }

    private void expireTombstones() {
        long cutoff = System.currentTimeMillis() - TOMBSTONE_RETENTION.toMillis();
        tombstones.values()
                .removeIf(item -> item.endedAtEpochMillis() < cutoff);
    }

    private void rememberTerminal(String id) {
        synchronized (tombstoneLock) {
            terminalIds.add(id);
            while (terminalIds.size() > MAX_TERMINAL_IDS) {
                terminalIds.removeFirst();
            }
        }
    }

    private void rememberIssued(String id) {
        synchronized (tombstoneLock) {
            issuedIds.add(id);
            while (issuedIds.size() > MAX_TERMINAL_IDS) {
                issuedIds.removeFirst();
            }
        }
    }

    private boolean isReplacedGeneration(String id) {
        String prefix = "synesis-";
        if (!id.startsWith(prefix)) {
            return false;
        }
        int separator = id.indexOf('-', prefix.length());
        if (separator < 0) {
            return false;
        }
        try {
            return Long.parseLong(id.substring(prefix.length(), separator)) != connectionGeneration;
        } catch (NumberFormatException malformed) {
            return false;
        }
    }

    /**
     * Receives authoritative protocol events and failures.
     */
    public interface Listener {

        /**
         * Receives one parsed event after lifecycle state update.
         *
         * @param method protocol event method
         * @param params bounded event parameters
         */
        void onEvent(String method, Map<String, Object> params);

        /**
         * Receives a protocol-generation failure.
         *
         * @param failure failure cause
         */
        void onFailure(Throwable failure);
    }

    /**
     * Parsed bounded response.
     *
     * @param id     response correlation ID
     * @param result successful result value
     * @param error  protocol error value
     */
    public record Response(String id, Object result, Object error) {

        /**
         * Returns whether the protocol response contains an error.
         *
         * @return whether the response failed
         */
        public boolean failed() {
            return error != null;
        }
    }

    /**
     * Request timeout/cancellation tombstone summary.
     *
     * @param connectionGeneration connection generation
     * @param requestId            request identity
     * @param method               protocol method
     * @param classification       request classification
     * @param digest               bounded request digest
     * @param sentAtEpochMillis    send timestamp
     * @param endedAtEpochMillis   timeout/cancellation timestamp
     * @param reason               terminal local reason
     * @param expectedThreadId     expected thread identity
     * @param expectedTurnId       expected turn identity
     * @param responseArrived      whether a late response arrived
     * @param lateResponseSummary  bounded late response
     */
    public record Tombstone(long connectionGeneration, String requestId, String method,
                            LifecycleControlRequestEnvelope.Classification classification, String digest,
                            long sentAtEpochMillis, long endedAtEpochMillis, String reason, String expectedThreadId,
                            String expectedTurnId, boolean responseArrived, String lateResponseSummary) {

    }

    private record Pending(String id, String method, LifecycleControlRequestEnvelope.Classification classification,
                           String digest, String expectedThreadId, String expectedTurnId, long sentAtEpochMillis,
                           CompletableFuture<Response> future) {

    }
}
