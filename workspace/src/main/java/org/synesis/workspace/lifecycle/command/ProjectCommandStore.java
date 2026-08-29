package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.application.project.ProjectCommandAdmissionService;

/** Persists and verifies bounded project-command records under one namespace scope. */
public final class ProjectCommandStore {

    private final Path namespaceRoot;

    /** Creates a store rooted at the host-wide command namespace.
     * @param namespaceRoot host-wide command namespace root
     */
    public ProjectCommandStore(Path namespaceRoot) {
        this.namespaceRoot = Objects.requireNonNull(namespaceRoot, "namespaceRoot")
                .toAbsolutePath().normalize();
    }

    /** Saves one record using an atomic sibling replacement.
     * @param record record to persist
     * @throws IOException if validation or persistence fails
     */
    public void save(ProjectCommandRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        validateLocator(record.scopeLocator());
        validateLocator(record.anchorId());
        try (ProjectCommandNamespace namespace = ProjectCommandNamespace.open(namespaceRoot)) {
            if (!namespace.isHeld()) throw new IOException("COMMAND_NAMESPACE_LOCK_UNAVAILABLE");
            Optional<ProjectCommandRecord> current = find(record.scopeLocator(), record.anchorId(), record.requestId());
            if (current.isPresent()) {
                Path currentTarget = scopePath(record.scopeLocator()).resolve("records")
                        .resolve(key(record.anchorId(), record.requestId()) + ".json");
                if (readSchema(currentTarget) != CommandDurableFormat.SCHEMA_VERSION) {
                    throw new CommandFormatException("COMMAND_FORMAT_MIGRATION_REQUIRED");
                }
                if (current.get().phase() == ProjectCommandPhase.TERMINAL
                        || current.get().phase() == ProjectCommandPhase.AMBIGUOUS) {
                    throw new CommandFormatException("COMMAND_RECORD_BLOCKING_OR_TERMINAL_IMMUTABLE");
                }
                if (record.revision() != current.get().revision() + 1L) {
                    throw new CommandFormatException("COMMAND_RECORD_REVISION_STALE");
                }
                if (record.phase().ordinal() <= current.get().phase().ordinal()) {
                    throw new CommandFormatException("COMMAND_RECORD_PHASE_ORDER_INVALID");
                }
            } else if (record.revision() != 1L || record.phase() != ProjectCommandPhase.STARTING) {
                throw new CommandFormatException("COMMAND_RECORD_INITIAL_STATE_INVALID");
            }
            Path scope = scopePath(record.scopeLocator());
            Path target = scope.resolve("records").resolve(key(record.anchorId(), record.requestId()) + ".json");
            if (!Files.exists(target) && countJson(scope.resolve("records")) >= ProjectCommandNamespace.MAX_RECORDS_PER_SCOPE) {
                throw new IOException("COMMAND_SCOPE_RECORD_CAPACITY_EXCEEDED");
            }
            Map<String, Object> value = toMap(record);
            String json = ProviderJson.write(CommandDurableFormat.withIntegrity(value));
            if (json.getBytes(StandardCharsets.UTF_8).length > ProjectCommandNamespace.MAX_SCOPE_BYTES) {
                throw new IOException("COMMAND_RECORD_SIZE_LIMIT");
            }
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + java.util.UUID.randomUUID());
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                try {
                    Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /** Reads one keyed record without renewing a lease or mutating authority.
     * @param scopeLocator physical-worktree scope
     * @param anchorId process-anchor identity
     * @param requestId canonical typed request ID
     * @return matching record, if present
     * @throws IOException if state is corrupt or incompatible
     */
    public Optional<ProjectCommandRecord> find(String scopeLocator, String anchorId, String requestId)
            throws IOException {
        validateLocator(scopeLocator);
        validateLocator(anchorId);
        Path target = namespaceRoot.resolve("scopes").resolve(scopeLocator).resolve("records")
                .resolve(key(anchorId, requestId) + ".json");
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        verifyAnchorAndScope(scopeLocator, anchorId);
        try {
            Object parsed = ProviderJson.parse(Files.readString(target, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> parsedMap)) {
                throw new CommandFormatException("COMMAND_RECORD_NOT_OBJECT");
            }
            Map<String, Object> value = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : parsedMap.entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    throw new CommandFormatException("COMMAND_RECORD_KEY_INVALID");
                }
                value.put(name, entry.getValue());
            }
            CommandDurableFormat.verify(value);
            ProjectCommandRecord record = fromMap(value);
            if (!record.scopeLocator().equals(scopeLocator) || !record.anchorId().equals(anchorId)
                    || !record.requestId().equals(requestId)) {
                throw new CommandFormatException("COMMAND_RECORD_KEY_MISMATCH");
            }
            return Optional.of(record);
        } catch (CommandFormatException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CommandFormatException("COMMAND_RECORD_CORRUPT", failure);
        }
    }

