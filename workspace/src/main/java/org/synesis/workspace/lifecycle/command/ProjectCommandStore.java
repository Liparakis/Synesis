package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.synesis.workspace.lifecycle.command.CommandDurableFormat.ReadCodes;

/**
 * Persists and verifies bounded project-command records under one namespace scope.
 */
public final class ProjectCommandStore {

    /**
     * Records are read without the regular-file check and size bound the namespace objects get,
     * so a record the store accepted before keeps being accepted.
     */
    private static final ReadCodes RECORD = new ReadCodes(null, null, "COMMAND_RECORD_NOT_OBJECT", "COMMAND_RECORD_KEY_INVALID", "COMMAND_RECORD_CORRUPT");
    private static final ReadCodes SCOPE = new ReadCodes(null, null, "COMMAND_SCOPE_NOT_OBJECT", "COMMAND_SCOPE_KEY_INVALID", null);

    /**
     * Canonical host-wide namespace under which command scopes are stored.
     */
    private final Path namespaceRoot;

    /**
     * Creates a store rooted at the host-wide command namespace.
     *
     * @param namespaceRoot host-wide command namespace root
     */
    public ProjectCommandStore(Path namespaceRoot) {
        this.namespaceRoot = Objects.requireNonNull(namespaceRoot, "namespaceRoot").toAbsolutePath().normalize();
    }

