package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

@SuppressWarnings("TextBlockMigration")
class Slice4FailureScenariosTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private McpProtocolHandler requesterHandler;
    private McpProtocolHandler ownerHandler;
    @SuppressWarnings("FieldCanBeLocal")
    private ProviderSessionBindingService.Binding b1;
    private ProviderSessionBindingService.Binding b2;
    @SuppressWarnings("FieldCanBeLocal")
    private AgentSessionService sessionService;

    private static void git(Path root, String... args) throws Exception {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "git";
        cmd[1] = "-C";
        cmd[2] = root.toString();
        System.arraycopy(args, 0, cmd, 3, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                .start();
        p.getInputStream()
                .readAllBytes();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git failed: " + String.join(" ", args));
        }
    }

    private static void commitIfNeeded(Path root) throws Exception {
        Process process = new ProcessBuilder("git", "-C", root.toString(), "status", "--porcelain")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream()
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git status failed");
        }
        if (!output.isBlank()) {
            git(root, "commit", "-m", "Commit agent session files");
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractResultField(String jsonRpcRes) {
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(jsonRpcRes);
        Map<String, Object> result = (Map<String, Object>) map.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        String text = (String) content.getFirst()
                .get("text");
        Map<String, Object> parsed = (Map<String, Object>) ProviderJson.parse(text);
        Map<String, Object> innerResult = (Map<String, Object>) parsed.get("result");
        return innerResult != null ? (String) innerResult.get("capabilityRequestHandle") : null;
    }

    @SuppressWarnings("unchecked")
    private static String extractResponseStatus(String jsonRpcRes) {
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(jsonRpcRes);
        Map<String, Object> result = (Map<String, Object>) map.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        String text = (String) content.getFirst()
                .get("text");
        Map<String, Object> parsed = (Map<String, Object>) ProviderJson.parse(text);
        return (String) parsed.get("status");
    }

    @SuppressWarnings("unchecked")
    private static String extractResponseReason(String jsonRpcRes) {
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(jsonRpcRes);
        Map<String, Object> result = (Map<String, Object>) map.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        String text = (String) content.getFirst()
                .get("text");
        Map<String, Object> parsed = (Map<String, Object>) ProviderJson.parse(text);
        return (String) parsed.get("reason");
    }

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("failure-scenarios-test-" + UUID.randomUUID()
                .toString()
                .substring(0, 8));
        Files.createDirectories(projectRoot);

        git(projectRoot, "init");
        git(projectRoot, "config", "user.name", "Test User");
        git(projectRoot, "config", "user.email", "test@example.com");
        Files.writeString(projectRoot.resolve("README.md"), "# Slice 4 Failure Scenarios Test\n");
        Files.writeString(projectRoot.resolve(".gitignore"), ".synesis/\n");
        git(projectRoot, "add", ".");
        git(projectRoot, "commit", "-m", "Initial commit");

        new ProjectApplicationService().init(projectRoot);
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        new org.synesis.workspace.application.provider.ProviderManualService().install("claude");
        new org.synesis.workspace.application.provider.ProviderManualService().install("antigravity");

        var location = new ProjectApplicationService().locate(projectRoot);
        var bindingService = new ProviderSessionBindingService();

        sessionService = new AgentSessionService();
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(projectRoot,
                "antigravity",
                "inst-req-1",
                null,
                false));
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(projectRoot,
                "codex",
                "inst-owner-1",
                null,
                false));

        git(projectRoot, "add", ".");
        commitIfNeeded(projectRoot);

        var bindings1 = bindingService.list(location, "antigravity");
        if (!bindings1.isEmpty() && bindings1.getLast()
                .worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location,
                    "antigravity",
                    bindings1.getLast()
                            .sessionId(),
                    Path.of(bindings1.getLast()
                            .worktreePath()));
        }
        var bindings2 = bindingService.list(location, "codex");
        if (!bindings2.isEmpty() && bindings2.getLast()
                .worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location,
                    "codex",
                    bindings2.getLast()
                            .sessionId(),
                    Path.of(bindings2.getLast()
                            .worktreePath()));
        }

        b1 = bindings1.getLast();
        b2 = bindings2.getLast();

        var identity = new IdentityBootstrap(location.profile()
                .resolve("link")).loadOrCreate()
                .identity();
        PredictionEventStore store = new PredictionEventStore(
                location.root()
                        .resolve(".synesis/coordination"), location.projectId());

        UUID ownerTaskId = UUID.randomUUID();
        org.synesis.coordination.domain.task.CoordinationTask ownerTask = new org.synesis.coordination.domain.task.CoordinationTask(
                ownerTaskId, location.projectId(), "Product Query Task", "catalog.product-query",
                identity.nodeId(), b2.supervisorId(), b2.workerId());
        store.append(ownerTaskId, PredictionEventType.TASK_CREATED, identity.nodeId(),
                CoordinationCommand.create(UUID.randomUUID(),
                                location.projectId(),
                                ownerTaskId,
                                PredictionEventType.TASK_CREATED,
                                identity.nodeId(),
                                ownerTask.encoded(),
                                identity)
                        .encoded(), identity);

        org.synesis.coordination.domain.task.TaskClaim claim1 = new org.synesis.coordination.domain.task.TaskClaim(
                ownerTaskId, identity.nodeId(), b2.supervisorId(), b2.workerId());
        store.append(ownerTaskId, PredictionEventType.TASK_CLAIMED, identity.nodeId(),
                CoordinationCommand.create(UUID.randomUUID(),
                                location.projectId(),
                                ownerTaskId,
                                PredictionEventType.TASK_CLAIMED,
                                identity.nodeId(),
                                claim1.encoded(),
                                identity)
                        .encoded(), identity);

        OwnershipClaim claim2 = new OwnershipClaim(ownerTaskId,
                "catalog.product-query",
                identity.nodeId(),
                b2.supervisorId(),
                List.of("catalog"),
                1L);
        store.append(ownerTaskId, PredictionEventType.OWNERSHIP_CLAIMED, identity.nodeId(),
                CoordinationCommand.create(UUID.randomUUID(),
                                location.projectId(),
                                ownerTaskId,
                                PredictionEventType.OWNERSHIP_CLAIMED,
                                identity.nodeId(),
                                claim2.encoded(),
                                identity)
                        .encoded(), identity);

        UUID reqTaskId = UUID.randomUUID();
        org.synesis.coordination.domain.task.CoordinationTask reqTask = new org.synesis.coordination.domain.task.CoordinationTask(
                reqTaskId, location.projectId(), "Product CLI Task", "catalog.product-cli",
                identity.nodeId(), b1.supervisorId(), b1.workerId());
        store.append(reqTaskId, PredictionEventType.TASK_CREATED, identity.nodeId(),
                CoordinationCommand.create(UUID.randomUUID(),
                                location.projectId(),
                                reqTaskId,
                                PredictionEventType.TASK_CREATED,
                                identity.nodeId(),
                                reqTask.encoded(),
                                identity)
                        .encoded(), identity);

        org.synesis.coordination.domain.task.TaskClaim claim3 = new org.synesis.coordination.domain.task.TaskClaim(
                reqTaskId, identity.nodeId(), b1.supervisorId(), b1.workerId());
        store.append(reqTaskId, PredictionEventType.TASK_CLAIMED, identity.nodeId(),
                CoordinationCommand.create(UUID.randomUUID(),
                                location.projectId(),
                                reqTaskId,
                                PredictionEventType.TASK_CLAIMED,
                                identity.nodeId(),
                                claim3.encoded(),
                                identity)
                        .encoded(), identity);

        requesterHandler = new McpProtocolHandler(sessionService, projectRoot, "antigravity", "inst-req-1");
        ownerHandler = new McpProtocolHandler(sessionService, projectRoot, "codex", "inst-owner-1");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.announce(projectRoot, "antigravity", "inst-req-1",
                "Implement the product CLI", "Publish the CLI implementation",
                List.of(ResourceSelector.pathExact("ProductCli.java")));
        collaboration.announce(projectRoot, "codex", "inst-owner-1",
                "Implement the product query service", "Publish the query implementation",
                List.of(ResourceSelector.pathExact("ProductQuery.java"),
                        ResourceSelector.pathExact("NewFeature.java")));

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\""
                + projectRoot.toUri()
                .toString()
                .replace("\\", "/") + "\"}}";
        requesterHandler.handleMessage(initReq);
        ownerHandler.handleMessage(initReq);
    }

    @Test
    void testCompleteTaskBeforeValidationReturnsBlockedUnresolvedDependency() {
        String descJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 2,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"request_coordination\",\n" +
                "    \"arguments\": {\n" +
                "      \"kind\": \"capability_request\",\n" +
                "      \"payload\": {\"capability\": \"catalog.product-query\",\n" +
                "      \"contract\": {\n" +
                "        \"inputs\": \"UUID id\",\n" +
                "        \"output\": \"Optional<Product>\",\n" +
                "        \"requiredBehavior\": [\"Return product when found\"],\n" +
                "        \"acceptanceTests\": [\"ProductQueryTest\"]\n" +
                "      }}\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String descRes = requesterHandler.handleMessage(descJson);
        assertNotNull(descRes);

        // Requester attempts completion before validation
        String reqCompJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 3,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"finish_lane\",\n" +
                "    \"arguments\": {\n" +
                "      \"summary\": \"Premature completion attempt\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String reqCompRes = requesterHandler.handleMessage(reqCompJson);
        assertNotNull(reqCompRes);
        assertEquals("blocked", extractResponseStatus(reqCompRes));
        assertEquals("unresolved_dependency", extractResponseReason(reqCompRes));
    }

    @Test
    void testContractRevisionAndValidationRevisionLoop() throws Exception {
        // 1. Requester describes capability
        String descJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 2,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"request_coordination\",\n" +
                "    \"arguments\": {\n" +
                "      \"kind\": \"capability_request\",\n" +
                "      \"payload\": {\"capability\": \"catalog.product-query\",\n" +
                "      \"contract\": {\n" +
                "        \"inputs\": \"UUID id\",\n" +
                "        \"output\": \"Optional<Product>\",\n" +
                "        \"requiredBehavior\": [\"Return product when found\"],\n" +
                "        \"acceptanceTests\": [\"ProductQueryTest\"]\n" +
                "      }}\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String descRes = requesterHandler.handleMessage(descJson);
        String reqHandle = extractResultField(descRes);

        // 2. Owner revises contract
        String reviseJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 3,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"respond_coordination\",\n" +
                "    \"arguments\": {\n" +
                "      \"kind\": \"capability_response\",\n" +
                "      \"payload\": {\"capabilityRequestHandle\": \"" + reqHandle
                + "\", \"response\": \"revise\", \"revision\": {\n" +
                "        \"inputs\": \"UUID id\",\n" +
                "        \"output\": \"Optional<Product>\",\n" +
                "        \"requiredBehavior\": [\"Return product when found\", \"Return empty when absent\"],\n" +
                "        \"acceptanceTests\": [\"ProductQueryTest\"]\n" +
                "      }}\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String reviseRes = ownerHandler.handleMessage(reviseJson);
        assertNotNull(reviseRes);
        assertEquals("ready", extractResponseStatus(reviseRes));

        // 2.5 Requester accepts revised contract by re-submitting request
        String reDescJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 35,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"request_coordination\",\n" +
                "    \"arguments\": {\n" +
                "      \"kind\": \"capability_request\",\n" +
                "      \"payload\": {\"capability\": \"catalog.product-query\",\n" +
                "      \"contract\": {\n" +
                "        \"inputs\": \"UUID id\",\n" +
                "        \"output\": \"Optional<Product>\",\n" +
                "        \"requiredBehavior\": [\"Return product when found\", \"Return empty when absent\"],\n" +
                "        \"acceptanceTests\": [\"ProductQueryTest\"]\n" +
                "      }}\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String reDescRes = requesterHandler.handleMessage(reDescJson);
        assertNotNull(reDescRes);

        // 3. Owner accepts after negotiation and publishes revision 1
        String acceptJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 4,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"respond_coordination\",\n" +
                "    \"arguments\": {\n" +
                "      \"kind\": \"capability_response\",\n" +
                "      \"payload\": {\"capabilityRequestHandle\": \"" + reqHandle + "\", \"response\": \"accept\"}\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String acceptRes = ownerHandler.handleMessage(acceptJson);
        assertNotNull(acceptRes);

        Path ownerWt = Path.of(b2.worktreePath());
        Files.writeString(ownerWt.resolve("ProductQuery.java"), "public class ProductQuery { // v1 }\n");
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Implement ProductQuery v1");

        String pub1Json = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 5,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"publish_capability_implementation\",\n" +
                "    \"arguments\": {\n" +
                "      \"capabilityRequestHandle\": \"" + reqHandle + "\",\n" +
                "      \"summary\": \"Revision 1\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        ownerHandler.handleMessage(pub1Json);

        // 4. Requester rejects revision 1 (revision_required)
        String valReqJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 6,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"respond_coordination\",\n" +
                "    \"arguments\": {\n" +
                "      \"kind\": \"implementation_validation\",\n" +
                "      \"payload\": {\"inboxItemId\": \"00000000-0000-0000-0000-000000000001\", \"capabilityRequestHandle\": \""
                + reqHandle
                + "\", \"implementationRevision\": 1, \"result\": \"revision_required\", \"reason\": \"Missing null check\"}\n"
                +
                "    }\n" +
                "  }\n" +
                "}";
        String valReqRes = requesterHandler.handleMessage(valReqJson);
        assertNotNull(valReqRes);
        assertEquals("blocked", extractResponseStatus(valReqRes), valReqRes);

        // 5. Owner publishes revision 2
        Files.writeString(ownerWt.resolve("ProductQuery.java"), "public class ProductQuery { // v2 null check }\n");
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Implement ProductQuery v2");

        String pub2Json = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 7,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"publish_capability_implementation\",\n" +
                "    \"arguments\": {\n" +
                "      \"capabilityRequestHandle\": \"" + reqHandle + "\",\n" +
                "      \"summary\": \"Revision 2\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String pub2Res = ownerHandler.handleMessage(pub2Json);
        assertNotNull(pub2Res);

        // 6. Requester accepts revision 2
        String val2Json = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 8,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"respond_coordination\",\n" +
                "    \"arguments\": {\n" +
                "      \"kind\": \"implementation_validation\",\n" +
                "      \"payload\": {\"inboxItemId\": \"00000000-0000-0000-0000-000000000002\", \"capabilityRequestHandle\": \""
                + reqHandle + "\", \"implementationRevision\": 2, \"result\": \"accepted\"}\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String val2Res = requesterHandler.handleMessage(val2Json);
        assertNotNull(val2Res);
        assertEquals("blocked", extractResponseStatus(val2Res));
    }

    @Test
    void testDirtyControlCheckoutPreventsAdvancement() throws Exception {
        // Owner writes and commits a new file in worktree
        Path ownerWt = Path.of(b2.worktreePath());
        Files.writeString(ownerWt.resolve("NewFeature.java"), "public class NewFeature {}\n");
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Add NewFeature");

        // Dirty the control checkout
        Files.writeString(projectRoot.resolve("dirty_file.tmp"), "uncommitted change");

        // Owner completes task
        String ownerCompJson = "{\n" +
                "  \"jsonrpc\": \"2.0\",\n" +
                "  \"id\": 2,\n" +
                "  \"method\": \"tools/call\",\n" +
                "  \"params\": {\n" +
                "    \"name\": \"finish_lane\",\n" +
                "    \"arguments\": {\n" +
                "      \"summary\": \"Complete while control checkout is dirty\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String ownerCompRes = ownerHandler.handleMessage(ownerCompJson);
        assertNotNull(ownerCompRes);
        assertEquals("waiting", extractResponseStatus(ownerCompRes), ownerCompRes);
        assertEquals("integration_pending", extractResponseReason(ownerCompRes));
    }
}
