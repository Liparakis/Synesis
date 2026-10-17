package org.synesis.coordination.collaboration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.ProjectionValidation;
import org.synesis.coordination.application.ContinuationResult;
import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies deterministic active-intent and claim arbitration behavior.
 */
final class WorkIntentServiceTest {

    private static PredictionEventStore fresh(Path temp, UUID project) throws Exception {
        return new PredictionEventStore(temp, project);
    }

    private static WorkIntent intent(UUID project, String participant, ResourceSelector selector) {
        return new WorkIntent(UUID.randomUUID(),
                project,
                participant,
                "codex",
                UUID.randomUUID(),
                "Implement task tracker",
                "45 tests pass",
                "base-commit",
                List.of(selector),
                1,
                WorkIntent.Status.ANNOUNCED);
    }

    private static WorkIntent lane(UUID project,
            UUID groupId,
            String participant,
            WorkIntent.Role role,
            String claim,
            List<ResourceSelector> reviewTargets) {
        UUID intentId = UUID.randomUUID();
        return new WorkIntent(intentId,
                project,
                participant,
                "codex",
                UUID.randomUUID(),
                "Build a board",
                "CRUD and persistence",
                "base-commit",
                List.of(ResourceSelector.pathExact(claim)),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(intentId),
                WorkIntent.Status.ANNOUNCED,
                role,
                reviewTargets);
    }

    private static TaskSnapshotPayload snapshot(WorkIntent owner, String snapshotId, String path) {
        String sessionId = "session-" + owner.participant();
        SnapshotProvenance provenance = new SnapshotProvenance(owner.workGroupId(),
                owner.intentId(),
                owner.authorityLineageId(),
                owner.participant(),
                sessionId,
                owner.version(),
                List.of(),
                List.of(),
                List.of("PATH_EXACT:" + path),
                "refs/synesis/snapshots/" + snapshotId,
                "integrity-" + snapshotId,
                "manifest-" + snapshotId);
        return new TaskSnapshotPayload(owner.taskId(),
                snapshotId,
                "node",
                "supervisor",
                "worker",
                sessionId,
                "base-commit",
                "commit-" + snapshotId,
                List.of(path),
                List.of(),
                "Published candidate",
                provenance,
                true);
    }

    private static String attemptContinuation(Path temp,
            UUID project,
            WorkIntent owner,
            String participant,
            String reference,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        UUID targetIntentId = UUID.nameUUIDFromBytes(("target:" + owner.intentId() + ":" + participant).getBytes(
                StandardCharsets.UTF_8));
        UUID grantId = UUID.nameUUIDFromBytes(("grant:" + owner.intentId() + ":" + participant).getBytes(
                StandardCharsets.UTF_8));
        WorkIntent target = new WorkIntent(targetIntentId,
                project,
                participant,
                "codex",
                owner.taskId(),
                owner.goal(),
                owner.acceptance(),
                owner.baseCommit(),
                owner.selectors(),
                owner.version() + 1,
                owner.workGroupId(),
                owner.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                owner.role(),
                owner.reviewTargetSelectors(),
                owner.knownDependencies());
        ready.countDown();
        start.await();
        try {
            ContinuationResult result = new WorkIntentService(new PredictionEventStore(temp, project),
                    NodeIdentity.generate()).continueEligibleFromRecovery(new LaneContinuation(grantId,
                    owner.intentId(),
                    target,
                    owner.participant(),
                    participant,
                    owner.version(),
                    reference));
            return "COMMITTED".equals(result.status()) ? "WON" : result.status();
        } catch (IOException expected) {
            return expected.getMessage();
        }
    }

    @Test
    void reviewerIsAdmittedOnlyBesideAProducerOfAnotherParticipantThatItsTargetsCover(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService service = new WorkIntentService(store, NodeIdentity.generate());
        List<ResourceSelector> sourceTargets = List.of(ResourceSelector.pathExact("src/board.py"));

        WorkIntent early = lane(project, groupId, "agt-reviewer", WorkIntent.Role.REVIEWER, "tests/board_test.py", sourceTargets);
        IOException refused = assertThrows(IOException.class, () -> service.announce(early));
        assertEquals("REVIEWER_REQUIRES_PRODUCER", refused.getMessage());
        assertTrue(fresh(temp, project).collaborationProjection().intent(early.identity()).isEmpty());
        assertTrue(fresh(temp, project).collaborationProjection().activeIntents().isEmpty());
        assertTrue(fresh(temp, project).workGroupProjection().group(early.group()).isEmpty());

        WorkIntent producer = lane(project, groupId, "agt-producer", WorkIntent.Role.PRODUCER, "src/board.py", List.of());
        assertTrue(service.announce(producer).acquired());

        WorkIntent elsewhere = lane(project, groupId, "agt-elsewhere", WorkIntent.Role.REVIEWER, "tests/other_test.py", List.of(ResourceSelector.pathExact("src/other.py")));
        assertEquals("REVIEWER_REQUIRES_PRODUCER", assertThrows(IOException.class, () -> service.announce(elsewhere)).getMessage());
        assertTrue(fresh(temp, project).collaborationProjection().intent(elsewhere.identity()).isEmpty());

        assertTrue(service.announce(early).acquired());
        assertTrue(fresh(temp, project).collaborationProjection().intent(early.identity()).isPresent());
    }

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
    void scopeExtensionIsIdempotentAndPreservesExistingLaneState(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService service = new WorkIntentService(store, identity);
        WorkIntent original = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_store.py"));
        assertTrue(service.announce(original).acquired());
        int initialEventCount = new PredictionEventStore(temp, project).events().size();

        var repeated = service.extendScope(original.identity(), original.participantIdentity(), new ClaimEpoch(1),
                original.selectors(), null, null, original.knownDependencies());
        assertTrue(repeated.acquired());
        assertEquals(1, repeated.intent().version());
        assertEquals(initialEventCount, new PredictionEventStore(temp, project).events().size());

        ResourceSelector added = ResourceSelector.pathExact("tests/test_task_store.py");
        var extended = service.extendScope(original.identity(), original.participantIdentity(), new ClaimEpoch(1),
                List.of(added), "Updated goal", "Updated acceptance", List.of("tasktracker.domain"));
        assertTrue(extended.acquired());
        assertEquals(2, extended.intent().version());
        assertEquals(List.of(original.selectors().getFirst(), added), extended.intent().selectors());
        assertEquals("Updated goal", extended.intent().goal());
        assertEquals("Updated acceptance", extended.intent().acceptance());
        assertEquals(List.of("tasktracker.domain"), extended.intent().knownDependencies());

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertEquals(extended.intent(), replayed.collaborationProjection().intent(original.identity()).orElseThrow());
        assertEquals(1, replayed.events().stream()
                .filter(event -> event.type() == PredictionEventType.WORK_INTENT_SCOPE_EXTENDED).count());
    }

    @Test
    void scopeExtensionRejectsStaleEpochAndForeignOverlapWithoutPartialAcquisition(@TempDir Path temp)
            throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService service = new WorkIntentService(store, identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_store.py"));
        WorkIntent foreign = intent(project, "agt-foreign", ResourceSelector.pathExact("src/task_cli.py"));
        assertTrue(service.announce(owner).acquired());
        assertTrue(service.announce(foreign).acquired());
        int beforeRejectedAmendment = new PredictionEventStore(temp, project).events().size();

        var conflict = service.extendScope(owner.identity(), owner.participantIdentity(), new ClaimEpoch(1),
                List.of(ResourceSelector.pathExact("src/task_cli.py"), ResourceSelector.pathExact("tests/new.py")),
                null, null, List.of());
        assertFalse(conflict.acquired());
        PredictionEventStore afterConflict = new PredictionEventStore(temp, project);
        WorkIntent unchanged = afterConflict.collaborationProjection().intent(owner.identity()).orElseThrow();
        assertEquals(1, unchanged.version());
        assertEquals(List.of(owner.selectors().getFirst()), unchanged.selectors());
        assertEquals(beforeRejectedAmendment, afterConflict.events().size());

        service.extendScope(owner.identity(), owner.participantIdentity(), new ClaimEpoch(1),
                List.of(ResourceSelector.pathExact("tests/accepted.py")), null, null, List.of());
        IOException stale = assertThrows(IOException.class, () -> service.extendScope(owner.identity(),
                owner.participantIdentity(), new ClaimEpoch(1), List.of(ResourceSelector.pathExact("tests/stale.py")),
                null, null, List.of()));
        assertEquals("CLAIM_EPOCH_STALE", stale.getMessage());
    }

