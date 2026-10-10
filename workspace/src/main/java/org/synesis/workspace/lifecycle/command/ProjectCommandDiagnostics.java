package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Performs a bounded, read-only projection of the durable command namespace.
 */
public final class ProjectCommandDiagnostics {

    private ProjectCommandDiagnostics() {
    }

    /**
     * Inspects the namespace without creating directories, taking locks, or changing state.
     *
     * @param namespaceRoot command namespace root
     * @return bounded diagnostic projection
     */
    public static Report inspect(Path namespaceRoot) {
        Objects.requireNonNull(namespaceRoot, "namespaceRoot");
        Path root = namespaceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return Report.absent();
        }
        Counters counters = new Counters();
        Path index = root.resolve(ProjectCommandNamespace.INDEX_FILE);
        Map<String, Object> indexValue = readObject(index, ProjectCommandNamespace.MAX_INDEX_BYTES, counters);
        if (indexValue == null) {
            counters.corruptObjectCount++;
        }
        enumerateLocks(root.resolve(ProjectCommandNamespace.LOCKS), counters);
        enumerateScopes(root.resolve(ProjectCommandNamespace.SCOPES), counters);
        enumerateAnchors(root.resolve(ProjectCommandNamespace.PROCESS_SCOPES), counters);
        enumerateTemporaries(root, counters);
        counters.liveAtCapacityCount = counters.recordCounts.entrySet().stream().filter(entry -> entry.getValue() >= ProjectCommandNamespace.MAX_REQUEST_IDS_PER_LIVE_ANCHOR && !counters.deadAnchorIds.contains(entry.getKey())).mapToInt(ignored -> 1).sum();

