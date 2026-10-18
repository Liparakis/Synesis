package org.synesis.workspace;

import org.synesis.workspace.application.project.execution.ProjectCommandSpec;
import org.synesis.workspace.application.workspace.mutation.WorkspacePatchService;

import org.synesis.workspace.application.workspace.mutation.PatchRequest;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.run.AgentRunController;
import org.synesis.workspace.application.agent.run.EventLogObserver;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.agent.workflow.AgentNextActionService;
import org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper;
import org.synesis.workspace.application.agent.workflow.NextActionRequest;
import org.synesis.workspace.application.capability.ContractConsumerService;
import org.synesis.workspace.application.capability.ContractOwnerService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.recovery.RecoverySnapshotService;
import org.synesis.workspace.test.ProviderTestSupport;

/**
 * Exercises durable next-action projection and exact lifecycle guidance.
 */
class AgentNextActionServiceTest {

    private Path controlRoot;

    private static void git(Path root, String... arguments) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, arguments);
    }

    @BeforeEach
    void setUp() throws Exception {
        controlRoot = Files.createTempDirectory("synesis-nextaction-test-");
        git(controlRoot, "init");
        git(controlRoot, "config", "user.name", "Test User");
        git(controlRoot, "config", "user.email", "test@example.com");

        Files.createDirectories(controlRoot.resolve("src"));
        Files.writeString(controlRoot.resolve("src/Product.java"), "public class Product {}\n");

        git(controlRoot, "add", ".");
        git(controlRoot, "commit", "-m", "Initial commit");

        ProjectLocation location = new ProjectApplicationService().init(controlRoot).location();
        ProviderTestSupport.install(location, "codex");
        ProviderTestSupport.install(location, "claude");
    }

    private void prepareSessionAndTrust(String provider, String connId) throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        SessionResolutionRequest sessionReq = new SessionResolutionRequest(controlRoot, provider, connId, null, false);
        sessionService.ensureSession(sessionReq);

        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        var bindings = bindingService.list(location, provider);
        var binding = bindings.getLast();
        bindingService.verifyWorkspaceTrust(location, provider, binding.sessionId(), Path.of(binding.worktreePath()));
    }

    /** The owner lane publishes a contract over one file it claims and the consumer lane binds to it; returns the binding's request handle. */
    private String agreeContract(String ownerProvider, String ownerConnection, String consumerProvider, String consumerConnection, String capability, String ownerFile, CapabilityContract contract) {
        AgentResponse published = new ContractOwnerService().publish(new ContractOwnerService.PublishRequest(controlRoot, ownerProvider, ownerConnection,
                List.of(ResourceSelector.pathExact(ownerFile)), contract, List.of()));
        assertEquals(AgentStatus.COMPLETED, published.status(), published.toJson());
        AgentResponse bound = new ContractConsumerService().bind(new ContractConsumerService.BindRequest(controlRoot, consumerProvider, consumerConnection, capability,
                ResourceSelector.pathExact(ownerFile), ((Number) ((Map<?, ?>) published.result()).get("revision")).longValue()));
        assertEquals(AgentStatus.READY, bound.status(), bound.toJson());
        return (String) ((Map<?, ?>) bound.result()).get("capabilityRequestHandle");
    }

    @Test
    void testEmptyStateReturnsReadyWithPendingZero() throws Exception {
        prepareSessionAndTrust("codex", "conn-na-1");

        AgentNextActionService service = new AgentNextActionService();
        NextActionRequest req = new NextActionRequest(controlRoot, "codex", "conn-na-1");

        AgentResponse response = service.getNextAction(req);
        assertEquals(AgentStatus.READY, response.status());

        String json = response.toJson();
        assertTrue(json.contains("\"pending\":0"));
        assertFalse(json.contains(controlRoot.toString()));
    }

    @Test
    void freshParticipantSeesReadOnlyContinuationActionForRecoveryHeldLane() throws Exception {
        assertReadOnlyRecoveryContinuation(false, false);
    }

    @Test
    void recoveryHeldContractOwnerRemainsDiscoverableWhileIntegrationWaits() throws Exception {
        assertReadOnlyRecoveryContinuation(true, false);
    }

    @Test
    void pendingIntegrationRetainsExplicitSelectionBetweenRecoveryCandidates() throws Exception {
        assertReadOnlyRecoveryContinuation(true, true);
    }

    private void assertReadOnlyRecoveryContinuation(boolean pendingContract, boolean multipleCandidates) throws Exception {
        prepareSessionAndTrust("codex", "continuation-owner");
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claim = collaboration.announce(controlRoot, "codex", "continuation-owner", "Implement the product change", "Publish the completed product change", List.of(ResourceSelector.pathExact("src/Product.java")));
        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var ownerBinding = bindings.find(location, "codex", "continuation-owner").orElseThrow();
        CoordinationRequest contract = null;
        if (pendingContract) {
            prepareSessionAndTrust("codex", "continuation-requester");
            collaboration.announce(controlRoot, "codex", "continuation-requester", "Implement a consumer", "Publish the consumer", List.of(ResourceSelector.pathExact("src/Consumer.java")), claim.intent().workGroupId());
            contract = collaboration.request(controlRoot, "codex", "continuation-requester", claim.intent().intentId(), CoordinationRequest.Kind.CONTRACT, "Agree on the product API");
            var snapshotStore = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
            var signer = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            var queued = new TaskSnapshotPayload(claim.intent().taskId(),
                    "snap_recovery_contract_fence",
                    signer.nodeId(),
                    ownerBinding.supervisorId(),
                    ownerBinding.workerId(),
                    ownerBinding.sessionId(),
                    ownerBinding.baseCommit(),
                    ownerBinding.baseCommit(),
                    List.of("src/Product.java"),
                    List.of(),
                    "Published product",
                    new SnapshotProvenance(claim.intent().workGroupId(),
                            claim.intent().intentId(),
                            claim.intent().authorityLineageId(),
                            claim.intent().participant(),
                            ownerBinding.sessionId(),
                            claim.intent().version(),
                            List.of(),
                            List.of(),
                            List.of("PATH_EXACT:src/Product.java"),
                            "refs/synesis/snapshots/snap_recovery_contract_fence",
                            "integrity",
                            "manifest"),
                    false);
            snapshotStore.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, signer.nodeId(), queued.encode(), signer);
        }
        RecoverySnapshotService.Snapshot snapshot = new RecoverySnapshotService().materialize(location, ownerBinding.sessionId());
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        WorkIntentService intentService = new WorkIntentService(store, identity);
        intentService.suspend(claim.intent().participant());
        intentService.holdRecovery(claim.intent().participant(), snapshot.root().toString() + "#" + snapshot.contentHash());
        if (multipleCandidates) {
            var requesterBinding = bindings.find(location, "codex", "continuation-requester").orElseThrow();
            var requesterSnapshot = new RecoverySnapshotService().materialize(location, requesterBinding.sessionId());
            intentService.suspend(contract.requester());
            intentService.holdRecovery(contract.requester(), requesterSnapshot.root().toString() + "#" + requesterSnapshot.contentHash());
        }
        long eventsBeforePoll = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).headSequence();

        prepareSessionAndTrust("codex", "continuation-successor");
        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "continuation-successor"));

        assertEquals(AgentStatus.READY, response.status(), response.toJson());
        assertEquals(AgentReason.CONTINUATION_AVAILABLE, response.reason(), response.toJson());
        assertEquals(AgentNextAction.REQUEST_COORDINATION, response.nextAction(), response.toJson());
        Map<?, ?> result = (Map<?, ?>) response.result();
        assertEquals("RECOVERY_CONTINUATION_AVAILABLE", result.get("state"));
        assertEquals("continuation", result.get("nextProtocolKind"));
        Map<?, ?> payload = (Map<?, ?>) result.get("nextProtocolPayload");
        if (multipleCandidates) {
            assertEquals(Boolean.TRUE, result.get("continuationSelectionRequired"));
            assertFalse(result.containsKey("selectedContinuation"));
            assertFalse(result.containsKey("nextProtocolPayload"));
        } else {
            assertEquals(claim.intent().intentId().toString(), payload.get("intentId"));
            assertEquals(claim.intent().version(), ((Number) payload.get("claimEpoch")).longValue());
        }
        java.util.List<?> candidates = (java.util.List<?>) result.get("continuationCandidates");
        assertEquals(multipleCandidates ? 2 : 1, candidates.size());
        Map<?, ?> candidate = (Map<?, ?>) candidates.getFirst();
        Map<?, ?> continuationRequest = (Map<?, ?>) candidate.get("continuationRequest");
        assertEquals("continuation", continuationRequest.get("kind"));
        assertEquals(Map.of("intentId", candidate.get("sourceIntentId"), "claimEpoch", candidate.get("claimEpoch")), continuationRequest.get("payload"));
        Map<?, ?> workflow = (Map<?, ?>) result.get("workflow");
        assertEquals("RECOVER", workflow.get("type"));
        if (multipleCandidates) {
            assertFalse(workflow.containsKey("recommendedTool"));
            assertFalse(workflow.containsKey("arguments"));
        } else {
            assertEquals("request_coordination", workflow.get("recommendedTool"));
            Map<?, ?> arguments = (Map<?, ?>) workflow.get("arguments");
            assertEquals("continuation", arguments.get("kind"));
            Map<?, ?> projectedPayload = (Map<?, ?>) arguments.get("payload");
            assertFalse(projectedPayload.containsKey("grantId"));
        }
        assertFalse(response.toJson().contains(snapshot.root().toString()));
        assertFalse(response.toJson().contains("recoverySnapshotReference"));
        assertEquals(eventsBeforePoll, new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).headSequence());
        if (pendingContract) {
            var unchanged = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
            UUID contractId = contract.requestId();
            assertEquals(CoordinationRequest.Status.PENDING, unchanged.collaborationProjection().requests().stream().filter(request -> request.requestId().equals(contractId)).findFirst().orElseThrow().status());
            assertEquals(org.synesis.coordination.domain.collaboration.Participant.State.RECOVERY_HELD, unchanged.collaborationProjection().participantState(claim.intent().participant()).orElseThrow());
            assertTrue(((List<?>) result.get("pendingCoordination")).isEmpty());
            assertEquals(org.synesis.coordination.domain.task.TaskCompletionState.INTEGRATION_PENDING, unchanged.taskCompletionProjection().snapshotState("snap_recovery_contract_fence").orElseThrow());
        }
        if (!multipleCandidates) {
            // A session of another provider cannot continue the lane, because a lane is continued
            // from its own provider's binding. It must not be handed a request that would be refused.
            prepareSessionAndTrust("claude", "continuation-other-provider");
            AgentResponse otherProvider = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "claude", "continuation-other-provider"));
            assertNotEquals(AgentReason.CONTINUATION_AVAILABLE, otherProvider.reason(), otherProvider.toJson());
            Map<?, ?> refused = onlyContinuationCandidate(otherProvider);
            if (refused != null) {
                assertEquals(Boolean.FALSE, refused.get("available"), otherProvider.toJson());
                assertEquals("CONTINUATION_REQUIRES_SAME_PROVIDER", refused.get("blockedReason"), otherProvider.toJson());
            } else {
                assertTrue(pendingContract, "only the shared integration wait may replace the collaboration view: " + otherProvider.toJson());
            }
        }
        if (pendingContract && !multipleCandidates) {
            // A session that already holds its own lane cannot take a second one.
            AgentResponse laneHolder = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "continuation-requester"));
            assertNotEquals(AgentReason.CONTINUATION_AVAILABLE, laneHolder.reason(), laneHolder.toJson());
            Map<?, ?> blocked = onlyContinuationCandidate(laneHolder);
            if (blocked != null) {
                assertEquals(Boolean.FALSE, blocked.get("available"), laneHolder.toJson());
                assertEquals("CURRENT_PARTICIPANT_HOLDS_A_LANE", blocked.get("blockedReason"), laneHolder.toJson());
            }
        }
    }

    private static Map<?, ?> onlyContinuationCandidate(AgentResponse response) {
        java.util.List<?> candidates = (java.util.List<?>) ((Map<?, ?>) response.result()).get("continuationCandidates");
        if (candidates == null) {
            return null;
        }
        assertEquals(1, candidates.size(), response.toJson());
        return (Map<?, ?>) candidates.getFirst();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mandatoryReviewCandidatesAreSnapshotSpecificAndNotAutoSelected() throws Exception {
        prepareSessionAndTrust("codex", "mandatory-review-owner-a");
        prepareSessionAndTrust("codex", "mandatory-review-owner-b");
        prepareSessionAndTrust("claude", "mandatory-reviewer");
        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        UUID groupId = UUID.randomUUID();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.createWorkGroup(controlRoot, groupId, "Build a board", "CRUD and persistence", ReviewPolicy.mandatoryIndependent());
        var ownerA = collaboration.announce(controlRoot,
                "codex",
                "mandatory-review-owner-a",
                "Implement board creation",
                "Publish the board creation lane",
                List.of(ResourceSelector.pathExact("src/BoardCreate.java")),
                groupId);
        var ownerB = collaboration.announce(controlRoot,
                "codex",
                "mandatory-review-owner-b",
                "Implement board listing",
                "Publish the board listing lane",
                List.of(ResourceSelector.pathExact("src/BoardList.java")),
                groupId);
        collaboration.announce(controlRoot,
                "claude",
                "mandatory-reviewer",
                "Review board changes",
                "Record an independent review",
                List.of(ResourceSelector.pathExact("docs/review-report.md")),
                groupId,
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathSubtree("src")));

        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var bindingA = bindings.find(location, "codex", "mandatory-review-owner-a").orElseThrow();
        var bindingB = bindings.find(location, "codex", "mandatory-review-owner-b").orElseThrow();
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        appendMandatorySnapshot(store, identity, bindingA, ownerA.intent(), "snap_mandatory_create", "src/BoardCreate.java");
        appendMandatorySnapshot(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()), identity, bindingB, ownerB.intent(), "snap_mandatory_list", "src/BoardList.java");
        long eventsBeforePoll = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).headSequence();

        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "claude", "mandatory-reviewer"));

        Map<String, Object> result = (Map<String, Object>) response.result();
        assertTrue(result.containsKey("reviewCandidates"), response.toJson());
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("reviewCandidates");
        assertEquals(2, candidates.size(), response.toJson());
        assertEquals(Boolean.TRUE, result.get("reviewCandidateSelectionRequired"));
        assertEquals("Choose an exact candidate explicitly; Synesis does not select one for you.", result.get("reviewCandidateSelectionInstruction"));
        assertTrue(((List<?>) result.get("reviewActions")).isEmpty(), response.toJson());
        assertEquals(2, candidates.stream().map(candidate -> candidate.get("snapshotId")).distinct().count());
        for (Map<String, Object> candidate : candidates) {
            Map<String, Object> reviewerIntent = (Map<String, Object>) candidate.get("reviewerIntent");
            assertEquals(candidate.get("snapshotId"), reviewerIntent.get("snapshotId"));
            assertEquals("ensure_session", reviewerIntent.get("tool"));
            Map<String, Object> ensureArguments = (Map<String, Object>) reviewerIntent.get("arguments");
            Map<String, Object> reviewerTask = (Map<String, Object>) ensureArguments.get("task");
            assertEquals("reviewer", reviewerTask.get("role"));
            assertEquals(groupId.toString(), reviewerTask.get("workGroupId"));
            assertFalse(((List<?>) reviewerTask.get("reviewTargets")).isEmpty());
            assertEquals(List.of(Map.of("kind", "path_exact", "path", "reviews/" + candidate.get("snapshotId") + ".md")), reviewerTask.get("claims"),
                    "the notes a reviewer ends its lane by publishing are claimed with no step of its own (ADR-0102 item 10)");
            Map<String, Object> claimRequirement = (Map<String, Object>) reviewerIntent.get("claimRequirement");
            assertEquals("task.claims", claimRequirement.get("field"));
            assertEquals(Boolean.TRUE, claimRequirement.get("required"));
            assertEquals(List.of("producer snapshot paths", "all active claims"), claimRequirement.get("mustBeDisjointFrom"));
            Map<String, Object> request = (Map<String, Object>) candidate.get("requestCoordination");
            assertEquals("work_group_join", request.get("kind"));
            Map<String, Object> payload = (Map<String, Object>) request.get("payload");
            assertEquals(candidate.get("snapshotId"), payload.get("snapshotId"));
            assertEquals(groupId.toString(), payload.get("workGroupId"));
        }
        assertEquals(eventsBeforePoll, new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).headSequence());
    }

    private static final String UNCLAIMED_ONLY = "This session holds no lane. Read the project goal with read_file, choose a part that no listed participant claims, and announce it by calling "
            + "ensure_session with a task (goal, acceptance, claims). Calling ensure_session without a task changes nothing.";

    @Test
    @SuppressWarnings("unchecked")
    void laneLessCallerIsPointedAtReviewCandidatesAsAnAlternativeToClaimingAPart() throws Exception {
        prepareSessionAndTrust("codex", "fresh-owner");
        prepareSessionAndTrust("codex", "fresh-newcomer");
        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        UUID groupId = UUID.randomUUID();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.createWorkGroup(controlRoot, groupId, "Build a board", "CRUD and persistence", ReviewPolicy.mandatoryIndependent());
        var owner = collaboration.announce(controlRoot, "codex", "fresh-owner", "Implement board creation", "Publish the board creation lane", List.of(ResourceSelector.pathExact("src/BoardCreate.java")), groupId);

        AgentResponse before = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "fresh-newcomer"));
        assertEquals(AgentReason.COORDINATION_INTENT_REQUIRED, before.reason(), before.toJson());
        assertEquals(UNCLAIMED_ONLY, ((Map<String, Object>) before.result()).get("instruction"));

        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        appendMandatorySnapshot(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()), identity, bindings.find(location, "codex", "fresh-owner").orElseThrow(), owner.intent(),
                "snap_fresh", "src/BoardCreate.java");

        AgentResponse after = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "fresh-newcomer"));
        assertEquals(AgentReason.COORDINATION_INTENT_REQUIRED, after.reason(), after.toJson());
        Map<String, Object> result = (Map<String, Object>) after.result();
        String instruction = (String) result.get("instruction");
        assertTrue(instruction.contains("either choose a part that no listed participant claims, or review a snapshot awaiting review: reviewCandidates[i].reviewerIntent"), instruction);
        assertEquals("snap_fresh", ((Map<String, Object>) ((List<?>) result.get("reviewCandidates")).getFirst()).get("snapshotId"));

        String newcomer = WorkspaceCollaborationService.participantHandle(bindings.find(location, "codex", "fresh-newcomer").orElseThrow().sessionId());
        List<Map<String, Object>> offered = CollaborationResponseMapper.openReviewCandidates(location, newcomer);
        assertEquals(1, offered.size());
        assertEquals("snap_fresh", offered.getFirst().get("snapshotId"));
        assertEquals(List.of("src/BoardCreate.java"), offered.getFirst().get("files"));
        assertEquals("ensure_session", ((Map<String, Object>) offered.getFirst().get("reviewerIntent")).get("tool"));
        // The only candidate is the owner's own snapshot, and a session that holds a lane takes no second one.
        assertTrue(CollaborationResponseMapper.openReviewCandidates(location, owner.intent().participant()).isEmpty());

        // The run controller resumes a stopped agent with no lane for exactly what this offers (ADR-0099).
        AgentRunController.Observation observed = new EventLogObserver(location).observe();
        assertEquals(List.of("snap_fresh"), observed.reviewsOffered("codex", List.of("fresh-newcomer")));
        assertEquals(List.of(), observed.reviewsOffered("codex", List.of("fresh-newcomer", "fresh-owner")), "an agent one of whose sessions is the author is not offered the review");
        assertEquals(List.of(), observed.reviewsOffered("codex", List.of("never-connected")), "nothing is offered to a connection with no session");
    }

    /**
     * What the run controller reads to keep the session of an agent that asked for a review and to
     * resume it once when the author answers (ADR-0099, 2026-10-18 fix 10), from a real log.
     */
    @Test
    void theRunControllerSeesARequestHeldUntilItsGrantIsUsedAndItsAnswerOnce() throws Exception {
        prepareSessionAndTrust("codex", "answer-author");
        prepareSessionAndTrust("codex", "answer-reviewer");
        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        UUID groupId = UUID.randomUUID();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.createWorkGroup(controlRoot, groupId, "Count words", "Review each module", ReviewPolicy.mandatoryIndependent());
        var author = collaboration.announce(controlRoot, "codex", "answer-author", "Implement count", "Publish count", List.of(ResourceSelector.pathExact("count.py")), groupId);
        collaboration.announce(controlRoot, "codex", "answer-reviewer", "Review count", "Independently review count", List.of(ResourceSelector.pathExact("review_count.md")), groupId,
                WorkIntent.Role.REVIEWER, List.of(ResourceSelector.pathExact("count.py")));
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        appendMandatorySnapshot(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()), identity,
                new ProviderSessionBindingService().find(location, "codex", "answer-author").orElseThrow(), author.intent(), "snap_count", "count.py");
        AgentRunController.Observation before = new EventLogObserver(location).observe();
        assertTrue(before.newestAnswerFor("codex", "answer-reviewer").isEmpty());
        assertFalse(before.holdsRequestOrGrant("codex", "answer-reviewer"));

        CoordinationRequest request = collaboration.request(controlRoot, "codex", "answer-reviewer", author.intent().intentId(), CoordinationRequest.Kind.REVIEW, "Review count", "snap_count");
        AgentRunController.Observation pending = new EventLogObserver(location).observe();
        assertTrue(pending.newestAnswerFor("codex", "answer-reviewer").isEmpty(), "asking is not an answer");
        assertTrue(pending.holdsRequestOrGrant("codex", "answer-reviewer"), "a pending request keeps the session that asked");

        collaboration.respond(controlRoot, "codex", "answer-author", request.requestId(), CoordinationRequest.Status.ACCEPTED, "admitted");
        AgentRunController.Observation accepted = new EventLogObserver(location).observe();
        long answeredAt = accepted.newestAnswerFor("codex", "answer-reviewer").orElseThrow();
        assertTrue(answeredAt > pending.headSequence() && answeredAt < accepted.headSequence(), "the response, before the grant it issues: " + answeredAt);
        assertTrue(accepted.holdsRequestOrGrant("codex", "answer-reviewer"), "the grant keeps it");
        assertTrue(accepted.newestAnswerFor("codex", "answer-author").isEmpty(), "the author answered and asked nothing");

        collaboration.respond(controlRoot, "codex", "answer-author", request.requestId(), CoordinationRequest.Status.ACCEPTED, "admitted again");
        assertEquals(answeredAt, new EventLogObserver(location).observe().newestAnswerFor("codex", "answer-reviewer").orElseThrow(), "the same answer given again is not a new one");
    }

    private static final String VALIDATION_SENTENCE = " Synesis runs this project's validation command (`python -m unittest`) on every integration, so running or reporting it is not a part to claim.";

    @Test
    @SuppressWarnings("unchecked")
    void laneLessCallerIsToldTheValidationCommandIsNotAPartToClaim() throws Exception {
        new ProjectApplicationService().setValidation(controlRoot, new ProjectCommandSpec(List.of("python", "-m", "unittest"), ".", 30), false);
        prepareSessionAndTrust("codex", "fresh-owner-v");
        prepareSessionAndTrust("codex", "fresh-newcomer-v");
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.announce(controlRoot, "codex", "fresh-owner-v", "Implement board creation", "Publish the board creation lane", List.of(ResourceSelector.pathExact("src/BoardCreate.java")));
        AgentResponse with = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "fresh-newcomer-v"));
        assertEquals(AgentReason.COORDINATION_INTENT_REQUIRED, with.reason(), with.toJson());
        assertEquals(UNCLAIMED_ONLY + VALIDATION_SENTENCE, ((Map<String, Object>) with.result()).get("instruction"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void validationSentenceAccompaniesTheReviewAlternative() throws Exception {
        new ProjectApplicationService().setValidation(controlRoot, new ProjectCommandSpec(List.of("python", "-m", "unittest"), ".", 30), false);
        prepareSessionAndTrust("codex", "fresh-owner-r");
        prepareSessionAndTrust("codex", "fresh-newcomer-r");
        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        UUID groupId = UUID.randomUUID();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.createWorkGroup(controlRoot, groupId, "Build a board", "CRUD and persistence", ReviewPolicy.mandatoryIndependent());
        var owner = collaboration.announce(controlRoot, "codex", "fresh-owner-r", "Implement board creation", "Publish the board creation lane", List.of(ResourceSelector.pathExact("src/BoardCreate.java")), groupId);
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        appendMandatorySnapshot(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()), identity,
                new ProviderSessionBindingService().find(location, "codex", "fresh-owner-r").orElseThrow(), owner.intent(), "snap_fresh_v", "src/BoardCreate.java");

        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "fresh-newcomer-r"));
        String instruction = (String) ((Map<String, Object>) response.result()).get("instruction");
        assertTrue(instruction.contains("either choose a part that no listed participant claims, or review a snapshot awaiting review"), instruction);
        assertTrue(instruction.endsWith(VALIDATION_SENTENCE), instruction);
    }

    private static void appendMandatorySnapshot(PredictionEventStore store,
            org.synesis.link.identity.NodeIdentity identity,
            ProviderSessionBindingService.Binding binding,
            WorkIntent intent,
            String snapshotId,
            String changedPath) throws Exception {
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        SnapshotProvenance provenance = new SnapshotProvenance(intent.workGroupId(),
                intent.intentId(),
                intent.authorityLineageId(),
                participant,
                binding.sessionId(),
                intent.version(),
                List.of(),
                List.of(),
                List.of("PATH_EXACT:" + changedPath),
                "refs/synesis/snapshots/" + snapshotId,
                "integrity-" + snapshotId,
                "manifest-" + snapshotId);
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(intent.taskId(),
                snapshotId,
                identity.nodeId(),
                binding.supervisorId(),
                binding.workerId(),
                binding.sessionId(),
                binding.baseCommit(),
                binding.baseCommit(),
                List.of(changedPath),
                List.of(),
                "Published candidate",
                provenance,
                true);
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingOwnerResponsePrecedesUnrelatedRecoveryContinuation() throws Exception {
        prepareSessionAndTrust("codex", "owner-response-priority");
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var ownerClaim = collaboration.announce(controlRoot,
                "codex",
                "owner-response-priority",
                "Implement the product change",
                "Publish the completed product change",
                List.of(ResourceSelector.pathExact("src/Product.java")));

        prepareSessionAndTrust("codex", "requesting-reviewer-priority");
        var requestingReviewer = collaboration.announce(controlRoot,
                "codex",
                "requesting-reviewer-priority",
                "Review the product change",
                "Admit an independent review",
                List.of(ResourceSelector.pathExact("tests/ProductTest.java")),
                ownerClaim.intent().workGroupId(),
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/Product.java")));
        CoordinationRequest request = collaboration.request(controlRoot, "codex", "requesting-reviewer-priority", ownerClaim.intent().intentId(), CoordinationRequest.Kind.REVIEW, "Review the completed product snapshot");
        assertEquals(ownerClaim.intent().workGroupId(), requestingReviewer.intent().workGroupId());

        prepareSessionAndTrust("codex", "recoverable-reviewer-priority");
        var recoverableReviewer = collaboration.announce(controlRoot,
                "codex",
                "recoverable-reviewer-priority",
                "Review the product change",
                "Record an independent review",
                List.of(ResourceSelector.pathExact("review-report.md")),
                ownerClaim.intent().workGroupId(),
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/Product.java")));
        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var recoverableBinding = bindings.find(location, "codex", "recoverable-reviewer-priority").orElseThrow();
        RecoverySnapshotService.Snapshot snapshot = new RecoverySnapshotService().materialize(location, recoverableBinding.sessionId());
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        WorkIntentService intentService = new WorkIntentService(store, identity);
        intentService.suspend(recoverableReviewer.intent().participant());
        intentService.holdRecovery(recoverableReviewer.intent().participant(), snapshot.root().toString() + "#" + snapshot.contentHash());

        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "owner-response-priority"));

        assertEquals(AgentStatus.READY, response.status(), response.toJson());
        assertEquals(AgentReason.OWNER_REQUEST_PENDING, response.reason(), response.toJson());
        assertEquals(AgentNextAction.RESPOND_COORDINATION, response.nextAction(), response.toJson());
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertEquals(request.requestId().toString(), ((Map<String, Object>) ((List<?>) result.get("pendingCoordination")).getFirst()).get("requestId"));
        assertEquals("respond_coordination", ((Map<String, Object>) result.get("workflow")).get("recommendedTool"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingContractsExposeOwnerContextWithoutAssigningNonOwnerResponse() throws Exception {
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        prepareSessionAndTrust("codex", "contract-owner");
        var owner = collaboration.announce(controlRoot, "codex", "contract-owner", "Implement the product", "Publish the product", List.of(ResourceSelector.pathExact("src/Product.java")));
        prepareSessionAndTrust("codex", "contract-requester");
        var requester = collaboration.announce(controlRoot, "codex", "contract-requester", "Implement a consumer", "Publish the consumer", List.of(ResourceSelector.pathExact("src/Consumer.java")));
        prepareSessionAndTrust("claude", "contract-observer");
        collaboration.announce(controlRoot, "claude", "contract-observer", "Document the product", "Publish documentation", List.of(ResourceSelector.pathExact("docs/Product.md")));
        CoordinationRequest first = collaboration.request(controlRoot, "codex", "contract-requester", owner.intent().intentId(), CoordinationRequest.Kind.CONTRACT, "Define the return type");
        CoordinationRequest second = collaboration.request(controlRoot, "claude", "contract-observer", owner.intent().intentId(), CoordinationRequest.Kind.CONTRACT, "Define the error contract");
        var location = new ProjectApplicationService().locate(controlRoot);
        var binding = new ProviderSessionBindingService().find(location, "codex", "contract-owner").orElseThrow();
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(owner.intent().taskId(),
                "snap_contract_fence",
                identity.nodeId(),
                binding.supervisorId(),
                binding.workerId(),
                binding.sessionId(),
                binding.baseCommit(),
                binding.baseCommit(),
                List.of("src/Product.java"),
                List.of(),
                "Published product",
                new SnapshotProvenance(owner.intent().workGroupId(),
                        owner.intent().intentId(),
                        owner.intent().authorityLineageId(),
                        owner.intent().participant(),
                        binding.sessionId(),
                        owner.intent().version(),
                        List.of(),
                        List.of(),
                        List.of("PATH_EXACT:src/Product.java"),
                        "refs/synesis/snapshots/snap_contract_fence",
                        "integrity-contract",
                        "manifest-contract"),
                false);
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);
        long eventsBeforePoll = store.headSequence();

        AgentNextActionService service = new AgentNextActionService();
        AgentResponse ownerResponse = service.getNextAction(new NextActionRequest(controlRoot, "codex", "contract-owner"));
        assertEquals(AgentReason.OWNER_REQUEST_PENDING, ownerResponse.reason(), ownerResponse.toJson());
        assertEquals(AgentNextAction.RESPOND_COORDINATION, ownerResponse.nextAction());
        Map<String, Object> envelope = (Map<String, Object>) ProviderJson.parse(ownerResponse.toJson());
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("REVIEW_CONTRACT", workflow.get("type"));
        assertFalse(workflow.containsKey("recommendedTool"));
        assertFalse(workflow.containsKey("arguments"), "CONTRACT requires the owner's judgment");
        Map<?, ?> decision = (Map<?, ?>) workflow.get("decision");
        assertEquals(true, decision.get("required"));
        assertEquals("coordinationStatus", decision.get("field"));
        assertEquals("respond_coordination", decision.get("tool"));
        assertEquals(List.of("ACCEPTED", "REVISED", "REJECTED", "CANCELLED", "COMPLETED"), decision.get("allowedResults"));
        List<Map<?, ?>> templates = (List<Map<?, ?>>) decision.get("responseTemplates");
        assertEquals(2, templates.size());
        assertEquals(java.util.Set.of(first.requestId().toString(), second.requestId().toString()),
                templates.stream().map(template -> ((Map<?, ?>) template.get("payload")).get("coordinationRequest")).collect(java.util.stream.Collectors.toSet()));
        for (Map<?, ?> template : templates) {
            assertEquals("coordination_response", template.get("kind"));
            assertFalse(((Map<?, ?>) template.get("payload")).containsKey("coordinationStatus"));
        }
        List<Map<String, Object>> pending = (List<Map<String, Object>>) result.get("pendingCoordination");
        assertEquals(2, pending.size());
        assertEquals(java.util.Set.of(first.requestId().toString(), second.requestId().toString()), pending.stream().map(item -> item.get("requestId")).collect(java.util.stream.Collectors.toSet()));
        for (Map<String, Object> item : pending) {
            CoordinationRequest expected = item.get("requestId").equals(first.requestId().toString()) ? first : second;
            assertEquals(expected.requester(), item.get("requester"));
            assertEquals(owner.intent().participant(), item.get("target"));
            assertEquals(owner.intent().intentId().toString(), item.get("conflictingIntentId"));
            assertEquals("CONTRACT", item.get("kind"));
            assertEquals(expected.proposal(), item.get("proposal"));
            assertEquals("PENDING", item.get("status"));
            assertEquals(owner.intent().workGroupId().toString(), item.get("workGroupId"));
            assertEquals(owner.intent().version(), ((Number) item.get("claimEpoch")).longValue());
        }
        assertFalse(result.containsKey("nextProtocolPayload"));
        assertEquals(pending, result.get("pendingCoordination"));
        assertFalse(workflow.containsKey("payload"), "the envelope must not repeat the result it describes");

        AgentResponse nonOwnerResponse = service.getNextAction(new NextActionRequest(controlRoot, "codex", "contract-requester"));
        assertEquals(AgentStatus.WAITING, nonOwnerResponse.status(), nonOwnerResponse.toJson());
        assertEquals(AgentReason.INTEGRATION_PENDING, nonOwnerResponse.reason());
        assertEquals(AgentNextAction.WAIT, nonOwnerResponse.nextAction());
        Map<?, ?> nonOwnerResult = (Map<?, ?>) nonOwnerResponse.result();
        assertEquals(2, nonOwnerResult.get("pending"));
        assertFalse(nonOwnerResult.containsKey("reviewDecision"));
        assertFalse(nonOwnerResult.containsKey("request"));
        assertEquals("get_next_action", ((Map<?, ?>) nonOwnerResult.get("workflow")).get("recommendedTool"));
        assertEquals(Map.of(), ((Map<?, ?>) nonOwnerResult.get("workflow")).get("arguments"));
        var reloaded = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals(eventsBeforePoll, reloaded.headSequence());
        assertTrue(new WorkIntentService(reloaded, identity).requests().stream().allMatch(request -> request.status() == CoordinationRequest.Status.PENDING));
        assertNotEquals(requester.intent().participant(), owner.intent().participant());
    }

    @Test
    void committedProducerNeedsExplicitCompletionRequestForFinishProjection() throws Exception {
        prepareSessionAndTrust("codex", "committed-producer");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claim = collaboration.announce(controlRoot,
                "codex",
                "committed-producer",
                "Implement the product change",
                "Publish the completed product change",
                List.of(ResourceSelector.pathExact("src/Product.java"), ResourceSelector.pathExact("src/ProductTest.java")));

        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        var binding = bindingService.find(location, "codex", "committed-producer").orElseThrow();
        Path worker = Path.of(binding.worktreePath());
        Files.writeString(worker.resolve("src/Product.java"), "public class Product { int version = 2; }\n");
        git(worker, "add", "src/Product.java");
        git(worker, "commit", "-m", "worker product model change");

        AgentNextActionService nextActionService = new AgentNextActionService();
        AgentResponse response = nextActionService.getNextAction(new NextActionRequest(controlRoot, "codex", "committed-producer"));

        assertEquals(AgentStatus.READY, response.status(), response.toJson());
        assertEquals("IMPLEMENT", ((Map<?, ?>) response.result()).get("workflow") instanceof Map<?, ?> workflow ? workflow.get("type") : null, response.toJson());
        assertFalse(response.toJson().contains("SNAPSHOT_PUBLICATION_REQUIRED"), response.toJson());
        assertFalse(response.toJson().contains("finish_lane"), response.toJson());

        Files.writeString(worker.resolve("src/Product.java"), "public class Product { int version = 3; String label = \"tracked\"; }\n");
        git(worker, "add", "src/Product.java");
        git(worker, "commit", "-m", "worker product behavior change");
        AgentResponse afterSecondMutation = nextActionService.getNextAction(new NextActionRequest(controlRoot, "codex", "committed-producer"));
        assertEquals(AgentStatus.READY, afterSecondMutation.status(), afterSecondMutation.toJson());
        assertEquals("IMPLEMENT", ((Map<?, ?>) afterSecondMutation.result()).get("workflow") instanceof Map<?, ?> workflow ? workflow.get("type") : null, afterSecondMutation.toJson());
        assertFalse(afterSecondMutation.toJson().contains("finish_lane"), afterSecondMutation.toJson());

        Files.writeString(worker.resolve("src/ProductTest.java"), "public class ProductTest { void acceptsTrackedProduct() {} }\n");
        git(worker, "add", "src/ProductTest.java");
        git(worker, "commit", "-m", "worker product tests");
        AgentResponse afterTests = nextActionService.getNextAction(new NextActionRequest(controlRoot, "codex", "committed-producer"));
        assertEquals(AgentStatus.READY, afterTests.status(), afterTests.toJson());
        assertEquals("IMPLEMENT", ((Map<?, ?>) afterTests.result()).get("workflow") instanceof Map<?, ?> workflow ? workflow.get("type") : null, afterTests.toJson());
        assertFalse(afterTests.toJson().contains("finish_lane"), afterTests.toJson());

        assertTrue(new TaskSnapshotService().hasPublishableChanges(worker, claim.intent().selectors(), claim.intent().baseCommit(), false),
                "base=" + claim.intent().baseCommit() + ", head=" + org.synesis.workspace.test.TestGit.output(worker, "rev-parse", "HEAD") + ", diff=" + org.synesis.workspace.test.TestGit.output(worker,
                        "diff",
                        "--name-only",
                        claim.intent().baseCommit(),
                        "HEAD"));

        prepareSessionAndTrust("codex", "unrelated-recovery-reviewer");
        var recoverableReviewer = collaboration.announce(controlRoot,
                "codex",
                "unrelated-recovery-reviewer",
                "Review the product change",
                "Record an independent review",
                List.of(ResourceSelector.pathExact("review-report.md")),
                claim.intent().workGroupId(),
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/Product.java")));
        var recoverableBinding = bindingService.find(location, "codex", "unrelated-recovery-reviewer").orElseThrow();
        RecoverySnapshotService.Snapshot recovery = new RecoverySnapshotService().materialize(location, recoverableBinding.sessionId());
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        WorkIntentService intentService = new WorkIntentService(store, identity);
        intentService.suspend(recoverableReviewer.intent().participant());
        intentService.holdRecovery(recoverableReviewer.intent().participant(), recovery.root().toString() + "#" + recovery.contentHash());

        AgentResponse completionRequest = nextActionService.getNextAction(new NextActionRequest(controlRoot, "codex", "committed-producer", true));
        assertEquals(AgentReason.SNAPSHOT_PUBLICATION_REQUIRED, completionRequest.reason(), completionRequest.toJson());
        assertEquals(AgentNextAction.FINISH_LANE, completionRequest.nextAction(), completionRequest.toJson());
        assertEquals(claim.intent().intentId().toString(), ((Map<?, ?>) completionRequest.result()).get("intentId"));

        AgentResponse ordinaryPollAfterRequest = nextActionService.getNextAction(new NextActionRequest(controlRoot, "codex", "committed-producer"));
        assertFalse(ordinaryPollAfterRequest.toJson().contains("finish_lane"), ordinaryPollAfterRequest.toJson());
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitDependencyProjectsCapabilityGuidanceWithoutEmptyRequest() throws Exception {
        prepareSessionAndTrust("codex", "dependency-requester");
        prepareSessionAndTrust("claude", "dependency-owner");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var requester = collaboration.announce(controlRoot,
                "codex",
                "dependency-requester",
                "Implement task-tracker integration",
                "Use the domain capability when available",
                List.of(ResourceSelector.pathExact("src/service")),
                null,
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of("tasktracker.domain"));
        collaboration.announce(controlRoot,
                "claude",
                "dependency-owner",
                "Implement the task-tracker domain",
                "Publish the domain capability",
                List.of(ResourceSelector.pathExact("src/domain")),
                requester.intent().workGroupId());

        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "dependency-requester"));

        assertEquals(AgentStatus.NEEDS_CAPABILITY, response.status(), response.toJson());
        assertEquals(AgentReason.OWNER_REQUIRED, response.reason());
        assertEquals(AgentNextAction.REQUEST_COORDINATION, response.nextAction());
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertEquals("tasktracker.domain", result.get("capability"));
        assertEquals(List.of("capability", "ownerPath", "revision"), result.get("requiredFields"), "what contract_bind takes: the lane accepts the owner's contract and writes none");
        Map<String, Object> currentIntent = (Map<String, Object>) result.get("currentIntent");
        assertEquals(List.of("tasktracker.domain"), currentIntent.get("knownDependencies"));
        // The requester is shown who claims what, so it can name the file it builds on.
        assertEquals(List.of(List.of("src/domain")), ((List<Map<String, Object>>) result.get("lanes")).stream().map(lane -> lane.get("claims")).toList());
        assertTrue(String.valueOf(result.get("instruction")).contains("ownerPath"), response.toJson());
        assertEquals("contract_bind", result.get("coordinationKind"));
        assertEquals(List.of(), result.get("contracts"));

        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("REVISE_SCOPE", workflow.get("type"));
        assertFalse(workflow.containsKey("recommendedTool"), response.toJson());
        assertFalse(workflow.containsKey("arguments"), response.toJson());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ownerResolvesItsAcceptedKnownDependencyLocallyWithExactContract() throws Exception {
        prepareSessionAndTrust("codex", "local-capability-requester");
        prepareSessionAndTrust("claude", "local-capability-owner");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var requester = collaboration.announce(controlRoot,
                "codex",
                "local-capability-requester",
                "Build the consumer",
                "Use the negotiated domain API",
                List.of(ResourceSelector.pathExact("src/Consumer.java")));
        var owner = collaboration.announce(controlRoot,
                "claude",
                "local-capability-owner",
                "Build the domain API",
                "Publish its accepted contract",
                List.of(ResourceSelector.pathExact("src/Domain.java")),
                requester.intent().workGroupId(),
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of("tasktracker.domain"));

        CapabilityContract contract = new CapabilityContract("Task creation API",
                "A non-empty title",
                List.of("Create a task with a generated ID"),
                List.of("Task defaults to the accepted status"));
        String handle = agreeContract("claude", "local-capability-owner", "codex", "local-capability-requester", "tasktracker.domain", "src/Domain.java", contract);

        AgentNextActionService nextActions = new AgentNextActionService();
        var extension = collaboration.announce(controlRoot,
                "claude",
                "local-capability-owner",
                owner.intent().goal(),
                owner.intent().acceptance(),
                List.of(ResourceSelector.pathExact("src/Domain.java"), ResourceSelector.pathExact("tests/DomainTest.java")),
                owner.intent().workGroupId(),
                owner.intent().role(),
                owner.intent().reviewTargetSelectors(),
                owner.intent().knownDependencies(),
                owner.intent().intentId(),
                owner.intent().version());
        assertTrue(extension.acquired());
        assertEquals(2, extension.intent().version());

        AgentResponse ownerImplementation = nextActions.getNextAction(new NextActionRequest(controlRoot,
                "claude",
                "local-capability-owner"));
        assertEquals(AgentStatus.READY, ownerImplementation.status(), ownerImplementation.toJson());
        Map<?, ?> resolvedDependencies = (Map<?, ?>) ((Map<?, ?>) ownerImplementation.result())
                .get("resolvedDependencies");
        Map<?, ?> resolvedDomain = (Map<?, ?>) resolvedDependencies.get("tasktracker.domain");
        assertEquals(handle, resolvedDomain.get("capabilityRequestHandle"));
        assertEquals(contract.purpose(), ((Map<?, ?>) resolvedDomain.get("contract")).get("purpose"));
        assertFalse(ownerImplementation.toJson().contains("needs_capability"), ownerImplementation.toJson());
        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"),
                location.projectId());
        assertEquals(1, store.capabilityRequestProjection().records().size());

        // A second consumer binds to the same text, so the owner cannot be handed two contracts for one file.
        prepareSessionAndTrust("codex", "local-capability-requester-two");
        var secondRequester = collaboration.announce(controlRoot,
                "codex",
                "local-capability-requester-two",
                "Build another consumer",
                "Use the same capability",
                List.of(ResourceSelector.pathExact("src/SecondConsumer.java")),
                owner.intent().workGroupId());
        assertTrue(secondRequester.acquired());
        AgentResponse secondBound = new ContractConsumerService().bind(new ContractConsumerService.BindRequest(controlRoot, "codex", "local-capability-requester-two",
                "tasktracker.domain", ResourceSelector.pathExact("src/Domain.java"), 1));
        assertEquals(AgentStatus.READY, secondBound.status(), secondBound.toJson());
        AgentResponse afterSecond = nextActions.getNextAction(new NextActionRequest(controlRoot,
                "claude",
                "local-capability-owner"));
        assertEquals(AgentStatus.READY, afterSecond.status(), afterSecond.toJson());
        assertFalse(afterSecond.toJson().contains("CAPABILITY_CONTRACT_AMBIGUOUS"), afterSecond.toJson());
        assertEquals(contract.purpose(), ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) afterSecond.result()).get("resolvedDependencies")).get("tasktracker.domain")).get("contract")).get("purpose"));
        assertEquals(2, new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).capabilityRequestProjection().records().size());
    }

    @Test
    void requesterDoesNotReceiveItsOwnCapabilityRequestAsOwnerAction() throws Exception {
        prepareSessionAndTrust("codex", "requester-owner-filter");
        prepareSessionAndTrust("claude", "requester-owner-filter-owner");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var requester = collaboration.announce(controlRoot,
                "codex",
                "requester-owner-filter",
                "Implement task-tracker service",
                "Use the domain capability",
                List.of(ResourceSelector.pathExact("src/service")),
                null,
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of("tasktracker.domain"));
        collaboration.announce(controlRoot,
                "claude",
                "requester-owner-filter-owner",
                "Implement task-tracker domain",
                "Provide the domain capability",
                List.of(ResourceSelector.pathExact("src/domain")),
                requester.intent().workGroupId(),
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of());

        AgentResponse requestResponse = new ContractConsumerService().requestChange(new ContractConsumerService.ChangeRequest(controlRoot,
                "codex",
                "requester-owner-filter",
                ResourceSelector.pathExact("src/domain"),
                "Create TODO tasks and enforce valid status transitions"));
        assertEquals(AgentStatus.WAITING, requestResponse.status(), requestResponse.toJson());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, requestResponse.reason(), requestResponse.toJson());

        AgentResponse requesterNext = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "requester-owner-filter"));
        assertNotEquals(AgentNextAction.RESPOND_COORDINATION, requesterNext.nextAction(), requesterNext.toJson());
        assertEquals(1, ((List<?>) ((Map<?, ?>) requesterNext.result()).get("yourChangeRequests")).size(), requesterNext.toJson());

        AgentResponse ownerNext = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "claude", "requester-owner-filter-owner"));
        assertEquals(AgentNextAction.RESPOND_COORDINATION, ownerNext.nextAction(), ownerNext.toJson());
        assertEquals(1, ((List<?>) ((Map<?, ?>) ownerNext.result()).get("changeRequests")).size(), ownerNext.toJson());
    }

    @Test
    void sameProviderRequesterDoesNotReceiveCapabilityOwnerAction() throws Exception {
        prepareSessionAndTrust("codex", "same-provider-requester");
        prepareSessionAndTrust("codex", "same-provider-owner");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var requester = collaboration.announce(controlRoot,
                "codex",
                "same-provider-requester",
                "Implement task-tracker service",
                "Use the domain capability",
                List.of(ResourceSelector.pathExact("src/service")),
                null,
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of("tasktracker.domain"));
        collaboration.announce(controlRoot,
                "codex",
                "same-provider-owner",
                "Implement task-tracker domain",
                "Provide the domain capability",
                List.of(ResourceSelector.pathExact("src/domain")),
                requester.intent().workGroupId(),
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of());

        AgentResponse requestResponse = new ContractConsumerService().requestChange(new ContractConsumerService.ChangeRequest(controlRoot,
                "codex",
                "same-provider-requester",
                ResourceSelector.pathExact("src/domain"),
                "Create TODO tasks and enforce valid status transitions"));
        assertEquals(AgentStatus.WAITING, requestResponse.status(), requestResponse.toJson());

        AgentResponse requesterNext = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "same-provider-requester"));
        assertNotEquals(AgentNextAction.RESPOND_COORDINATION, requesterNext.nextAction(), requesterNext.toJson());

        AgentResponse ownerNext = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "same-provider-owner"));
        assertEquals(AgentNextAction.RESPOND_COORDINATION, ownerNext.nextAction(), ownerNext.toJson());
        String changeRequestId = (String) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) ownerNext.result()).get("changeRequests")).getFirst()).get("changeRequest");

        CapabilityContract contract = new CapabilityContract("Task title and description",
                "Task and repository contract",
                List.of("Create TODO tasks", "Enforce valid status transitions"),
                List.of("Invalid input is rejected"));
        AgentResponse published = new ContractOwnerService().publish(new ContractOwnerService.PublishRequest(controlRoot, "codex", "same-provider-owner",
                List.of(ResourceSelector.pathExact("src/domain")), contract, List.of(UUID.fromString(changeRequestId))));
        assertEquals(AgentStatus.COMPLETED, published.status(), published.toJson());
        AgentResponse bound = new ContractConsumerService().bind(new ContractConsumerService.BindRequest(controlRoot, "codex", "same-provider-requester", "tasktracker.domain",
                ResourceSelector.pathExact("src/domain"), 1));
        assertEquals(AgentStatus.READY, bound.status(), bound.toJson());
        String handle = (String) ((Map<?, ?>) bound.result()).get("capabilityRequestHandle");

        AgentResponse ownerAfterAccept = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "same-provider-owner"));
        assertEquals(AgentStatus.READY, ownerAfterAccept.status(), ownerAfterAccept.toJson());
        assertNull(ownerAfterAccept.nextAction());
        assertEquals(handle, ((Map<?, ?>) ownerAfterAccept.result()).get("capabilityRequestHandle"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) ownerAfterAccept.result()).get("implementationPending"));
    }

    @Test
    void acceptedCapabilityOwnerNeedsExplicitCompletionPollToPublishAndRequesterCannotPublish() throws Exception {
        prepareSessionAndTrust("codex", "publication-requester");
        prepareSessionAndTrust("codex", "publication-owner");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var requester = collaboration.announce(controlRoot,
                "codex",
                "publication-requester",
                "Implement a task-tracker service",
                "Use the accepted task-tracker contract",
                List.of(ResourceSelector.pathExact("src/Consumer.java")),
                null,
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of("tasktracker.domain"));
        var owner = collaboration.announce(controlRoot,
                "codex",
                "publication-owner",
                "Implement task-tracker domain",
                "Publish the task-tracker capability",
                List.of(ResourceSelector.pathExact("src/Product.java")),
                requester.intent().workGroupId());

        CapabilityContract contract = new CapabilityContract("Task title and description",
                "Task and repository contract",
                List.of("Create TODO tasks", "Enforce valid status transitions"),
                List.of("Invalid input is rejected"));
        String handle = agreeContract("codex", "publication-owner", "codex", "publication-requester", "tasktracker.domain", "src/Product.java", contract);

        AgentNextActionService nextActions = new AgentNextActionService();
        AgentResponse ownerInbox = nextActions.getNextAction(new NextActionRequest(controlRoot, "codex", "publication-owner"));
        Map<?, ?> ownerInboxResult = (Map<?, ?>) ownerInbox.result();
        assertEquals(handle, ownerInboxResult.get("capabilityRequestHandle"), ownerInbox.toJson());
        long contractRevision = ((Number) ownerInboxResult.get("contractRevision")).longValue();

        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var ownerBinding = bindings.find(location, "codex", "publication-owner").orElseThrow();
        Path ownerWorktree = Path.of(ownerBinding.worktreePath());
        Files.writeString(ownerWorktree.resolve("src/Product.java"), "public class Product { int count() { return 1; } }\n");

        AgentResponse ordinaryOwnerPoll = nextActions.getNextAction(new NextActionRequest(controlRoot, "codex", "publication-owner"));
        AgentResponse repeatedOwnerPoll = nextActions.getNextAction(new NextActionRequest(controlRoot, "codex", "publication-owner"));
        assertEquals(AgentStatus.READY, ordinaryOwnerPoll.status(), ordinaryOwnerPoll.toJson());
        assertNull(ordinaryOwnerPoll.nextAction(), ordinaryOwnerPoll.toJson());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) ordinaryOwnerPoll.result()).get("implementationPending"));
        assertNull(recommendedTool(ordinaryOwnerPoll), ordinaryOwnerPoll.toJson());
        assertNull(recommendedTool(repeatedOwnerPoll), repeatedOwnerPoll.toJson());

        AgentResponse requesterCompletionPoll = nextActions.getNextAction(new NextActionRequest(controlRoot, "codex", "publication-requester", true));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) requesterCompletionPoll.result()).get("contractAccepted"), requesterCompletionPoll.toJson());
        assertNull(recommendedTool(requesterCompletionPoll), requesterCompletionPoll.toJson());

        AgentResponse ownerCompletionPoll = nextActions.getNextAction(new NextActionRequest(controlRoot, "codex", "publication-owner", true));
        assertEquals(AgentStatus.READY, ownerCompletionPoll.status(), ownerCompletionPoll.toJson());
        assertEquals("publish_capability_implementation", ownerCompletionPoll.nextAction().value(), ownerCompletionPoll.toJson());
        Map<?, ?> publicationArguments = recommendedArguments(ownerCompletionPoll);
        assertEquals(handle, publicationArguments.get("capabilityRequestHandle"));
        assertEquals(contractRevision, ((Number) publicationArguments.get("contractRevision")).longValue());
        assertEquals(owner.intent().intentId().toString(), publicationArguments.get("intentId"));
        assertEquals(owner.intent().version(), ((Number) publicationArguments.get("claimEpoch")).longValue());
        assertTrue(publicationArguments.get("expectedTreeHash") instanceof String hash && !hash.isBlank(), ownerCompletionPoll.toJson());
    }

    private static Object recommendedTool(AgentResponse response) {
        Object workflow = ((Map<?, ?>) response.result()).get("workflow");
        return workflow instanceof Map<?, ?> map ? map.get("recommendedTool") : null;
    }

    private static Map<?, ?> recommendedArguments(AgentResponse response) {
        Object workflow = ((Map<?, ?>) response.result()).get("workflow");
        Object arguments = workflow instanceof Map<?, ?> map ? map.get("arguments") : null;
        return arguments instanceof Map<?, ?> map ? map : Map.of();
    }

    @Test
    void testSurfacesNeedsCapabilityAndPrioritizesSafetyFailure() throws Exception {
        prepareSessionAndTrust("codex", "conn-na-2");

        // Write synthetic coordination items file
        Path coordDir = controlRoot.resolve(".synesis/local/coordination");
        Files.createDirectories(coordDir);

        List<Object> items = List.of(java.util.Map.of("type", "NEEDS_CAPABILITY", "capability", "catalog.product-query", "workerId", "codex"), java.util.Map.of("type", "SAFETY_FAILURE", "workerId", "codex"));
        Files.writeString(coordDir.resolve("items.json"), ProviderJson.write(items));

        AgentNextActionService service = new AgentNextActionService();
        NextActionRequest req = new NextActionRequest(controlRoot, "codex", "conn-na-2");

        AgentResponse response = service.getNextAction(req);
        // SAFETY_FAILURE is highest priority
        assertEquals(AgentStatus.FAILED, response.status());
    }

    @Test
    void testSurfacesOwnerRequestConciselyAndFiltersOtherWorkers() throws Exception {
        prepareSessionAndTrust("claude", "conn-na-3");

        Path coordDir = controlRoot.resolve(".synesis/local/coordination");
        Files.createDirectories(coordDir);

        List<Object> items = List.of(java.util.Map.of("type", "OWNER_REQUEST", "capability", "catalog.product-query", "workerId", "claude", "details", java.util.Map.of("inputs", "query", "output", "result")),
                java.util.Map.of("type", "NEEDS_CAPABILITY", "capability", "other.service", "workerId", "other-worker"));
        Files.writeString(coordDir.resolve("items.json"), ProviderJson.write(items));

        AgentNextActionService service = new AgentNextActionService();
        NextActionRequest req = new NextActionRequest(controlRoot, "claude", "conn-na-3");

        AgentResponse response = service.getNextAction(req);
        assertEquals(AgentStatus.WAITING, response.status());

        String json = response.toJson();
        assertTrue(json.contains("owner_request_pending"));
        assertTrue(json.contains("catalog.product-query"));
        assertFalse(json.contains("other.service")); // Filtered out item for other worker
        assertFalse(json.contains("workerId")); // No worker/session IDs leaked
    }

    @Test
    void testExcludesObsoleteAndCompletedItems() throws Exception {
        prepareSessionAndTrust("codex", "conn-na-4");

        Path coordDir = controlRoot.resolve(".synesis/local/coordination");
        Files.createDirectories(coordDir);

        List<Object> items = List.of(java.util.Map.of("type", "NEEDS_CAPABILITY", "capability", "old.cap", "workerId", "codex", "completed", true),
                java.util.Map.of("type", "VALIDATION_REQUIRED", "capability", "old.cap2", "workerId", "codex", "obsolete", true));
        Files.writeString(coordDir.resolve("items.json"), ProviderJson.write(items));

        AgentNextActionService service = new AgentNextActionService();
        NextActionRequest req = new NextActionRequest(controlRoot, "codex", "conn-na-4");

        AgentResponse response = service.getNextAction(req);
        assertEquals(AgentStatus.READY, response.status());
        assertTrue(response.toJson().contains("\"pending\":0"));
    }

    @Test
    void unclaimedSessionCannotReceiveReviewAdmissionWithoutExplicitRole() throws Exception {
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        new org.synesis.workspace.application.provider.ProviderManualService().install("claude");

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new SessionResolutionRequest(controlRoot, "codex", "claim-owner", null, false));
        sessionService.ensureSession(new SessionResolutionRequest(controlRoot, "claude", "claim-contender", null, false));

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.announce(controlRoot, "codex", "claim-owner", "Implement source", "Publish source", List.of(ResourceSelector.pathExact("src/task_tracker.py")));

        AgentNextActionService service = new AgentNextActionService();
        AgentResponse response = service.getNextAction(new NextActionRequest(controlRoot, "claude", "claim-contender"));

        assertEquals(AgentStatus.BLOCKED, response.status());
        assertFalse(response.toJson().contains("REVIEW_ADMISSION_REQUIRED"), response.toJson());
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeReviewerIntentStillReceivesReviewAdmissionForSharedWorkGroup() throws Exception {
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        new org.synesis.workspace.application.provider.ProviderManualService().install("claude");

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new SessionResolutionRequest(controlRoot, "codex", "active-owner", null, false));
        sessionService.ensureSession(new SessionResolutionRequest(controlRoot, "claude", "active-reviewer", null, false));

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var ownerClaim = collaboration.announce(controlRoot, "codex", "active-owner", "Implement source", "Publish source", List.of(ResourceSelector.pathExact("src/task_tracker.py")));
        var reviewerClaim = collaboration.announce(controlRoot,
                "claude",
                "active-reviewer",
                "Review source",
                "Validate the published source",
                List.of(ResourceSelector.pathExact("tests/test_task_tracker.py")),
                ownerClaim.intent().workGroupId(),
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/task_tracker.py")));
        assertEquals(ownerClaim.intent().workGroupId(), reviewerClaim.intent().workGroupId());

        AgentNextActionService service = new AgentNextActionService();
        AgentResponse response = service.getNextAction(new NextActionRequest(controlRoot, "claude", "active-reviewer"));

        assertEquals(AgentStatus.READY, response.status());
        assertEquals(AgentReason.VALIDATION_REQUIRED, response.reason());
        assertEquals(AgentNextAction.REQUEST_COORDINATION, response.nextAction());
        assertTrue(response.toJson().contains("REVIEW_ADMISSION_REQUIRED"));
        assertTrue(response.toJson().contains("work_group_join"));
        assertTrue(response.toJson().contains(ownerClaim.intent().workGroupId().toString()));
        assertTrue(response.toJson().contains(ownerClaim.intent().intentId().toString()));
        Map<String, Object> result = (Map<String, Object>) response.result();
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("request_coordination", workflow.get("recommendedTool"));
        Map<String, Object> arguments = (Map<String, Object>) workflow.get("arguments");
        assertEquals("work_group_join", arguments.get("kind"));
        Map<String, Object> payload = (Map<String, Object>) arguments.get("payload");
        assertEquals(ownerClaim.intent().workGroupId().toString(), payload.get("workGroupId"));
        assertEquals(ownerClaim.intent().intentId().toString(), payload.get("intentId"));

        AgentResponse ownerResponse = service.getNextAction(new NextActionRequest(controlRoot, "codex", "active-owner"));
        assertFalse(ownerResponse.toJson().contains("REVIEW_ADMISSION_REQUIRED"));
    }

    /**
     * A reviewer lane is admitted only beside a producer of another participant. The tests that put one participant
     * on both sides of a review need that other producer on the log first, and release it once the reviewer is in.
     *
     * @return the other producer's lane
     */
    private static UUID appendOtherProducer(PredictionEventStore store,
            org.synesis.link.identity.NodeIdentity identity,
            WorkIntent producer) throws Exception {
        UUID otherId = UUID.randomUUID();
        WorkIntent other = new WorkIntent(otherId,
                producer.projectId(),
                "agt_other_producer",
                "codex",
                UUID.randomUUID(),
                "Implement the rest",
                "Publish the rest",
                producer.baseCommit(),
                List.of(ResourceSelector.pathExact("src/Other.java")),
                1,
                producer.workGroupId(),
                WorkIntent.defaultAuthorityLineage(otherId),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of());
        store.append(otherId, PredictionEventType.WORK_INTENT_ANNOUNCED, identity.nodeId(), CollaborationCodec.encodeIntent(other), identity);
        return otherId;
    }

    @Test
    @SuppressWarnings("unchecked")
    void selfReviewGrantIsNotProjectedAsAnExecutableReviewAction() throws Exception {
        prepareSessionAndTrust("codex", "same-participant-review");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var producer = collaboration.announce(controlRoot, "codex", "same-participant-review", "Implement source", "Publish source", List.of(ResourceSelector.pathExact("src/Product.java")));

        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var binding = bindings.find(location, "codex", "same-participant-review").orElseThrow();
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID otherProducer = appendOtherProducer(store, identity, producer.intent());
        UUID reviewerIntentId = UUID.randomUUID();
        WorkIntent reviewer = new WorkIntent(reviewerIntentId,
                producer.intent().projectId(),
                participant,
                "codex",
                UUID.randomUUID(),
                "Review source",
                "Review the immutable source snapshot",
                producer.intent().baseCommit(),
                List.of(ResourceSelector.pathExact("tests/ProductTest.java")),
                1,
                producer.intent().workGroupId(),
                WorkIntent.defaultAuthorityLineage(reviewerIntentId),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.REVIEWER,
                List.of(),
                List.of());
        store.append(reviewer.intentId(), PredictionEventType.WORK_INTENT_ANNOUNCED, identity.nodeId(), CollaborationCodec.encodeIntent(reviewer), identity);
        store.append(otherProducer, PredictionEventType.WORK_INTENT_RELEASED, identity.nodeId(), CollaborationCodec.encodeRelease(otherProducer), identity);
        assertEquals(producer.intent().workGroupId(), reviewer.workGroupId());
        LaneGrant selfGrant = new LaneGrant(UUID.randomUUID(), producer.intent().workGroupId(), producer.intent().intentId(), participant, producer.intent().version(), true);
        store.append(selfGrant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(), CollaborationCodec.encodeLaneGrant(selfGrant), identity);

        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "same-participant-review"));
        Map<String, Object> result = (Map<String, Object>) response.result();
        Object projectedReviews = result.get("reviewActions");
        if (projectedReviews instanceof List<?> reviews) {
            assertTrue(reviews.isEmpty(), response.toJson());
        }
        assertFalse(response.toJson().contains("review_decision"), response.toJson());
        assertFalse(response.toJson().contains("REVIEW_ADMISSION_REQUIRED"), response.toJson());
    }

    @Test
    void reviewerSelfGrantDoesNotBecomePendingReviewObligation() throws Exception {
        prepareSessionAndTrust("codex", "reviewer-continuation-obligation");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var producer = collaboration.announce(controlRoot, "codex", "reviewer-continuation-obligation", "Implement source", "Publish source", List.of(ResourceSelector.pathExact("src/Product.java")));

        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var binding = bindings.find(location, "codex", "reviewer-continuation-obligation").orElseThrow();
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());

        UUID otherProducer = appendOtherProducer(store, identity, producer.intent());
        UUID reviewerIntentId = UUID.randomUUID();
        WorkIntent reviewer = new WorkIntent(reviewerIntentId,
                producer.intent().projectId(),
                participant,
                "codex",
                UUID.randomUUID(),
                "Review source",
                "Review the snapshot",
                producer.intent().baseCommit(),
                List.of(ResourceSelector.pathExact("tests/ProductTest.java")),
                1,
                producer.intent().workGroupId(),
                WorkIntent.defaultAuthorityLineage(reviewerIntentId),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.REVIEWER,
                List.of(),
                List.of());
        store.append(reviewer.intentId(), PredictionEventType.WORK_INTENT_ANNOUNCED, identity.nodeId(), CollaborationCodec.encodeIntent(reviewer), identity);
        store.append(otherProducer, PredictionEventType.WORK_INTENT_RELEASED, identity.nodeId(), CollaborationCodec.encodeRelease(otherProducer), identity);

        LaneGrant continuationGrant = new LaneGrant(UUID.randomUUID(), reviewer.workGroupId(), reviewer.intentId(), participant, reviewer.version(), true);
        store.append(continuationGrant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(), CollaborationCodec.encodeLaneGrant(continuationGrant), identity);

        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "reviewer-continuation-obligation"));
        assertFalse(response.toJson().contains("REVIEWER_PENDING"), response.toJson());
    }

    @Test
    @SuppressWarnings("unchecked")
    void predecessorReviewPendingSnapshotDoesNotFenceSuccessorRevision() throws Exception {
        prepareSessionAndTrust("codex", "predecessor-review-pending");
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var predecessor = collaboration.announce(controlRoot, "codex", "predecessor-review-pending", "Implement source", "Publish source", List.of(ResourceSelector.pathExact("src/Product.java")));

        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var predecessorBinding = bindings.find(location, "codex", "predecessor-review-pending").orElseThrow();
        String predecessorParticipant = WorkspaceCollaborationService.participantHandle(predecessorBinding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        SnapshotProvenance predecessorProvenance = new SnapshotProvenance(predecessor.intent().workGroupId(),
                predecessor.intent().intentId(),
                predecessorParticipant,
                predecessorBinding.sessionId(),
                predecessor.intent().version(),
                List.of(),
                List.of(),
                List.of("PATH_EXACT:src/Product.java"),
                "refs/synesis/snap_predecessor_review_pending",
                "predecessor-review-pending-integrity");
        TaskSnapshotPayload predecessorSnapshot = new TaskSnapshotPayload(predecessor.intent().taskId(),
                "snap_predecessor_review_pending",
                identity.nodeId(),
                predecessorBinding.supervisorId(),
                predecessorBinding.workerId(),
                predecessorBinding.sessionId(),
                predecessorBinding.baseCommit(),
                predecessorBinding.baseCommit(),
                List.of("src/Product.java"),
                List.of(),
                "Published predecessor",
                predecessorProvenance,
                true);
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), predecessorSnapshot.encode(), identity);

        RecoverySnapshotService.Snapshot recovery = new RecoverySnapshotService().materialize(location, predecessorBinding.sessionId());
        WorkIntentService intentService = new WorkIntentService(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()), identity);
        intentService.suspend(predecessorParticipant);
        String recoveryReference = recovery.root().toString() + "#" + recovery.contentHash();
        intentService.holdRecovery(predecessorParticipant, recoveryReference);

        prepareSessionAndTrust("codex", "successor-review-pending");
        var successorBinding = bindings.find(location, "codex", "successor-review-pending").orElseThrow();
        String successorParticipant = WorkspaceCollaborationService.participantHandle(successorBinding.sessionId());
        WorkIntent successor = new WorkIntent(UUID.randomUUID(),
                location.projectId(),
                successorParticipant,
                "codex",
                predecessor.intent().taskId(),
                predecessor.intent().goal(),
                predecessor.intent().acceptance(),
                predecessor.intent().baseCommit(),
                predecessor.intent().selectors(),
                predecessor.intent().version() + 1,
                predecessor.intent().workGroupId(),
                predecessor.intent().authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                predecessor.intent().reviewTargetSelectors(),
                predecessor.intent().knownDependencies());
        UUID continuationGrantId = UUID.randomUUID();
        new org.synesis.coordination.application.WorkGroupService(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()), identity).issue(new LaneGrant(continuationGrantId,
                predecessor.intent().workGroupId(),
                successor.intentId(),
                successorParticipant,
                predecessor.intent().version(),
                true));
        intentService.continueFromRecovery(new LaneContinuation(continuationGrantId,
                predecessor.intent().intentId(),
                successor,
                predecessorParticipant,
                successorParticipant,
                predecessor.intent().version(),
                recoveryReference));

        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "successor-review-pending"));
        Map<String, Object> result = (Map<String, Object>) response.result();
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("IMPLEMENT", workflow.get("type"), response.toJson());
    }

    @Test
    @SuppressWarnings("unchecked")
    void latePredecessorReviewGrantCannotFenceSuccessorReviewAction() throws Exception {
        prepareSessionAndTrust("codex", "late-review-producer");
        prepareSessionAndTrust("codex", "late-review-successor");
        prepareSessionAndTrust("codex", "late-reviewer");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var predecessor = collaboration.announce(controlRoot, "codex", "late-review-producer", "Implement source", "Publish source", List.of(ResourceSelector.pathExact("src/Product.java")));
        var reviewerClaim = collaboration.announce(controlRoot,
                "codex",
                "late-reviewer",
                "Review source",
                "Validate source",
                List.of(ResourceSelector.pathExact("tests/ProductTest.java")),
                predecessor.intent().workGroupId(),
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/Product.java")));

        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var predecessorBinding = bindings.find(location, "codex", "late-review-producer").orElseThrow();
        var successorBinding = bindings.find(location, "codex", "late-review-successor").orElseThrow();
        var reviewerBinding = bindings.find(location, "codex", "late-reviewer").orElseThrow();
        String predecessorParticipant = WorkspaceCollaborationService.participantHandle(predecessorBinding.sessionId());
        String successorParticipant = WorkspaceCollaborationService.participantHandle(successorBinding.sessionId());
        String reviewerParticipant = WorkspaceCollaborationService.participantHandle(reviewerBinding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        LaneGrant originalGrant = new LaneGrant(UUID.randomUUID(), predecessor.intent().workGroupId(), predecessor.intent().intentId(), reviewerParticipant, predecessor.intent().version(), true);
        store.append(originalGrant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(), CollaborationCodec.encodeLaneGrant(originalGrant), identity);

        RecoverySnapshotService.Snapshot recovery = new RecoverySnapshotService().materialize(location, predecessorBinding.sessionId());
        WorkIntentService intentService = new WorkIntentService(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()), identity);
        intentService.suspend(predecessorParticipant);
        String recoveryReference = recovery.root().toString() + "#" + recovery.contentHash();
        intentService.holdRecovery(predecessorParticipant, recoveryReference);

        WorkIntent successor = new WorkIntent(UUID.randomUUID(),
                location.projectId(),
                successorParticipant,
                "codex",
                predecessor.intent().taskId(),
                predecessor.intent().goal(),
                predecessor.intent().acceptance(),
                predecessor.intent().baseCommit(),
                predecessor.intent().selectors(),
                predecessor.intent().version() + 1,
                predecessor.intent().workGroupId(),
                predecessor.intent().authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                predecessor.intent().reviewTargetSelectors(),
                predecessor.intent().knownDependencies());
        UUID continuationGrantId = UUID.randomUUID();
        new org.synesis.coordination.application.WorkGroupService(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()), identity).issue(new LaneGrant(continuationGrantId,
                predecessor.intent().workGroupId(),
                successor.intentId(),
                successorParticipant,
                predecessor.intent().version(),
                true));
        intentService.continueFromRecovery(new LaneContinuation(continuationGrantId,
                predecessor.intent().intentId(),
                successor,
                predecessorParticipant,
                successorParticipant,
                predecessor.intent().version(),
                recoveryReference));

        UUID lateGrantId = UUID.randomUUID();
        LaneGrant lateGrant = new LaneGrant(lateGrantId, predecessor.intent().workGroupId(), predecessor.intent().intentId(), reviewerParticipant, predecessor.intent().version(), true);
        PredictionEventStore afterContinuation = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        afterContinuation.append(lateGrantId, PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(), CollaborationCodec.encodeLaneGrant(lateGrant), identity);

        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "late-reviewer"));
        Map<String, Object> result = (Map<String, Object>) response.result();
        List<Map<String, Object>> reviewActions = (List<Map<String, Object>>) result.get("reviewActions");
        assertTrue(reviewActions.stream().noneMatch(action -> lateGrantId.toString().equals(action.get("grantId"))), response.toJson());
        assertTrue(reviewActions.stream().anyMatch(action -> {
            Map<String, Object> payload = (Map<String, Object>) action.get("nextProtocolPayload");
            return successor.intentId().toString().equals(payload.get("intentId"));
        }), response.toJson());
        assertEquals(reviewerClaim.intent().workGroupId(), successor.workGroupId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactPendingSnapshotGrantRemainsProjectedAfterProducerContinuation() throws Exception {
        prepareSessionAndTrust("codex", "snapshot-review-producer");
        prepareSessionAndTrust("codex", "snapshot-review-successor");
        prepareSessionAndTrust("claude", "snapshot-reviewer");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        UUID groupId = UUID.randomUUID();
        collaboration.createWorkGroup(controlRoot, groupId, "Publish a product change", "Independently review the immutable change", ReviewPolicy.mandatoryIndependent());
        var predecessor = collaboration.announce(controlRoot, "codex", "snapshot-review-producer", "Implement source", "Publish source", List.of(ResourceSelector.pathExact("src/Product.java")), groupId);
        collaboration.announce(controlRoot,
                "claude",
                "snapshot-reviewer",
                "Review source",
                "Validate the immutable source",
                List.of(ResourceSelector.pathExact("tests/ProductTest.java")),
                groupId,
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/Product.java")));

        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var predecessorBinding = bindings.find(location, "codex", "snapshot-review-producer").orElseThrow();
        var successorBinding = bindings.find(location, "codex", "snapshot-review-successor").orElseThrow();
        var reviewerBinding = bindings.find(location, "claude", "snapshot-reviewer").orElseThrow();
        String predecessorParticipant = WorkspaceCollaborationService.participantHandle(predecessorBinding.sessionId());
        String successorParticipant = WorkspaceCollaborationService.participantHandle(successorBinding.sessionId());
        String reviewerParticipant = WorkspaceCollaborationService.participantHandle(reviewerBinding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        String snapshotId = "snap_snapshot_review_after_producer_continuation";
        appendMandatorySnapshot(store, identity, predecessorBinding, predecessor.intent(), snapshotId, "src/Product.java");

        LaneGrant reviewGrant = new LaneGrant(UUID.randomUUID(), groupId, predecessor.intent().intentId(), reviewerParticipant, predecessor.intent().version(), true, snapshotId);
        new org.synesis.coordination.application.WorkGroupService(store, identity).issue(reviewGrant);

        RecoverySnapshotService.Snapshot recovery = new RecoverySnapshotService().materialize(location, predecessorBinding.sessionId());
        WorkIntentService intentService = new WorkIntentService(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()), identity);
        intentService.suspend(predecessorParticipant);
        String recoveryReference = recovery.root().toString() + "#" + recovery.contentHash();
        intentService.holdRecovery(predecessorParticipant, recoveryReference);

        WorkIntent successor = new WorkIntent(UUID.randomUUID(),
                location.projectId(),
                successorParticipant,
                "codex",
                predecessor.intent().taskId(),
                predecessor.intent().goal(),
                predecessor.intent().acceptance(),
                predecessor.intent().baseCommit(),
                predecessor.intent().selectors(),
                predecessor.intent().version() + 1,
                groupId,
                predecessor.intent().authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                predecessor.intent().reviewTargetSelectors(),
                predecessor.intent().knownDependencies());
        UUID continuationGrantId = UUID.randomUUID();
        new org.synesis.coordination.application.WorkGroupService(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()), identity).issue(new LaneGrant(continuationGrantId,
                groupId,
                successor.intentId(),
                successorParticipant,
                predecessor.intent().version(),
                true));
        intentService.continueFromRecovery(new LaneContinuation(continuationGrantId,
                predecessor.intent().intentId(),
                successor,
                predecessorParticipant,
                successorParticipant,
                predecessor.intent().version(),
                recoveryReference));

        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "claude", "snapshot-reviewer"));

        assertEquals(AgentStatus.READY, response.status(), response.toJson());
        assertEquals(AgentReason.VALIDATION_REQUIRED, response.reason(), response.toJson());
        assertEquals(AgentNextAction.REQUEST_COORDINATION, response.nextAction(), response.toJson());
        Map<String, Object> result = (Map<String, Object>) response.result();
        List<Map<String, Object>> reviewActions = (List<Map<String, Object>>) result.get("reviewActions");
        Map<String, Object> reviewAction = reviewActions.stream().filter(action -> {
            Map<String, Object> actionPayload = (Map<String, Object>) action.get("nextProtocolPayload");
            return reviewGrant.grantId().toString().equals(actionPayload.get("grantId"));
        }).findFirst().orElseThrow(() -> new AssertionError(response.toJson()));
        assertEquals("GRANT_AVAILABLE", reviewAction.get("state"));
        Map<String, Object> snapshot = (Map<String, Object>) reviewAction.get("snapshot");
        assertEquals(snapshotId, snapshot.get("snapshotId"));
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("request_coordination", workflow.get("recommendedTool"));
        Map<String, Object> arguments = (Map<String, Object>) workflow.get("arguments");
        assertEquals("work_group_join", arguments.get("kind"));
        Map<String, Object> payload = (Map<String, Object>) arguments.get("payload");
        assertEquals(reviewGrant.grantId().toString(), payload.get("grantId"));
        assertEquals(predecessor.intent().intentId().toString(), payload.get("intentId"));
        assertEquals(reviewerParticipant, payload.get("targetParticipant"));
        assertEquals(predecessor.intent().version(), ((Number) payload.get("claimEpoch")).longValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingReviewAdmissionProjectsWaitInsteadOfReplayingRequest() throws Exception {
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        prepareSessionAndTrust("codex", "pending-review-owner");
        prepareSessionAndTrust("codex", "pending-review-reviewer");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var ownerClaim = collaboration.announce(controlRoot, "codex", "pending-review-owner", "Implement source", "Review the completed source snapshot", List.of(ResourceSelector.pathExact("src/Product.java")));
        var reviewerClaim = collaboration.announce(controlRoot,
                "codex",
                "pending-review-reviewer",
                "Review source",
                "Validate the completed source snapshot",
                List.of(ResourceSelector.pathExact("tests/ProductTest.java")),
                ownerClaim.intent().workGroupId(),
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/Product.java")));
        assertEquals(ownerClaim.intent().workGroupId(), reviewerClaim.intent().workGroupId());

        AgentNextActionService service = new AgentNextActionService();
        AgentResponse initial = service.getNextAction(new NextActionRequest(controlRoot, "codex", "pending-review-reviewer"));
        assertEquals(AgentNextAction.REQUEST_COORDINATION, initial.nextAction());
        assertTrue(initial.toJson().contains("REVIEW_ADMISSION_REQUIRED"), initial.toJson());

        CoordinationRequest request = collaboration.request(controlRoot, "codex", "pending-review-reviewer", ownerClaim.intent().intentId(), CoordinationRequest.Kind.REVIEW, "Review the completed source snapshot");

        AgentResponse pending = service.getNextAction(new NextActionRequest(controlRoot, "codex", "pending-review-reviewer"));
        assertEquals(AgentStatus.WAITING, pending.status());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, pending.reason());
        assertEquals(AgentNextAction.WAIT, pending.nextAction());
        Map<String, Object> result = (Map<String, Object>) pending.result();
        assertEquals(Boolean.TRUE, result.get("reviewRequestPending"));
        Map<String, Object> reviewRequest = (Map<String, Object>) result.get("reviewRequest");
        assertEquals(request.requestId().toString(), reviewRequest.get("requestId"));
        assertEquals(ownerClaim.intent().intentId().toString(), reviewRequest.get("intentId"));
        assertEquals("wait", result.get("nextProtocolAction"));
        assertEquals("review_admission", result.get("nextProtocolKind"));
        Map<String, Object> payload = (Map<String, Object>) result.get("nextProtocolPayload");
        assertEquals(request.requestId().toString(), payload.get("requestId"));
        assertEquals(ownerClaim.intent().workGroupId().toString(), payload.get("workGroupId"));
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("WAIT", workflow.get("type"));
        assertEquals("get_next_action", workflow.get("recommendedTool"));
        assertEquals(Map.of(), workflow.get("arguments"));
        assertFalse(pending.toJson().contains("REVIEW_ADMISSION_REQUIRED"), pending.toJson());
        assertTrue(((List<?>) result.get("reviewActions")).isEmpty(), pending.toJson());

        AgentResponse repeated = service.getNextAction(new NextActionRequest(controlRoot, "codex", "pending-review-reviewer"));
        Map<String, Object> repeatedResult = (Map<String, Object>) repeated.result();
        Map<String, Object> repeatedRequest = (Map<String, Object>) repeatedResult.get("reviewRequest");
        assertEquals(request.requestId().toString(), repeatedRequest.get("requestId"));
        assertEquals(AgentNextAction.WAIT, repeated.nextAction());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reviewerIntentTargetsThePublishedPeerSnapshot() throws Exception {
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        new org.synesis.workspace.application.provider.ProviderManualService().install("claude");

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new SessionResolutionRequest(controlRoot, "claude", "reviewer-first", null, false));
        sessionService.ensureSession(new SessionResolutionRequest(controlRoot, "codex", "owner-second", null, false));

        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var ownerBinding = bindings.find(location, "codex", "owner-second").orElseThrow();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var ownerClaim = collaboration.announce(controlRoot, "codex", "owner-second", "Implement source", "Publish the completed source", List.of(ResourceSelector.pathExact("src/task_tracker.py")));
        var reviewerClaim = collaboration.announce(controlRoot,
                "claude",
                "reviewer-first",
                "Review source",
                "Validate the published source",
                List.of(ResourceSelector.pathExact("tests/test_task_tracker.py")),
                ownerClaim.intent().workGroupId(),
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/task_tracker.py")));
        assertEquals(reviewerClaim.intent().workGroupId(), ownerClaim.intent().workGroupId());

        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        String ownerParticipant = WorkspaceCollaborationService.participantHandle(ownerBinding.sessionId());
        SnapshotProvenance provenance = new SnapshotProvenance(ownerClaim.intent().workGroupId(),
                ownerClaim.intent().intentId(),
                ownerParticipant,
                ownerBinding.sessionId(),
                1,
                List.of(),
                List.of(),
                List.of("PATH_EXACT:src/task_tracker.py"),
                "refs/synesis/snapshots/snap_reviewer_first",
                "reviewer-first-integrity");
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(ownerClaim.intent().taskId(),
                "snap_reviewer_first",
                identity.nodeId(),
                "supervisor",
                "worker",
                ownerBinding.sessionId(),
                ownerBinding.baseCommit(),
                ownerBinding.baseCommit(),
                List.of("src/task_tracker.py"),
                List.of(),
                "Published source",
                provenance,
                false);
        store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);

        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "claude", "reviewer-first"));

        assertEquals(AgentStatus.READY, response.status());
        assertEquals(AgentReason.VALIDATION_REQUIRED, response.reason());
        assertEquals(AgentNextAction.REQUEST_COORDINATION, response.nextAction());
        Map<String, Object> result = (Map<String, Object>) response.result();
        List<Map<String, Object>> reviewActions = (List<Map<String, Object>>) result.get("reviewActions");
        assertEquals(1, reviewActions.size());
        assertEquals("REVIEW_ADMISSION_REQUIRED", reviewActions.getFirst().get("state"));
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        Map<String, Object> arguments = (Map<String, Object>) workflow.get("arguments");
        Map<String, Object> payload = (Map<String, Object>) arguments.get("payload");
        assertEquals("request_coordination", workflow.get("recommendedTool"));
        assertEquals("work_group_join", arguments.get("kind"));
        assertEquals(ownerClaim.intent().workGroupId().toString(), payload.get("workGroupId"));
        assertEquals(ownerClaim.intent().intentId().toString(), payload.get("intentId"));
    }

    @Test
    void completedReviewerDoesNotProjectAdmissionWithoutAnActiveReviewerIntent() throws Exception {
        assertCompletedParticipantDoesNotAssumeReviewAuthority(WorkIntent.Role.REVIEWER);
    }

    @Test
    void completedProducerDoesNotProjectAdmissionWithoutAnActiveReviewerIntent() throws Exception {
        assertCompletedParticipantDoesNotAssumeReviewAuthority(WorkIntent.Role.PRODUCER);
    }

    private void assertCompletedParticipantDoesNotAssumeReviewAuthority(WorkIntent.Role role) throws Exception {
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new SessionResolutionRequest(controlRoot, "codex", "completed-reviewer", null, false));
        sessionService.ensureSession(new SessionResolutionRequest(controlRoot, "codex", "active-owner", null, false));

        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var completedBinding = bindings.find(location, "codex", "completed-reviewer").orElseThrow();
        bindings.find(location, "codex", "active-owner").orElseThrow();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        // A reviewer lane is only admitted into a group that already has a producer, so the owner announces first then.
        boolean reviewer = role == WorkIntent.Role.REVIEWER;
        org.synesis.coordination.domain.collaboration.ClaimResult ownerClaim = reviewer
                ? collaboration.announce(controlRoot, "codex", "active-owner", "Implement the sibling source", "Publish the completed source", List.of(ResourceSelector.pathExact("src/sibling.py")))
                : null;
        var completedClaim = collaboration.announce(controlRoot,
                "codex",
                "completed-reviewer",
                "Review the sibling implementation",
                "Review the immutable sibling snapshot",
                List.of(ResourceSelector.pathExact("tests/completed_review.py")),
                reviewer ? ownerClaim.intent().workGroupId() : null,
                role,
                reviewer ? List.of(ResourceSelector.pathExact("src/sibling.py")) : List.of());
        if (!reviewer) {
            ownerClaim = collaboration.announce(controlRoot,
                    "codex",
                    "active-owner",
                    "Implement the sibling source",
                    "Publish the completed source",
                    List.of(ResourceSelector.pathExact("src/sibling.py")),
                    completedClaim.intent().workGroupId());
        }

        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        SnapshotProvenance provenance = new SnapshotProvenance(completedClaim.intent().workGroupId(),
                completedClaim.intent().intentId(),
                WorkspaceCollaborationService.participantHandle(completedBinding.sessionId()),
                completedBinding.sessionId(),
                1,
                List.of(),
                List.of(),
                List.of("PATH_EXACT:tests/completed_review.py"),
                "refs/synesis/snapshots/snap_completed_review",
                "completed-review-integrity");
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(completedClaim.intent().taskId(),
                "snap_completed_review",
                identity.nodeId(),
                "supervisor",
                "worker",
                completedBinding.sessionId(),
                completedBinding.baseCommit(),
                completedBinding.baseCommit(),
                List.of("tests/completed_review.py"),
                List.of(),
                "Completed review lane",
                provenance,
                false);
        store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);
        collaboration.release(controlRoot, "codex", "completed-reviewer");
        assertTrue(bindings.complete(location, "codex", "completed-reviewer"));

        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(controlRoot, "codex", "completed-reviewer"));

        assertEquals(AgentStatus.WAITING, response.status(), response.toJson());
        assertEquals(AgentNextAction.WAIT, response.nextAction(), response.toJson());
        assertFalse(response.toJson().contains("REVIEW_ADMISSION_REQUIRED"), response.toJson());
        assertEquals("COMPLETED", bindings.find(location, "codex", "completed-reviewer").orElseThrow().status());
        assertEquals(WorkIntent.Status.ANNOUNCED,
                new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).collaborationProjection().intent(ownerClaim.intent().intentId()).orElseThrow().status());

        AgentResponse mutation = new org.synesis.workspace.application.workspace.mutation.WorkspacePatchService().applyPatch(new org.synesis.workspace.application.workspace.mutation.PatchRequest(controlRoot,
                "codex",
                "completed-reviewer",
                "src/forbidden.py",
                true,
                "print('must not mutate')\n",
                null,
                List.of()));
        assertEquals(AgentStatus.RETRY_REQUIRED, mutation.status());
        assertEquals(AgentReason.WORKSPACE_STALE, mutation.reason());
    }
}
