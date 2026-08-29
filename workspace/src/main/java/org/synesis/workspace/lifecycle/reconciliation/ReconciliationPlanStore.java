package org.synesis.workspace.lifecycle.reconciliation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.PlanIntegrity;
import org.synesis.workspace.lifecycle.cleanup.LifecyclePathVerifier;

/**
 * Storage service for persisting and loading immutable reconciliation plans outside the control checkout.
 *
 * <p>Plans are stored under
 * {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\reconciliation-plans\<plan-id>.json}.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class ReconciliationPlanStore {

    /**
     * Creates a reconciliation plan store.
     */
    public ReconciliationPlanStore() {
    }

    /**
     * Resolves the directory path for storing administrative reconciliation plans.
     *
     * @param controlRoot control project root path
     * @return normalized administrative reconciliation plans directory path
     */
    public static Path resolvePlansDirectory(Path controlRoot) {
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        return workspaceRoot.resolve("admin")
                .resolve("reconciliation-plans");
    }

    private static String serializeCanonical(ReconciliationPlan plan) {
        return ProviderJson.write(toSerializableMap(plan));
    }

    private static Map<String, Object> toSerializableMap(ReconciliationPlan plan) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", plan.schemaVersion());
        map.put("planId", plan.planId());
        map.put("projectId", plan.projectId());
        map.put("controlRepositoryPath", plan.controlRepositoryPath());
        map.put("externalWorkspaceRoot", plan.externalWorkspaceRoot());
        map.put("createdAtEpochMillis", plan.createdAtEpochMillis());
        map.put("totalInspectedCount", plan.totalInspectedCount());
        map.put("executableCount", plan.executableCount());
        map.put("contentHash", plan.contentHash());

        List<Map<String, Object>> entriesList = new ArrayList<>();
        for (ReconciliationPlanEntry e : plan.entries()) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("schemaVersion", e.schemaVersion());
            em.put("actionId", e.actionId());
            em.put("action",
                    e.action()
                            .name());
            em.put("targetResourceId", e.targetResourceId());
            em.put("executable", e.executable());
            em.put("reasons", e.reasons());
            em.put("preconditionSummary", e.preconditionSummary());
            entriesList.add(em);
        }
        map.put("entries", entriesList);

        return map;
    }

    @SuppressWarnings("unchecked")
    private static ReconciliationPlan fromSerializableMap(String rawJson) throws IOException {
        try {
            Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(rawJson);
            int schemaVersion = ((Number) map.get("schemaVersion")).intValue();
            String planId = (String) map.get("planId");
            String projectId = (String) map.get("projectId");
            String controlRepo = (String) map.get("controlRepositoryPath");
            String workspaceRoot = (String) map.get("externalWorkspaceRoot");
            long createdAt = ((Number) map.get("createdAtEpochMillis")).longValue();
            int totalInspected = ((Number) map.get("totalInspectedCount")).intValue();
            int executableCount = ((Number) map.get("executableCount")).intValue();
            String contentHash = (String) map.get("contentHash");

            List<Map<String, Object>> entriesRaw = (List<Map<String, Object>>) map.get("entries");
            List<ReconciliationPlanEntry> entries = new ArrayList<>();
            if (entriesRaw != null) {
                for (Map<String, Object> em : entriesRaw) {
                    entries.add(new ReconciliationPlanEntry(
                            ((Number) em.get("schemaVersion")).intValue(),
                            (String) em.get("actionId"),
                            ReconciliationAction.valueOf((String) em.get("action")),
                            (String) em.get("targetResourceId"),
                            (Boolean) em.get("executable"),
                            (List<String>) em.get("reasons"),
                            (String) em.get("preconditionSummary")
                    ));
                }
            }

            return new ReconciliationPlan(schemaVersion,
                    planId,
                    projectId,
                    controlRepo,
                    workspaceRoot,
                    createdAt,
                    totalInspected,
                    executableCount,
                    contentHash,
                    entries);
        } catch (Exception ex) {
            throw new IOException("Failed to parse reconciliation plan JSON", ex);
        }
    }

    /**
     * Saves a reconciliation plan to disk with canonical content hashing.
     *
     * @param controlRoot         control project root path
     * @param projectId           project ID
     * @param totalInspectedCount count of total inspected resources
     * @param entries             list of plan entries
     * @return saved persisted reconciliation plan instance
     * @throws IOException if saving fails
     */
    public ReconciliationPlan createAndSave(
            Path controlRoot,
            String projectId,
            int totalInspectedCount,
            List<ReconciliationPlanEntry> entries
    ) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(entries, "entries");

        Path root = controlRoot.toAbsolutePath()
                .normalize();
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);
        String planId = "recplan-" + UUID.randomUUID()
                .toString()
                .replace("-", "");

        int executableCount = (int) entries.stream()
                .filter(ReconciliationPlanEntry::executable)
                .count();

        ReconciliationPlan unsignedPlan = new ReconciliationPlan(
                1,
                planId,
                projectId,
                root.toString(),
                workspaceRoot.toString(),
                System.currentTimeMillis(),
                totalInspectedCount,
                executableCount,
                "UNSIGNED",
                entries
        );

        String canonicalContent = serializeCanonical(unsignedPlan);
        String contentHash = PlanIntegrity.sha256Utf8(canonicalContent);

        ReconciliationPlan finalPlan = new ReconciliationPlan(
                unsignedPlan.schemaVersion(),
                unsignedPlan.planId(),
                unsignedPlan.projectId(),
                unsignedPlan.controlRepositoryPath(),
                unsignedPlan.externalWorkspaceRoot(),
                unsignedPlan.createdAtEpochMillis(),
                unsignedPlan.totalInspectedCount(),
                unsignedPlan.executableCount(),
                contentHash,
                unsignedPlan.entries()
        );

        Path plansDir = resolvePlansDirectory(root);
        Files.createDirectories(plansDir);
        Path targetFile = plansDir.resolve(planId + ".json");
        Files.writeString(targetFile, ProviderJson.write(toSerializableMap(finalPlan)), StandardCharsets.UTF_8);

        return finalPlan;
    }

    /**
     * Loads and verifies a persisted reconciliation plan by plan ID.
     *
     * @param controlRoot control project root path
     * @param planId      opaque plan ID
     * @return loaded and verified persisted reconciliation plan
     * @throws IOException if plan is missing, tampered, or invalid
     */
    public ReconciliationPlan load(Path controlRoot, String planId) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");

        Path root = controlRoot.toAbsolutePath()
                .normalize();
        Path plansDir = resolvePlansDirectory(root);
        Path planFile = plansDir.resolve(planId + ".json");

        if (!Files.exists(planFile)) {
            throw new IOException("Reconciliation plan not found: " + planId);
        }

        String rawJson = Files.readString(planFile, StandardCharsets.UTF_8);
        ReconciliationPlan plan = fromSerializableMap(rawJson);

        if (!planId.equals(plan.planId())) {
            throw new IOException("Plan ID mismatch in store: expected " + planId + " but found " + plan.planId());
        }

        Path expectedWorkspace = LifecyclePathVerifier.resolveWorkspaceRoot(root);
        if (!expectedWorkspace.toString()
                .equals(plan.externalWorkspaceRoot())) {
            throw new IOException("External workspace root mismatch in plan: " + plan.externalWorkspaceRoot());
        }

        ReconciliationPlan unsigned = new ReconciliationPlan(
                plan.schemaVersion(), plan.planId(), plan.projectId(), plan.controlRepositoryPath(),
                plan.externalWorkspaceRoot(), plan.createdAtEpochMillis(), plan.totalInspectedCount(),
                plan.executableCount(), "UNSIGNED", plan.entries()
        );
        String expectedHash = PlanIntegrity.sha256Utf8(serializeCanonical(unsigned));
        if (!expectedHash.equals(plan.contentHash())) {
            throw new IOException("Reconciliation plan content hash integrity verification failed for " + planId);
        }

        return plan;
    }

}
