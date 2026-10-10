package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;

/**
 * Owns the bounded host-wide command namespace and its permanent lock objects.
 *
 * <p>The namespace is shared by project command lifecycles, while each scope
 * and process anchor remains separately addressable. Opening it acquires the permanent namespace
 * lock; callers must hold that lock while reconciling the index with scope, anchor, and lock
 * records so a stale index cannot authorize a command by itself.</p>
 */
public final class ProjectCommandNamespace implements AutoCloseable {

    /**
     * Maximum request IDs retained by one live process anchor.
     */
    public static final int MAX_REQUEST_IDS_PER_LIVE_ANCHOR = 8_192;
    /**
     * Maximum serialized namespace index size in bytes.
     */
    static final int MAX_INDEX_BYTES = 16 * 1024 * 1024;
    /**
     * Maximum number of indexed scope/anchor entries.
     */
    static final int MAX_INDEX_ENTRIES = 20_480;
    /**
     * Maximum physical-worktree scope directories.
     */
    static final int MAX_SCOPES = 4_096;
    /**
     * Maximum process-anchor directories.
     */
    static final int MAX_ANCHORS = 16_384;
    /**
     * Maximum permanent worktree lock files.
     */
    static final int MAX_LOCKS = 65_536;
    /**
     * Maximum request records and reservations per scope.
     */
    static final int MAX_RECORDS_PER_SCOPE = 65_536;
    /**
     * Maximum serialized scope metadata size in bytes.
     */
    static final int MAX_SCOPE_BYTES = 256 * 1024;
    /**
     * Maximum serialized anchor metadata size in bytes.
     */
    static final int MAX_ANCHOR_BYTES = 128 * 1024;
    /**
     * Maximum path identity size in UTF-8 bytes.
     */
    static final int MAX_PATH_IDENTITY_BYTES = 32 * 1024;
    /**
     * Maximum namespace entries inspected during one reconciliation pass.
     */
    static final int MAX_INSPECTED_ENTRIES = 131_072;
    /**
     * Maximum temporary/stale artifacts inspected during one pass.
     */
    static final int MAX_TEMPORARY_ENTRIES = 256;

    static final String INDEX_FILE = "namespace.json";
    static final String LOCK_FILE = "namespace.lock";
    static final String PROCESS_SCOPES = "process-scopes";
    static final String LOCKS = "locks";
    static final String SCOPES = "scopes";
    static final String SCOPE_FILE = "scope.json";
    static final String ANCHOR_FILE = "anchor.json";
    static final String RECORDS = "records";
    static final String TERMINAL_HISTORY = "terminal-history";

    /**
     * Failure codes of a scope, anchor, or other durable object read under the namespace lock.
     */
    static final CommandDurableFormat.ReadCodes DURABLE_OBJECT = new CommandDurableFormat.ReadCodes("COMMAND_DURABLE_OBJECT_INVALID",
            "COMMAND_DURABLE_OBJECT_SIZE_LIMIT",
            "COMMAND_DURABLE_OBJECT_NOT_OBJECT",
            "COMMAND_DURABLE_OBJECT_KEY_INVALID",
            "COMMAND_DURABLE_OBJECT_CORRUPT");
    private static final CommandDurableFormat.ReadCodes INDEX = new CommandDurableFormat.ReadCodes("COMMAND_NAMESPACE_INDEX_INVALID",
            "COMMAND_NAMESPACE_SIZE_LIMIT",
            "COMMAND_NAMESPACE_INDEX_NOT_OBJECT",
            "COMMAND_NAMESPACE_INDEX_KEY_INVALID",
            "COMMAND_NAMESPACE_INDEX_CORRUPT");

    /**
     * Normalized host-wide namespace root.
     */
    private final Path root;
    /**
     * Integrity-protected summary of indexed scope and anchor counts.
     */
    private final Path indexPath;
    /**
     * Process-anchor directories used to bind command ownership to runtimes.
     */
    private final Path processScopesPath;
    /**
     * Permanent lock objects preventing unsafe scope reuse.
     */
    private final Path locksPath;
    /**
     * Per-scope command records and reservations.
     */
    private final Path scopesPath;
    /**
     * OS-backed lock held for this namespace handle's lifetime.
     */
    private final CommandPermanentLock namespaceLock;

