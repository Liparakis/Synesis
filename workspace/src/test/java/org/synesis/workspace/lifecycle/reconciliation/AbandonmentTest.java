package org.synesis.workspace.lifecycle.reconciliation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.project.ProjectApplicationService;
import org.synesis.workspace.infrastructure.process.ProcessEvidenceState;
import org.synesis.workspace.infrastructure.process.ProcessInspector;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseRecord;

import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseState;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbandonmentTest {

    private static void git(Path root, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git failed");
        }
    }

    @Test
    void abandonsDeadSessionBeyondGracePeriodAndPreservesWorktree(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        git(controlRoot, "init");
        git(controlRoot, "config", "user.name", "Test User");
        git(controlRoot, "config", "user.email", "test@example.com");
        Files.writeString(controlRoot.resolve("README.md"), "# Test Repo\n");
        git(controlRoot, "add", ".");
        git(controlRoot, "commit", "-m", "Initial commit");
        new ProjectApplicationService().init(controlRoot);

        Path worktreeDir = controlRoot.resolve(".synesis/local/worktrees/worker-dead");
        Files.createDirectories(worktreeDir);
        Files.writeString(worktreeDir.resolve("work.txt"), "uncommitted work");

        ProjectApplicationService projectService = new ProjectApplicationService();
        var location = projectService.locate(controlRoot);

        long start = System.currentTimeMillis() - 600000L; // 10 minutes ago
        SessionLeaseStore leaseStore = new SessionLeaseStore();
        SessionLeaseRecord record = new SessionLeaseRecord(
                1, location.projectId().toString(), "codex", "conn-dead", "worker-dead", "sess-dead",
                new org.synesis.workspace.lifecycle.lease.SessionProcessIdentity(99999L, "java", "cmd", start, "nonce"),
                "0.1.0-SNAPSHOT", start, start, SessionLeaseState.ACTIVE
        );
        leaseStore.save(controlRoot, record);

        ProcessInspector deadInspector = pid -> java.util.Optional.empty();
        SessionLeaseService leaseService = new SessionLeaseService(leaseStore, deadInspector);
        ReconciliationService service = new ReconciliationService(new ProjectApplicationService(), leaseService, leaseStore, new ReconciliationPlanStore());

        ReconciliationPlan plan = service.preparePlan(controlRoot);
        ReconciliationService.ReconciliationExecutionSummary summary = service.executePlan(controlRoot, plan.planId());

        assertEquals("SUCCESS", summary.resultStatus());
        assertTrue(summary.completedCount() > 0);

        // Verify worktree was PRESERVED and NOT deleted
        assertTrue(Files.exists(worktreeDir));
        assertTrue(Files.exists(worktreeDir.resolve("work.txt")));
    }
}
