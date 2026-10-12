package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import org.synesis.coordination.application.RequesterAnswerPolicy;
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
        return ((Number) innerResult.get("revision")).longValue();
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
        // 1. The owner publishes the one contract over its file and the requester binds to it
        long contractRevision = extractContractRevision(ownerPublishesContract("Return product when found"));
        String reqHandle = bind(requesterHandler, contractRevision);

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
        assertEquals(requestOf(reqHandle).contractRevision(), ((Number) publicationArguments.get("contractRevision")).longValue());
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

    /** The owner publishes, or revises, the one contract over its file. {@code answers} are the change requests the revision covers. */
    private String ownerPublishesContract(String requiredBehavior, String... answers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paths", List.of("ProductQuery.java"));
        payload.put("contract", productQueryContract(requiredBehavior));
        if (answers.length > 0) {
            payload.put("answers", List.of(answers));
        }
        String published = toolCall(ownerHandler, "request_coordination", Map.of("kind", "contract_publish", "payload", payload));
        assertEquals("completed", extractResponseStatus(published), published);
        return published;
    }

    private static Map<String, Object> bindArguments(long revision) {
        return Map.of("kind", "contract_bind", "payload", Map.of("capability", "catalog.product-query", "ownerPath", "ProductQuery.java", "revision", revision));
    }

    /** The consumer binds to the exact revision it read and is given the handle of its binding. */
    private String bind(McpProtocolHandler consumer, long revision) {
        String bound = toolCall(consumer, "request_coordination", bindArguments(revision));
        assertEquals("ready", extractResponseStatus(bound), bound);
        assertEquals(Boolean.TRUE, extractInnerResult(bound).get("contractAccepted"), bound);
        String handle = extractResultField(bound);
        assertNotNull(handle, bound);
        return handle;
    }

    private String fileChangeRequest(McpProtocolHandler consumer, String need) {
        String filed = toolCall(consumer, "request_coordination", Map.of("kind", "contract_change_request", "payload", Map.of("ownerPath", "ProductQuery.java", "need", need)));
        assertEquals("waiting", extractResponseStatus(filed), filed);
        assertEquals("owner_response_pending", extractResponseReason(filed), filed);
        return String.valueOf(extractInnerResult(filed).get("changeRequest"));
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
        // 1. The requester binds to the first revision and the owner then publishes a second.
        ownerPublishesContract("Return product when found");
        String reqHandle = bind(requesterHandler, 1);
        long requestedRevision = 1;
        ownerPublishesContract("Return product when found; reject null ids");

        // 2. The requester is handed the exact bind call; a bind to the superseded revision is told the current one.
        String revisionNext = toolCall(requesterHandler, "get_next_action", Map.of());
        Map<String, Object> revisionResult = extractInnerResult(revisionNext);
        assertEquals("catalog.product-query", revisionResult.get("capability"), revisionNext);
        assertFalse(revisionResult.containsKey("acceptRevision"), revisionNext);
        Map<String, Object> bindAgain = (Map<String, Object>) revisionResult.get("bindAgain");
        assertNotNull(bindAgain, revisionNext);
        assertEquals("request_coordination", bindAgain.get("tool"), revisionNext);
        Map<String, Object> acceptArguments = (Map<String, Object>) bindAgain.get("arguments");
        long acceptedRevision = ((Number) ((Map<String, Object>) acceptArguments.get("payload")).get("revision")).longValue();
        assertTrue(acceptedRevision > requestedRevision, revisionNext);

        String stale = toolCall(requesterHandler, "request_coordination", bindArguments(requestedRevision));
        assertEquals("stale_request", extractResponseReason(stale), stale);
        assertEquals("CONTRACT_REVISION_STALE", extractInnerResult(stale).get("reason"), stale);

        String accepted = toolCall(requesterHandler, "request_coordination", acceptArguments);
        assertEquals("ready", extractResponseStatus(accepted), accepted);
        assertEquals(Boolean.TRUE, extractInnerResult(accepted).get("contractAccepted"), accepted);
        assertEquals(reqHandle, extractResultField(accepted), "binding again keeps the binding");

        // 3. The owner implements and publishes; the requester inspects, tests, and accepts.
        Path ownerWt = Path.of(b2.worktreePath());
        Files.writeString(ownerWt.resolve("ProductQuery.java"), "public class ProductQuery {}\n");
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Implement ProductQuery");
        String publicationReady = toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true));
        Map<String, Object> publicationArguments = new LinkedHashMap<>(extractWorkflowArguments(publicationReady));
        assertEquals(requestOf(reqHandle).contractRevision(), ((Number) publicationArguments.get("contractRevision")).longValue(), publicationReady);
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
        long requestsBefore = replayedStore().events().stream().filter(event -> event.type() == PredictionEventType.CONTRACT_CHANGE_REQUESTED).count();
        String lateRequest = toolCall(ownerHandler,
                "request_coordination",
                Map.of("kind", "contract_change_request", "payload", Map.of("ownerPath", "ProductCli.java", "need", "Print one line per product")));
        assertEquals("waiting", extractResponseStatus(lateRequest), lateRequest);
        assertEquals("CAPABILITY_OWNER_COMPLETING", extractInnerResult(lateRequest).get("reason"), lateRequest);
        assertEquals(requestsBefore, replayedStore().events().stream().filter(event -> event.type() == PredictionEventType.CONTRACT_CHANGE_REQUESTED).count(), lateRequest);

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
                Map.of("kind", "contract_bind", "payload", Map.of("capability", "catalog.product-store", "ownerPath", "ProductQuery.java", "revision", 1)));
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

    /**
     * One lane finishes and the other is cancelled because nothing needed to change. The work
     * group closes with the cancellation and the cancelled lane's session is told its lane is
     * closed. Before, the group stayed active with no lane left to close it, and the session was
     * told to establish an intent, which invites announcing the cancelled work again.
     */
    @Test
    @SuppressWarnings("unchecked")
    void cancellingTheLastOpenLaneClosesItsWorkGroupAndTellsItsSessionTheLaneIsClosed() throws Exception {
        Path ownerWt = Path.of(b2.worktreePath());
        Files.writeString(ownerWt.resolve("ProductQuery.java"), "public class ProductQuery {}\n");
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Implement ProductQuery");
        toolCall(ownerHandler, "finish_lane", extractWorkflowArguments(toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true))));
        assertEquals("ACTIVE", replayedStore().workGroupProjection().groups().getFirst().status().name(), "the other lane is still open");

        String cancelled = toolCall(requesterHandler, "cancel_lane", Map.of("reason", "The CLI already does what the goal asks."));
        assertEquals("completed", extractResponseStatus(cancelled), cancelled);

        assertEquals("COMPLETED", replayedStore().workGroupProjection().groups().getFirst().status().name());
        // The other lane's integration moved the control branch, so the session refreshes first.
        toolCall(requesterHandler, "ensure_session", Map.of());
        String next = toolCall(requesterHandler, "get_next_action", Map.of());
        assertEquals("completed", extractResponseStatus(next), next);
        assertEquals("CANCELLED", extractInnerResult(next).get("state"), next);
        assertEquals("CLOSE", ((Map<String, Object>) extractInnerResult(next).get("workflow")).get("type"), next);
    }

    /**
     * The requester's work can only be integrated with exactly the content it validated. An owner
     * that changed a validated file and finished left the requester waiting for good, with the
     * owner's lane already closed; two real agents stalled that way. The finish is refused while a
     * validated file differs, and goes through once it is restored.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ownerCannotFinishWithAValidatedFileChangedAndCanOnceItIsRestored() throws Exception {
        ownerPublishesContract("Return product when found");
        String reqHandle = bind(requesterHandler, 1);
        Path ownerWt = Path.of(b2.worktreePath());
        String validatedContent = "public class ProductQuery {}\n";
        Files.writeString(ownerWt.resolve("ProductQuery.java"), validatedContent);
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Implement ProductQuery");
        Map<String, Object> publication = extractWorkflowArguments(toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true)));
        publication.put("summary", "Implemented product query");
        String published = toolCall(ownerHandler, "publish_capability_implementation", publication);
        assertEquals("waiting", extractResponseStatus(published), published);
        String validationNext = toolCall(requesterHandler, "get_next_action", Map.of());
        Map<String, Object> inspect = extractWorkflowArguments(validationNext);
        assertNotNull(inspect, validationNext);
        Map<String, Object> inspectPayload = (Map<String, Object>) inspect.get("payload");
        toolCall(requesterHandler, "respond_coordination", inspect);
        String validated = toolCall(requesterHandler,
                "respond_coordination",
                Map.of("kind", "implementation_validation",
                        "payload", Map.of("inboxItemId", inspectPayload.get("inboxItemId"), "capabilityRequestHandle", reqHandle, "implementationRevision", 1, "result", "accepted")));
        assertEquals("completed", extractResponseStatus(validated), validated);

        Files.writeString(ownerWt.resolve("ProductQuery.java"), "public class ProductQuery { int addedAfterValidation; }\n");
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Change the validated file");
        String refused = toolCall(ownerHandler, "finish_lane", Map.of("summary", "Product query service complete"));

        assertEquals("blocked", extractResponseStatus(refused), refused);
        assertEquals("VALIDATED_IMPLEMENTATION_CHANGED", extractInnerResult(refused).get("reason"), refused);
        assertEquals(List.of("ProductQuery.java"), extractInnerResult(refused).get("changedValidatedPaths"), refused);
        assertTrue(replayedStore().taskCompletionProjection().allSnapshots().isEmpty(), "a refused finish publishes nothing");

        Files.writeString(ownerWt.resolve("ProductQuery.java"), validatedContent);
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Restore the validated file");
        String finished = toolCall(ownerHandler, "finish_lane", Map.of("summary", "Product query service complete"));
        assertFalse("blocked".equals(extractResponseStatus(finished)) || "failed".equals(extractResponseStatus(finished)), finished);

        String refreshed = toolCall(requesterHandler, "ensure_session", Map.of());
        Path reqWt = Path.of(String.valueOf(extractInnerResult(refreshed).get("worktree")));
        Files.writeString(reqWt.resolve("ProductCli.java"), "public class ProductCli {}\n");
        git(reqWt, "add", ".");
        git(reqWt, "commit", "-m", "Implement ProductCli");
        String requesterFinished = toolCall(requesterHandler, "finish_lane", Map.of("summary", "Product CLI complete"));
        assertEquals("completed", extractResponseStatus(requesterFinished), requesterFinished);
        assertEquals("COMPLETED", replayedStore().workGroupProjection().groups().getFirst().status().name());
    }

    /**
     * An owner whose contract a consumer bound to is told who is held up by it. Two real agents stalled
     * when the owner waited for the requester's tests before publishing while the requester waited
     * for the publication.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ownerWithABoundConsumerIsToldItsConsumerIsWaitingForTheImplementation() throws Exception {
        ownerPublishesContract("Return product when found");
        bind(requesterHandler, 1);

        String ownerPoll = toolCall(ownerHandler, "get_next_action", Map.of());

        Map<String, Object> result = extractInnerResult(ownerPoll);
        assertEquals(Boolean.TRUE, result.get("implementationPending"), ownerPoll);
        assertTrue(String.valueOf(result.get("instruction")).contains("waiting for your implementation"), ownerPoll);
        assertEquals(List.of("requester_waiting_for_implementation"), ((Map<String, Object>) result.get("workflow")).get("blockers"), ownerPoll);
    }

    /**
     * A session that holds no lane while others work is told how to get one. Before, the answer
     * permitted only {@code ensure_session}, recommended it with empty arguments, and named another
     * participant's lane; an agent repeated the empty call until its polls ran out.
     */
    @Test
    @SuppressWarnings("unchecked")
    void sessionWithoutALaneIsToldToAnnounceOneWhileOthersWork() throws Exception {
        AgentSessionService lateSessions = new AgentSessionService();
        lateSessions.ensureSession(new SessionResolutionRequest(projectRoot, "claude", "inst-late-3", null, false));
        var bindingService = new ProviderSessionBindingService();
        var projectLocation = new ProjectApplicationService().locate(projectRoot);
        var lateBinding = bindingService.find(projectLocation, "claude", "inst-late-3").orElseThrow();
        bindingService.verifyWorkspaceTrust(projectLocation, "claude", lateBinding.sessionId(), Path.of(lateBinding.worktreePath()));
        McpProtocolHandler lateHandler = new McpProtocolHandler(lateSessions, projectRoot, "claude", "inst-late-3");
        lateHandler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + projectRoot.toUri().toString().replace("\\", "/") + "\"}}");
        toolCall(lateHandler, "ensure_session", Map.of());

        String next = toolCall(lateHandler, "get_next_action", Map.of());

        assertEquals("coordination_intent_required", extractResponseReason(next), next);
        Map<String, Object> result = extractInnerResult(next);
        assertTrue(String.valueOf(result.get("instruction")).contains("ensure_session with a task"), next);
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertTrue(((List<String>) workflow.get("permittedOperations")).containsAll(List.of("ensure_session", "read_file")), next);
        assertFalse(workflow.containsKey("arguments"), "the task is the caller's to choose: " + next);
        assertFalse(workflow.containsKey("laneId"), "no other participant's lane is this session's context: " + next);
        lateHandler.close();
    }

    /**
     * A session that holds no lane, in a project whose lanes are all finished, is told nothing is
     * open. Before, it was told to establish an intent, and a resumed agent whose own lane was done
     * went looking for work to announce.
     */
    @Test
    @SuppressWarnings("unchecked")
    void sessionWithoutALaneIsToldWhenNothingIsOpen() throws Exception {
        Path ownerWt = Path.of(b2.worktreePath());
        Files.writeString(ownerWt.resolve("ProductQuery.java"), "public class ProductQuery {}");
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Implement ProductQuery");
        toolCall(ownerHandler, "finish_lane", extractWorkflowArguments(toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true))));
        toolCall(requesterHandler, "cancel_lane", Map.of("reason", "The CLI is no longer wanted."));

        AgentSessionService lateSessions = new AgentSessionService();
        lateSessions.ensureSession(new SessionResolutionRequest(projectRoot, "claude", "inst-late-2", null, false));
        var bindingService = new ProviderSessionBindingService();
        var projectLocation = new ProjectApplicationService().locate(projectRoot);
        var lateBinding = bindingService.find(projectLocation, "claude", "inst-late-2").orElseThrow();
        bindingService.verifyWorkspaceTrust(projectLocation, "claude", lateBinding.sessionId(), Path.of(lateBinding.worktreePath()));
        McpProtocolHandler lateHandler = new McpProtocolHandler(lateSessions, projectRoot, "claude", "inst-late-2");
        lateHandler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + projectRoot.toUri().toString().replace("\\", "/") + "\"}}");
        toolCall(lateHandler, "ensure_session", Map.of());

        String next = toolCall(lateHandler, "get_next_action", Map.of());

        assertEquals("completed", extractResponseStatus(next), next);
        assertEquals("NO_OPEN_WORK", extractInnerResult(next).get("state"), next);
        Map<String, Object> workflow = (Map<String, Object>) extractInnerResult(next).get("workflow");
        assertEquals("CLOSE", workflow.get("type"), next);
        assertTrue(((List<String>) workflow.get("permittedOperations")).contains("ensure_session"), "a new intent can still be announced: " + next);
        lateHandler.close();
    }

    /**
     * A consumer lane is cancelled while it is bound. Its binding is superseded with it, in any
     * state, and no gate refuses the cancellation (ADR-0092). Before, a binding the owner had
     * accepted refused the cancel, and the owner waited on a lane that could not leave.
     */
    @Test
    @SuppressWarnings("unchecked")
    void cancellingAConsumerLaneSupersedesItsBindingAndReleasesTheOwner() throws Exception {
        ownerPublishesContract("Return product when found");
        String reqHandle = bind(requesterHandler, 1);
        ownerCommits("public class ProductQuery {}\n");
        String whileBound = toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true));
        assertEquals("publish_capability_implementation", extractInnerResponse(whileBound).get("nextAction"), "the owner is held by the binding: " + whileBound);

        String cancelled = toolCall(requesterHandler, "cancel_lane", Map.of("reason", "The CLI is no longer wanted."));

        assertEquals("completed", extractResponseStatus(cancelled), cancelled);
        assertEquals("SUPERSEDED", requestOf(reqHandle).state().name());
        String released = toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true));
        assertEquals("finish_lane", extractInnerResponse(released).get("nextAction"), released);
        String finished = toolCall(ownerHandler, "finish_lane", extractWorkflowArguments(released));
        assertFalse("blocked".equals(extractResponseStatus(finished)) || "failed".equals(extractResponseStatus(finished)), finished);
        assertEquals(1, replayedStore().events().stream().filter(event -> event.type() == PredictionEventType.TASK_INTEGRATED).count(), finished);
    }

    /** A binding whose implementation is already published is superseded by a cancel just the same. */
    @Test
    void cancellingAConsumerLaneSupersedesAPublishedBindingToo() throws Exception {
        ownerPublishesContract("Return product when found");
        String reqHandle = bind(requesterHandler, 1);
        ownerCommits("public class ProductQuery {}\n");
        ownerPublishes(reqHandle);
        assertEquals("IMPLEMENTATION_AVAILABLE", requestOf(reqHandle).state().name());

        String cancelled = toolCall(requesterHandler, "cancel_lane", Map.of("reason", "The CLI is no longer wanted."));

        assertEquals("completed", extractResponseStatus(cancelled), cancelled);
        assertEquals("SUPERSEDED", requestOf(reqHandle).state().name());
    }

    /**
     * A lane that changed nothing asks to finish. It is told there is nothing to publish and that
     * it may cancel. Before, it was told only that it may implement, and an agent that had found
     * nothing to change left the lane open.
     */
    @Test
    @SuppressWarnings("unchecked")
    void laneWithNothingToPublishThatAsksToFinishIsToldItMayCancel() throws Exception {
        String asked = toolCall(requesterHandler, "get_next_action", Map.of("completionRequested", true));

        assertEquals("ready", extractResponseStatus(asked), asked);
        assertEquals(Boolean.TRUE, extractInnerResult(asked).get("nothingToPublish"), asked);
        Map<String, Object> workflow = (Map<String, Object>) extractInnerResult(asked).get("workflow");
        assertEquals(List.of("nothing_to_publish"), workflow.get("blockers"), asked);
        assertTrue(((List<String>) workflow.get("permittedOperations")).contains("cancel_lane"), asked);

        String polled = toolCall(requesterHandler, "get_next_action", Map.of());
        assertFalse(polled.contains("nothingToPublish"), "only a request to finish is answered this way: " + polled);
    }

    /**
     * The owner publishes a revision and the requester does not bind again. The owner is held
     * for a stated time and no longer; when it finishes, the requester learns that the owner went
     * ahead and builds against the integrated work instead of waiting on a lane that is gone.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ownerWaitsABoundedTimeForTheAnswerToItsProposalThenFinishesAndTheRequesterIsToldItWentAhead() throws Exception {
        String configuredGrace = System.getProperty(RequesterAnswerPolicy.GRACE_PROPERTY);
        try {
            // 1. The requester declares the dependency and binds; the owner then publishes a revision it must answer.
            String declared = toolCall(requesterHandler,
                    "ensure_session",
                    Map.of("task", Map.of("goal", "Implement the product CLI",
                            "acceptance", "Publish the CLI implementation",
                            "claims", List.of(Map.of("kind", "path_exact", "path", "ProductCli.java")),
                            "knownDependencies", List.of("catalog.product-query"))));
            if ("blocked".equals(extractResponseStatus(declared))) {
                Map<String, Object> exactExtension = (Map<String, Object>) ((Map<String, Object>) extractInnerResult(declared).get("nextProtocolPayload")).get("arguments");
                declared = toolCall(requesterHandler, "ensure_session", exactExtension);
            }
            assertEquals("ready", extractResponseStatus(declared), declared);
            ownerPublishesContract("Return product when found");
            String reqHandle = bind(requesterHandler, 1);
            ownerPublishesContract("Return product when found; reject null ids");
            Map<String, Object> lateAccept = (Map<String, Object>) ((Map<String, Object>) extractInnerResult(toolCall(requesterHandler, "get_next_action", Map.of())).get("bindAgain")).get("arguments");

            // 2. The owner's work is done. Inside the answer period it is told to wait until a
            //    stated time, and finish_lane refuses for the same reason.
            Path ownerWt = Path.of(b2.worktreePath());
            Files.writeString(ownerWt.resolve("ProductQuery.java"), "public class ProductQuery {}\n");
            git(ownerWt, "add", ".");
            git(ownerWt, "commit", "-m", "Implement ProductQuery");
            System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, "3600");
            String held = toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true));
            assertEquals("waiting", extractResponseStatus(held), held);
            assertEquals("requester_response_pending", extractResponseReason(held), held);
            Map<String, Object> heldResult = extractInnerResult(held);
            assertEquals("CONTRACT_PROPOSAL_AWAITING_REQUESTER", heldResult.get("reason"), held);
            long deadline = ((Number) heldResult.get("requesterAnswerDeadlineEpochMillis")).longValue();
            assertTrue(deadline > System.currentTimeMillis(), held);
            Map<String, Object> heldWorkflow = (Map<String, Object>) heldResult.get("workflow");
            assertEquals(deadline, ((Number) heldWorkflow.get("requesterAnswerDeadlineEpochMillis")).longValue(), held);
            assertEquals(Map.of("completionRequested", true), heldWorkflow.get("arguments"), "polling again must ask for completion again: " + held);
            String refused = toolCall(ownerHandler, "finish_lane", Map.of("summary", "Product query complete"));
            assertEquals("blocked", extractResponseStatus(refused), refused);
            assertEquals("requester_response_pending", extractResponseReason(refused), refused);
            assertEquals("REVISION_REQUESTED", replayedStore().capabilityRequestProjection().findByHandle(reqHandle).orElseThrow().state().name());
            // The controller reads the same deadline, for the owner's provider only: the log does
            // not move when it passes, so this is how a stopped owner is woken for it.
            var observer = new org.synesis.workspace.application.agent.run.EventLogObserver(new ProjectApplicationService().locate(projectRoot));
            assertEquals(java.util.OptionalLong.of(deadline), observer.observe().answerDeadline("codex"));
            assertEquals(java.util.OptionalLong.empty(), observer.observe().answerDeadline("claude"));

            // 3. The answer period is over. The owner is offered its finish, and finishing records
            //    the proposal as superseded by the owner's work.
            System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, "0");
            String ownerProjection = toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true));
            assertEquals("finish_lane", extractInnerResponse(ownerProjection).get("nextAction"), ownerProjection);
            String ownerFinished = toolCall(ownerHandler, "finish_lane", extractWorkflowArguments(ownerProjection));
            assertEquals("integrated", ((Map<String, Object>) extractInnerResult(ownerFinished).get("completion")).get("task"), ownerFinished);
            var settled = replayedStore().capabilityRequestProjection().findByHandle(reqHandle).orElseThrow();
            assertEquals("SUPERSEDED", settled.state().name(), ownerFinished);
            assertEquals(RequesterAnswerPolicy.OWNER_FINISHED_WITHOUT_ANSWER, settled.reason(), ownerFinished);
            assertEquals(java.util.OptionalLong.empty(), observer.observe().answerDeadline("codex"), "a settled proposal leaves no deadline to wake anyone for");

            // 4. An answer that arrives now is told what happened, not called stale, and the
            //    requester's inbox says the owner went ahead and what it last proposed.
            String answeredLate = toolCall(requesterHandler, "request_coordination", lateAccept);
            assertEquals("ready", extractResponseStatus(answeredLate), answeredLate);
            // The owner is integrated, so binding is satisfied by its work and waits for no validation.
            assertEquals("CAPABILITY_OWNER_INTEGRATED", extractInnerResult(answeredLate).get("reason"), answeredLate);
            String afterIntegration = toolCall(requesterHandler, "get_next_action", Map.of());
            if ("retry_required".equals(extractResponseStatus(afterIntegration))) {
                toolCall(requesterHandler, "ensure_session", Map.of());
                afterIntegration = toolCall(requesterHandler, "get_next_action", Map.of());
            }
            assertEquals("ready", extractResponseStatus(afterIntegration), afterIntegration);
            Map<String, Object> integrated = extractInnerResult(afterIntegration);
            assertEquals(List.of("catalog.product-query"), integrated.get("dependenciesIntegrated"), afterIntegration);
            List<Map<String, Object>> wentAhead = (List<Map<String, Object>>) integrated.get("ownerProceededWithoutAnswer");
            assertEquals(1, wentAhead.size(), afterIntegration);
            assertEquals(reqHandle, wentAhead.getFirst().get("capabilityRequestHandle"), afterIntegration);
            assertEquals(List.of("Return product when found; reject null ids"), ((Map<String, Object>) wentAhead.getFirst().get("proposedContract")).get("requiredBehavior"),
                    "the requester is shown what the owner proposed, not its own draft: " + afterIntegration);
            assertNotNull(integrated.get("ownerProceededGuidance"), afterIntegration);

            // 5. The requester builds on the integrated work and finishes; nothing waits on the
            //    superseded request, and the work group completes.
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
        } finally {
            if (configuredGrace == null) {
                System.clearProperty(RequesterAnswerPolicy.GRACE_PROPERTY);
            } else {
                System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, configuredGrace);
            }
        }
    }

    // ---- ADR-0089: one publication serves every requester; an unanswered request is superseded ----

    private McpProtocolHandler secondRequesterHandler;

    /** Starts a second requester session that announces its own lane with a claim disjoint from the others. */
    private McpProtocolHandler secondRequester() throws Exception {
        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new SessionResolutionRequest(projectRoot, "claude", "inst-req-2", null, false));
        var bindingService = new ProviderSessionBindingService();
        var location = new ProjectApplicationService().locate(projectRoot);
        var binding = bindingService.find(location, "claude", "inst-req-2").orElseThrow();
        bindingService.verifyWorkspaceTrust(location, "claude", binding.sessionId(), Path.of(binding.worktreePath()));
        new WorkspaceCollaborationService().announce(projectRoot, "claude", "inst-req-2", "Implement the product report", "Publish the report implementation", List.of(ResourceSelector.pathExact("ProductReport.java")));
        secondRequesterHandler = new McpProtocolHandler(sessions, projectRoot, "claude", "inst-req-2");
        secondRequesterHandler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + projectRoot.toUri().toString().replace("\\", "/") + "\"}}");
        return secondRequesterHandler;
    }

    private String fileRequest(McpProtocolHandler requester) throws Exception {
        // With more than one open lane the bind names the file it builds on; the lane claiming it is the owner.
        String handle = bind(requester, 1);
        assertEquals(replayedStore().collaborationProjection().activeIntents().stream().filter(intent -> intent.provider().equals("codex")).findFirst().orElseThrow().authorityLineageId(),
                replayedStore().capabilityRequestProjection().findByHandle(handle).orElseThrow().authorityLineageId());
        return handle;
    }

    private void ownerCommits(String content) throws Exception {
        Path ownerWt = Path.of(b2.worktreePath());
        Files.writeString(ownerWt.resolve("ProductQuery.java"), content);
        git(ownerWt, "add", ".");
        git(ownerWt, "commit", "-m", "Owner change");
    }

    /** Projects the owner's publication, checks which request it names when one is expected, and publishes it. */
    private void ownerPublishes(String expectedHandle) {
        String projected = toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true));
        assertEquals("publish_capability_implementation", extractInnerResponse(projected).get("nextAction"), projected);
        Map<String, Object> arguments = new LinkedHashMap<>(extractWorkflowArguments(projected));
        if (expectedHandle != null) {
            assertEquals(expectedHandle, arguments.get("capabilityRequestHandle"), projected);
        }
        arguments.put("summary", "Implemented product query");
        String published = toolCall(ownerHandler, "publish_capability_implementation", arguments);
        assertEquals("waiting", extractResponseStatus(published), published);
    }

    private org.synesis.coordination.domain.capability.CapabilityRequestRecord requestOf(String handle) throws Exception {
        return replayedStore().capabilityRequestProjection().findByHandle(handle).orElseThrow();
    }

    private String latestCommit(String handle) throws Exception {
        return replayedStore().capabilityRequestProjection().findLatestImplementation(handle).orElseThrow().commitSha();
    }

    private int latestRevision(String handle) throws Exception {
        return replayedStore().capabilityRequestProjection().findLatestImplementation(handle).orElseThrow().revisionNumber();
    }

    /** Inspects, reads and tests the published revision as the requester, then answers with {@code result}. */
    @SuppressWarnings("unchecked")
    private String requesterJudges(McpProtocolHandler requester, String handle, int revision, String result) {
        String next = toolCall(requester, "get_next_action", Map.of());
        Map<String, Object> inspectArguments = extractWorkflowArguments(next);
        Map<String, Object> inspectPayload = (Map<String, Object>) inspectArguments.get("payload");
        assertEquals(handle, inspectPayload.get("capabilityRequestHandle"), next);
        assertEquals(revision, ((Number) inspectPayload.get("implementationRevision")).intValue(), next);
        assertEquals("ready", extractResponseStatus(toolCall(requester, "respond_coordination", inspectArguments)));
        assertEquals("completed", extractResponseStatus(toolCall(requester, "read_file", Map.of("path", "ProductQuery.java", "capabilityRequestHandle", handle, "implementationRevision", revision))));
        assertEquals("completed", extractResponseStatus(toolCall(requester, "run_command", Map.of("argv", List.of("git", "rev-parse", "HEAD"), "capabilityRequestHandle", handle, "implementationRevision", revision))));
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("inboxItemId", inspectPayload.get("inboxItemId"), "capabilityRequestHandle", handle, "implementationRevision", revision, "result", result));
        if (!"accepted".equals(result)) {
            payload.put("reason", "Needs a change");
            payload.put("contractField", "requiredBehavior");
        }
        return toolCall(requester, "respond_coordination", Map.of("kind", "implementation_validation", "payload", payload));
    }

    @Test
    void publishingForOneRequesterPublishesTheSameCommitToEveryAcceptedRequest() throws Exception {
        McpProtocolHandler requesterTwo = secondRequester();
        ownerPublishesContract("Return product when found");
        String first = fileRequest(requesterHandler);
        String second = fileRequest(requesterTwo);
        ownerCommits("public class ProductQuery {}\n");

        // With two requests accepted the projection may name either; the publication reaches both.
        ownerPublishes(null);

        assertEquals("IMPLEMENTATION_AVAILABLE", requestOf(first).state().name());
        assertEquals("IMPLEMENTATION_AVAILABLE", requestOf(second).state().name());
        assertEquals(1, latestRevision(first));
        assertEquals(1, latestRevision(second));
        assertEquals(latestCommit(first), latestCommit(second));
        requesterTwo.close();
    }

    @Test
    void ownerAskingToFinishIsShownTheRequestWaitingForItsImplementationAheadOfAnotherRequestersVerdict() throws Exception {
        McpProtocolHandler requesterTwo = secondRequester();
        ownerPublishesContract("Return product when found");
        String first = fileRequest(requesterHandler);
        ownerCommits("public class ProductQuery {}\n");
        ownerPublishes(first);
        String second = fileRequest(requesterTwo);

        String projected = toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true));

        assertEquals("ready", extractResponseStatus(projected), projected);
        assertEquals("publish_capability_implementation", extractInnerResponse(projected).get("nextAction"), projected);
        assertEquals(second, extractWorkflowArguments(projected).get("capabilityRequestHandle"), projected);
        assertEquals("IMPLEMENTATION_AVAILABLE", requestOf(first).state().name(), "the first requester has not judged yet");

        ownerPublishes(second);
        assertEquals("IMPLEMENTATION_AVAILABLE", requestOf(second).state().name());
        assertEquals(latestCommit(first), latestCommit(second));
        assertEquals(1, latestRevision(first));
        requesterTwo.close();
    }

    @Test
    void revisionForOneRequesterReturnsAnotherRequestersValidatedRequestToImplementationAvailable() throws Exception {
        McpProtocolHandler requesterTwo = secondRequester();
        ownerPublishesContract("Return product when found");
        String first = fileRequest(requesterHandler);
        String second = fileRequest(requesterTwo);
        ownerCommits("public class ProductQuery { /* v1 */ }\n");
        ownerPublishes(null);
        String firstCommit = latestCommit(second);

        assertEquals("completed", extractResponseStatus(requesterJudges(requesterTwo, second, 1, "accepted")));
        assertEquals("VALIDATED", requestOf(second).state().name());
        assertEquals("waiting", extractResponseStatus(requesterJudges(requesterHandler, first, 1, "revision_required")));
        assertEquals("IMPLEMENTING", requestOf(first).state().name());

        ownerCommits("public class ProductQuery { /* v2 */ }\n");
        ownerPublishes(first);

        assertEquals("IMPLEMENTATION_AVAILABLE", requestOf(second).state().name());
        assertEquals(2, latestRevision(second));
        assertEquals(latestCommit(first), latestCommit(second));
        assertFalse(firstCommit.equals(latestCommit(second)), "the second requester now holds the revised commit");
        requesterTwo.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void changeRequestFiledAfterTheFinishWasProjectedHoldsTheFinishUntilTheOwnerAnswersIt() throws Exception {
        ownerCommits("public class ProductQuery {}\n");
        // The owner has projected its finish; a consumer then files a need before the finish lands.
        Map<String, Object> finishArguments = new LinkedHashMap<>(extractWorkflowArguments(toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true))));
        String changeRequest = fileChangeRequest(requesterHandler, "Return the newest product first");
        finishArguments.put("expectedRevision", replayedStore().headSequence());

        String refused = toolCall(ownerHandler, "finish_lane", finishArguments);

        assertEquals("blocked", extractResponseStatus(refused), refused);
        assertEquals("owner_response_pending", extractResponseReason(refused), refused);
        assertEquals("CONTRACT_CHANGE_REQUEST_OPEN", extractInnerResult(refused).get("reason"), refused);
        assertTrue(replayedStore().taskCompletionProjection().allSnapshots().isEmpty(), "a refused finish publishes nothing");

        String declined = toolCall(ownerHandler, "respond_coordination",
                Map.of("kind", "contract_change_response", "payload", Map.of("changeRequest", changeRequest, "decision", "declined", "reason", "The contract fixes the order")));
        assertEquals("completed", extractResponseStatus(declined), declined);
        String ownerProjection = toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true));
        assertEquals("finish_lane", extractInnerResponse(ownerProjection).get("nextAction"), ownerProjection);
        String finished = toolCall(ownerHandler, "finish_lane", extractWorkflowArguments(ownerProjection));
        assertEquals("integrated", ((Map<String, Object>) extractInnerResult(finished).get("completion")).get("task"), finished);
    }

    /**
     * The shape of the failed five-agent run: two consumers need opposite things from one module.
     * The owner is shown both together, revises for one and declines the other with a reason, and
     * the declined consumer can still bind to the contract as it stands.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ownerIsShownTwoOppositeNeedsTogetherAndAnswersOneByRevisionAndTheOtherByDeclining() throws Exception {
        McpProtocolHandler requesterTwo = secondRequester();
        String creates = fileChangeRequest(requesterHandler, "Listing a missing store creates it");
        String pure = fileChangeRequest(requesterTwo, "Listing has no side effects");

        String ownerNext = toolCall(ownerHandler, "get_next_action", Map.of());
        assertEquals("ready", extractResponseStatus(ownerNext), ownerNext);
        assertEquals("owner_response_pending", extractResponseReason(ownerNext), ownerNext);
        assertEquals("respond_coordination", extractInnerResponse(ownerNext).get("nextAction"), ownerNext);
        List<Map<String, Object>> shown = (List<Map<String, Object>>) extractInnerResult(ownerNext).get("changeRequests");
        assertEquals(java.util.Set.of(creates, pure), shown.stream().map(change -> String.valueOf(change.get("changeRequest"))).collect(java.util.stream.Collectors.toSet()), ownerNext);

        String published = ownerPublishesContract("Listing a missing store creates it", creates);
        assertEquals(List.of(creates), extractInnerResult(published).get("changeRequestsAnswered"), published);
        String declined = toolCall(ownerHandler, "respond_coordination",
                Map.of("kind", "contract_change_response", "payload", Map.of("changeRequest", pure, "decision", "declined", "reason", "One contract serves both consumers; listing creates the store")));
        assertEquals("completed", extractResponseStatus(declined), declined);

        var contracts = replayedStore().contractProjection();
        assertEquals("ACCEPTED", contracts.changeRequest(UUID.fromString(creates)).orElseThrow().state().name());
        var refusal = contracts.changeRequest(UUID.fromString(pure)).orElseThrow();
        assertEquals("DECLINED", refusal.state().name());
        assertEquals("One contract serves both consumers; listing creates the store", refusal.reason());
        assertEquals(0, contracts.openChangeRequestsFor(refusal.ownerLineageId()).size());

        assertFalse(bind(requesterTwo, 1).isBlank(), "a declined consumer may bind to the contract as it stands");
        assertFalse(bind(requesterHandler, 1).isBlank());
        requesterTwo.close();
    }

    /**
     * Each bound consumer is told to bind again after a revision, and what it validated under the
     * earlier revision no longer counts.
     */
    @Test
    @SuppressWarnings("unchecked")
    void contractRevisionTellsEveryBoundConsumerToBindAgainAndWhatWasValidatedNoLongerCounts() throws Exception {
        McpProtocolHandler requesterTwo = secondRequester();
        ownerPublishesContract("Return product when found");
        String first = fileRequest(requesterHandler);
        String second = fileRequest(requesterTwo);
        ownerCommits("public class ProductQuery {}\n");
        ownerPublishes(null);
        String commit = latestCommit(first);
        assertEquals("completed", extractResponseStatus(requesterJudges(requesterTwo, second, 1, "accepted")));
        assertEquals("VALIDATED", requestOf(second).state().name());

        ownerPublishesContract("Return product when found; reject null ids");

        for (String handle : List.of(first, second)) {
            assertEquals("REVISION_REQUESTED", requestOf(handle).state().name(), "what was validated under the earlier revision no longer counts");
        }
        List<Map<String, Object>> bindCalls = new java.util.ArrayList<>();
        for (McpProtocolHandler consumer : List.of(requesterHandler, requesterTwo)) {
            String next = toolCall(consumer, "get_next_action", Map.of());
            Map<String, Object> result = extractInnerResult(next);
            assertFalse(result.containsKey("acceptRevision"), next);
            Map<String, Object> bindAgain = (Map<String, Object>) result.get("bindAgain");
            assertNotNull(bindAgain, next);
            assertEquals(2, ((Number) ((Map<String, Object>) ((Map<String, Object>) bindAgain.get("arguments")).get("payload")).get("revision")).intValue(), next);
            bindCalls.add((Map<String, Object>) bindAgain.get("arguments"));
        }
        assertEquals("ready", extractResponseStatus(toolCall(requesterHandler, "request_coordination", bindCalls.get(0))));
        assertEquals("ready", extractResponseStatus(toolCall(requesterTwo, "request_coordination", bindCalls.get(1))));
        assertEquals("ACCEPTED", requestOf(first).state().name());
        assertEquals("ACCEPTED", requestOf(second).state().name());

        requesterTwo.close();
    }

    /**
     * After a contract revision and a bind again, the owner may publish the commit it already
     * published. One publication reaches every consumer (ADR-0089), so each bound consumer is
     * given the new implementation revision and validates it anew.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anUnchangedCommitPublishedAgainForANewContractRevisionReachesEveryReboundConsumer() throws Exception {
        McpProtocolHandler requesterTwo = secondRequester();
        ownerPublishesContract("Return product when found");
        String first = fileRequest(requesterHandler);
        String second = fileRequest(requesterTwo);
        ownerCommits("public class ProductQuery {}\n");
        ownerPublishes(null);
        String commit = latestCommit(first);
        ownerPublishesContract("Return product when found; reject null ids");
        assertEquals("ready", extractResponseStatus(toolCall(requesterHandler, "request_coordination", bindArguments(2))));
        assertEquals("ready", extractResponseStatus(toolCall(requesterTwo, "request_coordination", bindArguments(2))));

        ownerPublishes(null);

        for (String handle : List.of(first, second)) {
            assertEquals("IMPLEMENTATION_AVAILABLE", requestOf(handle).state().name(), handle);
            assertEquals(2, latestRevision(handle), handle);
            assertEquals(commit, latestCommit(handle), "the owner changed nothing, so it is the same commit");
        }
        assertEquals("completed", extractResponseStatus(requesterJudges(requesterHandler, first, 2, "accepted")));
        assertEquals("completed", extractResponseStatus(requesterJudges(requesterTwo, second, 2, "accepted")));
        assertEquals("VALIDATED", requestOf(first).state().name());
        assertEquals("VALIDATED", requestOf(second).state().name());

        String ownerProjection = toolCall(ownerHandler, "get_next_action", Map.of("completionRequested", true));
        assertEquals("finish_lane", extractInnerResponse(ownerProjection).get("nextAction"), ownerProjection);
        String finished = toolCall(ownerHandler, "finish_lane", extractWorkflowArguments(ownerProjection));
        assertEquals("integrated", ((Map<String, Object>) extractInnerResult(finished).get("completion")).get("task"), finished);
        requesterTwo.close();
    }

    @Test
    void aVerdictThatAsksForARevisionMustNameTheContractField() throws Exception {
        ownerPublishesContract("Return product when found");
        String handle = bind(requesterHandler, 1);
        ownerCommits("public class ProductQuery {}\n");
        ownerPublishes(handle);

        String refused = toolCall(requesterHandler, "respond_coordination", Map.of("kind", "implementation_validation",
                "payload", Map.of("inboxItemId", UUID.randomUUID().toString(), "capabilityRequestHandle", handle, "implementationRevision", 1, "result", "revision_required", "reason", "Needs a change")));

        assertEquals("blocked", extractResponseStatus(refused), refused);
        assertEquals("COORDINATION_RESPONSE_FIELD_REQUIRED:contractField", extractInnerResult(refused).get("error"), refused);
        assertEquals("IMPLEMENTATION_AVAILABLE", requestOf(handle).state().name(), "a refused verdict records nothing");
    }
}
