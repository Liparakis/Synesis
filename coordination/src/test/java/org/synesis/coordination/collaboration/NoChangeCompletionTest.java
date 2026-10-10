package org.synesis.coordination.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.NoChangeCompletion;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies the explicit, durable no-change completion transition.
 */
final class NoChangeCompletionTest {

    private static WorkIntent intent(UUID project, String participant, String path, UUID group) {
        UUID intentId = UUID.randomUUID();
        return new WorkIntent(intentId,
                project,
                participant,
                "codex",
                UUID.randomUUID(),
                "Verify the repository",
                "Verification succeeds without mutation",
                "base-commit",
                List.of(ResourceSelector.pathExact(path)),
                1,
                group,
                WorkIntent.defaultAuthorityLineage(intentId),
                WorkIntent.Status.ANNOUNCED);
    }

    private static NoChangeCompletion completion(Path temp, UUID project, WorkIntent intent) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkGroup group = store.workGroupProjection().group(intent.workGroupId()).orElseThrow();
        return new NoChangeCompletion(intent.intentId(),
                intent.workGroupId(),
                intent.participant(),
                intent.provider(),
                "binding-" + intent.participant(),
                intent.authorityLineageId(),
                intent.version(),
                group.version(),
                store.headSequence(),
                intent.baseCommit(),
                "Verification completed successfully; no repository mutation was required");
    }

    @Test
    void lastNoChangeIntentReleasesClaimsAndCompletesGroup(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent intent = intent(project, "agt-verifier", "verification.py", UUID.randomUUID());
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        assertTrue(service.announce(intent).acquired());

        NoChangeCompletion completion = completion(temp, project, intent);
        assertEquals(completion, service.completeNoChange(completion));

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertTrue(replayed.collaborationProjection().activeIntents().isEmpty());
        assertEquals(WorkGroup.Status.COMPLETED,
                replayed.workGroupProjection().group(intent.workGroupId()).orElseThrow().status());
        assertEquals("verification.py", intent.selectors().getFirst().value());
        assertEquals(1,
                replayed.events().stream().filter(event -> event.type() == PredictionEventType.WORK_INTENT_RELEASED)
                        .count());
        assertEquals(1,
                replayed.events().stream()
                        .filter(event -> event.type() == PredictionEventType.WORK_GROUP_STATUS_CHANGED).count());
        assertFalse(service.owns(intent.participant(), intent.selectors().getFirst()));
    }

    @Test
    void providerDetachDoesNotSilentlyCompleteTheGroup(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent intent = intent(project, "agt-exited", "verification.py", UUID.randomUUID());
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        assertTrue(service.announce(intent).acquired());

        service.detach(intent.participant());

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertEquals(WorkGroup.Status.ACTIVE,
                replayed.workGroupProjection().group(intent.workGroupId()).orElseThrow().status());
        assertTrue(replayed.collaborationProjection().activeIntents().isEmpty());
        assertEquals(0,
                replayed.events().stream()
                        .filter(event -> event.type() == PredictionEventType.WORK_GROUP_STATUS_CHANGED).count());
    }

    @Test
    void completionIsIdempotentAndDoesNotDuplicateTerminalEvents(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent intent = intent(project, "agt-retry", "verification.py", UUID.randomUUID());
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        assertTrue(service.announce(intent).acquired());

        NoChangeCompletion completion = completion(temp, project, intent);
        service.completeNoChange(completion);
        int eventsAfterFirst = new PredictionEventStore(temp, project).events().size();
        assertEquals(completion, service.completeNoChange(completion));

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertEquals(eventsAfterFirst, replayed.events().size());
        assertEquals(1,
                replayed.events().stream().filter(event -> event.type() == PredictionEventType.WORK_INTENT_RELEASED)
                        .count());
        assertEquals(1,
                replayed.events().stream()
                        .filter(event -> event.type() == PredictionEventType.WORK_GROUP_STATUS_CHANGED).count());
    }

    @Test
    void cancellingTheOnlyLaneOfAGroupCancelsTheGroup(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        WorkIntent only = intent(project, "agt-only", "docs/usage.md", group);
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project),
                NodeIdentity.generate());
        assertTrue(service.announce(only).acquired());

        service.cancel("agt-only");

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertEquals(WorkGroup.Status.CANCELLED, replayed.workGroupProjection().group(group).orElseThrow().status());
    }

    @Test
    void cancellingOneLaneLeavesTheGroupOpenForItsSibling(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        WorkIntent first = intent(project, "agt-first", "verification-a.py", group);
        WorkIntent second = intent(project, "agt-second", "verification-b.py", group);
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project),
                NodeIdentity.generate());
        assertTrue(service.announce(first).acquired());
        assertTrue(service.announce(second).acquired());

        service.cancel("agt-first");

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertEquals(WorkGroup.Status.ACTIVE, replayed.workGroupProjection().group(group).orElseThrow().status());
        assertTrue(replayed.collaborationProjection().intent(second.intentId()).isPresent());
    }

    @Test
    void cancellingTheLastOpenLaneCompletesAGroupWhoseOtherLaneFinished(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        WorkIntent finished = intent(project, "agt-finished", "verification-a.py", group);
        WorkIntent cancelled = intent(project, "agt-cancelled", "verification-b.py", group);
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project),
                NodeIdentity.generate());
        assertTrue(service.announce(finished).acquired());
        assertTrue(service.announce(cancelled).acquired());
        service.completeNoChange(completion(temp, project, finished));

        service.cancel("agt-cancelled");

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertEquals(WorkGroup.Status.COMPLETED, replayed.workGroupProjection().group(group).orElseThrow().status());
    }

    @Test
    void siblingIntentKeepsGroupActiveUntilItAlsoCompletes(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent first = intent(project, "agt-first", "verification-a.py", group);
        WorkIntent second = intent(project, "agt-second", "verification-b.py", group);
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        assertTrue(service.announce(first).acquired());
        assertTrue(service.announce(second).acquired());

        service.completeNoChange(completion(temp, project, first));
        PredictionEventStore afterFirst = new PredictionEventStore(temp, project);
        assertEquals(WorkGroup.Status.ACTIVE, afterFirst.workGroupProjection().group(group).orElseThrow().status());
        assertTrue(afterFirst.collaborationProjection().intent(second.intentId()).isPresent());

        service.completeNoChange(completion(temp, project, second));
        PredictionEventStore afterSecond = new PredictionEventStore(temp, project);
        assertEquals(WorkGroup.Status.COMPLETED, afterSecond.workGroupProjection().group(group).orElseThrow().status());
    }

    @Test
    void staleEpochGroupVersionAndRevisionAreRejected(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent intent = intent(project, "agt-stale", "verification.py", UUID.randomUUID());
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        assertTrue(service.announce(intent).acquired());

        NoChangeCompletion valid = completion(temp, project, intent);
        assertThrows(IOException.class,
                () -> service.completeNoChange(new NoChangeCompletion(valid.intentId(),
                        valid.workGroupId(),
                        valid.participant(),
                        valid.provider(),
                        valid.bindingIdentity(),
                        valid.authorityLineageId(),
                        valid.claimEpoch() + 1,
                        valid.workGroupVersion(),
                        valid.expectedRevision(),
                        valid.workspaceCommit(),
                        valid.summary())));
        assertThrows(IOException.class,
                () -> service.completeNoChange(new NoChangeCompletion(valid.intentId(),
                        valid.workGroupId(),
                        valid.participant(),
                        valid.provider(),
                        valid.bindingIdentity(),
                        valid.authorityLineageId(),
                        valid.claimEpoch(),
                        valid.workGroupVersion() + 1,
                        valid.expectedRevision(),
                        valid.workspaceCommit(),
                        valid.summary())));
        assertThrows(IOException.class,
                () -> service.completeNoChange(new NoChangeCompletion(valid.intentId(),
                        valid.workGroupId(),
                        valid.participant(),
                        valid.provider(),
                        valid.bindingIdentity(),
                        valid.authorityLineageId(),
                        valid.claimEpoch(),
                        valid.workGroupVersion(),
                        valid.expectedRevision() - 1,
                        valid.workspaceCommit(),
                        valid.summary())));

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertTrue(replayed.collaborationProjection().intent(intent.intentId()).isPresent());
    }

    @Test
    void pendingGrantBlocksNoChangeCompletion(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent intent = intent(project, "agt-owner", "verification.py", UUID.randomUUID());
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        assertTrue(service.announce(intent).acquired());
        new WorkGroupService(new PredictionEventStore(temp, project), identity).issue(new LaneGrant(UUID.randomUUID(),
                intent.workGroupId(),
                intent.intentId(),
                "agt-reviewer",
                intent.version(),
                true));

        IOException failure = assertThrows(IOException.class,
                () -> service.completeNoChange(completion(temp, project, intent)));
        assertEquals("NO_CHANGE_REVIEW_OBLIGATION", failure.getMessage());
        assertTrue(new PredictionEventStore(temp, project).collaborationProjection().intent(intent.intentId())
                .isPresent());
    }

    @Test
    void consumedUnvalidatedReviewGrantBlocksNoChangeCompletion(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent intent = intent(project, "agt-owner", "verification.py", UUID.randomUUID());
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        assertTrue(service.announce(intent).acquired());
        UUID grantId = UUID.randomUUID();
        new WorkGroupService(new PredictionEventStore(temp, project), identity).issue(new LaneGrant(grantId,
                intent.workGroupId(),
                intent.intentId(),
                "agt-reviewer",
                intent.version(),
                true));
        new WorkGroupService(new PredictionEventStore(temp, project), identity).consume(grantId,
                "agt-reviewer",
                intent.intentId(),
                intent.version());

        IOException failure = assertThrows(IOException.class,
                () -> service.completeNoChange(completion(temp, project, intent)));
        assertEquals("NO_CHANGE_REVIEW_OBLIGATION", failure.getMessage());
        assertTrue(new PredictionEventStore(temp, project).collaborationProjection().intent(intent.intentId())
                .isPresent());
    }

    @Test
    void heartbeatsAfterProjectionDoNotStaleTheCompletion(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent finishing = intent(project, "agt-finishing", "verification-a.py", group);
        WorkIntent polling = intent(project, "agt-polling", "verification-b.py", group);
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        assertTrue(service.announce(finishing).acquired());
        assertTrue(service.announce(polling).acquired());

        NoChangeCompletion projected = completion(temp, project, finishing);
        service.heartbeat(polling.participant());
        service.heartbeat(finishing.participant());

        PredictionEventStore beforeFinish = new PredictionEventStore(temp, project);
        assertEquals(projected.expectedRevision() + 2, beforeFinish.headSequence());
        assertTrue(beforeFinish.unchangedSince(projected.expectedRevision()));
        assertTrue(beforeFinish.unchangedSince(beforeFinish.headSequence()));
        assertFalse(beforeFinish.unchangedSince(beforeFinish.headSequence() + 1));
        assertFalse(beforeFinish.unchangedSince(-1));

        assertEquals(projected, service.completeNoChange(projected));

        // A fresh replay applies the projection's own validation of the recorded revision.
        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertTrue(replayed.collaborationProjection().intent(finishing.intentId()).isEmpty());
        assertTrue(replayed.collaborationProjection().noChangeCompletion(finishing.intentId()).isPresent());
        assertTrue(replayed.collaborationProjection().intent(polling.intentId()).isPresent());
    }

    @Test
    void stateChangeAfterProjectionStillStalesTheCompletion(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent finishing = intent(project, "agt-finishing", "verification-a.py", group);
        WorkIntent polling = intent(project, "agt-polling", "verification-b.py", group);
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        assertTrue(service.announce(finishing).acquired());
        assertTrue(service.announce(polling).acquired());

        NoChangeCompletion projected = completion(temp, project, finishing);
        service.heartbeat(polling.participant());
        assertTrue(service.announce(intent(project, "agt-late", "verification-c.py", group)).acquired());
        service.heartbeat(polling.participant());

        assertFalse(new PredictionEventStore(temp, project).unchangedSince(projected.expectedRevision()));
        assertThrows(IOException.class, () -> service.completeNoChange(projected));
        assertTrue(new PredictionEventStore(temp, project).collaborationProjection().intent(finishing.intentId()).isPresent());
    }
}
