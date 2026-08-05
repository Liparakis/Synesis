package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.DirectoryStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;

/** Owns the bounded host-wide command namespace and its permanent lock objects. */
public final class ProjectCommandNamespace implements AutoCloseable {

    /** Maximum serialized namespace index size in bytes. */
    public static final int MAX_INDEX_BYTES = 16 * 1024 * 1024;
    /** Maximum number of indexed scope/anchor entries. */
    public static final int MAX_INDEX_ENTRIES = 20_480;
    /** Maximum physical-worktree scope directories. */
    public static final int MAX_SCOPES = 4_096;
    /** Maximum process-anchor directories. */
    public static final int MAX_ANCHORS = 16_384;
    /** Maximum permanent worktree lock files. */
    public static final int MAX_LOCKS = 65_536;
    /** Maximum aliases retained for one physical scope. */
    public static final int MAX_SCOPE_ALIASES = 32;
    /** Maximum request records and reservations per scope. */
    public static final int MAX_RECORDS_PER_SCOPE = 65_536;
    /** Maximum serialized scope metadata size in bytes. */
    public static final int MAX_SCOPE_BYTES = 256 * 1024;
    /** Maximum serialized anchor metadata size in bytes. */
    public static final int MAX_ANCHOR_BYTES = 128 * 1024;
    /** Maximum path identity size in UTF-8 bytes. */
    public static final int MAX_PATH_IDENTITY_BYTES = 32 * 1024;
    /** Maximum namespace entries inspected during one reconciliation pass. */
    public static final int MAX_INSPECTED_ENTRIES = 131_072;
    /** Maximum temporary/stale artifacts inspected during one pass. */
    public static final int MAX_TEMPORARY_ENTRIES = 256;
    /** Maximum complete namespace reconciliation retries. */
    public static final int MAX_RETRIES = 3;

    private final Path root;
    private final Path indexPath;
    private final Path processScopesPath;
    private final Path locksPath;
    private final Path scopesPath;
    private final CommandPermanentLock namespaceLock;

    private ProjectCommandNamespace(Path root, CommandPermanentLock namespaceLock) throws IOException {
        this.root = root;
        this.indexPath = root.resolve("namespace.json");
        this.processScopesPath = root.resolve("process-scopes");
        this.locksPath = root.resolve("locks");
        this.scopesPath = root.resolve("scopes");
        this.namespaceLock = namespaceLock;
        if (!Files.exists(indexPath)) {
            writeIndex(Map.of("scopeCount", 0L, "anchorCount", 0L));
        }
    }

    /** Creates the namespace skeleton and acquires the permanent namespace lock.
     * @param root host-wide command namespace root
     * @return open namespace with its OS lock held
     * @throws IOException if namespace creation or locking fails
     */
    public static ProjectCommandNamespace open(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        Path normalized = root.toAbsolutePath().normalize();
        Files.createDirectories(normalized.resolve("process-scopes"));
        Files.createDirectories(normalized.resolve("locks"));
        Files.createDirectories(normalized.resolve("scopes"));
        return new ProjectCommandNamespace(normalized,
                CommandPermanentLock.open(normalized.resolve("namespace.lock")));
    }

    /** Returns the namespace root.
     * @return normalized namespace root
     */
    public Path root() {
        return root;
    }

    /** Returns the permanent namespace lock path.
     * @return permanent lock path
     */
    public Path namespaceLockPath() {
        return root.resolve("namespace.lock");
    }

    /** Returns the process-scope directory root.
     * @return process-scope root
     */
    public Path processScopesPath() {
        return processScopesPath;
    }

    /** Returns the physical-worktree scope directory root.
     * @return scope root
     */
    public Path scopesPath() {
        return scopesPath;
    }

