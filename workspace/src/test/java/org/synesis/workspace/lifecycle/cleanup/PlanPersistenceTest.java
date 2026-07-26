package org.synesis.workspace.lifecycle.cleanup;

import org.synesis.workspace.infrastructure.process.ProcessEvidenceState;
import org.synesis.workspace.infrastructure.process.ProcessInspector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanPersistenceTest {

    @Test
    void persistsAndLoadsImmutablePlanOutsideControlCheckout(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        new ProjectApplicationService().init(controlRoot);

        CleanupPlan rawPlan = new CleanupPlan(
                "test-project",
                System.currentTimeMillis(),
                1,
                0,
                0,
                0,
                0,
                1,
                0,
                1024L,
                false,
                List.of(new CleanupPlanEntry(
                        LifecycleResourceType.TEMPORARY_FILE,
                        "temp-1",
                        controlRoot.resolve(".synesis/local/temp-1.tmp-1"),
                        CleanupClassification.CLEANUP_ELIGIBLE,
                        true,
                        List.of("temporary_file_expired"),
                        1024L,
                        "Expired",
                        List.of("temp"),
                        "NOT_APPLICABLE",
                        false,
                        "path_verified",
                        ProcessEvidenceState.NOT_OBSERVED,
                        new LifecycleResourceFingerprint("temp-1", 1000L, "NONE", "NONE", "", "hash1"),
                        "DELETE_TEMPORARY_FILE"
                ))
        );

        CleanupPlanStore store = new CleanupPlanStore();
        PersistedCleanupPlan saved = store.createAndSave(controlRoot, rawPlan);

        assertNotNull(saved.planId());
        assertTrue(saved.planId().startsWith("plan-"));
        assertNotNull(saved.contentHash());

        // Assert plan file exists outside control checkout
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path expectedFile = workspaceRoot.resolve("admin/cleanup-plans").resolve(saved.planId() + ".json");
        assertTrue(Files.exists(expectedFile));
        assertFalse(expectedFile.startsWith(controlRoot));

        // Load plan and verify integrity
        PersistedCleanupPlan loaded = store.load(controlRoot, saved.planId());
        assertEquals(saved.planId(), loaded.planId());
        assertEquals(saved.contentHash(), loaded.contentHash());
        assertEquals(1, loaded.entries().size());
    }

    @Test
    void rejectsTamperedCleanupPlan(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        new ProjectApplicationService().init(controlRoot);

        CleanupPlan rawPlan = new CleanupPlan(
                "test-project",
                System.currentTimeMillis(),
                1, 0, 0, 0, 0, 1, 0, 512L, false,
                List.of(new CleanupPlanEntry(
                        LifecycleResourceType.TEMPORARY_FILE, "t1", controlRoot.resolve("t1"),
                        CleanupClassification.CLEANUP_ELIGIBLE, true, List.of("expired"), 512L, "Expired",
                        List.of("t1"), "NOT_APPLICABLE", false, "path_verified", ProcessEvidenceState.NOT_OBSERVED,
                        new LifecycleResourceFingerprint("t1", 1000L, "NONE", "NONE", "", "h1"), "DELETE"
                ))
        );

        CleanupPlanStore store = new CleanupPlanStore();
        PersistedCleanupPlan saved = store.createAndSave(controlRoot, rawPlan);

        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path planFile = workspaceRoot.resolve("admin/cleanup-plans").resolve(saved.planId() + ".json");

        // Tamper with plan content without updating contentHash
        String content = Files.readString(planFile);
        String tampered = content.replace("totalDiscoveredCount\":1", "totalDiscoveredCount\":999");
        Files.writeString(planFile, tampered);

        // Load must fail integrity check
        assertThrows(Exception.class, () -> store.load(controlRoot, saved.planId()));
    }
}
