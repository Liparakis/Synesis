package org.synesis.workspace;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.agent.workflow.AgentNextActionService;
import org.synesis.workspace.application.agent.workflow.NextActionRequest;
import org.synesis.workspace.application.capability.CapabilityRequestService;
import org.synesis.workspace.application.capability.CapabilityResponseService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.test.ProviderTestSupport;

/**
 * Exercises capability request validation and durable response projection.
 */
class CapabilityNegotiationTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private ProjectApplicationService projectService;
    private ProviderSessionBindingService bindingService;
    private CapabilityRequestService requestService;
    private CapabilityResponseService responseService;
    private AgentNextActionService nextActionService;

    private static void git(Path root, String... args) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, args);
    }

    private static <T> List<T> concurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> pending = new ArrayList<>();
            for (Callable<T> call : calls) {
                pending.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : pending) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /** Replays the durable log from disk, which fails if conflicting events were ever appended. */
    private PredictionEventStore replayedStore() throws Exception {
        var location = projectService.locate(projectRoot);
        return new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
    }

    private CapabilityRequestRecord replayedRequest(String handle) throws Exception {
        return replayedStore().capabilityRequestProjection().findByHandle(handle).orElseThrow();
    }

    private String requestProductQuery(CapabilityContract contract) {
        AgentResponse response = requestService.describeRequiredCapability(new CapabilityRequestService.DescribeCapabilityRequest(projectRoot, "claude", "inst-1", "catalog.product-query", contract, null, null));
        assertEquals(AgentStatus.WAITING, response.status(), response.toJson());
        return (String) ((Map<?, ?>) response.result()).get("capabilityRequestHandle");
    }

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("test-project");
        Files.createDirectories(projectRoot);

        git(projectRoot, "init");
        git(projectRoot, "config", "user.name", "Test User");
        git(projectRoot, "config", "user.email", "test@example.com");
        Files.writeString(projectRoot.resolve("README.md"), "# Test Project\n");
        git(projectRoot, "add", ".");
        git(projectRoot, "commit", "-m", "Initial commit");

        projectService = new ProjectApplicationService();
        projectService.init(projectRoot);
        ProviderTestSupport.install(projectService.locate(projectRoot), "claude");
        ProviderTestSupport.install(projectService.locate(projectRoot), "codex");

        bindingService = new ProviderSessionBindingService();
        requestService = new CapabilityRequestService();
        responseService = new CapabilityResponseService();
        nextActionService = new AgentNextActionService();

        var location = projectService.locate(projectRoot);
        // Bind session for requester (claude) and owner (codex)
        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new SessionResolutionRequest(projectRoot, "claude", "inst-1", null, false));
        sessionService.ensureSession(new SessionResolutionRequest(projectRoot, "codex", "inst-2", null, false));

        var bindings1 = bindingService.list(location, "claude");
        if (!bindings1.isEmpty() && bindings1.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "claude", bindings1.getLast().sessionId(), Path.of(bindings1.getLast().worktreePath()));
        }
        var bindings2 = bindingService.list(location, "codex");
        if (!bindings2.isEmpty() && bindings2.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "codex", bindings2.getLast().sessionId(), Path.of(bindings2.getLast().worktreePath()));
        }

        // Assign semantic ownership for catalog.product-query to the bound codex session. The
        // owner scope must be that binding's own supervisor and worker: replies and
        // publication are authorized against the stored scope, not the shared node identity.
        String codexSupervisor = bindingService.find(location, "codex", "inst-2").orElseThrow().supervisorId();
        String codexWorker = bindingService.find(location, "codex", "inst-2").orElseThrow().workerId();
        var codexIdentity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID taskId = UUID.randomUUID();

        org.synesis.coordination.domain.task.CoordinationTask task = new org.synesis.coordination.domain.task.CoordinationTask(taskId,
                location.projectId(),
                "Product Query Task",
                "catalog.product-query",
                codexIdentity.nodeId(),
                codexSupervisor,
                codexWorker);
        CoordinationCommand cmd1 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.TASK_CREATED, codexIdentity.nodeId(), task.encoded(), codexIdentity);
        store.append(taskId, PredictionEventType.TASK_CREATED, codexIdentity.nodeId(), cmd1.encoded(), codexIdentity);

        org.synesis.coordination.domain.task.TaskClaim claim1 = new org.synesis.coordination.domain.task.TaskClaim(taskId, codexIdentity.nodeId(), codexSupervisor, codexWorker);
        CoordinationCommand cmd2 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.TASK_CLAIMED, codexIdentity.nodeId(), claim1.encoded(), codexIdentity);
        store.append(taskId, PredictionEventType.TASK_CLAIMED, codexIdentity.nodeId(), cmd2.encoded(), codexIdentity);

        OwnershipClaim claim2 = new OwnershipClaim(taskId, "catalog.product-query", codexIdentity.nodeId(), codexSupervisor, List.of("catalog"), 1L);
        CoordinationCommand cmd3 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.OWNERSHIP_CLAIMED, codexIdentity.nodeId(), claim2.encoded(), codexIdentity);
        store.append(taskId, PredictionEventType.OWNERSHIP_CLAIMED, codexIdentity.nodeId(), cmd3.encoded(), codexIdentity);

    }

    @Test
    @SuppressWarnings("ExtractMethodRecommender")
    void completeCapabilityNegotiationAcceptanceFlow() throws Exception {
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var requesterIntent = collaboration.announce(projectRoot,
                "claude",
                "inst-1",
                "Implement the product CLI",
                "Use the negotiated product query API",
                List.of(ResourceSelector.pathExact("src/ProductCli.java")),
                null,
                org.synesis.coordination.domain.collaboration.WorkIntent.Role.PRODUCER,
                List.of(),
                List.of("catalog.product-query"));
        collaboration.announce(projectRoot,
                "codex",
                "inst-2",
                "Implement the product query API",
                "Publish the negotiated capability implementation",
                List.of(ResourceSelector.pathExact("src/ProductQuery.java")),
                requesterIntent.intent().workGroupId());

        CapabilityContract contract = new CapabilityContract("UUID productId",
                "Optional<Product>",
                List.of("Return exact matching product", "Return empty when missing"),
                List.of("existing product returned", "missing product returns empty"));

        // 1. Requester describes required capability
        CapabilityRequestService.DescribeCapabilityRequest descReq = new CapabilityRequestService.DescribeCapabilityRequest(projectRoot, "claude", "inst-1", "catalog.product-query", contract, null, null);
        AgentResponse descResp = requestService.describeRequiredCapability(descReq);

        assertEquals(AgentStatus.WAITING, descResp.status());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, descResp.reason());
        assertEquals(AgentNextAction.WAIT, descResp.nextAction());

        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) descResp.result();
        String handle = (String) result.get("capabilityRequestHandle");
        assertNotNull(handle);
        assertTrue(handle.startsWith("req_"));

        // 2. Owner checks get_next_action -> receives respond_to_owner_request
        NextActionRequest ownerNextReq = new NextActionRequest(projectRoot, "codex", "inst-2");
        AgentResponse ownerNextResp = nextActionService.getNextAction(ownerNextReq);

        assertEquals(AgentStatus.READY, ownerNextResp.status());
        assertEquals(AgentNextAction.RESPOND_COORDINATION, ownerNextResp.nextAction());

        // 3. Owner accepts request
        long contractRevision = ((Number) ((Map<?, ?>) ownerNextResp.result()).get("contractRevision")).longValue();
        CapabilityResponseService.OwnerResponseRequest ownerResp = new CapabilityResponseService.OwnerResponseRequest(projectRoot, "codex", "inst-2", handle, "accept", null, null, contractRevision);
        AgentResponse respResult = responseService.respondToOwnerRequest(ownerResp);
        assertEquals(AgentStatus.READY, respResult.status());

        // 4. Requester checks get_next_action -> receives implementation_unavailable
        NextActionRequest reqNextReq = new NextActionRequest(projectRoot, "claude", "inst-1");
        AgentResponse reqNextResp = nextActionService.getNextAction(reqNextReq);

        assertEquals(AgentStatus.READY, reqNextResp.status());
        assertNull(reqNextResp.nextAction());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) reqNextResp.result()).get("contractAccepted"), reqNextResp.toJson());
        assertFalse(((Map<?, ?>) reqNextResp.result()).containsKey("implementationPending"), reqNextResp.toJson());
    }

    @Test
    void revisionAndRejectionFlows() {
        CapabilityContract contract = new CapabilityContract("UUID productId", "Optional<Product>", List.of("Return exact matching product"), List.of("existing product returned"));

        // Requester creates request
        CapabilityRequestService.DescribeCapabilityRequest descReq = new CapabilityRequestService.DescribeCapabilityRequest(projectRoot, "claude", "inst-1", "catalog.product-query", contract, null, null);
        AgentResponse descResp = requestService.describeRequiredCapability(descReq);
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) descResp.result();
        String handle = (String) result.get("capabilityRequestHandle");

        // Owner requests revision
        AgentResponse ownerPending = nextActionService.getNextAction(new NextActionRequest(projectRoot, "codex", "inst-2"));
        long ownerContractRevision = ((Number) ((Map<?, ?>) ownerPending.result()).get("contractRevision")).longValue();
        CapabilityContract revisedContract = new CapabilityContract("UUID productId",
                "Optional<Product>",
                List.of("Return exact matching product", "Reject null input"),
                List.of("existing product returned", "null input rejected"));
        CapabilityResponseService.OwnerResponseRequest ownerRevResp = new CapabilityResponseService.OwnerResponseRequest(projectRoot, "codex", "inst-2", handle, "revise", revisedContract, "Null input must be rejected", ownerContractRevision);
        AgentResponse revResult = responseService.respondToOwnerRequest(ownerRevResp);
        assertEquals(AgentStatus.READY, revResult.status());

        // Requester checks get_next_action -> receives revision_required
        NextActionRequest reqNextReq = new NextActionRequest(projectRoot, "claude", "inst-1");
        AgentResponse reqNextResp = nextActionService.getNextAction(reqNextReq);

        assertEquals(AgentStatus.READY, reqNextResp.status());
        assertEquals(AgentReason.REVISION_REQUIRED, reqNextResp.reason());
        assertEquals(AgentNextAction.REVISE_CAPABILITY_REQUEST, reqNextResp.nextAction());

        // Requester accepts revision
        long requesterContractRevision = ((Number) ((Map<?, ?>) reqNextResp.result()).get("contractRevision")).longValue();
        CapabilityRequestService.DescribeCapabilityRequest acceptRevReq = new CapabilityRequestService.DescribeCapabilityRequest(projectRoot,
                "claude",
                "inst-1",
                "catalog.product-query",
                revisedContract,
                handle,
                "accept",
                null,
                requesterContractRevision);
        AgentResponse acceptRevResp = requestService.describeRequiredCapability(acceptRevReq);

        assertEquals(AgentStatus.READY, acceptRevResp.status());
        assertNull(acceptRevResp.nextAction());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) acceptRevResp.result()).get("contractAccepted"));
    }

    @Test
    void activeIntentLineageCanAuthorizeCapabilityWithoutLegacySemanticOwnership() throws Exception {
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.announce(projectRoot, "codex", "inst-2", "Implement task tracker", "Publish the source implementation", List.of(ResourceSelector.pathExact("src/task_tracker.py")));
        collaboration.announce(projectRoot, "claude", "inst-1", "Implement task tracker tests", "Publish tests after the source contract is accepted", List.of(ResourceSelector.pathExact("tests/task_tracker_test.py")));

        var location = projectService.locate(projectRoot);
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        String ownerParticipant = WorkspaceCollaborationService.participantHandle(bindingService.list(location, "codex").getLast().sessionId());
        UUID ownerLineage = store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(ownerParticipant)).findFirst().orElseThrow().authorityLineageId();

        CapabilityContract contract = new CapabilityContract("task records",
                "JSON-serializable task records",
                List.of("preserve the accepted task-tracker contract"),
                List.of("the dependent test lane can import the published implementation"));
        AgentResponse response = requestService.describeRequiredCapability(new CapabilityRequestService.DescribeCapabilityRequest(projectRoot, "claude", "inst-1", "task-tracker", contract, null, null, ownerLineage));

        assertEquals(AgentStatus.WAITING, response.status());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, response.reason());
        PredictionEventStore reloaded = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals(1, reloaded.capabilityRequestProjection().records().size());
    }

    @Test
    void concurrentEquivalentRequestsRecordOneActiveRequest() throws Exception {
        CapabilityContract contract = new CapabilityContract("UUID productId", "Optional<Product>", List.of("Return exact matching product"), List.of("existing product returned"));

        // Separate service instances stand in for separate MCP processes sharing one project.
        List<Callable<AgentResponse>> calls = new ArrayList<>();
        for (int caller = 0; caller < 4; caller++) {
            calls.add(() -> new CapabilityRequestService().describeRequiredCapability(
                    new CapabilityRequestService.DescribeCapabilityRequest(projectRoot, "claude", "inst-1", "catalog.product-query", contract, null, null)));
        }
        List<AgentResponse> responses = concurrently(calls);

        for (AgentResponse response : responses) {
            assertEquals(AgentStatus.WAITING, response.status(), response.toJson());
            assertEquals(AgentReason.OWNER_RESPONSE_PENDING, response.reason(), response.toJson());
        }
        Set<Object> handles = responses.stream().map(response -> ((Map<?, ?>) response.result()).get("capabilityRequestHandle")).collect(Collectors.toSet());
        assertEquals(1, handles.size(), "every caller must observe the single durable request");
        assertEquals(1, replayedStore().capabilityRequestProjection().records().size());
    }

    @Test
    void concurrentConflictingOwnerRepliesToOneRevisionLeaveOneDecision() throws Exception {
        CapabilityContract contract = new CapabilityContract("UUID productId", "Optional<Product>", List.of("Return exact matching product"), List.of("existing product returned"));
        String handle = requestProductQuery(contract);
        long contractRevision = replayedRequest(handle).contractRevision();

        List<String> decisions = List.of("accept", "reject", "accept", "reject");
        List<Callable<AgentResponse>> calls = new ArrayList<>();
        for (String decision : decisions) {
            calls.add(() -> new CapabilityResponseService().respondToOwnerRequest(
                    new CapabilityResponseService.OwnerResponseRequest(projectRoot, "codex", "inst-2", handle, decision, null, null, contractRevision)));
        }
        List<AgentResponse> responses = concurrently(calls);

        PredictionEventStore replayed = replayedStore();
        CapabilityLifecycleState decided = replayed.capabilityRequestProjection().findByHandle(handle).orElseThrow().state();
        assertTrue(decided == CapabilityLifecycleState.ACCEPTED || decided == CapabilityLifecycleState.REJECTED, decided.name());
        long decisionEvents = replayed.events().stream()
                .filter(event -> event.type() == PredictionEventType.CAPABILITY_REQUEST_ACCEPTED || event.type() == PredictionEventType.CAPABILITY_REQUEST_REJECTED).count();
        assertEquals(1, decisionEvents, "only the first reply to a contract revision may be appended");

        String winner = decided == CapabilityLifecycleState.ACCEPTED ? "accept" : "reject";
        for (int index = 0; index < decisions.size(); index++) {
            AgentResponse response = responses.get(index);
            if (decisions.get(index).equals(winner)) {
                assertEquals(AgentStatus.READY, response.status(), response.toJson());
            } else {
                assertEquals(AgentStatus.BLOCKED, response.status(), response.toJson());
                assertEquals(AgentReason.STALE_REQUEST, response.reason(), response.toJson());
            }
        }
    }

    @Test
    void siblingSessionOutsideStoredOwnerScopeCannotAnswer() throws Exception {
        // A second codex connection shares the project node identity and, with no collaboration
        // lanes announced, is not fenced by lineage. Only the stored owner scope separates it.
        new AgentSessionService().ensureSession(new SessionResolutionRequest(projectRoot, "codex", "inst-3", null, false));
        var location = projectService.locate(projectRoot);
        var owner = bindingService.find(location, "codex", "inst-2").orElseThrow();
        var sibling = bindingService.find(location, "codex", "inst-3").orElseThrow();
        assertEquals(owner.nodeId(), sibling.nodeId());
        assertNotEquals(owner.supervisorId(), sibling.supervisorId());

        CapabilityContract contract = new CapabilityContract("UUID productId", "Optional<Product>", List.of("Return exact matching product"), List.of("existing product returned"));
        String handle = requestProductQuery(contract);
        long contractRevision = replayedRequest(handle).contractRevision();

        AgentResponse siblingReply = responseService.respondToOwnerRequest(
                new CapabilityResponseService.OwnerResponseRequest(projectRoot, "codex", "inst-3", handle, "accept", null, null, contractRevision));
        assertEquals(AgentStatus.BLOCKED, siblingReply.status(), siblingReply.toJson());
        assertEquals(AgentReason.POLICY_DENIED, siblingReply.reason(), siblingReply.toJson());
        assertEquals(CapabilityLifecycleState.AWAITING_OWNER, replayedRequest(handle).state());

        AgentResponse siblingNext = nextActionService.getNextAction(new NextActionRequest(projectRoot, "codex", "inst-3"));
        assertNotEquals(AgentNextAction.RESPOND_COORDINATION, siblingNext.nextAction(), siblingNext.toJson());

        AgentResponse ownerReply = responseService.respondToOwnerRequest(
                new CapabilityResponseService.OwnerResponseRequest(projectRoot, "codex", "inst-2", handle, "accept", null, null, contractRevision));
        assertEquals(AgentStatus.READY, ownerReply.status(), ownerReply.toJson());
        assertEquals(CapabilityLifecycleState.ACCEPTED, replayedRequest(handle).state());
    }
}