    /** Creates one permanent worktree lock object and returns its path.
     * @param worktreeLocator bounded physical-worktree locator
     * @return permanent lock path
     * @throws IOException if the locator or lock object is invalid
     */
    public Path worktreeLockPath(String worktreeLocator) throws IOException {
        String safe = boundedLocator(worktreeLocator);
        Path path = locksPath.resolve(safe + ".lock");
        if (Files.exists(path) && (!Files.isRegularFile(path) || Files.isSymbolicLink(path))) {
            throw new IOException("COMMAND_LOCK_OBJECT_INVALID");
        }
        if (!Files.exists(path)) {
            if (countEntries(locksPath, MAX_LOCKS) >= MAX_LOCKS) {
                throw new IOException("COMMAND_LOCK_CAPACITY_EXCEEDED");
            }
            Files.createFile(path);
        }
        return path;
    }

    /** Acquires one published permanent worktree lock object.
     * @param worktreeLocator bounded physical-worktree locator
     * @return held permanent lock
     * @throws IOException if the lock cannot be acquired
     */
    public CommandPermanentLock openWorktreeLock(String worktreeLocator) throws IOException {
        return CommandPermanentLock.open(worktreeLockPath(worktreeLocator));
    }

    /** Publishes verified scope metadata and creates its bounded record directories.
     * @param identity verified physical-worktree identity
     * @return scope directory
     * @throws IOException if scope metadata cannot be persisted
     */
    public Path publishScope(PhysicalWorktreeIdentity identity) throws IOException {
        Objects.requireNonNull(identity, "identity");
        validatePathIdentity(identity.realPath());
        String locator = boundedLocator(identity.locator());
        Path scope = scopesPath.resolve(locator);
        if (!Files.exists(scope) && countEntries(scopesPath, MAX_SCOPES) >= MAX_SCOPES) {
            throw new IOException("COMMAND_SCOPE_CAPACITY_EXCEEDED");
        }
        Files.createDirectories(scope.resolve("records"));
        Files.createDirectories(scope.resolve("reservations"));
        Files.createDirectories(scope.resolve("replay"));
        Files.createDirectories(scope.resolve("audit"));
        worktreeLockPath(locator);
        Path metadataPath = scope.resolve("scope.json");
        if (Files.exists(metadataPath)) {
            Map<String, Object> existing = readDurable(metadataPath, MAX_SCOPE_BYTES);
            if (number(existing, "schemaVersion") != CommandDurableFormat.SCHEMA_VERSION) {
                throw new CommandFormatException("COMMAND_FORMAT_MIGRATION_REQUIRED");
            }
            if (!locator.equals(existing.get("worktreeLocator"))
                    || !(existing.get("realPath") instanceof String path)
                    || !Files.isSameFile(identity.realPath(), Path.of(path))) {
                throw new CommandFormatException("COMMAND_SCOPE_IDENTITY_CHANGED");
            }
            return scope;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("worktreeLocator", locator);
        metadata.put("realPath", identity.realPath().toString());
        metadata.put("lockLocator", locksPath.relativize(locksPath.resolve(locator + ".lock")).toString());
        metadata.put("objectRevision", 1L);
        writeDurable(metadataPath, metadata, MAX_SCOPE_BYTES);
        return scope;
    }

    /** Persists one immutable process anchor under its process-scope directory.
     * @param anchor process anchor to persist
     * @return anchor metadata path
     * @throws IOException if persistence fails
     */
    public Path writeAnchor(ProjectCommandProcessAnchor anchor) throws IOException {
        Objects.requireNonNull(anchor, "anchor");
        String locator = boundedLocator(anchor.anchorId());
        Path directory = processScopesPath.resolve(locator);
        if (!Files.exists(directory) && countEntries(processScopesPath, MAX_ANCHORS) >= MAX_ANCHORS) {
            throw new IOException("COMMAND_ANCHOR_CAPACITY_EXCEEDED");
        }
        Files.createDirectories(directory);
        Path anchorPath = directory.resolve("anchor.json");
        if (Files.exists(anchorPath)) {
            Map<String, Object> existing = readDurable(anchorPath, MAX_ANCHOR_BYTES);
            if (number(existing, "schemaVersion") != CommandDurableFormat.SCHEMA_VERSION) {
                throw new CommandFormatException("COMMAND_FORMAT_MIGRATION_REQUIRED");
            }
            if (!anchor.equals(readAnchor(anchor.anchorId()))) {
                throw new CommandFormatException("COMMAND_ANCHOR_IDENTITY_CHANGED");
            }
            return anchorPath;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("anchorId", anchor.anchorId());
        metadata.put("scopeLocator", boundedLocator(anchor.scopeLocator()));
        metadata.put("createdAtEpochMillis", anchor.createdAtEpochMillis());
        Map<String, Object> process = new LinkedHashMap<>();
        SessionProcessIdentity identity = anchor.processIdentity();
        process.put("pid", identity.pid());
        process.put("executableIdentity", identity.executableIdentity());
        process.put("commandLine", identity.commandLine());
        process.put("processStartTime", identity.processStartTime());
        process.put("connectionNonce", identity.connectionNonce());
        metadata.put("processIdentity", process);
        writeDurable(anchorPath, metadata, MAX_ANCHOR_BYTES);
        return anchorPath;
    }

    /** Loads and verifies one persisted process anchor without renewing any lease.
     * @param anchorId process anchor ID
     * @return verified process anchor
     * @throws IOException if the anchor is missing, corrupt, or incompatible
     */
    @SuppressWarnings("unchecked")
    public ProjectCommandProcessAnchor readAnchor(String anchorId) throws IOException {
        return readAnchor(root, anchorId);
    }

    /** Reads one anchor without opening or mutating the namespace.
     * @param namespaceRoot host-wide command namespace root
     * @param anchorId process anchor ID
     * @return verified process anchor
     * @throws IOException if the anchor is missing, corrupt, or incompatible
     */
    public static ProjectCommandProcessAnchor readAnchor(Path namespaceRoot, String anchorId) throws IOException {
        Objects.requireNonNull(namespaceRoot, "namespaceRoot");
        Path path = namespaceRoot.toAbsolutePath().normalize().resolve("process-scopes")
                .resolve(boundedLocator(anchorId)).resolve("anchor.json");
        Map<String, Object> value = readDurable(path, MAX_ANCHOR_BYTES);
        if (!anchorId.equals(string(value, "anchorId"))) {
            throw new CommandFormatException("COMMAND_ANCHOR_ID_MISMATCH");
        }
        Map<String, Object> process = map(value, "processIdentity");
        SessionProcessIdentity identity = new SessionProcessIdentity(
                number(process, "pid"), string(process, "executableIdentity"),
                string(process, "commandLine"), number(process, "processStartTime"),
                string(process, "connectionNonce"));
        return new ProjectCommandProcessAnchor(anchorId, string(value, "scopeLocator"), identity,
                number(value, "createdAtEpochMillis"));
    }

    /** Writes the rebuildable index with compatibility and integrity metadata.
     * @param index bounded index fields
     * @throws IOException if limits or atomic persistence fail
     */
    public void writeIndex(Map<String, Object> index) throws IOException {
        Objects.requireNonNull(index, "index");
        Map<String, Object> value = new LinkedHashMap<>(index);
        if (Files.exists(indexPath)) {
            Map<String, Object> current = readIndex();
            long currentSchema = ((Number) current.get("schemaVersion")).longValue();
            long requestedSchema = value.get("schemaVersion") instanceof Number number
                    ? number.longValue() : CommandDurableFormat.SCHEMA_VERSION;
            if (currentSchema < CommandDurableFormat.SCHEMA_VERSION) {
                throw new CommandFormatException("COMMAND_FORMAT_MIGRATION_REQUIRED");
            }
            if (requestedSchema > CommandDurableFormat.SCHEMA_VERSION) {
                throw new CommandFormatException("COMMAND_FORMAT_NEWER_THAN_WRITER");
            }
            long expectedRevision = ((Number) current.getOrDefault("objectRevision", 1L)).longValue() + 1L;
            if (value.containsKey("objectRevision")
                    && ((Number) value.get("objectRevision")).longValue() != expectedRevision) {
                throw new CommandFormatException("COMMAND_NAMESPACE_REVISION_STALE");
            }
            value.put("objectRevision", expectedRevision);
        }
        Map<String, Object> durable = CommandDurableFormat.withIntegrity(value);
        if (countIndexEntries(durable) > MAX_INDEX_ENTRIES) {
            throw new IOException("COMMAND_NAMESPACE_ENTRY_LIMIT");
        }
        String json = ProviderJson.write(durable);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_INDEX_BYTES) {
            throw new IOException("COMMAND_NAMESPACE_SIZE_LIMIT");
        }
        writeAtomically(indexPath, json);
    }

    /** Reads and verifies the rebuildable index without mutating it.
     * @return verified index fields
     * @throws IOException if the index is missing, corrupt, or incompatible
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readIndex() throws IOException {
        if (!Files.isRegularFile(indexPath) || Files.isSymbolicLink(indexPath)) {
            throw new CommandFormatException("COMMAND_NAMESPACE_INDEX_INVALID");
        }
        byte[] bytes = Files.readAllBytes(indexPath);
        if (bytes.length > MAX_INDEX_BYTES) {
            throw new CommandFormatException("COMMAND_NAMESPACE_SIZE_LIMIT");
        }
        try {
            Object parsed = ProviderJson.parse(new String(bytes, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new CommandFormatException("COMMAND_NAMESPACE_INDEX_NOT_OBJECT");
            }
            Map<String, Object> value = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new CommandFormatException("COMMAND_NAMESPACE_INDEX_KEY_INVALID");
                }
                value.put(key, entry.getValue());
            }
            CommandDurableFormat.verify(value);
            return Map.copyOf(value);
        } catch (CommandFormatException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CommandFormatException("COMMAND_NAMESPACE_INDEX_CORRUPT", failure);
        }
    }

    /** Reconciles the rebuildable index after a complete bounded namespace pass.
     * @return verified rebuilt index
     * @throws IOException if an authoritative object is missing, corrupt, duplicated, or over a bound
     */
    public Map<String, Object> reconcileIndex() throws IOException {
        if (!isHeld()) {
            throw new IOException("COMMAND_NAMESPACE_LOCK_REQUIRED");
        }
        int scopes = enumerateDirectories(scopesPath, MAX_SCOPES);
        int anchors = enumerateAnchors(processScopesPath);
        int locks = enumerateLocks(locksPath);
        int temporary = enumerateTemporaryFiles(root);
        if (temporary > MAX_TEMPORARY_ENTRIES) {
            throw new IOException("COMMAND_TEMPORARY_ENTRY_LIMIT");
        }
        Map<String, Object> rebuilt = new LinkedHashMap<>();
        rebuilt.put("scopeCount", (long) scopes);
        rebuilt.put("anchorCount", (long) anchors);
        rebuilt.put("permanentLockCount", (long) locks);
        rebuilt.put("temporaryCount", (long) temporary);
        writeIndex(rebuilt);
        return readIndex();
    }

    /** Returns the permanent namespace lock's current ownership state.
     * @return true while the namespace lock is held
     */
    public boolean isHeld() {
        return namespaceLock.isHeld();
    }

    /** Releases the namespace OS lock while preserving the lock object. */
    @Override
    public void close() throws IOException {
        namespaceLock.close();
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String boundedLocator(String locator) throws IOException {
        Objects.requireNonNull(locator, "locator");
        if (locator.isBlank() || locator.contains("/") || locator.contains("\\")
                || locator.length() > 256) {
            throw new IOException("COMMAND_LOCATOR_INVALID");
        }
        return locator;
    }

    private static void writeDurable(Path target, Map<String, Object> value, int maxBytes) throws IOException {
        String json = ProviderJson.write(CommandDurableFormat.withIntegrity(value));
        if (json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IOException("COMMAND_DURABLE_OBJECT_SIZE_LIMIT");
        }
        writeAtomically(target, json);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readDurable(Path path, int maxBytes) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new CommandFormatException("COMMAND_DURABLE_OBJECT_INVALID");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > maxBytes) {
            throw new CommandFormatException("COMMAND_DURABLE_OBJECT_SIZE_LIMIT");
        }
        try {
            Object parsed = ProviderJson.parse(new String(bytes, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> parsedMap)) {
                throw new CommandFormatException("COMMAND_DURABLE_OBJECT_NOT_OBJECT");
            }
            Map<String, Object> value = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : parsedMap.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new CommandFormatException("COMMAND_DURABLE_OBJECT_KEY_INVALID");
                }
                value.put(key, entry.getValue());
            }
            CommandDurableFormat.verify(value);
            return value;
        } catch (CommandFormatException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CommandFormatException("COMMAND_DURABLE_OBJECT_CORRUPT", failure);
        }
    }

    private static void validatePathIdentity(Path path) throws IOException {
        if (path.toString().getBytes(StandardCharsets.UTF_8).length > MAX_PATH_IDENTITY_BYTES) {
            throw new IOException("WORKTREE_IDENTITY_SIZE_LIMIT");
        }
    }

    private static int countEntries(Path directory, int limit) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path ignored : stream) {
                if (++count > limit) return count;
            }
        }
        return count;
    }

    private static int countIndexEntries(Object value) {
        if (value instanceof Map<?, ?> map) {
            int count = map.size();
            for (Object child : map.values()) count += countIndexEntries(child);
            return count;
        }
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object child : iterable) count += 1 + countIndexEntries(child);
            return count;
        }
        return 0;
    }

    private static int enumerateDirectories(Path directory, int limit) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("COMMAND_NAMESPACE_DIRECTORY_MISSING");
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (++count > limit || !Files.isDirectory(path) || Files.isSymbolicLink(path)) {
                    throw new IOException("COMMAND_NAMESPACE_ENUMERATION_INVALID");
                }
                readDurable(path.resolve("scope.json"), MAX_SCOPE_BYTES);
            }
        }
        return count;
    }

    private static int enumerateAnchors(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("COMMAND_NAMESPACE_DIRECTORY_MISSING");
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (++count > MAX_ANCHORS || !Files.isDirectory(path) || Files.isSymbolicLink(path)) {
                    throw new IOException("COMMAND_NAMESPACE_ENUMERATION_INVALID");
                }
                readAnchor(directory.getParent(), path.getFileName().toString());
            }
        }
        return count;
    }

    private static int enumerateLocks(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("COMMAND_NAMESPACE_DIRECTORY_MISSING");
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.lock")) {
            for (Path path : stream) {
                if (++count > MAX_LOCKS || !Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                    throw new IOException("COMMAND_NAMESPACE_LOCK_OBJECT_INVALID");
                }
            }
        }
        return count;
    }

    private static int enumerateTemporaryFiles(Path directory) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.endsWith(".tmp") || name.contains(".tmp-") || name.contains(".migration-")) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String string(Map<String, Object> value, String key) throws CommandFormatException {
        Object raw = value.get(key);
        if (!(raw instanceof String text)) {
            throw new CommandFormatException("COMMAND_ANCHOR_FIELD_MISSING:" + key);
        }
        return text;
    }

    private static long number(Map<String, Object> value, String key) throws CommandFormatException {
        Object raw = value.get(key);
        if (!(raw instanceof Number number)) {
            throw new CommandFormatException("COMMAND_ANCHOR_FIELD_MISSING:" + key);
        }
        return number.longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> value, String key) throws CommandFormatException {
        Object raw = value.get(key);
        if (!(raw instanceof Map<?, ?> parsed)) {
            throw new CommandFormatException("COMMAND_ANCHOR_FIELD_MISSING:" + key);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : parsed.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new CommandFormatException("COMMAND_ANCHOR_FIELD_INVALID:" + key);
            }
            result.put(name, entry.getValue());
        }
        return result;
    }
}
