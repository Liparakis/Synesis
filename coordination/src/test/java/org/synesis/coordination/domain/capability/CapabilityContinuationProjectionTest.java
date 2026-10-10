package org.synesis.coordination.domain.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.ImplementationEventPayload;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.IdentityBootstrap;

/** Verifies capability authority follows continuation without losing accepted implementation state. */
final class CapabilityContinuationProjectionTest {

    @Test
    void requesterContinuationRestagesInspectionAndPreservesTheImplementation(@TempDir Path temp) throws Exception {
        UUID projectId = UUID.randomUUID();
        var signer = new IdentityBootstrap(temp.resolve("link")).loadOrCreate().identity();
        CapabilityRequestHandle handle = new CapabilityRequestHandle("req_01234567890123456789012345678901");
        UUID lineage = UUID.randomUUID();
        CapabilityContract contract = new CapabilityContract("lookup", "id: UUID", "value: string", List.of("stable"),
                "not found", "none", "pure", "v1", "local", "serialized", List.of("lookup returns value"));
        CapabilityRequestProjection projection = new CapabilityRequestProjection();
        apply(projection, projectId, signer, 1, PredictionEventType.CAPABILITY_REQUEST_CREATED,
                new CapabilityRequestPayload(handle, "catalog.lookup", "requester-node", "requester-supervisor", "requester-worker",
                        "owner-node", "owner-supervisor", "owner-worker", lineage, contract, CapabilityLifecycleState.AWAITING_OWNER, null, 0).encode());
        apply(projection, projectId, signer, 2, PredictionEventType.CAPABILITY_REQUEST_ACCEPTED,
                new CapabilityRequestPayload(handle, "catalog.lookup", "requester-node", "requester-supervisor", "requester-worker",
                        "owner-node", "owner-supervisor", "owner-worker", lineage, contract, CapabilityLifecycleState.ACCEPTED, null, 1).encode());
        apply(projection, projectId, signer, 3, PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED,
                new ImplementationEventPayload(handle, lineage, 1, "base", "commit-one", List.of("task_store.py"), "published", "", "", List.of(), "").encode());
        apply(projection, projectId, signer, 4, PredictionEventType.CAPABILITY_VALIDATION_STARTED,
                new ImplementationEventPayload(handle, lineage, 1, "base", "commit-one", List.of("task_store.py"), "", "", "", List.of(),
                        temp.resolve("old-validation-worktree").toString(), "old-requester-session").encode());

        CapabilityActorScope source = new CapabilityActorScope("requester-node", "requester-supervisor", "requester-worker", "old-requester-session");
        CapabilityActorScope successor = new CapabilityActorScope("successor-node", "successor-supervisor", "successor-worker", "new-requester-session");
        var target = new WorkIntent(UUID.randomUUID(), projectId, "agt_successor", "codex", UUID.randomUUID(), "task", "acceptance", "base",
                List.of(ResourceSelector.pathExact("task_store.py")), 2, UUID.randomUUID(), UUID.randomUUID(), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of(), List.of());
        LaneContinuation continuation = new LaneContinuation(UUID.randomUUID(), UUID.randomUUID(), target, "agt_predecessor", "agt_successor", 1,
                "snapshot#hash", source, successor);
        apply(projection, projectId, signer, 5, PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                CollaborationCodec.encodeContinuation(continuation));

        CapabilityRequestRecord transferred = projection.findByHandle(handle).orElseThrow();
        assertEquals("successor-node", transferred.requesterNodeId());
        assertEquals("successor-supervisor", transferred.requesterSupervisorId());
        assertEquals("successor-worker", transferred.requesterWorkerId());
        assertEquals(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE, transferred.state());
        assertEquals(1, transferred.contractRevision());
        assertEquals("commit-one", projection.findLatestImplementation(handle).orElseThrow().commitSha());
        assertTrue(projection.findValidationContext(handle).isEmpty());
    }

