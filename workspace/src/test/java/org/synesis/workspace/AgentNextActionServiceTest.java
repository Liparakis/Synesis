package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.application.agent.AgentNextActionService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;

class AgentNextActionServiceTest {

    private Path controlRoot;

    private static void git(Path root, String... arguments) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, arguments);
    }

    @BeforeEach
    void setUp() throws Exception {
        controlRoot = Files.createTempDirectory("synesis-nextaction-test-");
        git(controlRoot, "init");
        git(controlRoot, "config", "user.name", "Test User");
        git(controlRoot, "config", "user.email", "test@example.com");

        Files.createDirectories(controlRoot.resolve("src"));
        Files.writeString(controlRoot.resolve("src/Product.java"), "public class Product {}\n");

        git(controlRoot, "add", ".");
        git(controlRoot, "commit", "-m", "Initial commit");

        new ProjectApplicationService().init(controlRoot);
    }

    private void prepareSessionAndTrust(String provider, String connId) throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest sessionReq = new AgentSessionService.SessionResolutionRequest(
                controlRoot, provider, connId, null, false);
        sessionService.ensureSession(sessionReq);

        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        var bindings = bindingService.list(location, provider);
        var binding = bindings.getLast();
        bindingService.verifyWorkspaceTrust(location, provider, binding.sessionId(), Path.of(binding.worktreePath()));
    }

    @Test
    void testEmptyStateReturnsReadyWithPendingZero() throws Exception {
        prepareSessionAndTrust("codex", "conn-na-1");

        AgentNextActionService service = new AgentNextActionService();
        AgentNextActionService.NextActionRequest req = new AgentNextActionService.NextActionRequest(
                controlRoot, "codex", "conn-na-1");

        AgentResponse response = service.getNextAction(req);
        assertEquals(AgentStatus.READY, response.status());

        String json = response.toJson();
        assertTrue(json.contains("\"pending\":0"));
        assertFalse(json.contains(controlRoot.toString()));
    }

    @Test
    void testSurfacesNeedsCapabilityAndPrioritizesSafetyFailure() throws Exception {
        prepareSessionAndTrust("codex", "conn-na-2");

        // Write synthetic coordination items file
        Path coordDir = controlRoot.resolve(".synesis/local/coordination");
        Files.createDirectories(coordDir);

        List<Object> items = List.of(
                java.util.Map.of("type", "NEEDS_CAPABILITY", "capability", "catalog.product-query", "workerId", "codex"),
                java.util.Map.of("type", "SAFETY_FAILURE", "workerId", "codex")
        );
        Files.writeString(coordDir.resolve("items.json"), ProviderJson.write(items));

        AgentNextActionService service = new AgentNextActionService();
        AgentNextActionService.NextActionRequest req = new AgentNextActionService.NextActionRequest(
                controlRoot, "codex", "conn-na-2");

        AgentResponse response = service.getNextAction(req);
        // SAFETY_FAILURE is highest priority
        assertEquals(AgentStatus.FAILED, response.status());
    }

    @Test
    void testSurfacesOwnerRequestConciselyAndFiltersOtherWorkers() throws Exception {
        prepareSessionAndTrust("antigravity", "conn-na-3");

        Path coordDir = controlRoot.resolve(".synesis/local/coordination");
        Files.createDirectories(coordDir);

        List<Object> items = List.of(
                java.util.Map.of("type", "OWNER_REQUEST", "capability", "catalog.product-query", "workerId", "antigravity",
                        "details", java.util.Map.of("inputs", "query", "output", "result")),
                java.util.Map.of("type", "NEEDS_CAPABILITY", "capability", "other.service", "workerId", "other-worker")
        );
        Files.writeString(coordDir.resolve("items.json"), ProviderJson.write(items));

        AgentNextActionService service = new AgentNextActionService();
        AgentNextActionService.NextActionRequest req = new AgentNextActionService.NextActionRequest(
                controlRoot, "antigravity", "conn-na-3");

        AgentResponse response = service.getNextAction(req);
        assertEquals(AgentStatus.WAITING, response.status());

        String json = response.toJson();
        assertTrue(json.contains("owner_request_pending"));
        assertTrue(json.contains("catalog.product-query"));
        assertFalse(json.contains("other.service")); // Filtered out item for other worker
        assertFalse(json.contains("workerId")); // No worker/session IDs leaked
    }

    @Test
    void testExcludesObsoleteAndCompletedItems() throws Exception {
        prepareSessionAndTrust("codex", "conn-na-4");

        Path coordDir = controlRoot.resolve(".synesis/local/coordination");
        Files.createDirectories(coordDir);

        List<Object> items = List.of(
                java.util.Map.of("type", "NEEDS_CAPABILITY", "capability", "old.cap", "workerId", "codex", "completed", true),
                java.util.Map.of("type", "VALIDATION_REQUIRED", "capability", "old.cap2", "workerId", "codex", "obsolete", true)
        );
        Files.writeString(coordDir.resolve("items.json"), ProviderJson.write(items));

        AgentNextActionService service = new AgentNextActionService();
        AgentNextActionService.NextActionRequest req = new AgentNextActionService.NextActionRequest(
                controlRoot, "codex", "conn-na-4");

        AgentResponse response = service.getNextAction(req);
        assertEquals(AgentStatus.READY, response.status());
        assertTrue(response.toJson().contains("\"pending\":0"));
    }

    @Test
    void unclaimedSessionCannotServiceAnotherLanesCapabilityInbox() throws Exception {
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        new org.synesis.workspace.application.provider.ProviderManualService().install("antigravity");

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "claim-owner", null, false));
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(
                controlRoot, "antigravity", "claim-contender", null, false));

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.announce(controlRoot, "codex", "claim-owner", "Implement source", "Publish source",
                List.of(ResourceSelector.pathExact("src/task_tracker.py")));

        AgentNextActionService service = new AgentNextActionService();
        AgentResponse response = service.getNextAction(new AgentNextActionService.NextActionRequest(
                controlRoot, "antigravity", "claim-contender"));

        assertEquals(AgentStatus.READY, response.status());
        assertEquals(AgentReason.VALIDATION_REQUIRED, response.reason());
        assertEquals(AgentNextAction.REQUEST_COORDINATION, response.nextAction());
        assertTrue(response.toJson().contains("REVIEW_ADMISSION_REQUIRED"));
        assertTrue(response.toJson().contains("work_group_join"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeReviewerIntentStillReceivesReviewAdmissionForSharedWorkGroup() throws Exception {
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        new org.synesis.workspace.application.provider.ProviderManualService().install("antigravity");

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "active-owner", null, false));
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(
                controlRoot, "antigravity", "active-reviewer", null, false));

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var ownerClaim = collaboration.announce(controlRoot, "codex", "active-owner",
                "Implement source", "Publish source",
                List.of(ResourceSelector.pathExact("src/task_tracker.py")));
        var reviewerClaim = collaboration.announce(controlRoot, "antigravity", "active-reviewer",
                "Review source", "Validate the published source",
                List.of(ResourceSelector.pathExact("tests/test_task_tracker.py")));
        assertEquals(ownerClaim.intent().workGroupId(), reviewerClaim.intent().workGroupId());

        AgentNextActionService service = new AgentNextActionService();
        AgentResponse response = service.getNextAction(new AgentNextActionService.NextActionRequest(
                controlRoot, "antigravity", "active-reviewer"));

        assertEquals(AgentStatus.READY, response.status());
        assertEquals(AgentReason.VALIDATION_REQUIRED, response.reason());
        assertEquals(AgentNextAction.REQUEST_COORDINATION, response.nextAction());
        assertTrue(response.toJson().contains("REVIEW_ADMISSION_REQUIRED"));
        assertTrue(response.toJson().contains("work_group_join"));
        assertTrue(response.toJson().contains(ownerClaim.intent().workGroupId().toString()));
        assertTrue(response.toJson().contains(ownerClaim.intent().intentId().toString()));
        Map<String, Object> result = (Map<String, Object>) response.result();
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("request_coordination", workflow.get("recommendedTool"));
        Map<String, Object> arguments = (Map<String, Object>) workflow.get("arguments");
        assertEquals("work_group_join", arguments.get("kind"));
        Map<String, Object> payload = (Map<String, Object>) arguments.get("payload");
        assertEquals(ownerClaim.intent().workGroupId().toString(), payload.get("workGroupId"));
        assertEquals(ownerClaim.intent().intentId().toString(), payload.get("intentId"));

        AgentResponse ownerResponse = service.getNextAction(new AgentNextActionService.NextActionRequest(
                controlRoot, "codex", "active-owner"));
        assertFalse(ownerResponse.toJson().contains("REVIEW_ADMISSION_REQUIRED"));
    }
}