    private ProjectCommandNamespace(Path root, CommandPermanentLock namespaceLock) throws IOException {
        this.root = root;
        this.indexPath = root.resolve(INDEX_FILE);
        this.processScopesPath = root.resolve(PROCESS_SCOPES);
        this.locksPath = root.resolve(LOCKS);
        this.scopesPath = root.resolve(SCOPES);
        this.namespaceLock = namespaceLock;
        if (!Files.exists(indexPath)) {
            writeIndex(Map.of("scopeCount", 0L, "anchorCount", 0L));
        }
    }

    /**
     * Creates the namespace skeleton and acquires the permanent namespace lock.
     *
     * <p>Creation is bounded by the namespace limits and the returned handle
     * must remain open for every operation that reconciles or mutates namespace metadata.</p>
     *
     * @param root host-wide command namespace root
     * @return open namespace with its OS lock held
     * @throws IOException if namespace creation or locking fails
     */
    public static ProjectCommandNamespace open(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        Path normalized = root.toAbsolutePath().normalize();
        Files.createDirectories(normalized.resolve(PROCESS_SCOPES));
        Files.createDirectories(normalized.resolve(LOCKS));
        Files.createDirectories(normalized.resolve(SCOPES));
        return new ProjectCommandNamespace(normalized, CommandPermanentLock.open(normalized.resolve(LOCK_FILE)));
    }

    /**
     * Reads one anchor without opening or mutating the namespace.
     *
     * @param namespaceRoot host-wide command namespace root
     * @param anchorId      process anchor ID
     * @return verified process anchor
     * @throws IOException if the anchor is missing, corrupt, or incompatible
     */
    static ProjectCommandProcessAnchor readAnchor(Path namespaceRoot, String anchorId) throws IOException {
        Objects.requireNonNull(namespaceRoot, "namespaceRoot");
        Path path = anchorFile(namespaceRoot.toAbsolutePath().normalize(), boundedLocator(anchorId));
        Map<String, Object> value = readDurable(path, MAX_ANCHOR_BYTES);
        if (!anchorId.equals(string(value, "anchorId"))) {
            throw new CommandFormatException("COMMAND_ANCHOR_ID_MISMATCH");
        }
        Map<String, Object> process = map(value);
        SessionProcessIdentity identity = new SessionProcessIdentity(number(process, "pid"),
                string(process, "executableIdentity"),
                string(process, "commandLine"),
                number(process, "processStartTime"),
                string(process, "connectionNonce"));
        return new ProjectCommandProcessAnchor(anchorId, string(value, "scopeLocator"), identity, number(value, "createdAtEpochMillis"));
    }

    /**
     * Validates a scope or anchor locator, which becomes one path segment under the namespace.
     *
     * @param locator candidate locator
     * @return the same locator
     * @throws IOException with {@code COMMAND_LOCATOR_INVALID} when it is missing, over-long, or could leave its directory
     */
    static String requireLocator(String locator) throws IOException {
        if (locator == null || locator.isBlank() || locator.length() > 256 || locator.contains("/") || locator.contains("\\") || ".".equals(locator) || "..".equals(locator)) {
            throw new IOException("COMMAND_LOCATOR_INVALID");
        }
        return locator;
    }

    static Path scopeDirectory(Path root, String scopeLocator) {
        return root.resolve(SCOPES).resolve(scopeLocator);
    }

    static Path recordsDirectory(Path root, String scopeLocator) {
        return scopeDirectory(root, scopeLocator).resolve(RECORDS);
    }

    static Path anchorFile(Path root, String anchorId) {
        return root.resolve(PROCESS_SCOPES).resolve(anchorId).resolve(ANCHOR_FILE);
    }

    static Path lockFile(Path root, String scopeLocator) {
        return root.resolve(LOCKS).resolve(scopeLocator + ".lock");
    }

    private static String boundedLocator(String locator) throws IOException {
        return requireLocator(Objects.requireNonNull(locator, "locator"));
    }

    private static void writeDurable(Path target, Map<String, Object> value, int maxBytes) throws IOException {
        CommandDurableFormat.write(target, CommandDurableFormat.withIntegrity(value), maxBytes, "COMMAND_DURABLE_OBJECT_SIZE_LIMIT");
    }

    private static Map<String, Object> readDurable(Path path, int maxBytes) throws IOException {
        return CommandDurableFormat.read(path, maxBytes, DURABLE_OBJECT);
    }