    @Test
    void concurrentScopeExtensionsAtOneEpochAcquireOneCompleteAmendment(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_store.py"));
        assertTrue(new WorkIntentService(new PredictionEventStore(temp, project), identity).announce(owner).acquired());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> concurrentScopeResult(temp, project, identity, owner,
                    "tests/first.py", ready, start));
            Future<Boolean> second = executor.submit(() -> concurrentScopeResult(temp, project, identity, owner,
                    "tests/second.py", ready, start));
            ready.await();
            start.countDown();
            assertTrue(first.get() ^ second.get());
        }
        WorkIntent current = new PredictionEventStore(temp, project).collaborationProjection()
                .intent(owner.identity()).orElseThrow();
        assertEquals(2, current.version());
        assertEquals(2, current.selectors().size());
    }

    private static boolean concurrentScopeResult(Path temp, UUID project, NodeIdentity identity, WorkIntent owner,
            String addedPath, CountDownLatch ready, CountDownLatch start) throws Exception {
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        ready.countDown();
        start.await();
        try {
            return service.extendScope(owner.identity(), owner.participantIdentity(), new ClaimEpoch(1),
                    List.of(ResourceSelector.pathExact(addedPath)), null, null, List.of()).acquired();
        } catch (IOException stale) {
            if (!"CLAIM_EPOCH_STALE".equals(stale.getMessage())) {
                throw stale;
            }
            return false;
        }
    }

    @Test
    void conflictDeniesClaimWithoutCreatingReciprocalRequests(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        WorkIntent contender = intent(project, "agt-contender", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(owner).acquired());
        var rejected = service.announce(contender);
        assertFalse(rejected.acquired());
        assertEquals(owner.authorityLineageId(), rejected.conflicts().getFirst().ownerAuthorityLineageId());
        assertTrue(service.requests().isEmpty());
        assertFalse(service.announce(contender).acquired());
        assertTrue(service.requests().isEmpty());
    }

    @Test
    void groupedIntentRoundTripsItsLogicalParentWhileLegacyIntentIsSingleton(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent grouped = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-grouped",
                "codex",
                UUID.randomUUID(),
                "parallel work",
                "contract accepted",
                "base",
                List.of(ResourceSelector.pathExact("src/a.py")),
                1,
                group,
                WorkIntent.Status.ANNOUNCED);
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        assertTrue(service.announce(grouped).acquired());
        WorkIntent replayed = new PredictionEventStore(temp, project).collaborationProjection().activeIntents()
                .getFirst();
        assertEquals(group, replayed.workGroupId());
        UUID legacyId = UUID.randomUUID();
        assertEquals(legacyId,
                new WorkIntent(legacyId,
                        project,
                        "agt-legacy",
                        "codex",
                        UUID.randomUUID(),
                        "g",
                        "a",
                        "base",
                        List.of(ResourceSelector.pathExact("src/b.py")),
                        1,
                        WorkIntent.Status.ANNOUNCED).workGroupId());
    }

    @Test
    void lateIntentCannotBeAnnouncedIntoCompletedWorkGroup(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService service = new WorkIntentService(store, identity);
        WorkIntent completedLane = intent(project, "agt-completed", ResourceSelector.pathExact("src/completed.py"));
        assertTrue(service.announce(completedLane).acquired());

        WorkGroup group = new PredictionEventStore(temp, project).workGroupProjection()
                .group(completedLane.workGroupId()).orElseThrow();
        new WorkGroupService(new PredictionEventStore(temp, project), identity).close(group.workGroupId(),
                WorkGroup.Status.COMPLETED,
                group.version());

        WorkIntent lateLane = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-late",
                "codex",
                UUID.randomUUID(),
                completedLane.goal(),
                completedLane.acceptance(),
                completedLane.baseCommit(),
                List.of(ResourceSelector.pathExact("src/late.py")),
                1,
                completedLane.workGroupId(),
                WorkIntent.Status.ANNOUNCED);
        assertThrows(IOException.class, () -> service.announce(lateLane));

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertEquals(WorkGroup.Status.COMPLETED,
                replayed.workGroupProjection().group(completedLane.workGroupId()).orElseThrow().status());
        assertTrue(replayed.collaborationProjection().activeIntents().stream()
                .noneMatch(candidate -> candidate.intentId().equals(lateLane.intentId())));
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
        assertThrows(IOException.class, () -> service.release(intent.intentId(), "agt-other"));
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
        CoordinationRequest request = service.request("agt-contender",
                owner.intentId(),
                CoordinationRequest.Kind.CONTRACT,
                "Agree on TaskTracker API v1");
        assertEquals(CoordinationRequest.Status.PENDING, service.requests().getFirst().status());
        service.respond("agt-owner", request.requestId(), CoordinationRequest.Status.ACCEPTED, "API accepted");
        assertEquals(CoordinationRequest.Status.ACCEPTED, service.requests().getFirst().status());
        assertTrue(service.owns("agt-owner", ResourceSelector.pathExact("src/task_tracker.py")));
    }

    @Test
    void reviewAdmissionRequiresReviewerIntentAndConsumesIssuedGrant(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService service = new WorkIntentService(store, identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(owner).acquired());

        CoordinationRequest request = service.request("agt-reviewer",
                owner.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the published snapshot without claiming its files");
        assertEquals(CoordinationRequest.Status.PENDING, request.status());
        CoordinationRequest replayedRequest = service.request("agt-reviewer",
                owner.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the published snapshot without claiming its files");
        assertEquals(request.requestId(), replayedRequest.requestId());
        assertEquals(1, service.requests().size());

        IOException unannounced = assertThrows(IOException.class,
                () -> service.respond("agt-owner",
                        request.requestId(),
                        CoordinationRequest.Status.ACCEPTED,
                        "review admitted"));
        assertEquals("REVIEWER_INTENT_REQUIRED", unannounced.getMessage());
        PredictionEventStore afterUnannounced = new PredictionEventStore(temp, project);
        assertEquals(CoordinationRequest.Status.PENDING,
                afterUnannounced.collaborationProjection().requests().getFirst().status());
        assertEquals(0,
                afterUnannounced.events().stream()
                        .filter(event -> event.type() == PredictionEventType.COORDINATION_RESPONDED).count());
        assertTrue(afterUnannounced.workGroupProjection().grants().isEmpty());

        UUID reviewerId = UUID.randomUUID();
        WorkIntent reviewer = new WorkIntent(reviewerId,
                project,
                "agt-reviewer",
                "codex",
                UUID.randomUUID(),
                "Review task tracker",
                "Review the immutable producer snapshot",
                "base-commit",
                List.of(ResourceSelector.pathExact("docs/task-tracker-review.md")),
                1,
                owner.workGroupId(),
                WorkIntent.defaultAuthorityLineage(reviewerId),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/task_tracker.py")));
        assertTrue(service.announce(reviewer).acquired());

        CoordinationRequest admittedReplay = service.request("agt-reviewer",
                owner.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the published snapshot without claiming its files");
        assertEquals(request.requestId(), admittedReplay.requestId());
        service.respond("agt-owner", request.requestId(), CoordinationRequest.Status.ACCEPTED, "review admitted");

        CoordinationRequest acceptedReplay = service.request("agt-reviewer",
                owner.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the published snapshot without claiming its files");
        assertEquals(request.requestId(), acceptedReplay.requestId());

        var grants = new PredictionEventStore(temp, project).workGroupProjection().grants();
        assertEquals(1, grants.size());
        LaneGrant grant = grants.getFirst();
        assertEquals("agt-reviewer", grant.targetParticipant());
        assertEquals(owner.intentId(), grant.targetIntentId());
        assertEquals(owner.version(), grant.claimEpoch());
        assertTrue(service.owns("agt-owner", ResourceSelector.pathExact("src/task_tracker.py")));
        assertFalse(service.owns("agt-reviewer", ResourceSelector.pathExact("src/task_tracker.py")));

        new WorkGroupService(new PredictionEventStore(temp, project), identity).consume(grant.grantId(),
                "agt-reviewer",
                owner.intentId(),
                owner.version());
        assertFalse(new PredictionEventStore(temp, project).workGroupProjection().grantAvailable(grant.grantId()));

        // The owner response is idempotent and must not mint a second grant.
        service.respond("agt-owner", request.requestId(), CoordinationRequest.Status.ACCEPTED, "review admitted");
        assertEquals(1, new PredictionEventStore(temp, project).workGroupProjection().grants().size());
    }

    @Test
    void producerOnlyRequesterCannotAcceptReviewAdmission(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService service = new WorkIntentService(store, identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        UUID requesterId = UUID.randomUUID();
        WorkIntent requesterProducer = new WorkIntent(requesterId,
                project,
                "agt-reviewer",
                "codex",
                UUID.randomUUID(),
                "Implement another task",
                "Publish another task",
                "base-commit",
                List.of(ResourceSelector.pathExact("tests/other_task.py")),
                1,
                owner.workGroupId(),
                WorkIntent.defaultAuthorityLineage(requesterId),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER);
        assertTrue(service.announce(owner).acquired());
        assertTrue(service.announce(requesterProducer).acquired());

        CoordinationRequest request = service.request(requesterProducer.participant(),
                owner.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the producer snapshot");
        IOException rejected = assertThrows(IOException.class,
                () -> service.respond(owner.participant(),
                        request.requestId(),
                        CoordinationRequest.Status.ACCEPTED,
                        "review admitted"));

        assertEquals("REVIEWER_INTENT_REQUIRED", rejected.getMessage());
        PredictionEventStore afterRejected = new PredictionEventStore(temp, project);
        assertEquals(CoordinationRequest.Status.PENDING,
                afterRejected.collaborationProjection().requests().getFirst().status());
        assertEquals(0,
                afterRejected.events().stream()
                        .filter(event -> event.type() == PredictionEventType.COORDINATION_RESPONDED).count());
        assertTrue(afterRejected.workGroupProjection().grants().isEmpty());
    }

    @Test
    void aProducerWhoseSnapshotAwaitsReviewIsAdmittedToReviewAnotherAndKeepsItsOneLane(@TempDir Path temp)
            throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        new WorkGroupService(new PredictionEventStore(temp, project), identity).create(new WorkGroup(groupId,
                project,
                "Build modules",
                "Every module reviewed by a non-author",
                1,
                WorkGroup.Status.ACTIVE,
                ReviewPolicy.mandatoryIndependent()));
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        WorkIntent slug = lane(project, groupId, "agt-slug", WorkIntent.Role.PRODUCER, "slug.py", List.of());
        WorkIntent wrap = lane(project, groupId, "agt-wrap", WorkIntent.Role.PRODUCER, "wrap.py", List.of());
        assertTrue(service.announce(slug).acquired());
        assertTrue(service.announce(wrap).acquired());
        TaskSnapshotPayload slugSnapshot = snapshot(slug, "snap_slug", "slug.py");
        TaskSnapshotPayload wrapSnapshot = snapshot(wrap, "snap_wrap", "wrap.py");
        for (TaskSnapshotPayload published : List.of(slugSnapshot, wrapSnapshot)) {
            new PredictionEventStore(temp, project).append(UUID.randomUUID(),
                    PredictionEventType.TASK_SNAPSHOT_CREATED,
                    identity.nodeId(),
                    published.encode(),
                    identity);
        }

        IOException ownWork = assertThrows(IOException.class,
                () -> service.request(slug.participant(), slug.intentId(), CoordinationRequest.Kind.REVIEW,
                        "Review my own module", slugSnapshot.snapshotId()));
        assertEquals("REVIEW_SELF_NOT_ALLOWED", ownWork.getMessage());

        CoordinationRequest request = service.request(slug.participant(), wrap.intentId(),
                CoordinationRequest.Kind.REVIEW, "Review wrap.py", wrapSnapshot.snapshotId());
        service.respond(wrap.participant(), request.requestId(), CoordinationRequest.Status.ACCEPTED, "admitted");

        PredictionEventStore admitted = new PredictionEventStore(temp, project);
        LaneGrant grant = admitted.workGroupProjection().grants().getFirst();
        assertEquals(slug.participant(), grant.targetParticipant());
        assertEquals(wrapSnapshot.snapshotId(), grant.snapshotId());
        assertEquals(wrap.intentId(), grant.targetIntentId());
        new WorkGroupService(admitted, identity).consume(grant.grantId(), slug.participant(), wrap.intentId(),
                wrap.version());
        PredictionEventStore consumed = new PredictionEventStore(temp, project);
        assertTrue(consumed.workGroupProjection().grantConsumed(grant.grantId()));
        // Review authority is the grant; the reviewer's own lane and claim epoch are untouched.
        assertEquals(List.of(slug.intentId()), consumed.collaborationProjection().activeIntents().stream()
                .filter(intent -> intent.participant().equals(slug.participant())).map(WorkIntent::intentId).toList());
        assertEquals(slug.version(), consumed.collaborationProjection().intent(slug.intentId()).orElseThrow().version());
    }

    @Test
    void theReviewAProducerHeldMovesToTheParticipantThatContinuesItsLane(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = mandatoryGroup(temp, project, groupId, identity);
        WorkIntent slug = lane(project, groupId, "agt-slug", WorkIntent.Role.PRODUCER, "slug.py", List.of());
        WorkIntent wrap = lane(project, groupId, "agt-wrap", WorkIntent.Role.PRODUCER, "wrap.py", List.of());
        TaskSnapshotPayload wrapSnapshot = publishBoth(temp, project, identity, service, slug, wrap);
        CoordinationRequest request = service.request(slug.participant(), wrap.intentId(), CoordinationRequest.Kind.REVIEW,
                "Review wrap.py", wrapSnapshot.snapshotId());
        service.respond(wrap.participant(), request.requestId(), CoordinationRequest.Status.ACCEPTED, "admitted");
        LaneGrant held = new PredictionEventStore(temp, project).workGroupProjection().grants().getFirst();

        String reference = temp.resolve("recovery").toAbsolutePath() + "#hash";
        service.suspend(slug.participant());
        service.holdRecovery(slug.participant(), reference);
        WorkIntent successor = new WorkIntent(UUID.randomUUID(), project, "agt-continuer", "codex", slug.taskId(), slug.goal(),
                slug.acceptance(), slug.baseCommit(), slug.selectors(), slug.version() + 1, groupId, slug.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED, WorkIntent.Role.PRODUCER, List.of(), slug.knownDependencies());
        service.continueEligibleFromRecovery(new LaneContinuation(UUID.randomUUID(), slug.intentId(), successor,
                slug.participant(), successor.participant(), slug.version(), reference));

        PredictionEventStore continued = new PredictionEventStore(temp, project);
        assertTrue(continued.workGroupProjection().grantSuperseded(held.grantId()));
        List<LaneGrant> carried = continued.workGroupProjection().grants().stream()
                .filter(grant -> wrapSnapshot.snapshotId().equals(grant.snapshotId()))
                .filter(grant -> !continued.workGroupProjection().grantSuperseded(grant.grantId())).toList();
        assertEquals(List.of(successor.participant()), carried.stream().map(LaneGrant::targetParticipant).toList());
        assertEquals(successor.participant(), continued.collaborationProjection().requests().stream()
                .filter(candidate -> candidate.requestId().equals(request.requestId())).findFirst().orElseThrow().requester());
    }

    @Test
    void anAuthorWhoseLaneHasEndedIsNotAdmittedToReview(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = mandatoryGroup(temp, project, groupId, identity);
        WorkIntent slug = lane(project, groupId, "agt-slug", WorkIntent.Role.PRODUCER, "slug.py", List.of());
        WorkIntent wrap = lane(project, groupId, "agt-wrap", WorkIntent.Role.PRODUCER, "wrap.py", List.of());
        TaskSnapshotPayload wrapSnapshot = publishBoth(temp, project, identity, service, slug, wrap);
        CoordinationRequest request = service.request(slug.participant(), wrap.intentId(), CoordinationRequest.Kind.REVIEW,
                "Review wrap.py", wrapSnapshot.snapshotId());
        service.release(slug.intentId(), slug.participant());

        IOException refused = assertThrows(IOException.class,
                () -> service.respond(wrap.participant(), request.requestId(), CoordinationRequest.Status.ACCEPTED, "admitted"));
        assertEquals("REVIEWER_INTENT_REQUIRED", refused.getMessage());
        assertTrue(new PredictionEventStore(temp, project).workGroupProjection().grants().isEmpty());
    }

    private static WorkIntentService mandatoryGroup(Path temp, UUID project, UUID groupId, NodeIdentity identity) throws Exception {
        new WorkGroupService(new PredictionEventStore(temp, project), identity).create(new WorkGroup(groupId,
                project,
                "Build modules",
                "Every module reviewed by a non-author",
                1,
                WorkGroup.Status.ACTIVE,
                ReviewPolicy.mandatoryIndependent()));
        return new WorkIntentService(new PredictionEventStore(temp, project), identity);
    }

    /** Announces both lanes, publishes a snapshot of each, and returns the second one's. */
    private static TaskSnapshotPayload publishBoth(Path temp, UUID project, NodeIdentity identity, WorkIntentService service,
            WorkIntent first, WorkIntent second) throws Exception {
        assertTrue(service.announce(first).acquired());
        assertTrue(service.announce(second).acquired());
        TaskSnapshotPayload secondSnapshot = snapshot(second, "snap_" + second.participant(), second.selectors().getFirst().value());
        for (TaskSnapshotPayload published : List.of(snapshot(first, "snap_" + first.participant(), first.selectors().getFirst().value()), secondSnapshot)) {
            new PredictionEventStore(temp, project).append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                    identity.nodeId(), published.encode(), identity);
        }
        return secondSnapshot;
    }

    @Test
    void aParticipantIsNotAdmittedToReviewALaneOfItsOwnAuthorityLineage(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        new WorkGroupService(new PredictionEventStore(temp, project), identity).create(new WorkGroup(groupId,
                project,
                "Build modules",
                "Every module reviewed by a non-author",
                1,
                WorkGroup.Status.ACTIVE,
                ReviewPolicy.mandatoryIndependent()));
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        WorkIntent original = lane(project, groupId, "agt-original", WorkIntent.Role.PRODUCER, "slug.py", List.of());
        UUID continuedId = UUID.randomUUID();
        WorkIntent continued = new WorkIntent(continuedId, project, "agt-continuer", "codex", original.taskId(),
                original.goal(), original.acceptance(), original.baseCommit(),
                List.of(ResourceSelector.pathExact("slug_more.py")), 1, groupId, original.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED, WorkIntent.Role.PRODUCER, List.of());
        assertTrue(service.announce(original).acquired());
        assertTrue(service.announce(continued).acquired());
        TaskSnapshotPayload originalSnapshot = snapshot(original, "snap_original", "slug.py");
        TaskSnapshotPayload continuedSnapshot = snapshot(continued, "snap_continued", "slug_more.py");
        for (TaskSnapshotPayload published : List.of(originalSnapshot, continuedSnapshot)) {
            new PredictionEventStore(temp, project).append(UUID.randomUUID(),
                    PredictionEventType.TASK_SNAPSHOT_CREATED,
                    identity.nodeId(),
                    published.encode(),
                    identity);
        }

        IOException ownLineage = assertThrows(IOException.class,
                () -> service.request(original.participant(), continued.intentId(), CoordinationRequest.Kind.REVIEW,
                        "Review the continued slug lane", continuedSnapshot.snapshotId()));
        assertEquals("REVIEW_SELF_NOT_ALLOWED", ownLineage.getMessage());
        assertTrue(new PredictionEventStore(temp, project).collaborationProjection().requests().isEmpty());
    }

    @Test
    void mandatoryReviewRequiresExactCandidateAndIssuesSnapshotBoundSingleReviewerGrant(@TempDir Path temp)
            throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        new WorkGroupService(store, identity).create(new WorkGroup(groupId,
                project,
                "Build a board",
                "CRUD and persistence",
                1,
                WorkGroup.Status.ACTIVE,
                ReviewPolicy.mandatoryIndependent()));
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        WorkIntent owner = lane(project,
                groupId,
                "agt-policy-owner",
                WorkIntent.Role.PRODUCER,
                "src/board.py",
                List.of());
        WorkIntent reviewer = lane(project,
                groupId,
                "agt-policy-reviewer",
                WorkIntent.Role.REVIEWER,
                "docs/review.md",
                List.of(ResourceSelector.pathExact("src/board.py")));
        assertTrue(service.announce(owner).acquired());
        assertTrue(service.announce(reviewer).acquired());
        TaskSnapshotPayload snapshot = snapshot(owner, "snap_policy_candidate", "src/board.py");
        new PredictionEventStore(temp, project).append(UUID.randomUUID(),
                PredictionEventType.TASK_SNAPSHOT_CREATED,
                identity.nodeId(),
                snapshot.encode(),
                identity);

        IOException missingSnapshot = assertThrows(IOException.class,
                () -> service.request(reviewer.participant(),
                        owner.intentId(),
                        CoordinationRequest.Kind.REVIEW,
                        "Review candidate"));
        assertEquals("REVIEW_SNAPSHOT_REQUIRED", missingSnapshot.getMessage());

        CoordinationRequest request = service.request(reviewer.participant(),
                owner.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review candidate",
                snapshot.snapshotId());
        assertEquals(snapshot.snapshotId(), request.snapshotId());
        assertEquals(request.requestId(),
                service.request(reviewer.participant(),
                        owner.intentId(),
                        CoordinationRequest.Kind.REVIEW,
                        "Review candidate",
                        snapshot.snapshotId()).requestId());
        service.respond(owner.participant(),
                request.requestId(),
                CoordinationRequest.Status.ACCEPTED,
                "candidate selected");

        LaneGrant grant = new PredictionEventStore(temp, project).workGroupProjection().grants().getFirst();
        assertEquals(snapshot.snapshotId(), grant.snapshotId());
        assertEquals(reviewer.participant(), grant.targetParticipant());

        WorkIntent secondReviewer = lane(project,
                groupId,
                "agt-policy-reviewer-two",
                WorkIntent.Role.REVIEWER,
                "tests/review.py",
                List.of(ResourceSelector.pathExact("src/board.py")));
        assertTrue(service.announce(secondReviewer).acquired());
        IOException alreadyAssigned = assertThrows(IOException.class,
                () -> service.request(secondReviewer.participant(),
                        owner.intentId(),
                        CoordinationRequest.Kind.REVIEW,
                        "Review candidate",
                        snapshot.snapshotId()));
        assertEquals("REVIEW_CANDIDATE_ALREADY_ASSIGNED", alreadyAssigned.getMessage());
        assertEquals(1, new PredictionEventStore(temp, project).collaborationProjection().requests().size());
    }

    @Test
    void reviewRoleRoundTripsAndBindsProducerIntentToReviewerParticipant(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService service = new WorkIntentService(store, identity);
        WorkIntent producer = intent(project, "agt-producer", ResourceSelector.pathExact("src/task_tracker.py"));
        UUID reviewerId = UUID.randomUUID();
        WorkIntent reviewer = new WorkIntent(reviewerId,
                project,
                "agt-reviewer",
                "codex",
                UUID.randomUUID(),
                "Review task tracker",
                "Review the immutable producer snapshot",
                "base-commit",
                List.of(ResourceSelector.pathExact("tests/task_tracker_test.py")),
                1,
                producer.workGroupId(),
                WorkIntent.defaultAuthorityLineage(reviewerId),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/task_tracker.py")));

        assertTrue(service.announce(producer).acquired());
        assertTrue(service.announce(reviewer).acquired());

        WorkIntent replayedReviewer = new PredictionEventStore(temp, project).collaborationProjection()
                .intent(reviewerId).orElseThrow();
        assertEquals(WorkIntent.Role.REVIEWER, replayedReviewer.role());
        assertEquals(List.of(ResourceSelector.pathExact("src/task_tracker.py")),
                replayedReviewer.reviewTargetSelectors());

        CoordinationRequest request = service.request(reviewer.participant(),
                producer.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the producer snapshot");
        service.respond(producer.participant(),
                request.requestId(),
                CoordinationRequest.Status.ACCEPTED,
                "review admitted");
        LaneGrant grant = new PredictionEventStore(temp, project).workGroupProjection().grants().getFirst();
        assertEquals(producer.intentId(), grant.targetIntentId());
        assertEquals(reviewer.participant(), grant.targetParticipant());

        IOException wrongTarget = assertThrows(IOException.class,
                () -> service.request(reviewer.participant(),
                        reviewer.intentId(),
                        CoordinationRequest.Kind.REVIEW,
                        "A reviewer intent is not a producer target"));
        assertEquals("REVIEW_TARGET_NOT_PRODUCER", wrongTarget.getMessage());
    }

    @Test
    void reviewRequestReplayRemainsIdempotentAfterTargetLaneReleases(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService service = new WorkIntentService(store, identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(owner).acquired());

        CoordinationRequest request = service.request("agt-reviewer",
                owner.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the published snapshot");
        service.release(owner.intentId(), owner.participant());

        CoordinationRequest replayed = service.request("agt-reviewer",
                owner.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the published snapshot");

        assertEquals(request.requestId(), replayed.requestId());
        assertEquals(1, service.requests().size());
        assertThrows(IOException.class,
                () -> service.request("agt-other",
                        owner.intentId(),
                        CoordinationRequest.Kind.REVIEW,
                        "A new request cannot target a released lane"));
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
        assertEquals(2,
                service.activeIntents().stream().filter(i -> i.intentId().equals(owner.intentId())).findFirst()
                        .orElseThrow().version());
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
        CoordinationRequest request = service.request("agt-contender",
                owner.intentId(),
                CoordinationRequest.Kind.CONTRACT,
                "agree API");
        assertFalse(new PredictionEventStore(temp, project).collaborationProjection()
                .inboxAcknowledged(request.requestId()));
        assertThrows(IOException.class, () -> service.acknowledgeInbox("agt-contender", request.requestId()));
        service.acknowledgeInbox("agt-owner", request.requestId());
        service.acknowledgeInbox("agt-owner", request.requestId());
        assertTrue(new PredictionEventStore(temp, project).collaborationProjection()
                .inboxAcknowledged(request.requestId()));
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
        assertEquals(Participant.State.CANCELLED, projection.participants().getFirst().state());
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
        new WorkGroupService(new PredictionEventStore(temp, project), identity).issue(new LaneGrant(grantId,
                owner.workGroupId(),
                targetIntentId,
                "agt-target",
                owner.version(),
                true));
        WorkIntent target = new WorkIntent(targetIntentId,
                project,
                "agt-target",
                "codex",
                owner.taskId(),
                owner.goal(),
                owner.acceptance(),
                owner.baseCommit(),
                owner.selectors(),
                owner.version() + 1,
                owner.workGroupId(),
                owner.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                owner.role(),
                owner.reviewTargetSelectors(),
                owner.knownDependencies());
        service.continueFromRecovery(new LaneContinuation(grantId,
                owner.intentId(),
                target,
                "agt-owner",
                "agt-target",
                owner.version(),
                reference));

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertFalse(service.owns("agt-owner", ResourceSelector.pathExact("src/task_tracker.py")));
        assertTrue(service.owns("agt-target", ResourceSelector.pathExact("src/task_tracker.py")));
        assertFalse(replayed.workGroupProjection().grantAvailable(grantId));
        assertEquals(Participant.State.DETACHED,
                replayed.collaborationProjection().participantState("agt-owner").orElseThrow());
        assertEquals(owner.taskId(), replayed.collaborationProjection().intent(targetIntentId).orElseThrow().taskId());
        assertEquals(owner.authorityLineageId(),
                replayed.collaborationProjection().intent(targetIntentId).orElseThrow().authorityLineageId());
    }

    @Test
    void freshContinuationDerivesAndConsumesAOneShotSuccessorGrant(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService service = new WorkIntentService(store, identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(owner).acquired());
        service.suspend(owner.participant());
        String reference = temp.resolve("recovery").toAbsolutePath() + "#hash";
        service.holdRecovery(owner.participant(), reference);

        UUID targetIntentId = UUID.nameUUIDFromBytes(("target:" + owner.intentId()).getBytes(StandardCharsets.UTF_8));
        UUID grantId = UUID.nameUUIDFromBytes(("grant:" + owner.intentId()).getBytes(StandardCharsets.UTF_8));
        WorkIntent target = new WorkIntent(targetIntentId,
                project,
                "agt-successor",
                "codex",
                owner.taskId(),
                owner.goal(),
                owner.acceptance(),
                owner.baseCommit(),
                owner.selectors(),
                owner.version() + 1,
                owner.workGroupId(),
                owner.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                owner.role(),
                owner.reviewTargetSelectors(),
                owner.knownDependencies());
        LaneContinuation continuation = new LaneContinuation(grantId,
                owner.intentId(),
                target,
                owner.participant(),
                target.participant(),
                owner.version(),
                reference);

        assertFalse(store.workGroupProjection().grantAvailable(grantId));
        service.continueEligibleFromRecovery(continuation);
        assertDoesNotThrow(() -> service.continueEligibleFromRecovery(continuation));
        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertFalse(replayed.workGroupProjection().grantAvailable(grantId));
        assertEquals(0,
                replayed.events().stream().filter(event -> event.type() == PredictionEventType.LANE_GRANT_ISSUED)
                        .count());
        assertEquals(1,
                replayed.events().stream()
                        .filter(event -> event.type() == PredictionEventType.LANE_CONTINUATION_ACCEPTED).count());
        ReviewValidationPayload selfReview = new ReviewValidationPayload(grantId,
                owner.workGroupId(),
                target.intentId(),
                target.participant(),
                target.version(),
                owner.taskId(),
                "snap_self_review",
                "ACCEPTED",
                "",
                target.participant());
        IOException rejected = assertThrows(IOException.class,
                () -> replayed.append(UUID.randomUUID(),
                        PredictionEventType.REVIEW_VALIDATION_RECORDED,
                        identity.nodeId(),
                        selfReview.encode(),
                        identity));
        assertEquals("REVIEW_SELF_APPROVAL_FORBIDDEN", rejected.getMessage());
    }

    @Test
    void reviewerContinuationReplacesDeadReviewerGrantWithoutChangingProducerAuthority(@TempDir Path temp)
            throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        ResourceSelector source = ResourceSelector.pathExact("src/feature.py");
        WorkIntent producer = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-producer",
                "codex",
                taskId,
                "Implement feature",
                "Publish feature",
                "base",
                List.of(source),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(UUID.randomUUID()),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of());
        WorkIntent reviewer = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-reviewer",
                "claude",
                UUID.randomUUID(),
                "Review feature",
                "Review immutable candidate",
                "base",
                List.of(ResourceSelector.pathExact("tests/feature_test.py")),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(UUID.randomUUID()),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.REVIEWER,
                List.of(source),
                List.of());
        assertTrue(service.announce(producer).acquired());
        assertTrue(service.announce(reviewer).acquired());
        CoordinationRequest request = service.request(reviewer.participant(),
                producer.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the feature candidate");
        service.respond(producer.participant(), request.requestId(), CoordinationRequest.Status.ACCEPTED, "admitted");
        LaneGrant original = new PredictionEventStore(temp, project).workGroupProjection().grants().stream().findFirst()
                .orElseThrow();

        service.suspend(reviewer.participant());
        String reference = temp.resolve("review-recovery").toAbsolutePath() + "#hash";
        service.holdRecovery(reviewer.participant(), reference);
        WorkIntent successor = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-reviewer-successor",
                "codex",
                reviewer.taskId(),
                reviewer.goal(),
                reviewer.acceptance(),
                reviewer.baseCommit(),
                reviewer.selectors(),
                reviewer.version() + 1,
                reviewer.workGroupId(),
                reviewer.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                reviewer.role(),
                reviewer.reviewTargetSelectors(),
                reviewer.knownDependencies());
        service.continueEligibleFromRecovery(new LaneContinuation(UUID.nameUUIDFromBytes(("continuation:"
                + reviewer.intentId()).getBytes(StandardCharsets.UTF_8)),
                reviewer.intentId(),
                successor,
                reviewer.participant(),
                successor.participant(),
                reviewer.version(),
                reference));

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertTrue(replayed.workGroupProjection().grantSuperseded(original.grantId()));
        assertFalse(replayed.workGroupProjection().grantAvailable(original.grantId()));
        LaneGrant replacement = replayed.workGroupProjection().grants().stream()
                .filter(grant -> grant.targetParticipant().equals(successor.participant())).findFirst().orElseThrow();
        assertEquals(original.targetIntentId(), replacement.targetIntentId());
        assertEquals(original.claimEpoch(), replacement.claimEpoch());
        assertTrue(replayed.workGroupProjection().grantAvailable(replacement.grantId()));
        assertEquals(1,
                replayed.collaborationProjection().activeIntents().stream()
                        .filter(intent -> intent.role() == WorkIntent.Role.REVIEWER).count());
    }

    @Test
    void reviewerContinuationMigratesConsumedSnapshotBoundReviewGrant(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        new WorkGroupService(new PredictionEventStore(temp, project), identity).create(new WorkGroup(groupId,
                project,
                "Build a feature",
                "Review the exact candidate",
                1,
                WorkGroup.Status.ACTIVE,
                ReviewPolicy.mandatoryIndependent()));
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        ResourceSelector source = ResourceSelector.pathExact("src/feature.py");
        WorkIntent producer = lane(project,
                groupId,
                "agt-producer",
                WorkIntent.Role.PRODUCER,
                "src/feature.py",
                List.of());
        WorkIntent reviewer = lane(project,
                groupId,
                "agt-reviewer",
                WorkIntent.Role.REVIEWER,
                "tests/review.py",
                List.of(source));
        assertTrue(service.announce(producer).acquired());
        assertTrue(service.announce(reviewer).acquired());
        TaskSnapshotPayload snapshot = snapshot(producer, "snap_reviewer_recovery", "src/feature.py");
        new PredictionEventStore(temp, project).append(UUID.randomUUID(),
                PredictionEventType.TASK_SNAPSHOT_CREATED,
                identity.nodeId(),
                snapshot.encode(),
                identity);
        CoordinationRequest request = service.request(reviewer.participant(),
                producer.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review exact candidate",
                snapshot.snapshotId());
        service.respond(producer.participant(), request.requestId(), CoordinationRequest.Status.ACCEPTED, "admitted");

        LaneGrant original = new PredictionEventStore(temp, project).workGroupProjection().grants().stream()
                .filter(grant -> snapshot.snapshotId().equals(grant.snapshotId())).findFirst().orElseThrow();
        new WorkGroupService(new PredictionEventStore(temp, project), identity).consume(original.grantId(),
                reviewer.participant(),
                producer.intentId(),
                producer.version());

        service.suspend(reviewer.participant());
        String reference = temp.resolve("reviewer-recovery-snapshot").toAbsolutePath() + "#hash";
        service.holdRecovery(reviewer.participant(), reference);
        WorkIntent successor = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-reviewer-successor",
                "codex",
                reviewer.taskId(),
                reviewer.goal(),
                reviewer.acceptance(),
                reviewer.baseCommit(),
                reviewer.selectors(),
                reviewer.version() + 1,
                reviewer.workGroupId(),
                reviewer.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                reviewer.role(),
                reviewer.reviewTargetSelectors(),
                reviewer.knownDependencies());
        service.continueEligibleFromRecovery(new LaneContinuation(UUID.nameUUIDFromBytes(("continuation:"
                + reviewer.intentId()).getBytes(StandardCharsets.UTF_8)),
                reviewer.intentId(),
                successor,
                reviewer.participant(),
                successor.participant(),
                reviewer.version(),
                reference));

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertTrue(replayed.workGroupProjection().grantSuperseded(original.grantId()));
        assertFalse(replayed.workGroupProjection().grantAvailable(original.grantId()));
        LaneGrant replacement = replayed.workGroupProjection().grants().stream()
                .filter(grant -> snapshot.snapshotId().equals(grant.snapshotId()))
                .filter(grant -> grant.targetParticipant().equals(successor.participant())).findFirst().orElseThrow();
        assertEquals(producer.intentId(), replacement.targetIntentId());
        assertEquals(producer.version(), replacement.claimEpoch());
        assertTrue(replayed.workGroupProjection().grantAvailable(replacement.grantId()));
        assertDoesNotThrow(() -> new WorkGroupService(replayed, identity).consume(replacement.grantId(),
                successor.participant(),
                producer.intentId(),
                producer.version()));
    }

    @Test
    void reviewerContinuationCannotReuseActiveProducerParticipant(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        ResourceSelector producerSelector = ResourceSelector.pathExact("src/feature.py");
        WorkIntent producer = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-producer",
                "codex",
                taskId,
                "Implement feature",
                "Publish feature",
                "base",
                List.of(producerSelector),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(UUID.randomUUID()),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of());
        WorkIntent reviewer = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-reviewer",
                "claude",
                UUID.randomUUID(),
                "Review feature",
                "Review immutable candidate",
                "base",
                List.of(ResourceSelector.pathExact("tests/feature_test.py")),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(UUID.randomUUID()),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.REVIEWER,
                List.of(producerSelector),
                List.of());
        assertTrue(service.announce(producer).acquired());
        assertTrue(service.announce(reviewer).acquired());

        service.suspend(reviewer.participant());
        String reference = temp.resolve("reviewer-role-isolation").toAbsolutePath() + "#hash";
        service.holdRecovery(reviewer.participant(), reference);
        WorkIntent successor = new WorkIntent(UUID.randomUUID(),
                project,
                producer.participant(),
                "codex",
                reviewer.taskId(),
                reviewer.goal(),
                reviewer.acceptance(),
                reviewer.baseCommit(),
                reviewer.selectors(),
                reviewer.version() + 1,
                reviewer.workGroupId(),
                reviewer.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                reviewer.role(),
                reviewer.reviewTargetSelectors(),
                reviewer.knownDependencies());
        LaneContinuation continuation = new LaneContinuation(UUID.nameUUIDFromBytes(("continuation:"
                + reviewer.intentId()).getBytes(StandardCharsets.UTF_8)),
                reviewer.intentId(),
                successor,
                reviewer.participant(),
                successor.participant(),
                reviewer.version(),
                reference);

        IOException rejected = assertThrows(IOException.class,
                () -> service.continueEligibleFromRecovery(continuation));
        assertEquals("REVIEW_SELF_NOT_ALLOWED", rejected.getMessage());
    }

    @Test
    void reviewerContinuationRetargetsPendingReviewRequestToSuccessor(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        ResourceSelector source = ResourceSelector.pathExact("src/feature.py");
        WorkIntent producer = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-producer",
                "codex",
                taskId,
                "Implement feature",
                "Publish feature",
                "base",
                List.of(source),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(UUID.randomUUID()),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of());
        WorkIntent reviewer = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-reviewer",
                "claude",
                UUID.randomUUID(),
                "Review feature",
                "Review immutable candidate",
                "base",
                List.of(ResourceSelector.pathExact("tests/feature_test.py")),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(UUID.randomUUID()),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.REVIEWER,
                List.of(source),
                List.of());
        assertTrue(service.announce(producer).acquired());
        assertTrue(service.announce(reviewer).acquired());
        CoordinationRequest request = service.request(reviewer.participant(),
                producer.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the feature candidate");

        service.suspend(reviewer.participant());
        String reference = temp.resolve("reviewer-recovery-pending-review").toAbsolutePath() + "#hash";
        service.holdRecovery(reviewer.participant(), reference);
        WorkIntent successor = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-reviewer-successor",
                "codex",
                reviewer.taskId(),
                reviewer.goal(),
                reviewer.acceptance(),
                reviewer.baseCommit(),
                reviewer.selectors(),
                reviewer.version() + 1,
                reviewer.workGroupId(),
                reviewer.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                reviewer.role(),
                reviewer.reviewTargetSelectors(),
                reviewer.knownDependencies());
        service.continueEligibleFromRecovery(new LaneContinuation(UUID.nameUUIDFromBytes(("continuation:"
                + reviewer.intentId()).getBytes(StandardCharsets.UTF_8)),
                reviewer.intentId(),
                successor,
                reviewer.participant(),
                successor.participant(),
                reviewer.version(),
                reference));

        CoordinationRequest retargeted = new PredictionEventStore(temp, project).collaborationProjection().requests()
                .stream().filter(candidate -> candidate.requestId().equals(request.requestId())).findFirst()
                .orElseThrow();
        assertEquals(CoordinationRequest.Status.PENDING, retargeted.status());
        assertEquals(successor.participant(), retargeted.requester());
        assertEquals(producer.participant(), retargeted.target());
        assertEquals(producer.intentId(), retargeted.conflictingIntentId());

        service.respond(producer.participant(),
                request.requestId(),
                CoordinationRequest.Status.ACCEPTED,
                "admitted after reviewer continuation");
        LaneGrant successorGrant = new PredictionEventStore(temp, project).workGroupProjection().grants().stream()
                .filter(grant -> grant.targetParticipant().equals(successor.participant()))
                .filter(grant -> grant.targetIntentId().equals(producer.intentId())).findFirst().orElseThrow();
        assertEquals(producer.version(), successorGrant.claimEpoch());
        assertTrue(new PredictionEventStore(temp, project).workGroupProjection()
                .grantAvailable(successorGrant.grantId()));
    }

    @Test
    void producerContinuationRebindsOutstandingReviewerGrantToSuccessorEpoch(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        ResourceSelector source = ResourceSelector.pathExact("src/feature.py");
        WorkIntent producer = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-producer",
                "codex",
                taskId,
                "Implement feature",
                "Publish feature",
                "base",
                List.of(source),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(UUID.randomUUID()),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of());
        WorkIntent reviewer = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-reviewer",
                "claude",
                UUID.randomUUID(),
                "Review feature",
                "Review immutable candidate",
                "base",
                List.of(ResourceSelector.pathExact("tests/feature_test.py")),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(UUID.randomUUID()),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.REVIEWER,
                List.of(source),
                List.of());
        assertTrue(service.announce(producer).acquired());
        assertTrue(service.announce(reviewer).acquired());
        CoordinationRequest request = service.request(reviewer.participant(),
                producer.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the feature candidate");
        service.respond(producer.participant(), request.requestId(), CoordinationRequest.Status.ACCEPTED, "admitted");
        LaneGrant original = new PredictionEventStore(temp, project).workGroupProjection().grants().stream().findFirst()
                .orElseThrow();

        service.suspend(producer.participant());
        String reference = temp.resolve("producer-recovery").toAbsolutePath() + "#hash";
        service.holdRecovery(producer.participant(), reference);
        WorkIntent successor = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-producer-successor",
                "codex",
                producer.taskId(),
                producer.goal(),
                producer.acceptance(),
                producer.baseCommit(),
                producer.selectors(),
                producer.version() + 1,
                producer.workGroupId(),
                producer.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                producer.role(),
                producer.reviewTargetSelectors(),
                producer.knownDependencies());
        service.continueEligibleFromRecovery(new LaneContinuation(UUID.nameUUIDFromBytes(("continuation:"
                + producer.intentId()).getBytes(StandardCharsets.UTF_8)),
                producer.intentId(),
                successor,
                producer.participant(),
                successor.participant(),
                producer.version(),
                reference));

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertTrue(replayed.workGroupProjection().grantSuperseded(original.grantId()));
        assertFalse(replayed.workGroupProjection().grantAvailable(original.grantId()));
        LaneGrant replacement = replayed.workGroupProjection().grants().stream()
                .filter(grant -> grant.targetIntentId().equals(successor.intentId()))
                .filter(grant -> grant.targetParticipant().equals(reviewer.participant())).findFirst().orElseThrow();
        assertEquals(successor.version(), replacement.claimEpoch());
        assertTrue(replayed.workGroupProjection().grantAvailable(replacement.grantId()));
    }

    @Test
    void reviewerContinuationConsumesGrantMigratedFromProducerSuccessor(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        ResourceSelector source = ResourceSelector.pathExact("src/feature.py");
        WorkIntent producer = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-producer",
                "codex",
                taskId,
                "Implement feature",
                "Publish feature",
                "base",
                List.of(source),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(UUID.randomUUID()),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of());
        WorkIntent reviewer = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-reviewer",
                "claude",
                UUID.randomUUID(),
                "Review feature",
                "Review immutable candidate",
                "base",
                List.of(ResourceSelector.pathExact("tests/feature_test.py")),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(UUID.randomUUID()),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.REVIEWER,
                List.of(source),
                List.of());
        assertTrue(service.announce(producer).acquired());
        assertTrue(service.announce(reviewer).acquired());
        CoordinationRequest request = service.request(reviewer.participant(),
                producer.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the feature candidate");
        service.respond(producer.participant(), request.requestId(), CoordinationRequest.Status.ACCEPTED, "admitted");

        service.suspend(producer.participant());
        String producerReference = temp.resolve("producer-recovery-before-reviewer").toAbsolutePath() + "#hash";
        service.holdRecovery(producer.participant(), producerReference);
        WorkIntent producerSuccessor = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-producer-successor",
                "codex",
                producer.taskId(),
                producer.goal(),
                producer.acceptance(),
                producer.baseCommit(),
                producer.selectors(),
                producer.version() + 1,
                producer.workGroupId(),
                producer.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                producer.role(),
                producer.reviewTargetSelectors(),
                producer.knownDependencies());
        service.continueEligibleFromRecovery(new LaneContinuation(UUID.nameUUIDFromBytes(("continuation:"
                + producer.intentId()).getBytes(StandardCharsets.UTF_8)),
                producer.intentId(),
                producerSuccessor,
                producer.participant(),
                producerSuccessor.participant(),
                producer.version(),
                producerReference));

        service.suspend(reviewer.participant());
        String reviewerReference = temp.resolve("reviewer-recovery-after-producer").toAbsolutePath() + "#hash";
        service.holdRecovery(reviewer.participant(), reviewerReference);
        WorkIntent reviewerSuccessor = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-reviewer-successor",
                "codex",
                reviewer.taskId(),
                reviewer.goal(),
                reviewer.acceptance(),
                reviewer.baseCommit(),
                reviewer.selectors(),
                reviewer.version() + 1,
                reviewer.workGroupId(),
                reviewer.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                reviewer.role(),
                reviewer.reviewTargetSelectors(),
                reviewer.knownDependencies());
        service.continueEligibleFromRecovery(new LaneContinuation(UUID.nameUUIDFromBytes(("continuation:"
                + reviewer.intentId()).getBytes(StandardCharsets.UTF_8)),
                reviewer.intentId(),
                reviewerSuccessor,
                reviewer.participant(),
                reviewerSuccessor.participant(),
                reviewer.version(),
                reviewerReference));

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        LaneGrant migrated = replayed.workGroupProjection().grants().stream()
                .filter(grant -> grant.targetIntentId().equals(producerSuccessor.intentId()))
                .filter(grant -> grant.targetParticipant().equals(reviewerSuccessor.participant()))
                .filter(grant -> grant.claimEpoch() == producerSuccessor.version())
                .filter(grant -> replayed.workGroupProjection().grantAvailable(grant.grantId())).findFirst()
                .orElseThrow();
        assertDoesNotThrow(() -> new WorkGroupService(replayed, identity).consume(migrated.grantId(),
                reviewerSuccessor.participant(),
                producerSuccessor.intentId(),
                producerSuccessor.version()));
        assertTrue(new PredictionEventStore(temp, project).workGroupProjection().grantConsumed(migrated.grantId()));
    }

    @Test
    void producerContinuationRetargetsPendingReviewRequestToSuccessor(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        ResourceSelector source = ResourceSelector.pathExact("src/feature.py");
        WorkIntent producer = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-producer",
                "codex",
                taskId,
                "Implement feature",
                "Publish feature",
                "base",
                List.of(source),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(UUID.randomUUID()),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of());
        WorkIntent reviewer = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-reviewer",
                "claude",
                UUID.randomUUID(),
                "Review feature",
                "Review immutable candidate",
                "base",
                List.of(ResourceSelector.pathExact("tests/feature_test.py")),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(UUID.randomUUID()),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.REVIEWER,
                List.of(source),
                List.of());
        assertTrue(service.announce(producer).acquired());
        assertTrue(service.announce(reviewer).acquired());
        CoordinationRequest request = service.request(reviewer.participant(),
                producer.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review the feature candidate");

        service.suspend(producer.participant());
        String reference = temp.resolve("producer-recovery-pending-review").toAbsolutePath() + "#hash";
        service.holdRecovery(producer.participant(), reference);
        WorkIntent successor = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-producer-successor",
                "codex",
                producer.taskId(),
                producer.goal(),
                producer.acceptance(),
                producer.baseCommit(),
                producer.selectors(),
                producer.version() + 1,
                producer.workGroupId(),
                producer.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                producer.role(),
                producer.reviewTargetSelectors(),
                producer.knownDependencies());
        service.continueEligibleFromRecovery(new LaneContinuation(UUID.nameUUIDFromBytes(("continuation:"
                + producer.intentId()).getBytes(StandardCharsets.UTF_8)),
                producer.intentId(),
                successor,
                producer.participant(),
                successor.participant(),
                producer.version(),
                reference));

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        CoordinationRequest retargeted = replayed.collaborationProjection().requests().stream()
                .filter(candidate -> candidate.requestId().equals(request.requestId())).findFirst().orElseThrow();
        assertEquals(CoordinationRequest.Status.PENDING, retargeted.status());
        assertEquals(successor.participant(), retargeted.target());
        assertEquals(successor.intentId(), retargeted.conflictingIntentId());
        service.respond(successor.participant(), request.requestId(), CoordinationRequest.Status.ACCEPTED, "admitted");
        LaneGrant grant = new PredictionEventStore(temp, project).workGroupProjection().grants().stream()
                .filter(candidate -> candidate.targetIntentId().equals(successor.intentId()))
                .filter(candidate -> candidate.targetParticipant().equals(reviewer.participant())).findFirst()
                .orElseThrow();
        assertEquals(successor.intentId(), grant.targetIntentId());
        assertEquals(reviewer.participant(), grant.targetParticipant());
        assertEquals(successor.version(), grant.claimEpoch());
    }

    @Test
    void pendingSnapshotReviewCanBeAcceptedByProducerSuccessor(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        new WorkGroupService(new PredictionEventStore(temp, project), identity).create(new WorkGroup(groupId,
                project,
                "Build a feature",
                "Review the exact candidate",
                1,
                WorkGroup.Status.ACTIVE,
                ReviewPolicy.mandatoryIndependent()));
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        ResourceSelector source = ResourceSelector.pathExact("src/feature.py");
        WorkIntent producer = lane(project,
                groupId,
                "agt-producer",
                WorkIntent.Role.PRODUCER,
                "src/feature.py",
                List.of());
        WorkIntent reviewer = lane(project,
                groupId,
                "agt-reviewer",
                WorkIntent.Role.REVIEWER,
                "tests/review.py",
                List.of(source));
        assertTrue(service.announce(producer).acquired());
        assertTrue(service.announce(reviewer).acquired());
        TaskSnapshotPayload snapshot = snapshot(producer, "snap_pending_review_successor", "src/feature.py");
        new PredictionEventStore(temp, project).append(UUID.randomUUID(),
                PredictionEventType.TASK_SNAPSHOT_CREATED,
                identity.nodeId(),
                snapshot.encode(),
                identity);
        CoordinationRequest request = service.request(reviewer.participant(),
                producer.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review exact candidate",
                snapshot.snapshotId());

        service.suspend(producer.participant());
        String reference = temp.resolve("producer-recovery-pending-review").toAbsolutePath() + "#hash";
        service.holdRecovery(producer.participant(), reference);
        WorkIntent successor = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-producer-successor",
                "codex",
                producer.taskId(),
                producer.goal(),
                producer.acceptance(),
                producer.baseCommit(),
                producer.selectors(),
                producer.version() + 1,
                producer.workGroupId(),
                producer.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                producer.role(),
                producer.reviewTargetSelectors(),
                producer.knownDependencies());
        service.continueEligibleFromRecovery(new LaneContinuation(UUID.nameUUIDFromBytes(("continuation:"
                + producer.intentId()).getBytes(StandardCharsets.UTF_8)),
                producer.intentId(),
                successor,
                producer.participant(),
                successor.participant(),
                producer.version(),
                reference));

        CoordinationRequest retargeted = new PredictionEventStore(temp, project).collaborationProjection().requests()
                .stream().filter(candidate -> candidate.requestId().equals(request.requestId())).findFirst()
                .orElseThrow();
        assertEquals(successor.participant(), retargeted.target());
        assertEquals(successor.intentId(), retargeted.conflictingIntentId());
        assertEquals(snapshot.snapshotId(), retargeted.snapshotId());
        service.respond(successor.participant(), request.requestId(), CoordinationRequest.Status.ACCEPTED, "admitted");

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        LaneGrant grant = replayed.workGroupProjection().grants().stream()
                .filter(candidate -> snapshot.snapshotId().equals(candidate.snapshotId())).findFirst().orElseThrow();
        assertEquals(producer.intentId(), grant.targetIntentId());
        assertEquals(producer.version(), grant.claimEpoch());
        assertDoesNotThrow(() -> new WorkGroupService(replayed, identity).consume(grant.grantId(),
                reviewer.participant(),
                producer.intentId(),
                producer.version()));
    }

    @Test
    void producerContinuationPreservesExistingSnapshotBoundReviewGrant(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        new WorkGroupService(new PredictionEventStore(temp, project), identity).create(new WorkGroup(groupId,
                project,
                "Build a feature",
                "Review the exact candidate",
                1,
                WorkGroup.Status.ACTIVE,
                ReviewPolicy.mandatoryIndependent()));
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        ResourceSelector source = ResourceSelector.pathExact("src/feature.py");
        WorkIntent producer = lane(project,
                groupId,
                "agt-producer",
                WorkIntent.Role.PRODUCER,
                "src/feature.py",
                List.of());
        WorkIntent reviewer = lane(project,
                groupId,
                "agt-reviewer",
                WorkIntent.Role.REVIEWER,
                "tests/review.py",
                List.of(source));
        assertTrue(service.announce(producer).acquired());
        assertTrue(service.announce(reviewer).acquired());
        TaskSnapshotPayload snapshot = snapshot(producer, "snap_granted_review_successor", "src/feature.py");
        new PredictionEventStore(temp, project).append(UUID.randomUUID(),
                PredictionEventType.TASK_SNAPSHOT_CREATED,
                identity.nodeId(),
                snapshot.encode(),
                identity);
        CoordinationRequest request = service.request(reviewer.participant(),
                producer.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "Review exact candidate",
                snapshot.snapshotId());
        service.respond(producer.participant(), request.requestId(), CoordinationRequest.Status.ACCEPTED, "admitted");
        LaneGrant originalGrant = new PredictionEventStore(temp, project).workGroupProjection().grants().stream()
                .filter(candidate -> snapshot.snapshotId().equals(candidate.snapshotId())).findFirst().orElseThrow();

        service.suspend(producer.participant());
        String reference = temp.resolve("producer-recovery-granted-review").toAbsolutePath() + "#hash";
        service.holdRecovery(producer.participant(), reference);
        WorkIntent successor = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-producer-successor",
                "codex",
                producer.taskId(),
                producer.goal(),
                producer.acceptance(),
                producer.baseCommit(),
                producer.selectors(),
                producer.version() + 1,
                producer.workGroupId(),
                producer.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                producer.role(),
                producer.reviewTargetSelectors(),
                producer.knownDependencies());
        service.continueEligibleFromRecovery(new LaneContinuation(UUID.nameUUIDFromBytes(("continuation:"
                + producer.intentId()).getBytes(StandardCharsets.UTF_8)),
                producer.intentId(),
                successor,
                producer.participant(),
                successor.participant(),
                producer.version(),
                reference));

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        LaneGrant retainedGrant = replayed.workGroupProjection().grants().stream()
                .filter(candidate -> candidate.grantId().equals(originalGrant.grantId())).findFirst().orElseThrow();
        assertEquals(producer.intentId(), retainedGrant.targetIntentId());
        assertEquals(producer.version(), retainedGrant.claimEpoch());
        assertFalse(replayed.workGroupProjection().grantSuperseded(retainedGrant.grantId()));
        assertTrue(replayed.workGroupProjection().grantAvailable(retainedGrant.grantId()));
        assertDoesNotThrow(() -> new WorkGroupService(replayed, identity).consume(retainedGrant.grantId(),
                reviewer.participant(),
                producer.intentId(),
                producer.version()));
    }

    @Test
    void concurrentFreshSuccessorsHaveExactlyOneWinner(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService setup = new WorkIntentService(store, identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(setup.announce(owner).acquired());
        setup.suspend(owner.participant());
        String reference = temp.resolve("recovery").toAbsolutePath() + "#hash";
        setup.holdRecovery(owner.participant(), reference);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<String>> attempts = List.of(executor.submit(() -> attemptContinuation(temp,
                            project,
                            owner,
                            "agt-successor-b",
                            reference,
                            ready,
                            start)),
                    executor.submit(() -> attemptContinuation(temp,
                            project,
                            owner,
                            "agt-successor-c",
                            reference,
                            ready,
                            start)));
            ready.await();
            start.countDown();
            List<String> outcomes = attempts.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }).toList();
            assertEquals(1, outcomes.stream().filter("WON"::equals).count(), outcomes.toString());
            assertEquals(1,
                    new PredictionEventStore(temp, project).collaborationProjection().activeIntents().stream()
                            .filter(intent -> intent.taskId().equals(owner.taskId())).count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void repairLaneTransfersExactScopeAtomically(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkIntentService service = new WorkIntentService(store, identity);
        WorkIntent source = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(source).acquired());
        WorkIntent target = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-repair",
                "repair",
                source.taskId(),
                "Repair task tracker conflict",
                "Resolve and validate the conflict",
                "new-head",
                source.selectors(),
                source.version() + 1,
                source.workGroupId(),
                source.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED);

        service.createRepairLane(source.intentId(), "snap_repair", "new-head", target);
        service.createRepairLane(source.intentId(), "snap_repair", "new-head", target);

        PredictionEventStore replayed = new PredictionEventStore(temp, project);
        assertFalse(service.owns("agt-owner", ResourceSelector.pathExact("src/task_tracker.py")));
        assertTrue(service.owns("agt-repair", ResourceSelector.pathExact("src/task_tracker.py")));
        assertEquals(Participant.State.COMPLETED,
                replayed.collaborationProjection().participantState("agt-owner").orElseThrow());
        assertEquals(Participant.State.ACTIVE,
                replayed.collaborationProjection().participantState("agt-repair").orElseThrow());
        assertEquals(1,
                replayed.events().stream().filter(event -> event.type() == PredictionEventType.REPAIR_LANE_CREATED)
                        .count());
    }

    @Test
    void collaborationValidationMatchesApplyOnACopyAndLeavesTheProjectionUntouched(@TempDir Path temp)
            throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntentService service = new WorkIntentService(new PredictionEventStore(temp, project), identity);
        WorkIntent owner = intent(project, "agt-owner", ResourceSelector.pathExact("src/task_tracker.py"));
        assertTrue(service.announce(owner).acquired());
        CoordinationRequest request = service.request("agt-contender",
                owner.intentId(),
                CoordinationRequest.Kind.CONTRACT,
                "Agree on TaskTracker API v1");
        service.respond("agt-owner", request.requestId(), CoordinationRequest.Status.ACCEPTED, "API accepted");

        ProjectionValidation.assertValidateMatchesApplyOnCopy(new PredictionEventStore(temp, project).events());
    }
}
