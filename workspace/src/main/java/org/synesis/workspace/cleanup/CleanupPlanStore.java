package org.synesis.workspace.cleanup;

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
import org.synesis.workspace.provider.ProviderJson;

/**
 * Storage service for persisting and loading immutable cleanup plans outside the control checkout.
 *
 * <p>Plans are stored under {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\cleanup-plans\<plan-id>.json}.
 *
 * @since 1.0
 */
public final class CleanupPlanStore {

    /**
     * Creates a cleanup plan store.
     */
    public CleanupPlanStore() {
    }

    /**
     * Resolves the directory path for storing administrative cleanup plans.
     *
     * @param controlRoot control project root path
     * @return normalized administrative cleanup plans directory path
     */
    public static Path resolvePlansDirectory(Path controlRoot) {
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        return workspaceRoot.resolve("admin").resolve("cleanup-plans");
    }

    /**
     * Converts a runtime {@link CleanupPlan} into an immutable {@link PersistedCleanupPlan} and saves it to disk.
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
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);
        String planId = "plan-" + UUID.randomUUID().toString().replace("-", "");

        List<PersistedCleanupPlanEntry> persistedEntries = new ArrayList<>();
        int executableCount = 0;
        for (CleanupPlanEntry entry : plan.entries()) {
            if (entry.eligible()) {
                executableCount++;
            }
            persistedEntries.add(new PersistedCleanupPlanEntry(
                    1,
                    entry.resourceType(),
                    entry.resourceId(),
                    entry.resourcePath() != null ? entry.resourcePath().toString() : "",
                    entry.classification(),
                    entry.eligible(),
                    entry.reasons(),
                    entry.estimatedBytes(),
                    entry.pathSafetyCode(),
                    entry.fingerprint(),
                    entry.proposedAction()
            ));
        }

        Map<String, String> retentionMap = new LinkedHashMap<>();
        retentionMap.put("workerRetentionHours", "24");
        retentionMap.put("validationRetentionHours", "24");
        retentionMap.put("integrationRetentionHours", "24");
        retentionMap.put("evidenceRetentionDays", "7");
        retentionMap.put("temporaryFileRetentionHours", "1");

        PersistedCleanupPlan unsignedPlan = new PersistedCleanupPlan(
                1,
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
                persistedEntries
        );

        String canonicalContent = serializeCanonical(unsignedPlan);
        String contentHash = computeSha256(canonicalContent);

        PersistedCleanupPlan finalPlan = new PersistedCleanupPlan(
                unsignedPlan.schemaVersion(),
                unsignedPlan.planId(),
                unsignedPlan.projectId(),
                unsignedPlan.controlRepositoryPath(),
                unsignedPlan.externalWorkspaceRoot(),
                unsignedPlan.createdAtEpochMillis(),
                unsignedPlan.retentionPolicySnapshot(),
                unsignedPlan.durableStateSequence(),
                unsignedPlan.totalDiscoveredCount(),
                unsignedPlan.totalExecutableCount(),
                unsignedPlan.totalEstimatedReclaimableBytes(),
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
        Path plansDir = resolvePlansDirectory(root);
        Path planFile = plansDir.resolve(planId + ".json");

        if (!Files.exists(planFile)) {
            throw new IOException("Cleanup plan not found: " + planId);
        }

        String rawJson = Files.readString(planFile, StandardCharsets.UTF_8);
        PersistedCleanupPlan plan = fromSerializableMap(rawJson);

        // Verification checks
        if (!planId.equals(plan.planId())) {
            throw new IOException("Plan ID mismatch in store: expected " + planId + " but found " + plan.planId());
        }

        Path expectedWorkspace = LifecyclePathVerifier.resolveWorkspaceRoot(root);
        if (!expectedWorkspace.toString().equals(plan.externalWorkspaceRoot())) {
            throw new IOException("External workspace root mismatch in plan: " + plan.externalWorkspaceRoot());
        }

        // Verify content hash integrity
        PersistedCleanupPlan unsigned = new PersistedCleanupPlan(
                plan.schemaVersion(), plan.planId(), plan.projectId(), plan.controlRepositoryPath(),
                plan.externalWorkspaceRoot(), plan.createdAtEpochMillis(), plan.retentionPolicySnapshot(),
                plan.durableStateSequence(), plan.totalDiscoveredCount(), plan.totalExecutableCount(),
                plan.totalEstimatedReclaimableBytes(), "UNSIGNED", plan.entries()
        );
        String expectedHash = computeSha256(serializeCanonical(unsigned));
        if (!expectedHash.equals(plan.contentHash())) {
            throw new IOException("Cleanup plan content hash integrity verification failed for " + planId);
        }

        return plan;
    }

    private static String serializeCanonical(PersistedCleanupPlan plan) {
        return ProviderJson.write(toSerializableMap(plan));
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
                    LifecycleResourceFingerprint fp = new LifecycleResourceFingerprint(
                            (String) fpRaw.get("normalizedIdentity"),
                            ((Number) fpRaw.get("durableStateVersion")).longValue(),
                            (String) fpRaw.get("gitHead"),
                            (String) fpRaw.get("gitCommonDir"),
                            (String) fpRaw.get("cleanStatusDigest"),
                            (String) fpRaw.get("metadataHash")
                    );

                    entries.add(new PersistedCleanupPlanEntry(
                            ((Number) em.get("schemaVersion")).intValue(),
                            LifecycleResourceType.valueOf((String) em.get("resourceType")),
                            (String) em.get("resourceId"),
                            (String) em.get("resourcePath"),
                            CleanupClassification.valueOf((String) em.get("classification")),
                            (Boolean) em.get("eligible"),
                            (List<String>) em.get("reasons"),
                            ((Number) em.get("estimatedBytes")).longValue(),
                            (String) em.get("pathSafetyCode"),
                            fp,
                            (String) em.get("proposedOperation")
                    ));
                }
            }

            return new PersistedCleanupPlan(
                    schemaVersion, planId, projectId, controlRepo, workspaceRoot,
                    createdAt, retentionSnapshot, durableSeq, discoveredCount,
                    executableCount, reclaimableBytes, contentHash, entries
            );
        } catch (Exception ex) {
            throw new IOException("Failed to parse cleanup plan JSON", ex);
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
