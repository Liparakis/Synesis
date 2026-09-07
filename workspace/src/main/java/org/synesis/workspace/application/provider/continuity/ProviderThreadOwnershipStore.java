package org.synesis.workspace.application.provider.continuity;

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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Atomic local store for unique active provider-thread ownership.
 *
 * <p>All records for one project share one lock, so the acquire read-and-write
 * sequence is atomic across binding sessions and JVMs. Released records remain
 * as tombstones and cannot be casually reused.</p>
 */
public final class ProviderThreadOwnershipStore {

    private static final String DIRECTORY = "provider-thread-ownership";
    private final Path directory;
    private final Path lockFile;

    /**
     * Creates an ownership store rooted at the supplied directory.
     *
     * @param directory ownership record directory
     */
    public ProviderThreadOwnershipStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
        this.lockFile = this.directory.resolve("ownership.lock");
    }

    /**
     * Returns the conventional project-local ownership store.
     *
     * @param location initialized project location
     * @return ownership store
     */
    public static ProviderThreadOwnershipStore storeFor(ProjectApplicationService.ProjectLocation location) {
        Objects.requireNonNull(location, "location");
        return new ProviderThreadOwnershipStore(location.synesisDirectory()
                .resolve("local/runtime")
                .resolve(DIRECTORY));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.length() > 8_192) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private static void requireProvider(String value) {
        requireText(value, "provider");
        if (!value.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("provider is invalid");
        }
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

    private static boolean optionalBoolean(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (item == null) {
            return false;
        }
        if (!(item instanceof Boolean flag)) {
            throw new IllegalArgumentException("invalid " + key);
        }
        return flag;
    }

    /**
     * Atomically claims a provider thread for a binding.
     *
     * @param projectId        project identity
     * @param provider         provider identifier
     * @param providerThreadId exact provider thread
     * @param bindingSessionId existing binding session
     * @return newly active ownership record
     * @throws IOException when a durable conflict or malformed record exists
     */
    public synchronized ProviderThreadOwnershipRecord acquire(String projectId, String provider,
            String providerThreadId, String bindingSessionId) throws IOException {
        requireText(projectId, "projectId");
        requireProvider(provider);
        requireText(providerThreadId, "providerThreadId");
        requireText(bindingSessionId, "bindingSessionId");
        final ProviderThreadOwnershipRecord[] result = {null};
        withLock(() -> {
            Path path = pathFor(provider, providerThreadId);
            Optional<ProviderThreadOwnershipRecord> existing = readUnlocked(path);
            if (existing.isPresent()) {
                ProviderThreadOwnershipRecord owner = existing.get();
                if (!owner.projectId()
                        .equals(projectId) || !owner.provider()
                        .equals(provider)
                        || !owner.providerThreadId()
                        .equals(providerThreadId)) {
                    throw new IOException("provider_thread_ownership_key_mismatch");
                }
                if (owner.status() == ProviderThreadOwnershipRecord.Status.ACTIVE
                        && owner.bindingSessionId()
                        .equals(bindingSessionId)) {
                    result[0] = owner;
                    return;
                }
                throw new IOException("provider_thread_owned_by_another_binding");
            }
            long now = System.currentTimeMillis();
            ProviderThreadOwnershipRecord record = new ProviderThreadOwnershipRecord(
                    ProviderThreadOwnershipRecord.CURRENT_SCHEMA_VERSION, projectId, provider,
                    providerThreadId, bindingSessionId, ProviderThreadOwnershipRecord.Status.ACTIVE,
                    false, 1L, now, now);
            writeUnlocked(path, record);
            result[0] = record;
        });
        return result[0];
    }

    /**
     * Marks one exact owned provider thread cold-resume eligible.
     *
     * <p>The transition is durable and idempotent. It is intentionally keyed
     * by provider and thread rather than attachment generation, so a later
     * lawful generation inherits the established provider fact.</p>
     *
     * @param projectId        project identity
     * @param provider         canonical provider identifier
     * @param providerThreadId exact provider thread
     * @param bindingSessionId exact owning binding
     * @return current persisted owner
     * @throws IOException when ownership is missing, stale, or mismatched
     */
    public synchronized ProviderThreadOwnershipRecord markPersistenceReady(String projectId, String provider,
            String providerThreadId, String bindingSessionId) throws IOException {
        requireText(projectId, "projectId");
        requireProvider(provider);
        requireText(providerThreadId, "providerThreadId");
        requireText(bindingSessionId, "bindingSessionId");
        final ProviderThreadOwnershipRecord[] result = {null};
        withLock(() -> {
            Path path = pathFor(provider, providerThreadId);
            ProviderThreadOwnershipRecord prior = readUnlocked(path)
                    .orElseThrow(() -> new IOException("provider_thread_ownership_missing"));
            if (prior.status() != ProviderThreadOwnershipRecord.Status.ACTIVE
                    || !prior.provider()
                    .equals(provider)
                    || !prior.projectId()
                    .equals(projectId)
                    || !prior.bindingSessionId()
                    .equals(bindingSessionId)) {
                throw new IOException("provider_thread_persistence_scope_rejected");
            }
            if (prior.persistenceReady()) {
                result[0] = prior;
                return;
            }
            ProviderThreadOwnershipRecord next = new ProviderThreadOwnershipRecord(
                    ProviderThreadOwnershipRecord.CURRENT_SCHEMA_VERSION, prior.projectId(), prior.provider(),
                    prior.providerThreadId(), prior.bindingSessionId(), prior.status(), true, prior.revision() + 1L,
                    prior.acquiredAtEpochMillis(), System.currentTimeMillis());
            writeUnlocked(path, next);
            result[0] = next;
        });
        return result[0];
    }

    /**
     * Reads exact provider-thread ownership.
     *
     * @param provider         provider identifier
     * @param providerThreadId exact provider thread
     * @return ownership record when present
     * @throws IOException when the record is malformed
     */
    public synchronized Optional<ProviderThreadOwnershipRecord> find(String provider, String providerThreadId)
            throws IOException {
        return readUnlocked(pathFor(provider, providerThreadId));
    }

    /**
     * Finds the active provider thread owned by one exact binding.
     *
     * @param provider         provider identifier
     * @param bindingSessionId existing binding session
     * @return active ownership, or empty
     * @throws IOException when a record is malformed
     */
    public synchronized Optional<ProviderThreadOwnershipRecord> findByBinding(String provider,
            String bindingSessionId) throws IOException {
        requireProvider(provider);
        requireText(bindingSessionId, "bindingSessionId");
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(item -> item.getFileName()
                            .toString()
                            .endsWith(".json"))
                    .toList()) {
                ProviderThreadOwnershipRecord record = readUnlocked(path).orElse(null);
                if (record != null && record.status() == ProviderThreadOwnershipRecord.Status.ACTIVE
                        && record.provider()
                        .equals(provider)
                        && record.bindingSessionId()
                        .equals(bindingSessionId)) {
                    return Optional.of(record);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Releases ownership only for the exact active owner.
     *
     * @param provider         provider identifier
     * @param providerThreadId exact provider thread
     * @param bindingSessionId expected owner binding
     * @return released record
     * @throws IOException when ownership is missing, stale, or belongs elsewhere
     */
    public synchronized ProviderThreadOwnershipRecord release(String provider, String providerThreadId,
            String bindingSessionId) throws IOException {
        final ProviderThreadOwnershipRecord[] result = {null};
        withLock(() -> {
            Path path = pathFor(provider, providerThreadId);
            ProviderThreadOwnershipRecord prior = readUnlocked(path)
                    .orElseThrow(() -> new IOException("provider_thread_ownership_missing"));
            if (prior.status() != ProviderThreadOwnershipRecord.Status.ACTIVE
                    || !prior.bindingSessionId()
                    .equals(bindingSessionId)) {
                throw new IOException("provider_thread_release_rejected");
            }
            ProviderThreadOwnershipRecord next = new ProviderThreadOwnershipRecord(prior.schemaVersion(),
                    prior.projectId(), prior.provider(), prior.providerThreadId(), prior.bindingSessionId(),
                    ProviderThreadOwnershipRecord.Status.RELEASED, prior.persistenceReady(), prior.revision() + 1L,
                    prior.acquiredAtEpochMillis(), System.currentTimeMillis());
            writeUnlocked(path, next);
            result[0] = next;
        });
        return result[0];
    }

    /**
     * Returns the ownership record directory.
     *
     * @return ownership record directory
     */
    public Path directory() {
        return directory;
    }

    private Path pathFor(String provider, String providerThreadId) {
        requireProvider(provider);
        requireText(providerThreadId, "providerThreadId");
        return directory.resolve(provider + "-" + digest(providerThreadId) + ".json");
    }

    private Optional<ProviderThreadOwnershipRecord> readUnlocked(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            Object parsed = ProviderJson.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> raw)) {
                throw new IOException("provider-thread ownership must be an object");
            }
            Map<String, Object> value = new LinkedHashMap<>();
            raw.forEach((key, item) -> value.put(String.valueOf(key), item));
            return Optional.of(new ProviderThreadOwnershipRecord(
                    number(value, "schemaVersion").intValue(), text(value, "projectId"),
                    text(value, "provider"), text(value, "providerThreadId"),
                    text(value, "bindingSessionId"),
                    ProviderThreadOwnershipRecord.Status.valueOf(text(value, "status")),
                    optionalBoolean(value, "persistenceReady"),
                    number(value, "revision").longValue(), number(value, "acquiredAtEpochMillis").longValue(),
                    number(value, "updatedAtEpochMillis").longValue()));
        } catch (RuntimeException failure) {
            throw new IOException("malformed provider-thread ownership", failure);
        }
    }

    private void writeUnlocked(Path path, ProviderThreadOwnershipRecord record) throws IOException {
        Files.createDirectories(directory);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", record.schemaVersion());
        value.put("projectId", record.projectId());
        value.put("provider", record.provider());
        value.put("providerThreadId", record.providerThreadId());
        value.put("bindingSessionId", record.bindingSessionId());
        value.put("status",
                record.status()
                        .name());
        value.put("persistenceReady", record.persistenceReady());
        value.put("revision", record.revision());
        value.put("acquiredAtEpochMillis", record.acquiredAtEpochMillis());
        value.put("updatedAtEpochMillis", record.updatedAtEpochMillis());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, ProviderJson.write(value) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void withLock(LockedOperation operation) throws IOException {
        Files.createDirectories(directory);
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.lock();
            } catch (OverlappingFileLockException failure) {
                throw new IOException("provider_thread_ownership_update_already_active", failure);
            }
            try (lock) {
                operation.run();
            }
        }
    }

    @FunctionalInterface
    private interface LockedOperation {

        void run() throws IOException;
    }
}
