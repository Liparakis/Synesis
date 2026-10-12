package org.synesis.workspace.application.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
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

    assertTrue(AgentReviewActionProjection.reviewRequired(
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

    assertFalse(AgentReviewActionProjection.reviewRequired(
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

    assertFalse(AgentReviewActionProjection.reviewRequired(
        new PredictionEventStore(temp, projectId), producer, producer.participant()));
  }

  @Test
  void reviewerThatDecidedOwesNothingOnceTheContinuedProducerLaneIsGone(@TempDir Path temp)
      throws Exception {
    WorkIntent reviewer = reviewerOfContinuedAndReleasedProducer(temp, true);

    assertFalse(AgentReviewActionProjection.hasUnresolvedReviewObligation(
        new PredictionEventStore(temp, reviewer.projectId()), reviewer));
  }

  @Test
  void reviewerWithoutGrantOrDecisionStillWaitsWhenNoProducerIsLeft(@TempDir Path temp)
      throws Exception {
    WorkIntent reviewer = reviewerOfContinuedAndReleasedProducer(temp, false);

    assertTrue(AgentReviewActionProjection.hasUnresolvedReviewObligation(
        new PredictionEventStore(temp, reviewer.projectId()), reviewer));
  }

  /**
   * Builds a group whose producer lane was continued by a successor and then released, leaving no
   * active producer; when {@code decided}, the reviewer first accepted the original producer's
   * snapshot through a consumed grant that the continuation then left pointing at a superseded lane.
   */
  private static WorkIntent reviewerOfContinuedAndReleasedProducer(Path temp, boolean decided)
      throws Exception {
    UUID projectId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    NodeIdentity identity = NodeIdentity.generate();
    PredictionEventStore store = new PredictionEventStore(temp, projectId);
    WorkGroupService groups = new WorkGroupService(store, identity);
    groups.create(new WorkGroup(groupId, projectId, "Build a board", "CRUD, persistence, tests", 1,
        WorkGroup.Status.ACTIVE, ReviewPolicy.mandatoryIndependent()));
    WorkIntentService intents = new WorkIntentService(store, identity);
    WorkIntent producer = intent(projectId, groupId, "Build a board", "CRUD, persistence, tests");
    intents.announce(producer);
    UUID reviewerId = UUID.randomUUID();
    WorkIntent reviewer = new WorkIntent(reviewerId, projectId, "agt-policy-reviewer", "codex",
        UUID.randomUUID(), "Review the implementation", "Publish the review report", "base",
        List.of(ResourceSelector.pathExact("review-report.md")), 1, groupId,
        WorkIntent.defaultAuthorityLineage(reviewerId), WorkIntent.Status.ANNOUNCED,
        WorkIntent.Role.REVIEWER, List.of(ResourceSelector.pathExact("src/app.py")));
    intents.announce(reviewer);

    if (decided) {
      UUID grantId = UUID.randomUUID();
      SnapshotProvenance provenance = new SnapshotProvenance(groupId, producer.intentId(),
          producer.authorityLineageId(), producer.participant(), "producer-session", 1, List.of(),
          List.of(), List.of("PATH_EXACT:src/app.py"), "refs/synesis/snapshots/snap-1",
          "integrity-snap-1", "manifest-snap-1");
      store.append(producer.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(),
          new TaskSnapshotPayload(producer.taskId(), "snap-1", "node", "supervisor", "worker",
              "producer-session", "base", "commit", List.of("src/app.py"), List.of(),
              "Published reviewed candidate", provenance, true).encode(), identity);
      groups.issue(new LaneGrant(grantId, groupId, producer.intentId(), reviewer.participant(),
          producer.version(), true, "snap-1"));
      groups.consume(grantId, reviewer.participant(), producer.intentId(), producer.version());
      new PredictionEventStore(temp, projectId).append(grantId,
          PredictionEventType.REVIEW_VALIDATION_RECORDED, identity.nodeId(),
          new ReviewValidationPayload(grantId, groupId, producer.intentId(),
              reviewer.participant(), producer.version(), producer.taskId(), "snap-1", "ACCEPTED",
              "", producer.participant()).encode(), identity);
    }

    intents.suspend(producer.participant());
    String reference = temp.resolve("recovery").toAbsolutePath() + "#hash";
    intents.holdRecovery(producer.participant(), reference);
    UUID successorId = UUID.nameUUIDFromBytes(("successor:" + producer.intentId())
        .getBytes(StandardCharsets.UTF_8));
    WorkIntent successor = new WorkIntent(successorId, projectId, "agt-policy-successor", "codex",
        producer.taskId(), producer.goal(), producer.acceptance(), producer.baseCommit(),
        producer.selectors(), producer.version() + 1, groupId, producer.authorityLineageId(),
        WorkIntent.Status.ANNOUNCED);
    intents.continueEligibleFromRecovery(new LaneContinuation(
        UUID.nameUUIDFromBytes(("continuation:" + producer.intentId())
            .getBytes(StandardCharsets.UTF_8)),
        producer.intentId(), successor, producer.participant(), successor.participant(),
        producer.version(), reference));
    intents.release(successorId, successor.participant());
    return reviewer;
  }

  private static WorkIntent intent(UUID projectId, UUID groupId, String goal, String acceptance) {
    UUID intentId = UUID.randomUUID();
    return new WorkIntent(intentId, projectId, "agt-policy-producer", "codex",
        UUID.randomUUID(), goal, acceptance, "base", List.of(ResourceSelector.pathExact("src/app.py")),
        1, groupId, WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED);
  }
}
