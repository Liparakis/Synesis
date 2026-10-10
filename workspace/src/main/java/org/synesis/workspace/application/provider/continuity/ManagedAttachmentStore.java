package org.synesis.workspace.application.provider.continuity;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentRecord.Status;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Atomic durable store for one managed attachment record.
 *
 * <p>Unknown or removed formats are rejected loudly. The store never accepts
 * raw attachment proof as durable input.</p>
 *
 * @param file durable record file location
 */
public record ManagedAttachmentStore(Path file) {

    /**
     * Creates a store at the supplied adapter-private path.
     *
     * @param file record file
     */
    public ManagedAttachmentStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    private static String text(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return text;
    }

    private static Number number(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof Number number)) {
            throw new IllegalArgumentException("missing " + key);
        }
        return number;
    }

    private static String optionalThreadId(Map<String, Object> value) {
        Object item = value.get("threadId");
        if (item == null) {
            return null;
        }
        if (!(item instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("invalid threadId");
        }
        return text;
    }

    private static ManagedAttachmentRecord record(Map<String, Object> value) {
        return new ManagedAttachmentRecord(number(value, "schemaVersion").intValue(),
                text(value, "projectId"),
                text(value, "provider"),
                ProviderContinuityMode.valueOf(text(value, "mode")),
                text(value, "bindingSessionId"),
                optionalThreadId(value),
                number(value, "generation").longValue(),
                text(value, "proofHash"),
                text(value, "runtimeHomeId"),
                Status.valueOf(text(value, "status")),
                number(value, "revision").longValue(),
                number(value, "updatedAtEpochMillis").longValue());
    }

    /**
     * Serializes one typed attachment record into its durable JSON fields.
     */
    private static Map<String, Object> recordFields(ManagedAttachmentRecord record) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", record.schemaVersion());
        fields.put("projectId", record.projectId());
        fields.put("provider", record.provider());
        fields.put("mode", record.mode().name());
        fields.put("bindingSessionId", record.bindingSessionId());
        fields.put("threadId", record.threadId());
        fields.put("generation", record.generation());
        fields.put("proofHash", record.proofHash());
        fields.put("runtimeHomeId", record.runtimeHomeId());
        fields.put("status", record.status().name());
        fields.put("revision", record.revision());
        fields.put("updatedAtEpochMillis", record.updatedAtEpochMillis());
        return fields;
    }

    /**
     * Reads the record, or returns empty when it has not been issued.
     *
     * @return optional durable record
     * @throws IOException when the format is invalid or unreadable
     */
    public synchronized Optional<ManagedAttachmentRecord> read() throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Object parsed = ProviderJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> raw)) {
                throw new IOException("managed attachment record must be an object");
            }
            return Optional.of(record(ProviderJson.stringKeyedObject(raw)));
        } catch (RuntimeException failure) {
            throw new IOException("malformed managed attachment record", failure);
        }
    }

    /**
     * Atomically writes a non-regressing record.
     *
     * @param record record to persist
     * @throws IOException when persistence fails or revision regresses
     */
    public synchronized void write(ManagedAttachmentRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        withLock(() -> writeUnlocked(record));
    }

    /**
     * Replaces the record only if its generation and proof hash still match. This is the
     * cross-process one-winner fence for reattachment races.
     *
     * @param expectedGeneration current generation
     * @param expectedProofHash  current proof hash
     * @param replacement        replacement record
     * @return false when another replacement won the race
     * @throws IOException when persistence fails
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    synchronized boolean compareAndReplace(long expectedGeneration, String expectedProofHash, ManagedAttachmentRecord replacement) throws IOException {
        Objects.requireNonNull(expectedProofHash, "expectedProofHash");
        Objects.requireNonNull(replacement, "replacement");
        final boolean[] replaced = {false};
        withLock(() -> {
            ManagedAttachmentRecord current = read().orElse(null);
            if (current == null || current.generation() != expectedGeneration || !current.proofHash().equals(expectedProofHash)) {
                return;
            }
            writeUnlocked(replacement);
            replaced[0] = true;
        });
        return replaced[0];
    }

    private void writeUnlocked(ManagedAttachmentRecord record) throws IOException {
        ManagedAttachmentRecord prior = read().orElse(null);
        if (prior != null && (!prior.projectId().equals(record.projectId()) || !prior.bindingSessionId().equals(record.bindingSessionId()) || record.revision() <= prior.revision()
                || record.generation() < prior.generation())) {
            throw new IOException("managed attachment revision or identity regression");
        }
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, ProviderJson.write(recordFields(record)) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void withLock(LockedOperation operation) throws IOException {
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        Files.createDirectories(lockPath.getParent());
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.lock();
            } catch (OverlappingFileLockException failure) {
                throw new IOException("managed attachment update already active", failure);
            }
            try (lock) {
                operation.run();
            }
        }
    }

    /**
     * Returns the adapter-private record path.
     *
     * @return record path
     */
    @Override
    public Path file() {
        return file;
    }

    @FunctionalInterface
    private interface LockedOperation {

        void run() throws IOException;
    }
}
