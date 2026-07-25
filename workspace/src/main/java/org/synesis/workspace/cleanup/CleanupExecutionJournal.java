package org.synesis.workspace.cleanup;

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
import org.synesis.workspace.provider.ProviderJson;

/**
 * Append-only execution journal tracking cleanup execution steps for crash safety and idempotency.
 *
 * <p>Journals are stored under {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\cleanup-executions\<execution-id>.jsonl}.
 *
 * @since 1.0
 */
public final class CleanupExecutionJournal {

    private final Path journalFile;
    private final String executionId;

    /**
     * Creates and opens an append-only cleanup execution journal.
     *
     * @param controlRoot control project root path
     * @param executionId execution run identifier
     * @throws IOException if directory creation fails
     */
    public CleanupExecutionJournal(Path controlRoot, String executionId) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        this.executionId = Objects.requireNonNull(executionId, "executionId");

        Path root = controlRoot.toAbsolutePath().normalize();
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);
        Path execDir = workspaceRoot.resolve("admin").resolve("cleanup-executions");
        Files.createDirectories(execDir);

        this.journalFile = execDir.resolve(executionId + ".jsonl");
    }

    /**
     * Appends an execution record to the journal file.
     *
     * @param record execution record to append
     * @throws IOException if writing fails
     */
    public synchronized void append(CleanupExecutionRecord record) throws IOException {
        Objects.requireNonNull(record, "record");

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("executionId", record.executionId());
        map.put("planId", record.planId());
        map.put("entryResourceId", record.entryResourceId());
        map.put("resourceType", record.resourceType().name());
        map.put("state", record.state().name());
        map.put("preconditionReason", record.preconditionReason());
        map.put("timestampEpochMillis", record.timestampEpochMillis());
        map.put("bytesReclaimed", record.bytesReclaimed());
        map.put("diagnosticDetails", record.diagnosticDetails());

        String jsonLine = ProviderJson.write(map) + "\n";
        Files.writeString(journalFile, jsonLine, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Reads all historical execution journals under the project's administration directory to find
     * resource IDs that were previously completed for a plan.
     *
     * @param controlRoot control project root path
     * @param planId      target plan identifier
     * @return set of resource IDs that have reached COMPLETED state for this plan
     */
    @SuppressWarnings("unchecked")
    public static Set<String> loadCompletedResourceIds(Path controlRoot, String planId) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");

        Set<String> completed = new HashSet<>();
        try {
            Path root = controlRoot.toAbsolutePath().normalize();
            Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);
            Path execDir = workspaceRoot.resolve("admin").resolve("cleanup-executions");
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
                            String resId = (String) map.get("entryResourceId");
                            String stateStr = (String) map.get("state");
                            if (planId.equals(pId) && "COMPLETED".equals(stateStr) && resId != null) {
                                completed.add(resId);
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
