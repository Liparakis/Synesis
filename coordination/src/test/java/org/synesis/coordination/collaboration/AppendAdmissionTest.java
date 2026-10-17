package org.synesis.coordination.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.NoChangeCompletion;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.ImplementationEventPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CompletionPreparedPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Every authority rule is checked by the projections, not only by the service that normally asks. Each test writes
 * the forbidden entry straight to the store, as a member computer's proposal or an append made without the project
 * lock would, and expects the refusal the service gives.
 */
final class AppendAdmissionTest {

    private NodeIdentity identity;

    @BeforeEach
    void identity() throws Exception {
        identity = NodeIdentity.generate();
    }

    private static WorkIntent lane(UUID project, UUID group, String participant, WorkIntent.Role role, String path,
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
                List.of(ResourceSelector.pathExact(path)),
                1,
                group,
                WorkIntent.defaultAuthorityLineage(intentId),
                WorkIntent.Status.ANNOUNCED,
                role,
                reviewTargets);
    }

    private static WorkIntent producer(UUID project, UUID group, String participant, String path) {
        return lane(project, group, participant, WorkIntent.Role.PRODUCER, path, List.of());
    }

    private static TaskSnapshotPayload snapshotOf(WorkIntent lane, long epoch, String snapshotId) {
        return new TaskSnapshotPayload(lane.taskId(),
                snapshotId,
                "node",
                "supervisor",
                "worker",
                "session-" + lane.participant(),
                "base",
                "commit-" + snapshotId,
                List.of("src/a.py"),
                List.of(),
                "work",
                new SnapshotProvenance(lane.workGroupId(),
                        lane.intentId(),
                        lane.authorityLineageId(),
                        lane.participant(),
                        "session-" + lane.participant(),
                        epoch,
                        List.of(),
                        List.of(),
                        List.of("PATH:src/a.py"),
                        "refs/synesis/snapshots/" + snapshotId,
                        "integrity",
                        "artifacts"),
                true);
    }

    private void appendRaw(Path temp, UUID project, UUID id, PredictionEventType type, byte[] payload)
            throws Exception {
        new PredictionEventStore(temp, project).append(id, type, identity.nodeId(), payload, identity);
    }

    private String refusal(Path temp, UUID project, UUID id, PredictionEventType type, byte[] payload) {
        return assertThrows(IOException.class, () -> appendRaw(temp, project, id, type, payload)).getMessage();
    }

    private WorkIntentService service(Path temp, UUID project) throws Exception {
        return new WorkIntentService(new PredictionEventStore(temp, project), identity);
    }

    @Test
    void aLaneCannotBeAnnouncedIntoAClosedWorkGroup(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        WorkIntent first = producer(project, group, "agt-a", "src/a.py");
        assertTrue(service(temp, project).announce(first).acquired());
        new WorkGroupService(new PredictionEventStore(temp, project), identity).close(group,
                WorkGroup.Status.COMPLETED,
                1);

        WorkIntent late = producer(project, group, "agt-b", "src/b.py");
        assertEquals("WORK_GROUP_NOT_ACTIVE",
                refusal(temp, project, late.intentId(), PredictionEventType.WORK_INTENT_ANNOUNCED,
                        CollaborationCodec.encodeIntent(late)));
    }

    @Test
    void aReviewerCannotBeAnnouncedWithNoProducerToReview(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        WorkIntent reviewer = lane(project,
                UUID.randomUUID(),
                "agt-reviewer",
                WorkIntent.Role.REVIEWER,
                "tests/a_test.py",
                List.of());
        assertEquals("REVIEWER_REQUIRES_PRODUCER",
                refusal(temp, project, reviewer.intentId(), PredictionEventType.WORK_INTENT_ANNOUNCED,
                        CollaborationCodec.encodeIntent(reviewer)));
    }

    @Test
    void onlyTheOwnerOffersAHandoffToAnActiveParticipant(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        WorkIntent owner = producer(project, group, "agt-owner", "src/a.py");
        WorkIntent other = producer(project, group, "agt-other", "src/b.py");
        WorkIntentService service = service(temp, project);
        assertTrue(service.announce(owner).acquired());
        assertTrue(service.announce(other).acquired());

        CoordinationRequest notTheOwner = new CoordinationRequest(UUID.randomUUID(),
                project,
                "agt-other",
                "agt-owner",
                owner.intentId(),
                CoordinationRequest.Kind.HANDOFF,
                "take it",
                CoordinationRequest.Status.PENDING);
        assertEquals("INTENT_OWNER_MISMATCH",
                refusal(temp, project, notTheOwner.requestId(), PredictionEventType.COORDINATION_REQUESTED,
                        CollaborationCodec.encodeRequest(notTheOwner)));

        CoordinationRequest toNobody = new CoordinationRequest(UUID.randomUUID(),
                project,
                "agt-owner",
                "agt-nobody",
                owner.intentId(),
                CoordinationRequest.Kind.HANDOFF,
                "take it",
                CoordinationRequest.Status.PENDING);
        assertEquals("HANDOFF_TARGET_NOT_ACTIVE",
                refusal(temp, project, toNobody.requestId(), PredictionEventType.COORDINATION_REQUESTED,
                        CollaborationCodec.encodeRequest(toNobody)));
    }

    @Test
    void aParticipantCannotRequestAReviewOfItsOwnLane(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        WorkIntent owner = producer(project, UUID.randomUUID(), "agt-owner", "src/a.py");
        assertTrue(service(temp, project).announce(owner).acquired());

        CoordinationRequest ownWork = new CoordinationRequest(UUID.randomUUID(),
                project,
                "agt-owner",
                "agt-peer",
                owner.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "review me",
                CoordinationRequest.Status.PENDING);
        assertEquals("REVIEW_SELF_NOT_ALLOWED",
                refusal(temp, project, ownWork.requestId(), PredictionEventType.COORDINATION_REQUESTED,
                        CollaborationCodec.encodeRequest(ownWork)));
    }

    @Test
    void aReviewIsAcceptedOnlyForARequesterThatHoldsAReviewerLane(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        WorkIntent owner = producer(project, group, "agt-owner", "src/a.py");
        WorkIntent peer = producer(project, group, "agt-peer", "src/b.py");
        WorkIntentService service = service(temp, project);
        assertTrue(service.announce(owner).acquired());
        assertTrue(service.announce(peer).acquired());
        CoordinationRequest request = new CoordinationRequest(UUID.randomUUID(),
                project,
                "agt-peer",
                "agt-owner",
                owner.intentId(),
                CoordinationRequest.Kind.REVIEW,
                "review me",
                CoordinationRequest.Status.PENDING);
        appendRaw(temp, project, request.requestId(), PredictionEventType.COORDINATION_REQUESTED,
                CollaborationCodec.encodeRequest(request));

        assertEquals("REVIEWER_INTENT_REQUIRED",
                refusal(temp, project, request.requestId(), PredictionEventType.COORDINATION_RESPONDED,
                        CollaborationCodec.encodeResponse(request.identity(),
                                CoordinationRequest.Status.ACCEPTED,
                                "ok")));
    }

    private record Held(WorkIntent owner, WorkIntent target, String reference) {

    }

    private Held heldLane(Path temp, UUID project, UUID group) throws Exception {
        WorkIntent owner = producer(project, group, "agt-owner", "src/a.py");
        WorkIntentService service = service(temp, project);
        assertTrue(service.announce(owner).acquired());
        service.suspend(owner.participant());
        String reference = temp.resolve("recovery").toAbsolutePath() + "#hash";
        service.holdRecovery(owner.participant(), reference);
        WorkIntent target = new WorkIntent(UUID.randomUUID(),
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
        return new Held(owner, target, reference);
    }

    private static byte[] continuation(Held held, String reference) {
        return CollaborationCodec.encodeContinuation(new LaneContinuation(UUID.randomUUID(),
                held.owner().intentId(),
                held.target(),
                held.owner().participant(),
                held.target().participant(),
                held.owner().version(),
                reference));
    }

    @Test
    void aContinuationMustNameTheSnapshotTheRecoveryHoldsAndAnOpenWorkGroup(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        Held held = heldLane(temp, project, group);

        assertEquals("RECOVERY_SNAPSHOT_MISMATCH",
                refusal(temp, project, held.target().intentId(), PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                        continuation(held, held.reference() + "-other")));

        new WorkGroupService(new PredictionEventStore(temp, project), identity).close(group,
                WorkGroup.Status.CANCELLED,
                1);
        assertEquals("WORK_GROUP_NOT_ACTIVE",
                refusal(temp, project, held.target().intentId(), PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                        continuation(held, held.reference())));
    }

    @Test
    void aSuccessorCannotTakeTheOtherSideOfAReview(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        Held held = heldLane(temp, project, group);
        // The successor already holds the reviewer lane of the same group.
        WorkIntent reviewer = lane(project,
                group,
                held.target().participant(),
                WorkIntent.Role.REVIEWER,
                "tests/a_test.py",
                List.of());
        appendRaw(temp, project, reviewer.intentId(), PredictionEventType.WORK_INTENT_ANNOUNCED,
                CollaborationCodec.encodeIntent(reviewer));

        assertEquals("REVIEW_SELF_NOT_ALLOWED",
                refusal(temp, project, held.target().intentId(), PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                        continuation(held, held.reference())));
    }

    @Test
    void aSuccessorCannotTakeOverALaneItHoldsAnUndecidedReviewGrantOn(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        Held held = heldLane(temp, project, group);
        // A producer may review without a reviewer lane, so the grant is all that marks it a reviewer.
        LaneGrant review = new LaneGrant(UUID.randomUUID(), group, held.owner().intentId(), held.target().participant(), 1, true);
        new WorkGroupService(new PredictionEventStore(temp, project), identity).issue(review);

        assertEquals("REVIEW_SELF_NOT_ALLOWED",
                refusal(temp, project, held.target().intentId(), PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                        continuation(held, held.reference())));
        assertThrows(IOException.class, () -> service(temp, project).continueEligibleFromRecovery(new LaneContinuation(UUID.randomUUID(),
                held.owner().intentId(), held.target(), held.owner().participant(), held.target().participant(),
                held.owner().version(), held.reference())));
    }

    @Test
    void aGrantWhoseLaneHasASuccessorCannotBeConsumed(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        Held held = heldLane(temp, project, group);
        service(temp, project).continueEligibleFromRecovery(new LaneContinuation(UUID.randomUUID(),
                held.owner().intentId(),
                held.target(),
                held.owner().participant(),
                held.target().participant(),
                held.owner().version(),
                held.reference()));
        LaneGrant stale = new LaneGrant(UUID.randomUUID(), group, held.owner().intentId(), "agt-reviewer", 1, true);
        new WorkGroupService(new PredictionEventStore(temp, project), identity).issue(stale);

        assertEquals("LANE_GRANT_TARGET_STALE",
                refusal(temp, project, stale.grantId(), PredictionEventType.LANE_GRANT_CONSUMED,
                        CollaborationCodec.encodeLaneGrant(stale)));
    }

    @Test
    void aClosedWorkGroupCannotBeReopened(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        WorkGroupService groups = new WorkGroupService(new PredictionEventStore(temp, project), identity);
        groups.create(new WorkGroup(group, project, "goal", "acceptance", 1, WorkGroup.Status.ACTIVE));
        groups.close(group, WorkGroup.Status.COMPLETED, 1);

        WorkGroup reopened = new WorkGroup(group, project, "goal", "acceptance", 3, WorkGroup.Status.ACTIVE);
        assertEquals("GROUP_STATUS_INVALID",
                refusal(temp, project, group, PredictionEventType.WORK_GROUP_STATUS_CHANGED,
                        CollaborationCodec.encodeWorkGroup(reopened)));
    }

    private NoChangeCompletion noChange(Path temp, UUID project, WorkIntent lane, long groupVersionOffset)
            throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, project);
        WorkGroup group = store.workGroupProjection().group(lane.workGroupId()).orElseThrow();
        return new NoChangeCompletion(lane.intentId(),
                lane.workGroupId(),
                lane.participant(),
                lane.provider(),
                "binding-" + lane.participant(),
                lane.authorityLineageId(),
                lane.version(),
                group.version() + groupVersionOffset,
                store.headSequence(),
                lane.baseCommit(),
                "Nothing to change");
    }

    @Test
    void aLaneCannotFinishWithoutChangeOnAStaleGroupVersionOrAReviewObligation(@TempDir Path temp)
            throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        WorkIntent lane = producer(project, group, "agt-a", "src/a.py");
        WorkIntent other = producer(project, group, "agt-b", "src/b.py");
        WorkIntentService service = service(temp, project);
        assertTrue(service.announce(lane).acquired());
        assertTrue(service.announce(other).acquired());

        NoChangeCompletion staleVersion = noChange(temp, project, lane, 5);
        assertEquals("WORK_GROUP_VERSION_STALE",
                refusal(temp, project, lane.intentId(), PredictionEventType.WORK_INTENT_RELEASED,
                        CollaborationCodec.encodeNoChangeCompletion(staleVersion)));

        // Another lane asked this one for something and is waiting for the answer.
        service.request("agt-b", lane.intentId(), CoordinationRequest.Kind.SCOPE_REVISION, "please widen");
        NoChangeCompletion owing = noChange(temp, project, lane, 0);
        assertEquals("NO_CHANGE_REVIEW_OBLIGATION",
                refusal(temp, project, lane.intentId(), PredictionEventType.WORK_INTENT_RELEASED,
                        CollaborationCodec.encodeNoChangeCompletion(owing)));
    }

    @Test
    void aSnapshotOrPreparationNeedsTheLaneToBeStillTheCallers(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        WorkIntent lane = producer(project, group, "agt-a", "src/a.py");
        WorkIntentService service = service(temp, project);
        assertTrue(service.announce(lane).acquired());

        assertEquals("SNAPSHOT_COMPLETION_EVIDENCE_STALE",
                refusal(temp, project, UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                        snapshotOf(lane, 2, "snap_wrong_epoch").encode()));

        // The lane is cancelled by someone else while its owner is still finishing.
        service.cancel(lane.participant());
        assertEquals("SNAPSHOT_COMPLETION_EVIDENCE_STALE",
                refusal(temp, project, UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                        snapshotOf(lane, 1, "snap_cancelled").encode()));
        CompletionPreparedPayload prepared = new CompletionPreparedPayload(lane.taskId(),
                "cmp_1",
                lane.intentId(),
                1,
                "base",
                "refs/synesis/prepared/x",
                "tree",
                List.of("src/a.py"));
        assertEquals("SNAPSHOT_COMPLETION_EVIDENCE_STALE",
                refusal(temp, project, UUID.randomUUID(), PredictionEventType.COMPLETION_PREPARED,
                        prepared.encode()));
    }

    @Test
    void nobodyApprovesItsOwnWork(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        WorkIntent lane = producer(project, group, "agt-owner", "src/a.py");
        assertTrue(service(temp, project).announce(lane).acquired());
        appendRaw(temp, project, UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                snapshotOf(lane, 1, "snap_own").encode());

        // A grant addressed to the producer itself, bound to its own snapshot, is never consumable.
        LaneGrant bound = new LaneGrant(UUID.randomUUID(), group, lane.intentId(), "agt-owner", 1, true, "snap_own");
        new WorkGroupService(new PredictionEventStore(temp, project), identity).issue(bound);
        assertEquals("REVIEW_SNAPSHOT_MISMATCH",
                refusal(temp, project, bound.grantId(), PredictionEventType.LANE_GRANT_CONSUMED,
                        CollaborationCodec.encodeLaneGrant(bound)));

        // And one that is consumed anyway cannot carry a decision by the producer.
        LaneGrant plain = new LaneGrant(UUID.randomUUID(), group, lane.intentId(), "agt-owner", 1, true);
        new WorkGroupService(new PredictionEventStore(temp, project), identity).issue(plain);
        appendRaw(temp, project, plain.grantId(), PredictionEventType.LANE_GRANT_CONSUMED,
                CollaborationCodec.encodeLaneGrant(plain));
        ReviewValidationPayload selfApproval = new ReviewValidationPayload(plain.grantId(),
                group,
                lane.intentId(),
                "agt-owner",
                1,
                lane.taskId(),
                "snap_own",
                "ACCEPTED",
                null,
                "agt-owner");
        assertEquals("REVIEW_SELF_APPROVAL_FORBIDDEN",
                refusal(temp, project, plain.grantId(), PredictionEventType.REVIEW_VALIDATION_RECORDED,
                        selfApproval.encode()));
    }

    @Test
    void aCapabilityIsPublishedOnlyByALaneThatIsStillActive(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        WorkIntent owner = producer(project, UUID.randomUUID(), "agt-owner", "src/a.py");
        WorkIntentService service = service(temp, project);
        assertTrue(service.announce(owner).acquired());
        CapabilityRequestHandle handle = new CapabilityRequestHandle("req_01234567890123456789012345678901");
        CapabilityContract contract = new CapabilityContract("lookup", "id: UUID", "value: string",
                List.of("stable"), "not found", "none", "pure", "v1", "local", "serialized",
                List.of("lookup returns value"));
        for (CapabilityLifecycleState state : List.of(CapabilityLifecycleState.AWAITING_OWNER,
                CapabilityLifecycleState.ACCEPTED)) {
            appendRaw(temp, project, UUID.randomUUID(),
                    state == CapabilityLifecycleState.ACCEPTED ? PredictionEventType.CAPABILITY_REQUEST_ACCEPTED
                            : PredictionEventType.CAPABILITY_REQUEST_CREATED,
                    new CapabilityRequestPayload(handle, "catalog.lookup", "requester-node", "requester-supervisor",
                            "requester-worker", "owner-node", "owner-supervisor", "owner-worker",
                            owner.authorityLineageId(), contract, state, null,
                            state == CapabilityLifecycleState.ACCEPTED ? new PredictionEventStore(temp, project).headSequence() : 0).encode());
        }
        service.release(owner.intentId(), owner.participant());

        ImplementationEventPayload publication = new ImplementationEventPayload(handle,
                owner.authorityLineageId(),
                1,
                "base",
                "commit-one",
                List.of("src/a.py"),
                "published",
                "",
                "",
                List.of(),
                "");
        assertEquals("CAPABILITY_PUBLISHER_STALE",
                refusal(temp, project, UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED,
                        publication.encode()));
    }

    @Test
    void anIntegratedTaskCannotBeCancelled(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        WorkIntent lane = producer(project, UUID.randomUUID(), "agt-owner", "src/a.py");
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(taskId,
                "snap_done",
                "node",
                "supervisor",
                "worker",
                "session",
                "base",
                "commit",
                List.of("src/a.py"),
                List.of(),
                "done",
                snapshotOf(lane, 1, "snap_done").provenance(),
                false);
        appendRaw(temp, project, taskId, PredictionEventType.TASK_SNAPSHOT_CREATED, snapshot.encode());
        appendRaw(temp, project, taskId, PredictionEventType.TASK_INTEGRATED, snapshot.encode());

        assertEquals("task_not_cancellable",
                refusal(temp, project, taskId, PredictionEventType.TASK_CANCELLATION_REQUESTED,
                        "give up".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void anAppendIsValidatedAgainstTheLogItIsPlacedOnNotTheOneItsStoreOpenedWith(@TempDir Path temp)
            throws Exception {
        UUID project = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        PredictionEventStore stale = new PredictionEventStore(temp, project);
        PredictionEventStore other = new PredictionEventStore(temp, project);
        WorkIntent first = producer(project, group, "agt-first", "src/shared.py");
        other.append(first.intentId(),
                PredictionEventType.WORK_INTENT_ANNOUNCED,
                identity.nodeId(),
                CollaborationCodec.encodeIntent(first),
                identity);

        WorkIntent overlapping = producer(project, group, "agt-second", "src/shared.py");
        IOException refused = assertThrows(IOException.class,
                () -> stale.append(overlapping.intentId(),
                        PredictionEventType.WORK_INTENT_ANNOUNCED,
                        identity.nodeId(),
                        CollaborationCodec.encodeIntent(overlapping),
                        identity));
        assertEquals("OVERLAPPING_CLAIM", refused.getMessage());
        // The stale instance now knows the entry it was behind on.
        assertEquals(other.headSequence(), stale.headSequence());
        assertEquals(1,
                stale.collaborationProjection().activeIntents().stream()
                        .filter(intent -> intent.intentId().equals(first.intentId())).count());
    }
}
