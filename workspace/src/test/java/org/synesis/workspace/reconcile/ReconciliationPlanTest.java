package org.synesis.workspace.reconcile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.cleanup.LifecyclePathVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationPlanTest {

    @Test
    void persistsAndLoadsImmutableReconciliationPlanOutsideControlCheckout(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        new ProjectApplicationService().init(controlRoot);

        ReconciliationPlanStore store = new ReconciliationPlanStore();
        ReconciliationPlan plan = store.createAndSave(
                controlRoot, "proj-rec", 1,
                List.of(new ReconciliationPlanEntry(
                        1, "rec-1", ReconciliationAction.MARK_SESSION_ABANDONED, "sess-1",
                        true, List.of("session_abandonment_eligible"), "Process death verified"
                ))
        );

        assertNotNull(plan.planId());
        assertTrue(plan.planId().startsWith("recplan-"));

        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path planFile = workspaceRoot.resolve("admin/reconciliation-plans").resolve(plan.planId() + ".json");
        assertTrue(Files.exists(planFile));
        assertFalse(planFile.startsWith(controlRoot));

        ReconciliationPlan loaded = store.load(controlRoot, plan.planId());
        assertEquals(plan.planId(), loaded.planId());
        assertEquals(plan.contentHash(), loaded.contentHash());
    }

    @Test
    void rejectsTamperedReconciliationPlan(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        new ProjectApplicationService().init(controlRoot);

        ReconciliationPlanStore store = new ReconciliationPlanStore();
        ReconciliationPlan plan = store.createAndSave(
                controlRoot, "proj-rec", 1,
                List.of(new ReconciliationPlanEntry(
                        1, "rec-1", ReconciliationAction.MARK_SESSION_ABANDONED, "sess-1",
                        true, List.of("session_abandonment_eligible"), "Process death verified"
                ))
        );

        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path planFile = workspaceRoot.resolve("admin/reconciliation-plans").resolve(plan.planId() + ".json");

        String content = Files.readString(planFile);
        String tampered = content.replace("totalInspectedCount\":1", "totalInspectedCount\":999");
        Files.writeString(planFile, tampered);

        assertThrows(Exception.class, () -> store.load(controlRoot, plan.planId()));
    }
}
