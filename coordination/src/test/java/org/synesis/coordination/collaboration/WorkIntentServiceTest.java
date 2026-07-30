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
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.application.WorkGroupService;
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
    void conflictCreatesIdempotentInboxItemsForOwnerAndContender(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        WorkIntent contender = intent(project, "agt-contender", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(owner).acquired());
        assertFalse(service.announce(contender).acquired());
        int firstCount = service.requests().size();
        assertEquals(2, firstCount);
        assertTrue(service.requests().stream().anyMatch(request -> request.target().equals("agt-owner")));
        assertTrue(service.requests().stream().anyMatch(request -> request.target().equals("agt-contender")));
        assertFalse(service.announce(contender).acquired());
        assertEquals(firstCount, service.requests().size());
    }

    @Test
    void groupedIntentRoundTripsItsLogicalParentWhileLegacyIntentIsSingleton(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent grouped = new WorkIntent(UUID.randomUUID(), project, "agt-grouped", "codex", UUID.randomUUID(),
                "parallel work", "contract accepted", "base", List.of(ResourceSelector.pathExact("src/a.py")),
                1, group, WorkIntent.Status.ANNOUNCED);
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        assertTrue(service.announce(grouped).acquired());
        WorkIntent replayed = new PredictionEventStore(temp, project).collaborationProjection()
                .activeIntents().getFirst();
        assertEquals(group, replayed.workGroupId());
        UUID legacyId = UUID.randomUUID();
        assertEquals(legacyId, new WorkIntent(legacyId, project, "agt-legacy", "codex", UUID.randomUUID(),
                "g", "a", "base", List.of(ResourceSelector.pathExact("src/b.py")), 1,
                WorkIntent.Status.ANNOUNCED).workGroupId());
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

    @Test
    void handoffRetainsOwnerUntilAcceptedThenFencesSourceEpoch(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        WorkIntent target = intent(project, "agt-target", ResourceSelector.pathExact("tests/test_task_tracker.py"));
        assertTrue(service.announce(owner).acquired());
        assertTrue(service.announce(target).acquired());
        CoordinationRequest offer = service.offerHandoff("agt-owner", owner.intentId(), "agt-target", "snapshot clean");
        assertTrue(service.owns("agt-owner", ResourceSelector.pathExact("src/task_tracker.py")));
        service.respond("agt-target", offer.requestId(), CoordinationRequest.Status.ACCEPTED, "accepted");
        assertFalse(service.owns("agt-owner", ResourceSelector.pathExact("src/task_tracker.py")));
        assertTrue(service.owns("agt-target", ResourceSelector.pathExact("src/task_tracker.py")));
        assertEquals(2, service.activeIntents().stream().filter(i -> i.intentId().equals(owner.intentId())).findFirst().orElseThrow().version());
    }

    @Test
    void inboxAcknowledgementIsExactCallerAuthorizedAndIdempotent(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        WorkIntent contender = intent(project, "agt-contender", ResourceSelector.pathExact("tests/task_tracker.py"));
        assertTrue(service.announce(owner).acquired());
        assertTrue(service.announce(contender).acquired());
        CoordinationRequest request = service.request("agt-contender", owner.intentId(),
                CoordinationRequest.Kind.CONTRACT, "agree API");
        assertFalse(new PredictionEventStore(temp, project).collaborationProjection().inboxAcknowledged(request.requestId()));
        assertThrows(java.io.IOException.class, () -> service.acknowledgeInbox("agt-contender", request.requestId()));
        service.acknowledgeInbox("agt-owner", request.requestId());
        service.acknowledgeInbox("agt-owner", request.requestId());
        assertTrue(new PredictionEventStore(temp, project).collaborationProjection().inboxAcknowledged(request.requestId()));
    }

    @Test
    void cancellationReleasesClaimsAndPermanentlyFencesLane(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(owner).acquired());
        service.cancel("agt-owner");
        var projection = new PredictionEventStore(temp, project).collaborationProjection();
        assertFalse(service.owns("agt-owner", ResourceSelector.pathExact("src/task_tracker.py")));
        assertEquals(org.synesis.coordination.domain.collaboration.Participant.State.CANCELLED,
                projection.participants().getFirst().state());
    }

    @Test
    void continuationTransfersHeldClaimsAtomicallyAndConsumesSingleUseGrant(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService service = new WorkIntentService(store, identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(owner).acquired());
        service.suspend("agt-owner");
        String reference = temp.resolve("recovery").toAbsolutePath() + "#hash";
        service.holdRecovery("agt-owner", reference);
        UUID targetIntentId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        new WorkGroupService(new PredictionEventStore(temp, project), identity).issue(new LaneGrant(
                grantId, owner.workGroupId(), targetIntentId, "agt-target", owner.version(), true));
        WorkIntent target = new WorkIntent(targetIntentId, project, "agt-target", "codex", UUID.randomUUID(),
                owner.goal(), owner.acceptance(), owner.baseCommit(), owner.selectors(), owner.version() + 1,
                owner.workGroupId(), WorkIntent.Status.ANNOUNCED);
        service.continueFromRecovery(new CollaborationCodec.Continuation(grantId, owner.intentId(), target,
                "agt-owner", "agt-target", owner.version(), reference));

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertFalse(service.owns("agt-owner", ResourceSelector.pathExact("src/task_tracker.py")));
        assertTrue(service.owns("agt-target", ResourceSelector.pathExact("src/task_tracker.py")));
        assertFalse(replayed.workGroupProjection().grantAvailable(grantId));
        assertEquals(org.synesis.coordination.domain.collaboration.Participant.State.DETACHED,
                replayed.collaborationProjection().participantState("agt-owner").orElseThrow());
    }

    private static WorkIntent intent(UUID project, String participant, ResourceSelector selector) {
        return new WorkIntent(UUID.randomUUID(), project, participant, "codex", UUID.randomUUID(),
                "Implement task tracker", "45 tests pass", "base-commit", List.of(selector), 1,
                WorkIntent.Status.ANNOUNCED);
    }
}
