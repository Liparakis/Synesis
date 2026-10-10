package org.synesis.workspace.lifecycle.cleanup.plan;

import org.synesis.workspace.lifecycle.AdministrativePlanFiles;
import org.synesis.workspace.lifecycle.ProjectAdministrativeLayout;

import org.synesis.workspace.lifecycle.cleanup.inventory.LifecycleResourceFingerprint;

import org.synesis.workspace.lifecycle.cleanup.inventory.LifecycleResourceType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Storage service for persisting and loading immutable cleanup plans outside the control checkout.
 *
 * <p>Plans are stored under
 * {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\cleanup-plans\<plan-id>.json}.
 *
 * @since 1.0
 */
@SuppressWarnings({"DuplicatedCode", "ExtractMethodRecommender"})
public final class CleanupPlanStore {

    /**
     * Creates a cleanup plan store.
     */
    public CleanupPlanStore() {
    }

    private static Map<String, Object> toSerializableMap(PersistedCleanupPlan plan) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", plan.schemaVersion());
        map.put("planId", plan.planId());
        map.put("projectId", plan.projectId());
        map.put("controlRepositoryPath", plan.controlRepositoryPath());
        map.put("externalWorkspaceRoot", plan.externalWorkspaceRoot());
        map.put("createdAtEpochMillis", plan.createdAtEpochMillis());
        map.put("retentionPolicySnapshot", plan.retentionPolicySnapshot());
        map.put("durableStateSequence", plan.durableStateSequence());
        map.put("totalDiscoveredCount", plan.totalDiscoveredCount());
        map.put("totalExecutableCount", plan.totalExecutableCount());
        map.put("totalEstimatedReclaimableBytes", plan.totalEstimatedReclaimableBytes());
        map.put("contentHash", plan.contentHash());

        List<Map<String, Object>> entriesList = new ArrayList<>();
        for (PersistedCleanupPlanEntry e : plan.entries()) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("schemaVersion", e.schemaVersion());
            em.put("resourceType", e.resourceType().name());
            em.put("resourceId", e.resourceId());
            em.put("resourcePath", e.resourcePath());
            em.put("classification", e.classification().name());
            em.put("eligible", e.eligible());
            em.put("reasons", e.reasons());
            em.put("estimatedBytes", e.estimatedBytes());
            em.put("pathSafetyCode", e.pathSafetyCode());
            em.put("proposedOperation", e.proposedOperation());

            Map<String, Object> fp = new LinkedHashMap<>();
            fp.put("normalizedIdentity", e.fingerprint().normalizedIdentity());
            fp.put("durableStateVersion", e.fingerprint().durableStateVersion());
            fp.put("gitHead", e.fingerprint().gitHead() != null ? e.fingerprint().gitHead() : "");
            fp.put("gitCommonDir", e.fingerprint().gitCommonDir() != null ? e.fingerprint().gitCommonDir() : "");
            fp.put("cleanStatusDigest", e.fingerprint().cleanStatusDigest() != null ? e.fingerprint().cleanStatusDigest() : "");
            fp.put("metadataHash", e.fingerprint().metadataHash());
            em.put("fingerprint", fp);

