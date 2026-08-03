package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Atomic durable store for authoritative Codex lifecycle checkpoints.
 *
 * <p>The store is intentionally independent of evidence persistence. A
 * checkpoint is written synchronously whenever an authoritative lifecycle
 * transition occurs, so evidence backpressure cannot erase terminal state.
 * Instances are thread-safe for one project host. The file is local project
 * state and contains no credentials or private key material.
 *
 * @since 1.0
 */
public final class CodexLifecycleStateStore {

    /** Durable lifecycle status values. */
    public enum State {
        /** No attachment has been started for the binding. */
        NEW,
        /** App Server process is starting or initializing. */
        STARTING,
        /** Attachment is initialized and has no active turn. */
        IDLE,
        /** A turn is active. */
        RUNNING,
        /** An interrupt has been requested and awaits terminal completion. */
        INTERRUPTING,
        /** The exact turn completed as interrupted. */
        INTERRUPTED,
        /** The exact turn completed normally. */
        COMPLETED,
        /** Lifecycle or protocol failure is authoritative. */
        FAILED,
        /** User/provider interaction is required before continuation. */
        INTERACTION_REQUIRED,
        /** Attachment was stopped cleanly. */
        STOPPED,
        /** A state-changing operation has an unproven outcome. */
        AMBIGUOUS
    }

    /**
     * Immutable lifecycle checkpoint.
     *
     * @param bindingSessionId exact provider binding session
     * @param projectId project identity
     * @param provider provider ID
     * @param revision monotonic lifecycle revision
     * @param state authoritative state
     * @param ownerHostInstanceId owner instance identity
     * @param attachmentGeneration process attachment generation
     * @param connectionGeneration App Server connection generation
     * @param rootPid owned App Server root PID, or {@code -1}
     * @param rootStartEpochMillis verified process start instant
     * @param rootExecutable executable identity
     * @param rootCommandIdentity bounded command identity
     * @param threadId exact thread identity, or {@code null}
     * @param turnId exact active/last turn identity, or {@code null}
     * @param terminalDiagnostic bounded terminal diagnostic, or {@code null}
     * @param evidenceComplete whether detailed persisted evidence is complete
     * @param updatedAtEpochMillis checkpoint timestamp
     */
    public record Checkpoint(String bindingSessionId, String projectId, String provider, long revision,
            State state, String ownerHostInstanceId, long attachmentGeneration, long connectionGeneration,
            long rootPid, long rootStartEpochMillis, String rootExecutable, String rootCommandIdentity,
            String threadId, String turnId, String terminalDiagnostic, boolean evidenceComplete,
            long updatedAtEpochMillis) {
        /** Validates and freezes checkpoint state. */
        public Checkpoint {
            require(bindingSessionId, "bindingSessionId");
            require(projectId, "projectId");
            require(provider, "provider");
            Objects.requireNonNull(state, "state");
            require(ownerHostInstanceId, "ownerHostInstanceId");
            require(rootExecutable, "rootExecutable");
            require(rootCommandIdentity, "rootCommandIdentity");
            if (revision < 0 || attachmentGeneration < 0 || connectionGeneration < 0
                    || updatedAtEpochMillis <= 0) {
                throw new IllegalArgumentException("invalid lifecycle checkpoint revision or timestamp");
            }
        }
    }

    private final Path root;

    /**
     * Creates a store rooted at one project-local runtime directory.
     *
     * @param runtimeDirectory directory for lifecycle checkpoints
     */
    public CodexLifecycleStateStore(Path runtimeDirectory) {
        this.root = Objects.requireNonNull(runtimeDirectory, "runtimeDirectory").toAbsolutePath().normalize();
    }

