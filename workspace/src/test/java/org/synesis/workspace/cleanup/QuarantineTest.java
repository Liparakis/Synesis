package org.synesis.workspace.cleanup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuarantineTest {

    @Test
    void quarantinesUnregisteredOrphanDirectoryAtomically(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        new ProjectApplicationService().init(controlRoot);

        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path orphanDir = workspaceRoot.resolve("worktrees/unlinked-1");
        Files.createDirectories(orphanDir);
        Files.writeString(orphanDir.resolve("leftover.txt"), "leftover");

        PersistedCleanupPlanEntry entry = new PersistedCleanupPlanEntry(
                1,
                LifecycleResourceType.UNLINKED_EXTERNAL_WORKSPACE,
                "unlinked-1",
                orphanDir.toString(),
                CleanupClassification.ORPHANED,
                true,
                List.of("durable_record_missing"),
                100L,
                "path_verified",
                new LifecycleResourceFingerprint("unlinked-1", 1000L, "NONE", "NONE", "", "h1"),
                "REQUIRES_DOCTOR_RECONCILIATION"
        );

        LifecycleQuarantineService quarantineService = new LifecycleQuarantineService();
        String qId = quarantineService.quarantineResource(controlRoot, entry);

        assertTrue(qId.startsWith("quarantine-"));
        assertFalse(Files.exists(orphanDir));

        Path qTarget = workspaceRoot.resolve("admin/quarantine").resolve(qId);
        assertTrue(Files.exists(qTarget));
        assertTrue(Files.exists(qTarget.resolve("unlinked-1/leftover.txt")));
        assertTrue(Files.exists(qTarget.resolve("quarantine-manifest.json")));
    }
}
