package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Performs bounded dead-anchor terminal-evidence maintenance under existing cleanup authority.
 */
final class ProjectCommandMaintenanceService {

    /**
     * Unlike the store, cleanup refuses symbolic-link and oversized records and lets parse failures propagate.
     */
    private static final CommandDurableFormat.ReadCodes RECORD = new CommandDurableFormat.ReadCodes("COMMAND_RECORD_INVALID",
            "COMMAND_RECORD_SIZE_LIMIT",
            "COMMAND_RECORD_NOT_OBJECT",
            "COMMAND_RECORD_KEY_INVALID",
            null);

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
                if (!ProjectCommandPhase.TERMINAL.name().equals(string(value, "phase"))) {
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
        return CommandDurableFormat.read(path, ProjectCommandNamespace.MAX_SCOPE_BYTES, RECORD);
    }

    private static void requireDead(ProjectCommandProcessAnchor anchor) throws IOException {
        switch (anchor.liveness()) {
            case UNKNOWN -> throw new IOException("COMMAND_ANCHOR_PROCESS_LIVENESS_AMBIGUOUS");
            case LIVE -> throw new IOException("COMMAND_ANCHOR_PROCESS_STILL_LIVE");
            case DEAD -> {
            }
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
    CleanupResult cleanupDeadAnchor(Path namespaceRoot, String anchorId, Instant now, Duration retention) throws IOException {
        Objects.requireNonNull(namespaceRoot, "namespaceRoot");
        Objects.requireNonNull(anchorId, "anchorId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(retention, "retention");
        Path root = namespaceRoot.toAbsolutePath().normalize();
        Path anchorPath = ProjectCommandNamespace.anchorFile(root, ProjectCommandNamespace.requireLocator(anchorId));
        ProjectCommandProcessAnchor anchor = ProjectCommandNamespace.readAnchor(root, anchorId);
        requireDead(anchor);

        Path scope = ProjectCommandNamespace.scopeDirectory(root, ProjectCommandNamespace.requireLocator(anchor.scopeLocator()));
        Path records = scope.resolve(ProjectCommandNamespace.RECORDS);
        List<Map<String, Object>> terminal = readRecords(records, anchorId);
        long latest = anchor.createdAtEpochMillis();
        for (Map<String, Object> value : terminal) {
            latest = Math.max(latest, number(value));
            if (ProjectCommandRecord.pinnedForCleanup(value)) {
                throw new IOException("COMMAND_PINNED_EVIDENCE_RETAINED");
            }
        }
        if (now.toEpochMilli() - latest < retention.toMillis()) {
            throw new IOException("COMMAND_DEAD_ANCHOR_RETENTION_ACTIVE");
        }

        Path lockPath = ProjectCommandNamespace.lockFile(root, ProjectCommandNamespace.requireLocator(anchor.scopeLocator()));
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
            Path history = scope.resolve(ProjectCommandNamespace.TERMINAL_HISTORY);
            Files.createDirectories(history);
            Map<String, Object> compacted = new LinkedHashMap<>();
            compacted.put("anchorId", anchorId);
            compacted.put("scopeLocator", current.scopeLocator());
            compacted.put("processIdentityDigest",
                    ProjectCommandCanonicalizer.requestKey(current.processIdentity().executableIdentity(), current.processIdentity().commandLine() + ":" + current.processIdentity().processStartTime()));
            compacted.put("records", compactedRecords(reread));
            compacted.put("compactedAtEpochMillis", now.toEpochMilli());
            Path historyPath = history.resolve(anchorId + "-" + UUID.randomUUID() + ".json");
            CommandDurableFormat.write(historyPath, CommandDurableFormat.withIntegrity(compacted), ProjectCommandNamespace.MAX_SCOPE_BYTES, "COMMAND_TERMINAL_HISTORY_SIZE_LIMIT");
            for (Map<String, Object> value : reread) {
                Path record = records.resolve(ProjectCommandCanonicalizer.requestKey(string(value, "anchorId"), string(value, "requestId")) + ".json");
                Files.deleteIfExists(record);
            }
            if (Files.exists(anchorPath)) {
                Files.delete(anchorPath);
            }
            Files.deleteIfExists(anchorPath.getParent());
            namespace.writeIndex(Map.of("scopeCount", countDirectories(root.resolve(ProjectCommandNamespace.SCOPES)), "anchorCount", countDirectories(root.resolve(ProjectCommandNamespace.PROCESS_SCOPES)), "terminalHistoryCompactions", 1L));
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
    record CleanupResult(String anchorId, int compactedRecordCount, Path historyPath, boolean lockRetained) {

    }
}
