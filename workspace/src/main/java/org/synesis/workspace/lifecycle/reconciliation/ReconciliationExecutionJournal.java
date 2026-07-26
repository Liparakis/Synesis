package org.synesis.workspace.lifecycle.reconciliation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.synesis.workspace.lifecycle.cleanup.LifecyclePathVerifier;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Append-only execution journal tracking reconciliation execution steps for crash safety and idempotency.
 *
 * <p>Journals are stored under {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\reconciliation-executions\<execution-id>.jsonl}.
 *
 * @since 1.0
 */
public final class ReconciliationExecutionJournal {

    private final Path journalFile;

    /**
     * Creates and opens an append-only reconciliation execution journal.
     *
     * @param controlRoot control project root path
     * @param executionId execution run identifier
     * @throws IOException if directory creation fails
     */
    public ReconciliationExecutionJournal(Path controlRoot, String executionId) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(executionId, "executionId");

        Path root = controlRoot.toAbsolutePath().normalize();
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);
        Path execDir = workspaceRoot.resolve("admin").resolve("reconciliation-executions");
        Files.createDirectories(execDir);

        this.journalFile = execDir.resolve(executionId + ".jsonl");
    }

    /**
     * Appends an execution record to the journal file.
     *
     * @param record execution record to append
     * @throws IOException if writing fails
     */
    public synchronized void append(ReconciliationExecutionRecord record) throws IOException {
        Objects.requireNonNull(record, "record");

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("executionId", record.executionId());
        map.put("planId", record.planId());
        map.put("actionId", record.actionId());
        map.put("action", record.action().name());
        map.put("targetResourceId", record.targetResourceId());
        map.put("state", record.state());
        map.put("preconditionReason", record.preconditionReason());
        map.put("timestampEpochMillis", record.timestampEpochMillis());
        map.put("diagnosticDetails", record.diagnosticDetails());

        String jsonLine = ProviderJson.write(map) + "\n";
        Files.writeString(journalFile, jsonLine, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Reads all historical execution journals under the project's administration directory to find
     * action IDs that were previously completed for a plan.
     *
     * @param controlRoot control project root path
     * @param planId      target plan identifier
     * @return set of action IDs that have reached COMPLETED state for this plan
     */
    @SuppressWarnings("unchecked")
    public static Set<String> loadCompletedActionIds(Path controlRoot, String planId) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");

        Set<String> completed = new HashSet<>();
        try {
            Path root = controlRoot.toAbsolutePath().normalize();
            Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);
            Path execDir = workspaceRoot.resolve("admin").resolve("reconciliation-executions");
            if (!Files.isDirectory(execDir)) {
                return completed;
            }

            try (var stream = Files.list(execDir)) {
                for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (String line : lines) {
                            if (line.isBlank()) {
                                continue;
                            }
                            Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(line);
                            String pId = (String) map.get("planId");
                            String actId = (String) map.get("actionId");
                            String stateStr = (String) map.get("state");
                            if (planId.equals(pId) && "COMPLETED".equals(stateStr) && actId != null) {
                                completed.add(actId);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return completed;
    }
}
