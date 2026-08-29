package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Performs bounded dead-anchor terminal-evidence maintenance under existing cleanup authority.
 */
@SuppressWarnings("DuplicatedCode")
public final class ProjectCommandMaintenanceService {

    /**
     * Creates a command maintenance service.
     */
    public ProjectCommandMaintenanceService() {
    }

    private static List<Map<String, Object>> readRecords(Path records, String anchorId) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.isDirectory(records)) {
            return result;
        }
        int inspected = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(records, "*.json")) {
            for (Path path : stream) {
                if (++inspected > ProjectCommandNamespace.MAX_INSPECTED_ENTRIES) {
                    throw new IOException("COMMAND_NAMESPACE_ENUMERATION_LIMIT");
                }
                Map<String, Object> value = readObject(path);
                if (!anchorId.equals(string(value, "anchorId"))) {
                    continue;
                }
                if (!ProjectCommandPhase.TERMINAL.name()
                        .equals(string(value, "phase"))) {
                    throw new IOException("COMMAND_BLOCKING_RECORD_RETAINED");
                }
                result.add(value);
            }
        }
        return result;
    }

    private static List<Map<String, Object>> compactedRecords(List<Map<String, Object>> records) {
        List<Map<String, Object>> compacted = new ArrayList<>();
        for (Map<String, Object> value : records) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("requestId", value.get("requestId"));
            summary.put("requestDigest", value.get("requestDigest"));
            summary.put("semanticDigest", value.get("semanticDigest"));
            summary.put("terminalResolution", value.get("terminalResolution"));
            summary.put("outcomeKnown", value.get("outcomeKnown"));
            summary.put("exitCode", value.get("exitCode"));
            summary.put("createdAtEpochMillis", value.get("createdAtEpochMillis"));
            summary.put("updatedAtEpochMillis", value.get("updatedAtEpochMillis"));
            summary.put("response", value.get("response"));
            compacted.add(summary);
        }
        return compacted;
    }

    private static Map<String, Object> readObject(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new CommandFormatException("COMMAND_RECORD_INVALID");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > ProjectCommandNamespace.MAX_SCOPE_BYTES) {
            throw new CommandFormatException("COMMAND_RECORD_SIZE_LIMIT");
        }
        Object parsed = ProviderJson.parse(new String(bytes, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new CommandFormatException("COMMAND_RECORD_NOT_OBJECT");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new CommandFormatException("COMMAND_RECORD_KEY_INVALID");
            }
            value.put(key, entry.getValue());
        }
        CommandDurableFormat.verify(value);
        return value;
    }

    private static void requireDead(ProjectCommandProcessAnchor anchor) throws IOException {
        var handle = ProcessHandle.of(anchor.processIdentity()
                .pid());
        if (handle.isEmpty()) {
            return;
        }
        ProcessHandle.Info info = handle.get()
                .info();
        if (info.startInstant()
                .isEmpty() || info.command()
                .isEmpty() || info.commandLine()
                .isEmpty()) {
            throw new IOException("COMMAND_ANCHOR_PROCESS_LIVENESS_AMBIGUOUS");
        }
        boolean same = info.startInstant()
                .get()
                .toEpochMilli()
                == anchor.processIdentity()
                .processStartTime()
                && info.command()
                .get()
                .equals(anchor.processIdentity()
                        .executableIdentity())
                && info.commandLine()
                .get()
                .equals(anchor.processIdentity()
                        .commandLine());
        if (same) {
            throw new IOException("COMMAND_ANCHOR_PROCESS_STILL_LIVE");
        }
    }

    private static long countDirectories(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0L;
        }
        long count = 0L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path _ : stream) {
                if (++count > ProjectCommandNamespace.MAX_INDEX_ENTRIES) {
                    throw new IOException("COMMAND_NAMESPACE_ENTRY_LIMIT");
                }
            }
        }
        return count;
    }

    private static void atomicWrite(Path target, String value) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, value, StandardCharsets.UTF_8);
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

    private static String locator(String value) throws IOException {
        if (value == null || value.isBlank() || value.length() > 256
                || value.contains("/") || value.contains("\\")
                || ".".equals(value) || "..".equals(value)) {
            throw new IOException("COMMAND_LOCATOR_INVALID");
        }
        return value;
    }

    private static String string(Map<String, Object> value, String key) throws IOException {
        if (!(value.get(key) instanceof String text)) {
            throw new CommandFormatException("COMMAND_FIELD_MISSING:" + key);
        }
        return text;
    }

    private static long number(Map<String, Object> value) throws IOException {
        if (!(value.get("updatedAtEpochMillis") instanceof Number number)) {
            throw new CommandFormatException("COMMAND_FIELD_MISSING:updatedAtEpochMillis");
        }
        return number.longValue();
    }

    /**
     * Cleans one dead anchor after the supplied retention interval.
     *
     * @param namespaceRoot host-wide command namespace root
     * @param anchorId      exact process anchor ID
     * @param now           deterministic current time
     * @param retention     required diagnostic retention interval
     * @return cleanup result
     * @throws IOException when state is missing, corrupt, unsupported, pinned, or still live
     */
    public CleanupResult cleanupDeadAnchor(Path namespaceRoot, String anchorId, Instant now, Duration retention)
            throws IOException {
        Objects.requireNonNull(namespaceRoot, "namespaceRoot");
        Objects.requireNonNull(anchorId, "anchorId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(retention, "retention");
        Path root = namespaceRoot.toAbsolutePath()
                .normalize();
        Path anchorPath = root.resolve("process-scopes")
                .resolve(locator(anchorId))
                .resolve("anchor.json");
        ProjectCommandProcessAnchor anchor = ProjectCommandNamespace.readAnchor(root, anchorId);
        requireDead(anchor);

        Path scope = root.resolve("scopes")
                .resolve(locator(anchor.scopeLocator()));
        Path records = scope.resolve("records");
        List<Map<String, Object>> terminal = readRecords(records, anchorId);
        long latest = anchor.createdAtEpochMillis();
        for (Map<String, Object> value : terminal) {
            latest = Math.max(latest, number(value));
            if (Boolean.TRUE.equals(value.get("pinned"))
                    || Boolean.TRUE.equals(value.get("repairPinned"))
                    || Boolean.TRUE.equals(value.get("reconciliationPinned"))
                    || Boolean.TRUE.equals(value.get("acceptancePinned"))
                    || Boolean.TRUE.equals(value.get("checkpointPinned"))) {
                throw new IOException("COMMAND_PINNED_EVIDENCE_RETAINED");
            }
        }
        if (now.toEpochMilli() - latest < retention.toMillis()) {
            throw new IOException("COMMAND_DEAD_ANCHOR_RETENTION_ACTIVE");
        }

        Path lockPath = root.resolve("locks")
                .resolve(locator(anchor.scopeLocator()) + ".lock");
        if (!Files.isRegularFile(lockPath) || Files.isSymbolicLink(lockPath)) {
            throw new IOException("COMMAND_PERMANENT_LOCK_MISSING");
        }
        try (ProjectCommandNamespace firstPass = ProjectCommandNamespace.open(root)) {
            // Namespace pass one validates and freezes the bounded object set
            // before the physical-worktree lock is acquired.
            if (!firstPass.isHeld()) {
                throw new IOException("COMMAND_NAMESPACE_LOCK_UNAVAILABLE");
            }
        }
        try (CommandPermanentLock worktreeLock = CommandPermanentLock.open(lockPath);
                ProjectCommandNamespace namespace = ProjectCommandNamespace.open(root)) {
            if (!worktreeLock.isHeld() || !namespace.isHeld()) {
                throw new IOException("COMMAND_PROTECTION_UNAVAILABLE");
            }
            ProjectCommandProcessAnchor current = ProjectCommandNamespace.readAnchor(root, anchorId);
            requireDead(current);
            List<Map<String, Object>> reread = readRecords(records, anchorId);
            Path history = scope.resolve("terminal-history");
            Files.createDirectories(history);
            Map<String, Object> compacted = new LinkedHashMap<>();
            compacted.put("anchorId", anchorId);
            compacted.put("scopeLocator", current.scopeLocator());
            compacted.put("processIdentityDigest", ProjectCommandCanonicalizer.requestKey(
                    current.processIdentity()
                            .executableIdentity(),
                    current.processIdentity()
                            .commandLine() + ":" + current.processIdentity()
                            .processStartTime()));
            compacted.put("records", compactedRecords(reread));
            compacted.put("compactedAtEpochMillis", now.toEpochMilli());
            String json = ProviderJson.write(CommandDurableFormat.withIntegrity(compacted));
            if (json.getBytes(StandardCharsets.UTF_8).length > ProjectCommandNamespace.MAX_SCOPE_BYTES) {
                throw new IOException("COMMAND_TERMINAL_HISTORY_SIZE_LIMIT");
            }
            Path historyPath = history.resolve(anchorId + "-" + UUID.randomUUID() + ".json");
            atomicWrite(historyPath, json);
            for (Map<String, Object> value : reread) {
                Path record = records.resolve(ProjectCommandCanonicalizer.requestKey(
                        string(value, "anchorId"), string(value, "requestId")) + ".json");
                Files.deleteIfExists(record);
            }
            if (Files.exists(anchorPath)) {
                Files.delete(anchorPath);
            }
            Files.deleteIfExists(anchorPath.getParent());
            namespace.writeIndex(Map.of("scopeCount", countDirectories(root.resolve("scopes")),
                    "anchorCount", countDirectories(root.resolve("process-scopes")),
                    "terminalHistoryCompactions", 1L));
            return new CleanupResult(anchorId, reread.size(), historyPath, true);
        }
    }

    /**
     * Immutable cleanup result.
     *
     * @param anchorId             removed anchor ID
     * @param compactedRecordCount number of terminal records compacted
     * @param historyPath          compact terminal-history evidence path
     * @param lockRetained         whether the permanent worktree lock remains
     */
    public record CleanupResult(String anchorId, int compactedRecordCount, Path historyPath, boolean lockRetained) {

    }
}
