package org.synesis.coordination.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/** Verifies deterministic active-intent and claim arbitration behavior. */
final class WorkIntentServiceTest {
    @Test
    void exactAndSubtreeClaimsConflictButUnrelatedClaimsDoNot(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService service = new WorkIntentService(store, identity);

        WorkIntent first = intent(project, "agt-first", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(first).acquired());

        WorkIntent conflict = intent(project, "agt-second", ResourceSelector.pathSubtree("src"));
        assertFalse(service.announce(conflict).acquired());
        assertEquals(1, service.announce(conflict).conflicts().size());

        WorkIntent unrelated = intent(project, "agt-third", ResourceSelector.pathExact("tests/test_task_tracker.py"));
        assertTrue(service.announce(unrelated).acquired());
        assertTrue(service.owns("agt-first", ResourceSelector.pathExact("src/task_tracker.py")));
    }

    @Test
    void releaseRemovesClaimAndReplayPreservesActiveIntent(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService service = new WorkIntentService(store, identity);
        WorkIntent intent = intent(project, "agt-first", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(intent).acquired());
        assertTrue(service.owns("agt-first", ResourceSelector.pathExact("src/task_tracker.py")));
        service.release(intent.intentId(), "agt-first");
        assertFalse(service.owns("agt-first", ResourceSelector.pathExact("src/task_tracker.py")));

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertTrue(replayed.collaborationProjection().activeIntents().isEmpty());
        WorkIntent reacquired = intent(project, "agt-second", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(reacquired).acquired());
    }

    @Test
    void releaseRequiresTheClaimOwnerAndActivationSurvivesRelease(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        WorkIntent intent = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(intent).acquired());
        assertThrows(java.io.IOException.class, () -> service.release(intent.intentId(), "agt-other"));
        service.release(intent.intentId(), "agt-owner");
        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertTrue(replayed.collaborationProjection().activated());
    }

    @Test
    void conflictingParticipantsCanDiscoverAndResolveNegotiation(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(owner).acquired());
        CoordinationRequest request = service.request("agt-contender", owner.intentId(),
                CoordinationRequest.Kind.CONTRACT, "Agree on TaskTracker API v1");
        assertEquals(CoordinationRequest.Status.PENDING, service.requests().get(0).status());
        service.respond("agt-owner", request.requestId(), CoordinationRequest.Status.ACCEPTED, "API accepted");
        assertEquals(CoordinationRequest.Status.ACCEPTED, service.requests().get(0).status());
        assertTrue(service.owns("agt-owner", ResourceSelector.pathExact("src/task_tracker.py")));
    }

    private static WorkIntent intent(UUID project, String participant, ResourceSelector selector) {
        return new WorkIntent(UUID.randomUUID(), project, participant, "codex", UUID.randomUUID(),
                "Implement task tracker", "45 tests pass", "base-commit", List.of(selector), 1,
                WorkIntent.Status.ANNOUNCED);
    }
}
