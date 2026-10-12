package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Exercises capability negotiation across two independent MCP processes.
 */
@SuppressWarnings("TextBlockMigration")
class TwoProcessCapabilityNegotiationProcessTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private McpProtocolHandler requesterHandler;
    private McpProtocolHandler ownerHandler;

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
        projectRoot = tempDir.resolve("two-process-test");
        Files.createDirectories(projectRoot);

        git(projectRoot, "init");
        git(projectRoot, "config", "user.name", "Test User");
        git(projectRoot, "config", "user.email", "test@example.com");
        Files.writeString(projectRoot.resolve("README.md"), "# Two Process Test\n");
        git(projectRoot, "add", ".");
        git(projectRoot, "commit", "-m", "Initial commit");

        ProjectApplicationService projectService = new ProjectApplicationService();
        projectService.init(projectRoot);
        var location = projectService.locate(projectRoot);
        McpProviderTestSupport.install(location, "codex");
        McpProviderTestSupport.install(location, "claude");
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        new org.synesis.workspace.application.provider.ProviderManualService().install("claude");

        location = projectService.locate(projectRoot);
        var bindingService = new ProviderSessionBindingService();

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new SessionResolutionRequest(projectRoot, "claude", "inst-req-1", null, false));
        sessionService.ensureSession(new SessionResolutionRequest(projectRoot, "codex", "inst-owner-1", null, false));

        var bindings1 = bindingService.list(location, "claude");
        if (!bindings1.isEmpty() && bindings1.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "claude", bindings1.getLast().sessionId(), Path.of(bindings1.getLast().worktreePath()));
        }
        var bindings2 = bindingService.list(location, "codex");
        if (!bindings2.isEmpty() && bindings2.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "codex", bindings2.getLast().sessionId(), Path.of(bindings2.getLast().worktreePath()));
        }

        // Owner replies are authorized against the stored owner scope, so the semantic owner
        // must be the bound codex session's own supervisor and worker.
        var ownerBinding = bindingService.find(location, "codex", "inst-owner-1").orElseThrow();
        String ownerSupervisor = ownerBinding.supervisorId();
        String ownerWorker = ownerBinding.workerId();
        var codexIdentity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID taskId = UUID.randomUUID();
        org.synesis.coordination.domain.task.CoordinationTask task = new org.synesis.coordination.domain.task.CoordinationTask(taskId,
                location.projectId(),
                "Product Query Task",
                "catalog.product-query",
                codexIdentity.nodeId(),
                ownerSupervisor,
                ownerWorker);
        CoordinationCommand cmd1 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.TASK_CREATED, codexIdentity.nodeId(), task.encoded(), codexIdentity);
        store.append(taskId, PredictionEventType.TASK_CREATED, codexIdentity.nodeId(), cmd1.encoded(), codexIdentity);

        org.synesis.coordination.domain.task.TaskClaim claim1 = new org.synesis.coordination.domain.task.TaskClaim(taskId, codexIdentity.nodeId(), ownerSupervisor, ownerWorker);
        CoordinationCommand cmd2 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.TASK_CLAIMED, codexIdentity.nodeId(), claim1.encoded(), codexIdentity);
        store.append(taskId, PredictionEventType.TASK_CLAIMED, codexIdentity.nodeId(), cmd2.encoded(), codexIdentity);

        OwnershipClaim claim2 = new OwnershipClaim(taskId, "catalog.product-query", codexIdentity.nodeId(), ownerSupervisor, List.of("catalog"), 1L);
        CoordinationCommand cmd3 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.OWNERSHIP_CLAIMED, codexIdentity.nodeId(), claim2.encoded(), codexIdentity);
        store.append(taskId, PredictionEventType.OWNERSHIP_CLAIMED, codexIdentity.nodeId(), cmd3.encoded(), codexIdentity);

        requesterHandler = new McpProtocolHandler(sessionService, projectRoot, "claude", "inst-req-1");
        ownerHandler = new McpProtocolHandler(sessionService, projectRoot, "codex", "inst-owner-1");

        // Perform initialize for both handlers with explicit project rootUri
        String initParams = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + projectRoot.toUri() + "\"}}";
        requesterHandler.handleMessage(initParams);
        ownerHandler.handleMessage(initParams);
    }

    @Test
    void twoIndependentProcessesAgreeOneContract() {
        // 1. Each process announces the file it builds
        String ensureReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"Test goal\",\"acceptance\":\"Test acceptance\","
                + "\"claims\":[{\"kind\":\"path_exact\",\"path\":\"ProductCli.java\"}]}}}}";
        String ensureResp = requesterHandler.handleMessage(ensureReq);
        assertNotNull(ensureResp);
        assertTrue(ensureResp.contains("ready"), "Expected ready but got: " + ensureResp);

        String ensureOwnerResp = ownerHandler.handleMessage(ensureReq.replace("ProductCli.java", "ProductQuery.java"));
        assertNotNull(ensureOwnerResp);
        assertTrue(ensureOwnerResp.contains("ready"), "Expected ready but got: " + ensureOwnerResp);

        // 2. The owner publishes the one contract over its file
        String publishReq = "{\n" + "  \"jsonrpc\": \"2.0\",\n" + "  \"id\": 3,\n" + "  \"method\": \"tools/call\",\n" + "  \"params\": {\n" + "    \"name\": \"request_coordination\",\n" + "    \"arguments\": {\n"
                + "      \"kind\": \"contract_publish\",\n" + "      \"payload\": {\n" + "      \"paths\": [\"ProductQuery.java\"],\n" + "      \"contract\": {\n" + "        \"purpose\": \"Look up a product by id\",\n" + "        \"inputs\": \"UUID productId\",\n"
                + "        \"output\": \"Optional<Product>\",\n" + "        \"requiredBehavior\": [\"Return exact matching product\", \"Return empty when missing\", \"Reject null input\"],\n"
                + "        \"errorSemantics\": \"Malformed ids are rejected; unknown products return empty\",\n" + "        \"sideEffects\": \"none\",\n" + "        \"invariants\": \"Does not mutate product records\",\n" + "        \"compatibility\": \"Additive changes only\",\n" + "        \"performance\": \"One lookup per call\",\n" + "        \"concurrency\": \"Concurrent reads are safe\",\n" + "        \"acceptanceTests\": [\"existing product returned\", \"missing product returns empty\", \"null input rejected\"]\n" + "      }\n" + "      }\n" + "    }\n" + "  }\n" + "}";
        String publishResp = ownerHandler.handleMessage(publishReq);
        assertNotNull(publishResp);
        assertTrue(publishResp.contains("contractId"), "Expected the published contract but got: " + publishResp);

        // 3. The requester binds to the exact revision it read
        String bindReq = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"contract_bind\","
                + "\"payload\":{\"capability\":\"catalog.product-query\",\"ownerPath\":\"ProductQuery.java\",\"revision\":1}}}}";
        String bindResp = requesterHandler.handleMessage(bindReq);
        assertNotNull(bindResp);
        assertTrue(bindResp.contains("contractAccepted"), "Expected the binding to be accepted but got: " + bindResp);
        String handle = extractHandleFromMcpResponse(bindResp);
        assertNotNull(handle);
        assertTrue(handle.startsWith("req_"));

        // 4. The owner is told whom its implementation is for; the requester can build against the contract
        String ownerNextReq = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"get_next_action\",\"arguments\":{}}}";
        String ownerNextResp = ownerHandler.handleMessage(ownerNextReq);
        assertTrue(ownerNextResp.contains(handle), "Expected the binding in the owner's next action but got: " + ownerNextResp);
        String reqNextResp = requesterHandler.handleMessage(ownerNextReq.replace("\"id\":5", "\"id\":6"));
        assertTrue(reqNextResp.contains("contractAccepted"), "Expected the accepted contract to be available for implementation: " + reqNextResp);

        // 5. Verify no absolute paths leak in responses
        String root = projectRoot.toString().replace('\\', '/');
        assertFalse(publishResp.contains(root));
        assertFalse(bindResp.contains(root));
        assertFalse(ownerNextResp.contains(root));
        assertFalse(reqNextResp.contains(root));
    }

    @Test
    void secondMcpSessionDiscoversClaimBeforeCompetingMutation() {
        String ownerEnsure = "{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"Implement tracker\",\"acceptance\":\"45 tests pass\",\"claims\":[{\"path\":\"src/task_tracker.py\",\"kind\":\"path_exact\"}]}}}}";
        String first = requesterHandler.handleMessage(ownerEnsure);
        assertTrue(first.contains("ready"), "first claim should be acquired: " + first);

        String second = ownerHandler.handleMessage(ownerEnsure.replace("\"id\":20", "\"id\":21"));
        assertTrue(second.contains("overlapping_claim"), "second claim must be blocked: " + second);

        String mutation = "{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/call\",\"params\":{\"name\":\"apply_patch\",\"arguments\":{\"path\":\"src/task_tracker.py\",\"create\":true,\"content\":\"competing\"}}}";
        String mutationResponse = ownerHandler.handleMessage(mutation);
        assertTrue(mutationResponse.contains("overlapping_claim"), "competing mutation must be blocked: " + mutationResponse);
    }

    @SuppressWarnings("unchecked")
    private String extractHandleFromMcpResponse(String mcpResponseJson) {
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(mcpResponseJson);
        Map<String, Object> result = (Map<String, Object>) map.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        String text = (String) content.getFirst().get("text");
        Map<String, Object> agentResp = (Map<String, Object>) ProviderJson.parse(text);
        Map<String, Object> innerRes = (Map<String, Object>) agentResp.get("result");
        return (String) innerRes.get("capabilityRequestHandle");
    }
}
