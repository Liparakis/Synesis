package org.synesis.mcp.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        new ProjectApplicationService().init(projectRoot);
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");

        var location = new ProjectApplicationService().locate(projectRoot);
        var bindingService = new ProviderSessionBindingService();

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(projectRoot, "antigravity", "inst-req-1", null, false));
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(projectRoot, "codex", "inst-owner-1", null, false));

        var bindings1 = bindingService.list(location, "antigravity");
        if (!bindings1.isEmpty() && bindings1.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "antigravity", bindings1.getLast().sessionId(), Path.of(bindings1.getLast().worktreePath()));
        }
        var bindings2 = bindingService.list(location, "codex");
        if (!bindings2.isEmpty() && bindings2.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "codex", bindings2.getLast().sessionId(), Path.of(bindings2.getLast().worktreePath()));
        }

        var codexIdentity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(
                location.root().resolve(".synesis/coordination"), location.projectId());
        UUID taskId = UUID.randomUUID();
        org.synesis.coordination.domain.task.CoordinationTask task = new org.synesis.coordination.domain.task.CoordinationTask(
                taskId, location.projectId(), "Product Query Task", "catalog.product-query",
                codexIdentity.nodeId(), "supervisor-owner", "worker-owner");
        CoordinationCommand cmd1 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.TASK_CREATED, codexIdentity.nodeId(), task.encoded(), codexIdentity);
        store.append(taskId, PredictionEventType.TASK_CREATED, codexIdentity.nodeId(), cmd1.encoded(), codexIdentity);

        org.synesis.coordination.domain.task.TaskClaim claim1 = new org.synesis.coordination.domain.task.TaskClaim(
                taskId, codexIdentity.nodeId(), "supervisor-owner", "worker-owner");
        CoordinationCommand cmd2 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.TASK_CLAIMED, codexIdentity.nodeId(), claim1.encoded(), codexIdentity);
        store.append(taskId, PredictionEventType.TASK_CLAIMED, codexIdentity.nodeId(), cmd2.encoded(), codexIdentity);

        OwnershipClaim claim2 = new OwnershipClaim(taskId, "catalog.product-query", codexIdentity.nodeId(), "supervisor-owner", List.of("catalog"), 1L);
        CoordinationCommand cmd3 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.OWNERSHIP_CLAIMED, codexIdentity.nodeId(), claim2.encoded(), codexIdentity);
        store.append(taskId, PredictionEventType.OWNERSHIP_CLAIMED, codexIdentity.nodeId(), cmd3.encoded(), codexIdentity);

        requesterHandler = new McpProtocolHandler(sessionService, projectRoot, "antigravity", "inst-req-1");
        ownerHandler = new McpProtocolHandler(sessionService, projectRoot, "codex", "inst-owner-1");

        // Perform initialize for both handlers with explicit project rootUri
        String initParams = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + projectRoot.toUri().toString() + "\"}}";
        requesterHandler.handleMessage(initParams);
        ownerHandler.handleMessage(initParams);
    }

    @Test
    void twoIndependentProcessesNegotiateCapability() {
        // 1. Requester calls ensure_session
        String ensureReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"Test goal\",\"acceptance\":\"Test acceptance\"}}}}";
        String ensureResp = requesterHandler.handleMessage(ensureReq);
        assertNotNull(ensureResp);
        assertTrue(ensureResp.contains("ready"), "Expected ready but got: " + ensureResp);

        // 2. Owner calls ensure_session
        String ensureOwnerResp = ownerHandler.handleMessage(ensureReq);
        assertNotNull(ensureOwnerResp);
        assertTrue(ensureOwnerResp.contains("ready"), "Expected ready but got: " + ensureOwnerResp);

        // 3. Requester describes required capability
        String descReq = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 3,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"request_coordination\",\n" +
                "    \"arguments\": {\n" +
                "      \"capability\": \"catalog.product-query\",\n" +
                "      \"contract\": {\n" +
                "        \"inputs\": \"UUID productId\",\n" +
                "        \"output\": \"Optional<Product>\",\n" +
                "        \"requiredBehavior\": [\"Return exact matching product\", \"Return empty when missing\", \"Reject null input\"],\n" +
                "        \"acceptanceTests\": [\"existing product returned\", \"missing product returns empty\", \"null input rejected\"]\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        String descRespStr = requesterHandler.handleMessage(descReq);
        assertNotNull(descRespStr);
        assertTrue(descRespStr.contains("owner_response_pending"), "Expected owner_response_pending but got: " + descRespStr);
        assertTrue(descRespStr.contains("req_"));

        // Extract public handle locator
        String handle = extractHandleFromMcpResponse(descRespStr);
        assertNotNull(handle);

        // 4. Owner calls get_next_action -> receives respond_coordination
        String ownerNextReq = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"get_next_action\",\"arguments\":{}}}";
        String ownerNextResp = ownerHandler.handleMessage(ownerNextReq);
        assertNotNull(ownerNextResp);
        assertTrue(ownerNextResp.contains("respond_coordination"), "Expected respond_coordination but got: " + ownerNextResp);
        assertTrue(ownerNextResp.contains(handle));

        // 5. Owner accepts the request
        String acceptReq = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 5,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"respond_coordination\",\n" +
                "    \"arguments\": {\n" +
                "      \"request\": \"" + handle + "\",\n" +
                "      \"response\": \"accept\"\n" +
                "    }\n" +
                "  }\n" +
                "}";

        String acceptRespStr = ownerHandler.handleMessage(acceptReq);
        assertNotNull(acceptRespStr);
        assertTrue(acceptRespStr.contains("ready"), "Expected ready but got: " + acceptRespStr);

        // 6. Requester calls get_next_action -> receives implementation_unavailable
        String reqNextReq = "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"get_next_action\",\"arguments\":{}}}";
        String reqNextResp = requesterHandler.handleMessage(reqNextReq);
        assertNotNull(reqNextResp);
        assertTrue(reqNextResp.contains("implementation_unavailable"), "Expected implementation_unavailable but got: " + reqNextResp);

        // 7. Verify no internal IDs or absolute paths leak in responses
        assertFalse(descRespStr.contains(projectRoot.toString().replace('\\', '/')));
        assertFalse(ownerNextResp.contains(projectRoot.toString().replace('\\', '/')));
        assertFalse(acceptRespStr.contains(projectRoot.toString().replace('\\', '/')));
        assertFalse(reqNextResp.contains(projectRoot.toString().replace('\\', '/')));
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
        assertTrue(mutationResponse.contains("overlapping_claim"),
                "competing mutation must be blocked: " + mutationResponse);
    }

    @SuppressWarnings("unchecked")
    private String extractHandleFromMcpResponse(String mcpResponseJson) {
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(mcpResponseJson);
        Map<String, Object> result = (Map<String, Object>) map.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        String text = (String) content.get(0).get("text");
        Map<String, Object> agentResp = (Map<String, Object>) ProviderJson.parse(text);
        Map<String, Object> innerRes = (Map<String, Object>) agentResp.get("result");
        return (String) innerRes.get("request");
    }
}
