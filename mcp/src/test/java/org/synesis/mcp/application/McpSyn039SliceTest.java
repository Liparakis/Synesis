package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        Path ownerWorktree = Path.of(bindings.find(location, "codex", "syn039-owner")
                .orElseThrow().worktreePath());
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claim = collaboration.announce(project, "codex", "syn039-owner",
                "Implement Todo", "Review the completed Todo snapshot",
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

        Map<String, Object> ownerGrantPending = innerResult(owner.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("validation_required", ownerGrantPending.get("reason"), ownerGrantPending.toString());
        assertEquals("wait", ownerGrantPending.get("nextAction"), ownerGrantPending.toString());
        Map<String, Object> ownerGrantPendingResult =
                (Map<String, Object>) ownerGrantPending.get("result");
        assertEquals(Boolean.TRUE, ownerGrantPendingResult.get("reviewGrantPending"));
        Map<String, Object> pendingGrant =
                (Map<String, Object>) ownerGrantPendingResult.get("reviewGrant");
        assertEquals(ids.groupId.toString(), pendingGrant.get("workGroupId"));
        assertEquals(ids.intentId.toString(), pendingGrant.get("targetIntentId"));
        assertEquals(1L, ((Number) pendingGrant.get("claimEpoch")).longValue());
        assertEquals(Boolean.TRUE, pendingGrant.get("singleUse"));
        assertEquals("wait", ownerGrantPendingResult.get("nextProtocolAction"));
        assertEquals("review_grant_consumption", ownerGrantPendingResult.get("nextProtocolKind"));
        Map<String, Object> pendingPayload =
                (Map<String, Object>) ownerGrantPendingResult.get("nextProtocolPayload");
        assertEquals(pendingGrant.get("grantId"), pendingPayload.get("grantId"));
        assertEquals(ids.groupId.toString(), pendingPayload.get("workGroupId"));
        assertEquals(ids.intentId.toString(), pendingPayload.get("intentId"));
        assertEquals(1L, ((Number) pendingPayload.get("claimEpoch")).longValue());
        assertEquals(Boolean.TRUE, pendingPayload.get("snapshotRequired"));
        Map<String, Object> ownerGrantPendingWorkflow =
                (Map<String, Object>) ownerGrantPendingResult.get("workflow");
        assertEquals("WAIT", ownerGrantPendingWorkflow.get("type"));
        assertEquals("get_next_action", ownerGrantPendingWorkflow.get("recommendedTool"));
        assertEquals(Map.of(), ownerGrantPendingWorkflow.get("arguments"));

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

        Files.writeString(ownerWorktree.resolve("todo.py"),
                "def add_todo(items, item):\n    return [*items, item]\n\n\ndef complete_todo(items, item):\n    return [value for value in items if value != item]\n");
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
        Map<String, Object> validationEnvelope = innerResult(validationNext);
        Map<String, Object> validationResult = (Map<String, Object>) validationEnvelope.get("result");
        Map<String, Object> validationWorkflow = (Map<String, Object>) validationResult.get("workflow");
        assertFalse(validationWorkflow.containsKey("recommendedTool"), validationWorkflow.toString());
        assertFalse(validationWorkflow.containsKey("arguments"), validationWorkflow.toString());
        Map<String, Object> projectedValidationPayload =
                (Map<String, Object>) validationResult.get("nextProtocolPayload");
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
        assertFalse(projectedValidationPayload.containsKey("workGroupId"));
        assertFalse(projectedValidationPayload.containsKey("targetParticipant"));
        String wrongSnapshot = reviewer.handleMessage(toolCall("respond_coordination",
                "{\"kind\":\"review_validation\",\"payload\":{"
                        + "\"grantId\":\"" + grant.get("grantId") + "\","
                        + "\"snapshotId\":\"snap_wrong\","
                        + "\"intentId\":\"" + grant.get("targetIntentId") + "\","
                        + "\"claimEpoch\":" + grant.get("claimEpoch") + ","
                        + "\"result\":\"accepted\"}}"));
        assertTrue(wrongSnapshot.contains("REVIEW_SNAPSHOT"), wrongSnapshot);
        Map<String, Object> acceptedValidationPayload = new LinkedHashMap<>(projectedValidationPayload);
        acceptedValidationPayload.put("result", "accepted");
        Map<String, Object> acceptedValidationArguments = new LinkedHashMap<>();
        acceptedValidationArguments.put("kind", "review_validation");
        acceptedValidationArguments.put("payload", acceptedValidationPayload);
        String validated = reviewer.handleMessage(toolCall("respond_coordination",
                ProviderJson.write(acceptedValidationArguments)));
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
    void emptyLaneDoesNotProjectUnexecutableFinishLaneAfterReviewGrantConsumption(
            @TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);

        String rejected = fixture.owner.handleMessage(toolCall("finish_lane",
                "{\"summary\":\"Publish the completed immutable snapshot\"}"));
        assertTrue(rejected.contains("\\\"task_not_ready\\\""), rejected);

        Map<String, Object> projection = innerResult(
                fixture.owner.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("ready", projection.get("status"), projection.toString());
        assertFalse("snapshot_publication_required".equals(projection.get("reason")), projection.toString());
        Map<String, Object> result = (Map<String, Object>) projection.get("result");
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        assertEquals("IMPLEMENT", workflow.get("type"), projection.toString());
        assertFalse(workflow.containsKey("recommendedTool"), workflow.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectedFinishLanePublishesImmutableSnapshotVisibleToReviewerDespitePythonCache(
            @TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);

        Files.writeString(fixture.ownerWorktree.resolve("todo.py"),
                "def add_todo(items, item):\n    return [*items, item]\n\n\ndef remove_todo(items, item):\n    return [value for value in items if value != item]\n");
        Files.createDirectories(fixture.ownerWorktree.resolve("__pycache__"));
        Files.write(fixture.ownerWorktree.resolve("__pycache__/todo.cpython-313.pyc"),
                new byte[] {0x42, 0x43, 0x48});

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

        String published = fixture.owner.handleMessage(toolCall("finish_lane", ProviderJson.write(arguments)));
        Map<String, Object> publishedResult = (Map<String, Object>) innerResult(published).get("result");
        assertEquals("PUBLISHED", publishedResult.get("snapshotState"), published);
        String snapshotId = String.valueOf(publishedResult.get("snapshotId"));
        assertTrue(snapshotId.startsWith("snap_"), published);

        String reviewerStatus = fixture.reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"collaboration_status\",\"payload\":{}}"));
        assertTrue(reviewerStatus.contains(snapshotId), reviewerStatus);
    }

    @Test
    @SuppressWarnings("unchecked")
    void completedLaneProjectsReciprocalReviewAdmissionWhileSiblingRemainsActive(
            @TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var reviewerClaim = collaboration.announce(fixture.project, "codex",
                "syn039-reviewer-publication", "Add Todo coverage",
                "Publish and validate the reviewer snapshot",
                List.of(ResourceSelector.pathExact("test_todo.py")));
        assertEquals(fixture.groupId, reviewerClaim.intent().workGroupId());

        Files.writeString(fixture.ownerWorktree.resolve("todo.py"),
                "def add_todo(items, item):\n    return [*items, item]\n\n"
                        + "\ndef remove_todo(items, item):\n"
                        + "    return [value for value in items if value != item]\n");

        Map<String, Object> projection = innerResult(
                fixture.owner.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("snapshot_publication_required", projection.get("reason"), projection.toString());
        assertEquals("finish_lane", projection.get("nextAction"), projection.toString());
        Map<String, Object> projectedResult = (Map<String, Object>) projection.get("result");
        Map<String, Object> workflow = (Map<String, Object>) projectedResult.get("workflow");
        Map<String, Object> finishArguments = (Map<String, Object>) workflow.get("arguments");

        Map<String, Object> completion = innerResult(
                fixture.owner.handleMessage(toolCall("finish_lane", ProviderJson.write(finishArguments))));
        assertEquals("ready", completion.get("status"), completion.toString());
        assertEquals("request_coordination", completion.get("nextAction"), completion.toString());
        Map<String, Object> result = (Map<String, Object>) completion.get("result");
        assertEquals("work_group_join", result.get("nextProtocolKind"), completion.toString());
        Map<String, Object> payload = (Map<String, Object>) result.get("nextProtocolPayload");
        assertEquals(fixture.groupId.toString(), payload.get("workGroupId"), completion.toString());
        assertEquals(reviewerClaim.intent().intentId().toString(), payload.get("intentId"), completion.toString());
        Map<String, Object> continuationWorkflow = (Map<String, Object>) result.get("workflow");
        assertEquals("request_coordination", continuationWorkflow.get("recommendedTool"), completion.toString());
        Map<String, Object> continuationArguments =
                (Map<String, Object>) continuationWorkflow.get("arguments");
        assertEquals("work_group_join", continuationArguments.get("kind"), completion.toString());
        assertEquals(payload, continuationArguments.get("payload"), completion.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reviewerRecoveryPreservesConsumedGrantAfterControlCheckoutAdvances(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        var location = new ProjectApplicationService().locate(fixture.project);
        var bindings = new ProviderSessionBindingService();
        var ownerBinding = bindings.find(location, "codex", "syn039-owner-publication").orElseThrow();
        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId),
                WorkspaceCollaborationService.participantHandle(ownerBinding.sessionId()));
        var before = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();

        Files.writeString(fixture.project.resolve("README.md"), "integrated control state\n");
        git(fixture.project, "add", "README.md");
        git(fixture.project, "commit", "-m", "integrated snapshot");

        Map<String, Object> stale = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("workspace_stale", stale.get("reason"), stale.toString());
        assertEquals("ensure_session", stale.get("nextAction"), stale.toString());

        Map<String, Object> ensured = innerResult(fixture.reviewer.handleMessage(toolCall("ensure_session", "{}")));
        assertEquals("ready", ensured.get("status"), ensured.toString());
        var after = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        assertEquals(before.sessionId(), after.sessionId());
        assertFalse(before.worktreePath().equals(after.worktreePath()), after.toString());

        Map<String, Object> validation = innerResult(
                fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("validation_required", validation.get("reason"), validation.toString());
        assertEquals("review_decision", validation.get("nextAction"), validation.toString());
        Map<String, Object> validationResult = (Map<String, Object>) validation.get("result");
        assertEquals("review_validation", validationResult.get("nextProtocolKind"), validation.toString());
        assertTrue(validationResult.containsKey("nextProtocolPayload"), validation.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dirtyReviewerReceivesDurableReviewDecisionAfterControlCheckoutAdvances(
            @TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        var location = new ProjectApplicationService().locate(fixture.project);
        var bindings = new ProviderSessionBindingService();
        var ownerBinding = bindings.find(location, "codex", "syn039-owner-publication").orElseThrow();
        var reviewerBinding = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        Path reviewerWorktree = Path.of(reviewerBinding.worktreePath());
        Path legitimateReviewerChange = reviewerWorktree.resolve("reviewer-notes.txt");
        Files.writeString(legitimateReviewerChange, "review before integration\n");

        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId),
                WorkspaceCollaborationService.participantHandle(ownerBinding.sessionId()));
        Files.writeString(fixture.project.resolve("README.md"), "integrated control state\n");
        git(fixture.project, "add", "README.md");
        git(fixture.project, "commit", "-m", "integrated snapshot");

        Map<String, Object> validation = innerResult(
                fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("ready", validation.get("status"), validation.toString());
        assertEquals("validation_required", validation.get("reason"), validation.toString());
        assertEquals("review_decision", validation.get("nextAction"), validation.toString());
        Map<String, Object> validationResult = (Map<String, Object>) validation.get("result");
        assertEquals(Boolean.TRUE, validationResult.get("reviewOnly"), validation.toString());
        assertEquals("review_validation", validationResult.get("nextProtocolKind"), validation.toString());
        Map<String, Object> payload = new LinkedHashMap<>(
                (Map<String, Object>) validationResult.get("nextProtocolPayload"));
        payload.put("result", "accepted");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("kind", validationResult.get("nextProtocolKind"));
        arguments.put("payload", payload);

        String accepted = fixture.reviewer.handleMessage(toolCall("respond_coordination",
                ProviderJson.write(arguments)));
        assertTrue(accepted.contains("ACCEPTED"), accepted);
        assertTrue(Files.exists(legitimateReviewerChange), "reviewer work must not be discarded");
        var after = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        assertEquals(reviewerBinding.worktreePath(), after.worktreePath(),
                "review-only continuation must not replace a dirty worktree");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dirtyParticipantReceivesPendingReviewAcceptanceAfterControlCheckoutAdvances(
            @TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        var location = new ProjectApplicationService().locate(fixture.project);
        var bindings = new ProviderSessionBindingService();
        var reviewerBinding = bindings.find(location, "codex", "syn039-reviewer-publication").orElseThrow();
        Path reviewerWorktree = Path.of(reviewerBinding.worktreePath());

        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId),
                currentParticipant(fixture.project, "syn039-owner-publication"));
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.release(fixture.project, "codex", "syn039-owner-publication");
        Map<String, Object> validation = innerResult(
                fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> validationResult = (Map<String, Object>) validation.get("result");
        Map<String, Object> validationPayload = new LinkedHashMap<>(
                (Map<String, Object>) validationResult.get("nextProtocolPayload"));
        validationPayload.put("result", "accepted");
        Map<String, Object> validationArguments = new LinkedHashMap<>();
        validationArguments.put("kind", validationResult.get("nextProtocolKind"));
        validationArguments.put("payload", validationPayload);
        String accepted = fixture.reviewer.handleMessage(toolCall(
                "respond_coordination", ProviderJson.write(validationArguments)));
        assertTrue(accepted.contains("ACCEPTED"), accepted);

        var reviewerClaim = collaboration.announce(fixture.project, "codex",
                "syn039-reviewer-publication", "Add reviewer regression coverage",
                "Publish the reviewer test snapshot after owner admission",
                List.of(ResourceSelector.pathExact("reviewer-notes.txt")));
        assertEquals(fixture.groupId, reviewerClaim.intent().workGroupId());

        String reviewRequest = fixture.owner.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{"
                        + "\"workGroupId\":\"" + fixture.groupId + "\","
                        + "\"intentId\":\"" + reviewerClaim.intent().intentId() + "\","
                        + "\"proposal\":\"Review the reviewer snapshot\"}}"));
        String requestId = nestedField(reviewRequest, "request", "requestId");
        assertTrue(requestId != null, reviewRequest);

        Files.writeString(reviewerWorktree.resolve("reviewer-notes.txt"),
                "legitimate reviewer work\n");
        Files.writeString(fixture.project.resolve("README.md"), "integrated control state\n");
        git(fixture.project, "add", "README.md");
        git(fixture.project, "commit", "-m", "integrated snapshot");

        Map<String, Object> next = innerResult(
                fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
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
    void acceptedReviewReportsActiveStatusWhileOwnerIntentRemainsLive(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId),
                currentParticipant(fixture.project, "syn039-owner-publication"));

        Map<String, Object> next = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> result = (Map<String, Object>) next.get("result");
        Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) result.get("nextProtocolPayload"));
        payload.put("result", "accepted");
        Map<String, Object> acceptedArguments = new LinkedHashMap<>();
        acceptedArguments.put("kind", result.get("nextProtocolKind"));
        acceptedArguments.put("payload", payload);

        String accepted = fixture.reviewer.handleMessage(toolCall("respond_coordination",
                ProviderJson.write(acceptedArguments)));
        Map<String, Object> acceptedResult = (Map<String, Object>) innerResult(accepted).get("result");
        assertEquals("ACCEPTED", acceptedResult.get("result"), accepted);
        assertEquals("ACTIVE", acceptedResult.get("workGroupStatus"), accepted);

        Map<String, Object> status = (Map<String, Object>) innerResult(fixture.reviewer.handleMessage(
                toolCall("request_coordination", "{\"kind\":\"collaboration_status\",\"payload\":{}}")))
                .get("result");
        List<Map<String, Object>> groups = (List<Map<String, Object>>) status.get("groups");
        assertEquals("ACTIVE", groups.stream()
                .filter(group -> fixture.groupId.toString().equals(group.get("workGroupId")))
                .findFirst().orElseThrow().get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectedReviewRoutesActionableWorkToTheImplementer(@TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId),
                currentParticipant(fixture.project, "syn039-owner-publication"));

        Map<String, Object> next = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> result = (Map<String, Object>) next.get("result");
        Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) result.get("nextProtocolPayload"));
        payload.put("result", "rejected");
        payload.put("reason", "The implementation is missing the required completion behavior.");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("kind", result.get("nextProtocolKind"));
        arguments.put("payload", payload);

        String rejected = fixture.reviewer.handleMessage(toolCall("respond_coordination",
                ProviderJson.write(arguments)));
        Map<String, Object> rejectedResult = (Map<String, Object>) innerResult(rejected).get("result");
        assertEquals("REJECTED", rejectedResult.get("result"), rejected);
        Map<String, Object> route = (Map<String, Object>) rejectedResult.get("route");
        assertEquals("ensure_session", route.get("nextAction"), rejected);
        assertEquals(fixture.intentId.toString(), route.get("targetIntentId"), rejected);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reviewValidationRejectsWrongParticipantStaleEpochSnapshotInvalidResultAndConflictingReplay(
            @TempDir Path temp) throws Exception {
        ReviewFixture fixture = prepareReviewFixture(temp);
        appendReviewableSnapshot(fixture.project, new UUIDs(fixture.groupId, fixture.intentId),
                currentParticipant(fixture.project, "syn039-owner-publication"));
        Map<String, Object> next = innerResult(fixture.reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> result = (Map<String, Object>) next.get("result");
        Map<String, Object> basePayload = new LinkedHashMap<>((Map<String, Object>) result.get("nextProtocolPayload"));

        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new AgentSessionService.SessionResolutionRequest(
                fixture.project, "codex", "syn039-wrong-reviewer", null, false));
        McpProtocolHandler wrongReviewer = new McpProtocolHandler(
                sessions, fixture.project, "codex", "syn039-wrong-reviewer");
        String wrongParticipant = wrongReviewer.handleMessage(toolCall("respond_coordination",
                ProviderJson.write(reviewArguments(basePayload, "accepted", null))));
        assertTrue(wrongParticipant.contains("REVIEW_GRANT_TARGET_MISMATCH"), wrongParticipant);

        Map<String, Object> staleEpoch = new LinkedHashMap<>(basePayload);
        staleEpoch.put("claimEpoch", 2);
        String stale = fixture.reviewer.handleMessage(toolCall("respond_coordination",
                ProviderJson.write(reviewArguments(staleEpoch, "accepted", null))));
        assertTrue(stale.contains("REVIEW_GRANT_BINDING_MISMATCH"), stale);

        Map<String, Object> wrongSnapshot = new LinkedHashMap<>(basePayload);
        wrongSnapshot.put("snapshotId", "snap_wrong");
        String snapshotMismatch = fixture.reviewer.handleMessage(toolCall("respond_coordination",
                ProviderJson.write(reviewArguments(wrongSnapshot, "accepted", null))));
        assertTrue(snapshotMismatch.contains("REVIEW_SNAPSHOT"), snapshotMismatch);

        String invalid = fixture.reviewer.handleMessage(toolCall("respond_coordination",
                ProviderJson.write(reviewArguments(basePayload, "accepted|rejected", null))));
        assertTrue(invalid.contains("COORDINATION_RESPONSE_INVALID_RESULT"), invalid);

        String accepted = fixture.reviewer.handleMessage(toolCall("respond_coordination",
                ProviderJson.write(reviewArguments(basePayload, "accepted", null))));
        assertTrue(accepted.contains("ACCEPTED"), accepted);
        String replayed = fixture.reviewer.handleMessage(toolCall("respond_coordination",
                ProviderJson.write(reviewArguments(basePayload, "accepted", null))));
        assertTrue(replayed.contains("ACCEPTED"), replayed);

        String conflictingReplay = fixture.reviewer.handleMessage(toolCall("respond_coordination",
                ProviderJson.write(reviewArguments(basePayload, "rejected",
                        "A conflicting replay must not replace the recorded decision."))));
        assertTrue(conflictingReplay.contains("REVIEW_DECISION_CONFLICT"), conflictingReplay);
    }

    private static Map<String, Object> reviewArguments(Map<String, Object> basePayload,
            String result, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>(basePayload);
        payload.put("result", result);
        if (reason != null) payload.put("reason", reason);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("kind", "review_validation");
        arguments.put("payload", payload);
        return arguments;
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
        return new ReviewFixture(project, Path.of(ownerBinding.worktreePath()), owner, reviewer,
                ids.groupId, ids.intentId);
    }

    private static String toolCall(String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}";
    }

    private static String currentParticipant(Path project, String connectionInstanceId) throws Exception {
        var location = new ProjectApplicationService().locate(project);
        return WorkspaceCollaborationService.participantHandle(
                new ProviderSessionBindingService().find(location, "codex", connectionInstanceId)
                        .orElseThrow().sessionId());
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

    private record ReviewFixture(Path project, Path ownerWorktree, McpProtocolHandler owner,
            McpProtocolHandler reviewer, UUID groupId, UUID intentId) { }
}
