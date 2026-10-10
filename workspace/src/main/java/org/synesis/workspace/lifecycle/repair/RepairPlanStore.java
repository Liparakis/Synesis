package org.synesis.workspace.lifecycle.repair;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.doctor.DoctorFindingCode;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativePlanFiles;
import org.synesis.workspace.lifecycle.ProjectAdministrativeLayout;

/**
 * Storage service for persisting and loading immutable repair plans outside control checkout under
 * {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-plans\<plan-id>.json}.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class RepairPlanStore {

    /**
     * Creates a repair plan store.
     */
    public RepairPlanStore() {
    }

    private static Map<String, Object> toSerializableMap(RepairPlan plan) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", plan.schemaVersion());
        map.put("planId", plan.planId());
        map.put("projectId", plan.projectId());
        map.put("controlRepositoryPath", plan.controlRepositoryPath());
        map.put("externalWorkspaceRoot", plan.externalWorkspaceRoot());
        map.put("createdAtEpochMillis", plan.createdAtEpochMillis());
        map.put("doctorReportFingerprint", plan.doctorReportFingerprint());
        map.put("supportedRepairsCount", plan.supportedRepairsCount());
        map.put("unsupportedCount", plan.unsupportedCount());
        map.put("contentHash", plan.contentHash());

        List<Map<String, Object>> entriesList = new ArrayList<>();
        for (RepairPlanEntry e : plan.entries()) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("schemaVersion", e.schemaVersion());
            em.put("entryId", e.entryId());
            em.put("findingCode", e.findingCode().name());
            em.put("action", e.action().name());
            em.put("targetPath", e.targetPath());
            em.put("targetFingerprint", e.targetFingerprint());
            em.put("executable", e.executable());
            em.put("reasons", e.reasons());
            em.put("summary", e.summary());
            em.put("backupRequired", e.backupRequired());
            entriesList.add(em);
        }
        map.put("entries", entriesList);

        return map;
    }

    @SuppressWarnings("unchecked")
    private static RepairPlan fromSerializableMap(String rawJson) throws IOException {
        try {
            Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(rawJson);
            int schemaVersion = ((Number) map.get("schemaVersion")).intValue();
            String planId = (String) map.get("planId");
            String projectId = (String) map.get("projectId");
            String controlRepo = (String) map.get("controlRepositoryPath");
            String workspaceRoot = (String) map.get("externalWorkspaceRoot");
            long createdAt = ((Number) map.get("createdAtEpochMillis")).longValue();
            String docFingerprint = (String) map.get("doctorReportFingerprint");
            int supported = ((Number) map.get("supportedRepairsCount")).intValue();
            int unsupported = ((Number) map.get("unsupportedCount")).intValue();
            String contentHash = (String) map.get("contentHash");

            List<Map<String, Object>> entriesRaw = (List<Map<String, Object>>) map.get("entries");
            List<RepairPlanEntry> entries = new ArrayList<>();
            if (entriesRaw != null) {
                for (Map<String, Object> em : entriesRaw) {
                    entries.add(new RepairPlanEntry(((Number) em.get("schemaVersion")).intValue(),
                            (String) em.get("entryId"),
                            DoctorFindingCode.valueOf((String) em.get("findingCode")),
                            RepairAction.valueOf((String) em.get("action")),
                            (String) em.get("targetPath"),
                            (String) em.get("targetFingerprint"),
                            (Boolean) em.get("executable"),
                            (List<String>) em.get("reasons"),
                            (String) em.get("summary"),
                            (Boolean) em.get("backupRequired")));
                }
            }

            return new RepairPlan(schemaVersion, planId, projectId, controlRepo, workspaceRoot, createdAt, docFingerprint, supported, unsupported, contentHash, entries);
        } catch (Exception ex) {
            throw new IOException("Failed to parse repair plan JSON", ex);
        }
    }

    /**
     * Saves a repair plan to disk with canonical content hashing.
     *
     * @param controlRoot             control project root path
     * @param projectId               project ID
     * @param doctorReportFingerprint doctor report fingerprint hash
     * @param entries                 list of plan entries
     * @return saved persisted repair plan instance
     * @throws IOException if saving fails
     */
    public RepairPlan createAndSave(Path controlRoot, String projectId, String doctorReportFingerprint, List<RepairPlanEntry> entries) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(doctorReportFingerprint, "doctorReportFingerprint");
        Objects.requireNonNull(entries, "entries");

        Path root = controlRoot.toAbsolutePath().normalize();
        Path workspaceRoot = ProjectAdministrativeLayout.workspaceRoot(root);
        String planId = "repairplan-" + UUID.randomUUID().toString().replace("-", "");

        int supported = (int) entries.stream().filter(RepairPlanEntry::executable).count();
        int unsupported = entries.size() - supported;

        RepairPlan unsignedPlan = new RepairPlan(1, planId, projectId, root.toString(), workspaceRoot.toString(), System.currentTimeMillis(), doctorReportFingerprint, supported, unsupported, "UNSIGNED", entries);

        RepairPlan finalPlan = unsignedPlan.withContentHash(AdministrativePlanFiles.contentHash(toSerializableMap(unsignedPlan)));
        AdministrativePlanFiles.write(ProjectAdministrativeLayout.repairPlans(root), planId, toSerializableMap(finalPlan));

        return finalPlan;
    }

    /**
     * Loads and verifies a persisted repair plan by plan ID.
     *
     * @param controlRoot control project root path
     * @param planId      opaque plan ID
     * @return loaded and verified persisted repair plan
     * @throws IOException if plan is missing, tampered, or invalid
     */
    public RepairPlan load(Path controlRoot, String planId) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");

        Path root = controlRoot.toAbsolutePath().normalize();
        return AdministrativePlanFiles.load(root, ProjectAdministrativeLayout.repairPlans(root), "Repair", planId, RepairPlanStore::fromSerializableMap, RepairPlanStore::toSerializableMap);
    }

}