    @Test
    void ownerContinuationRetargetsPendingResponseWithoutRevisingTheContract(@TempDir Path temp) throws Exception {
        UUID projectId = UUID.randomUUID();
        var signer = new IdentityBootstrap(temp.resolve("link")).loadOrCreate().identity();
        CapabilityRequestHandle handle = new CapabilityRequestHandle("req_11234567890123456789012345678901");
        UUID lineage = UUID.randomUUID();
        CapabilityContract contract = new CapabilityContract("lookup", "id: UUID", "value: string", List.of("stable"),
                "not found", "none", "pure", "v1", "local", "serialized", List.of("lookup returns value"));
        CapabilityRequestProjection projection = new CapabilityRequestProjection();
        apply(projection, projectId, signer, 1, PredictionEventType.CAPABILITY_REQUEST_CREATED,
                new CapabilityRequestPayload(handle, "catalog.lookup", "requester-node", "requester-supervisor", "requester-worker",
                        "owner-node", "owner-supervisor", "owner-worker", lineage, contract, CapabilityLifecycleState.AWAITING_OWNER, null, 0).encode());
        CapabilityActorScope source = new CapabilityActorScope("owner-node", "owner-supervisor", "owner-worker", "old-owner-session");
        CapabilityActorScope successor = new CapabilityActorScope("successor-node", "successor-supervisor", "successor-worker", "new-owner-session");
        var target = new WorkIntent(UUID.randomUUID(), projectId, "agt_owner_successor", "codex", UUID.randomUUID(), "task", "acceptance", "base",
                List.of(ResourceSelector.pathExact("task_store.py")), 2, UUID.randomUUID(), UUID.randomUUID(), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of(), List.of());
        LaneContinuation continuation = new LaneContinuation(UUID.randomUUID(), UUID.randomUUID(), target, "agt_owner_predecessor", "agt_owner_successor", 1,
                "snapshot#hash", source, successor);
        apply(projection, projectId, signer, 2, PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                CollaborationCodec.encodeContinuation(continuation));

        CapabilityRequestRecord transferred = projection.findByHandle(handle).orElseThrow();
        assertEquals("successor-node", transferred.ownerNodeId());
        assertEquals("successor-supervisor", transferred.ownerSupervisorId());
        assertEquals("successor-worker", transferred.ownerWorkerId());
        assertEquals(CapabilityLifecycleState.AWAITING_OWNER, transferred.state());
        assertEquals(1, transferred.contractRevision());
        assertEquals(contract, transferred.contract());
    }

