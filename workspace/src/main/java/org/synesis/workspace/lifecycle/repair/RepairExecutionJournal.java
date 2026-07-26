package org.synesis.workspace.lifecycle.repair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.lifecycle.cleanup.LifecyclePathVerifier;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Append-only execution journal logging repair execution events under
 * {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-executions\<execution-id>.jsonl}.
 *
 * @since 1.0
 */
public final class RepairExecutionJournal {

    /**
     * Immutable execution record.
     *
     * @param executionId          opaque execution ID
     * @param planId               repair plan ID
     * @param entryId              plan entry ID
     * @param action               repair action
     * @param targetPath           target path
     * @param status               execution status state
     * @param timestampEpochMillis timestamp
     * @param details              explanation message
     */
    public record RepairExecutionRecord(
            String executionId,
            String planId,
            String entryId,
            String action,
            String targetPath,
            String status,
            long timestampEpochMillis,
            String details
    ) {
        /**
         * Validates non-null field invariants.
         */
        public RepairExecutionRecord {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(entryId, "entryId");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(targetPath, "targetPath");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(details, "details");
        }
    }

    private final Path journalFile;

    private RepairExecutionJournal(Path journalFile) {
        this.journalFile = journalFile;
    }

    /**
     * Creates or opens a repair execution journal.
     *
     * @param controlRoot control project root path
     * @param executionId opaque execution ID
     * @return open journal instance
     * @throws IOException if journal directory creation fails
     */
    public static RepairExecutionJournal open(Path controlRoot, String executionId) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(executionId, "executionId");

        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path journalDir = workspaceRoot.resolve("admin").resolve("repair-executions");
        Files.createDirectories(journalDir);
        Path file = journalDir.resolve(executionId + ".jsonl");

        return new RepairExecutionJournal(file);
    }

    /**
     * Appends an execution record to the JSONL log file.
     *
     * @param record execution record to append
     * @throws IOException if appending fails
     */
    public synchronized void append(RepairExecutionRecord record) throws IOException {
        Objects.requireNonNull(record, "record");

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("executionId", record.executionId());
        map.put("planId", record.planId());
        map.put("entryId", record.entryId());
        map.put("action", record.action());
        map.put("targetPath", record.targetPath());
        map.put("status", record.status());
        map.put("timestampEpochMillis", record.timestampEpochMillis());
        map.put("details", record.details());

        String jsonLine = ProviderJson.write(map) + "\n";
        Files.writeString(
                journalFile,
                jsonLine,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        );
    }

    /**
     * Reads all historical execution records from journal file.
     *
     * @return list of execution records
     * @throws IOException if reading fails
     */
    public synchronized List<RepairExecutionRecord> readAll() throws IOException {
        if (!Files.exists(journalFile)) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(journalFile, StandardCharsets.UTF_8);
        List<RepairExecutionRecord> result = new ArrayList<>();

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(line);
            result.add(new RepairExecutionRecord(
                    (String) map.get("executionId"),
                    (String) map.get("planId"),
                    (String) map.get("entryId"),
                    (String) map.get("action"),
                    (String) map.get("targetPath"),
                    (String) map.get("status"),
                    ((Number) map.get("timestampEpochMillis")).longValue(),
                    (String) map.get("details")
            ));
        }

        return Collections.unmodifiableList(result);
    }
}