            entriesList.add(em);
        }
        map.put("entries", entriesList);

        return map;
    }

    @SuppressWarnings("unchecked")
    private static PersistedCleanupPlan fromSerializableMap(String rawJson) throws IOException {
        try {
            Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(rawJson);
            int schemaVersion = ((Number) map.get("schemaVersion")).intValue();
            String planId = (String) map.get("planId");
            String projectId = (String) map.get("projectId");
            String controlRepo = (String) map.get("controlRepositoryPath");
            String workspaceRoot = (String) map.get("externalWorkspaceRoot");
            long createdAt = ((Number) map.get("createdAtEpochMillis")).longValue();
            Map<String, String> retentionSnapshot = (Map<String, String>) map.get("retentionPolicySnapshot");
            long durableSeq = ((Number) map.get("durableStateSequence")).longValue();
            int discoveredCount = ((Number) map.get("totalDiscoveredCount")).intValue();
            int executableCount = ((Number) map.get("totalExecutableCount")).intValue();
            long reclaimableBytes = ((Number) map.get("totalEstimatedReclaimableBytes")).longValue();
            String contentHash = (String) map.get("contentHash");

            List<Map<String, Object>> entriesRaw = (List<Map<String, Object>>) map.get("entries");
            List<PersistedCleanupPlanEntry> entries = new ArrayList<>();
            if (entriesRaw != null) {
                for (Map<String, Object> em : entriesRaw) {
                    Map<String, Object> fpRaw = (Map<String, Object>) em.get("fingerprint");
                    LifecycleResourceFingerprint fp = new LifecycleResourceFingerprint((String) fpRaw.get("normalizedIdentity"),
                            ((Number) fpRaw.get("durableStateVersion")).longValue(),
                            (String) fpRaw.get("gitHead"),
                            (String) fpRaw.get("gitCommonDir"),
                            (String) fpRaw.get("cleanStatusDigest"),
                            (String) fpRaw.get("metadataHash"));

                    entries.add(new PersistedCleanupPlanEntry(((Number) em.get("schemaVersion")).intValue(),
                            LifecycleResourceType.valueOf((String) em.get("resourceType")),
                            (String) em.get("resourceId"),
                            (String) em.get("resourcePath"),
                            CleanupClassification.valueOf((String) em.get("classification")),
                            (Boolean) em.get("eligible"),
                            (List<String>) em.get("reasons"),
                            ((Number) em.get("estimatedBytes")).longValue(),
                            (String) em.get("pathSafetyCode"),
                            fp,
                            (String) em.get("proposedOperation")));
                }
            }

            return new PersistedCleanupPlan(schemaVersion,
                    planId,
                    projectId,
                    controlRepo,
                    workspaceRoot,
                    createdAt,
                    retentionSnapshot,
                    durableSeq,
                    discoveredCount,
                    executableCount,
                    reclaimableBytes,
                    contentHash,
                    entries);
        } catch (Exception ex) {
            throw new IOException("Failed to parse cleanup plan JSON", ex);
        }
    }

    /**
     * Converts a runtime {@link CleanupPlan} into an immutable {@link PersistedCleanupPlan} and saves
     * it to disk.
     *
     * @param controlRoot control project root path
     * @param plan        runtime cleanup plan
     * @return saved persisted cleanup plan instance
     * @throws IOException if saving fails
     */
    public PersistedCleanupPlan createAndSave(Path controlRoot, CleanupPlan plan) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(plan, "plan");

        Path root = controlRoot.toAbsolutePath().normalize();
        Path workspaceRoot = ProjectAdministrativeLayout.workspaceRoot(root);
        String planId = "plan-" + UUID.randomUUID().toString().replace("-", "");

        List<PersistedCleanupPlanEntry> persistedEntries = new ArrayList<>();
        int executableCount = 0;
        for (CleanupPlanEntry entry : plan.entries()) {
            if (entry.eligible()) {
                executableCount++;
            }
            persistedEntries.add(new PersistedCleanupPlanEntry(1,
                    entry.resourceType(),
                    entry.resourceId(),
                    entry.resourcePath() != null ? entry.resourcePath().toString() : "",
                    entry.classification(),
                    entry.eligible(),
                    entry.reasons(),
                    entry.estimatedBytes(),
                    entry.pathSafetyCode(),
                    entry.fingerprint(),
                    entry.proposedAction()));
        }

        Map<String, String> retentionMap = new LinkedHashMap<>();
        retentionMap.put("workerRetentionHours", "24");
        retentionMap.put("validationRetentionHours", "24");
        retentionMap.put("integrationRetentionHours", "24");
        retentionMap.put("evidenceRetentionDays", "7");
        retentionMap.put("temporaryFileRetentionHours", "1");

        PersistedCleanupPlan unsignedPlan = new PersistedCleanupPlan(1,
                planId,
                plan.projectId(),
                root.toString(),
                workspaceRoot.toString(),
                plan.timestamp(),
                retentionMap,
                plan.timestamp(),
                plan.discoveredCount(),
                executableCount,
                plan.estimatedReclaimableBytes(),
                "UNSIGNED",
                persistedEntries);

        PersistedCleanupPlan finalPlan = unsignedPlan.withContentHash(AdministrativePlanFiles.contentHash(toSerializableMap(unsignedPlan)));
        AdministrativePlanFiles.write(ProjectAdministrativeLayout.cleanupPlans(root), planId, toSerializableMap(finalPlan));

        return finalPlan;
    }

    /**
     * Loads and verifies a persisted cleanup plan by plan ID.
     *
     * @param controlRoot control project root path
     * @param planId      opaque plan ID
     * @return loaded and verified persisted cleanup plan
     * @throws IOException if plan is missing, tampered, or invalid
     */
    public PersistedCleanupPlan load(Path controlRoot, String planId) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");

        Path root = controlRoot.toAbsolutePath().normalize();
        return AdministrativePlanFiles.load(root, ProjectAdministrativeLayout.cleanupPlans(root), "Cleanup", planId, CleanupPlanStore::fromSerializableMap, CleanupPlanStore::toSerializableMap);
    }

}
