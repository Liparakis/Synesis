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

class McpStage2BSlice1Test {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private McpProtocolHandler handler;

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
        projectRoot = tempDir.resolve("mcp-slice1-test");
        Files.createDirectories(projectRoot);

        git(projectRoot, "init");
        git(projectRoot, "config", "user.name", "Test User");
        git(projectRoot, "config", "user.email", "test@example.com");
        Files.writeString(projectRoot.resolve("README.md"), "# MCP Test\n");
        git(projectRoot, "add", ".");
        git(projectRoot, "commit", "-m", "Initial commit");

        new ProjectApplicationService().init(projectRoot);
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        new org.synesis.workspace.application.provider.ProviderManualService().install("claude");
        new org.synesis.workspace.application.provider.ProviderManualService().install("antigravity");

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(projectRoot, "antigravity", "inst-mcp-1", null, false));

        var location = new ProjectApplicationService().locate(projectRoot);
        var bindingService = new ProviderSessionBindingService();
        var bindings = bindingService.list(location, "antigravity");
        if (!bindings.isEmpty() && bindings.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "antigravity", bindings.getLast().sessionId(), Path.of(bindings.getLast().worktreePath()));
        }

        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(
                location.root().resolve(".synesis/coordination"), location.projectId());
        UUID taskId = UUID.randomUUID();
        org.synesis.coordination.domain.task.CoordinationTask task = new org.synesis.coordination.domain.task.CoordinationTask(
                taskId, location.projectId(), "Product Query Task", "catalog.product-query",
                identity.nodeId(), "supervisor-1", "worker-1");
        CoordinationCommand cmd1 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.TASK_CREATED, identity.nodeId(), task.encoded(), identity);
        store.append(taskId, PredictionEventType.TASK_CREATED, identity.nodeId(), cmd1.encoded(), identity);

        org.synesis.coordination.domain.task.TaskClaim claim1 = new org.synesis.coordination.domain.task.TaskClaim(
                taskId, identity.nodeId(), "supervisor-1", "worker-1");
        CoordinationCommand cmd2 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.TASK_CLAIMED, identity.nodeId(), claim1.encoded(), identity);
        store.append(taskId, PredictionEventType.TASK_CLAIMED, identity.nodeId(), cmd2.encoded(), identity);

        OwnershipClaim claim2 = new OwnershipClaim(taskId, "catalog.product-query", identity.nodeId(), "supervisor-1", List.of("catalog"), 1L);
        CoordinationCommand cmd3 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), taskId, PredictionEventType.OWNERSHIP_CLAIMED, identity.nodeId(), claim2.encoded(), identity);
        store.append(taskId, PredictionEventType.OWNERSHIP_CLAIMED, identity.nodeId(), cmd3.encoded(), identity);

        handler = new McpProtocolHandler(sessionService, projectRoot, "antigravity", "inst-mcp-1");
    }

    @Test
    void toolsListContainsTenTools() {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        String res = handler.handleMessage(req);
        assertNotNull(res);

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(res);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) map.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");

        assertEquals(10, tools.size());
        assertEquals("ensure_session", tools.get(0).get("name"));
        assertEquals("read_file", tools.get(1).get("name"));
        assertEquals("apply_patch", tools.get(2).get("name"));
        assertEquals("run_command", tools.get(3).get("name"));
        assertEquals("get_next_action", tools.get(4).get("name"));
        assertEquals("request_coordination", tools.get(5).get("name"));
        assertEquals("respond_coordination", tools.get(6).get("name"));
        assertEquals("publish_capability_implementation", tools.get(7).get("name"));
        assertEquals("finish_lane", tools.get(8).get("name"));
        assertEquals("cancel_lane", tools.get(9).get("name"));
    }

    @Test
    void describeRequiredCapabilityCallReturnsConciseResponseWithoutPathLeak() {
        String callJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 2,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"request_coordination\",\n" +
                "    \"arguments\": {\n" +
                "      \"kind\": \"capability_request\",\n" +
                "      \"payload\": {\n" +
                "      \"capability\": \"catalog.product-query\",\n" +
                "      \"contract\": {\n" +
                "        \"inputs\": \"UUID productId\",\n" +
                "        \"output\": \"Optional<Product>\",\n" +
                "        \"requiredBehavior\": [\"Return exact matching product\"],\n" +
                "        \"acceptanceTests\": [\"existing product returned\"]\n" +
                "      }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        String res = handler.handleMessage(callJson);
        assertNotNull(res);
        assertTrue(res.contains("req_"));
        assertFalse(res.contains(projectRoot.toString().replace('\\', '/')));
        assertFalse(res.contains("eventId"));
    }

    @Test
    void lifecycleRejectsLegacyNamesAndNonDiscriminatedCoordinationPayloads() {
        String legacy = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"complete_task\",\"arguments\":{}}}");
        assertTrue(legacy.contains("Unknown tool"));
        String nonDiscriminated = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"capability\":\"x\"}}}");
        assertTrue(nonDiscriminated.contains("COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD"));
    }
}