    /** Counts bounded records retained for one live process anchor.
     * @param scopeLocator physical-worktree scope
     * @param anchorId process-anchor identity
     * @return number of matching records
     * @throws IOException if a record is corrupt or incompatible
     */
    public int countForAnchor(String scopeLocator, String anchorId) throws IOException {
        validateLocator(scopeLocator);
        validateLocator(anchorId);
        Path records = namespaceRoot.resolve("scopes").resolve(scopeLocator).resolve("records");
        if (!Files.isDirectory(records)) {
            return 0;
        }
        int inspected = 0;
        int matching = 0;
        try (var files = Files.list(records)) {
            var iterator = files.filter(path -> path.getFileName().toString().endsWith(".json")).iterator();
            while (iterator.hasNext()) {
                if (++inspected > ProjectCommandNamespace.MAX_INSPECTED_ENTRIES) {
                    throw new CommandFormatException("COMMAND_NAMESPACE_ENUMERATION_LIMIT");
                }
                Path file = iterator.next();
                try {
                    Object parsed = ProviderJson.parse(Files.readString(file, StandardCharsets.UTF_8));
                    if (!(parsed instanceof Map<?, ?> parsedMap)) {
                        throw new CommandFormatException("COMMAND_RECORD_NOT_OBJECT");
                    }
                    Map<String, Object> value = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : parsedMap.entrySet()) {
                        if (!(entry.getKey() instanceof String name)) {
                            throw new CommandFormatException("COMMAND_RECORD_KEY_INVALID");
                        }
                        value.put(name, entry.getValue());
                    }
                    CommandDurableFormat.verify(value);
                    if (anchorId.equals(value.get("anchorId"))) {
                        matching++;
                        if (matching > ProjectCommandAdmissionService.MAX_REQUEST_IDS_PER_LIVE_ANCHOR) {
                            return matching;
                        }
                    }
                } catch (CommandFormatException failure) {
                    throw failure;
                } catch (RuntimeException failure) {
                    throw new CommandFormatException("COMMAND_RECORD_CORRUPT", failure);
                }
            }
        }
        return matching;
    }

    /** Returns whether a verified worktree scope contains a non-terminal command.
     * @param worktree exact physical worktree identity
     * @return true when a command is still blocking
     * @throws IOException when the scope contains malformed durable state
     */
    public boolean hasBlockingRecords(PhysicalWorktreeIdentity worktree) throws IOException {
        Objects.requireNonNull(worktree, "worktree");
        validateLocator(worktree.locator());
        Path records = scopePath(worktree.locator()).resolve("records");
        if (!Files.isDirectory(records)) return false;
        try (var files = Files.list(records)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                Object parsed = ProviderJson.parse(Files.readString(file, StandardCharsets.UTF_8));
                if (!(parsed instanceof Map<?, ?> raw)) throw new CommandFormatException("COMMAND_RECORD_NOT_OBJECT");
                Map<String, Object> value = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new CommandFormatException("COMMAND_RECORD_KEY_INVALID");
                    value.put(key, entry.getValue());
                }
                CommandDurableFormat.verify(value);
                if (fromMap(value).blocking()) return true;
            }
        } catch (CommandFormatException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CommandFormatException("COMMAND_RECORD_CORRUPT", failure);
        }
        return false;
    }

    private static void validateLocator(String locator) throws IOException {
        if (locator == null || locator.isBlank() || locator.length() > 256
                || locator.contains("/") || locator.contains("\\")
                || ".".equals(locator) || "..".equals(locator)) {
            throw new IOException("COMMAND_LOCATOR_INVALID");
        }
    }

    private Path scopePath(String scopeLocator) {
        return namespaceRoot.resolve("scopes").resolve(scopeLocator);
    }

    private static long readSchema(Path path) throws IOException {
        Object parsed = ProviderJson.parse(Files.readString(path, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> raw) || !(raw.get("schemaVersion") instanceof Number number)) {
            throw new CommandFormatException("COMMAND_FORMAT_FIELD_MISSING:schemaVersion");
        }
        return number.longValue();
    }

    private static int countJson(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        int count = 0;
        try (var files = Files.list(directory)) {
            var iterator = files.filter(path -> path.getFileName().toString().endsWith(".json")).iterator();
            while (iterator.hasNext()) {
                if (++count > ProjectCommandNamespace.MAX_RECORDS_PER_SCOPE) return count;
                iterator.next();
            }
        }
        return count;
    }

    private static String key(String anchorId, String requestId) {
        return ProjectCommandCanonicalizer.requestKey(anchorId, requestId);
    }

    private void verifyAnchorAndScope(String scopeLocator, String anchorId) throws IOException {
        Path anchorPath = namespaceRoot.resolve("process-scopes").resolve(anchorId).resolve("anchor.json");
        if (!Files.isRegularFile(anchorPath) || Files.isSymbolicLink(anchorPath)) {
            throw new CommandFormatException("COMMAND_ANCHOR_MISSING");
        }
        ProjectCommandProcessAnchor anchor = ProjectCommandNamespace.readAnchor(namespaceRoot, anchorId);
        if (!scopeLocator.equals(anchor.scopeLocator())) {
            throw new CommandFormatException("COMMAND_ANCHOR_SCOPE_MISMATCH");
        }
        Path scopeMetadata = namespaceRoot.resolve("scopes").resolve(scopeLocator).resolve("scope.json");
        if (!Files.isRegularFile(scopeMetadata) || Files.isSymbolicLink(scopeMetadata)) {
            throw new CommandFormatException("COMMAND_SCOPE_MISSING");
        }
        Object parsed = ProviderJson.parse(Files.readString(scopeMetadata, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new CommandFormatException("COMMAND_SCOPE_NOT_OBJECT");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new CommandFormatException("COMMAND_SCOPE_KEY_INVALID");
            }
            metadata.put(key, entry.getValue());
        }
        CommandDurableFormat.verify(metadata);
        if (!scopeLocator.equals(metadata.get("worktreeLocator"))
                || !(metadata.get("realPath") instanceof String path)) {
            throw new CommandFormatException("COMMAND_SCOPE_IDENTITY_MISMATCH");
        }
        PhysicalWorktreeIdentity stored = PhysicalWorktreeIdentity.capture(Path.of(path));
        if (!scopeLocator.equals(stored.locator())) {
            throw new CommandFormatException("COMMAND_SCOPE_REAL_PATH_MISMATCH");
        }
    }

    private static Map<String, Object> toMap(ProjectCommandRecord record) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("anchorId", record.anchorId());
        value.put("scopeLocator", record.scopeLocator());
        value.put("requestId", record.requestId());
        value.put("requestDigest", record.requestDigest());
        value.put("semanticDigest", record.semanticDigest());
        value.put("phase", record.phase().name());
        if (record.terminalResolution() != null) {
            value.put("terminalResolution", record.terminalResolution().name());
        }
        value.put("outcomeKnown", record.outcomeKnown());
        if (record.exitCode() != null) {
            value.put("exitCode", record.exitCode());
        }
        value.put("stdoutComplete", record.stdoutComplete());
        value.put("stderrComplete", record.stderrComplete());
        if (record.reviewReference() != null) {
            value.put("reviewReference", record.reviewReference());
        }
        value.put("objectRevision", record.revision());
        value.put("createdAtEpochMillis", record.createdAtEpochMillis());
        value.put("updatedAtEpochMillis", record.updatedAtEpochMillis());
        value.put("response", record.response());
        value.put("commandProcessIdentity", record.commandProcessIdentity());
        return value;
    }

    @SuppressWarnings("unchecked")
    private static ProjectCommandRecord fromMap(Map<String, Object> value) throws CommandFormatException {
        String resolution = value.get("terminalResolution") instanceof String text ? text : null;
        Map<String, Object> response = value.get("response") instanceof Map<?, ?> map
                ? copyMap(map) : Map.of();
        Map<String, Object> commandProcessIdentity = value.get("commandProcessIdentity") instanceof Map<?, ?> map
                ? copyMap(map) : Map.of();
        return new ProjectCommandRecord(
                string(value, "anchorId"), string(value, "scopeLocator"), string(value, "requestId"),
                string(value, "requestDigest"), string(value, "semanticDigest"),
                ProjectCommandPhase.valueOf(string(value, "phase")),
                resolution == null ? null : ProjectCommandTerminalResolution.valueOf(resolution),
                booleanValue(value, "outcomeKnown"), integer(value, "exitCode"),
                booleanValue(value, "stdoutComplete"), booleanValue(value, "stderrComplete"),
                value.get("reviewReference") instanceof String text ? text : null,
                number(value, "objectRevision"), number(value, "createdAtEpochMillis"),
                number(value, "updatedAtEpochMillis"), response, commandProcessIdentity);
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) throws CommandFormatException {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new CommandFormatException("COMMAND_RECORD_RESPONSE_KEY_INVALID");
            }
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    private static String string(Map<String, Object> value, String key) throws CommandFormatException {
        if (!(value.get(key) instanceof String text)) {
            throw new CommandFormatException("COMMAND_RECORD_FIELD_MISSING:" + key);
        }
        return text;
    }

    private static long number(Map<String, Object> value, String key) throws CommandFormatException {
        if (!(value.get(key) instanceof Number number)) {
            throw new CommandFormatException("COMMAND_RECORD_FIELD_MISSING:" + key);
        }
        return number.longValue();
    }

    private static Integer integer(Map<String, Object> value, String key) throws CommandFormatException {
        if (value.get(key) == null) {
            return null;
        }
        if (!(value.get(key) instanceof Number number)) {
            throw new CommandFormatException("COMMAND_RECORD_FIELD_INVALID:" + key);
        }
        return number.intValue();
    }

    private static boolean booleanValue(Map<String, Object> value, String key) throws CommandFormatException {
        if (!(value.get(key) instanceof Boolean bool)) {
            throw new CommandFormatException("COMMAND_RECORD_FIELD_MISSING:" + key);
        }
        return bool;
    }
}
