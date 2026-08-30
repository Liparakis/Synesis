package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Bounded durable idempotency ledger for lifecycle-control requests.
 *
 * <p>The ledger is separate from App Server request IDs, MCP request IDs,
 * lifecycle revisions, WAIT waiter IDs, and evidence IDs. State-changing
 * entries are synchronously committed before a lifecycle mutation is allowed.
 * The implementation is thread-safe for one project host and uses atomic
 * replacement of one local JSON document. Active and ambiguous entries are
 * never evicted. On restart, volatile in-progress state-changing entries are
 * restored as AMBIGUOUS rather than reported completed.
 *
 * @since 1.0
 */
public final class LifecycleIdempotencyLedger {

    /**
     * Maximum entries retained by one project host.
     */
    public static final int MAX_HOST_ENTRIES = 1_024;
    /**
     * Maximum entries retained for one provider binding.
     */
    public static final int MAX_BINDING_ENTRIES = 128;
    /**
     * Minimum reconciliation window for state-changing entries.
     */
    public static final Duration RECONCILIATION_WINDOW = Duration.ofMinutes(15);
    private final Path file;
    private final DurableStore durableStore;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private long evictions;
    private long conflicts;
    private long initialPersistenceFailures;
    private long resultStorageFailures;
    /**
     * Opens the default project-local ledger.
     *
     * @param file JSON ledger file
     * @throws IOException when the existing ledger is malformed
     */
    public LifecycleIdempotencyLedger(Path file) throws IOException {
        this(file, new FileDurableStore(file));
    }
    /**
     * Opens a ledger with an injected durable store.
     *
     * @param file         logical ledger file used for loading and diagnostics
     * @param durableStore atomic persistence implementation
     * @throws IOException when the existing ledger is malformed
     */
    public LifecycleIdempotencyLedger(Path file, DurableStore durableStore) throws IOException {
        this.file = Objects.requireNonNull(file, "file")
                .toAbsolutePath()
                .normalize();
        this.durableStore = Objects.requireNonNull(durableStore, "durableStore");
        load();
    }

    private static Entry decode(Map<String, Object> value) {
        return new Entry(text(value, "hostInstanceId"), text(value, "projectId"),
                UUID.fromString(text(value, "requestId")), text(value, "operation"),
                LifecycleControlRequestEnvelope.Classification.valueOf(text(value, "classification")),
                text(value, "digest"), text(value, "bindingSessionId"), number(value, "expectedRevision"),
                text(value, "laneId"), number(value, "laneEpoch"), optional(value, "threadId"),
                optional(value, "turnId"), number(value, "startedAtEpochMillis"),
                number(value, "deadlineEpochMillis"), State.valueOf(text(value, "state")),
                number(value, "revisionBefore"), number(value, "revisionAfter"), optional(value, "result"),
                number(value, "completedAtEpochMillis"), number(value, "expiryEpochMillis"));
    }

    private static Map<String, Object> map(Object item) {
        if (!(item instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("ledger entry must be object");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        raw.forEach((key, itemValue) -> value.put(String.valueOf(key), itemValue));
        return value;
    }

    private static String text(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return text;
    }

    private static String optional(Map<String, Object> value, String key) {
        Object item = value.get(key);
        return item == null ? null : String.valueOf(item);
    }

    private static long number(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof Number number)) {
            throw new IllegalArgumentException("missing " + key);
        }
        return number.longValue();
    }

