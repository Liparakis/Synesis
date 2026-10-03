package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies a review candidate stays reserved across the accepted-response/grant crash window.
 */
final class ReviewAdmissionCrashSafetyTest {

    private static Scenario prepare(Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(root, projectId);
        new WorkGroupService(store, identity).create(new WorkGroup(groupId,
                projectId,
                "Build a board",
                "A published candidate receives independent review",
                1,
                WorkGroup.Status.ACTIVE,
                ReviewPolicy.mandatoryIndependent()));
        UUID producerId = UUID.randomUUID();
        WorkIntent producer = new WorkIntent(producerId,
                projectId,
                "producer",
                "codex",
                UUID.randomUUID(),
                "Build a board",
                "Publish the board",
                "base-commit",
                List.of(ResourceSelector.pathExact("src/board.py")),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(producerId),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                List.of());
        WorkIntent reviewer = reviewer(projectId, groupId, "reviewer");
        WorkIntent secondReviewer = reviewer(projectId, groupId, "second-reviewer");
        WorkIntentService service = new WorkIntentService(store, identity);
        assertTrue(service.announce(producer).acquired());
        assertTrue(service.announce(reviewer).acquired());
        assertTrue(service.announce(secondReviewer).acquired());

        String snapshotId = "snap_review_admission_crash";
        String sessionId = "producer-session";
        SnapshotProvenance provenance = new SnapshotProvenance(groupId,
                producer.intentId(),
                producer.authorityLineageId(),
                producer.participant(),
                sessionId,
                producer.version(),
                List.of(),
                List.of(),
                List.of("PATH_EXACT:src/board.py"),
                "refs/synesis/snapshots/" + snapshotId,
                "integrity-" + snapshotId,
                "manifest-" + snapshotId);
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(producer.taskId(),
                snapshotId,
                identity.nodeId(),
                "supervisor",
                "worker",
                sessionId,
                "base-commit",
                "commit-" + snapshotId,
                List.of("src/board.py"),
                List.of(),
                "Board candidate",
                provenance,
                true);
        store.append(producer.taskId(),
                PredictionEventType.TASK_SNAPSHOT_CREATED,
                identity.nodeId(),
                snapshot.encode(),
                identity);
        CoordinationRequest request = service.request(reviewer.participant(),
                producer.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the immutable board candidate",
                snapshotId);
        return new Scenario(root, projectId, identity, service, producer, secondReviewer, request, snapshotId);
    }

    private static WorkIntent reviewer(UUID projectId, UUID groupId, String participant) {
        UUID intentId = UUID.randomUUID();
        return new WorkIntent(intentId,
                projectId,
                participant,
                "codex",
                UUID.randomUUID(),
                "Review a board",
                "Review the published board candidate",
                "base-commit",
                List.of(ResourceSelector.pathExact("review/" + participant + ".md")),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(intentId),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/board.py")));
    }

    @Test
    void acceptedResponseReservesSnapshotUntilGrantReplay(@TempDir Path temp) throws Exception {
        Scenario scenario = prepare(temp.resolve("review-admission"));
        PredictionEventStore.setTestFaultInjector(boundary -> {
            if (boundary == PredictionEventStore.AppendBoundary.AFTER_PROJECTION) {
                throw new IOException("SIMULATED_PROCESS_LOSS_AFTER_ACCEPTED_RESPONSE");
            }
        });
        try {
            IOException crash = assertThrows(IOException.class,
                    () -> scenario.service().respond(scenario.producer().participant(),
                            scenario.request().requestId(),
                            CoordinationRequest.Status.ACCEPTED,
                            "candidate selected"));
            assertEquals("SIMULATED_PROCESS_LOSS_AFTER_ACCEPTED_RESPONSE", crash.getMessage());
        } finally {
            PredictionEventStore.clearTestFaultInjector();
        }

        PredictionEventStore restarted = new PredictionEventStore(scenario.root(), scenario.project());
        assertEquals(CoordinationRequest.Status.ACCEPTED,
                restarted.collaborationProjection().requests().getFirst().status());
        assertTrue(restarted.workGroupProjection().grants().isEmpty());
        WorkIntentService recovered = new WorkIntentService(restarted, scenario.identity());

        IOException competingReview = assertThrows(IOException.class,
                () -> recovered.request(scenario.secondReviewer().participant(),
                        scenario.producer().intentId(),
                        CoordinationRequest.Kind.REVIEW,
                        "attempt to take the already accepted candidate",
                        scenario.snapshotId()));
        assertEquals("REVIEW_CANDIDATE_ALREADY_ASSIGNED", competingReview.getMessage());
        assertEquals(1,
                new PredictionEventStore(scenario.root(), scenario.project()).collaborationProjection().requests()
                        .size());

        recovered.respond(scenario.producer().participant(),
                scenario.request().requestId(),
                CoordinationRequest.Status.ACCEPTED,
                "replay accepted response");

        PredictionEventStore converged = new PredictionEventStore(scenario.root(), scenario.project());
        assertEquals(1, converged.workGroupProjection().grants().size());
        assertEquals(scenario.snapshotId(), converged.workGroupProjection().grants().getFirst().snapshotId());
        assertEquals(1,
                converged.events().stream().filter(event -> event.type() == PredictionEventType.COORDINATION_RESPONDED)
                        .count());
    }

    private record Scenario(Path root, UUID project, NodeIdentity identity, WorkIntentService service,
                            WorkIntent producer, WorkIntent secondReviewer, CoordinationRequest request,
                            String snapshotId) {

    }
}