    private static void validatePathIdentity(Path path) throws IOException {
        if (path.toString().getBytes(StandardCharsets.UTF_8).length > MAX_PATH_IDENTITY_BYTES) {
            throw new IOException("WORKTREE_IDENTITY_SIZE_LIMIT");
        }
    }

    private static int countEntries(Path directory, int limit) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path ignored : stream) {
                if (++count > limit) {
                    return count;
                }
            }
        }
        return count;
    }

    private static int countIndexEntries(Object value) {
        if (value instanceof Map<?, ?> map) {
            int count = map.size();
            for (Object child : map.values()) {
                count += countIndexEntries(child);
            }
            return count;
        }
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object child : iterable) {
                count += 1 + countIndexEntries(child);
            }
            return count;
        }
        return 0;
    }

    private static int enumerateDirectories(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("COMMAND_NAMESPACE_DIRECTORY_MISSING");
        }
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (++count > MAX_SCOPES || !Files.isDirectory(path) || Files.isSymbolicLink(path)) {
                    throw new IOException("COMMAND_NAMESPACE_ENUMERATION_INVALID");
                }
                readDurable(path.resolve(SCOPE_FILE), MAX_SCOPE_BYTES);
            }
        }
        return count;
    }

    private static int enumerateAnchors(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("COMMAND_NAMESPACE_DIRECTORY_MISSING");
        }
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
        if (!Files.isDirectory(directory)) {
            throw new IOException("COMMAND_NAMESPACE_DIRECTORY_MISSING");
        }
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

    private static Map<String, Object> map(Map<String, Object> value) throws CommandFormatException {
        Object raw = value.get("processIdentity");
        if (!(raw instanceof Map<?, ?> parsed)) {
            throw new CommandFormatException("COMMAND_ANCHOR_FIELD_MISSING:processIdentity");
        }
        return CommandDurableFormat.stringKeyed(parsed, "COMMAND_ANCHOR_FIELD_INVALID:processIdentity");
    }

    /**
     * Returns the namespace root.
     *
     * @return normalized namespace root
     */
    Path root() {
        return root;
    }

    /**
     * Returns the permanent namespace lock path.
     *
     * @return permanent lock path
     */
    Path namespaceLockPath() {
        return root.resolve(LOCK_FILE);
    }

    /**
     * Returns the physical-worktree scope directory root.
     *
     * @return scope root
     */
    Path scopesPath() {
        return scopesPath;
    }

    /**
     * Creates one permanent worktree lock object and returns its path.
     *
     * @param worktreeLocator bounded physical-worktree locator
     * @return permanent lock path
     * @throws IOException if the locator or lock object is invalid
     */
    Path worktreeLockPath(String worktreeLocator) throws IOException {
        Path path = lockFile(root, boundedLocator(worktreeLocator));
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

    /**
     * Publishes verified scope metadata and creates its bounded record directories.
     *
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
        Files.createDirectories(scope.resolve(RECORDS));
        Files.createDirectories(scope.resolve("reservations"));
        Files.createDirectories(scope.resolve("replay"));
        Files.createDirectories(scope.resolve("audit"));
        worktreeLockPath(locator);
        Path metadataPath = scope.resolve(SCOPE_FILE);
        if (Files.exists(metadataPath)) {
            Map<String, Object> existing = readDurable(metadataPath, MAX_SCOPE_BYTES);
            if (number(existing, "schemaVersion") != CommandDurableFormat.SCHEMA_VERSION) {
                throw new CommandFormatException("COMMAND_FORMAT_MIGRATION_REQUIRED");
            }
            if (!locator.equals(existing.get("worktreeLocator")) || !(existing.get("realPath") instanceof String path) || !Files.isSameFile(identity.realPath(), Path.of(path))) {
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

    /**
     * Persists one immutable process anchor under its process-scope directory.
     *
     * @param anchor process anchor to persist
     * @throws IOException if persistence fails
     */
    public void writeAnchor(ProjectCommandProcessAnchor anchor) throws IOException {
        Objects.requireNonNull(anchor, "anchor");
        String locator = boundedLocator(anchor.anchorId());
        Path directory = processScopesPath.resolve(locator);
        if (!Files.exists(directory) && countEntries(processScopesPath, MAX_ANCHORS) >= MAX_ANCHORS) {
            throw new IOException("COMMAND_ANCHOR_CAPACITY_EXCEEDED");
        }
        Files.createDirectories(directory);
        Path anchorPath = anchorFile(root, locator);
        if (Files.exists(anchorPath)) {
            Map<String, Object> existing = readDurable(anchorPath, MAX_ANCHOR_BYTES);
            if (number(existing, "schemaVersion") != CommandDurableFormat.SCHEMA_VERSION) {
                throw new CommandFormatException("COMMAND_FORMAT_MIGRATION_REQUIRED");
            }
            if (!anchor.equals(readAnchor(anchor.anchorId()))) {
                throw new CommandFormatException("COMMAND_ANCHOR_IDENTITY_CHANGED");
            }
            return;
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
    }

    /**
     * Loads and verifies one persisted process anchor without renewing any lease.
     *
     * @param anchorId process anchor ID
     * @return verified process anchor
     * @throws IOException if the anchor is missing, corrupt, or incompatible
     */
    ProjectCommandProcessAnchor readAnchor(String anchorId) throws IOException {
        return readAnchor(root, anchorId);
    }

    /**
     * Writes the rebuildable index with compatibility and integrity metadata.
     *
     * @param index bounded index fields
     * @throws IOException if limits or atomic persistence fail
     */
    void writeIndex(Map<String, Object> index) throws IOException {
        Objects.requireNonNull(index, "index");
        Map<String, Object> value = new LinkedHashMap<>(index);
        if (Files.exists(indexPath)) {
            Map<String, Object> current = readIndex();
            long currentSchema = ((Number) current.get("schemaVersion")).longValue();
            long requestedSchema = value.get("schemaVersion") instanceof Number number ? number.longValue() : CommandDurableFormat.SCHEMA_VERSION;
            if (currentSchema < CommandDurableFormat.SCHEMA_VERSION) {
                throw new CommandFormatException("COMMAND_FORMAT_MIGRATION_REQUIRED");
            }
            if (requestedSchema > CommandDurableFormat.SCHEMA_VERSION) {
                throw new CommandFormatException("COMMAND_FORMAT_NEWER_THAN_WRITER");
            }
            long expectedRevision = ((Number) current.getOrDefault("objectRevision", 1L)).longValue() + 1L;
            if (value.containsKey("objectRevision") && ((Number) value.get("objectRevision")).longValue() != expectedRevision) {
                throw new CommandFormatException("COMMAND_NAMESPACE_REVISION_STALE");
            }
            value.put("objectRevision", expectedRevision);
        }
        Map<String, Object> durable = CommandDurableFormat.withIntegrity(value);
        if (countIndexEntries(durable) > MAX_INDEX_ENTRIES) {
            throw new IOException("COMMAND_NAMESPACE_ENTRY_LIMIT");
        }
        CommandDurableFormat.write(indexPath, durable, MAX_INDEX_BYTES, "COMMAND_NAMESPACE_SIZE_LIMIT");
    }

    /**
     * Reads and verifies the rebuildable index without mutating it.
     *
     * @return verified index fields
     * @throws IOException if the index is missing, corrupt, or incompatible
     */
    Map<String, Object> readIndex() throws IOException {
        Map<String, Object> value = CommandDurableFormat.read(indexPath, MAX_INDEX_BYTES, INDEX);
        try {
            return Map.copyOf(value);
        } catch (NullPointerException nullValue) {
            throw new CommandFormatException(INDEX.corrupt(), nullValue);
        }
    }

    /**
     * Reconciles the rebuildable index after a complete bounded namespace pass.
     *
     * @return verified rebuilt index
     * @throws IOException if an authoritative object is missing, corrupt, duplicated, or over a
     *                     bound
     */
    @SuppressWarnings("UnusedReturnValue")
    Map<String, Object> reconcileIndex() throws IOException {
        if (!isHeld()) {
            throw new IOException("COMMAND_NAMESPACE_LOCK_REQUIRED");
        }
        int scopes = enumerateDirectories(scopesPath);
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

    /**
     * Returns the permanent namespace lock's current ownership state.
     *
     * @return true while the namespace lock is held
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean isHeld() {
        return namespaceLock.isHeld();
    }

    /**
     * Releases the namespace OS lock while preserving the lock object.
     */
    @Override
    public void close() throws IOException {
        namespaceLock.close();
    }
}