    /**
     * Reads a binding checkpoint, returning a revision-zero NEW state when absent.
     *
     * @param bindingSessionId exact binding session
     * @param projectId project identity
     * @return durable checkpoint
     * @throws IOException when state is malformed or unreadable
     */
    public synchronized Checkpoint read(String bindingSessionId, String projectId) throws IOException {
        require(bindingSessionId, "bindingSessionId");
        require(projectId, "projectId");
        Path file = file(bindingSessionId);
        if (!Files.isRegularFile(file)) {
            return new Checkpoint(bindingSessionId, projectId, "codex", 0L, State.NEW, "unowned", 0L, 0L,
                    -1L, 0L, "none", "none", null, null, null, true, System.currentTimeMillis());
        }
        try {
            Object parsed = ProviderJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> raw)) {
                throw new IOException("lifecycle checkpoint must be an object");
            }
            Map<String, Object> value = new LinkedHashMap<>();
            raw.forEach((key, item) -> value.put(String.valueOf(key), item));
            Checkpoint checkpoint = new Checkpoint(
                    text(value, "bindingSessionId"), text(value, "projectId"), text(value, "provider"),
                    number(value, "revision"), State.valueOf(text(value, "state")),
                    text(value, "ownerHostInstanceId"), number(value, "attachmentGeneration"),
                    number(value, "connectionGeneration"), number(value, "rootPid"),
                    number(value, "rootStartEpochMillis"), text(value, "rootExecutable"),
                    text(value, "rootCommandIdentity"), optional(value, "threadId"), optional(value, "turnId"),
                    optional(value, "terminalDiagnostic"), Boolean.TRUE.equals(value.get("evidenceComplete")),
                    number(value, "updatedAtEpochMillis"));
            if (!bindingSessionId.equals(checkpoint.bindingSessionId())
                    || !projectId.equals(checkpoint.projectId())) {
                throw new IOException("lifecycle checkpoint identity mismatch");
            }
            return checkpoint;
        } catch (RuntimeException failure) {
            throw new IOException("malformed lifecycle checkpoint", failure);
        }
    }

    /**
     * Reads a checkpoint for shutdown diagnostics and returns a failed
     * fallback when the durable file is unavailable.
     *
     * @param bindingSessionId exact binding session
     * @param projectId project identity
     * @return checkpoint or bounded failed fallback
     */
    public synchronized Checkpoint readUnchecked(String bindingSessionId, String projectId) {
        try {
            return read(bindingSessionId, projectId);
        } catch (IOException failure) {
            return new Checkpoint(bindingSessionId, projectId, "codex", 0L, State.FAILED, "unknown", 0L, 0L,
                    -1L, 0L, "none", "none", null, null, "checkpoint_unreadable", false,
                    System.currentTimeMillis());
        }
    }

    /**
     * Atomically writes a checkpoint and rejects revision regression.
     *
     * @param checkpoint checkpoint to persist
     * @throws IOException when persistence fails or revision regresses
     */
    public synchronized void write(Checkpoint checkpoint) throws IOException {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Checkpoint prior = read(checkpoint.bindingSessionId(), checkpoint.projectId());
        if (checkpoint.revision() < prior.revision()) {
            throw new IOException("lifecycle revision regression");
        }
        Files.createDirectories(root);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("bindingSessionId", checkpoint.bindingSessionId());
        value.put("projectId", checkpoint.projectId());
        value.put("provider", checkpoint.provider());
        value.put("revision", checkpoint.revision());
        value.put("state", checkpoint.state().name());
        value.put("ownerHostInstanceId", checkpoint.ownerHostInstanceId());
        value.put("attachmentGeneration", checkpoint.attachmentGeneration());
        value.put("connectionGeneration", checkpoint.connectionGeneration());
        value.put("rootPid", checkpoint.rootPid());
        value.put("rootStartEpochMillis", checkpoint.rootStartEpochMillis());
        value.put("rootExecutable", checkpoint.rootExecutable());
        value.put("rootCommandIdentity", checkpoint.rootCommandIdentity());
        value.put("threadId", checkpoint.threadId());
        value.put("turnId", checkpoint.turnId());
        value.put("terminalDiagnostic", checkpoint.terminalDiagnostic());
        value.put("evidenceComplete", checkpoint.evidenceComplete());
        value.put("updatedAtEpochMillis", checkpoint.updatedAtEpochMillis());
        Path temporary = root.resolve(checkpoint.bindingSessionId() + ".json.tmp");
        Files.writeString(temporary, ProviderJson.write(value) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, file(checkpoint.bindingSessionId()), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file(checkpoint.bindingSessionId()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Returns the directory containing checkpoint files.
     *
     * @return checkpoint root
     */
    public Path root() {
        return root;
    }

    private Path file(String bindingSessionId) {
        return root.resolve(bindingSessionId + ".json");
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

    private static void require(String value, String label) {
        if (value == null || value.isBlank() || value.length() > LifecycleControlRequestEnvelope.MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(label + " invalid");
        }
    }
}