    /**
     * Prepares and durably commits a request before mutation.
     *
     * @param request                 immutable request envelope
     * @param lifecycleRevisionBefore current authoritative lifecycle revision
     * @return new, duplicate, conflict, or ambiguous disposition
     * @throws IOException when the initial durable write fails
     */
    public synchronized PrepareResult prepare(LifecycleControlRequestEnvelope request,
            long lifecycleRevisionBefore) throws IOException {
        Objects.requireNonNull(request, "request");
        Entry prior = entries.get(request.requestId());
        if (prior != null) {
            if (!prior.digest()
                    .equals(request.digest())) {
                conflicts++;
                throw new IdempotencyConflictException("lifecycle_idempotency_conflict");
            }
            return new PrepareResult(disposition(prior), prior, false);
        }
        long now = System.currentTimeMillis();
        long expiry = now + Math.max(RECONCILIATION_WINDOW.toMillis(),
                Math.max(1L, request.callerDeadlineEpochMillis() - now));
        Entry entry = new Entry(request.hostInstanceId(),
                request.authority()
                        .projectId(),
                request.requestId(),
                request.operation()
                        .name(),
                request.classification(),
                request.digest(),
                request.authority()
                        .bindingSessionId(),
                request.expectedLifecycleRevision(),
                request.authority()
                        .workIntentId(),
                request.authority()
                        .laneEpoch(),
                request.expectedThreadId(),
                request.expectedTurnId(),
                now,
                request.callerDeadlineEpochMillis(),
                State.ACCEPTED,
                lifecycleRevisionBefore,
                0L,
                null,
                0L,
                expiry);
        Map<UUID, Entry> before = new LinkedHashMap<>(entries);
        long evictionsBefore = evictions;
        entries.put(entry.requestId(), entry);
        boolean evicted = enforceBounds();
        if (entries.size() > MAX_HOST_ENTRIES || hasBindingOverCapacity()) {
            entries.clear();
            entries.putAll(before);
            evictions = evictionsBefore;
            throw new IOException("lifecycle_idempotency_capacity_exhausted");
        }
        try {
            persist();
        } catch (IOException failure) {
            initialPersistenceFailures++;
            entries.clear();
            entries.putAll(before);
            evictions = evictionsBefore;
            throw failure;
        }
        return new PrepareResult(Disposition.NEW, entry, evicted);
    }