    private static int countJson(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        int count = 0;
        try (var files = Files.list(directory)) {
            var iterator = files.filter(path -> path.getFileName().toString().endsWith(".json")).iterator();
            while (iterator.hasNext()) {
                if (++count > ProjectCommandNamespace.MAX_RECORDS_PER_SCOPE) {
                    return count;
                }
                iterator.next();
            }
        }
        return count;
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

    private static ProjectCommandRecord fromMap(Map<String, Object> value) throws CommandFormatException {
        String resolution = value.get("terminalResolution") instanceof String text ? text : null;
        Map<String, Object> response = value.get("response") instanceof Map<?, ?> map ? copyMap(map) : Map.of();
        Map<String, Object> commandProcessIdentity = value.get("commandProcessIdentity") instanceof Map<?, ?> map ? copyMap(map) : Map.of();
        return new ProjectCommandRecord(string(value, "anchorId"),
                string(value, "scopeLocator"),
                string(value, "requestId"),
                string(value, "requestDigest"),
                string(value, "semanticDigest"),
                ProjectCommandPhase.valueOf(string(value, "phase")),
                resolution == null ? null : ProjectCommandTerminalResolution.valueOf(resolution),
                booleanValue(value, "outcomeKnown"),
                integer(value),
                booleanValue(value, "stdoutComplete"),
                booleanValue(value, "stderrComplete"),
                value.get("reviewReference") instanceof String text ? text : null,
                number(value, "objectRevision"),
                number(value, "createdAtEpochMillis"),
                number(value, "updatedAtEpochMillis"),
                response,
                commandProcessIdentity);
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) throws CommandFormatException {
        return CommandDurableFormat.stringKeyed(source, "COMMAND_RECORD_RESPONSE_KEY_INVALID");
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

    private static Integer integer(Map<String, Object> value) throws CommandFormatException {
        if (value.get("exitCode") == null) {
            return null;
        }
        if (!(value.get("exitCode") instanceof Number number)) {
            throw new CommandFormatException("COMMAND_RECORD_FIELD_INVALID:exitCode");
        }
        return number.intValue();
    }

    private static boolean booleanValue(Map<String, Object> value, String key) throws CommandFormatException {
        if (!(value.get(key) instanceof Boolean bool)) {
            throw new CommandFormatException("COMMAND_RECORD_FIELD_MISSING:" + key);
        }
        return bool;
    }

    /**
     * Reads and verifies one durable command record.
     *
     * @param path record path
     * @return verified record fields
     * @throws IOException if the record is corrupt or incompatible
     */
    private static Map<String, Object> readRecord(Path path) throws IOException {
        return CommandDurableFormat.read(path, CommandDurableFormat.UNBOUNDED, RECORD);
    }

    /**
     * Applies the record lifecycle: a new record starts as revision 1 in STARTING, and an existing
     * one moves forward one revision and to a later phase until it is TERMINAL or AMBIGUOUS.
     */
    private static void requireTransition(Optional<Stored> current, ProjectCommandRecord next) throws CommandFormatException {
        if (current.isEmpty()) {
            if (next.revision() != 1L || next.phase() != ProjectCommandPhase.STARTING) {
                throw new CommandFormatException("COMMAND_RECORD_INITIAL_STATE_INVALID");
            }
            return;
        }
        ProjectCommandRecord previous = current.get().record();
        if (current.get().schemaVersion() != CommandDurableFormat.SCHEMA_VERSION) {
            throw new CommandFormatException("COMMAND_FORMAT_MIGRATION_REQUIRED");
        }
        if (previous.phase() == ProjectCommandPhase.TERMINAL || previous.phase() == ProjectCommandPhase.AMBIGUOUS) {
            throw new CommandFormatException("COMMAND_RECORD_BLOCKING_OR_TERMINAL_IMMUTABLE");
        }
        if (next.revision() != previous.revision() + 1L) {
            throw new CommandFormatException("COMMAND_RECORD_REVISION_STALE");
        }
        if (next.phase().ordinal() <= previous.phase().ordinal()) {
            throw new CommandFormatException("COMMAND_RECORD_PHASE_ORDER_INVALID");
        }
    }

    /**
     * Saves one record using an atomic sibling replacement.
     *
     * @param record record to persist
     * @throws IOException if validation or persistence fails
     */
    public void save(ProjectCommandRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        ProjectCommandNamespace.requireLocator(record.scopeLocator());
        ProjectCommandNamespace.requireLocator(record.anchorId());
        try (ProjectCommandNamespace _ = ProjectCommandNamespace.open(namespaceRoot)) {
            Optional<Stored> current = load(record.scopeLocator(), record.anchorId(), record.requestId());
            requireTransition(current, record);
            Path records = ProjectCommandNamespace.recordsDirectory(namespaceRoot, record.scopeLocator());
            if (current.isEmpty() && countJson(records) >= ProjectCommandNamespace.MAX_RECORDS_PER_SCOPE) {
                throw new IOException("COMMAND_SCOPE_RECORD_CAPACITY_EXCEEDED");
            }
            Path target = records.resolve(ProjectCommandCanonicalizer.requestKey(record.anchorId(), record.requestId()) + ".json");
            CommandDurableFormat.write(target, CommandDurableFormat.withIntegrity(toMap(record)), ProjectCommandNamespace.MAX_SCOPE_BYTES, "COMMAND_RECORD_SIZE_LIMIT");
        }
    }

    /**
     * Reads one keyed record without renewing a lease or mutating authority.
     *
     * @param scopeLocator physical-worktree scope
     * @param anchorId     process-anchor identity
     * @param requestId    canonical typed request ID
     * @return matching record, if present
     * @throws IOException if state is corrupt or incompatible
     */
    public Optional<ProjectCommandRecord> find(String scopeLocator, String anchorId, String requestId) throws IOException {
        ProjectCommandNamespace.requireLocator(scopeLocator);
        ProjectCommandNamespace.requireLocator(anchorId);
        return load(scopeLocator, anchorId, requestId).map(Stored::record);
    }

    /**
     * Counts bounded records retained for one live process anchor.
     *
     * @param scopeLocator physical-worktree scope
     * @param anchorId     process-anchor identity
     * @return number of matching records
     * @throws IOException if a record is corrupt or incompatible
     */
    public int countForAnchor(String scopeLocator, String anchorId) throws IOException {
        ProjectCommandNamespace.requireLocator(scopeLocator);
        ProjectCommandNamespace.requireLocator(anchorId);
        Path records = ProjectCommandNamespace.recordsDirectory(namespaceRoot, scopeLocator);
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
                    Map<String, Object> value = readRecord(file);
                    if (anchorId.equals(value.get("anchorId"))) {
                        matching++;
                        if (matching > ProjectCommandNamespace.MAX_REQUEST_IDS_PER_LIVE_ANCHOR) {
                            return matching;
                        }
                    }
                } catch (RuntimeException failure) {
                    throw new CommandFormatException("COMMAND_RECORD_CORRUPT", failure);
                }
            }
        }
        return matching;
    }

    /**
     * Returns whether a verified worktree scope contains a non-terminal command.
     *
     * @param worktree exact physical worktree identity
     * @return true when a command is still blocking
     * @throws IOException when the scope contains malformed durable state
     */
    public boolean hasBlockingRecords(PhysicalWorktreeIdentity worktree) throws IOException {
        Objects.requireNonNull(worktree, "worktree");
        ProjectCommandNamespace.requireLocator(worktree.locator());
        Path records = ProjectCommandNamespace.recordsDirectory(namespaceRoot, worktree.locator());
        if (!Files.isDirectory(records)) {
            return false;
        }
        try (var files = Files.list(records)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                Map<String, Object> value = readRecord(file);
                if (fromMap(value).blocking()) {
                    return true;
                }
            }
        } catch (RuntimeException failure) {
            throw new CommandFormatException("COMMAND_RECORD_CORRUPT", failure);
        }
        return false;
    }

    /**
     * Reads the keyed record once, with the schema it was written under, after verifying the
     * anchor and scope it belongs to.
     */
    private Optional<Stored> load(String scopeLocator, String anchorId, String requestId) throws IOException {
        Path target = ProjectCommandNamespace.recordsDirectory(namespaceRoot, scopeLocator).resolve(ProjectCommandCanonicalizer.requestKey(anchorId, requestId) + ".json");
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        verifyAnchorAndScope(scopeLocator, anchorId);
        try {
            Map<String, Object> value = readRecord(target);
            ProjectCommandRecord record = fromMap(value);
            if (!record.scopeLocator().equals(scopeLocator) || !record.anchorId().equals(anchorId) || !record.requestId().equals(requestId)) {
                throw new CommandFormatException("COMMAND_RECORD_KEY_MISMATCH");
            }
            return Optional.of(new Stored(record, number(value, "schemaVersion")));
        } catch (RuntimeException failure) {
            throw new CommandFormatException("COMMAND_RECORD_CORRUPT", failure);
        }
    }

    private void verifyAnchorAndScope(String scopeLocator, String anchorId) throws IOException {
        Path anchorPath = ProjectCommandNamespace.anchorFile(namespaceRoot, anchorId);
        if (!Files.isRegularFile(anchorPath) || Files.isSymbolicLink(anchorPath)) {
            throw new CommandFormatException("COMMAND_ANCHOR_MISSING");
        }
        ProjectCommandProcessAnchor anchor = ProjectCommandNamespace.readAnchor(namespaceRoot, anchorId);
        if (!scopeLocator.equals(anchor.scopeLocator())) {
            throw new CommandFormatException("COMMAND_ANCHOR_SCOPE_MISMATCH");
        }
        Path scopeMetadata = ProjectCommandNamespace.scopeDirectory(namespaceRoot, scopeLocator).resolve(ProjectCommandNamespace.SCOPE_FILE);
        if (!Files.isRegularFile(scopeMetadata) || Files.isSymbolicLink(scopeMetadata)) {
            throw new CommandFormatException("COMMAND_SCOPE_MISSING");
        }
        Map<String, Object> metadata = CommandDurableFormat.read(scopeMetadata, CommandDurableFormat.UNBOUNDED, SCOPE);
        if (!scopeLocator.equals(metadata.get("worktreeLocator")) || !(metadata.get("realPath") instanceof String path)) {
            throw new CommandFormatException("COMMAND_SCOPE_IDENTITY_MISMATCH");
        }
        PhysicalWorktreeIdentity stored = PhysicalWorktreeIdentity.capture(Path.of(path));
        if (!scopeLocator.equals(stored.locator())) {
            throw new CommandFormatException("COMMAND_SCOPE_REAL_PATH_MISMATCH");
        }
    }

    /**
     * A record together with the schema version its file was written under.
     */
    private record Stored(ProjectCommandRecord record, long schemaVersion) {

    }
}
