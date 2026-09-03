package org.synesis.workspace.application.provider.continuity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Atomic durable store for one managed attachment record.
 *
 * <p>Unknown or removed formats are rejected loudly. The store never accepts
 * raw attachment proof as durable input.</p>
 */
public final class ManagedAttachmentStore {

    private final Path file;

    /**
     * Creates a store at the supplied adapter-private path.
     *
     * @param file record file
     */
    public ManagedAttachmentStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    /**
     * Reads the record, or returns empty when it has not been issued.
     *
     * @return optional durable record
     * @throws IOException when the format is invalid or unreadable
     */
    public synchronized java.util.Optional<ManagedAttachmentRecord> read() throws IOException {
        if (!Files.isRegularFile(file)) {
            return java.util.Optional.empty();
        }
        try {
            Object parsed = ProviderJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> raw)) {
                throw new IOException("managed attachment record must be an object");
            }
            Map<String, Object> value = new LinkedHashMap<>();
            raw.forEach((key, item) -> value.put(String.valueOf(key), item));
            return java.util.Optional.of(new ManagedAttachmentRecord(
                    number(value, "schemaVersion").intValue(), text(value, "projectId"),
                    text(value, "provider"), ProviderContinuityMode.valueOf(text(value, "mode")),
                    text(value, "bindingSessionId"), text(value, "threadId"),
                    number(value, "generation").longValue(), text(value, "proofHash"),
                    text(value, "runtimeHomeId"), ManagedAttachmentRecord.Status.valueOf(text(value, "status")),
                    number(value, "revision").longValue(), number(value, "updatedAtEpochMillis").longValue()));
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
     * Replaces the record only if its generation and proof hash still match.
     * This is the cross-process one-winner fence for reattachment races.
     *
     * @param expectedGeneration current generation
     * @param expectedProofHash current proof hash
     * @param replacement replacement record
     * @return false when another replacement won the race
     * @throws IOException when persistence fails
     */
    public synchronized boolean compareAndReplace(long expectedGeneration, String expectedProofHash,
            ManagedAttachmentRecord replacement) throws IOException {
        Objects.requireNonNull(expectedProofHash, "expectedProofHash");
        Objects.requireNonNull(replacement, "replacement");
        final boolean[] replaced = {false};
        withLock(() -> {
            ManagedAttachmentRecord current = read().orElse(null);
            if (current == null || current.generation() != expectedGeneration
                    || !current.proofHash().equals(expectedProofHash)) {
                return;
            }
            writeUnlocked(replacement);
            replaced[0] = true;
        });
        return replaced[0];
    }

    private void writeUnlocked(ManagedAttachmentRecord record) throws IOException {
        ManagedAttachmentRecord prior = read().orElse(null);
        if (prior != null && (!prior.projectId().equals(record.projectId())
                || !prior.bindingSessionId().equals(record.bindingSessionId())
                || record.revision() <= prior.revision()
                || record.generation() < prior.generation())) {
            throw new IOException("managed attachment revision or identity regression");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", record.schemaVersion());
        value.put("projectId", record.projectId());
        value.put("provider", record.provider());
        value.put("mode", record.mode().name());
        value.put("bindingSessionId", record.bindingSessionId());
        value.put("threadId", record.threadId());
        value.put("generation", record.generation());
        value.put("proofHash", record.proofHash());
        value.put("runtimeHomeId", record.runtimeHomeId());
        value.put("status", record.status().name());
        value.put("revision", record.revision());
        value.put("updatedAtEpochMillis", record.updatedAtEpochMillis());
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, ProviderJson.write(value) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
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
    public Path file() {
        return file;
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

    @FunctionalInterface
    private interface LockedOperation {
        void run() throws IOException;
    }
}
