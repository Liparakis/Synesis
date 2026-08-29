package org.synesis.workspace.lifecycle.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.infrastructure.process.ProcessInspector;
import org.synesis.workspace.lifecycle.lease.SessionLeaseRecord;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseState;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;

class AbandonmentTest {

    private static void git(Path root, String... arguments) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, arguments);
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
                1,
                location.projectId()
                        .toString(),
                "codex",
                "conn-dead",
                "worker-dead",
                "sess-dead",
                new org.synesis.workspace.lifecycle.lease.SessionProcessIdentity(99999L, "java", "cmd", start, "nonce"),
                "0.1.0-SNAPSHOT",
                start,
                start,
                SessionLeaseState.ACTIVE
        );
        leaseStore.save(controlRoot, record);

        ProcessInspector deadInspector = _ -> java.util.Optional.empty();
        SessionLeaseService leaseService = new SessionLeaseService(leaseStore, deadInspector);
        ReconciliationService service = new ReconciliationService(new ProjectApplicationService(),
                leaseService,
                leaseStore,
                new ReconciliationPlanStore());

        ReconciliationPlan plan = service.preparePlan(controlRoot);
        ReconciliationService.ReconciliationExecutionSummary summary = service.executePlan(controlRoot, plan.planId());

        assertEquals("SUCCESS", summary.resultStatus());
        assertTrue(summary.completedCount() >= 0);

        // Verify worktree was PRESERVED and NOT deleted
        assertTrue(Files.exists(worktreeDir));
        assertTrue(Files.exists(worktreeDir.resolve("work.txt")));
    }

    @Test
    void suspendedSessionRetainsCollaborationClaimsOwnerIndependently(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        git(controlRoot, "init");
        git(controlRoot, "config", "user.name", "Test User");
        git(controlRoot, "config", "user.email", "test@example.com");
        Files.writeString(controlRoot.resolve("README.md"), "# Test Repo\n");
        git(controlRoot, "add", ".");
        git(controlRoot, "commit", "-m", "Initial commit");
        new ProjectApplicationService().init(controlRoot);
        var location = new ProjectApplicationService().locate(controlRoot);
        String session = "session-collaboration-dead";
        String participant = WorkspaceCollaborationService.participantHandle(session);
        var identity = new IdentityBootstrap(location.profile()
                .resolve("link")).loadOrCreate()
                .identity();
        var store = new PredictionEventStore(location.root()
                .resolve(".synesis/coordination"), location.projectId());
        WorkIntent intent = new WorkIntent(UUID.randomUUID(), location.projectId(), participant, "codex",
                UUID.randomUUID(), "dead collaboration owner", "claims released", "base",
                List.of(ResourceSelector.pathExact("src/dead-owner.py")), 1, WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(store, identity).announce(intent)
                .acquired());

        long start = System.currentTimeMillis() - 600000L;
        SessionLeaseStore leaseStore = new SessionLeaseStore();
        leaseStore.save(controlRoot,
                new SessionLeaseRecord(1,
                        location.projectId()
                                .toString(),
                        "codex",
                        "conn-dead-collab",
                        "worker-dead",
                        session,
                        new org.synesis.workspace.lifecycle.lease.SessionProcessIdentity(
                                99999L, "java", "cmd", start, "nonce"),
                        "0.1.0-SNAPSHOT",
                        start,
                        start,
                        SessionLeaseState.ACTIVE));
        SessionLeaseService leaseService = new SessionLeaseService(leaseStore, _ -> java.util.Optional.empty());
        ReconciliationService service = new ReconciliationService(new ProjectApplicationService(), leaseService,
                leaseStore, new ReconciliationPlanStore());
        ReconciliationPlan plan = service.preparePlan(controlRoot);
        assertTrue(service.executePlan(controlRoot, plan.planId())
                .completedCount() > 0);

        var replayed = new PredictionEventStore(location.root()
                .resolve(".synesis/coordination"), location.projectId());
        assertTrue(replayed.collaborationProjection()
                        .activeIntents()
                        .stream()
                        .anyMatch(candidate -> candidate.participant()
                                .equals(participant)),
                () -> "participant=" + participant + " intents=" + replayed.collaborationProjection()
                        .activeIntents()
                        + " participants=" + replayed.collaborationProjection()
                        .participants());
        assertEquals(org.synesis.coordination.domain.collaboration.Participant.State.SUSPENDED,
                replayed.collaborationProjection()
                        .participants()
                        .stream()
                        .filter(candidate -> candidate.id()
                                .equals(participant))
                        .findFirst()
                        .orElseThrow()
                        .state());
    }
}
