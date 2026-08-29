package org.synesis.workspace.lifecycle.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.infrastructure.process.ProcessInspector;

class CleanupEligibilityServiceTest {

    @Test
    void classifiesFinalizedCleanWorktreeAfterRetentionAsCleanupEligible(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot.resolve(".synesis"));
        Files.writeString(controlRoot.resolve(".synesis/project.json"), "{\"projectId\":\"test-project\"}");

        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path wtPath = workspaceRoot.resolve("worktrees/session-old");
        Files.createDirectories(wtPath);

        Instant now = Instant.parse("2026-07-25T12:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneId.of("UTC"));
        RetentionPolicy policy = new RetentionPolicy(
                fixedClock,
                Duration.ofHours(24),
                Duration.ofHours(24),
                Duration.ofHours(24),
                Duration.ofDays(7),
                Duration.ofHours(1),
                3,
                2L * 1024 * 1024 * 1024
        );

        long lastMod = now.minus(Duration.ofHours(48))
                .toEpochMilli();

        LifecycleInventoryService.DiscoveredResource resource = new LifecycleInventoryService.DiscoveredResource(
                LifecycleResourceType.WORKER_WORKTREE,
                "worktree-session-old",
                wtPath,
                List.of("session-old"),
                1024L,
                "main",
                lastMod,
                null
        );

        CleanupEligibilityService service = new CleanupEligibilityService(new LifecyclePathVerifier(),
                policy,
                ProcessInspector.system());

        CleanupPlanEntry entry = service.evaluateResource(controlRoot, resource);

        assertEquals(CleanupClassification.CLEANUP_ELIGIBLE, entry.classification());
        assertTrue(entry.eligible());
        assertTrue(entry.reasons()
                .contains(CleanupReason.FINALIZED_AND_CLEAN.code()));
    }

    @Test
    void classifiesRecentWorkerWorktreeAsDiagnosticRetained(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot.resolve(".synesis"));
        Files.writeString(controlRoot.resolve(".synesis/project.json"), "{\"projectId\":\"test-project\"}");

        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path wtPath = workspaceRoot.resolve("worktrees/session-recent");
        Files.createDirectories(wtPath);

        Instant now = Instant.parse("2026-07-25T12:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneId.of("UTC"));
        RetentionPolicy policy = new RetentionPolicy(
                fixedClock,
                Duration.ofHours(24),
                Duration.ofHours(24),
                Duration.ofHours(24),
                Duration.ofDays(7),
                Duration.ofHours(1),
                3,
                2L * 1024 * 1024 * 1024
        );

        long lastMod = now.minus(Duration.ofHours(2))
                .toEpochMilli();

        LifecycleInventoryService.DiscoveredResource resource = new LifecycleInventoryService.DiscoveredResource(
                LifecycleResourceType.WORKER_WORKTREE,
                "worktree-session-recent",
                wtPath,
                List.of("session-recent"),
                1024L,
                "main",
                lastMod,
                null
        );

        CleanupEligibilityService service = new CleanupEligibilityService(new LifecyclePathVerifier(),
                policy,
                ProcessInspector.system());

        CleanupPlanEntry entry = service.evaluateResource(controlRoot, resource);

        assertEquals(CleanupClassification.DIAGNOSTIC_RETAINED, entry.classification());
        assertFalse(entry.eligible());
        assertTrue(entry.reasons()
                .contains(CleanupReason.RETENTION_WINDOW_ACTIVE.code()));
    }

    @Test
    void classifiesUnlinkedWorkspaceAsOrphaned(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot.resolve(".synesis"));
        Files.writeString(controlRoot.resolve(".synesis/project.json"), "{\"projectId\":\"test-project\"}");

        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path unlinkedPath = workspaceRoot.resolve("worktrees/unlinked-999");
        Files.createDirectories(unlinkedPath);

        LifecycleInventoryService.DiscoveredResource resource = new LifecycleInventoryService.DiscoveredResource(
                LifecycleResourceType.UNLINKED_EXTERNAL_WORKSPACE,
                "unlinked-999",
                unlinkedPath,
                List.of("unlinked-999"),
                2048L,
                null,
                System.currentTimeMillis(),
                null
        );

        CleanupEligibilityService service = new CleanupEligibilityService(new LifecyclePathVerifier(),
                new RetentionPolicy(),
                ProcessInspector.system());

        CleanupPlanEntry entry = service.evaluateResource(controlRoot, resource);

        assertEquals(CleanupClassification.ORPHANED, entry.classification());
        assertFalse(entry.eligible());
        assertTrue(entry.reasons()
                .contains(CleanupReason.DURABLE_RECORD_MISSING.code()));
    }

    @Test
    void classifiesSnapshotsAsProtectedAndNotDeletable(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot.resolve(".synesis"));
        Files.writeString(controlRoot.resolve(".synesis/project.json"), "{\"projectId\":\"test-project\"}");

        Path snapPath = controlRoot.resolve(".synesis/local/snapshots/task-123.json");
        Files.createDirectories(snapPath.getParent());
        Files.writeString(snapPath, "{}");

        LifecycleInventoryService.DiscoveredResource resource = new LifecycleInventoryService.DiscoveredResource(
                LifecycleResourceType.TASK_SNAPSHOT,
                "task-123.json",
                snapPath,
                List.of("task-123.json"),
                512L,
                null,
                System.currentTimeMillis(),
                null
        );

        CleanupEligibilityService service = new CleanupEligibilityService(new LifecyclePathVerifier(),
                new RetentionPolicy(),
                ProcessInspector.system());

        CleanupPlanEntry entry = service.evaluateResource(controlRoot, resource);

        assertEquals(CleanupClassification.PROTECTED, entry.classification());
        assertFalse(entry.eligible());
        assertTrue(entry.reasons()
                .contains(CleanupReason.SNAPSHOT_CLEANUP_NOT_SUPPORTED.code()));
    }
}
