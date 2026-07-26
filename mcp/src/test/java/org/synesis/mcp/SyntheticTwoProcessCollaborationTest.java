package org.synesis.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.CoordinationCommand;
import org.synesis.coordination.domain.OwnershipClaim;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.domain.PredictionEventType;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.agent.AgentSessionService;
import org.synesis.workspace.project.ProjectApplicationService;
import org.synesis.workspace.application.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SyntheticTwoProcessCollaborationTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private McpProtocolHandler requesterHandler;
    private McpProtocolHandler ownerHandler;
    private ProviderSessionBindingService.Binding b1;
    private ProviderSessionBindingService.Binding b2;

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
        projectRoot = tempDir.resolve("synthetic-collaboration-test");
        Files.createDirectories(projectRoot);

        git(projectRoot, "init");
        git(projectRoot, "config", "user.name", "Test User");
        git(projectRoot, "config", "user.email", "test@example.com");
        Files.writeString(projectRoot.resolve("README.md"), "# Synthetic Two Process Collaboration Test\n");
        Files.writeString(projectRoot.resolve(".gitignore"), ".synesis/\n");
        git(projectRoot, "add", ".");
        git(projectRoot, "commit", "-m", "Initial commit");

        new ProjectApplicationService().init(projectRoot);

        var location = new ProjectApplicationService().locate(projectRoot);
        var bindingService = new ProviderSessionBindingService();

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(projectRoot, "antigravity", "inst-req-1", null, false));
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(projectRoot, "codex", "inst-owner-1", null, false));

        git(projectRoot, "add", ".");
        git(projectRoot, "commit", "-m", "Commit agent session files");

        var bindings1 = bindingService.list(location, "antigravity");
        if (!bindings1.isEmpty() && bindings1.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "antigravity", bindings1.getLast().sessionId(), Path.of(bindings1.getLast().worktreePath()));
        }
        var bindings2 = bindingService.list(location, "codex");
        if (!bindings2.isEmpty() && bindings2.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "codex", bindings2.getLast().sessionId(), Path.of(bindings2.getLast().worktreePath()));
        }

        b1 = bindings1.getLast();
        b2 = bindings2.getLast();

        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(
                location.root().resolve(".synesis/coordination"), location.projectId());

        // 1. Task for owner (Codex)
        UUID ownerTaskId = UUID.randomUUID();
        org.synesis.coordination.domain.CoordinationTask ownerTask = new org.synesis.coordination.domain.CoordinationTask(
                ownerTaskId, location.projectId(), "Product Query Task", "catalog.product-query",
                identity.nodeId(), b2.supervisorId(), b2.workerId());
        CoordinationCommand cmd1 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), ownerTaskId, PredictionEventType.TASK_CREATED, identity.nodeId(), ownerTask.encoded(), identity);
        store.append(ownerTaskId, PredictionEventType.TASK_CREATED, identity.nodeId(), cmd1.encoded(), identity);

        org.synesis.coordination.domain.TaskClaim claim1 = new org.synesis.coordination.domain.TaskClaim(
                ownerTaskId, identity.nodeId(), b2.supervisorId(), b2.workerId());
        CoordinationCommand cmd2 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), ownerTaskId, PredictionEventType.TASK_CLAIMED, identity.nodeId(), claim1.encoded(), identity);
        store.append(ownerTaskId, PredictionEventType.TASK_CLAIMED, identity.nodeId(), cmd2.encoded(), identity);

        OwnershipClaim claim2 = new OwnershipClaim(ownerTaskId, "catalog.product-query", identity.nodeId(), b2.supervisorId(), List.of("catalog"), 1L);
        CoordinationCommand cmd3 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), ownerTaskId, PredictionEventType.OWNERSHIP_CLAIMED, identity.nodeId(), claim2.encoded(), identity);
        store.append(ownerTaskId, PredictionEventType.OWNERSHIP_CLAIMED, identity.nodeId(), cmd3.encoded(), identity);

        // 2. Task for requester (Antigravity)
        UUID reqTaskId = UUID.randomUUID();
        org.synesis.coordination.domain.CoordinationTask reqTask = new org.synesis.coordination.domain.CoordinationTask(
                reqTaskId, location.projectId(), "Product CLI Task", "catalog.product-cli",
                identity.nodeId(), b1.supervisorId(), b1.workerId());
        CoordinationCommand cmd4 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), reqTaskId, PredictionEventType.TASK_CREATED, identity.nodeId(), reqTask.encoded(), identity);
        store.append(reqTaskId, PredictionEventType.TASK_CREATED, identity.nodeId(), cmd4.encoded(), identity);

        org.synesis.coordination.domain.TaskClaim claim3 = new org.synesis.coordination.domain.TaskClaim(
                reqTaskId, identity.nodeId(), b1.supervisorId(), b1.workerId());
        CoordinationCommand cmd5 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), reqTaskId, PredictionEventType.TASK_CLAIMED, identity.nodeId(), claim3.encoded(), identity);
        store.append(reqTaskId, PredictionEventType.TASK_CLAIMED, identity.nodeId(), cmd5.encoded(), identity);

        requesterHandler = new McpProtocolHandler(sessionService, projectRoot, "antigravity", "inst-req-1");
        ownerHandler = new McpProtocolHandler(sessionService, projectRoot, "codex", "inst-owner-1");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\""
                + projectRoot.toUri().toString().replace("\\", "/") + "\"}}";
        requesterHandler.handleMessage(initReq);
        ownerHandler.handleMessage(initReq);
    }

    @Test
    void fullSlice3TaskCompletionAndIntegrationFlow() throws Exception {
        // 1. Requester describes capability
        String descJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 2,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"synesis.describe_required_capability\",\n" +
                "    \"arguments\": {\n" +
                "      \"capability\": \"catalog.product-query\",\n" +
                "      \"contract\": {\n" +
                "        \"inputs\": \"UUID id\",\n" +
                "        \"output\": \"Optional<Product>\",\n" +
                "        \"requiredBehavior\": [\"Return product when found\"],\n" +
                "        \"acceptanceTests\": [\"ProductQueryTest\"]\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String descRes = requesterHandler.handleMessage(descJson);
        assertNotNull(descRes);
        String reqHandle = extractResultField(descRes, "request");
        assertNotNull(reqHandle);

        // 2. Owner accepts capability request
        String acceptJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 3,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"synesis.respond_to_owner_request\",\n" +
                "    \"arguments\": {\n" +
                "      \"request\": \"" + reqHandle + "\",\n" +
                "      \"response\": \"accept\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String acceptRes = ownerHandler.handleMessage(acceptJson);
        assertNotNull(acceptRes);

        // 3. Owner writes implementation code and publishes
        Path ownerWt = Path.of(b2.worktreePath());
        Files.writeString(ownerWt.resolve("ProductQuery.java"), "public class ProductQuery {}\n");
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Implement ProductQuery");

        String pubJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 4,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"synesis.publish_implementation\",\n" +
                "    \"arguments\": {\n" +
                "      \"request\": \"" + reqHandle + "\",\n" +
                "      \"summary\": \"Implemented product query\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String pubRes = ownerHandler.handleMessage(pubJson);
        assertNotNull(pubRes);

        // 4. Requester validates implementation
        String valJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 5,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"synesis.validate_available_implementation\",\n" +
                "    \"arguments\": {\n" +
                "      \"request\": \"" + reqHandle + "\",\n" +
                "      \"result\": \"accepted\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String valRes = requesterHandler.handleMessage(valJson);
        assertNotNull(valRes);

        // 5. Owner completes task
        String ownerCompJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 6,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"synesis.complete_task\",\n" +
                "    \"arguments\": {\n" +
                "      \"summary\": \"Product query service complete\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String ownerCompRes = ownerHandler.handleMessage(ownerCompJson);
        assertNotNull(ownerCompRes);

        // 6. Requester writes CLI code and completes task
        Path reqWt = Path.of(b1.worktreePath());
        Files.writeString(reqWt.resolve("ProductCli.java"), "public class ProductCli {}\n");
        git(reqWt, "add", ".");
        git(reqWt, "commit", "-m", "Implement ProductCli");

        // 6. Requester completes task and triggers integration
        String reqCompJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 7,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"synesis.complete_task\",\n" +
                "    \"arguments\": {\n" +
                "      \"summary\": \"Product CLI integration complete\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String reqCompRes = requesterHandler.handleMessage(reqCompJson);
        assertNotNull(reqCompRes);
        assertEquals("completed", extractResponseStatus(reqCompRes));
    }

    @SuppressWarnings("unchecked")
    private static String extractResultField(String jsonRpcRes, String field) {
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(jsonRpcRes);
        Map<String, Object> result = (Map<String, Object>) map.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        String text = (String) content.get(0).get("text");
        Map<String, Object> parsed = (Map<String, Object>) ProviderJson.parse(text);
        Map<String, Object> innerResult = (Map<String, Object>) parsed.get("result");
        return innerResult != null ? (String) innerResult.get(field) : null;
    }

    @SuppressWarnings("unchecked")
    private static String extractResponseStatus(String jsonRpcRes) {
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(jsonRpcRes);
        Map<String, Object> result = (Map<String, Object>) map.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        String text = (String) content.get(0).get("text");
        Map<String, Object> parsed = (Map<String, Object>) ProviderJson.parse(text);
        return (String) parsed.get("status");
    }
}
