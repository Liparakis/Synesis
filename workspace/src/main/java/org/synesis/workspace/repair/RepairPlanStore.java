package org.synesis.workspace.repair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.cleanup.LifecyclePathVerifier;
import org.synesis.workspace.doctor.DoctorFindingCode;
import org.synesis.workspace.provider.ProviderJson;

/**
 * Storage service for persisting and loading immutable repair plans outside control checkout under
 * {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-plans\<plan-id>.json}.
 *
 * @since 1.0
 */
public final class RepairPlanStore {

    /**
     * Creates a repair plan store.
     */
    public RepairPlanStore() {
    }

    /**
     * Resolves the directory path for storing administrative repair plans.
     *
     * @param controlRoot control project root path
     * @return normalized repair plans directory path
     */
    public static Path resolvePlansDirectory(Path controlRoot) {
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        return workspaceRoot.resolve("admin").resolve("repair-plans");
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
    public RepairPlan createAndSave(
            Path controlRoot,
            String projectId,
            String doctorReportFingerprint,
            List<RepairPlanEntry> entries
    ) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(doctorReportFingerprint, "doctorReportFingerprint");
        Objects.requireNonNull(entries, "entries");

        Path root = controlRoot.toAbsolutePath().normalize();
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);
        String planId = "repairplan-" + UUID.randomUUID().toString().replace("-", "");

        int supported = (int) entries.stream().filter(RepairPlanEntry::executable).count();
        int unsupported = entries.size() - supported;

        RepairPlan unsignedPlan = new RepairPlan(
                1,
                planId,
                projectId,
                root.toString(),
                workspaceRoot.toString(),
                System.currentTimeMillis(),
                doctorReportFingerprint,
                supported,
                unsupported,
                "UNSIGNED",
                entries
        );

        String canonicalContent = serializeCanonical(unsignedPlan);
        String contentHash = computeSha256(canonicalContent);

        RepairPlan finalPlan = new RepairPlan(
                unsignedPlan.schemaVersion(),
                unsignedPlan.planId(),
                unsignedPlan.projectId(),
                unsignedPlan.controlRepositoryPath(),
                unsignedPlan.externalWorkspaceRoot(),
                unsignedPlan.createdAtEpochMillis(),
                unsignedPlan.doctorReportFingerprint(),
                unsignedPlan.supportedRepairsCount(),
                unsignedPlan.unsupportedCount(),
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
        Path plansDir = resolvePlansDirectory(root);
        Path planFile = plansDir.resolve(planId + ".json");

        if (!Files.exists(planFile)) {
            throw new IOException("Repair plan not found: " + planId);
        }

        String rawJson = Files.readString(planFile, StandardCharsets.UTF_8);
        RepairPlan plan = fromSerializableMap(rawJson);

        if (!planId.equals(plan.planId())) {
            throw new IOException("Plan ID mismatch in store: expected " + planId + " but found " + plan.planId());
        }

        Path expectedWorkspace = LifecyclePathVerifier.resolveWorkspaceRoot(root);
        if (!expectedWorkspace.toString().equals(plan.externalWorkspaceRoot())) {
            throw new IOException("External workspace root mismatch in plan: " + plan.externalWorkspaceRoot());
        }

        RepairPlan unsigned = new RepairPlan(
                plan.schemaVersion(), plan.planId(), plan.projectId(), plan.controlRepositoryPath(),
                plan.externalWorkspaceRoot(), plan.createdAtEpochMillis(), plan.doctorReportFingerprint(),
                plan.supportedRepairsCount(), plan.unsupportedCount(), "UNSIGNED", plan.entries()
        );
        String expectedHash = computeSha256(serializeCanonical(unsigned));
        if (!expectedHash.equals(plan.contentHash())) {
            throw new IOException("Repair plan content hash integrity verification failed for " + planId);
        }

        return plan;
    }

    private static String serializeCanonical(RepairPlan plan) {
        return ProviderJson.write(toSerializableMap(plan));
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
                    entries.add(new RepairPlanEntry(
                            ((Number) em.get("schemaVersion")).intValue(),
                            (String) em.get("entryId"),
                            DoctorFindingCode.valueOf((String) em.get("findingCode")),
                            RepairAction.valueOf((String) em.get("action")),
                            (String) em.get("targetPath"),
                            (String) em.get("targetFingerprint"),
                            (Boolean) em.get("executable"),
                            (List<String>) em.get("reasons"),
                            (String) em.get("summary"),
                            (Boolean) em.get("backupRequired")
                    ));
                }
            }

            return new RepairPlan(schemaVersion, planId, projectId, controlRepo, workspaceRoot, createdAt, docFingerprint, supported, unsupported, contentHash, entries);
        } catch (Exception ex) {
            throw new IOException("Failed to parse repair plan JSON", ex);
        }
    }

    private static String computeSha256(String text) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 algorithm unavailable", ex);
        }
    }
}
