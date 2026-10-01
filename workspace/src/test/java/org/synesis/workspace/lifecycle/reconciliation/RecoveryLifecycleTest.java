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
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.infrastructure.process.ProcessInspector;
import org.synesis.workspace.lifecycle.lease.SessionLeaseRecord;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseState;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;

/**
 * Verifies crash recovery admission and terminal-participant fencing.
 */
class RecoveryLifecycleTest {

  private static void git(Path root, String... arguments) throws Exception {
    org.synesis.workspace.test.TestGit.run(root, arguments);
  }

  private static Path initializedProject(Path root) throws Exception {
    Files.createDirectories(root);
    git(root, "init");
    git(root, "config", "user.name", "Test User");
    git(root, "config", "user.email", "test@example.com");
    Files.writeString(root.resolve("README.md"), "# Test Repo\n");
    git(root, "add", ".");
    git(root, "commit", "-m", "Initial commit");
    new ProjectApplicationService().init(root);
    return root;
  }

  private static Lane deadLane(Path controlRoot, String connection) throws Exception {
    ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(
        controlRoot);
    ProviderSessionBindingService.Binding binding = new ProviderSessionBindingService()
        .ensure(location, "codex", connection)
        .binding();
    Path worktree = Path.of(binding.worktreePath());
    Files.writeString(worktree.resolve("interrupted.txt"), "uncommitted recovery evidence\n");

    String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
    var identity = new IdentityBootstrap(location.profile()
        .resolve("link")).loadOrCreate()
        .identity();
    PredictionEventStore store = new PredictionEventStore(location.root()
        .resolve(".synesis/coordination"), location.projectId());
    WorkIntent intent = new WorkIntent(UUID.randomUUID(), location.projectId(), participant,
        "codex", UUID.randomUUID(), "interrupted work", "recover the lane", binding.baseCommit(),
        List.of(ResourceSelector.pathExact("interrupted.txt")), 1, WorkIntent.Status.ANNOUNCED);
    assertTrue(new WorkIntentService(store, identity).announce(intent).acquired());

    long staleSince = System.currentTimeMillis() - 600_000L;
    new SessionLeaseStore().save(controlRoot,
        new SessionLeaseRecord(1, location.projectId().toString(), "codex", connection,
            "worker-dead", binding.sessionId(),
            new SessionProcessIdentity(99_999L, "java", "SynesisMcpServer", staleSince,
                "dead-connection"),
            "0.1.0-SNAPSHOT", staleSince, staleSince, SessionLeaseState.ACTIVE));
    return new Lane(location, binding, participant, intent, identity);
  }

  @Test
  void admissionReconciliationFencesDeadLaneAndHoldsSnapshot(@TempDir Path tempDir)
      throws Exception {
    Path controlRoot = initializedProject(tempDir.resolve("control-repo"));
    Lane lane = deadLane(controlRoot, "dead-admission-connection");
    SessionLeaseStore leaseStore = new SessionLeaseStore();
    ReconciliationService service = new ReconciliationService(new ProjectApplicationService(),
        new SessionLeaseService(leaseStore, _ -> java.util.Optional.empty()), leaseStore,
        new ReconciliationPlanStore());

    var result = service.reconcileOnAdmission(controlRoot);

    assertTrue(result.isPresent());
    PredictionEventStore replayed = new PredictionEventStore(lane.location.root()
        .resolve(".synesis/coordination"), lane.location.projectId());
    assertEquals(Participant.State.RECOVERY_HELD,
        replayed.collaborationProjection().participantState(lane.participant).orElseThrow());
    assertEquals(1, replayed.events().stream()
        .filter(event -> event.type() == org.synesis.coordination.domain.prediction.PredictionEventType.PARTICIPANT_SUSPENDED)
        .count());
    assertEquals(1, replayed.events().stream()
        .filter(event -> event.type() == org.synesis.coordination.domain.prediction.PredictionEventType.RECOVERY_SNAPSHOT_HELD)
        .count());

    assertTrue(service.reconcileOnAdmission(controlRoot).isEmpty());
    PredictionEventStore afterReplay = new PredictionEventStore(lane.location.root()
        .resolve(".synesis/coordination"), lane.location.projectId());
    assertEquals(1, afterReplay.events().stream()
        .filter(event -> event.type() == org.synesis.coordination.domain.prediction.PredictionEventType.PARTICIPANT_SUSPENDED)
        .count());
    assertEquals(1, afterReplay.events().stream()
        .filter(event -> event.type() == org.synesis.coordination.domain.prediction.PredictionEventType.RECOVERY_SNAPSHOT_HELD)
        .count());
  }

  @Test
  void stalePlanCannotSuspendParticipantThatCompletedBeforeExecution(@TempDir Path tempDir)
      throws Exception {
    Path controlRoot = initializedProject(tempDir.resolve("control-repo"));
    Lane lane = deadLane(controlRoot, "dead-stale-plan-connection");
    SessionLeaseStore leaseStore = new SessionLeaseStore();
    SessionLeaseService leaseService = new SessionLeaseService(leaseStore,
        _ -> java.util.Optional.empty());
    ReconciliationService service = new ReconciliationService(new ProjectApplicationService(),
        leaseService, leaseStore, new ReconciliationPlanStore());

    ReconciliationPlan plan = service.preparePlan(controlRoot);
    new WorkIntentService(new PredictionEventStore(lane.location.root()
        .resolve(".synesis/coordination"), lane.location.projectId()), lane.identity)
        .release(lane.intent.intentId(), lane.participant);

    ReconciliationService.ReconciliationExecutionSummary result = service.executePlan(controlRoot,
        plan.planId());

    assertEquals("SUCCESS", result.resultStatus());
    assertEquals(2, result.skippedStaleCount());
    PredictionEventStore replayed = new PredictionEventStore(lane.location.root()
        .resolve(".synesis/coordination"), lane.location.projectId());
    assertEquals(Participant.State.COMPLETED,
        replayed.collaborationProjection().participantState(lane.participant).orElseThrow());
    assertEquals(0, replayed.events().stream()
        .filter(event -> event.type() == org.synesis.coordination.domain.prediction.PredictionEventType.PARTICIPANT_SUSPENDED)
        .count());
  }

  private record Lane(ProjectApplicationService.ProjectLocation location,
                      ProviderSessionBindingService.Binding binding,
                      String participant,
                      WorkIntent intent,
                      org.synesis.link.identity.NodeIdentity identity) {
  }
}
