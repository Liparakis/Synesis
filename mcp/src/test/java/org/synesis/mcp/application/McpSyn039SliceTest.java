package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.collaboration.ReviewSnapshotAccessService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.recovery.RecoverySnapshotService;

/**
 * Locks the SYN-039 reviewer and integration evidence boundaries.
 */
@SuppressWarnings("TextBlockMigration")
final class McpSyn039SliceTest {

    private static Map<String, Object> reviewArguments(Map<String, Object> basePayload, String result, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>(basePayload);
        payload.put("result", result);
        if (reason != null) {
            payload.put("reason", reason);
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("kind", "review_validation");
        arguments.put("payload", payload);
        return arguments;
    }

    private static ReviewFixture prepareReviewFixture(Path temp) throws Exception {
        return prepareReviewFixture(temp, ResourceSelector.pathExact("review-report.md"));
    }

    @SuppressWarnings("unchecked")
    private static ReviewFixture prepareReviewFixture(Path temp, ResourceSelector reviewerClaim) throws Exception {
        Path project = temp.resolve("review-publication-project");
        Files.createDirectories(project);
        git(project, "init");
        git(project, "config", "user.name", "SYN-039 Test");
        git(project, "config", "user.email", "syn039@example.test");
        Files.writeString(project.resolve("todo.py"), "def add_todo(items, item):\n    return [*items, item]\n");
        git(project, "add", ".");
        git(project, "commit", "-m", "baseline");

        ProjectApplicationService projectService = new ProjectApplicationService();
        ProjectLocation initialized = projectService.init(project).location();
        McpProviderTestSupport.install(initialized, "codex");
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new SessionResolutionRequest(project, "codex", "syn039-owner-publication", null, false));
        sessions.ensureSession(new SessionResolutionRequest(project, "codex", "syn039-reviewer-publication", null, false));
        var location = new ProjectApplicationService().locate(project);
        var bindings = new ProviderSessionBindingService();
        for (var binding : bindings.list(location, "codex")) {
            if (binding.worktreePath() != null) {
                bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
            }
        }
        var ownerBinding = bindings.find(location, "codex", "syn039-owner-publication").orElseThrow();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claim = collaboration.announce(project, "codex", "syn039-owner-publication", "Implement Todo", "Review the completed Todo snapshot", List.of(ResourceSelector.pathExact("todo.py")));
        UUIDs ids = new UUIDs(claim.intent().workGroupId(), claim.intent().intentId());
        var reviewerIntent = new WorkspaceCollaborationService().announce(project,
                "codex",
                "syn039-reviewer-publication",
                "Review Todo",
                "Review the completed Todo snapshot",
                List.of(reviewerClaim),
                ids.groupId,
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("todo.py")));
        if (!reviewerIntent.acquired()) {
            throw new IllegalStateException("reviewer intent was not acquired");
        }
        McpProtocolHandler owner = new McpProtocolHandler(sessions, project, "codex", "syn039-owner-publication");
        McpProtocolHandler reviewer = new McpProtocolHandler(sessions, project, "codex", "syn039-reviewer-publication");
        String join = reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{" + "\"workGroupId\":\"" + ids.groupId + "\"," + "\"intentId\":\"" + ids.intentId + "\"," + "\"proposal\":\"Review the published Todo snapshot\"}}"));
        String requestId = nestedField(join);
        Map<String, Object> ownerNext = innerResult(owner.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> ownerResult = (Map<String, Object>) ownerNext.get("result");
        Map<String, Object> workflow = (Map<String, Object>) ownerResult.get("workflow");
        Map<String, Object> responseArguments = (Map<String, Object>) workflow.get("arguments");
        assertEquals(requestId, ((Map<String, Object>) responseArguments.get("payload")).get("coordinationRequest"));
        owner.handleMessage(toolCall("respond_coordination", ProviderJson.write(responseArguments)));
        Map<String, Object> status = (Map<String, Object>) innerResult(reviewer.handleMessage(toolCall("request_coordination", "{\"kind\":\"collaboration_status\",\"payload\":{}}"))).get("result");
        Map<String, Object> grant = ((List<Map<String, Object>>) status.get("grants")).getFirst();
        reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{" + "\"workGroupId\":\"" + grant.get("workGroupId") + "\"," + "\"grantId\":\"" + grant.get("grantId") + "\"," + "\"intentId\":\"" + grant.get("targetIntentId")
                        + "\"," + "\"claimEpoch\":" + grant.get("claimEpoch") + "," + "\"targetParticipant\":\"" + grant.get("targetParticipant") + "\"}}"));
        return new ReviewFixture(project, Path.of(ownerBinding.worktreePath()), owner, reviewer, ids.groupId, ids.intentId, reviewerIntent.intent().intentId());
    }

    @SuppressWarnings("unchecked")
    private static String publishOwnerCandidate(ReviewFixture fixture) throws Exception {
        Files.writeString(fixture.ownerWorktree.resolve("todo.py"),
                "def add_todo(items, item):\n    return [*items, item]\n\n" + "def remove_todo(items, item):\n" + "    return [value for value in items if value != item]\n");
        Map<String, Object> projection = innerResult(fixture.owner.handleMessage(toolCall("get_next_action", "{\"completionRequested\":true}")));
        assertEquals("snapshot_publication_required", projection.get("reason"), projection.toString());
        Map<String, Object> result = (Map<String, Object>) projection.get("result");
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        Map<String, Object> arguments = (Map<String, Object>) workflow.get("arguments");
        Map<String, Object> published = innerResult(fixture.owner.handleMessage(toolCall("finish_lane", ProviderJson.write(arguments))));
        assertEquals("waiting", published.get("status"), published.toString());
        Map<String, Object> publishedResult = (Map<String, Object>) published.get("result");
        assertEquals("REVIEW_PENDING", publishedResult.get("snapshotState"), published.toString());
        return String.valueOf(publishedResult.get("snapshotId"));
    }

    private static McpProtocolHandler independentReviewer(Path project, String connectionId, UUID workGroupId, String reviewTarget) throws Exception {
        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new SessionResolutionRequest(project, "codex", connectionId, null, false));
        var location = new ProjectApplicationService().locate(project);
        var bindings = new ProviderSessionBindingService();
        var binding = bindings.find(location, "codex", connectionId).orElseThrow();
        if (binding.worktreePath() == null) {
            throw new IllegalStateException("reviewer worktree was not assigned");
        }
        bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
        var intent = new WorkspaceCollaborationService().announce(project,
                "codex",
                connectionId,
                "Review the independent artifact",
                "Publish an independent review report",
                List.of(ResourceSelector.pathExact("independent-review-report.md")),
                workGroupId,
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact(reviewTarget)));
        if (!intent.acquired()) {
            throw new IllegalStateException("independent reviewer intent was not acquired");
        }
        return new McpProtocolHandler(sessions, project, "codex", connectionId);
    }

    private static ProducerLane independentProducer(Path project, String connectionId, UUID workGroupId, String claimPath) throws Exception {
        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new SessionResolutionRequest(project, "codex", connectionId, null, false));
        var location = new ProjectApplicationService().locate(project);
        var bindings = new ProviderSessionBindingService();
        var binding = bindings.find(location, "codex", connectionId).orElseThrow();
        if (binding.worktreePath() == null) {
            throw new IllegalStateException("producer worktree was not assigned");
        }
        Path worktree = Path.of(binding.worktreePath());
        bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), worktree);
        var intent = new WorkspaceCollaborationService().announce(project,
                "codex",
                connectionId,
                "Publish the independent review artifact",
                "Publish the reviewer artifact snapshot",
                List.of(ResourceSelector.pathExact(claimPath)),
                workGroupId);
        if (!intent.acquired()) {
            throw new IllegalStateException("independent producer intent was not acquired");
        }
        return new ProducerLane(new McpProtocolHandler(sessions, project, "codex", connectionId), intent.intent().intentId(), worktree);
    }

    private static String toolCall(String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}";
    }

    private static String currentParticipant(Path project) throws Exception {
        var location = new ProjectApplicationService().locate(project);
        return WorkspaceCollaborationService.participantHandle(new ProviderSessionBindingService().find(location, "codex", "syn039-owner-publication").orElseThrow().sessionId());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> innerResult(String json) {
        Map<String, Object> outer = (Map<String, Object>) ProviderJson.parse(json);
        Map<String, Object> rpcResult = (Map<String, Object>) outer.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) rpcResult.get("content");
        return (Map<String, Object>) ProviderJson.parse((String) content.getFirst().get("text"));
    }

    private static String nestedField(String json) {
        Object value = innerResult(json).get("result");
        if (!(value instanceof Map<?, ?> result)) {
            return null;
        }
        value = result.get("request");
        return value instanceof Map<?, ?> map && map.get("requestId") instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return (Map<String, Object>) value;
    }

    private static void git(Path root, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
    }

    private static String gitOutput(Path root) throws Exception {
        String[] command = new String[5];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        command[3] = "rev-parse";
        command[4] = "HEAD";
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }

    private static void appendReviewableSnapshot(Path project, UUIDs ids, String ownerParticipant) throws Exception {
        var location = new ProjectApplicationService().locate(project);
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        String commit = gitOutput(project);
        UUID taskId = store.collaborationProjection().intent(ids.intentId()).map(WorkIntent::taskId).orElseGet(() -> UUID.nameUUIDFromBytes("syn039-review-task".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        SnapshotProvenance provenance = new SnapshotProvenance(ids.groupId(),
                ids.intentId(),
                ownerParticipant,
                "owner-session",
                1,
                List.of(),
                List.of(),
                List.of("PATH_EXACT:todo.py"),
                "refs/synesis/snapshots/snap_reviewable",
                "test-integrity");
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(taskId,
                "snap_reviewable",
                identity.nodeId(),
                "supervisor",
                "worker",
                "owner-session",
                commit,
                commit,
                List.of("todo.py"),
                List.of(),
                "reviewable Todo",
                provenance,
                false);
        git(project, "update-ref", "refs/synesis/snapshots/snap_reviewable", commit);
        store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);
    }

    @Test
    void malformedReviewerTargetMetadataIsRejected(@TempDir Path temp) {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), temp, "codex", "syn039-invalid-review-target");

        String response = handler.handleMessage(toolCall("ensure_session", "{\"task\":{\"role\":\"reviewer\",\"reviewTargets\":[" + "{\"kind\":\"unknown\",\"path\":\"todo.py\"}]}}"));

        Map<String, Object> projection = innerResult(response);
        assertEquals("blocked", projection.get("status"), response);
        assertEquals("policy_denied", projection.get("reason"), response);
        assertEquals("INVALID_TASK_INTENT", ((Map<?, ?>) projection.get("result")).get("reason"), response);
    }

    @Test
    void passingTodoEvidenceIsNotConvertedToTestsFailed(@TempDir Path temp) {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), temp, "codex", "syn039-integration");
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"get_next_action\",\"arguments\":{" + "\"integrationCheck\":{"
                + "\"snapshotId\":\"snap_6162f6fd4ff4d51aadb5484609270ab3\"," + "\"baseCommit\":\"7a5925f20a4cd6b0400bfdb857a74affb27b708e\"," + "\"changedPaths\":[\"todo.py\",\"test_todo.py\"],"
                + "\"testCommand\":\"python -m pytest -q\"," + "\"testResult\":\"... [100%]\\r\\n3 passed in 0.01s\\r\\n\"}}}}";

        String response = handler.handleMessage(request);

        assertTrue(response.contains("\\\"status\\\":\\\"completed\\\""), response);
        assertTrue(response.contains("\\\"accepted\\\":true"), response);
        assertFalse(response.contains("TESTS_FAILED"), response);
    }

    @Test
    void passingTestListEvidenceMatchesTheRecordedTodoRetry(@TempDir Path temp) throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), Files.createDirectories(temp.resolve("project")), "codex", "syn039-list");
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"get_next_action\",\"arguments\":{" + "\"integrationCheck\":{\"controlHead\":\"base\",\"base\":\"base\","
                + "\"paths\":[\"todo.py\"],\"claims\":[\"todo.py\"]," + "\"tests\":[\"python -m pytest -q (3 passed)\",\"git diff --check\"]}}}}";

        String response = handler.handleMessage(request);

        assertTrue(response.contains("\\\"accepted\\\":true"), response);
        assertFalse(response.contains("TESTS_FAILED"), response);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingGrantRequestBecomesOwnerAuthorizedReviewAdmission(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("review-project");
        Files.createDirectories(project);
        git(project, "init");
        git(project, "config", "user.name", "SYN-039 Test");
        git(project, "config", "user.email", "syn039@example.test");
        Files.writeString(project.resolve("todo.py"), "def add_todo(items, item):\n    return [*items, item]\n");
        git(project, "add", ".");
        git(project, "commit", "-m", "baseline");

        ProjectApplicationService projectService = new ProjectApplicationService();
        var initialized = projectService.init(project).location();
        McpProviderTestSupport.install(initialized, "codex");
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new SessionResolutionRequest(project, "codex", "syn039-owner", null, false));
        sessions.ensureSession(new SessionResolutionRequest(project, "codex", "syn039-reviewer", null, false));
        var location = new ProjectApplicationService().locate(project);
        var bindings = new ProviderSessionBindingService();
        for (var binding : bindings.list(location, "codex")) {
            if (binding.worktreePath() != null) {
                bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
            }
        }
        Path ownerWorktree = Path.of(bindings.find(location, "codex", "syn039-owner").orElseThrow().worktreePath());
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claim = collaboration.announce(project, "codex", "syn039-owner", "Implement Todo", "Review the completed Todo snapshot", List.of(ResourceSelector.pathExact("todo.py")));
        collaboration.announce(project,
                "codex",
                "syn039-reviewer",
                "Review Todo",
                "Review the completed Todo snapshot",
                List.of(ResourceSelector.pathExact("test_todo.py")),
                claim.intent().workGroupId(),
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("todo.py")));
        UUIDs ids = new UUIDs(claim.intent().workGroupId(), claim.intent().intentId());
        McpProtocolHandler owner = new McpProtocolHandler(sessions, project, "codex", "syn039-owner");
        McpProtocolHandler reviewer = new McpProtocolHandler(sessions, project, "codex", "syn039-reviewer");

        String catalog = reviewer.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        assertTrue(catalog.contains("\"review_validation\""), catalog);
        assertTrue(catalog.contains("\"grantId\""), catalog);
        assertTrue(catalog.contains("\"snapshotId\""), catalog);

        String admissionNext = reviewer.handleMessage(toolCall("get_next_action", "{}"));
        assertTrue(admissionNext.contains("work_group_join"), admissionNext);
        assertTrue(admissionNext.contains(ids.groupId.toString()), admissionNext);
        Map<String, Object> admissionEnvelope = innerResult(admissionNext);
        Map<String, Object> admissionResult = (Map<String, Object>) admissionEnvelope.get("result");
        Map<String, Object> admissionWorkflow = (Map<String, Object>) admissionResult.get("workflow");
        assertEquals("request_coordination", admissionWorkflow.get("recommendedTool"));
        Map<String, Object> admissionArguments = (Map<String, Object>) admissionWorkflow.get("arguments");
        assertEquals("work_group_join", admissionArguments.get("kind"));
        Map<String, Object> admissionPayload = (Map<String, Object>) admissionArguments.get("payload");
        assertEquals(ids.groupId.toString(), admissionPayload.get("workGroupId"));
        assertEquals(ids.intentId.toString(), admissionPayload.get("intentId"));

        String joinRequest = reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{" + "\"workGroupId\":\"" + ids.groupId + "\"," + "\"intentId\":\"" + ids.intentId + "\"," + "\"proposal\":\"Review the published Todo snapshot\"}}"));
        assertFalse(joinRequest.contains("COORDINATION_FIELD_REQUIRED:grantId"), joinRequest);
        String requestId = nestedField(joinRequest);
        assertNotNull(requestId, joinRequest);

        Map<String, Object> ownerNext = innerResult(owner.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("owner_request_pending", ownerNext.get("reason"), ownerNext.toString());
        assertEquals("respond_coordination", ownerNext.get("nextAction"), ownerNext.toString());
        Map<String, Object> ownerResult = (Map<String, Object>) ownerNext.get("result");
        List<Map<String, Object>> pending = (List<Map<String, Object>>) ownerResult.get("pendingCoordination");
        assertEquals(1, pending.size());
        Map<String, Object> pendingReview = pending.getFirst();
        assertEquals(requestId, pendingReview.get("requestId"));
        assertEquals("REVIEW", pendingReview.get("kind"));
        assertEquals(ids.intentId.toString(), pendingReview.get("intentId"));
        assertEquals(ids.groupId.toString(), pendingReview.get("workGroupId"));
        assertEquals(1L, ((Number) pendingReview.get("claimEpoch")).longValue());
        Map<String, Object> workflow = (Map<String, Object>) ownerResult.get("workflow");
        assertEquals("respond_coordination", workflow.get("recommendedTool"));
        Map<String, Object> responseArguments = (Map<String, Object>) workflow.get("arguments");
        assertEquals("coordination_response", responseArguments.get("kind"));
        Map<String, Object> responsePayload = (Map<String, Object>) responseArguments.get("payload");
        assertEquals(requestId, responsePayload.get("coordinationRequest"));
        assertEquals("ACCEPTED", responsePayload.get("coordinationStatus"));
        assertEquals("admitted", responsePayload.get("proposal"));

        String wrongRequest = owner.handleMessage(toolCall("respond_coordination",
                "{\"kind\":\"coordination_response\",\"payload\":{" + "\"coordinationRequest\":\"00000000-0000-0000-0000-000000000000\"," + "\"coordinationStatus\":\"ACCEPTED\"}}"));
        assertTrue(wrongRequest.contains("REQUEST_NOT_FOUND"), wrongRequest);

        String accepted = owner.handleMessage(toolCall("respond_coordination", ProviderJson.write(responseArguments)));
        assertTrue(accepted.contains("completed"), accepted);

        // A pending reciprocal grant must not fence an empty implementation
        // lane.  Create the claimed source change before asserting the
        // validation wait projection.
        Files.writeString(ownerWorktree.resolve("todo.py"), "def add_todo(items, item):\n    return [*items, item]\n\n" + "def complete_todo(items, item):\n" + "    return [value for value in items if value != item]\n");
        Map<String, Object> ownerGrantPending = innerResult(owner.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("validation_required", ownerGrantPending.get("reason"), ownerGrantPending.toString());
        assertEquals("wait", ownerGrantPending.get("nextAction"), ownerGrantPending.toString());
        Map<String, Object> ownerGrantPendingResult = (Map<String, Object>) ownerGrantPending.get("result");
        assertEquals(Boolean.TRUE, ownerGrantPendingResult.get("reviewGrantPending"));
        Map<String, Object> pendingGrant = (Map<String, Object>) ownerGrantPendingResult.get("reviewGrant");
        assertEquals(ids.groupId.toString(), pendingGrant.get("workGroupId"));
        assertEquals(ids.intentId.toString(), pendingGrant.get("targetIntentId"));
        assertEquals(1L, ((Number) pendingGrant.get("claimEpoch")).longValue());
        assertEquals(Boolean.TRUE, pendingGrant.get("singleUse"));
        assertEquals("wait", ownerGrantPendingResult.get("nextProtocolAction"));
        assertEquals("review_grant_consumption", ownerGrantPendingResult.get("nextProtocolKind"));
        Map<String, Object> pendingPayload = (Map<String, Object>) ownerGrantPendingResult.get("nextProtocolPayload");
        assertEquals(pendingGrant.get("grantId"), pendingPayload.get("grantId"));
        assertEquals(ids.groupId.toString(), pendingPayload.get("workGroupId"));
        assertEquals(ids.intentId.toString(), pendingPayload.get("intentId"));
        assertEquals(1L, ((Number) pendingPayload.get("claimEpoch")).longValue());
        assertEquals(Boolean.TRUE, pendingPayload.get("snapshotRequired"));
        Map<String, Object> ownerGrantPendingWorkflow = (Map<String, Object>) ownerGrantPendingResult.get("workflow");
        assertEquals("WAIT", ownerGrantPendingWorkflow.get("type"));
        assertEquals("get_next_action", ownerGrantPendingWorkflow.get("recommendedTool"));
        assertEquals(Map.of(), ownerGrantPendingWorkflow.get("arguments"));

        String status = reviewer.handleMessage(toolCall("request_coordination", "{\"kind\":\"collaboration_status\",\"payload\":{}}"));
        Map<String, Object> statusEnvelope = innerResult(status);
        Map<String, Object> statusResult = (Map<String, Object>) statusEnvelope.get("result");
        List<Map<String, Object>> grants = (List<Map<String, Object>>) statusResult.get("grants");
        assertEquals(1, grants.size());
        Map<String, Object> grant = grants.getFirst();

        String consumed = reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{" + "\"workGroupId\":\"" + grant.get("workGroupId") + "\"," + "\"grantId\":\"" + grant.get("grantId") + "\"," + "\"intentId\":\"" + grant.get("targetIntentId")
                        + "\"," + "\"claimEpoch\":" + grant.get("claimEpoch") + "," + "\"targetParticipant\":\"" + grant.get("targetParticipant") + "\"}}"));
        assertTrue(consumed.contains("\\\"status\\\":\\\"completed\\\""), consumed);

        Files.writeString(ownerWorktree.resolve("todo.py"), "def add_todo(items, item):\n    return [*items, item]\n\n\ndef complete_todo(items, item):\n    return [value for value in items if value != item]\n");
        String ownerPublication = owner.handleMessage(toolCall("get_next_action", "{\"completionRequested\":true}"));
        assertTrue(ownerPublication.contains("snapshot_publication_required"), ownerPublication);
        assertTrue(ownerPublication.contains("finish_lane"), ownerPublication);
        assertTrue(ownerPublication.contains(ids.groupId.toString()), ownerPublication);
        assertTrue(ownerPublication.contains("claimEpoch"), ownerPublication);

        // The owner publishes while its lane is open, and the lane is released afterwards.
        appendReviewableSnapshot(project, ids, claim.intent().participant());
        collaboration.release(project, "codex", "syn039-owner");
        String replayedAdmission = reviewer.handleMessage(toolCall("request_coordination", ProviderJson.write(admissionArguments)));
        assertFalse(replayedAdmission.contains("INTENT_NOT_FOUND"), replayedAdmission);
        assertEquals(requestId, nestedField(replayedAdmission));
        String next = reviewer.handleMessage(toolCall("get_next_action", "{}"));
        assertTrue(next.contains("review_validation"), next);
        assertTrue(next.contains("snap_reviewable"), next);

        String validationNext = reviewer.handleMessage(toolCall("get_next_action", "{}"));
        assertTrue(validationNext.contains("review_validation"), validationNext);
        Map<String, Object> validationEnvelope = innerResult(validationNext);
        Map<String, Object> validationResult = (Map<String, Object>) validationEnvelope.get("result");
        Map<String, Object> validationWorkflow = (Map<String, Object>) validationResult.get("workflow");
        assertFalse(validationWorkflow.containsKey("recommendedTool"), validationWorkflow.toString());
        assertFalse(validationWorkflow.containsKey("arguments"), validationWorkflow.toString());
        Map<String, Object> projectedValidationPayload = (Map<String, Object>) validationResult.get("nextProtocolPayload");
        assertEquals("review_decision", validationResult.get("nextProtocolAction"));
        assertEquals("review_validation", validationResult.get("nextProtocolKind"));
        assertEquals(grant.get("grantId"), projectedValidationPayload.get("grantId"));
        assertEquals("snap_reviewable", projectedValidationPayload.get("snapshotId"));
        assertEquals(grant.get("targetIntentId"), projectedValidationPayload.get("intentId"));
        assertEquals(grant.get("claimEpoch"), projectedValidationPayload.get("claimEpoch"));
        assertFalse(projectedValidationPayload.containsKey("result"));
        Map<String, Object> reviewDecision = (Map<String, Object>) validationResult.get("reviewDecision");
        assertEquals(Boolean.TRUE, reviewDecision.get("required"));
        assertEquals("result", reviewDecision.get("field"));
        assertEquals(List.of("accepted", "rejected"), reviewDecision.get("allowedResults"));
        assertEquals(Boolean.TRUE, reviewDecision.get("rejectionReasonRequired"));
        assertEquals(reviewDecision, validationWorkflow.get("decision"));
        Map<String, Object> reviewAccess = (Map<String, Object>) validationResult.get("reviewAccess");
        assertEquals("immutable_review_snapshot", reviewAccess.get("workspace"));
        assertEquals("snap_reviewable", reviewAccess.get("snapshotId"));
        assertEquals(List.of("read_file", "run_command"), reviewAccess.get("readTools"));
        assertEquals(Boolean.TRUE, reviewAccess.get("writeLaneProtected"));
        assertFalse(projectedValidationPayload.containsKey("workGroupId"));
        assertFalse(projectedValidationPayload.containsKey("targetParticipant"));
        String wrongSnapshot = reviewer.handleMessage(toolCall("respond_coordination",
                "{\"kind\":\"review_validation\",\"payload\":{" + "\"grantId\":\"" + grant.get("grantId") + "\"," + "\"snapshotId\":\"snap_wrong\"," + "\"intentId\":\"" + grant.get("targetIntentId") + "\","
                        + "\"claimEpoch\":" + grant.get("claimEpoch") + "," + "\"result\":\"accepted\"}}"));
        assertTrue(wrongSnapshot.contains("REVIEW_SNAPSHOT"), wrongSnapshot);
        Map<String, Object> acceptedValidationPayload = new LinkedHashMap<>(projectedValidationPayload);
        acceptedValidationPayload.put("result", "accepted");
        Map<String, Object> acceptedValidationArguments = new LinkedHashMap<>();
        acceptedValidationArguments.put("kind", "review_validation");
        acceptedValidationArguments.put("payload", acceptedValidationPayload);
        String validated = reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(acceptedValidationArguments)));
        assertTrue(validated.contains("ACCEPTED"), validated);
        String replayed = reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{" + "\"workGroupId\":\"" + grant.get("workGroupId") + "\"," + "\"grantId\":\"" + grant.get("grantId") + "\"," + "\"intentId\":\"" + grant.get("targetIntentId")
                        + "\"," + "\"claimEpoch\":" + grant.get("claimEpoch") + "," + "\"targetParticipant\":\"" + grant.get("targetParticipant") + "\"}}"));
        assertTrue(replayed.contains("LANE_GRANT_REPLAYED"), replayed);
        String completedStatus = reviewer.handleMessage(toolCall("request_coordination", "{\"kind\":\"collaboration_status\",\"payload\":{}}"));
        assertTrue(completedStatus.contains("COMPLETED"), completedStatus);
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectedReviewAdmissionSurvivesSeparateSessionServices(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("separate-session-review-project");
        Files.createDirectories(project);
        git(project, "init");
        git(project, "config", "user.name", "SYN-039 Test");
        git(project, "config", "user.email", "syn039@example.test");
        Files.writeString(project.resolve("todo.py"), "def add_todo(items, item):\n    return [*items, item]\n");
        git(project, "add", ".");
        git(project, "commit", "-m", "baseline");

        ProjectApplicationService projectService = new ProjectApplicationService();
        var initialized = projectService.init(project).location();
        McpProviderTestSupport.install(initialized, "codex");
        new ProviderManualService().install("codex");
        AgentSessionService ownerSessions = new AgentSessionService();
        AgentSessionService reviewerSessions = new AgentSessionService();
        ownerSessions.ensureSession(new SessionResolutionRequest(project, "codex", "separate-owner", null, false));
        reviewerSessions.ensureSession(new SessionResolutionRequest(project, "codex", "separate-reviewer", null, false));
        var location = new ProjectApplicationService().locate(project);
        var bindings = new ProviderSessionBindingService();
        for (var binding : bindings.list(location, "codex")) {
            if (binding.worktreePath() != null) {
                bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
            }
        }
        var claim = new WorkspaceCollaborationService().announce(project, "codex", "separate-owner", "Implement Todo", "Review the completed Todo snapshot", List.of(ResourceSelector.pathExact("todo.py")));
        new WorkspaceCollaborationService().announce(project,
                "codex",
                "separate-reviewer",
                "Review Todo",
                "Review the completed Todo snapshot",
                List.of(ResourceSelector.pathExact("test_todo.py")),
                claim.intent().workGroupId(),
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("todo.py")));
        McpProtocolHandler owner = new McpProtocolHandler(ownerSessions, project, "codex", "separate-owner");
        McpProtocolHandler reviewer = new McpProtocolHandler(reviewerSessions, project, "codex", "separate-reviewer");

        Map<String, Object> projection = innerResult(reviewer.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("request_coordination", projection.get("nextAction"), projection.toString());
        Map<String, Object> result = (Map<String, Object>) projection.get("result");
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        Map<String, Object> arguments = (Map<String, Object>) workflow.get("arguments");
        assertEquals("work_group_join", arguments.get("kind"), projection.toString());
        Map<String, Object> payload = (Map<String, Object>) arguments.get("payload");
        assertEquals(claim.intent().intentId().toString(), payload.get("intentId"), projection.toString());

        String request = reviewer.handleMessage(toolCall("request_coordination", ProviderJson.write(arguments)));
        assertFalse(request.contains("INTENT_NOT_FOUND"), request);
        assertNotNull(nestedField(request), request);
        assertTrue(owner.handleMessage(toolCall("get_next_action", "{}")).contains("respond_coordination"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyLaneDoesNotProjectUnexecutableFinishLaneAfterReviewGrantConsumption(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);

        String rejected = fixture.owner.handleMessage(toolCall("finish_lane", "{\"summary\":\"Publish the completed immutable snapshot\"}"));
        assertTrue(rejected.contains("\\\"task_not_ready\\\""), rejected);

        Map<String, Object> projection = innerResult(fixture.owner.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("ready", projection.get("status"), projection.toString());
        assertNotEquals("snapshot_publication_required", projection.get("reason"), projection.toString());
        Map<String, Object> result = (Map<String, Object>) projection.get("result");
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("IMPLEMENT", workflow.get("type"), projection.toString());
        assertFalse(workflow.containsKey("recommendedTool"), workflow.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectedFinishLanePublishesImmutableSnapshotVisibleToReviewerDespitePythonCache(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);

        Files.writeString(fixture.ownerWorktree.resolve("todo.py"), "def add_todo(items, item):\n    return [*items, item]\n\n\ndef remove_todo(items, item):\n    return [value for value in items if value != item]\n");
        Files.createDirectories(fixture.ownerWorktree.resolve("__pycache__"));
        Files.write(fixture.ownerWorktree.resolve("__pycache__/todo.cpython-313.pyc"), new byte[]{0x42, 0x43, 0x48});

        String ownerPublication = fixture.owner.handleMessage(toolCall("get_next_action", "{\"completionRequested\":true}"));
        Map<String, Object> projection = innerResult(ownerPublication);
        assertEquals("snapshot_publication_required", projection.get("reason"));
        assertEquals("finish_lane", projection.get("nextAction"));
        Map<String, Object> projectedResult = (Map<String, Object>) projection.get("result");
        assertEquals(fixture.groupId.toString(), projectedResult.get("workGroupId"));
        assertEquals(fixture.intentId.toString(), projectedResult.get("intentId"));
        assertEquals(1L, ((Number) projectedResult.get("claimEpoch")).longValue());
        Map<String, Object> workflow = (Map<String, Object>) projectedResult.get("workflow");
        assertEquals("finish_lane", workflow.get("recommendedTool"));
        Map<String, Object> arguments = (Map<String, Object>) workflow.get("arguments");
        assertEquals("Publish the completed immutable snapshot", arguments.get("summary"));
        assertEquals(fixture.intentId.toString(), arguments.get("intentId"));
        assertEquals(fixture.groupId.toString(), arguments.get("workGroupId"));
        assertEquals(1L, ((Number) arguments.get("claimEpoch")).longValue());
        assertEquals(1L, ((Number) arguments.get("workGroupVersion")).longValue());
        assertTrue(arguments.containsKey("expectedRevision"), arguments.toString());
        assertTrue(arguments.containsKey("participant"), arguments.toString());

        String published = fixture.owner.handleMessage(toolCall("finish_lane", ProviderJson.write(arguments)));
        Map<String, Object> publishedResult = (Map<String, Object>) innerResult(published).get("result");
        assertEquals("REVIEW_PENDING", publishedResult.get("snapshotState"), published);
        String snapshotId = String.valueOf(publishedResult.get("snapshotId"));
        assertTrue(snapshotId.startsWith("snap_"), published);

        String reviewerStatus = fixture.reviewer.handleMessage(toolCall("request_coordination", "{\"kind\":\"collaboration_status\",\"payload\":{}}"));
        assertTrue(reviewerStatus.contains(snapshotId), reviewerStatus);
    }

    @Test
    @SuppressWarnings("unchecked")
    void completedLaneProjectsReciprocalReviewAdmissionWhileSiblingRemainsActive(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);

        Files.writeString(fixture.ownerWorktree.resolve("todo.py"),
                "def add_todo(items, item):\n    return [*items, item]\n\n" + "\ndef remove_todo(items, item):\n" + "    return [value for value in items if value != item]\n");

        Map<String, Object> projection = innerResult(fixture.owner.handleMessage(toolCall("get_next_action", "{\"completionRequested\":true}")));
        assertEquals("snapshot_publication_required", projection.get("reason"), projection.toString());
        assertEquals("finish_lane", projection.get("nextAction"), projection.toString());
        Map<String, Object> projectedResult = (Map<String, Object>) projection.get("result");
        Map<String, Object> workflow = (Map<String, Object>) projectedResult.get("workflow");
        Map<String, Object> finishArguments = (Map<String, Object>) workflow.get("arguments");

        Map<String, Object> completion = innerResult(fixture.owner.handleMessage(toolCall("finish_lane", ProviderJson.write(finishArguments))));
        assertEquals("waiting", completion.get("status"), completion.toString());
        assertEquals("wait", completion.get("nextAction"), completion.toString());
        Map<String, Object> result = (Map<String, Object>) completion.get("result");
        assertEquals("REVIEW_PENDING", result.get("snapshotState"), completion.toString());
        assertEquals("review_pending", result.get("integrationState"), completion.toString());
        assertEquals(Boolean.TRUE, result.get("reviewRequired"), completion.toString());
    }

    @Test
    void claimedDirtyProducerRecoversThroughPublicEnsureAndResumesCommands(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        ProducerLane lane = independentProducer(fixture.project, "dirty-public-recovery", fixture.groupId, "owned-notes.txt");
        Map<String, Object> created = innerResult(lane.handler.handleMessage(toolCall("apply_patch", ProviderJson.write(Map.of("path", "owned-notes.txt", "create", true, "content", "unfinished producer work\n")))));
        assertEquals("completed", created.get("status"), created.toString());
        var location = new ProjectApplicationService().locate(fixture.project);
        var bindings = new ProviderSessionBindingService();
        var before = bindings.find(location, "codex", "dirty-public-recovery").orElseThrow();
        Files.writeString(fixture.project.resolve("peer.txt"), "integrated peer\n");
        git(fixture.project, "add", "peer.txt");
        git(fixture.project, "commit", "-m", "integrate peer");
        Map<String, Object> ensured = innerResult(lane.handler.handleMessage(toolCall("ensure_session", "{}")));
        assertEquals("ready", ensured.get("status"), ensured.toString());
        var after = bindings.find(location, "codex", "dirty-public-recovery").orElseThrow();
        assertEquals(before.sessionId(), after.sessionId());
        assertNotEquals(before.worktreePath(), after.worktreePath());
        assertEquals("unfinished producer work\n", Files.readString(Path.of(after.worktreePath()).resolve("owned-notes.txt")));
        assertEquals("unfinished producer work\n", Files.readString(Path.of(before.worktreePath()).resolve("owned-notes.txt")));
        Map<String, Object> resumed = innerResult(lane.handler.handleMessage(toolCall("run_command", "{\"argv\":[\"git\",\"status\",\"--porcelain\"]}")));
        assertEquals("completed", resumed.get("status"), resumed.toString());
        Map<String, Object> repeated = innerResult(lane.handler.handleMessage(toolCall("ensure_session", "{}")));
        assertEquals("ready", repeated.get("status"), repeated.toString());
        assertEquals(after.worktreePath(), bindings.find(location, "codex", "dirty-public-recovery").orElseThrow().worktreePath());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reviewerRecoveryPreservesConsumedGrantAfterControlCheckoutAdvances(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        var location = new ProjectApplicationService().locate(fixture.project);
        var bindings = new ProviderSessionBindingService();
        var ownerBinding = bindings.find(location, "codex", "syn039-owner-publication").orElseThrow();
        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId), WorkspaceCollaborationService.participantHandle(ownerBinding.sessionId()));
        var before = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();

        Files.writeString(fixture.project.resolve("README.md"), "integrated control state\n");
        git(fixture.project, "add", "README.md");
        git(fixture.project, "commit", "-m", "integrated snapshot");

        Map<String, Object> stale = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("validation_required", stale.get("reason"), stale.toString());
        assertEquals("review_decision", stale.get("nextAction"), stale.toString());

        Map<String, Object> ensured = innerResult(fixture.reviewer.handleMessage(toolCall("ensure_session", "{}")));
        assertEquals("ready", ensured.get("status"), ensured.toString());
        var after = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        assertEquals(before.sessionId(), after.sessionId());
        assertNotEquals(before.worktreePath(), after.worktreePath(), after.toString());

        Map<String, Object> repeatedEnsure = innerResult(fixture.reviewer.handleMessage(toolCall("ensure_session", "{}")));
        assertEquals("ready", repeatedEnsure.get("status"), repeatedEnsure.toString());
        var repeatedBinding = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        assertEquals(after.sessionId(), repeatedBinding.sessionId());
        assertEquals(after.worktreePath(), repeatedBinding.worktreePath());

        Map<String, Object> validation = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("validation_required", validation.get("reason"), validation.toString());
        assertEquals("review_decision", validation.get("nextAction"), validation.toString());
        Map<String, Object> validationResult = (Map<String, Object>) validation.get("result");
        assertEquals("review_validation", validationResult.get("nextProtocolKind"), validation.toString());
        assertTrue(validationResult.containsKey("nextProtocolPayload"), validation.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoveredSessionRearmsCommandScopeForItsNewVerifiedWorktree(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);

        String anchoredCommand = fixture.reviewer.handleMessage(toolCall("run_command", "{\"argv\":[\"git\",\"status\",\"--porcelain\"]}"));
        assertEquals("completed", innerResult(anchoredCommand).get("status"), anchoredCommand);

        var location = new ProjectApplicationService().locate(fixture.project);
        var bindings = new ProviderSessionBindingService();
        var ownerBinding = bindings.find(location, "codex", "syn039-owner-publication").orElseThrow();
        var reviewerBefore = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId), WorkspaceCollaborationService.participantHandle(ownerBinding.sessionId()));

        Files.writeString(fixture.project.resolve("README.md"), "integrated control state\n");
        git(fixture.project, "add", "README.md");
        git(fixture.project, "commit", "-m", "integrated snapshot");

        Map<String, Object> stale = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("validation_required", stale.get("reason"), stale.toString());
        assertEquals("review_decision", stale.get("nextAction"), stale.toString());
        Map<String, Object> ensured = innerResult(fixture.reviewer.handleMessage(toolCall("ensure_session", "{}")));
        assertEquals("ready", ensured.get("status"), ensured.toString());

        var reviewerAfter = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        assertEquals(reviewerBefore.sessionId(), reviewerAfter.sessionId());
        assertNotEquals(reviewerBefore.worktreePath(), reviewerAfter.worktreePath(), reviewerAfter.toString());

        Map<String, Object> validation = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> validationResult = (Map<String, Object>) validation.get("result");
        Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) validationResult.get("nextProtocolPayload"));
        payload.put("result", "accepted");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("kind", validationResult.get("nextProtocolKind"));
        arguments.put("payload", payload);
        String accepted = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(arguments)));
        assertTrue(accepted.contains("ACCEPTED"), accepted);

        String resumedCommand = fixture.reviewer.handleMessage(toolCall("run_command", "{\"argv\":[\"git\",\"status\",\"--porcelain\"]}"));
        Map<String, Object> resumedResult = innerResult(resumedCommand);
        assertEquals("completed", resumedResult.get("status"), resumedCommand);
        assertFalse(resumedCommand.contains("MCP_PROCESS_SCOPE_CHANGED"), resumedCommand);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dirtyReviewerReceivesDurableReviewDecisionAfterControlCheckoutAdvances(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        var location = new ProjectApplicationService().locate(fixture.project);
        var bindings = new ProviderSessionBindingService();
        var ownerBinding = bindings.find(location, "codex", "syn039-owner-publication").orElseThrow();
        var reviewerBinding = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        Path reviewerWorktree = Path.of(reviewerBinding.worktreePath());
        Path legitimateReviewerChange = reviewerWorktree.resolve("review-report.md");
        Files.writeString(legitimateReviewerChange, "review before integration\n");

        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId), WorkspaceCollaborationService.participantHandle(ownerBinding.sessionId()));
        Files.writeString(fixture.project.resolve("README.md"), "integrated control state\n");
        git(fixture.project, "add", "README.md");
        git(fixture.project, "commit", "-m", "integrated snapshot");

        Map<String, Object> validation = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("ready", validation.get("status"), validation.toString());
        assertEquals("validation_required", validation.get("reason"), validation.toString());
        assertEquals("review_decision", validation.get("nextAction"), validation.toString());
        Map<String, Object> validationResult = (Map<String, Object>) validation.get("result");
        assertEquals(Boolean.TRUE, validationResult.get("reviewOnly"), validation.toString());
        assertEquals("review_validation", validationResult.get("nextProtocolKind"), validation.toString());
        Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) validationResult.get("nextProtocolPayload"));
        payload.put("result", "accepted");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("kind", validationResult.get("nextProtocolKind"));
        arguments.put("payload", payload);

        String accepted = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(arguments)));
        assertTrue(accepted.contains("ACCEPTED"), accepted);
        assertTrue(Files.exists(legitimateReviewerChange), "reviewer work must not be discarded");
        var after = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        assertEquals(reviewerBinding.worktreePath(), after.worktreePath(), "review-only continuation must not replace a dirty worktree");
    }

    @Test
    void dirtyReviewerReadsImmutableSnapshotAndPreservesChangesAfterControlAdvances(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        var location = new ProjectApplicationService().locate(fixture.project);
        var bindings = new ProviderSessionBindingService();
        var ownerBinding = bindings.find(location, "codex", "syn039-owner-publication").orElseThrow();
        var reviewerBinding = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        Path reviewerWorktree = Path.of(reviewerBinding.worktreePath());
        Path reviewerWork = reviewerWorktree.resolve("review-report.md");
        Files.writeString(reviewerWork, "legitimate review notes\n");

        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId), WorkspaceCollaborationService.participantHandle(ownerBinding.sessionId()));
        Files.writeString(fixture.project.resolve("README.md"), "integrated control state\n");
        git(fixture.project, "add", "README.md");
        git(fixture.project, "commit", "-m", "integrated snapshot");

        String read = fixture.reviewer.handleMessage(toolCall("read_file", "{\"path\":\"todo.py\"}"));
        Map<String, Object> readEnvelope = innerResult(read);
        assertEquals("completed", readEnvelope.get("status"), read);
        Map<String, Object> readResult = mapValue(readEnvelope.get("result"));
        assertEquals("immutable_review_snapshot", readResult.get("workspace"), read);
        assertEquals("snap_reviewable", readResult.get("reviewSnapshotId"), read);
        assertTrue(String.valueOf(readResult.get("content")).contains("add_todo"), read);

        String command = fixture.reviewer.handleMessage(toolCall("run_command", "{\"argv\":[\"git\",\"rev-parse\",\"HEAD\"]}"));
        Map<String, Object> commandEnvelope = innerResult(command);
        assertEquals("completed", commandEnvelope.get("status"), command);
        Map<String, Object> commandResult = mapValue(commandEnvelope.get("result"));
        assertEquals("completed", commandResult.get("outcome"), command);
        assertEquals("immutable_review_snapshot", commandResult.get("workspace"), command);
        assertEquals("snap_reviewable", commandResult.get("reviewSnapshotId"), command);

        String recovery = fixture.reviewer.handleMessage(toolCall("ensure_session", "{}"));
        Map<String, Object> resumed = innerResult(recovery);
        assertEquals("ready", resumed.get("status"), recovery);
        assertEquals("isolated", mapValue(resumed.get("result")).get("workspace"), recovery);
        assertTrue(Files.exists(reviewerWork), "dirty reviewer work must remain intact");
        assertEquals("legitimate review notes\n", Files.readString(reviewerWork));
        var after = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        assertNotEquals(reviewerBinding.worktreePath(), after.worktreePath(), "clean control advance should bind the preserved changes to a fresh worktree");
        assertEquals("legitimate review notes\n", Files.readString(Path.of(after.worktreePath()).resolve("review-report.md")));

        ReviewSnapshotAccessService accessService = new ReviewSnapshotAccessService();
        ReviewSnapshotAccessService.AccessResult access = accessService.resolve(fixture.project, "codex", "syn039-reviewer-publication");
        assertTrue(access.available(), String.valueOf(access.error()));
        accessService.remove(fixture.project, access.access().grant().grantId());
    }

    @Test
    void reviewSnapshotAccessFailsClosedForWrongParticipantAndMismatchedRef(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        new AgentSessionService().ensureSession(new SessionResolutionRequest(fixture.project, "codex", "syn039-wrong-reviewer", null, false));
        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId), currentParticipant(fixture.project));

        ReviewSnapshotAccessService accessService = new ReviewSnapshotAccessService();
        ReviewSnapshotAccessService.AccessResult wrongParticipant = accessService.resolve(fixture.project, "codex", "syn039-wrong-reviewer");
        assertFalse(wrongParticipant.available());

        Files.writeString(fixture.project.resolve("README.md"), "different ref target\n");
        git(fixture.project, "add", "README.md");
        git(fixture.project, "commit", "-m", "advance ref target");
        String wrongCommit = gitOutput(fixture.project);
        git(fixture.project, "update-ref", "refs/synesis/snapshots/snap_reviewable", wrongCommit);

        ReviewSnapshotAccessService.AccessResult mismatchedRef = accessService.resolve(fixture.project, "codex", "syn039-reviewer-publication");
        assertTrue(mismatchedRef.denied(), String.valueOf(mismatchedRef.error()));
        assertEquals("REVIEW_SNAPSHOT_COMMIT_MISMATCH", mismatchedRef.error());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dirtyParticipantReceivesPendingReviewAcceptanceAfterControlCheckoutAdvances(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        ProducerLane reviewerClaim = independentProducer(fixture.project, "syn039-reviewer-artifact", fixture.groupId, "reviewer-notes.txt");
        Path reviewerWorktree = reviewerClaim.worktreePath();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        McpProtocolHandler independentReviewer = independentReviewer(fixture.project, "syn039-owner-reviewer", fixture.groupId, "reviewer-notes.txt");

        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId), currentParticipant(fixture.project));
        collaboration.release(fixture.project, "codex", "syn039-owner-publication");
        Map<String, Object> validation = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> validationResult = (Map<String, Object>) validation.get("result");
        Map<String, Object> validationPayload = new LinkedHashMap<>((Map<String, Object>) validationResult.get("nextProtocolPayload"));
        validationPayload.put("result", "accepted");
        Map<String, Object> validationArguments = new LinkedHashMap<>();
        validationArguments.put("kind", validationResult.get("nextProtocolKind"));
        validationArguments.put("payload", validationPayload);
        String accepted = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(validationArguments)));
        assertTrue(accepted.contains("ACCEPTED"), accepted);

        String reviewRequest = independentReviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{" + "\"workGroupId\":\"" + fixture.groupId + "\"," + "\"intentId\":\"" + reviewerClaim.intentId() + "\"," + "\"proposal\":\"Review the reviewer snapshot\"}}"));
        String requestId = nestedField(reviewRequest);
        assertNotNull(requestId, reviewRequest);

        Files.writeString(reviewerWorktree.resolve("reviewer-notes.txt"), "legitimate reviewer work\n");
        Files.writeString(fixture.project.resolve("README.md"), "integrated control state\n");
        git(fixture.project, "add", "README.md");
        git(fixture.project, "commit", "-m", "integrated snapshot");

        Map<String, Object> next = innerResult(reviewerClaim.handler().handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("owner_request_pending", next.get("reason"), next.toString());
        assertEquals("respond_coordination", next.get("nextAction"), next.toString());
        Map<String, Object> result = (Map<String, Object>) next.get("result");
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("respond_coordination", workflow.get("recommendedTool"), workflow.toString());
        Map<String, Object> arguments = (Map<String, Object>) workflow.get("arguments");
        Map<String, Object> payload = (Map<String, Object>) arguments.get("payload");
        assertEquals(requestId, payload.get("coordinationRequest"), next.toString());
        assertEquals("ACCEPTED", payload.get("coordinationStatus"), next.toString());
        assertTrue(Files.exists(reviewerWorktree.resolve("reviewer-notes.txt")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeImplementerCanContinueBeforeReciprocalReviewerConsumesGrant(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        ProducerLane reviewerClaim = independentProducer(fixture.project, "syn039-reviewer-artifact", fixture.groupId, "reviewer-notes.txt");
        McpProtocolHandler independentReviewer = independentReviewer(fixture.project, "syn039-owner-reviewer", fixture.groupId, "reviewer-notes.txt");

        publishOwnerCandidate(fixture);

        Map<String, Object> validation = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> validationResult = (Map<String, Object>) validation.get("result");
        Map<String, Object> validationPayload = new LinkedHashMap<>((Map<String, Object>) validationResult.get("nextProtocolPayload"));
        validationPayload.put("result", "accepted");
        Map<String, Object> validationArguments = new LinkedHashMap<>();
        validationArguments.put("kind", validationResult.get("nextProtocolKind"));
        validationArguments.put("payload", validationPayload);
        String accepted = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(validationArguments)));
        assertTrue(accepted.contains("ACCEPTED"), accepted);

        String reviewRequest = independentReviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{" + "\"workGroupId\":\"" + fixture.groupId + "\"," + "\"intentId\":\"" + reviewerClaim.intentId() + "\"," + "\"proposal\":\"Review the reviewer snapshot\"}}"));
        assertNotNull(nestedField(reviewRequest), reviewRequest);

        Map<String, Object> ownerRequest = innerResult(reviewerClaim.handler().handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> ownerRequestResult = (Map<String, Object>) ownerRequest.get("result");
        Map<String, Object> ownerRequestWorkflow = (Map<String, Object>) ownerRequestResult.get("workflow");
        Map<String, Object> responseArguments = (Map<String, Object>) ownerRequestWorkflow.get("arguments");
        assertEquals("respond_coordination", ownerRequestWorkflow.get("recommendedTool"), ownerRequestWorkflow.toString());
        String ownerAccepted = reviewerClaim.handler().handleMessage(toolCall("respond_coordination", ProviderJson.write(responseArguments)));
        assertTrue(ownerAccepted.contains("ACCEPTED"), ownerAccepted);

        Map<String, Object> continuation = innerResult(reviewerClaim.handler().handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("ready", continuation.get("status"), continuation.toString());
        assertFalse(continuation.containsKey("nextAction"), continuation.toString());
        assertFalse(continuation.containsKey("reason"), continuation.toString());
        Map<String, Object> continuationResult = (Map<String, Object>) continuation.get("result");
        Map<String, Object> currentIntent = (Map<String, Object>) continuationResult.get("currentIntent");
        assertEquals(reviewerClaim.intentId().toString(), currentIntent.get("intentId"), continuation.toString());
        assertFalse(continuationResult.containsKey("reviewGrantPending"), continuation.toString());
        Map<String, Object> workflow = (Map<String, Object>) continuationResult.get("workflow");
        assertEquals("IMPLEMENT", workflow.get("type"), workflow.toString());
        assertTrue(((List<String>) workflow.get("permittedOperations")).contains("apply_patch"), workflow.toString());
        assertFalse(workflow.containsKey("recommendedTool"), workflow.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptedReviewReportsActiveStatusWhileOwnerIntentRemainsLive(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId), currentParticipant(fixture.project));

        Map<String, Object> next = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> result = (Map<String, Object>) next.get("result");
        Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) result.get("nextProtocolPayload"));
        payload.put("result", "accepted");
        Map<String, Object> acceptedArguments = new LinkedHashMap<>();
        acceptedArguments.put("kind", result.get("nextProtocolKind"));
        acceptedArguments.put("payload", payload);

        String accepted = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(acceptedArguments)));
        Map<String, Object> acceptedResult = (Map<String, Object>) innerResult(accepted).get("result");
        assertEquals("ACCEPTED", acceptedResult.get("result"), accepted);
        assertEquals("ACTIVE", acceptedResult.get("workGroupStatus"), accepted);

        Map<String, Object> status = (Map<String, Object>) innerResult(fixture.reviewer.handleMessage(toolCall("request_coordination", "{\"kind\":\"collaboration_status\",\"payload\":{}}"))).get("result");
        List<Map<String, Object>> groups = (List<Map<String, Object>>) status.get("groups");
        assertEquals("ACTIVE", groups.stream().filter(group -> fixture.groupId.toString().equals(group.get("workGroupId"))).findFirst().orElseThrow().get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectedReviewRoutesActionableWorkToTheImplementer(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId), currentParticipant(fixture.project));

        Map<String, Object> next = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> result = (Map<String, Object>) next.get("result");
        Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) result.get("nextProtocolPayload"));
        payload.put("result", "rejected");
        payload.put("reason", "The implementation is missing the required completion behavior.");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("kind", result.get("nextProtocolKind"));
        arguments.put("payload", payload);

        String rejected = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(arguments)));
        Map<String, Object> rejectedResult = (Map<String, Object>) innerResult(rejected).get("result");
        assertEquals("REJECTED", rejectedResult.get("result"), rejected);
        Map<String, Object> route = (Map<String, Object>) rejectedResult.get("route");
        assertEquals("get_next_action", route.get("nextAction"), rejected);
        assertEquals(fixture.intentId.toString(), route.get("targetIntentId"), rejected);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reviewValidationRejectsWrongParticipantStaleEpochSnapshotInvalidResultAndConflictingReplay(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId), currentParticipant(fixture.project));
        Map<String, Object> next = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> result = (Map<String, Object>) next.get("result");
        Map<String, Object> basePayload = new LinkedHashMap<>((Map<String, Object>) result.get("nextProtocolPayload"));

        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new SessionResolutionRequest(fixture.project, "codex", "syn039-wrong-reviewer", null, false));
        McpProtocolHandler wrongReviewer = new McpProtocolHandler(sessions, fixture.project, "codex", "syn039-wrong-reviewer");
        String wrongParticipant = wrongReviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(reviewArguments(basePayload, "accepted", null))));
        assertTrue(wrongParticipant.contains("REVIEW_GRANT_TARGET_MISMATCH"), wrongParticipant);

        Map<String, Object> staleEpoch = new LinkedHashMap<>(basePayload);
        staleEpoch.put("claimEpoch", 2);
        String stale = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(reviewArguments(staleEpoch, "accepted", null))));
        assertTrue(stale.contains("REVIEW_GRANT_BINDING_MISMATCH"), stale);

        Map<String, Object> wrongSnapshot = new LinkedHashMap<>(basePayload);
        wrongSnapshot.put("snapshotId", "snap_wrong");
        String snapshotMismatch = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(reviewArguments(wrongSnapshot, "accepted", null))));
        assertTrue(snapshotMismatch.contains("REVIEW_SNAPSHOT"), snapshotMismatch);

        String invalid = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(reviewArguments(basePayload, "accepted|rejected", null))));
        assertTrue(invalid.contains("COORDINATION_RESPONSE_INVALID_RESULT"), invalid);

        String accepted = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(reviewArguments(basePayload, "accepted", null))));
        assertTrue(accepted.contains("ACCEPTED"), accepted);
        String replayed = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(reviewArguments(basePayload, "accepted", null))));
        assertTrue(replayed.contains("ACCEPTED"), replayed);

        String conflictingReplay = fixture.reviewer.handleMessage(toolCall("respond_coordination",
                ProviderJson.write(reviewArguments(basePayload, "rejected", "A conflicting replay must not replace the recorded decision."))));
        assertTrue(conflictingReplay.contains("REVIEW_DECISION_CONFLICT"), conflictingReplay);
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptedReviewDoesNotReprojectAdmissionForActiveReviewerLane(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        String snapshotId = publishOwnerCandidate(fixture);
        Map<String, Object> validation = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> validationResult = (Map<String, Object>) validation.get("result");
        Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) validationResult.get("nextProtocolPayload"));
        payload.put("result", "accepted");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("kind", "review_validation");
        arguments.put("payload", payload);

        String accepted = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(arguments)));
        assertTrue(accepted.contains("ACCEPTED"), accepted);

        assertEquals(snapshotId, payload.get("snapshotId"));
        var location = new ProjectApplicationService().locate(fixture.project);
        var replayedStore = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals("REVIEW_ACCEPTED", replayedStore.taskCompletionProjection().snapshotState(snapshotId).orElseThrow().name());
        var recordedDecision = replayedStore.workGroupProjection().reviewValidationForSnapshot(snapshotId).orElseThrow();
        assertEquals(String.valueOf(payload.get("grantId")), recordedDecision.grantId().toString());
        assertEquals("ACCEPTED", recordedDecision.result());

        Map<String, Object> afterAcceptance = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("ready", afterAcceptance.get("status"), afterAcceptance.toString());
        Map<String, Object> afterResult = (Map<String, Object>) afterAcceptance.get("result");
        assertTrue(afterResult.toString().contains("IMPLEMENT"), afterResult.toString());
        assertFalse(afterResult.toString().contains("REVIEW_ADMISSION_REQUIRED"), afterResult.toString());
        assertTrue(((List<?>) afterResult.get("reviewActions")).isEmpty(), afterResult.toString());
        List<Map<String, Object>> snapshots = (List<Map<String, Object>>) afterResult.get("snapshots");
        Map<String, Object> projectedSnapshot = snapshots.stream().filter(candidate -> snapshotId.equals(candidate.get("snapshotId"))).findFirst().orElseThrow();
        Map<String, Object> projectedDecision = (Map<String, Object>) projectedSnapshot.get("reviewDecision");
        assertEquals(snapshotId, projectedDecision.get("snapshotId"));
        assertEquals(String.valueOf(payload.get("grantId")), projectedDecision.get("grantId"));
        assertEquals("ACCEPTED", projectedDecision.get("result"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptedReviewSurvivesReviewerRecoveryWithoutDuplicateReview(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        String snapshotId = publishOwnerCandidate(fixture);

        Map<String, Object> review = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> reviewResult = (Map<String, Object>) review.get("result");
        Map<String, Object> reviewPayload = new LinkedHashMap<>((Map<String, Object>) reviewResult.get("nextProtocolPayload"));
        reviewPayload.put("result", "accepted");
        Map<String, Object> reviewArguments = new LinkedHashMap<>();
        reviewArguments.put("kind", "review_validation");
        reviewArguments.put("payload", reviewPayload);
        String accepted = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(reviewArguments)));
        assertTrue(accepted.contains("ACCEPTED"), accepted);

        assertEquals(snapshotId, reviewPayload.get("snapshotId"));
        UUID reviewGrantId = UUID.fromString(String.valueOf(reviewPayload.get("grantId")));
        var location = new ProjectApplicationService().locate(fixture.project);
        var bindings = new ProviderSessionBindingService();
        var reviewerBinding = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        Path reviewerWorktree = Path.of(reviewerBinding.worktreePath());
        Files.writeString(reviewerWorktree.resolve("review-report.md"), "The immutable candidate was accepted before reviewer recovery.\n");

        RecoverySnapshotService.Snapshot recovery = new RecoverySnapshotService().materialize(location, reviewerBinding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        String formerParticipant = WorkspaceCollaborationService.participantHandle(reviewerBinding.sessionId());
        var lifecycle = new org.synesis.coordination.application.WorkIntentService(store, identity);
        lifecycle.suspend(formerParticipant);
        lifecycle.holdRecovery(formerParticipant, recovery.root() + "#" + recovery.contentHash());

        var replayed = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals("REVIEW_ACCEPTED", replayed.taskCompletionProjection().snapshotState(snapshotId).orElseThrow().name());
        assertEquals("ACCEPTED", replayed.workGroupProjection().reviewValidationForSnapshot(snapshotId).orElseThrow().result());

        String successorConnection = "syn039-review-successor";
        AgentSessionService successorSessions = new AgentSessionService();
        successorSessions.ensureSession(new SessionResolutionRequest(fixture.project, "codex", successorConnection, null, false));
        var successorBinding = bindings.find(location, "codex", successorConnection).orElseThrow();
        bindings.verifyWorkspaceTrust(location, "codex", successorBinding.sessionId(), Path.of(successorBinding.worktreePath()));
        McpProtocolHandler successor = new McpProtocolHandler(successorSessions, fixture.project, "codex", successorConnection);

        Map<String, Object> discovery = innerResult(successor.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> discoveryResult = (Map<String, Object>) discovery.get("result");
        assertEquals("request_coordination", discovery.get("nextAction"), discovery.toString());
        assertEquals("continuation", discoveryResult.get("nextProtocolKind"));
        assertTrue(((List<?>) discoveryResult.get("reviewActions")).isEmpty(), discovery.toString());
        assertTrue(((List<?>) discoveryResult.get("reviewCandidates")).isEmpty(), discovery.toString());
        List<Map<String, Object>> snapshots = (List<Map<String, Object>>) discoveryResult.get("snapshots");
        Map<String, Object> projectedSnapshot = snapshots.stream().filter(candidate -> snapshotId.equals(candidate.get("snapshotId"))).findFirst().orElseThrow();
        assertEquals("INTEGRATED", projectedSnapshot.get("snapshotState"));
        Map<String, Object> decision = (Map<String, Object>) projectedSnapshot.get("reviewDecision");
        assertEquals(reviewGrantId.toString(), decision.get("grantId"));
        assertEquals(snapshotId, decision.get("snapshotId"));
        assertEquals("ACCEPTED", decision.get("result"));

        Map<String, Object> continuationArguments = new LinkedHashMap<>();
        continuationArguments.put("kind", discoveryResult.get("nextProtocolKind"));
        continuationArguments.put("payload", discoveryResult.get("nextProtocolPayload"));
        Map<String, Object> continued = innerResult(successor.handleMessage(toolCall("request_coordination", ProviderJson.write(continuationArguments))));
        assertEquals("completed", continued.get("status"), continued.toString());
        Map<String, Object> continuationResult = (Map<String, Object>) continued.get("result");
        assertEquals("CONTINUED", continuationResult.get("status"), continued.toString());

        var afterContinuation = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertFalse(afterContinuation.workGroupProjection().grantSuperseded(reviewGrantId));
        assertTrue(afterContinuation.workGroupProjection().reviewValidationForGrant(reviewGrantId).isPresent());
        assertEquals(1, afterContinuation.workGroupProjection().grants().stream().filter(grant -> reviewGrantId.equals(grant.grantId())).count());

        assertTrue(Files.exists(Path.of(successorBinding.worktreePath()).resolve("review-report.md")));
        Map<String, Object> publication = innerResult(successor.handleMessage(toolCall("get_next_action", "{\"completionRequested\":true}")));
        assertEquals("snapshot_publication_required", publication.get("reason"), publication.toString());
        Map<String, Object> publicationResult = (Map<String, Object>) publication.get("result");
        Map<String, Object> workflow = (Map<String, Object>) publicationResult.get("workflow");
        Map<String, Object> finishArguments = (Map<String, Object>) workflow.get("arguments");
        Map<String, Object> completed = innerResult(successor.handleMessage(toolCall("finish_lane", ProviderJson.write(finishArguments))));
        assertEquals("completed", completed.get("status"), completed.toString());
        assertEquals("COMPLETED", new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).workGroupProjection().group(fixture.groupId).orElseThrow().status().name());
        assertEquals("ACCEPTED", new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).workGroupProjection().reviewValidationForSnapshot(snapshotId).orElseThrow().result());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reviewerEnsureSessionRequiresAClaimBeforeAdmission(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        String connectionId = "syn039-claimless-reviewer";
        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new SessionResolutionRequest(fixture.project, "codex", connectionId, null, false));
        McpProtocolHandler reviewer = new McpProtocolHandler(sessions, fixture.project, "codex", connectionId);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("goal", "Review the Todo snapshot");
        task.put("acceptance", "Publish an independent review report");
        task.put("workGroupId", fixture.groupId.toString());
        task.put("role", "reviewer");
        task.put("reviewTargets", List.of(Map.of("kind", "path_exact", "path", "todo.py")));
        task.put("claims", List.of());

        Map<String, Object> blocked = innerResult(reviewer.handleMessage(toolCall("ensure_session", ProviderJson.write(Map.of("refresh", true, "task", task)))));
        assertEquals("blocked", blocked.get("status"), blocked.toString());
        assertEquals("REVIEW_CLAIM_REQUIRED", ((Map<String, Object>) blocked.get("result")).get("reason"), blocked.toString());

        var location = new ProjectApplicationService().locate(fixture.project);
        var binding = new ProviderSessionBindingService().find(location, "codex", connectionId).orElseThrow();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        assertTrue(store.collaborationProjection().activeIntents().stream().noneMatch(intent -> intent.participant().equals(participant)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reviewerOwnedArtifactCompletesThroughPublishIntegrationAndGroupClosure(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        var location = new ProjectApplicationService().locate(fixture.project);
        var bindings = new ProviderSessionBindingService();
        var reviewerBinding = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        Path reviewerWorktree = Path.of(reviewerBinding.worktreePath());
        UUID reviewerIntentId = fixture.reviewerIntentId;
        Files.writeString(fixture.ownerWorktree.resolve("todo.py"),
                "def add_todo(items, item):\n" + "    return [*items, item]\n\n" + "def remove_todo(items, item):\n" + "    return [value for value in items if value != item]\n");

        Map<String, Object> ownerProjection = innerResult(fixture.owner.handleMessage(toolCall("get_next_action", "{\"completionRequested\":true}")));
        assertEquals("snapshot_publication_required", ownerProjection.get("reason"), ownerProjection.toString());
        Map<String, Object> ownerWorkflow = (Map<String, Object>) ((Map<String, Object>) ownerProjection.get("result")).get("workflow");
        Map<String, Object> ownerFinish = (Map<String, Object>) ownerWorkflow.get("arguments");
        Map<String, Object> ownerPublished = innerResult(fixture.owner.handleMessage(toolCall("finish_lane", ProviderJson.write(ownerFinish))));
        assertEquals("waiting", ownerPublished.get("status"), ownerPublished.toString());
        assertEquals("REVIEW_PENDING", ((Map<String, Object>) ownerPublished.get("result")).get("snapshotState"), ownerPublished.toString());
        String ownerSnapshotId = String.valueOf(((Map<String, Object>) ownerPublished.get("result")).get("snapshotId"));

        Map<String, Object> reviewProjection = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("review_decision", reviewProjection.get("nextAction"), reviewProjection.toString());
        Map<String, Object> reviewResult = (Map<String, Object>) reviewProjection.get("result");
        Map<String, Object> reviewPayload = new LinkedHashMap<>((Map<String, Object>) reviewResult.get("nextProtocolPayload"));
        reviewPayload.put("result", "accepted");
        Map<String, Object> reviewArguments = new LinkedHashMap<>();
        reviewArguments.put("kind", "review_validation");
        reviewArguments.put("payload", reviewPayload);
        String accepted = fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(reviewArguments)));
        assertTrue(accepted.contains("ACCEPTED"), accepted);

        // Integration may converge immediately after the decision, before the reviewer finishes
        // the separately claimed report lane.  The later publication projection must still work.
        innerResult(fixture.owner.handleMessage(toolCall("get_next_action", "{}")));
        PredictionEventStore integratedBeforeReport = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals("INTEGRATED", integratedBeforeReport.taskCompletionProjection().snapshotState(ownerSnapshotId).orElseThrow().name());
        assertEquals("ACCEPTED", integratedBeforeReport.workGroupProjection().reviewValidationForSnapshot(ownerSnapshotId).orElseThrow().result());
        assertEquals("ACTIVE", integratedBeforeReport.workGroupProjection().group(fixture.groupId).orElseThrow().status().name());
        String reviewerIntentBaseCommit = integratedBeforeReport.collaborationProjection().intent(reviewerIntentId).orElseThrow().baseCommit();

        Map<String, Object> refreshedReviewer = innerResult(fixture.reviewer.handleMessage(toolCall("ensure_session", "{\"refresh\":true}")));
        assertEquals("ready", refreshedReviewer.get("status"), refreshedReviewer.toString());
        reviewerBinding = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        reviewerWorktree = Path.of(reviewerBinding.worktreePath());
        String reviewerAssignedBaseCommit = reviewerBinding.baseCommit();
        assertNotEquals(reviewerIntentBaseCommit, reviewerBinding.baseCommit(), "refresh should expose the stale intent/worktree baseline mismatch");

        Map<String, Object> unclaimedCreate = innerResult(fixture.reviewer.handleMessage(toolCall("apply_patch",
                ProviderJson.write(Map.of("path", "unclaimed-review-notes.md", "create", true, "content", "This path is outside the active reviewer claim.\n")))));
        assertNotEquals("completed", unclaimedCreate.get("status"), unclaimedCreate.toString());
        assertFalse(Files.exists(reviewerWorktree.resolve("unclaimed-review-notes.md")));

        Map<String, Object> reportCreate = innerResult(fixture.reviewer.handleMessage(toolCall("apply_patch",
                ProviderJson.write(Map.of("path", "review-report.md", "create", true, "content", "Independent review: accepted.\n")))));
        assertEquals("completed", reportCreate.get("status"), reportCreate.toString());
        assertTrue(Files.exists(reviewerWorktree.resolve("review-report.md")));
        PredictionEventStore reportPending = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals("ACTIVE", reportPending.workGroupProjection().group(fixture.groupId).orElseThrow().status().name());
        Map<String, Object> reviewerProjection = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{\"completionRequested\":true}")));
        assertEquals("snapshot_publication_required", reviewerProjection.get("reason"), reviewerProjection.toString());
        Map<String, Object> reviewerWorkflow = (Map<String, Object>) ((Map<String, Object>) reviewerProjection.get("result")).get("workflow");
        Map<String, Object> reviewerFinish = (Map<String, Object>) reviewerWorkflow.get("arguments");
        assertEquals(reviewerIntentId.toString(), reviewerFinish.get("intentId"));
        Map<String, Object> reviewerCompleted = innerResult(fixture.reviewer.handleMessage(toolCall("finish_lane", ProviderJson.write(reviewerFinish))));
        assertEquals("completed", reviewerCompleted.get("status"), reviewerCompleted.toString());
        assertEquals("integrated", ((Map<String, Object>) reviewerCompleted.get("result")).get("integrationState"), reviewerCompleted.toString());

        PredictionEventStore completedStore = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertTrue(completedStore.collaborationProjection().activeIntents().isEmpty(), completedStore.collaborationProjection().activeIntents().toString());
        assertEquals("COMPLETED", completedStore.workGroupProjection().group(fixture.groupId).orElseThrow().status().name());
        assertEquals(2, completedStore.taskCompletionProjection().allSnapshots().size());
        assertTrue(completedStore.taskCompletionProjection().allSnapshots().stream()
                .allMatch(snapshot -> "INTEGRATED".equals(completedStore.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElseThrow().name())));
        var reviewerSnapshot = completedStore.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.provenance().laneId().equals(reviewerIntentId)).findFirst().orElseThrow();
        assertEquals(List.of("review-report.md"), reviewerSnapshot.changedPaths());
        assertEquals(reviewerAssignedBaseCommit, reviewerSnapshot.baseCommit());
        assertTrue(Files.exists(fixture.project.resolve("review-report.md")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> acceptOwnerCandidate(ReviewFixture fixture) {
        Map<String, Object> reviewProjection = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> reviewPayload = new LinkedHashMap<>((Map<String, Object>) ((Map<String, Object>) reviewProjection.get("result")).get("nextProtocolPayload"));
        return innerResult(fixture.reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(reviewArguments(reviewPayload, "accepted", null)))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReviewerThatRecordedItsVerdictIsToldToWriteItsNotesToItsClaimedPathAndFinish(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp, ResourceSelector.pathSubtree("reviews"));
        String snapshotId = publishOwnerCandidate(fixture);
        assertEquals("completed", acceptOwnerCandidate(fixture).get("status"));
        String notes = "reviews/" + snapshotId + ".md";

        Map<String, Object> next = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> told = (Map<String, Object>) next.get("result");
        assertEquals(notes, told.get("reviewNotesPath"), "s1-run5, s5-run3, s5-run5: shown only IMPLEMENT, the reviewer had nothing it was asked to write: " + next);
        String instruction = String.valueOf(told.get("instruction"));
        assertTrue(instruction.contains("write them to " + notes) && instruction.contains("A one-line note is enough"), instruction);

        Map<String, Object> noChange = innerResult(fixture.reviewer.handleMessage(toolCall("finish_lane", "{\"outcome\":\"no_change\",\"summary\":\"Nothing to add\"}")));
        assertNotEquals("completed", noChange.get("status"), "a reviewer lane still ends by publishing its notes: " + noChange);
        assertEquals("finish_lane", noChange.get("nextAction"), noChange.toString());
        assertEquals(notes, ((Map<String, Object>) noChange.get("result")).get("reviewNotesPath"), noChange.toString());

        assertEquals("ready", innerResult(fixture.reviewer.handleMessage(toolCall("ensure_session", "{\"refresh\":true}"))).get("status"));
        Map<String, Object> nothingYet = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{\"completionRequested\":true}")));
        Map<String, Object> nothingYetResult = (Map<String, Object>) nothingYet.get("result");
        assertEquals(notes, nothingYetResult.get("reviewNotesPath"), nothingYet.toString());
        assertFalse(String.valueOf(nothingYetResult.get("instruction")).contains("cancel_lane"), "a reviewer is not offered to cancel the lane it must finish: " + nothingYet);

        Map<String, Object> written = innerResult(fixture.reviewer.handleMessage(toolCall("apply_patch",
                ProviderJson.write(Map.of("path", notes, "create", true, "content", "Nothing to add: the snapshot meets the acceptance.\n")))));
        assertEquals("completed", written.get("status"), written.toString());
        Map<String, Object> publication = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{\"completionRequested\":true}")));
        assertEquals("snapshot_publication_required", publication.get("reason"), publication.toString());
        Map<String, Object> finish = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) publication.get("result")).get("workflow")).get("arguments");
        Map<String, Object> finished = innerResult(fixture.reviewer.handleMessage(toolCall("finish_lane", ProviderJson.write(finish))));
        assertEquals("completed", finished.get("status"), finished.toString());
        var location = new ProjectApplicationService().locate(fixture.project);
        assertEquals("COMPLETED", new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).workGroupProjection().group(fixture.groupId).orElseThrow()
                .status().name(), "the work group closes");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReviewerThatClaimedAReportPathOfItsOwnIsToldToWriteItsNotesThere(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        publishOwnerCandidate(fixture);
        acceptOwnerCandidate(fixture);

        Map<String, Object> next = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));

        assertEquals("review-report.md", ((Map<String, Object>) next.get("result")).get("reviewNotesPath"), "never a path the lane may not write: " + next);
    }

    /**
     * Captures the durable IDs created by one collaboration fixture.
     */
    private record UUIDs(java.util.UUID groupId, java.util.UUID intentId) {

    }

    /**
     * Holds the owner and review-side handlers for snapshot admission tests.
     */
    private record ReviewFixture(Path project, Path ownerWorktree, McpProtocolHandler owner, McpProtocolHandler reviewer, UUID groupId, UUID intentId, UUID reviewerIntentId) {

    }

    private record ProducerLane(McpProtocolHandler handler, UUID intentId, Path worktreePath) {

    }
}
