package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentNextActionService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.capability.CapabilityRequestService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.test.ProviderTestSupport;

/** Exercises durable next-action projection and exact lifecycle guidance. */
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

        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(controlRoot)
                .location();
        ProviderTestSupport.install(location, "codex");
        ProviderTestSupport.install(location, "claude");
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
    void committedProducerWithoutReviewReceivesFinishProjection() throws Exception {
        prepareSessionAndTrust("codex", "committed-producer");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claim = collaboration.announce(controlRoot, "codex", "committed-producer",
                "Implement the product change", "Publish the completed product change",
                List.of(ResourceSelector.pathExact("src/Product.java")));

        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        var binding = bindingService.find(location, "codex", "committed-producer")
                .orElseThrow();
        Path worker = Path.of(binding.worktreePath());
        Files.writeString(worker.resolve("src/Product.java"), "public class Product { int version = 2; }\n");
        git(worker, "add", "src/Product.java");
        git(worker, "commit", "-m", "worker product change");

        AgentResponse response = new AgentNextActionService().getNextAction(
                new AgentNextActionService.NextActionRequest(controlRoot, "codex", "committed-producer"));

        assertEquals(AgentStatus.READY, response.status(), response.toJson());
        assertEquals(AgentReason.SNAPSHOT_PUBLICATION_REQUIRED, response.reason(), response.toJson());
        assertEquals(AgentNextAction.FINISH_LANE, response.nextAction(), response.toJson());
        assertEquals(claim.intent().intentId().toString(),
                ((Map<?, ?>) response.result()).get("intentId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitDependencyProjectsCapabilityGuidanceWithoutEmptyRequest() throws Exception {
        prepareSessionAndTrust("codex", "dependency-requester");
        prepareSessionAndTrust("claude", "dependency-owner");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var requester = collaboration.announce(controlRoot, "codex", "dependency-requester",
                "Implement task-tracker integration", "Use the domain capability when available",
                List.of(ResourceSelector.pathExact("src/service")), null,
                WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.PRODUCER, List.of(),
                List.of("tasktracker.domain"));
        collaboration.announce(controlRoot, "claude", "dependency-owner",
                "Implement the task-tracker domain", "Publish the domain capability",
                List.of(ResourceSelector.pathExact("src/domain")), requester.intent().workGroupId());

        AgentResponse response = new AgentNextActionService().getNextAction(
                new AgentNextActionService.NextActionRequest(controlRoot, "codex", "dependency-requester"));

        assertEquals(AgentStatus.NEEDS_CAPABILITY, response.status(), response.toJson());
        assertEquals(AgentReason.OWNER_REQUIRED, response.reason());
        assertEquals(AgentNextAction.REQUEST_COORDINATION, response.nextAction());
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertEquals("tasktracker.domain", result.get("capability"));
        assertEquals(List.of("inputs", "output", "requiredBehavior", "acceptanceTests"),
                result.get("requiredFields"));
        Map<String, Object> currentIntent = (Map<String, Object>) result.get("currentIntent");
        assertEquals(List.of("tasktracker.domain"), currentIntent.get("knownDependencies"));

        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("REVISE_SCOPE", workflow.get("type"));
        assertFalse(workflow.containsKey("recommendedTool"), response.toJson());
        assertFalse(workflow.containsKey("arguments"), response.toJson());
    }

    @Test
    void requesterDoesNotReceiveItsOwnCapabilityRequestAsOwnerAction() throws Exception {
        prepareSessionAndTrust("codex", "requester-owner-filter");
        prepareSessionAndTrust("claude", "requester-owner-filter-owner");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var requester = collaboration.announce(controlRoot, "codex", "requester-owner-filter",
                "Implement task-tracker service", "Use the domain capability",
                List.of(ResourceSelector.pathExact("src/service")), null,
                WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.PRODUCER, List.of(),
                List.of("tasktracker.domain"));
        collaboration.announce(controlRoot, "claude", "requester-owner-filter-owner",
                "Implement task-tracker domain", "Provide the domain capability",
                List.of(ResourceSelector.pathExact("src/domain")), requester.intent().workGroupId(),
                WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.PRODUCER, List.of(), List.of());

        CapabilityContract contract = new CapabilityContract(
                "Task title and description",
                "Task and repository contract",
                List.of("Create TODO tasks", "Enforce valid status transitions"),
                List.of("Invalid input is rejected"));
        AgentResponse requestResponse = new CapabilityRequestService().describeRequiredCapability(
                new CapabilityRequestService.DescribeCapabilityRequest(
                        controlRoot, "codex", "requester-owner-filter", "tasktracker.domain", contract, null, null));
        assertEquals(AgentStatus.WAITING, requestResponse.status(), requestResponse.toJson());

        AgentResponse requesterNext = new AgentNextActionService().getNextAction(
                new AgentNextActionService.NextActionRequest(controlRoot, "codex", "requester-owner-filter"));
        assertEquals(AgentNextAction.WAIT, requesterNext.nextAction(), requesterNext.toJson());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, requesterNext.reason());

        AgentResponse ownerNext = new AgentNextActionService().getNextAction(
                new AgentNextActionService.NextActionRequest(controlRoot, "claude", "requester-owner-filter-owner"));
        assertEquals(AgentNextAction.RESPOND_COORDINATION, ownerNext.nextAction(), ownerNext.toJson());
    }

    @Test
    void sameProviderRequesterDoesNotReceiveCapabilityOwnerAction() throws Exception {
        prepareSessionAndTrust("codex", "same-provider-requester");
        prepareSessionAndTrust("codex", "same-provider-owner");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var requester = collaboration.announce(controlRoot, "codex", "same-provider-requester",
                "Implement task-tracker service", "Use the domain capability",
                List.of(ResourceSelector.pathExact("src/service")), null,
                WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.PRODUCER, List.of(),
                List.of("tasktracker.domain"));
        collaboration.announce(controlRoot, "codex", "same-provider-owner",
                "Implement task-tracker domain", "Provide the domain capability",
                List.of(ResourceSelector.pathExact("src/domain")), requester.intent().workGroupId(),
                WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.PRODUCER, List.of(), List.of());

        CapabilityContract contract = new CapabilityContract(
                "Task title and description",
                "Task and repository contract",
                List.of("Create TODO tasks", "Enforce valid status transitions"),
                List.of("Invalid input is rejected"));
        AgentResponse requestResponse = new CapabilityRequestService().describeRequiredCapability(
                new CapabilityRequestService.DescribeCapabilityRequest(
                        controlRoot, "codex", "same-provider-requester", "tasktracker.domain", contract, null, null));
        assertEquals(AgentStatus.WAITING, requestResponse.status(), requestResponse.toJson());

        AgentResponse requesterNext = new AgentNextActionService().getNextAction(
                new AgentNextActionService.NextActionRequest(controlRoot, "codex", "same-provider-requester"));
        assertEquals(AgentNextAction.WAIT, requesterNext.nextAction(), requesterNext.toJson());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, requesterNext.reason());

        AgentResponse ownerNext = new AgentNextActionService().getNextAction(
                new AgentNextActionService.NextActionRequest(controlRoot, "codex", "same-provider-owner"));
        assertEquals(AgentNextAction.RESPOND_COORDINATION, ownerNext.nextAction(), ownerNext.toJson());
    }

    @Test
    void testSurfacesNeedsCapabilityAndPrioritizesSafetyFailure() throws Exception {
        prepareSessionAndTrust("codex", "conn-na-2");

        // Write synthetic coordination items file
        Path coordDir = controlRoot.resolve(".synesis/local/coordination");
        Files.createDirectories(coordDir);

        List<Object> items = List.of(
                java.util.Map.of("type",
                        "NEEDS_CAPABILITY",
                        "capability",
                        "catalog.product-query",
                        "workerId",
                        "codex"),
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
        prepareSessionAndTrust("claude", "conn-na-3");

        Path coordDir = controlRoot.resolve(".synesis/local/coordination");
        Files.createDirectories(coordDir);

        List<Object> items = List.of(
                java.util.Map.of("type",
                        "OWNER_REQUEST",
                        "capability",
                        "catalog.product-query",
                        "workerId",
                        "claude",
                        "details",
                        java.util.Map.of("inputs", "query", "output", "result")),
                java.util.Map.of("type", "NEEDS_CAPABILITY", "capability", "other.service", "workerId", "other-worker")
        );
        Files.writeString(coordDir.resolve("items.json"), ProviderJson.write(items));

        AgentNextActionService service = new AgentNextActionService();
        AgentNextActionService.NextActionRequest req = new AgentNextActionService.NextActionRequest(
                controlRoot, "claude", "conn-na-3");

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
                java.util.Map.of("type",
                        "NEEDS_CAPABILITY",
                        "capability",
                        "old.cap",
                        "workerId",
                        "codex",
                        "completed",
                        true),
                java.util.Map.of("type",
                        "VALIDATION_REQUIRED",
                        "capability",
                        "old.cap2",
                        "workerId",
                        "codex",
                        "obsolete",
                        true)
        );
        Files.writeString(coordDir.resolve("items.json"), ProviderJson.write(items));

        AgentNextActionService service = new AgentNextActionService();
        AgentNextActionService.NextActionRequest req = new AgentNextActionService.NextActionRequest(
                controlRoot, "codex", "conn-na-4");

        AgentResponse response = service.getNextAction(req);
        assertEquals(AgentStatus.READY, response.status());
        assertTrue(response.toJson()
                .contains("\"pending\":0"));
    }

    @Test
    void unclaimedSessionCannotReceiveReviewAdmissionWithoutExplicitRole() throws Exception {
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        new org.synesis.workspace.application.provider.ProviderManualService().install("claude");

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "claim-owner", null, false));
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(
                controlRoot, "claude", "claim-contender", null, false));

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.announce(controlRoot, "codex", "claim-owner", "Implement source", "Publish source",
                List.of(ResourceSelector.pathExact("src/task_tracker.py")));

        AgentNextActionService service = new AgentNextActionService();
        AgentResponse response = service.getNextAction(new AgentNextActionService.NextActionRequest(
                controlRoot, "claude", "claim-contender"));

        assertEquals(AgentStatus.BLOCKED, response.status());
        assertFalse(response.toJson()
                .contains("REVIEW_ADMISSION_REQUIRED"), response.toJson());
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeReviewerIntentStillReceivesReviewAdmissionForSharedWorkGroup() throws Exception {
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        new org.synesis.workspace.application.provider.ProviderManualService().install("claude");

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "active-owner", null, false));
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(
                controlRoot, "claude", "active-reviewer", null, false));

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var ownerClaim = collaboration.announce(controlRoot, "codex", "active-owner",
                "Implement source", "Publish source",
                List.of(ResourceSelector.pathExact("src/task_tracker.py")));
        var reviewerClaim = collaboration.announce(controlRoot, "claude", "active-reviewer",
                "Review source", "Validate the published source",
                List.of(ResourceSelector.pathExact("tests/test_task_tracker.py")),
                ownerClaim.intent()
                        .workGroupId(), WorkIntent.CompletionMode.SNAPSHOT_REQUIRED,
                WorkIntent.Role.REVIEWER, List.of(ResourceSelector.pathExact("src/task_tracker.py")));
        assertEquals(ownerClaim.intent()
                        .workGroupId(),
                reviewerClaim.intent()
                        .workGroupId());

        AgentNextActionService service = new AgentNextActionService();
        AgentResponse response = service.getNextAction(new AgentNextActionService.NextActionRequest(
                controlRoot, "claude", "active-reviewer"));

        assertEquals(AgentStatus.READY, response.status());
        assertEquals(AgentReason.VALIDATION_REQUIRED, response.reason());
        assertEquals(AgentNextAction.REQUEST_COORDINATION, response.nextAction());
        assertTrue(response.toJson()
                .contains("REVIEW_ADMISSION_REQUIRED"));
        assertTrue(response.toJson()
                .contains("work_group_join"));
        assertTrue(response.toJson()
                .contains(ownerClaim.intent()
                        .workGroupId()
                        .toString()));
        assertTrue(response.toJson()
                .contains(ownerClaim.intent()
                        .intentId()
                        .toString()));
        Map<String, Object> result = (Map<String, Object>) response.result();
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("request_coordination", workflow.get("recommendedTool"));
        Map<String, Object> arguments = (Map<String, Object>) workflow.get("arguments");
        assertEquals("work_group_join", arguments.get("kind"));
        Map<String, Object> payload = (Map<String, Object>) arguments.get("payload");
        assertEquals(ownerClaim.intent()
                .workGroupId()
                .toString(), payload.get("workGroupId"));
        assertEquals(ownerClaim.intent()
                .intentId()
                .toString(), payload.get("intentId"));

        AgentResponse ownerResponse = service.getNextAction(new AgentNextActionService.NextActionRequest(
                controlRoot, "codex", "active-owner"));
        assertFalse(ownerResponse.toJson()
                .contains("REVIEW_ADMISSION_REQUIRED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingReviewAdmissionProjectsWaitInsteadOfReplayingRequest() throws Exception {
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        prepareSessionAndTrust("codex", "pending-review-owner");
        prepareSessionAndTrust("codex", "pending-review-reviewer");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var ownerClaim = collaboration.announce(controlRoot, "codex", "pending-review-owner",
                "Implement source", "Review the completed source snapshot",
                List.of(ResourceSelector.pathExact("src/Product.java")));
        var reviewerClaim = collaboration.announce(controlRoot, "codex", "pending-review-reviewer",
                "Review source", "Validate the completed source snapshot",
                List.of(ResourceSelector.pathExact("tests/ProductTest.java")),
                ownerClaim.intent()
                        .workGroupId(), WorkIntent.CompletionMode.SNAPSHOT_REQUIRED,
                WorkIntent.Role.REVIEWER, List.of(ResourceSelector.pathExact("src/Product.java")));
        assertEquals(ownerClaim.intent()
                        .workGroupId(),
                reviewerClaim.intent()
                        .workGroupId());

        AgentNextActionService service = new AgentNextActionService();
        AgentResponse initial = service.getNextAction(new AgentNextActionService.NextActionRequest(
                controlRoot, "codex", "pending-review-reviewer"));
        assertEquals(AgentNextAction.REQUEST_COORDINATION, initial.nextAction());
        assertTrue(initial.toJson()
                .contains("REVIEW_ADMISSION_REQUIRED"), initial.toJson());

        CoordinationRequest request = collaboration.request(controlRoot, "codex", "pending-review-reviewer",
                ownerClaim.intent()
                        .intentId(), CoordinationRequest.Kind.REVIEW,
                "Review the completed source snapshot");

        AgentResponse pending = service.getNextAction(new AgentNextActionService.NextActionRequest(
                controlRoot, "codex", "pending-review-reviewer"));
        assertEquals(AgentStatus.WAITING, pending.status());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, pending.reason());
        assertEquals(AgentNextAction.WAIT, pending.nextAction());
        Map<String, Object> result = (Map<String, Object>) pending.result();
        assertEquals(Boolean.TRUE, result.get("reviewRequestPending"));
        Map<String, Object> reviewRequest = (Map<String, Object>) result.get("reviewRequest");
        assertEquals(request.requestId()
                .toString(), reviewRequest.get("requestId"));
        assertEquals(ownerClaim.intent()
                .intentId()
                .toString(), reviewRequest.get("intentId"));
        assertEquals("wait", result.get("nextProtocolAction"));
        assertEquals("review_admission", result.get("nextProtocolKind"));
        Map<String, Object> payload = (Map<String, Object>) result.get("nextProtocolPayload");
        assertEquals(request.requestId()
                .toString(), payload.get("requestId"));
        assertEquals(ownerClaim.intent()
                .workGroupId()
                .toString(), payload.get("workGroupId"));
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("WAIT", workflow.get("type"));
        assertEquals("get_next_action", workflow.get("recommendedTool"));
        assertEquals(Map.of(), workflow.get("arguments"));
        assertFalse(pending.toJson()
                .contains("REVIEW_ADMISSION_REQUIRED"), pending.toJson());
        assertTrue(((List<?>) result.get("reviewActions")).isEmpty(), pending.toJson());

        AgentResponse repeated = service.getNextAction(new AgentNextActionService.NextActionRequest(
                controlRoot, "codex", "pending-review-reviewer"));
        Map<String, Object> repeatedResult = (Map<String, Object>) repeated.result();
        Map<String, Object> repeatedRequest = (Map<String, Object>) repeatedResult.get("reviewRequest");
        assertEquals(request.requestId()
                .toString(), repeatedRequest.get("requestId"));
        assertEquals(AgentNextAction.WAIT, repeated.nextAction());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reviewerFirstIntentTargetsThePublishedPeerSnapshot() throws Exception {
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
        new org.synesis.workspace.application.provider.ProviderManualService().install("claude");

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(
                controlRoot, "claude", "reviewer-first", null, false));
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "owner-second", null, false));

        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var ownerBinding = bindings.find(location, "codex", "owner-second")
                .orElseThrow();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var reviewerClaim = collaboration.announce(controlRoot, "claude", "reviewer-first",
                "Review source", "Validate the published source",
                List.of(ResourceSelector.pathExact("tests/test_task_tracker.py")), null,
                WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/task_tracker.py")));
        var ownerClaim = collaboration.announce(controlRoot, "codex", "owner-second",
                "Implement source", "Publish the completed source",
                List.of(ResourceSelector.pathExact("src/task_tracker.py")));
        assertEquals(reviewerClaim.intent()
                        .workGroupId(),
                ownerClaim.intent()
                        .workGroupId());

        var identity = new IdentityBootstrap(location.profile()
                .resolve("link")).loadOrCreate()
                .identity();
        var store = new PredictionEventStore(location.root()
                .resolve(".synesis/coordination"), location.projectId());
        String ownerParticipant = WorkspaceCollaborationService.participantHandle(ownerBinding.sessionId());
        SnapshotProvenance provenance = new SnapshotProvenance(ownerClaim.intent()
                .workGroupId(),
                ownerClaim.intent()
                        .intentId(), ownerParticipant, ownerBinding.sessionId(), 1,
                List.of(), List.of(), List.of("PATH_EXACT:src/task_tracker.py"),
                "refs/synesis/snapshots/snap_reviewer_first", "reviewer-first-integrity");
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(
                UUID.nameUUIDFromBytes("syn039-reviewer-first-task".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "snap_reviewer_first", identity.nodeId(), "supervisor", "worker", ownerBinding.sessionId(),
                ownerBinding.baseCommit(), ownerBinding.baseCommit(), List.of("src/task_tracker.py"), List.of(),
                "Published source", provenance);
        store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                identity.nodeId(), snapshot.encode(), identity);

        AgentResponse response = new AgentNextActionService().getNextAction(
                new AgentNextActionService.NextActionRequest(controlRoot, "claude", "reviewer-first"));

        assertEquals(AgentStatus.READY, response.status());
        assertEquals(AgentReason.VALIDATION_REQUIRED, response.reason());
        assertEquals(AgentNextAction.REQUEST_COORDINATION, response.nextAction());
        Map<String, Object> result = (Map<String, Object>) response.result();
        List<Map<String, Object>> reviewActions = (List<Map<String, Object>>) result.get("reviewActions");
        assertEquals(1, reviewActions.size());
        assertEquals("REVIEW_ADMISSION_REQUIRED",
                reviewActions.getFirst()
                        .get("state"));
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        Map<String, Object> arguments = (Map<String, Object>) workflow.get("arguments");
        Map<String, Object> payload = (Map<String, Object>) arguments.get("payload");
        assertEquals("request_coordination", workflow.get("recommendedTool"));
        assertEquals("work_group_join", arguments.get("kind"));
        assertEquals(ownerClaim.intent()
                .workGroupId()
                .toString(), payload.get("workGroupId"));
        assertEquals(ownerClaim.intent()
                .intentId()
                .toString(), payload.get("intentId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void completedParticipantProjectsReviewOnlyAdmissionForActiveSiblingGroup() throws Exception {
        new org.synesis.workspace.application.provider.ProviderManualService().install("codex");

        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "completed-reviewer", null, false));
        sessionService.ensureSession(new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "active-owner", null, false));

        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var completedBinding = bindings.find(location, "codex", "completed-reviewer")
                .orElseThrow();
        var ownerBinding = bindings.find(location, "codex", "active-owner")
                .orElseThrow();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var completedClaim = collaboration.announce(controlRoot, "codex", "completed-reviewer",
                "Review the sibling implementation", "Review the immutable sibling snapshot",
                List.of(ResourceSelector.pathExact("tests/completed_review.py")), null,
                WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/sibling.py")));
        var ownerClaim = collaboration.announce(controlRoot,
                "codex",
                "active-owner",
                "Implement the sibling source",
                "Publish the completed source",
                List.of(ResourceSelector.pathExact("src/sibling.py")),
                completedClaim.intent()
                        .workGroupId());

        var identity = new IdentityBootstrap(location.profile()
                .resolve("link")).loadOrCreate()
                .identity();
        var store = new PredictionEventStore(location.root()
                .resolve(".synesis/coordination"), location.projectId());
        SnapshotProvenance provenance = new SnapshotProvenance(completedClaim.intent()
                .workGroupId(),
                completedClaim.intent()
                        .intentId(),
                WorkspaceCollaborationService.participantHandle(completedBinding.sessionId()),
                completedBinding.sessionId(), 1, List.of(), List.of(),
                List.of("PATH_EXACT:tests/completed_review.py"),
                "refs/synesis/snapshots/snap_completed_review", "completed-review-integrity");
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(
                UUID.nameUUIDFromBytes("syn039-completed-review-task".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "snap_completed_review", identity.nodeId(), "supervisor", "worker", completedBinding.sessionId(),
                completedBinding.baseCommit(), completedBinding.baseCommit(),
                List.of("tests/completed_review.py"), List.of(), "Completed review lane", provenance);
        store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                identity.nodeId(), snapshot.encode(), identity);
        collaboration.release(controlRoot, "codex", "completed-reviewer");
        assertTrue(bindings.complete(location, "codex", "completed-reviewer"));

        AgentResponse response = new AgentNextActionService().getNextAction(
                new AgentNextActionService.NextActionRequest(controlRoot, "codex", "completed-reviewer"));

        assertEquals(AgentStatus.READY, response.status());
        assertEquals(AgentReason.VALIDATION_REQUIRED, response.reason());
        assertEquals(AgentNextAction.REQUEST_COORDINATION, response.nextAction());
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertEquals(Boolean.TRUE, result.get("reviewOnly"));
        List<Map<String, Object>> reviewActions = (List<Map<String, Object>>) result.get("reviewActions");
        assertEquals(1, reviewActions.size());
        assertEquals("REVIEW_ADMISSION_REQUIRED",
                reviewActions.getFirst()
                        .get("state"));
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("request_coordination", workflow.get("recommendedTool"));
        Map<String, Object> arguments = (Map<String, Object>) workflow.get("arguments");
        assertEquals("work_group_join", arguments.get("kind"));
        Map<String, Object> payload = (Map<String, Object>) arguments.get("payload");
        assertEquals(ownerClaim.intent()
                .workGroupId()
                .toString(), payload.get("workGroupId"));
        assertEquals(ownerClaim.intent()
                .intentId()
                .toString(), payload.get("intentId"));

        var request = collaboration.request(controlRoot, "codex", "completed-reviewer",
                ownerClaim.intent()
                        .intentId(), org.synesis.coordination.domain.collaboration.CoordinationRequest.Kind.REVIEW,
                "Review the sibling immutable snapshot");
        assertEquals(WorkspaceCollaborationService.participantHandle(ownerBinding.sessionId()), request.target());

        AgentResponse pending = new AgentNextActionService().getNextAction(
                new AgentNextActionService.NextActionRequest(controlRoot, "codex", "completed-reviewer"));
        assertEquals(AgentStatus.WAITING, pending.status());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, pending.reason());
        assertEquals(AgentNextAction.WAIT, pending.nextAction());
        Map<String, Object> pendingResult = (Map<String, Object>) pending.result();
        assertEquals(Boolean.TRUE, pendingResult.get("reviewRequestPending"));
        Map<String, Object> pendingRequest = (Map<String, Object>) pendingResult.get("reviewRequest");
        assertEquals(request.requestId()
                .toString(), pendingRequest.get("requestId"));
        assertEquals("get_next_action",
                ((Map<String, Object>) pendingResult.get("workflow")).get("recommendedTool"));

        AgentResponse mutation = new org.synesis.workspace.application.workspace.WorkspacePatchService().applyPatch(
                new org.synesis.workspace.application.workspace.WorkspacePatchService.PatchRequest(
                        controlRoot, "codex", "completed-reviewer", "src/forbidden.py", true,
                        "print('must not mutate')\n", null, List.of()));
        assertEquals(AgentStatus.RETRY_REQUIRED, mutation.status());
        assertEquals(AgentReason.WORKSPACE_STALE, mutation.reason());
    }
}
