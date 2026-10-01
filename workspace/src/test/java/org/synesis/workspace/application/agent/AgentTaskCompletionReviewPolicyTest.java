package org.synesis.workspace.application.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/** Verifies review requirement comes from structured group policy rather than prose. */
final class AgentTaskCompletionReviewPolicyTest {

  @Test
  void mandatoryIndependentGroupRequiresReviewBeforeAnyRequestOrGrant(@TempDir Path temp)
      throws Exception {
    UUID projectId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    NodeIdentity identity = NodeIdentity.generate();
    PredictionEventStore store = new PredictionEventStore(temp, projectId);
    new WorkGroupService(store, identity).create(new WorkGroup(groupId, projectId,
        "Build a board", "CRUD, persistence, tests", 1, WorkGroup.Status.ACTIVE,
        ReviewPolicy.mandatoryIndependent()));
    WorkIntent producer = intent(projectId, groupId, "Build a board",
        "CRUD, persistence, tests");
    new WorkIntentService(new PredictionEventStore(temp, projectId), identity).announce(producer);

    assertTrue(AgentTaskCompletionService.reviewRequired(
        new PredictionEventStore(temp, projectId), producer, producer.participant()));
  }

  @Test
  void mandatoryIndependentGroupDoesNotRequireReviewOfReviewerReport(@TempDir Path temp)
      throws Exception {
    UUID projectId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    NodeIdentity identity = NodeIdentity.generate();
    PredictionEventStore store = new PredictionEventStore(temp, projectId);
    new WorkGroupService(store, identity).create(new WorkGroup(groupId, projectId,
        "Build a board", "CRUD, persistence, tests", 1, WorkGroup.Status.ACTIVE,
        ReviewPolicy.mandatoryIndependent()));
    UUID intentId = UUID.randomUUID();
    WorkIntent reviewer = new WorkIntent(intentId, projectId, "agt-policy-reviewer", "codex",
        UUID.randomUUID(), "Review the implementation", "Publish the review report", "base",
        List.of(ResourceSelector.pathExact("review-report.md")), 1, groupId,
        WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED,
        WorkIntent.Role.REVIEWER, List.of(ResourceSelector.pathSubtree("src")));

    assertFalse(AgentTaskCompletionService.reviewRequired(
        new PredictionEventStore(temp, projectId), reviewer, reviewer.participant()));
  }

  @Test
  void acceptanceTextCannotTurnReviewOn(@TempDir Path temp) throws Exception {
    UUID projectId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    NodeIdentity identity = NodeIdentity.generate();
    PredictionEventStore store = new PredictionEventStore(temp, projectId);
    new WorkGroupService(store, identity).create(new WorkGroup(groupId, projectId,
        "Build a board", "Independent review is mandatory", 1, WorkGroup.Status.ACTIVE));
    WorkIntent producer = intent(projectId, groupId, "Build a board",
        "Independent review is mandatory");
    new WorkIntentService(new PredictionEventStore(temp, projectId), identity).announce(producer);

    assertFalse(AgentTaskCompletionService.reviewRequired(
        new PredictionEventStore(temp, projectId), producer, producer.participant()));
  }

  private static WorkIntent intent(UUID projectId, UUID groupId, String goal, String acceptance) {
    UUID intentId = UUID.randomUUID();
    return new WorkIntent(intentId, projectId, "agt-policy-producer", "codex",
        UUID.randomUUID(), goal, acceptance, "base", List.of(ResourceSelector.pathExact("src/app.py")),
        1, groupId, WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED);
  }
}