    @Test
    void validatedRequestFollowsEitherLaneToItsSuccessor(@TempDir Path temp) throws Exception {
        UUID projectId = UUID.randomUUID();
        var signer = new IdentityBootstrap(temp.resolve("link")).loadOrCreate().identity();
        CapabilityRequestHandle handle = new CapabilityRequestHandle("req_21234567890123456789012345678901");
        UUID lineage = UUID.randomUUID();
        CapabilityContract contract = new CapabilityContract("lookup", "id: UUID", "value: string", List.of("stable"),
                "not found", "none", "pure", "v1", "local", "serialized", List.of("lookup returns value"));
        CapabilityRequestProjection projection = new CapabilityRequestProjection();
        apply(projection, projectId, signer, 1, PredictionEventType.CAPABILITY_REQUEST_CREATED,
                new CapabilityRequestPayload(handle, "catalog.lookup", "requester-node", "requester-supervisor", "requester-worker",
                        "owner-node", "owner-supervisor", "owner-worker", lineage, contract, CapabilityLifecycleState.AWAITING_OWNER, null, 0).encode());
        apply(projection, projectId, signer, 2, PredictionEventType.CAPABILITY_REQUEST_ACCEPTED,
                new CapabilityRequestPayload(handle, "catalog.lookup", "requester-node", "requester-supervisor", "requester-worker",
                        "owner-node", "owner-supervisor", "owner-worker", lineage, contract, CapabilityLifecycleState.ACCEPTED, null, 1).encode());
        byte[] implementation = new ImplementationEventPayload(handle, lineage, 1, "base", "commit-one", List.of("task_store.py"), "published", "", "", List.of(), "").encode();
        apply(projection, projectId, signer, 3, PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED, implementation);
        apply(projection, projectId, signer, 4, PredictionEventType.CAPABILITY_VALIDATION_STARTED,
                new ImplementationEventPayload(handle, lineage, 1, "base", "commit-one", List.of("task_store.py"), "", "", "", List.of(),
                        temp.resolve("validation-worktree").toString(), "requester-session").encode());
        apply(projection, projectId, signer, 5, PredictionEventType.CAPABILITY_IMPLEMENTATION_VALIDATED, implementation);
        assertEquals(CapabilityLifecycleState.VALIDATED, projection.findByHandle(handle).orElseThrow().state());

        // The owner's process is lost after validation, before its lane published the snapshot
        // integration is waiting for. Its successor must be the owner, or nobody can finish.
        var ownerTarget = new WorkIntent(UUID.randomUUID(), projectId, "agt_owner_successor", "codex", UUID.randomUUID(), "task", "acceptance", "base",
                List.of(ResourceSelector.pathExact("task_store.py")), 2, UUID.randomUUID(), lineage, WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of(), List.of());
        apply(projection, projectId, signer, 6, PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                CollaborationCodec.encodeContinuation(new LaneContinuation(UUID.randomUUID(), UUID.randomUUID(), ownerTarget, "agt_owner", "agt_owner_successor", 1, "snapshot#hash",
                        new CapabilityActorScope("owner-node", "owner-supervisor", "owner-worker", "old-owner-session"),
                        new CapabilityActorScope("owner-node", "new-owner-supervisor", "new-owner-worker", "new-owner-session"))));
        CapabilityRequestRecord afterOwner = projection.findByHandle(handle).orElseThrow();
        assertTrue(afterOwner.matchesOwner("owner-node", "new-owner-supervisor", "new-owner-worker"));
        assertTrue(!afterOwner.matchesOwner("owner-node", "owner-supervisor", "owner-worker"), "the predecessor no longer acts as owner");
        assertEquals(CapabilityLifecycleState.VALIDATED, afterOwner.state(), "a verdict already given is not reopened");

        // The requester's process is lost too. Its successor must still hold the validated
        // dependency, or it would be told to request a capability it already has.
        var requesterTarget = new WorkIntent(UUID.randomUUID(), projectId, "agt_requester_successor", "claude", UUID.randomUUID(), "task", "acceptance", "base",
                List.of(ResourceSelector.pathExact("task_cli.py")), 2, UUID.randomUUID(), UUID.randomUUID(), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of(), List.of("catalog.lookup"));
        apply(projection, projectId, signer, 7, PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                CollaborationCodec.encodeContinuation(new LaneContinuation(UUID.randomUUID(), UUID.randomUUID(), requesterTarget, "agt_requester", "agt_requester_successor", 1, "snapshot#hash",
                        new CapabilityActorScope("requester-node", "requester-supervisor", "requester-worker", "old-requester-session"),
                        new CapabilityActorScope("requester-node", "new-requester-supervisor", "new-requester-worker", "new-requester-session"))));
        CapabilityRequestRecord afterRequester = projection.findByHandle(handle).orElseThrow();
        assertTrue(afterRequester.matchesRequester("requester-node", "new-requester-supervisor", "new-requester-worker"));
        assertTrue(afterRequester.matchesOwner("owner-node", "new-owner-supervisor", "new-owner-worker"));
        assertEquals(CapabilityLifecycleState.VALIDATED, afterRequester.state());
    }

