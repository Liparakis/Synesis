package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/** Locks the SYN-039 reviewer and integration evidence boundaries. */
final class McpSyn039SliceTest {

    @Test
    void passingTodoEvidenceIsNotConvertedToTestsFailed(@TempDir Path temp) throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(
                new AgentSessionService(), temp, "codex", "syn039-integration");
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"get_next_action\",\"arguments\":{"
                + "\"integrationCheck\":{"
                + "\"snapshotId\":\"snap_6162f6fd4ff4d51aadb5484609270ab3\","
                + "\"baseCommit\":\"7a5925f20a4cd6b0400bfdb857a74affb27b708e\","
                + "\"changedPaths\":[\"todo.py\",\"test_todo.py\"],"
                + "\"testCommand\":\"python -m pytest -q\","
                + "\"testResult\":\"... [100%]\\r\\n3 passed in 0.01s\\r\\n\"}}}}";

        String response = handler.handleMessage(request);

        assertTrue(response.contains("\\\"status\\\":\\\"completed\\\""), response);
        assertTrue(response.contains("\\\"accepted\\\":true"), response);
        assertFalse(response.contains("TESTS_FAILED"), response);
    }

    @Test
    void passingTestListEvidenceMatchesTheRecordedTodoRetry(@TempDir Path temp) throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(
                new AgentSessionService(), Files.createDirectories(temp.resolve("project")), "codex", "syn039-list");
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"get_next_action\",\"arguments\":{"
                + "\"integrationCheck\":{\"controlHead\":\"base\",\"base\":\"base\","
                + "\"paths\":[\"todo.py\"],\"claims\":[\"todo.py\"],"
                + "\"tests\":[\"python -m pytest -q (3 passed)\",\"git diff --check\"]}}}}";

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

        new ProjectApplicationService().init(project);
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new AgentSessionService.SessionResolutionRequest(
                project, "codex", "syn039-owner", null, false));
        sessions.ensureSession(new AgentSessionService.SessionResolutionRequest(
                project, "codex", "syn039-reviewer", null, false));
        var location = new ProjectApplicationService().locate(project);
        var bindings = new ProviderSessionBindingService();
        for (var binding : bindings.list(location, "codex")) {
            if (binding.worktreePath() != null) {
                bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
            }
        }
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claim = collaboration.announce(project, "codex", "syn039-owner",
                "Implement Todo", "Review the completed Todo snapshot",
                List.of(ResourceSelector.pathExact("todo.py")));
        UUIDs ids = new UUIDs(claim.intent().workGroupId(), claim.intent().intentId());
        McpProtocolHandler owner = new McpProtocolHandler(sessions, project, "codex", "syn039-owner");
        McpProtocolHandler reviewer = new McpProtocolHandler(sessions, project, "codex", "syn039-reviewer");

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
                "{\"kind\":\"work_group_join\",\"payload\":{"
                        + "\"workGroupId\":\"" + ids.groupId + "\","
                        + "\"intentId\":\"" + ids.intentId + "\","
                        + "\"proposal\":\"Review the published Todo snapshot\"}}"));
        assertFalse(joinRequest.contains("COORDINATION_FIELD_REQUIRED:grantId"), joinRequest);
        String requestId = nestedField(joinRequest, "request", "requestId");
        assertTrue(requestId != null, joinRequest);

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
                "{\"kind\":\"coordination_response\",\"payload\":{"
                        + "\"coordinationRequest\":\"00000000-0000-0000-0000-000000000000\","
                        + "\"coordinationStatus\":\"ACCEPTED\"}}"));
        assertTrue(wrongRequest.contains("REQUEST_NOT_FOUND"), wrongRequest);

        String accepted = owner.handleMessage(toolCall("respond_coordination", ProviderJson.write(responseArguments)));
        assertTrue(accepted.contains("completed"), accepted);

        String status = reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"collaboration_status\",\"payload\":{}}"));
        Map<String, Object> statusEnvelope = innerResult(status);
        Map<String, Object> statusResult = (Map<String, Object>) statusEnvelope.get("result");
        List<Map<String, Object>> grants = (List<Map<String, Object>>) statusResult.get("grants");
        assertEquals(1, grants.size());
        Map<String, Object> grant = grants.getFirst();

        String consumed = reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{"
                        + "\"workGroupId\":\"" + grant.get("workGroupId") + "\","
                        + "\"grantId\":\"" + grant.get("grantId") + "\","
                        + "\"intentId\":\"" + grant.get("targetIntentId") + "\","
                        + "\"claimEpoch\":" + grant.get("claimEpoch") + ","
                        + "\"targetParticipant\":\"" + grant.get("targetParticipant") + "\"}}"));
        assertTrue(consumed.contains("\\\"status\\\":\\\"completed\\\""), consumed);

        String ownerPublication = owner.handleMessage(toolCall("get_next_action", "{}"));
        assertTrue(ownerPublication.contains("snapshot_publication_required"), ownerPublication);
        assertTrue(ownerPublication.contains("finish_lane"), ownerPublication);
        assertTrue(ownerPublication.contains(ids.groupId.toString()), ownerPublication);
        assertTrue(ownerPublication.contains("claimEpoch"), ownerPublication);

        collaboration.release(project, "codex", "syn039-owner");
        appendReviewableSnapshot(project, ids, claim.intent().participant());
        String next = reviewer.handleMessage(toolCall("get_next_action", "{}"));
        assertTrue(next.contains("review_validation"), next);
        assertTrue(next.contains("snap_reviewable"), next);

        String validationNext = reviewer.handleMessage(toolCall("get_next_action", "{}"));
        assertTrue(validationNext.contains("review_validation"), validationNext);
        String wrongSnapshot = reviewer.handleMessage(toolCall("respond_coordination",
                "{\"kind\":\"review_validation\",\"payload\":{"
                        + "\"grantId\":\"" + grant.get("grantId") + "\","
                        + "\"snapshotId\":\"snap_wrong\","
                        + "\"intentId\":\"" + grant.get("targetIntentId") + "\","
                        + "\"claimEpoch\":" + grant.get("claimEpoch") + ","
                        + "\"result\":\"accepted\"}}"));
        assertTrue(wrongSnapshot.contains("REVIEW_SNAPSHOT"), wrongSnapshot);
        String validated = reviewer.handleMessage(toolCall("respond_coordination",
                "{\"kind\":\"review_validation\",\"payload\":{"
                        + "\"grantId\":\"" + grant.get("grantId") + "\","
                        + "\"snapshotId\":\"snap_reviewable\","
                        + "\"intentId\":\"" + grant.get("targetIntentId") + "\","
                        + "\"claimEpoch\":" + grant.get("claimEpoch") + ","
                        + "\"result\":\"accepted\"}}"));
        assertTrue(validated.contains("ACCEPTED"), validated);
        String replayed = reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{"
                        + "\"workGroupId\":\"" + grant.get("workGroupId") + "\","
                        + "\"grantId\":\"" + grant.get("grantId") + "\","
                        + "\"intentId\":\"" + grant.get("targetIntentId") + "\","
                        + "\"claimEpoch\":" + grant.get("claimEpoch") + ","
                        + "\"targetParticipant\":\"" + grant.get("targetParticipant") + "\"}}"));
        assertTrue(replayed.contains("LANE_GRANT_REPLAYED"), replayed);
        String completedStatus = reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"collaboration_status\",\"payload\":{}}"));
        assertTrue(completedStatus.contains("COMPLETED"), completedStatus);
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectedFinishLanePublishesImmutableSnapshotVisibleToReviewer(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        String ownerPublication = fixture.owner.handleMessage(toolCall("get_next_action", "{}"));
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
        assertEquals(Map.of("summary", "Publish the completed immutable snapshot"), arguments);

        Files.writeString(fixture.ownerWorktree.resolve("todo.py"),
                "def add_todo(items, item):\n    return [*items, item]\n\n\ndef remove_todo(items, item):\n    return [value for value in items if value != item]\n");
        String published = fixture.owner.handleMessage(toolCall("finish_lane", ProviderJson.write(arguments)));
        Map<String, Object> publishedResult = (Map<String, Object>) innerResult(published).get("result");
        assertEquals("PUBLISHED", publishedResult.get("snapshotState"), published);
        String snapshotId = String.valueOf(publishedResult.get("snapshotId"));
        assertTrue(snapshotId.startsWith("snap_"), published);

        String reviewerStatus = fixture.reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"collaboration_status\",\"payload\":{}}"));
        assertTrue(reviewerStatus.contains(snapshotId), reviewerStatus);
    }

    @SuppressWarnings("unchecked")
    private static ReviewFixture prepareReviewFixture(Path temp) throws Exception {
        Path project = temp.resolve("review-publication-project");
        Files.createDirectories(project);
        git(project, "init");
        git(project, "config", "user.name", "SYN-039 Test");
        git(project, "config", "user.email", "syn039@example.test");
        Files.writeString(project.resolve("todo.py"), "def add_todo(items, item):\n    return [*items, item]\n");
        git(project, "add", ".");
        git(project, "commit", "-m", "baseline");

        new ProjectApplicationService().init(project);
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new AgentSessionService.SessionResolutionRequest(
                project, "codex", "syn039-owner-publication", null, false));
        sessions.ensureSession(new AgentSessionService.SessionResolutionRequest(
                project, "codex", "syn039-reviewer-publication", null, false));
        var location = new ProjectApplicationService().locate(project);
        var bindings = new ProviderSessionBindingService();
        for (var binding : bindings.list(location, "codex")) {
            if (binding.worktreePath() != null) {
                bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
            }
        }
        var ownerBinding = bindings.find(location, "codex", "syn039-owner-publication").orElseThrow();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claim = collaboration.announce(project, "codex", "syn039-owner-publication",
                "Implement Todo", "Review the completed Todo snapshot",
                List.of(ResourceSelector.pathExact("todo.py")));
        UUIDs ids = new UUIDs(claim.intent().workGroupId(), claim.intent().intentId());
        McpProtocolHandler owner = new McpProtocolHandler(sessions, project, "codex", "syn039-owner-publication");
        McpProtocolHandler reviewer = new McpProtocolHandler(sessions, project, "codex", "syn039-reviewer-publication");
        String join = reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{"
                        + "\"workGroupId\":\"" + ids.groupId + "\","
                        + "\"intentId\":\"" + ids.intentId + "\","
                        + "\"proposal\":\"Review the published Todo snapshot\"}}"));
        String requestId = nestedField(join, "request", "requestId");
        Map<String, Object> ownerNext = innerResult(owner.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> ownerResult = (Map<String, Object>) ownerNext.get("result");
        Map<String, Object> workflow = (Map<String, Object>) ownerResult.get("workflow");
        Map<String, Object> responseArguments = (Map<String, Object>) workflow.get("arguments");
        assertEquals(requestId, ((Map<String, Object>) responseArguments.get("payload")).get("coordinationRequest"));
        owner.handleMessage(toolCall("respond_coordination", ProviderJson.write(responseArguments)));
        Map<String, Object> status = (Map<String, Object>) innerResult(reviewer.handleMessage(toolCall(
                "request_coordination", "{\"kind\":\"collaboration_status\",\"payload\":{}}"))).get("result");
        Map<String, Object> grant = ((List<Map<String, Object>>) status.get("grants")).getFirst();
        reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{"
                        + "\"workGroupId\":\"" + grant.get("workGroupId") + "\","
                        + "\"grantId\":\"" + grant.get("grantId") + "\","
                        + "\"intentId\":\"" + grant.get("targetIntentId") + "\","
                        + "\"claimEpoch\":" + grant.get("claimEpoch") + ","
                        + "\"targetParticipant\":\"" + grant.get("targetParticipant") + "\"}}"));
        return new ReviewFixture(Path.of(ownerBinding.worktreePath()), owner, reviewer, ids.groupId, ids.intentId);
    }

    private static String toolCall(String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> innerResult(String json) {
        Map<String, Object> outer = (Map<String, Object>) ProviderJson.parse(json);
        Map<String, Object> rpcResult = (Map<String, Object>) outer.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) rpcResult.get("content");
        return (Map<String, Object>) ProviderJson.parse((String) content.getFirst().get("text"));
    }

    @SuppressWarnings("unchecked")
    private static String nestedField(String json, String parent, String field) {
        Object value = innerResult(json).get("result");
        if (!(value instanceof Map<?, ?> result)) return null;
        value = result.get(parent);
        return value instanceof Map<?, ?> map && map.get(field) instanceof String text ? text : null;
    }

    private static void git(Path root, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(output);
    }

    private static void appendReviewableSnapshot(Path project, UUIDs ids, String ownerParticipant) throws Exception {
        var location = new ProjectApplicationService().locate(project);
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        SnapshotProvenance provenance = new SnapshotProvenance(ids.groupId(), ids.intentId(),
                identity.nodeId(), ownerParticipant, 1, List.of(), List.of(), List.of("PATH_EXACT:todo.py"),
                "refs/synesis/snapshots/snap_reviewable", "test-integrity");
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(
                UUID.nameUUIDFromBytes("syn039-review-task".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "snap_reviewable", identity.nodeId(), "supervisor", "worker", "owner-session",
                "HEAD", "HEAD", List.of("todo.py"), List.of(), "reviewable Todo", provenance);
        store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED,
                identity.nodeId(), snapshot.encode(), identity);
    }

    private record UUIDs(java.util.UUID groupId, java.util.UUID intentId) { }

    private record ReviewFixture(Path ownerWorktree, McpProtocolHandler owner, McpProtocolHandler reviewer,
            UUID groupId, UUID intentId) { }
}