        boolean staleIndex = indexValue != null && number(indexValue, "scopeCount") != counters.scopeCount;
        return new Report(true,
                counters.formatValid,
                counters.newerObjectCount,
                counters.olderFormatCount,
                counters.corruptObjectCount,
                counters.permanentLockCount,
                counters.scopeCount,
                counters.anchorCount,
                counters.requestCount,
                counters.liveAtCapacityCount,
                counters.deadAnchorCount,
                counters.eligibleTerminalCount,
                counters.pinnedEvidenceCount,
                staleIndex ? 1 : 0,
                counters.temporaryArtifactCount,
                counters.terminalHistoryCompactionCount,
                counters.leaseGapRevisionMismatchCount,
                counters.admissionRestartCount,
                counters.cleanCloseDetachBlockedCount,
                counters.deferredMutationCount,
                counters.enumerationComplete);
    }

    private static void enumerateLocks(Path locks, Counters counters) {
        if (!Files.isDirectory(locks)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(locks)) {
            for (Path path : stream) {
                if (++counters.inspected > ProjectCommandNamespace.MAX_INSPECTED_ENTRIES) {
                    counters.enumerationComplete = false;
                    return;
                }
                if (path.getFileName().toString().endsWith(".lock")) {
                    if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                        counters.corruptObjectCount++;
                        counters.formatValid = false;
                    } else {
                        counters.permanentLockCount++;
                    }
                }
            }
        } catch (IOException failure) {
            counters.enumerationComplete = false;
        }
    }

    private static void enumerateScopes(Path scopes, Counters counters) {
        if (!Files.isDirectory(scopes)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(scopes)) {
            for (Path scope : stream) {
                if (++counters.inspected > ProjectCommandNamespace.MAX_INSPECTED_ENTRIES) {
                    counters.enumerationComplete = false;
                    return;
                }
                if (!Files.isDirectory(scope) || Files.isSymbolicLink(scope)) {
                    counters.corruptObjectCount++;
                    counters.formatValid = false;
                    continue;
                }
                counters.scopeCount++;
                readObject(scope.resolve(ProjectCommandNamespace.SCOPE_FILE), ProjectCommandNamespace.MAX_SCOPE_BYTES, counters);
                Path records = scope.resolve(ProjectCommandNamespace.RECORDS);
                int recordsForScope = 0;
                if (Files.isDirectory(records)) {
                    try (DirectoryStream<Path> recordStream = Files.newDirectoryStream(records, "*.json")) {
                        for (Path record : recordStream) {
                            if (++counters.inspected > ProjectCommandNamespace.MAX_INSPECTED_ENTRIES) {
                                counters.enumerationComplete = false;
                                return;
                            }
                            Map<String, Object> value = readObject(record, ProjectCommandNamespace.MAX_SCOPE_BYTES, counters);
                            if (value == null) {
                                continue;
                            }
                            recordsForScope++;
                            counters.requestCount++;
                            if (value.get("anchorId") instanceof String anchorId) {
                                counters.recordCounts.merge(anchorId, 1, Integer::sum);
                            }
                            String phase = value.get("phase") instanceof String text ? text : "";
                            if (ProjectCommandPhase.TERMINAL.name().equals(phase)) {
                                if (ProjectCommandRecord.pinnedForCensus(value)) {
                                    counters.pinnedEvidenceCount++;
                                } else {
                                    counters.eligibleTerminalCount++;
                                }
                            }
                            scanDiagnostics(value, counters);
                        }
                    } catch (IOException failure) {
                        counters.enumerationComplete = false;
                    }
                }
                if (recordsForScope > ProjectCommandNamespace.MAX_RECORDS_PER_SCOPE) {
                    counters.corruptObjectCount++;
                    counters.formatValid = false;
                }
                Path history = scope.resolve(ProjectCommandNamespace.TERMINAL_HISTORY);
                if (Files.isDirectory(history)) {
                    try (DirectoryStream<Path> historyStream = Files.newDirectoryStream(history, "*.json")) {
                        for (Path ignored : historyStream) {
                            counters.terminalHistoryCompactionCount++;
                        }
                    } catch (IOException failure) {
                        counters.enumerationComplete = false;
                    }
                }
            }
        } catch (IOException failure) {
            counters.enumerationComplete = false;
        }
    }

    private static void enumerateAnchors(Path anchors, Counters counters) {
        if (!Files.isDirectory(anchors)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(anchors)) {
            for (Path directory : stream) {
                if (++counters.inspected > ProjectCommandNamespace.MAX_INSPECTED_ENTRIES) {
                    counters.enumerationComplete = false;
                    return;
                }
                if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
                    counters.corruptObjectCount++;
                    counters.formatValid = false;
                    continue;
                }
                counters.anchorCount++;
                Map<String, Object> value = readObject(directory.resolve(ProjectCommandNamespace.ANCHOR_FILE), ProjectCommandNamespace.MAX_ANCHOR_BYTES, counters);
                if (value == null) {
                    continue;
                }
                Object raw = value.get("processIdentity");
                if (raw instanceof Map<?, ?> process && process.get("pid") instanceof Number pid && process.get("processStartTime") instanceof Number start) {
                    if (ProjectCommandProcessAnchor.startTimeGone(pid.longValue(), start.longValue())) {
                        counters.deadAnchorCount++;
                        if (value.get("anchorId") instanceof String anchorId) {
                            counters.deadAnchorIds.add(anchorId);
                        }
                    }
                }
            }
        } catch (IOException failure) {
            counters.enumerationComplete = false;
        }
    }

    private static void enumerateTemporaries(Path root, Counters counters) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.endsWith(".tmp") || name.contains(".tmp-")) {
                    counters.temporaryArtifactCount++;
                    if (counters.temporaryArtifactCount > ProjectCommandNamespace.MAX_TEMPORARY_ENTRIES) {
                        counters.enumerationComplete = false;
                        return;
                    }
                }
            }
        } catch (IOException failure) {
            counters.enumerationComplete = false;
        }
    }

    private static Map<String, Object> readObject(Path path, int maxBytes, Counters counters) {
        try {
            Map<String, Object> value = CommandDurableFormat.readUnverified(path, maxBytes, ProjectCommandNamespace.DURABLE_OBJECT);
            try {
                CommandDurableFormat.verify(value);
            } catch (CommandFormatException format) {
                counters.formatValid = false;
                if (format.getMessage().contains("NEWER")) {
                    counters.newerObjectCount++;
                } else {
                    counters.corruptObjectCount++;
                }
            }
            long schema = number(value, "schemaVersion");
            if (schema < CommandDurableFormat.SCHEMA_VERSION) {
                counters.olderFormatCount++;
            }
            return value;
        } catch (Exception failure) {
            counters.corruptObjectCount++;
            counters.formatValid = false;
            return null;
        }
    }

    private static long number(Map<String, Object> value, String key) {
        return value.get(key) instanceof Number number ? number.longValue() : -1L;
    }

    private static void scanDiagnostics(Map<String, Object> value, Counters counters) {
        String text = String.valueOf(value);
        if (text.contains("LEASE_GAP")) {
            counters.leaseGapRevisionMismatchCount++;
        }
        if (text.contains("RESTART")) {
            counters.admissionRestartCount++;
        }
        if (text.contains("DETACH") && text.contains("BLOCK")) {
            counters.cleanCloseDetachBlockedCount++;
        }
        if (text.contains("DEFERRED")) {
            counters.deferredMutationCount++;
        }
    }

    /**
     * Aggregates bounded command namespace diagnostic counts.
     */
    private static final class Counters {

        private final Map<String, Integer> recordCounts = new HashMap<>();
        private final Set<String> deadAnchorIds = new HashSet<>();
        private boolean formatValid = true;
        private boolean enumerationComplete = true;
        private int inspected;
        private int newerObjectCount;
        private int olderFormatCount;
        private int corruptObjectCount;
        private int permanentLockCount;
        private int scopeCount;
        private int anchorCount;
        private int requestCount;
        private int liveAtCapacityCount;
        private int deadAnchorCount;
        private int eligibleTerminalCount;
        private int pinnedEvidenceCount;
        private int temporaryArtifactCount;
        private int terminalHistoryCompactionCount;
        private int leaseGapRevisionMismatchCount;
        private int admissionRestartCount;
        private int cleanCloseDetachBlockedCount;
        private int deferredMutationCount;
    }

    /**
     * Immutable bounded command namespace diagnostic projection.
     *
     * @param present                        namespace exists
     * @param formatValid                    all inspected objects have supported valid formats
     * @param newerObjectCount               unsupported newer objects
     * @param olderFormatCount               objects awaiting migration
     * @param corruptObjectCount             corrupt or substituted objects
     * @param permanentLockCount             permanent lock files
     * @param scopeCount                     physical-worktree scopes
     * @param anchorCount                    process anchors
     * @param requestCount                   inspected request records
     * @param liveAtCapacityCount            live anchors at request capacity
     * @param deadAnchorCount                anchors whose process evidence is no longer live
     * @param eligibleTerminalCount          unpinned terminal records eligible for retention review
     * @param pinnedEvidenceCount            pinned terminal evidence
     * @param staleIndexCount                stale index observations
     * @param temporaryArtifactCount         temporary files observed
     * @param terminalHistoryCompactionCount compacted terminal-history objects
     * @param leaseGapRevisionMismatchCount  lease-gap mismatch diagnostics
     * @param admissionRestartCount          admission restart diagnostics
     * @param cleanCloseDetachBlockedCount   blocked detach diagnostics
     * @param deferredMutationCount          deferred mutation diagnostics
     * @param enumerationComplete            whether all bounded directories reached end
     */
    public record Report(boolean present, boolean formatValid, int newerObjectCount, int olderFormatCount, int corruptObjectCount, int permanentLockCount, int scopeCount, int anchorCount, int requestCount,
                         int liveAtCapacityCount, int deadAnchorCount, int eligibleTerminalCount, int pinnedEvidenceCount, int staleIndexCount, int temporaryArtifactCount, int terminalHistoryCompactionCount,
                         int leaseGapRevisionMismatchCount, int admissionRestartCount, int cleanCloseDetachBlockedCount, int deferredMutationCount, boolean enumerationComplete) {

        /**
         * Builds the typed diagnostic fields attached to a next-action response.
         *
         * @return insertion-ordered durable-command diagnostics
         */
        public Map<String, Object> nextActionDetails() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("namespacePresent", present);
            details.put("formatValid", formatValid);
            details.put("newerObjects", newerObjectCount);
            details.put("olderFormats", olderFormatCount);
            details.put("permanentLocks", permanentLockCount);
            details.put("scopes", scopeCount);
            details.put("anchors", anchorCount);
            details.put("requests", requestCount);
            details.put("liveAtCapacity", liveAtCapacityCount);
            details.put("deadAnchors", deadAnchorCount);
            details.put("terminalEligible", eligibleTerminalCount);
            details.put("pinnedEvidence", pinnedEvidenceCount);
            details.put("staleIndex", staleIndexCount);
            details.put("enumerationComplete", enumerationComplete);
            details.put("terminalHistoryCompactions", terminalHistoryCompactionCount);
            details.put("leaseGapRevisionMismatches", leaseGapRevisionMismatchCount);
            details.put("admissionRestarts", admissionRestartCount);
            details.put("cleanCloseDetachBlocked", cleanCloseDetachBlockedCount);
            details.put("deferredMutations", deferredMutationCount);
            return details;
        }

        /**
         * Builds the string-valued fields attached to doctor findings.
         *
         * @return insertion-ordered command-namespace finding details
         */
        public Map<String, String> doctorDetails() {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("newerObjects", String.valueOf(newerObjectCount));
            details.put("olderFormats", String.valueOf(olderFormatCount));
            details.put("corruptObjects", String.valueOf(corruptObjectCount));
            details.put("permanentLocks", String.valueOf(permanentLockCount));
            details.put("scopes", String.valueOf(scopeCount));
            details.put("anchors", String.valueOf(anchorCount));
            details.put("requests", String.valueOf(requestCount));
            details.put("liveAtCapacity", String.valueOf(liveAtCapacityCount));
            details.put("deadAnchors", String.valueOf(deadAnchorCount));
            details.put("terminalEligible", String.valueOf(eligibleTerminalCount));
            details.put("pinnedEvidence", String.valueOf(pinnedEvidenceCount));
            details.put("staleIndex", String.valueOf(staleIndexCount));
            details.put("temporaryArtifacts", String.valueOf(temporaryArtifactCount));
            details.put("terminalHistoryCompactions", String.valueOf(terminalHistoryCompactionCount));
            details.put("enumerationComplete", String.valueOf(enumerationComplete));
            return details;
        }

        private static Report absent() {
            return new Report(false, true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, true);
        }
    }
}