    @Test
    void requestTheOwnerSupersededByFinishingFollowsTheRequesterSoItsSuccessorIsTold(@TempDir Path temp) throws Exception {
        UUID projectId = UUID.randomUUID();
        var signer = new IdentityBootstrap(temp.resolve("link")).loadOrCreate().identity();
        CapabilityRequestHandle handle = new CapabilityRequestHandle("req_31234567890123456789012345678901");
        UUID lineage = UUID.randomUUID();
        CapabilityContract draft = new CapabilityContract("lookup", "id: UUID", "value: string", List.of("stable"),
                "not found", "none", "pure", "v1", "local", "serialized", List.of("lookup returns value"));
        CapabilityContract proposed = new CapabilityContract("lookup", "id: UUID", "value: string", List.of("stable", "rejects null ids"),
                "not found", "none", "pure", "v1", "local", "serialized", List.of("lookup returns value"));
        CapabilityRequestProjection projection = new CapabilityRequestProjection();
        apply(projection, projectId, signer, 1, PredictionEventType.CAPABILITY_REQUEST_CREATED,
                new CapabilityRequestPayload(handle, "catalog.lookup", "requester-node", "requester-supervisor", "requester-worker",
                        "owner-node", "owner-supervisor", "owner-worker", lineage, draft, CapabilityLifecycleState.AWAITING_OWNER, null, 0).encode());
        apply(projection, projectId, signer, 2, PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED,
                new CapabilityRequestPayload(handle, "catalog.lookup", "requester-node", "requester-supervisor", "requester-worker",
                        "owner-node", "owner-supervisor", "owner-worker", lineage, proposed, CapabilityLifecycleState.REVISION_REQUESTED, "Null ids must be rejected", 1).encode());
        apply(projection, projectId, signer, 3, PredictionEventType.CAPABILITY_REQUEST_SUPERSEDED,
                new CapabilityRequestPayload(handle, "catalog.lookup", "requester-node", "requester-supervisor", "requester-worker",
                        "owner-node", "owner-supervisor", "owner-worker", lineage, proposed, CapabilityLifecycleState.SUPERSEDED,
                        CapabilityRequestRecord.OWNER_FINISHED_WITHOUT_ANSWER, 2).encode());
        assertTrue(projection.findByHandle(handle).orElseThrow().ownerFinishedWithoutAnswer());

        // The requester was away while the owner waited and finished. Whoever continues the
        // requester's lane must find the request, or it would never learn the owner went ahead.
        var requesterTarget = new WorkIntent(UUID.randomUUID(), projectId, "agt_requester_successor", "codex", UUID.randomUUID(), "task", "acceptance", "base",
                List.of(ResourceSelector.pathExact("task_cli.py")), 2, UUID.randomUUID(), UUID.randomUUID(), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of(), List.of("catalog.lookup"));
        apply(projection, projectId, signer, 4, PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                CollaborationCodec.encodeContinuation(new LaneContinuation(UUID.randomUUID(), UUID.randomUUID(), requesterTarget, "agt_requester", "agt_requester_successor", 1, "snapshot#hash",
                        new CapabilityActorScope("requester-node", "requester-supervisor", "requester-worker", "old-requester-session"),
                        new CapabilityActorScope("requester-node", "new-requester-supervisor", "new-requester-worker", "new-requester-session"))));
        CapabilityRequestRecord afterRequester = projection.findByHandle(handle).orElseThrow();
        assertTrue(afterRequester.matchesRequester("requester-node", "new-requester-supervisor", "new-requester-worker"));
        assertTrue(afterRequester.ownerFinishedWithoutAnswer(), "the outcome itself is not changed by the continuation");
        assertEquals(proposed, afterRequester.contract(), "the successor is shown what the owner last proposed");

        // The owner's side ended with its lane. A later continuation under the owner's old
        // identity must not pick the request up again.
        var ownerTarget = new WorkIntent(UUID.randomUUID(), projectId, "agt_owner_successor", "claude", UUID.randomUUID(), "task", "acceptance", "base",
                List.of(ResourceSelector.pathExact("task_store.py")), 2, UUID.randomUUID(), lineage, WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of(), List.of());
        apply(projection, projectId, signer, 5, PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                CollaborationCodec.encodeContinuation(new LaneContinuation(UUID.randomUUID(), UUID.randomUUID(), ownerTarget, "agt_owner", "agt_owner_successor", 1, "snapshot#hash",
                        new CapabilityActorScope("owner-node", "owner-supervisor", "owner-worker", "old-owner-session"),
                        new CapabilityActorScope("owner-node", "new-owner-supervisor", "new-owner-worker", "new-owner-session"))));
        assertTrue(projection.findByHandle(handle).orElseThrow().matchesOwner("owner-node", "owner-supervisor", "owner-worker"), "a finished owner side is left as it ended");
    }

    private static void apply(CapabilityRequestProjection projection, UUID project, org.synesis.link.identity.NodeIdentity signer,
            long sequence, PredictionEventType type, byte[] payload) throws Exception {
        PredictionEvent event = PredictionEvent.create(project, UUID.randomUUID(), sequence, type, signer.nodeId(), payload, new byte[32], signer,
                System.currentTimeMillis());
        projection.apply(event);
    }
}
