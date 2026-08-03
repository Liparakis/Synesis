package org.synesis.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.AgentNextActionService;
import org.synesis.workspace.application.capability.CapabilityRequestService;
import org.synesis.workspace.application.capability.CapabilityResponseService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        String[] cmd = new String[args.length + 3];
        cmd[0] = "git";
        cmd[1] = "-C";
        cmd[2] = root.toString();
        System.arraycopy(args, 0, cmd, 3, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git failed");
        }
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

        bindingService = new ProviderSessionBindingService();
        requestService = new CapabilityRequestService();
        responseService = new CapabilityResponseService();
        nextActionService = new AgentNextActionService();

        var location = projectService.locate(projectRoot);
        // Bind session for requester (antigravity) and owner (codex)
        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(projectRoot, "antigravity", "inst-1", null, false));
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(projectRoot, "codex", "inst-2", null, false));

        var bindings1 = bindingService.list(location, "antigravity");
        if (!bindings1.isEmpty() && bindings1.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "antigravity", bindings1.getLast().sessionId(), Path.of(bindings1.getLast().worktreePath()));
        }
        var bindings2 = bindingService.list(location, "codex");
        if (!bindings2.isEmpty() && bindings2.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "codex", bindings2.getLast().sessionId(), Path.of(bindings2.getLast().worktreePath()));
        }

        // Assign semantic ownership for catalog.product-query to codex node ID via event store
        var codexIdentity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(
                location.root().resolve(".synesis/coordination"), location.projectId());
        UUID taskId = UUID.randomUUID();

        org.synesis.coordination.domain.task.CoordinationTask task = new org.synesis.coordination.domain.task.CoordinationTask(
                taskId, location.projectId(), "Product Query Task", "catalog.product-query",
                codexIdentity.nodeId(), "supervisor-codex", "worker-codex");
        CoordinationCommand cmd1 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.TASK_CREATED, codexIdentity.nodeId(), task.encoded(), codexIdentity);
        store.append(taskId, PredictionEventType.TASK_CREATED, codexIdentity.nodeId(), cmd1.encoded(), codexIdentity);

        org.synesis.coordination.domain.task.TaskClaim claim1 = new org.synesis.coordination.domain.task.TaskClaim(
                taskId, codexIdentity.nodeId(), "supervisor-codex", "worker-codex");
        CoordinationCommand cmd2 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.TASK_CLAIMED, codexIdentity.nodeId(), claim1.encoded(), codexIdentity);
        store.append(taskId, PredictionEventType.TASK_CLAIMED, codexIdentity.nodeId(), cmd2.encoded(), codexIdentity);

        OwnershipClaim claim2 = new OwnershipClaim(taskId, "catalog.product-query", codexIdentity.nodeId(), "supervisor-codex", List.of("catalog"), 1L);
        CoordinationCommand cmd3 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.OWNERSHIP_CLAIMED, codexIdentity.nodeId(), claim2.encoded(), codexIdentity);
        store.append(taskId, PredictionEventType.OWNERSHIP_CLAIMED, codexIdentity.nodeId(), cmd3.encoded(), codexIdentity);
    }

    @Test
    void completeCapabilityNegotiationAcceptanceFlow() throws Exception {
        CapabilityContract contract = new CapabilityContract(
                "UUID productId",
                "Optional<Product>",
                List.of("Return exact matching product", "Return empty when missing"),
                List.of("existing product returned", "missing product returns empty")
        );

        // 1. Requester describes required capability
        CapabilityRequestService.DescribeCapabilityRequest descReq = new CapabilityRequestService.DescribeCapabilityRequest(
                projectRoot, "antigravity", "inst-1", "catalog.product-query", contract, null, null);
        AgentResponse descResp = requestService.describeRequiredCapability(descReq);

        assertEquals(AgentStatus.WAITING, descResp.status());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, descResp.reason());
        assertEquals(AgentNextAction.WAIT, descResp.nextAction());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) descResp.result();
        String handle = (String) result.get("capabilityRequestHandle");
        assertNotNull(handle);
        assertTrue(handle.startsWith("req_"));

        // 2. Owner checks get_next_action -> receives respond_to_owner_request
        AgentNextActionService.NextActionRequest ownerNextReq = new AgentNextActionService.NextActionRequest(
                projectRoot, "codex", "inst-2");
        AgentResponse ownerNextResp = nextActionService.getNextAction(ownerNextReq);

        assertEquals(AgentStatus.READY, ownerNextResp.status());
        assertEquals(AgentNextAction.RESPOND_COORDINATION, ownerNextResp.nextAction());

        // 3. Owner accepts request
        CapabilityResponseService.OwnerResponseRequest ownerResp = new CapabilityResponseService.OwnerResponseRequest(
                projectRoot, "codex", "inst-2", handle, "accept", null, null);
        AgentResponse respResult = responseService.respondToOwnerRequest(ownerResp);
        assertEquals(AgentStatus.READY, respResult.status());

        // 4. Requester checks get_next_action -> receives implementation_unavailable
        AgentNextActionService.NextActionRequest reqNextReq = new AgentNextActionService.NextActionRequest(
                projectRoot, "antigravity", "inst-1");
        AgentResponse reqNextResp = nextActionService.getNextAction(reqNextReq);

        assertEquals(AgentStatus.WAITING, reqNextResp.status());
        assertEquals(AgentReason.IMPLEMENTATION_UNAVAILABLE, reqNextResp.reason());
        assertEquals(AgentNextAction.WAIT, reqNextResp.nextAction());
    }

    @Test
    void revisionAndRejectionFlows() throws Exception {
        CapabilityContract contract = new CapabilityContract(
                "UUID productId",
                "Optional<Product>",
                List.of("Return exact matching product"),
                List.of("existing product returned")
        );

        // Requester creates request
        CapabilityRequestService.DescribeCapabilityRequest descReq = new CapabilityRequestService.DescribeCapabilityRequest(
                projectRoot, "antigravity", "inst-1", "catalog.product-query", contract, null, null);
        AgentResponse descResp = requestService.describeRequiredCapability(descReq);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) descResp.result();
        String handle = (String) result.get("capabilityRequestHandle");

        // Owner requests revision
        CapabilityContract revisedContract = new CapabilityContract(
                "UUID productId",
                "Optional<Product>",
                List.of("Return exact matching product", "Reject null input"),
                List.of("existing product returned", "null input rejected")
        );
        CapabilityResponseService.OwnerResponseRequest ownerRevResp = new CapabilityResponseService.OwnerResponseRequest(
                projectRoot, "codex", "inst-2", handle, "revise", revisedContract, "Null input must be rejected");
        AgentResponse revResult = responseService.respondToOwnerRequest(ownerRevResp);
        assertEquals(AgentStatus.READY, revResult.status());

        // Requester checks get_next_action -> receives revision_required
        AgentNextActionService.NextActionRequest reqNextReq = new AgentNextActionService.NextActionRequest(
                projectRoot, "antigravity", "inst-1");
        AgentResponse reqNextResp = nextActionService.getNextAction(reqNextReq);

        assertEquals(AgentStatus.READY, reqNextResp.status());
        assertEquals(AgentReason.REVISION_REQUIRED, reqNextResp.reason());
        assertEquals(AgentNextAction.REVISE_CAPABILITY_REQUEST, reqNextResp.nextAction());

        // Requester accepts revision
        CapabilityRequestService.DescribeCapabilityRequest acceptRevReq = new CapabilityRequestService.DescribeCapabilityRequest(
                projectRoot, "antigravity", "inst-1", null, null, handle, "accept");
        AgentResponse acceptRevResp = requestService.describeRequiredCapability(acceptRevReq);

        assertEquals(AgentStatus.WAITING, acceptRevResp.status());
        assertEquals(AgentReason.IMPLEMENTATION_UNAVAILABLE, acceptRevResp.reason());
    }

    @Test
    void activeIntentLineageCanAuthorizeCapabilityWithoutLegacySemanticOwnership() throws Exception {
        new ProviderManualService().install("codex");
        new ProviderManualService().install("antigravity");
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.announce(projectRoot, "codex", "inst-2", "Implement task tracker",
                "Publish the source implementation", List.of(ResourceSelector.pathExact("src/task_tracker.py")));
        collaboration.announce(projectRoot, "antigravity", "inst-1", "Implement task tracker tests",
                "Publish tests after the source contract is accepted",
                List.of(ResourceSelector.pathExact("tests/task_tracker_test.py")));

        var location = projectService.locate(projectRoot);
        PredictionEventStore store = new PredictionEventStore(
                location.root().resolve(".synesis/coordination"), location.projectId());
        String ownerParticipant = WorkspaceCollaborationService.participantHandle(
                bindingService.list(location, "codex").getLast().sessionId());
        UUID ownerLineage = store.collaborationProjection().activeIntents().stream()
                .filter(intent -> intent.participant().equals(ownerParticipant))
                .findFirst().orElseThrow().authorityLineageId();

        CapabilityContract contract = new CapabilityContract(
                "task records", "JSON-serializable task records",
                List.of("preserve the accepted task-tracker contract"),
                List.of("the dependent test lane can import the published implementation"));
        AgentResponse response = requestService.describeRequiredCapability(
                new CapabilityRequestService.DescribeCapabilityRequest(
                        projectRoot, "antigravity", "inst-1", "task-tracker", contract,
                        null, null, ownerLineage));

        assertEquals(AgentStatus.WAITING, response.status());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, response.reason());
        PredictionEventStore reloaded = new PredictionEventStore(
                location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals(1, reloaded.capabilityRequestProjection().records().size());
    }
}
