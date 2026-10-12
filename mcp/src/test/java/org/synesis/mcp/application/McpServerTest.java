package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.mcp.transport.stdio.McpStdioServer;

import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentService;
import org.synesis.workspace.application.provider.continuity.ProviderThreadOwnershipStore;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;

/**
 * Exercises MCP server framing, initialization, and tool dispatch.
 */
@SuppressWarnings("TextBlockMigration")
class McpServerTest {

    private Path tempRoot;
    private String previousUserHome;

    private static void git(Path root, String... arguments) throws Exception {
        GitProcessRunner.run(root, arguments);
    }

    @BeforeEach
    void setUp() throws Exception {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("synesis-mcp-provider-home-").toString());
        tempRoot = Files.createTempDirectory("synesis-mcp-test-");
        git(tempRoot, "init");
        git(tempRoot, "config", "user.name", "Test User");
        git(tempRoot, "config", "user.email", "test@example.com");
        Files.writeString(tempRoot.resolve("README.md"), "# Test Repo\n");
        git(tempRoot, "add", ".");
        git(tempRoot, "commit", "-m", "Initial commit");

        ProjectApplicationService projectService = new ProjectApplicationService();
        ProjectLocation location = projectService.init(tempRoot).location();
        McpProviderTestSupport.install(location, "codex");
        McpProviderTestSupport.install(location, "claude");
        new ProviderManualService().install("codex");
        new ProviderManualService().install("claude");
    }

    @AfterEach
    void restoreUserHome() {
        if (previousUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousUserHome);
        }
    }

    @Test
    void testInitializeHandshakeSucceeds() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        String responseJson = handler.handleMessage(initReq);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"protocolVersion\":\"2024-11-05\""));
        assertTrue(responseJson.contains("\"name\":\"synesis\""));
    }

    @Test
    void testInitializedNotificationReturnsNoResponse() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String notif = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        String responseJson = handler.handleMessage(notif);

        assertNull(responseJson);
    }

    @Test
    void testToolsListReturnsExactlyEnsureSession() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String listReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
        String responseJson = handler.handleMessage(listReq);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"name\":\"ensure_session\""));
        assertTrue(responseJson.contains("\"name\":\"read_file\""));
        assertTrue(responseJson.contains("\"name\":\"apply_patch\""));
        assertTrue(responseJson.contains("\"name\":\"run_command\""));
        assertTrue(responseJson.contains("\"name\":\"get_next_action\""));
    }

    @Test
    void pendingManagedTransportStaysQuarantinedThenPromotesInPlace() throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(tempRoot);
        var binding = new ProviderSessionBindingService().ensure(location, "codex", "pending-connection").binding();
        var ownership = ProviderThreadOwnershipStore.storeFor(location).acquire(location.projectId().toString(), "codex", "pending-thread", binding.sessionId());
        var issued = new ManagedAttachmentService().issueFromOwnership(location,
                location.projectId().toString(),
                "codex",
                binding.sessionId(),
                "normal-provider-home",
                ownership,
                ManagedAttachmentService.storeFor(location, binding.sessionId()));
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", "pending-connection", new SessionProcessIdentity(1L, "test", "test", 1L, "nonce"), true, issued.record());

        assertTrue(handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").contains("synesis"));
        String tools = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertEquals(10, tools.split("\"name\":\"").length - 1);

        String pendingEnsure = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}");
        assertTrue(pendingEnsure.contains("managed_attachment_pending"), pendingEnsure);
        String pendingMutation = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"apply_patch\",\"arguments\":{\"path\":\"README.md\"}}}");
        assertTrue(pendingMutation.contains("managed_attachment_pending"), pendingMutation);
        assertTrue(new SessionLeaseStore().load(tempRoot, "pending-connection").isEmpty());
        assertTrue(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).collaborationProjection().activeIntents().isEmpty());

        new ManagedAttachmentService().activate(ManagedAttachmentService.storeFor(location, binding.sessionId()), 1L);
        String activeEnsure = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}");
        assertTrue(activeEnsure.contains("ready"), activeEnsure);
    }

    @Test
    void completedManagedTransportRetainsOnlyReviewCoordinationAdmission() throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(tempRoot);
        var binding = new ProviderSessionBindingService().ensure(location, "codex", "completed-review-connection").binding();
        var ownership = ProviderThreadOwnershipStore.storeFor(location).acquire(location.projectId().toString(), "codex", "completed-review-thread", binding.sessionId());
        var issued = new ManagedAttachmentService().issueFromOwnership(location,
                location.projectId().toString(),
                "codex",
                binding.sessionId(),
                "normal-provider-home",
                ownership,
                ManagedAttachmentService.storeFor(location, binding.sessionId()));
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(),
                tempRoot,
                "codex",
                "completed-review-connection",
                new SessionProcessIdentity(1L, "test", "test", 1L, "nonce"),
                true,
                issued.record());

        new ManagedAttachmentService().activate(ManagedAttachmentService.storeFor(location, binding.sessionId()), 1L);
        assertTrue(new ProviderSessionBindingService().complete(location, "codex", "completed-review-connection"));

        String next = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_next_action\",\"arguments\":{}}}");
        assertFalse(next.contains("managed_attachment_rejected"), next);
        String mutation = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"apply_patch\",\"arguments\":{\"path\":\"README.md\"}}}");
        assertTrue(mutation.contains("managed_attachment_rejected"), mutation);
    }

    @Test
    void toolsListAdvertisesExactlyTenRawNamesAndRejectsDecoratedCalls() {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", "conn-raw");
        String response = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}");
        assertEquals(10, response.split("\"name\":\"").length - 1);
        assertTrue(response.contains("\"name\":\"ensure_session\""));
        assertFalse(response.contains("synesis.ensure_session"));
        String decorated = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.ensure_session\",\"arguments\":{}}}");
        assertTrue(decorated.contains("raw MCP tool name required"));
    }

    @Test
    void testToolsCallEnsureSessionReturnsReadyStatus() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        String responseJson = handler.handleMessage(callReq);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("ready"));
        assertTrue(responseJson.contains("isolated"));

        // Verify no internal diagnostic leak
        assertFalse(responseJson.contains("sessionId"));
        assertFalse(responseJson.contains("worktreePath"));
        assertFalse(responseJson.contains(tempRoot.toString()));
    }

    @Test
    void ensureSessionCarriesExplicitDependenciesIntoDurableNextAction() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", "conn-known-dependency");
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"service implementation\",\"acceptance\":\"uses domain capability\",\"knownDependencies\":[\"tasktracker.domain\"],\"claims\":[{\"kind\":\"path_exact\",\"path\":\"src/service.java\"}]}}}}";

        assertTrue(handler.handleMessage(ensure).contains("ready"));

        String next = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"get_next_action\",\"arguments\":{}}}");

        assertTrue(next.contains("needs_capability"), next);
        assertTrue(next.contains("tasktracker.domain"), next);
        // The lane binds to the owner's public contract; it no longer writes one of its own.
        assertTrue(next.contains("contract_bind"), next);
        assertTrue(next.contains("ownerPath"), next);
        assertFalse(next.contains("COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD"), next);

        ProjectLocation location = new ProjectApplicationService().locate(tempRoot);
        PredictionEventStore reopened = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        var intent = reopened.collaborationProjection().activeIntents().stream().filter(candidate -> candidate.knownDependencies().contains("tasktracker.domain")).findFirst().orElseThrow();
        assertEquals(List.of("tasktracker.domain"), intent.knownDependencies());

        PredictionEventStore restarted = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals(List.of("tasktracker.domain"), restarted.collaborationProjection().intent(intent.intentId()).orElseThrow().knownDependencies());
    }

    @Test
    void malformedStructuredDependenciesFailClosedAtAdmission() {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", "conn-malformed-dependencies");
        String request = "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{"
                + "\"goal\":\"service implementation\",\"acceptance\":\"uses domain capability\"," + "\"knownDependencies\":[\"\",7],\"claims\":[{\"kind\":\"path_exact\"," + "\"path\":\"src/service.java\"}]}}}}";

        String response = handler.handleMessage(request);
        assertTrue(response.contains("INVALID_TASK_INTENT"), response);
    }

    @Test
    void mixedTaskFieldsAreRejectedWithoutCreatingAnIntent() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", "conn-mixed-task-fields");
        String request =
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"ensure_session\",\"arguments\":{" + "\"task\":{\"goal\":\"review candidate\",\"acceptance\":\"report findings\","
                        + "\"claims\":[{\"kind\":\"path_exact\",\"path\":\"review-report.md\"}]}," + "\"workGroupId\":\"f5714c5c-7ab4-4033-a4d8-ac63906a0418\","
                        + "\"role\":\"reviewer\",\"reviewTargets\":[{\"kind\":\"path_subtree\"," + "\"path\":\"greeting_card\"}]}}}";

        String response = handler.handleMessage(request);
        assertTrue(response.contains("TASK_FIELDS_MUST_BE_NESTED"), response);

        ProjectLocation location = new ProjectApplicationService().locate(tempRoot);
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertTrue(store.collaborationProjection().activeIntents().isEmpty());
    }

    @Test
    void runCommandRejectsTheRemovedIntentSchema() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-legacy-command");
        String request = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"run_command\",\"arguments\":{" + "\"type\":\"git_status\",\"target\":\".\",\"arguments\":[]}}}";
        String response = handler.handleMessage(request);
        assertTrue(response.contains("invalid_path"), response);
    }

    @Test
    void runCommandRejectsNonIntegralOrOutOfRangeTimeouts() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-invalid-timeout");
        String fractional = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"run_command\",\"arguments\":{" + "\"argv\":[\"git\",\"status\"],\"timeoutSeconds\":1.5}}}";
        String tooLarge = fractional.replace("1.5", "3601");
        assertTrue(handler.handleMessage(fractional).contains("invalid_path"));
        assertTrue(handler.handleMessage(tooLarge).contains("invalid_path"));
    }

    @Test
    void collaborationContractPublishReturnsJsonSafeContractProjection() {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot, "claude", "conn-contract-json");
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"contract json\",\"acceptance\":\"publish\",\"claims\":[{\"kind\":\"path_exact\",\"path\":\"tests/task_tracker_contract.md\"}]}}}}";
        assertTrue(handler.handleMessage(ensure).contains("ready"));
        String publish = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"contract_publish\",\"payload\":{\"paths\":[\"tests/task_tracker_contract.md\"],\"contract\":{\"purpose\":\"track tasks\",\"inputs\":\"task id\",\"output\":\"task\",\"requiredBehavior\":[\"return the task\"],\"errorSemantics\":\"unknown id is refused\",\"sideEffects\":\"none\",\"invariants\":\"none\",\"compatibility\":\"additive\",\"performance\":\"one lookup\",\"concurrency\":\"reads are safe\",\"acceptanceTests\":[\"TaskTrackerTest\"]}}}}}";
        String response = handler.handleMessage(publish);
        assertFalse(response.contains("-32603"));
        assertTrue(response.contains("contractId"), response);
        assertTrue(response.contains("tests/task_tracker_contract.md"), response);
    }

    @Test
    void collaborationContractStatusReturnsJsonSafeHistoryProjection() {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot, "claude", "conn-contract-status");
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"contract status json\",\"acceptance\":\"publish\",\"claims\":[{\"kind\":\"path_exact\",\"path\":\"tests/status_contract.md\"}]}}}}";
        assertTrue(handler.handleMessage(ensure).contains("ready"));
        String publish = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"contract_publish\",\"payload\":{\"paths\":[\"tests/status_contract.md\"],\"contract\":{\"purpose\":\"track tasks\",\"inputs\":\"task id\",\"output\":\"task\",\"requiredBehavior\":[\"return the task\"],\"errorSemantics\":\"unknown id is refused\",\"sideEffects\":\"none\",\"invariants\":\"none\",\"compatibility\":\"additive\",\"performance\":\"one lookup\",\"concurrency\":\"reads are safe\",\"acceptanceTests\":[\"TaskTrackerTest\"]}}}}}";
        assertTrue(handler.handleMessage(publish).contains("contractId"));
        String status = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"collaboration_status\",\"payload\":{}}}}";
        String response = handler.handleMessage(status);
        assertFalse(response.contains("-32603"));
        assertTrue(response.contains("contracts"));
        assertTrue(response.contains("contractId"), response);
        assertFalse(response.contains("dependencies"), response);
    }

    @Test
    void collaborationDiscoveryReturnsJsonSafeParticipantsIntentsAndClaims() {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", "conn-discovery-json");
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"discoverable goal\",\"acceptance\":\"status is readable\",\"claims\":[{\"kind\":\"path_exact\",\"path\":\"tests/discovery.json\"}]}}}}";
        assertTrue(handler.handleMessage(ensure).contains("ready"));
        String status = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"collaboration_status\",\"payload\":{}}}}";
        String response = handler.handleMessage(status);
        assertFalse(response.contains("-32603"));
        assertTrue(response.contains("participants"));
        assertTrue(response.contains("intents"));
        assertTrue(response.contains("tests/discovery.json"));
        assertTrue(response.contains("discoverable goal"));
        String next = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_next_action\",\"arguments\":{}}}");
        assertFalse(next.contains("-32603"));
        assertTrue(next.contains("participants"));
        assertTrue(next.contains("tests/discovery.json"));
    }

    @Test
    @SuppressWarnings("ExtractMethodRecommender")
    void collaborationRequestAndHandoffOperationsAreAvailableThroughMcp() {
        McpProtocolHandler owner = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", "conn-mcp-owner");
        McpProtocolHandler requester = new McpProtocolHandler(new AgentSessionService(), tempRoot, "claude", "conn-mcp-requester");
        String ownerEnsure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"owner\",\"acceptance\":\"contract\",\"claims\":[{\"kind\":\"path_exact\",\"path\":\"tests/mcp-request-owner.txt\"}]}}}}";
        String requesterEnsure = ownerEnsure.replace("conn-mcp-owner", "conn-mcp-requester").replace("\\\"id\\\":1", "\\\"id\\\":2").replace("owner", "requester").replace("mcp-request-owner", "mcp-requester");
        assertTrue(owner.handleMessage(ownerEnsure).contains("ready"));
        assertTrue(requester.handleMessage(requesterEnsure).contains("ready"));
        String status = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"collaboration_status\",\"payload\":{}}}}";
        String statusResponse = owner.handleMessage(status);
        String marker = "\\\"intentId\\\":\\\"";
        int markerStart = statusResponse.indexOf(marker);
        String intentId = markerStart >= 0 ? statusResponse.substring(markerStart + marker.length(), markerStart + marker.length() + 36) : "";
        assertFalse(intentId.isBlank(), statusResponse);
        String request =
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"contract_change_request\",\"payload\":{\"ownerPath\":\"tests/mcp-request-owner.txt\",\"need\":\"agree on API v1\"}}}}";
        String requestResponse = requester.handleMessage(request);
        assertFalse(requestResponse.contains("-32603"));
        assertTrue(requestResponse.contains("owner_response_pending"), requestResponse);
        String scopeRevision = "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"scope_revision\",\"payload\":{\"intentId\":\"" + intentId
                + "\",\"selectors\":[\"tests/mcp-request-owner.txt\"]," + "\"proposal\":\"narrow ownership to a disjoint slice\"}}}}";
        String scopeRevisionResponse = requester.handleMessage(scopeRevision);
        assertFalse(scopeRevisionResponse.contains("-32603"), scopeRevisionResponse);
        assertTrue(scopeRevisionResponse.contains("CONTRACT"), scopeRevisionResponse);
        assertTrue(scopeRevisionResponse.contains("PENDING"), scopeRevisionResponse);
        String participantMarker = "\\\"id\\\":\\\"agt_";
        int firstParticipant = statusResponse.indexOf(participantMarker);
        int secondParticipant = statusResponse.indexOf(participantMarker, firstParticipant + participantMarker.length());
        String targetParticipant = secondParticipant >= 0 ? "agt_" + statusResponse.substring(secondParticipant + participantMarker.length(), secondParticipant + participantMarker.length() + 36) : "";
        String handoff = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"handoff\",\"payload\":{\"intentId\":\"" + intentId
                + "\",\"targetParticipant\":\"" + targetParticipant + "\",\"proposal\":\"clean-worktree-claim-only\"}}}}";
        String handoffResponse = owner.handleMessage(handoff);
        assertFalse(handoffResponse.contains("-32603"));
        assertTrue(handoffResponse.contains("HANDOFF"));
        assertTrue(handoffResponse.contains("PENDING"));
    }

    @Test
    void firstVerifiedEnsureSessionCreatesLeaseBeforeClaims() throws Exception {
        String connection = "conn-first-lease";
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", connection);
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"lease first\",\"acceptance\":\"recoverable\",\"claims\":[{\"kind\":\"path_exact\",\"path\":\"tests/lease-first.txt\"}]}}}}";
        assertTrue(handler.handleMessage(ensure).contains("ready"));
        Path lease = SessionLeaseStore.resolveLeasesDirectory(tempRoot).resolve(connection + ".json");
        assertTrue(Files.exists(lease), "verified ensure_session must establish a lease");
        assertTrue(Files.readString(lease).contains("ACTIVE"));
    }

    @Test
    void cleanlyClosedSessionCanEstablishANewLaneGeneration() {
        String connection = "conn-fenced-epoch";
        McpProtocolHandler first = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", connection);
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"fence epoch\",\"acceptance\":\"release\",\"claims\":[{\"kind\":\"path_exact\",\"path\":\"tests/fenced-epoch.txt\"}]}}}}";
        assertTrue(first.handleMessage(ensure).contains("ready"));
        first.close();

        McpProtocolHandler returning = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", connection);
        String response = returning.handleMessage(ensure.replace("\"id\":1", "\"id\":2"));
        assertTrue(response.contains("ready"), response);
    }

    @Test
    void testUnknownMethodReturnsMethodNotFound() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String req = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"unknown/method\"}";
        String responseJson = handler.handleMessage(req);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"code\":-32601"));
        assertTrue(responseJson.contains("Method not found"));
    }

    @Test
    void testUnknownToolReturnsToolError() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String req = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"unknown_tool\"}}";
        String responseJson = handler.handleMessage(req);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("Unknown tool"));
    }

    @Test
    void testInvalidJsonReturnsParseError() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String req = "INVALID_JSON_{{";
        String responseJson = handler.handleMessage(req);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"code\":-32700"));
    }

    @Test
    void testInitializeWithRootUriBindsProjectRoot() {
        AgentSessionService sessionService = new AgentSessionService();
        Path dummyCwd = tempRoot.getParent(); // Incorrect cwd (not project root)
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, dummyCwd, "claude", "conn-claude-1");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + tempRoot.toUri() + "\"}}";
        String responseJson = handler.handleMessage(initReq);

        assertNotNull(responseJson);
        assertEquals(tempRoot, handler.activeProjectRoot());

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        String callResponse = handler.handleMessage(callReq);
        assertTrue(callResponse.contains("ready"));
    }

    @Test
    void unpinnedConnectionListsToolsBeforeResolvingAProjectRoot() {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot.getParent(), "codex", "conn-late-root");

        String listResponse = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertTrue(listResponse.contains("\"name\":\"ensure_session\""));

        handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}");
        String rootsChanged = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\",\"params\":{\"workspaceFolders\":[{\"uri\":\"" + tempRoot.toUri() + "\"}]}}";
        handler.handleMessage(rootsChanged);
        String ready = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}");
        assertTrue(ready.contains("ready"));
    }

    @Test
    void concurrentUnpinnedConnectionsKeepTheirProjectRootsIsolated() throws Exception {
        Path secondRoot = Files.createTempDirectory("synesis-mcp-second-project-");
        git(secondRoot, "init");
        git(secondRoot, "config", "user.name", "Test User");
        git(secondRoot, "config", "user.email", "test@example.com");
        Files.writeString(secondRoot.resolve("README.md"), "# Second MCP project\n");
        git(secondRoot, "add", ".");
        git(secondRoot, "commit", "-m", "Initial commit");
        ProjectLocation secondLocation = new ProjectApplicationService().init(secondRoot).location();
        McpProviderTestSupport.install(secondLocation, "codex");

        McpProtocolHandler first = new McpProtocolHandler(new AgentSessionService(), tempRoot.getParent(), "codex", "conn-project-a");
        McpProtocolHandler second = new McpProtocolHandler(new AgentSessionService(), tempRoot.getParent(), "codex", "conn-project-b");
        first.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + tempRoot.toUri() + "\"}}");
        second.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + secondRoot.toUri() + "\"}}");

        String firstReady = first.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}");
        String secondReady = second.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}");

        assertEquals(tempRoot, first.activeProjectRoot());
        assertEquals(secondRoot, second.activeProjectRoot());
        assertTrue(firstReady.contains("ready"), firstReady);
        assertTrue(secondReady.contains("ready"), secondReady);
    }

    @Test
    void prooflessConnectionCannotDowngradeAnExactManagedBinding() throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(tempRoot);
        String connection = "conn-managed-proofless";
        var binding = new ProviderSessionBindingService().ensure(location, "codex", connection).binding();
        var owner = ProviderThreadOwnershipStore.storeFor(location).acquire(location.projectId().toString(), "codex", "thread-managed", binding.sessionId());
        var store = ManagedAttachmentService.storeFor(location, binding.sessionId());
        var attachment = new ManagedAttachmentService().issueFromOwnership(location, location.projectId().toString(), "codex", binding.sessionId(), "normal-provider-home", owner, store);
        assertNotNull(attachment.proof());

        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot.getParent(), "codex", connection, false);
        handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + tempRoot.toUri() + "\"}}");
        String response = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}");

        assertTrue(response.contains("managed_attachment_proof_required"), response);
    }

    @Test
    void testInitializeWithWorkspaceFoldersBindsProjectRoot() {
        AgentSessionService sessionService = new AgentSessionService();
        Path dummyCwd = tempRoot.getParent();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, dummyCwd, "claude", "conn-claude-2");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"workspaceFolders\":[{\"uri\":\"" + tempRoot.toUri() + "\",\"name\":\"test\"}]}}";
        handler.handleMessage(initReq);

        assertEquals(tempRoot, handler.activeProjectRoot());
    }

    @Test
    void testInitializeWithRootsBindsProjectRoot() {
        AgentSessionService sessionService = new AgentSessionService();
        Path dummyCwd = tempRoot.getParent();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, dummyCwd, "claude", "conn-claude-3");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"roots\":[{\"uri\":\"" + tempRoot.toUri() + "\",\"name\":\"test\"}]}}";
        handler.handleMessage(initReq);

        assertEquals(tempRoot, handler.activeProjectRoot());
    }

    @Test
    @SuppressWarnings("ExtractMethodRecommender")
    void testAmbiguousMultipleInitializedRootsFailClosed() throws Exception {
        Path secondProject = Files.createTempDirectory("synesis-second-proj-");
        git(secondProject, "init");
        git(secondProject, "config", "user.name", "Test User");
        git(secondProject, "config", "user.email", "test@example.com");
        Files.writeString(secondProject.resolve("README.md"), "# Second Repo\n");
        git(secondProject, "add", ".");
        git(secondProject, "commit", "-m", "Initial commit");
        new org.synesis.workspace.application.ProjectApplicationService().init(secondProject);

        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot.getParent(), "claude", "conn-ambiguous");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"workspaceFolders\":[{\"uri\":\"" + tempRoot.toUri() + "\"},{\"uri\":\"" + secondProject.toUri() + "\"}]}}";
        handler.handleMessage(initReq);

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        String callResponse = handler.handleMessage(callReq);

        assertTrue(callResponse.contains("retry_required"));
        assertTrue(callResponse.contains("workspace_not_ready"));
    }

    @Test
    void testWorktreeRootPathIsNotAcceptedAsControlProject() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");
        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        handler.handleMessage(callReq);

        SessionResolutionRequest req = new SessionResolutionRequest(tempRoot, "codex", "conn-1", null, false);
        var ctx = sessionService.resolveSessionContext(req);
        Path worktreePath = ctx.worktreePath();

        McpProtocolHandler worktreeHandler = new McpProtocolHandler(sessionService, tempRoot.getParent(), "claude", "conn-wt-test");
        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + worktreePath.toUri() + "\"}}";
        worktreeHandler.handleMessage(initReq);

        String wtCallResponse = worktreeHandler.handleMessage(callReq);
        assertTrue(wtCallResponse.contains("retry_required"));
    }

    @Test
    void testMissingProjectJsonReturnsWorkspaceNotReady() throws Exception {
        Path uninit = Files.createTempDirectory("synesis-uninit-root-");
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, uninit, "claude", "conn-uninit");

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        String callResponse = handler.handleMessage(callReq);

        assertTrue(callResponse.contains("retry_required"));
        assertTrue(callResponse.contains("workspace_not_ready"));
    }

    @Test
    void testEncodedWindowsFileUriAndUriWithSpacesParsedCleanly() {
        Path p1 = McpProtocolHandler.parseUriOrPath("file:///C:/My%20Test%20Folder/project");
        assertNotNull(p1);
        assertTrue(p1.toString().contains("My Test Folder"));

        Path p2 = McpProtocolHandler.parseUriOrPath("file:///c%3A/Users/Liparakis/Desktop/SynesisTestProject");
        assertNotNull(p2);
        assertTrue(p2.toString().replace('\\', '/').toLowerCase().contains("synesistestproject"));

        Path absoluteUnixUri = McpProtocolHandler.parseUriOrPath(tempRoot.toUri().toString());
        assertEquals(tempRoot, absoluteUnixUri);
    }

    @Test
    void testRootsListChangedNotificationUpdatesUnboundContext() {
        AgentSessionService sessionService = new AgentSessionService();
        Path dummyCwd = tempRoot.getParent();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, dummyCwd, "claude", "conn-changed-1");

        String notif = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\",\"params\":{\"workspaceFolders\":[{\"uri\":\"" + tempRoot.toUri() + "\"}]}}";
        handler.handleMessage(notif);

        assertEquals(tempRoot, handler.activeProjectRoot());
    }

    @Test
    void testConflictingRootNotificationAfterSessionBindingIsRejected() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "claude", "conn-bound-1");

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        String response = handler.handleMessage(callReq);
        assertTrue(response.contains("ready"));

        Path conflicting = tempRoot.getParent();
        String notif = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\",\"params\":{\"workspaceFolders\":[{\"uri\":\"" + conflicting.toUri() + "\"}]}}";
        handler.handleMessage(notif);

        assertEquals(tempRoot, handler.activeProjectRoot());
    }

    @Test
    void testRootsListReturnsActiveProjectRoot() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-roots");

        String rootsReq = "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"roots/list\"}";
        String responseJson = handler.handleMessage(rootsReq);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"roots\":"));
        assertTrue(responseJson.contains(tempRoot.getFileName().toString()));
    }

    @Test
    void testInitializeNegotiatesSupportedProtocolVersion() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-version");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"," + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}";
        String responseJson = handler.handleMessage(initReq);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"protocolVersion\":\"2025-06-18\""));
    }

    @Test
    void testInitializeFallsBackForUnsupportedProtocolVersion() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-version-fallback");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"," + "\"params\":{\"protocolVersion\":\"2099-01-01\"}}";
        String responseJson = handler.handleMessage(initReq);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"protocolVersion\":\"2024-11-05\""));
    }

    @Test
    void testStdioServerEventLoopAndCleanEofShutdown() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n" + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\"}}\n";

        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        McpStdioServer server = new McpStdioServer(handler, in, new PrintStream(out), new PrintStream(err));
        int exitCode = server.run();

        assertEquals(0, exitCode);
        assertEquals(0, err.size()); // Stderr clean

        String stdoutString = out.toString(StandardCharsets.UTF_8);
        List<String> lines = stdoutString.lines().toList();
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("\"id\":1"));
        assertTrue(lines.get(1).contains("\"id\":2"));
        assertTrue(lines.get(2).contains("\"id\":3"));
    }

    @Test
    void stdioTransportFailureDurablyDisconnectsTerminalLease() {
        String connection = "conn-stdio-abnormal";
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", connection);
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        assertTrue(handler.handleMessage(ensure).contains("ready"));
        assertTrue(new org.synesis.workspace.lifecycle.lease.SessionLeaseService().markTerminalAuthorityConfirmed(tempRoot, connection));

        java.io.InputStream failingInput = new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("synthetic transport failure");
            }
        };
        McpStdioServer server = new McpStdioServer(handler, failingInput, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(1, server.run());
        assertEquals(org.synesis.workspace.lifecycle.lease.SessionLeaseState.TERMINAL_DISCONNECTED, new SessionLeaseStore().load(tempRoot, connection).orElseThrow().leaseState());
    }

    @Test
    void testMcpReadFileAndApplyPatchEndToEnd() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "claude", "conn-mcp-tools");

        // 1. Ensure Session
        String ensureReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        String ensureResp = handler.handleMessage(ensureReq);
        assertTrue(ensureResp.contains("ready"));

        // 2. Apply Patch Create Mode
        String createReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"apply_patch\",\"arguments\":{\"path\":\"src/McpFile.txt\",\"create\":true,\"content\":\"Hello MCP World\\n\"}}}";
        String createResp = handler.handleMessage(createReq);
        assertTrue(createResp.contains("completed"));
        assertTrue(createResp.contains("src/McpFile.txt"));

        // 3. Read File
        String readReq = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"read_file\",\"arguments\":{\"path\":\"src/McpFile.txt\"}}}";
        String readResp = handler.handleMessage(readReq);
        assertTrue(readResp.contains("completed"));
        assertTrue(readResp.contains("Hello MCP World"));

        // 4. Apply Patch Modify Mode
        byte[] bytes = "Hello MCP World\n".getBytes(StandardCharsets.UTF_8);
        String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String modifyReq = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"apply_patch\",\"arguments\":{\"path\":\"src/McpFile.txt\",\"expectedHash\":\"" + hash
                + "\",\"edits\":[{\"find\":\"World\",\"replace\":\"Claude\",\"expectedOccurrences\":1}]}}}";
        String modifyResp = handler.handleMessage(modifyReq);
        assertTrue(modifyResp.contains("completed"));

        // 5. Read Modified File
        String readModResp = handler.handleMessage(readReq);
        assertTrue(readModResp.contains("Hello MCP Claude"));

        // 5b. An edit that does not say how often its text occurs is applied as one occurrence.
        // It used to be dropped, which left an empty patch refused as an invalid path.
        String claudeHash;
        try {
            claudeHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("Hello MCP Claude\n".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        String uncountedResp = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"tools/call\",\"params\":{\"name\":\"apply_patch\",\"arguments\":{\"path\":\"src/McpFile.txt\",\"expectedHash\":\"" + claudeHash
                + "\",\"edits\":[{\"find\":\"Claude\",\"replace\":\"Codex\"}]}}}");
        assertTrue(uncountedResp.contains("completed"), uncountedResp);
        assertTrue(handler.handleMessage(readReq).contains("Hello MCP Codex"));

        // 6. Run Command (direct argv)
        String runCmdReq = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"run_command\",\"arguments\":{\"argv\":[\"git\",\"status\",\"--porcelain\"]}}}";
        String runCmdResp = handler.handleMessage(runCmdReq);
        assertTrue(runCmdResp.contains("completed"), runCmdResp);
        assertTrue(runCmdResp.contains("stdoutBytesRead"));

        // 7. Get Next Action
        String nextActionReq = "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"get_next_action\",\"arguments\":{}}}";
        String nextActionResp = handler.handleMessage(nextActionReq);
        assertTrue(nextActionResp.contains("ready"));
        assertTrue(nextActionResp.contains("pending"));
        assertTrue(nextActionResp.contains("actionId"));
        assertTrue(nextActionResp.contains("AT_LEAST_ONCE"));
        String nextActionAgain = handler.handleMessage(nextActionReq);
        Matcher matcher = Pattern.compile("actionId[^0-9a-f]*([0-9a-f]{8}-[0-9a-f-]{27})").matcher(nextActionResp);
        assertTrue(matcher.find());
        assertTrue(nextActionAgain.contains(matcher.group(1)));
    }

    @Test
    void runCommandWithoutClaimsRenewsLeaseAndPersistsTerminalResult() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "claude", "conn-mcp-no-claims");

        String ensureReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        assertTrue(handler.handleMessage(ensureReq).contains("ready"));

        String runReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"run_command\",\"arguments\":{\"argv\":[\"git\",\"status\",\"--porcelain\"]}}}";
        String response = handler.handleMessage(runReq);

        assertTrue(response.contains("completed"), response);
        assertFalse(response.contains("LEASE_RENEWAL_FAILED"), response);
    }

    @Test
    void providerShapedPathAliasesRemainRevisionChecked() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "claude", "conn-aliases");

        String ensureReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        assertTrue(handler.handleMessage(ensureReq).contains("ready"));

        String createReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"apply_patch\",\"arguments\":{\"relativePath\":\"src/alias.txt\",\"create\":true,\"newContent\":\"alias\"}}}";
        String createResp = handler.handleMessage(createReq);
        assertTrue(createResp.contains("completed"));

        String readReq = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"read_file\",\"arguments\":{\"relativePath\":\"src/alias.txt\"}}}";
        String readResp = handler.handleMessage(readReq);
        assertTrue(readResp.contains("completed"));
        assertTrue(readResp.contains("alias"));
    }
}