    /**
     * Verifies that an accepted entry is present in the durable representation
     * with the expected semantic identity before execution begins.
     *
     * @param requestId      request identity
     * @param digest         canonical request digest
     * @param revisionBefore lifecycle revision captured before preparation
     * @return {@code true} when the committed entry matches exactly
     * @throws IOException when the durable representation cannot be read
     */
    public synchronized boolean verifyCommitted(UUID requestId, String digest, long revisionBefore)
            throws IOException {
        Entry memory = entries.get(requestId);
        if (memory == null || !memory.digest()
                .equals(digest) || memory.revisionBefore() != revisionBefore) {
            return false;
        }
        // A successful write callback is not, by itself, proof that the
        // ledger reached durable storage.  Re-read the committed document (or
        // fail closed when it is absent) before allowing a state-changing
        // lifecycle mutation to proceed.  This is intentionally strict for
        // injected stores too: a store which reports success without leaving a
        // readable committed representation has not satisfied the durable
        // idempotency gate.
        if (!Files.isRegularFile(file)) {
            return false;
        }
        Object parsed = ProviderJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(parsed instanceof List<?> raw)) {
            throw new IOException("lifecycle idempotency ledger is not an array");
        }
        for (Object item : raw) {
            Entry durable = decode(map(item));
            if (durable.requestId()
                    .equals(requestId)) {
                return durable.state() == State.ACCEPTED
                        && durable.digest()
                        .equals(digest)
                        && durable.revisionBefore() == revisionBefore;
            }
        }
        return false;
    }

    /**
     * Persists an execution state transition.
     *
     * @param requestId     request identity
     * @param state         terminal or in-progress state
     * @param revisionAfter lifecycle revision after execution, when known
     * @param result        bounded result or reference
     * @throws IOException when the durable write fails
     */
    public synchronized void complete(UUID requestId, State state, long revisionAfter, String result)
            throws IOException {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(state, "state");
        Entry prior = entries.get(requestId);
        if (prior == null) {
            throw new IOException("unknown lifecycle idempotency entry");
        }
        if (revisionAfter < 0) {
            throw new IllegalArgumentException("revisionAfter must not be negative");
        }
        long now = System.currentTimeMillis();
        Entry updated = new Entry(prior.hostInstanceId(), prior.projectId(), prior.requestId(), prior.operation(),
                prior.classification(), prior.digest(), prior.bindingSessionId(), prior.expectedRevision(),
                prior.laneId(), prior.laneEpoch(), prior.threadId(), prior.turnId(), prior.startedAtEpochMillis(),
                prior.deadlineEpochMillis(), state, prior.revisionBefore(), revisionAfter, result,
                state == State.COMPLETED || state == State.FAILED || state == State.AMBIGUOUS ? now : 0L,
                prior.expiryEpochMillis());
        entries.put(requestId, updated);
        try {
            persist();
        } catch (IOException failure) {
            resultStorageFailures++;
            entries.put(requestId, prior);
            throw failure;
        }
    }

    /**
     * Returns a request entry without changing it.
     *
     * @param requestId request identity
     * @return entry when known
     */
    public synchronized java.util.Optional<Entry> find(UUID requestId) {
        return java.util.Optional.ofNullable(entries.get(requestId));
    }

    /**
     * Returns immutable current entries.
     *
     * @return current entries
     */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /**
     * Returns the number of deterministic evictions since opening.
     *
     * @return eviction count
     */
    public synchronized long evictionCount() {
        return evictions;
    }

    /**
     * Returns the number of digest conflicts observed.
     *
     * @return conflict count
     */
    public synchronized long conflictCount() {
        return conflicts;
    }

    /**
     * Returns the number of failed initial durable writes.
     *
     * @return initial persistence failures
     */
    public synchronized long initialPersistenceFailureCount() {
        return initialPersistenceFailures;
    }

    /**
     * Returns the number of failed terminal/result writes.
     *
     * @return result storage failures
     */
    public synchronized long resultStorageFailureCount() {
        return resultStorageFailures;
    }

    /**
     * Returns the logical ledger file path.
     *
     * @return ledger file
     */
    public Path file() {
        return file;
    }

    private Disposition disposition(Entry entry) {
        return switch (entry.state()) {
            case ACCEPTED, IN_PROGRESS -> Disposition.IN_PROGRESS;
            case COMPLETED, FAILED -> Disposition.COMPLETED;
            case AMBIGUOUS -> Disposition.AMBIGUOUS;
        };
    }

    private boolean enforceBounds() {
        boolean evicted = false;
        while (entries.size() > MAX_HOST_ENTRIES) {
            UUID candidate = evictionCandidate(null);
            if (candidate == null) {
                break;
            }
            entries.remove(candidate);
            evictions++;
            evicted = true;
        }
        List<String> bindings = entries.values()
                .stream()
                .map(Entry::bindingSessionId)
                .distinct()
                .sorted()
                .toList();
        for (String binding : bindings) {
            while (entries.values()
                    .stream()
                    .filter(item -> item.bindingSessionId()
                            .equals(binding))
                    .count()
                    > MAX_BINDING_ENTRIES) {
                UUID candidate = evictionCandidate(binding);
                if (candidate == null) {
                    break;
                }
                entries.remove(candidate);
                evictions++;
                evicted = true;
            }
        }
        return evicted;
    }

    private UUID evictionCandidate(String binding) {
        return entries.values()
                .stream()
                .filter(item -> binding == null || item.bindingSessionId()
                        .equals(binding))
                .filter(item -> !item.activeOrAmbiguous())
                .sorted(Comparator.comparingLong(Entry::expiryEpochMillis)
                        .thenComparing(item -> item.requestId()
                                .toString()))
                .map(Entry::requestId)
                .findFirst()
                .orElse(null);
    }

    private long bindingCount(String bindingSessionId) {
        return entries.values()
                .stream()
                .filter(item -> item.bindingSessionId()
                        .equals(bindingSessionId))
                .count();
    }

    private boolean hasBindingOverCapacity() {
        return entries.values()
                .stream()
                .map(Entry::bindingSessionId)
                .distinct()
                .anyMatch(binding -> bindingCount(binding) > MAX_BINDING_ENTRIES);
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Object parsed = ProviderJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof List<?> raw)) {
                throw new IOException("idempotency ledger must be an array");
            }
            for (Object item : raw) {
                Map<String, Object> value = map(item);
                Entry entry = decode(value);
                State state = entry.state();
                if (state == State.ACCEPTED || state == State.IN_PROGRESS) {
                    state = entry.classification() == LifecycleControlRequestEnvelope.Classification.STATE_CHANGING
                            ? State.AMBIGUOUS : State.FAILED;
                    entry = new Entry(entry.hostInstanceId(), entry.projectId(), entry.requestId(), entry.operation(),
                            entry.classification(), entry.digest(), entry.bindingSessionId(), entry.expectedRevision(),
                            entry.laneId(), entry.laneEpoch(), entry.threadId(), entry.turnId(),
                            entry.startedAtEpochMillis(), entry.deadlineEpochMillis(), state, entry.revisionBefore(),
                            entry.revisionAfter(), "owner_restart_ambiguity", entry.completedAtEpochMillis(),
                            entry.expiryEpochMillis());
                }
                if (entry.expiryEpochMillis() >= System.currentTimeMillis() || entry.activeOrAmbiguous()) {
                    entries.put(entry.requestId(), entry);
                }
            }
            enforceBounds();
        } catch (RuntimeException failure) {
            throw new IOException("malformed lifecycle idempotency ledger", failure);
        }
    }

    private void persist() throws IOException {
        durableStore.persist(List.copyOf(entries.values()));
    }

    /**
     * Durable execution states.
     */
    public enum State {
        /**
         * Request was durably accepted but execution has not begun.
         */
        ACCEPTED,
        /**
         * Request execution is in progress.
         */
        IN_PROGRESS,
        /**
         * Request completed with a bounded result.
         */
        COMPLETED,
        /**
         * Request was rejected or failed before mutation.
         */
        FAILED,
        /**
         * Request effect cannot be proven safe to replay.
         */
        AMBIGUOUS
    }

    /**
     * Result of looking up or durably accepting a request.
     */
    public enum Disposition {
        /**
         * A new entry was committed.
         */
        NEW,
        /**
         * An equivalent request is currently executing.
         */
        IN_PROGRESS,
        /**
         * A prior result can be replayed.
         */
        COMPLETED,
        /**
         * The request ID was reused for a different digest.
         */
        CONFLICT,
        /**
         * Prior execution is ambiguous and requires STATUS reconciliation.
         */
        AMBIGUOUS
    }

    /**
     * Persistence seam used by deterministic failure tests.
     */
    @FunctionalInterface
    public interface DurableStore {

        /**
         * Persists the complete bounded entry set atomically.
         *
         * @param entries entries to persist
         * @throws IOException when the durable write cannot be completed
         */
        void persist(List<Entry> entries) throws IOException;
    }

    /**
     * Immutable durable ledger entry.
     *
     * @param hostInstanceId         production host instance
     * @param projectId              project identity
     * @param requestId              lifecycle request ID
     * @param operation              operation name
     * @param classification         read-only or state-changing
     * @param digest                 canonical semantic request digest
     * @param bindingSessionId       exact binding session
     * @param expectedRevision       expected lifecycle revision
     * @param laneId                 exact lane/WorkIntent ID
     * @param laneEpoch              exact lane epoch
     * @param threadId               expected thread identity
     * @param turnId                 expected turn identity
     * @param startedAtEpochMillis   request start timestamp
     * @param deadlineEpochMillis    original caller deadline
     * @param state                  execution state
     * @param revisionBefore         lifecycle revision before execution
     * @param revisionAfter          lifecycle revision after execution, when known
     * @param result                 bounded result or reference
     * @param completedAtEpochMillis completion timestamp, or zero
     * @param expiryEpochMillis      expiry timestamp
     */
    public record Entry(String hostInstanceId, String projectId, UUID requestId, String operation,
                        LifecycleControlRequestEnvelope.Classification classification, String digest,
                        String bindingSessionId,
                        long expectedRevision, String laneId, long laneEpoch, String threadId, String turnId,
                        long startedAtEpochMillis, long deadlineEpochMillis, State state, long revisionBefore,
                        long revisionAfter, String result, long completedAtEpochMillis, long expiryEpochMillis) {

        /**
         * Validates the durable entry shape.
         */
        public Entry {
            require(hostInstanceId, "hostInstanceId");
            require(projectId, "projectId");
            Objects.requireNonNull(requestId, "requestId");
            require(operation, "operation");
            Objects.requireNonNull(classification, "classification");
            require(digest, "digest");
            require(bindingSessionId, "bindingSessionId");
            require(laneId, "laneId");
            Objects.requireNonNull(state, "state");
            if (expectedRevision < 0 || laneEpoch < 1 || startedAtEpochMillis <= 0
                    || deadlineEpochMillis <= 0 || revisionBefore < 0 || revisionAfter < 0
                    || expiryEpochMillis <= 0) {
                throw new IllegalArgumentException("invalid idempotency entry bounds");
            }
            if (result != null && result.length() > 16_384) {
                throw new IllegalArgumentException("idempotency result exceeds bound");
            }
        }

        private static void require(String value, String label) {
            if (value == null || value.isBlank() || value.length() > LifecycleControlRequestEnvelope.MAX_TEXT_BYTES) {
                throw new IllegalArgumentException(label + " invalid");
            }
        }

        /**
         * Returns whether this entry cannot be evicted safely.
         *
         * @return active or ambiguous state
         */
        public boolean activeOrAmbiguous() {
            return state == State.ACCEPTED || state == State.IN_PROGRESS || state == State.AMBIGUOUS;
        }
    }

    /**
     * Result returned after a prepare operation.
     *
     * @param disposition duplicate or new request disposition
     * @param entry       durable entry
     * @param evicted     whether a completed entry was deterministically evicted
     */
    public record PrepareResult(Disposition disposition, Entry entry, boolean evicted) {

        /**
         * Validates result state.
         */
        public PrepareResult {
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(entry, "entry");
        }
    }

    /**
     * Stable digest conflict raised before lifecycle execution.
     */
    public static final class IdempotencyConflictException extends IOException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /**
         * Creates a stable digest conflict exception.
         *
         * @param diagnostic stable conflict diagnostic
         */
        public IdempotencyConflictException(String diagnostic) {
            super(diagnostic);
        }
    }

    /** Persists idempotency entries in the lifecycle's bounded file store. */
    @SuppressWarnings({"ClassCanBeRecord", "DuplicatedCode"})
    private static final class FileDurableStore implements DurableStore {

        private final Path file;

        private FileDurableStore(Path file) {
            this.file = file.toAbsolutePath()
                    .normalize();
        }

        @Override
        public synchronized void persist(List<Entry> entries) throws IOException {
            Files.createDirectories(file.getParent());
            List<Map<String, Object>> encoded = new ArrayList<>();
            for (Entry entry : entries) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("hostInstanceId", entry.hostInstanceId());
                value.put("projectId", entry.projectId());
                value.put("requestId",
                        entry.requestId()
                                .toString());
                value.put("operation", entry.operation());
                value.put("classification",
                        entry.classification()
                                .name());
                value.put("digest", entry.digest());
                value.put("bindingSessionId", entry.bindingSessionId());
                value.put("expectedRevision", entry.expectedRevision());
                value.put("laneId", entry.laneId());
                value.put("laneEpoch", entry.laneEpoch());
                value.put("threadId", entry.threadId());
                value.put("turnId", entry.turnId());
                value.put("startedAtEpochMillis", entry.startedAtEpochMillis());
                value.put("deadlineEpochMillis", entry.deadlineEpochMillis());
                value.put("state",
                        entry.state()
                                .name());
                value.put("revisionBefore", entry.revisionBefore());
                value.put("revisionAfter", entry.revisionAfter());
                value.put("result", entry.result());
                value.put("completedAtEpochMillis", entry.completedAtEpochMillis());
                value.put("expiryEpochMillis", entry.expiryEpochMillis());
                encoded.add(value);
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, ProviderJson.write(encoded) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
