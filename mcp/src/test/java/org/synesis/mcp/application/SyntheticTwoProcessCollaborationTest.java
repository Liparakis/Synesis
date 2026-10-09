package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Exercises the synthetic two-process collaboration lifecycle.
 */
@SuppressWarnings("TextBlockMigration")
class SyntheticTwoProcessCollaborationTest {

    @TempDir
    Path tempDir;

    @SuppressWarnings("FieldCanBeLocal")
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

    private static void commitIfNeeded(Path root) throws Exception {
        Process process = new ProcessBuilder("git", "-C", root.toString(), "status", "--porcelain").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
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
        String text = (String) content.getFirst().get("text");
        Map<String, Object> parsed = (Map<String, Object>) ProviderJson.parse(text);
        Map<String, Object> innerResult = (Map<String, Object>) parsed.get("result");
        return innerResult != null ? (String) innerResult.get("capabilityRequestHandle") : null;
    }

    @SuppressWarnings("unchecked")
    private static long extractContractRevision(String jsonRpcRes) {
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(jsonRpcRes);
        Map<String, Object> result = (Map<String, Object>) map.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        String text = (String) content.getFirst().get("text");
        Map<String, Object> parsed = (Map<String, Object>) ProviderJson.parse(text);
        Map<String, Object> innerResult = (Map<String, Object>) parsed.get("result");
        return ((Number) innerResult.get("contractRevision")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractWorkflowArguments(String jsonRpcRes) {
        Map<String, Object> innerResult = extractInnerResult(jsonRpcRes);
        Map<String, Object> workflow = (Map<String, Object>) innerResult.get("workflow");
        return (Map<String, Object>) workflow.get("arguments");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractInnerResponse(String jsonRpcRes) {
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(jsonRpcRes);
        Map<String, Object> result = (Map<String, Object>) map.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        String text = (String) content.getFirst().get("text");
        return (Map<String, Object>) ProviderJson.parse(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractInnerResult(String jsonRpcRes) {
        return (Map<String, Object>) extractInnerResponse(jsonRpcRes).get("result");
    }

    private String toolCall(McpProtocolHandler handler, String name, Map<String, Object> arguments) {
        return handler.handleMessage(ProviderJson.write(Map.of("jsonrpc",
                "2.0",
                "id",
                UUID.randomUUID().toString(),
                "method",
                "tools/call",
                "params",
                Map.of("name", name, "arguments", arguments))));
    }

    private static String extractResponseReason(String jsonRpcRes) {
        return (String) extractInnerResponse(jsonRpcRes).get("reason");
    }

    @SuppressWarnings("unchecked")
    private static String extractResponseStatus(String jsonRpcRes) {
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(jsonRpcRes);
        Map<String, Object> result = (Map<String, Object>) map.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        String text = (String) content.getFirst().get("text");
        Map<String, Object> parsed = (Map<String, Object>) ProviderJson.parse(text);
        return (String) parsed.get("status");
    }

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("synthetic-collaboration-test");
        Files.createDirectories(projectRoot);

        git(projectRoot, "init");
        git(projectRoot, "config", "user.name", "Test User");
        git(projectRoot, "config", "user.email", "test@example.com");
        Files.writeString(projectRoot.resolve("README.md"), "# Synthetic Two Process Collaboration Test\n");
        Files.writeString(projectRoot.resolve("pyproject.toml"), "[tool.pytest.ini_options]\ntestpaths = ['tests']\n");
        Files.createDirectories(projectRoot.resolve("tests"));
        Files.writeString(projectRoot.resolve("tests/test_smoke.py"), "def test_smoke():\n    assert True\n");
        Files.writeString(projectRoot.resolve(".gitignore"), ".synesis/\n");
        git(projectRoot, "add", ".");
        git(projectRoot, "commit", "-m", "Initial commit");

        ProjectApplicationService projectService = new ProjectApplicationService();
        projectService.init(projectRoot);
        var location = projectService.locate(projectRoot);
        McpProviderTestSupport.install(location, "codex");
        McpProviderTestSupport.install(location, "claude");
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        new org.synesis.workspace.application.provider.ProviderManualService().install("claude");
        git(projectRoot, "add", ".");
        commitIfNeeded(projectRoot);

        location = projectService.locate(projectRoot);
        var bindingService = new ProviderSessionBindingService();

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new SessionResolutionRequest(projectRoot, "claude", "inst-req-1", null, false));
        sessionService.ensureSession(new SessionResolutionRequest(projectRoot, "codex", "inst-owner-1", null, false));

        git(projectRoot, "add", ".");
        commitIfNeeded(projectRoot);

        var bindings1 = bindingService.list(location, "claude");
        if (!bindings1.isEmpty() && bindings1.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "claude", bindings1.getLast().sessionId(), Path.of(bindings1.getLast().worktreePath()));
        }
        var bindings2 = bindingService.list(location, "codex");
        if (!bindings2.isEmpty() && bindings2.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "codex", bindings2.getLast().sessionId(), Path.of(bindings2.getLast().worktreePath()));
        }

        b1 = bindingService.find(location, "claude", "inst-req-1").orElseThrow();
        b2 = bindingService.find(location, "codex", "inst-owner-1").orElseThrow();

        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());

        // 1. Task for owner (Codex)
        UUID ownerTaskId = UUID.randomUUID();
        org.synesis.coordination.domain.task.CoordinationTask ownerTask = new org.synesis.coordination.domain.task.CoordinationTask(ownerTaskId,
                location.projectId(),
                "Product Query Task",
                "catalog.product-query",
                identity.nodeId(),
                b2.supervisorId(),
                b2.workerId());
        CoordinationCommand cmd1 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), ownerTaskId, PredictionEventType.TASK_CREATED, identity.nodeId(), ownerTask.encoded(), identity);
        store.append(ownerTaskId, PredictionEventType.TASK_CREATED, identity.nodeId(), cmd1.encoded(), identity);

        org.synesis.coordination.domain.task.TaskClaim claim1 = new org.synesis.coordination.domain.task.TaskClaim(ownerTaskId, identity.nodeId(), b2.supervisorId(), b2.workerId());
        CoordinationCommand cmd2 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), ownerTaskId, PredictionEventType.TASK_CLAIMED, identity.nodeId(), claim1.encoded(), identity);
        store.append(ownerTaskId, PredictionEventType.TASK_CLAIMED, identity.nodeId(), cmd2.encoded(), identity);

        OwnershipClaim claim2 = new OwnershipClaim(ownerTaskId, "catalog.product-query", identity.nodeId(), b2.supervisorId(), List.of("catalog"), 1L);
        CoordinationCommand cmd3 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), ownerTaskId, PredictionEventType.OWNERSHIP_CLAIMED, identity.nodeId(), claim2.encoded(), identity);
        store.append(ownerTaskId, PredictionEventType.OWNERSHIP_CLAIMED, identity.nodeId(), cmd3.encoded(), identity);

        // 2. Task for requester (Claude)
        UUID reqTaskId = UUID.randomUUID();
        org.synesis.coordination.domain.task.CoordinationTask reqTask = new org.synesis.coordination.domain.task.CoordinationTask(reqTaskId,
                location.projectId(),
                "Product CLI Task",
                "catalog.product-cli",
                identity.nodeId(),
                b1.supervisorId(),
                b1.workerId());
        CoordinationCommand cmd4 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), reqTaskId, PredictionEventType.TASK_CREATED, identity.nodeId(), reqTask.encoded(), identity);
        store.append(reqTaskId, PredictionEventType.TASK_CREATED, identity.nodeId(), cmd4.encoded(), identity);

        org.synesis.coordination.domain.task.TaskClaim claim3 = new org.synesis.coordination.domain.task.TaskClaim(reqTaskId, identity.nodeId(), b1.supervisorId(), b1.workerId());
        CoordinationCommand cmd5 = CoordinationCommand.create(UUID.randomUUID(), location.projectId(), reqTaskId, PredictionEventType.TASK_CLAIMED, identity.nodeId(), claim3.encoded(), identity);
        store.append(reqTaskId, PredictionEventType.TASK_CLAIMED, identity.nodeId(), cmd5.encoded(), identity);

        requesterHandler = new McpProtocolHandler(sessionService, projectRoot, "claude", "inst-req-1");
        ownerHandler = new McpProtocolHandler(sessionService, projectRoot, "codex", "inst-owner-1");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.announce(projectRoot, "claude", "inst-req-1", "Implement the product CLI", "Publish the CLI implementation", List.of(ResourceSelector.pathExact("ProductCli.java")));
        collaboration.announce(projectRoot, "codex", "inst-owner-1", "Implement the product query service", "Publish the query implementation", List.of(ResourceSelector.pathExact("ProductQuery.java")));

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + projectRoot.toUri().toString().replace("\\", "/") + "\"}}";
        requesterHandler.handleMessage(initReq);
        ownerHandler.handleMessage(initReq);
    }

    @Test
    void fullSlice3TaskCompletionAndIntegrationFlow() throws Exception {
        // 1. Requester describes capability
        String descJson = "{\n" + "  \"jsonrpc\": \"2.0\",\n" + "  \"id\": 2,\n" + "  \"method\": \"tools/call\",\n" + "  \"params\": {\n" + "    \"name\": \"request_coordination\",\n" + "    \"arguments\": {\n"
                + "      \"kind\": \"capability_request\",\n" + "      \"payload\": {\"capability\": \"catalog.product-query\",\n" + "      \"contract\": {\n" + "        \"purpose\": \"Look up a product by id\",\n" + "        \"inputs\": \"UUID id\",\n"
                + "        \"output\": \"Optional<Product>\",\n" + "        \"requiredBehavior\": [\"Return product when found\"],\n" + "        \"errorSemantics\": \"Malformed ids are rejected; unknown products return empty\",\n" + "        \"sideEffects\": \"none\",\n" + "        \"invariants\": \"Does not mutate product records\",\n" + "        \"compatibility\": \"Additive changes only\",\n" + "        \"performance\": \"One lookup per call\",\n" + "        \"concurrency\": \"Concurrent reads are safe\",\n" + "        \"acceptanceTests\": [\"ProductQueryTest\"]\n" + "      }}\n" + "    }\n"
                + "  }\n" + "}";
        String descRes = requesterHandler.handleMessage(descJson);
        assertNotNull(descRes);
        String reqHandle = extractResultField(descRes);
        assertNotNull(reqHandle);

        // 2. Owner accepts the exact contract revision
        String ownerNextJson = "{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tools/call\",\"params\":{\"name\":\"get_next_action\",\"arguments\":{}}}";
        long contractRevision = extractContractRevision(ownerHandler.handleMessage(ownerNextJson));
        String acceptJson = "{\n" + "  \"jsonrpc\": \"2.0\",\n" + "  \"id\": 3,\n" + "  \"method\": \"tools/call\",\n" + "  \"params\": {\n" + "    \"name\": \"respond_coordination\",\n" + "    \"arguments\": {\n"
                + "      \"kind\": \"capability_response\",\n" + "      \"payload\": {\"capabilityRequestHandle\": \"" + reqHandle + "\", \"response\": \"accept\", \"contractRevision\": " + contractRevision + "}\n" + "    }\n" + "  }\n" + "}";
        String acceptRes = ownerHandler.handleMessage(acceptJson);
        assertNotNull(acceptRes);

        // 3. Owner writes implementation code and publishes
        Path ownerWt = Path.of(b2.worktreePath());
        Files.writeString(ownerWt.resolve("ProductQuery.java"), "public class ProductQuery {}\n");
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Implement ProductQuery");

        String publicationReadyJson = "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\",\"params\":{\"name\":\"get_next_action\",\"arguments\":{\"completionRequested\":true}}}";
        String publicationReady = ownerHandler.handleMessage(publicationReadyJson);
        assertEquals("ready", extractResponseStatus(publicationReady), publicationReady);
        Map<String, Object> publicationArguments = extractWorkflowArguments(publicationReady);
        assertEquals(reqHandle, publicationArguments.get("capabilityRequestHandle"));
        assertEquals(contractRevision, ((Number) publicationArguments.get("contractRevision")).longValue());
        assertNotNull(publicationArguments.get("intentId"));
        assertNotNull(publicationArguments.get("claimEpoch"));
        assertNotNull(publicationArguments.get("expectedTreeHash"));
        publicationArguments.put("summary", "Implemented product query");
        String pubJson = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"publish_capability_implementation\",\"arguments\":" + ProviderJson.write(publicationArguments) + "}}";
        String pubRes = ownerHandler.handleMessage(pubJson);
        assertNotNull(pubRes);
        assertEquals("waiting", extractResponseStatus(pubRes), pubRes);
        assertEquals("validation_required", extractResponseReason(pubRes), pubRes);
        Map<String, Object> publicationResult = extractInnerResult(pubRes);
        assertEquals(1, ((Number) publicationResult.get("revision")).intValue());
        assertNotNull(publicationResult.get("commitSha"));
        assertNotNull(publicationResult.get("snapshotId"));
        assertEquals(Boolean.FALSE, publicationResult.get("replayed"));

        // 4. Requester opens, reads, and tests the exact immutable capability revision before deciding.
        String validationNext = toolCall(requesterHandler, "get_next_action", Map.of());
        Map<String, Object> inspectArguments = extractWorkflowArguments(validationNext);
        @SuppressWarnings("unchecked") Map<String, Object> inspectPayload = (Map<String, Object>) inspectArguments.get("payload");
        assertEquals("respond_coordination", ((Map<?, ?>) extractInnerResult(validationNext).get("workflow")).get("recommendedTool"));
        assertEquals("inspect", inspectPayload.get("result"));
        assertEquals(reqHandle, inspectPayload.get("capabilityRequestHandle"));
        assertEquals(1, ((Number) inspectPayload.get("implementationRevision")).intValue());
        assertEquals("ready", extractResponseStatus(toolCall(requesterHandler, "respond_coordination", inspectArguments)));

        String read = toolCall(requesterHandler,
                "read_file",
                Map.of("path", "ProductQuery.java", "capabilityRequestHandle", reqHandle, "implementationRevision", 1));
        assertEquals("completed", extractResponseStatus(read), read);
        assertTrue(String.valueOf(extractInnerResult(read).get("content")).contains("ProductQuery"), read);
        String test = toolCall(requesterHandler,
                "run_command",
                Map.of("argv", List.of("git", "rev-parse", "HEAD"), "capabilityRequestHandle", reqHandle, "implementationRevision", 1));
        assertEquals("completed", extractResponseStatus(test), test);
        assertEquals(String.valueOf(publicationResult.get("commitSha")), String.valueOf(extractInnerResult(test).get("stdout")).trim(), test);

        String valRes = toolCall(requesterHandler,
                "respond_coordination",
                Map.of("kind", "implementation_validation",
                        "payload", Map.of("inboxItemId", inspectPayload.get("inboxItemId"),
                                "capabilityRequestHandle", reqHandle,
                                "implementationRevision", 1,
                                "result", "accepted")));
        assertEquals("completed", extractResponseStatus(valRes), valRes);

        // 5. Owner completes task
        String ownerCompJson = "{\n" + "  \"jsonrpc\": \"2.0\",\n" + "  \"id\": 6,\n" + "  \"method\": \"tools/call\",\n" + "  \"params\": {\n" + "    \"name\": \"finish_lane\",\n" + "    \"arguments\": {\n"
                + "      \"summary\": \"Product query service complete\"\n" + "    }\n" + "  }\n" + "}";
        String ownerCompRes = ownerHandler.handleMessage(ownerCompJson);
        assertNotNull(ownerCompRes);

        // 6. Requester writes CLI code and completes task
        Path reqWt = Path.of(b1.worktreePath());
        Files.writeString(reqWt.resolve("ProductCli.java"), "public class ProductCli {}\n");
        git(reqWt, "add", ".");
        git(reqWt, "commit", "-m", "Implement ProductCli");

        // 6. Requester completes task and triggers integration
        String reqCompJson = "{\n" + "  \"jsonrpc\": \"2.0\",\n" + "  \"id\": 7,\n" + "  \"method\": \"tools/call\",\n" + "  \"params\": {\n" + "    \"name\": \"finish_lane\",\n" + "    \"arguments\": {\n"
                + "      \"summary\": \"Product CLI integration complete\"\n" + "    }\n" + "  }\n" + "}";
        String reqCompRes = requesterHandler.handleMessage(reqCompJson);
        assertNotNull(reqCompRes);
        assertEquals("completed", extractResponseStatus(reqCompRes), reqCompRes);
    }

    private static Map<String, Object> productQueryContract(String requiredBehavior) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("purpose", "Look up a product by id");
        contract.put("inputs", "UUID id");
        contract.put("output", "Optional<Product>");
        contract.put("requiredBehavior", List.of(requiredBehavior));
        contract.put("errorSemantics", "Malformed ids are rejected; unknown products return empty");
        contract.put("sideEffects", "none");
        contract.put("invariants", "Does not mutate product records");
        contract.put("compatibility", "Additive changes only");
        contract.put("performance", "One lookup per call");
        contract.put("concurrency", "Concurrent reads are safe");
        contract.put("acceptanceTests", List.of("ProductQueryTest"));
        return contract;
    }

    private PredictionEventStore replayedStore() throws Exception {
        var location = new ProjectApplicationService().locate(projectRoot);
        return new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
    }

    /**
     * Follows the order a real two-agent run took: the owner revises the contract, the requester
     * finishes its lane before the owner does, and the requester keeps polling while the owner
     * finishes. Each step uses only what Synesis projected to that agent.
     */
    @Test
    @SuppressWarnings("unchecked")
    void revisedContractIsAcceptedPublishedValidatedAndIntegratedWhileThePeerKeepsPolling() throws Exception {
        // 1. The requester asks and the owner answers with a replacement contract.
        String described = toolCall(requesterHandler,
                "request_coordination",
                Map.of("kind", "capability_request", "payload", Map.of("capability", "catalog.product-query", "contract", productQueryContract("Return product when found"))));
        String reqHandle = extractResultField(described);
        assertNotNull(reqHandle, described);
        long requestedRevision = extractContractRevision(toolCall(ownerHandler, "get_next_action", Map.of()));
        String revised = toolCall(ownerHandler,
                "respond_coordination",
                Map.of("kind", "capability_response",
                        "payload", Map.of("capabilityRequestHandle", reqHandle,
                                "response", "revise",
                                "contractRevision", requestedRevision,
                                "revision", productQueryContract("Return product when found; reject null ids"),
                                "reason", "Null ids must be rejected")));
        assertEquals("ready", extractResponseStatus(revised), revised);

        // 2. The requester is handed the exact accept call and a mismatch names what differs.
        String revisionNext = toolCall(requesterHandler, "get_next_action", Map.of());
        Map<String, Object> revisionResult = extractInnerResult(revisionNext);
        assertEquals("catalog.product-query", revisionResult.get("capability"), revisionNext);
        Map<String, Object> acceptRevision = (Map<String, Object>) revisionResult.get("acceptRevision");
        assertNotNull(acceptRevision, revisionNext);
        assertEquals("request_coordination", acceptRevision.get("tool"), revisionNext);
        Map<String, Object> acceptArguments = (Map<String, Object>) acceptRevision.get("arguments");
        long acceptedRevision = ((Number) ((Map<String, Object>) acceptArguments.get("payload")).get("contractRevision")).longValue();
        assertTrue(acceptedRevision > requestedRevision, revisionNext);

        Map<String, Object> misnamedPayload = new LinkedHashMap<>((Map<String, Object>) acceptArguments.get("payload"));
        misnamedPayload.put("capability", "catalog.product-lookup");
        String misnamed = toolCall(requesterHandler, "request_coordination", Map.of("kind", "capability_request", "payload", misnamedPayload));
        assertEquals("stale_request", extractResponseReason(misnamed), misnamed);
        assertEquals("CAPABILITY_NAME_MISMATCH", extractInnerResult(misnamed).get("reason"), misnamed);

        String accepted = toolCall(requesterHandler, "request_coordination", acceptArguments);
        assertEquals("ready", extractResponseStatus(accepted), accepted);
        assertEquals(Boolean.TRUE, extractInnerResult(accepted).get("contractAccepted"), accepted);

        // 3. The owner implements and publishes; the requester inspects, tests, and accepts.
        Path ownerWt = Path.of(b2.worktreePath());
        Files.writeString(ownerWt.resolve("ProductQuery.java"), "public class ProductQuery {}\n");
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Implement ProductQuery");
        String publicationReady = toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true));
        Map<String, Object> publicationArguments = new LinkedHashMap<>(extractWorkflowArguments(publicationReady));
        assertEquals(acceptedRevision, ((Number) publicationArguments.get("contractRevision")).longValue(), publicationReady);
        publicationArguments.put("summary", "Implemented product query");
        String published = toolCall(ownerHandler, "publish_capability_implementation", publicationArguments);
        assertEquals("waiting", extractResponseStatus(published), published);

        // The requester has not judged the implementation yet and may still send it back, so an
        // owner asking to finish is told to wait rather than offered a finish it would be refused.
        String earlyFinish = toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true));
        assertEquals("waiting", extractResponseStatus(earlyFinish), earlyFinish);
        assertEquals("validation_required", extractResponseReason(earlyFinish), earlyFinish);
        assertEquals("IMPLEMENTATION_AWAITING_VALIDATION", extractInnerResult(earlyFinish).get("reason"), earlyFinish);

        Map<String, Object> inspectArguments = extractWorkflowArguments(toolCall(requesterHandler, "get_next_action", Map.of()));
        Map<String, Object> inspectPayload = (Map<String, Object>) inspectArguments.get("payload");
        assertEquals("ready", extractResponseStatus(toolCall(requesterHandler, "respond_coordination", inspectArguments)));
        String validated = toolCall(requesterHandler,
                "respond_coordination",
                Map.of("kind", "implementation_validation",
                        "payload", Map.of("inboxItemId", inspectPayload.get("inboxItemId"),
                                "capabilityRequestHandle", reqHandle,
                                "implementationRevision", 1,
                                "result", "accepted")));
        assertEquals("completed", extractResponseStatus(validated), validated);

        // 4. The requester finishes first, so its snapshot waits on the owner's unfinished lane.
        Path reqWt = Path.of(b1.worktreePath());
        Files.writeString(reqWt.resolve("ProductCli.java"), "public class ProductCli {}\n");
        git(reqWt, "add", ".");
        git(reqWt, "commit", "-m", "Implement ProductCli");
        String requesterProjection = toolCall(requesterHandler, "get_next_action", Map.of("completionRequested", true));
        String requesterFinished = toolCall(requesterHandler, "finish_lane", extractWorkflowArguments(requesterProjection));
        assertEquals("waiting", extractResponseStatus(requesterFinished), requesterFinished);
        assertEquals(1, replayedStore().taskCompletionProjection().allSnapshots().size(), requesterFinished);

        // The requester's lane is now closing: its completion is prepared and it will not read
        // its inbox again. A request addressed to it is not recorded, and the asker is told to wait.
        String closingLineage = replayedStore().collaborationProjection().activeIntents().stream()
                .filter(intent -> intent.provider().equals("claude")).findFirst().orElseThrow().authorityLineageId().toString();
        long requestsBefore = replayedStore().events().stream().filter(event -> event.type() == PredictionEventType.CAPABILITY_REQUEST_CREATED).count();
        String lateRequest = toolCall(ownerHandler,
                "request_coordination",
                Map.of("kind", "capability_request",
                        "payload", Map.of("capability", "catalog.cli-output", "ownerAuthorityLineageId", closingLineage, "contract", productQueryContract("Print one line per product"))));
        assertEquals("waiting", extractResponseStatus(lateRequest), lateRequest);
        assertEquals("CAPABILITY_OWNER_COMPLETING", extractInnerResult(lateRequest).get("reason"), lateRequest);
        assertEquals(requestsBefore, replayedStore().events().stream().filter(event -> event.type() == PredictionEventType.CAPABILITY_REQUEST_CREATED).count(), lateRequest);

        // 5. An ordinary owner poll names the completion request instead of the shared wait.
        String ownerPoll = toolCall(ownerHandler, "get_next_action", Map.of());
        Map<String, Object> ownerPollResult = extractInnerResult(ownerPoll);
        assertEquals(Boolean.TRUE, ownerPollResult.get("implementationValidated"), ownerPoll);
        Map<String, Object> ownerWorkflow = (Map<String, Object>) ownerPollResult.get("workflow");
        assertEquals("get_next_action", ownerWorkflow.get("recommendedTool"), ownerPoll);
        assertEquals(Map.of("completionRequested", true), ownerWorkflow.get("arguments"), ownerPoll);

        // 6. The requester polls between the owner's finish projection and the owner's finish.
        String ownerProjection = toolCall(ownerHandler, "get_next_action", (Map<String, Object>) ownerWorkflow.get("arguments"));
        Map<String, Object> ownerFinish = extractWorkflowArguments(ownerProjection);
        long projectedRevision = ((Number) ownerFinish.get("expectedRevision")).longValue();
        toolCall(requesterHandler, "get_next_action", Map.of());
        PredictionEventStore beforeFinish = replayedStore();
        assertTrue(beforeFinish.headSequence() > projectedRevision, "the peer poll must have advanced the log");
        assertTrue(beforeFinish.unchangedSince(projectedRevision), "only heartbeats may separate the projection from the finish");
        String ownerFinished = toolCall(ownerHandler, "finish_lane", ownerFinish);
        assertEquals("integrated", ((Map<String, Object>) extractInnerResult(ownerFinished).get("completion")).get("task"), ownerFinished);

        // 7. The requester's next poll pumps its now-unblocked snapshot; nothing is left claimed.
        String requesterDone = toolCall(requesterHandler, "get_next_action", Map.of());
        PredictionEventStore done = replayedStore();
        assertEquals(2, done.events().stream().filter(event -> event.type() == PredictionEventType.TASK_INTEGRATED).count(), requesterDone);
        assertTrue(done.collaborationProjection().activeIntents().isEmpty(), requesterDone);
        assertEquals("COMPLETED", done.workGroupProjection().groups().getFirst().status().name(), requesterDone);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dependencyOnALaneAlreadyIntegratedIsSatisfiedByItsWorkInsteadOfDemandingAnOwner() throws Exception {
        // 1. The CLI lane declares a dependency nobody has been asked for. While the other lane is
        //    open, that is still a negotiation to be had.
        String declared = toolCall(requesterHandler,
                "ensure_session",
                Map.of("task", Map.of("goal", "Implement the product CLI",
                        "acceptance", "Publish the CLI implementation",
                        "claims", List.of(Map.of("kind", "path_exact", "path", "ProductCli.java")),
                        "knownDependencies", List.of("catalog.product-store"))));
        if ("blocked".equals(extractResponseStatus(declared))) {
            // Extending an announced intent goes through the exact call the server projects.
            Map<String, Object> exactExtension = (Map<String, Object>) ((Map<String, Object>) extractInnerResult(declared).get("nextProtocolPayload")).get("arguments");
            declared = toolCall(requesterHandler, "ensure_session", exactExtension);
        }
        assertEquals("ready", extractResponseStatus(declared), declared);
        String whileOwnerOpen = toolCall(requesterHandler, "get_next_action", Map.of());
        assertEquals("owner_required", extractResponseReason(whileOwnerOpen), whileOwnerOpen);

        // 2. The other lane finishes and is integrated without ever being asked.
        Path ownerWt = Path.of(b2.worktreePath());
        Files.writeString(ownerWt.resolve("ProductQuery.java"), "public class ProductQuery {}\n");
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Implement ProductQuery");
        String ownerProjection = toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true));
        String ownerFinished = toolCall(ownerHandler, "finish_lane", extractWorkflowArguments(ownerProjection));
        assertEquals("integrated", ((Map<String, Object>) extractInnerResult(ownerFinished).get("completion")).get("task"), ownerFinished);

        // 3. Nobody is left to own the dependency, and the work it named is in the CLI lane's
        //    base. The inbox says so instead of asking for an owner again.
        String afterIntegration = toolCall(requesterHandler, "get_next_action", Map.of());
        if ("retry_required".equals(extractResponseStatus(afterIntegration))) {
            toolCall(requesterHandler, "ensure_session", Map.of());
            afterIntegration = toolCall(requesterHandler, "get_next_action", Map.of());
        }
        assertEquals("ready", extractResponseStatus(afterIntegration), afterIntegration);
        assertEquals(List.of("catalog.product-store"), extractInnerResult(afterIntegration).get("dependenciesIntegrated"), afterIntegration);

        String asked = toolCall(requesterHandler,
                "request_coordination",
                Map.of("kind", "capability_request", "payload", Map.of("capability", "catalog.product-store", "contract", productQueryContract("Persist products"))));
        assertEquals("ready", extractResponseStatus(asked), asked);
        assertEquals("CAPABILITY_OWNER_INTEGRATED", extractInnerResult(asked).get("reason"), asked);
        assertTrue(replayedStore().capabilityRequestProjection().records().isEmpty(), "no request may be recorded against a lane that is gone");

        // 4. The CLI lane can therefore finish, and the work group completes.
        String refreshed = toolCall(requesterHandler, "ensure_session", Map.of());
        assertEquals("ready", extractResponseStatus(refreshed), refreshed);
        Path reqWt = Path.of(String.valueOf(extractInnerResult(refreshed).get("worktree")));
        Files.writeString(reqWt.resolve("ProductCli.java"), "public class ProductCli {}\n");
        git(reqWt, "add", ".");
        git(reqWt, "commit", "-m", "Implement ProductCli");
        String requesterProjection = toolCall(requesterHandler, "get_next_action", Map.of("completionRequested", true));
        assertEquals("finish_lane", extractInnerResponse(requesterProjection).get("nextAction"), requesterProjection);
        String requesterFinished = toolCall(requesterHandler, "finish_lane", extractWorkflowArguments(requesterProjection));
        assertEquals("completed", extractResponseStatus(requesterFinished), requesterFinished);
        PredictionEventStore done = replayedStore();
        assertEquals(2, done.events().stream().filter(event -> event.type() == PredictionEventType.TASK_INTEGRATED).count(), requesterFinished);
        assertTrue(done.collaborationProjection().activeIntents().isEmpty(), requesterFinished);
        assertEquals("COMPLETED", done.workGroupProjection().groups().getFirst().status().name(), requesterFinished);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dependencyDeclaredAfterTheOtherWorkGroupCompletedIsSatisfiedByItsIntegratedWork() throws Exception {
        // 1. Both announced lanes finish, which completes their work group.
        Path ownerWt = Path.of(b2.worktreePath());
        Files.writeString(ownerWt.resolve("ProductQuery.java"), "public class ProductQuery {}\n");
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Implement ProductQuery");
        toolCall(ownerHandler, "finish_lane", extractWorkflowArguments(toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true))));
        String refreshed = toolCall(requesterHandler, "ensure_session", Map.of());
        Path reqWt = Path.of(String.valueOf(extractInnerResult(refreshed).get("worktree")));
        Files.writeString(reqWt.resolve("ProductCli.java"), "public class ProductCli {}\n");
        git(reqWt, "add", ".");
        git(reqWt, "commit", "-m", "Implement ProductCli");
        toolCall(requesterHandler, "finish_lane", extractWorkflowArguments(toolCall(requesterHandler, "get_next_action", Map.of("completionRequested", true))));
        assertEquals("COMPLETED", replayedStore().workGroupProjection().groups().getFirst().status().name());

        // 2. A session that arrives afterwards announces work that depends on what they built.
        //    Its lane starts a work group of its own; nobody in the project is left to ask.
        AgentSessionService lateSessions = new AgentSessionService();
        lateSessions.ensureSession(new SessionResolutionRequest(projectRoot, "claude", "inst-late-1", null, false));
        var bindingService = new ProviderSessionBindingService();
        var projectLocation = new ProjectApplicationService().locate(projectRoot);
        var lateBinding = bindingService.find(projectLocation, "claude", "inst-late-1").orElseThrow();
        bindingService.verifyWorkspaceTrust(projectLocation, "claude", lateBinding.sessionId(), Path.of(lateBinding.worktreePath()));
        McpProtocolHandler lateHandler = new McpProtocolHandler(lateSessions, projectRoot, "claude", "inst-late-1");
        lateHandler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + projectRoot.toUri().toString().replace("\\", "/") + "\"}}");
        String declared = toolCall(lateHandler,
                "ensure_session",
                Map.of("task", Map.of("goal", "Add a product report",
                        "acceptance", "Publish the report",
                        "claims", List.of(Map.of("kind", "path_exact", "path", "ProductReport.java")),
                        "knownDependencies", List.of("catalog.product-store"))));
        assertEquals("ready", extractResponseStatus(declared), declared);
        assertEquals(2, replayedStore().workGroupProjection().groups().size(), "the late lane is not part of the completed work group");

        String next = toolCall(lateHandler, "get_next_action", Map.of());
        assertEquals("ready", extractResponseStatus(next), next);
        assertEquals(List.of("catalog.product-store"), extractInnerResult(next).get("dependenciesIntegrated"), next);
        lateHandler.close();
    }
}
