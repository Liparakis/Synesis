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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.infrastructure.filesystem.TextFileDocument;

/** Exercises the reviewed immutable-snapshot continuation through the MCP boundary. */
final class McpSyn039RejectedContinuationTest {

    @Test
    @SuppressWarnings("unchecked")
    void rejectedSnapshotCreatesFreshReviewableCorrectionAndOnlyAcceptedCorrectionIntegrates(
            @TempDir Path temp) throws Exception {
        Path project = temp.resolve("syn039-rejected-continuation");
        Files.createDirectories(project);
        git(project, "init");
        git(project, "config", "user.name", "SYN-039 Test");
        git(project, "config", "user.email", "syn039@example.test");
        Files.writeString(project.resolve("todo.py"),
                "def add_todo(items, item):\n    return [*items, item]\n");
        git(project, "add", ".");
        git(project, "commit", "-m", "baseline");

        new ProjectApplicationService().init(project);
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new AgentSessionService.SessionResolutionRequest(
                project, "codex", "syn039-owner", null, false));
        sessions.ensureSession(new AgentSessionService.SessionResolutionRequest(
                project, "codex", "syn039-reviewer", null, false));
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(project);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        for (var binding : bindings.list(location, "codex")) {
            if (binding.worktreePath() != null) {
                bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
            }
        }
        var ownerBinding = bindings.find(location, "codex", "syn039-owner").orElseThrow();
        Path ownerWorktree = Path.of(ownerBinding.worktreePath());

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claim = collaboration.announce(project, "codex", "syn039-owner", "Implement Todo",
                "Todo completion must reject invalid indexes", List.of(ResourceSelector.pathExact("todo.py")));
        UUID groupId = claim.intent().workGroupId();
        UUID intentId = claim.intent().intentId();
        var reviewerClaim = collaboration.announce(project, "codex", "syn039-reviewer", "Review Todo",
                "Review the immutable Todo snapshot", List.of(ResourceSelector.pathExact("test_todo.py")),
                groupId, org.synesis.coordination.domain.collaboration.WorkIntent.CompletionMode.SNAPSHOT_REQUIRED,
                org.synesis.coordination.domain.collaboration.WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("todo.py")));
        assertTrue(reviewerClaim.acquired());
        McpProtocolHandler owner = new McpProtocolHandler(sessions, project, "codex", "syn039-owner");
        McpProtocolHandler reviewer = new McpProtocolHandler(sessions, project, "codex", "syn039-reviewer");

        Map<String, Object> reviewerAdmission = innerResult(
                reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> admissionResult = map(reviewerAdmission.get("result"));
        Map<String, Object> admissionWorkflow = map(admissionResult.get("workflow"));
        Map<String, Object> admissionArguments = map(admissionWorkflow.get("arguments"));
        assertEquals("request_coordination", admissionWorkflow.get("recommendedTool"));
        String requestJson = reviewer.handleMessage(toolCall("request_coordination",
                ProviderJson.write(admissionArguments)));
        String requestId = nestedField(requestJson, "request", "requestId");
        assertNotNull(requestId, requestJson);

        Map<String, Object> ownerRequest = innerResult(owner.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> ownerRequestResult = map(ownerRequest.get("result"));
        Map<String, Object> ownerResponseArguments = map(map(ownerRequestResult.get("workflow")).get("arguments"));
        assertEquals(requestId, map(ownerResponseArguments.get("payload")).get("coordinationRequest"));
        owner.handleMessage(toolCall("respond_coordination", ProviderJson.write(ownerResponseArguments)));

        Map<String, Object> reviewerStatus = collaborationStatus(reviewer);
        Map<String, Object> grantOne = grants(reviewerStatus).getFirst();
        reviewer.handleMessage(toolCall("request_coordination", ProviderJson.write(Map.of(
                "kind", "work_group_join",
                "payload", Map.of("workGroupId", groupId.toString(),
                        "grantId", grantOne.get("grantId"),
                        "intentId", intentId.toString(),
                        "claimEpoch", grantOne.get("claimEpoch"),
                        "targetParticipant", grantOne.get("targetParticipant"))))));

        Files.writeString(ownerWorktree.resolve("todo.py"),
                "def add_todo(items, item):\n    return [*items, item]\n\n"
                        + "def complete_todo(items, index):\n    return items[:index] + items[index + 1:]\n");
        Map<String, Object> publication = innerResult(owner.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> publicationResult = map(publication.get("result"));
        Map<String, Object> finishArguments = map(map(publicationResult.get("workflow")).get("arguments"));
        Map<String, Object> firstCompletion = innerResult(owner.handleMessage(
                toolCall("finish_lane", ProviderJson.write(finishArguments))));
        Map<String, Object> firstCompletionResult = map(firstCompletion.get("result"));
        assertEquals("waiting", firstCompletion.get("status"), firstCompletion.toString());
        assertEquals("REVIEW_PENDING", firstCompletionResult.get("snapshotState"), firstCompletion.toString());

        PredictionEventStore store = store(project);
        var firstSnapshot = store.taskCompletionProjection().allSnapshots().getFirst();
        assertTrue(firstSnapshot.reviewRequired());
        assertEquals(TaskCompletionState.REVIEW_PENDING,
                store.taskCompletionProjection().snapshotState(firstSnapshot.snapshotId()).orElseThrow());
        Map<String, Object> firstReplay = innerResult(owner.handleMessage(
                toolCall("finish_lane", ProviderJson.write(finishArguments))));
        Map<String, Object> firstReplayResult = map(firstReplay.get("result"));
        assertEquals("waiting", firstReplay.get("status"), firstReplay.toString());
        assertEquals(firstSnapshot.snapshotId(), firstReplayResult.get("snapshotId"), firstReplay.toString());
        store = store(project);
        assertEquals(1, store.taskCompletionProjection().allSnapshots().size());
        assertTrue(store.collaborationProjection().intent(intentId).isPresent());
        assertEquals(1L, store.collaborationProjection().intent(intentId).orElseThrow().version());
        assertFalse(store.events().stream().anyMatch(event -> event.type() == PredictionEventType.TASK_INTEGRATED));
        assertFalse(store.events().stream().anyMatch(event -> event.type() == PredictionEventType.WORK_INTENT_RELEASED));
        assertEquals("BOUND", bindings.find(location, "codex", "syn039-owner").orElseThrow().status());

        Map<String, Object> reviewAction = innerResult(reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> reviewResult = map(reviewAction.get("result"));
        Map<String, Object> rejectedPayload = new LinkedHashMap<>(map(reviewResult.get("nextProtocolPayload")));
        rejectedPayload.put("result", "rejected");
        rejectedPayload.put("reason", "invalid indexes do not raise IndexError");
        reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(Map.of(
                "kind", "review_validation", "payload", rejectedPayload))));

        store = store(project);
        var rejectedIntent = store.collaborationProjection().intent(intentId).orElseThrow();
        assertEquals(2L, rejectedIntent.version());
        assertEquals(claim.intent().authorityLineageId(), rejectedIntent.authorityLineageId());
        assertEquals(TaskCompletionState.REVIEW_REJECTED,
                store.taskCompletionProjection().snapshotState(firstSnapshot.snapshotId()).orElseThrow());
        assertEquals("ACTIVE", store.collaborationProjection().participantState(
                claim.intent().participant()).orElseThrow().name());

        Map<String, Object> correction = innerResult(owner.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> correctionResult = map(correction.get("result"));
        Map<String, Object> correctionWorkflow = map(correctionResult.get("workflow"));
        assertEquals("ready", correction.get("status"), correction.toString());
        assertEquals("revision_required", correction.get("reason"), correction.toString());
        assertEquals("IMPLEMENT", correctionWorkflow.get("type"), correction.toString());
        assertEquals(firstSnapshot.snapshotId(), correctionResult.get("latestRejectedSnapshotId"));
        assertFalse(correctionWorkflow.containsKey("recommendedTool"));

        Map<String, Object> secondAdmission = innerResult(reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> secondAdmissionArgs = map(map(map(secondAdmission.get("result")).get("workflow"))
                .get("arguments"));
        String secondRequestJson = reviewer.handleMessage(toolCall("request_coordination",
                ProviderJson.write(secondAdmissionArgs)));
        String secondRequestId = nestedField(secondRequestJson, "request", "requestId");
        assertNotEquals(requestId, secondRequestId);

        Map<String, Object> secondOwnerRequest = innerResult(owner.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> secondOwnerResponse = map(map(map(secondOwnerRequest.get("result")).get("workflow"))
                .get("arguments"));
        owner.handleMessage(toolCall("respond_coordination", ProviderJson.write(secondOwnerResponse)));
        Map<String, Object> secondStatus = collaborationStatus(reviewer);
        assertFalse(grants(secondStatus).isEmpty(), secondStatus.toString());
        reviewerStatus = secondStatus;
        Map<String, Object> grantTwo = grants(reviewerStatus).stream()
                .filter(grant -> ((Number) grant.get("claimEpoch")).longValue() == 2L)
                .findFirst().orElseThrow(() -> new AssertionError(secondStatus.toString()));
        assertNotEquals(grantOne.get("grantId"), grantTwo.get("grantId"));
        reviewer.handleMessage(toolCall("request_coordination", ProviderJson.write(Map.of(
                "kind", "work_group_join",
                "payload", Map.of("workGroupId", groupId.toString(),
                        "grantId", grantTwo.get("grantId"),
                        "intentId", intentId.toString(),
                        "claimEpoch", grantTwo.get("claimEpoch"),
                        "targetParticipant", grantTwo.get("targetParticipant"))))));

        String rejectedImplementation = "def complete_todo(items, index):\n"
                + "    return items[:index] + items[index + 1:]\n";
        String correctedImplementation = "def complete_todo(items, index):\n"
                + "    if index < 0 or index >= len(items):\n"
                + "        raise IndexError(index)\n"
                + "    return items[:index] + items[index + 1:]\n";
        String currentRevision = TextFileDocument.decode(Files.readAllBytes(ownerWorktree.resolve("todo.py")))
                .revision();
        Map<String, Object> correctionArguments = Map.of(
                "path", "todo.py",
                "expectedHash", currentRevision,
                "edits", List.of(Map.of(
                        "find", rejectedImplementation,
                        "replace", correctedImplementation,
                        "expectedOccurrences", 1)));
        Map<String, Object> correctionResponse = innerResult(owner.handleMessage(
                toolCall("apply_patch", ProviderJson.write(correctionArguments))));
        assertEquals("completed", correctionResponse.get("status"), correctionResponse.toString());
        assertTrue(Files.readString(ownerWorktree.resolve("todo.py")).contains("raise IndexError(index)"));
        Map<String, Object> secondPublication = innerResult(owner.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> secondPublicationResult = map(secondPublication.get("result"));
        Map<String, Object> secondFinish = map(map(secondPublicationResult.get("workflow")).get("arguments"));
        assertEquals(2L, ((Number) secondFinish.get("claimEpoch")).longValue());
        PredictionEventStore beforeSecondFinish = store(project);
        assertEquals(beforeSecondFinish.headSequence(),
                ((Number) secondFinish.get("expectedRevision")).longValue(), secondFinish.toString());
        assertEquals(beforeSecondFinish.workGroupProjection().group(groupId).orElseThrow().version(),
                ((Number) secondFinish.get("workGroupVersion")).longValue(), secondFinish.toString());
        var beforeIntent = beforeSecondFinish.collaborationProjection().intent(intentId).orElseThrow();
        assertEquals(intentId.toString(), secondFinish.get("intentId"), secondFinish.toString());
        assertEquals(groupId.toString(), secondFinish.get("workGroupId"), secondFinish.toString());
        assertEquals(beforeIntent.participant(), secondFinish.get("participant"), secondFinish.toString());
        assertEquals("ANNOUNCED", beforeIntent.status().name(), beforeIntent.toString());
        Map<String, Object> secondCompletion = innerResult(owner.handleMessage(
                toolCall("finish_lane", ProviderJson.write(secondFinish))));
        Map<String, Object> secondCompletionResult = map(secondCompletion.get("result"));
        assertEquals("waiting", secondCompletion.get("status"), secondCompletion.toString());
        assertEquals("REVIEW_PENDING", secondCompletionResult.get("snapshotState"), secondCompletion.toString());

        store = store(project);
        var secondSnapshot = store.taskCompletionProjection().allSnapshots().stream()
                .filter(snapshot -> snapshot.provenance().claimEpoch() == 2L).findFirst().orElseThrow();
        assertNotEquals(firstSnapshot.snapshotId(), secondSnapshot.snapshotId());
        assertEquals(firstSnapshot.commitSha(), store.taskCompletionProjection()
                .findSnapshotById(firstSnapshot.snapshotId()).orElseThrow().commitSha());
        assertEquals(TaskCompletionState.REVIEW_REJECTED,
                store.taskCompletionProjection().snapshotState(firstSnapshot.snapshotId()).orElseThrow());
        assertEquals(TaskCompletionState.REVIEW_PENDING,
                store.taskCompletionProjection().snapshotState(secondSnapshot.snapshotId()).orElseThrow());

        Map<String, Object> staleGrantPayload = new LinkedHashMap<>();
        staleGrantPayload.put("grantId", grantOne.get("grantId"));
        staleGrantPayload.put("snapshotId", secondSnapshot.snapshotId());
        staleGrantPayload.put("intentId", intentId.toString());
        staleGrantPayload.put("claimEpoch", 1L);
        staleGrantPayload.put("result", "accepted");
        String staleGrant = reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(Map.of(
                "kind", "review_validation", "payload", staleGrantPayload))));
        assertTrue(staleGrant.contains("REVIEW_SNAPSHOT"), staleGrant);

        Map<String, Object> secondReview = innerResult(reviewer.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> secondReviewResult = map(secondReview.get("result"));
        assertEquals("review_decision", secondReviewResult.get("nextProtocolAction"), secondReview.toString());
        Map<String, Object> acceptedPayload = new LinkedHashMap<>(map(secondReviewResult.get("nextProtocolPayload")));
        acceptedPayload.put("result", "accepted");
        String acceptedReview = reviewer.handleMessage(toolCall("respond_coordination", ProviderJson.write(Map.of(
                "kind", "review_validation", "payload", acceptedPayload))));
        Map<String, Object> acceptedReviewResult = innerResult(acceptedReview);
        assertEquals("completed", acceptedReviewResult.get("status"), acceptedReviewResult.toString());
        collaboration.release(project, "codex", "syn039-reviewer");
        bindings.complete(location, "codex", "syn039-reviewer");
        store = store(project);
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED,
                store.taskCompletionProjection().snapshotState(secondSnapshot.snapshotId()).orElseThrow(),
                store.events().toString());
        assertEquals("ACCEPTED", store.workGroupProjection().reviewValidationForSnapshot(secondSnapshot.snapshotId())
                .orElseThrow().result(), store.events().toString());
        Map<String, Object> terminal = innerResult(owner.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("completed", terminal.get("status"), terminal.toString());
        store = store(project);
        assertEquals(TaskCompletionState.INTEGRATED,
                store.taskCompletionProjection().snapshotState(secondSnapshot.snapshotId()).orElseThrow());
        assertTrue(store.collaborationProjection().activeIntents().isEmpty());
        assertEquals("COMPLETED", store.workGroupProjection().group(groupId).orElseThrow().status().name());
        assertEquals("COMPLETED", store.collaborationProjection().participantState(
                claim.intent().participant()).orElseThrow().name());
        assertEquals("COMPLETED", bindings.find(location, "codex", "syn039-owner").orElseThrow().status());

        long integratedSequence = sequenceOf(store, PredictionEventType.TASK_INTEGRATED, secondSnapshot.snapshotId());
        long releasedSequence = store.events().stream()
                .filter(event -> event.type() == PredictionEventType.WORK_INTENT_RELEASED)
                .mapToLong(event -> event.sequence()).max().orElseThrow();
        assertTrue(integratedSequence < releasedSequence);
    }

    private static PredictionEventStore store(Path project) throws Exception {
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(project);
        return new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
    }

    private static long sequenceOf(PredictionEventStore store, PredictionEventType type, String snapshotId)
            throws Exception {
        return store.events().stream().filter(event -> event.type() == type)
                .filter(event -> new String(event.payload()).contains(snapshotId))
                .mapToLong(event -> event.sequence()).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> collaborationStatus(McpProtocolHandler handler) {
        return map(innerResult(handler.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"collaboration_status\",\"payload\":{}}"))).get("result"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> grants(Map<String, Object> status) {
        return (List<Map<String, Object>>) status.get("grants");
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
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static String nestedField(String json, String parent, String field) {
        Object result = innerResult(json).get("result");
        if (!(result instanceof Map<?, ?> map)) return null;
        Object value = map.get(parent);
        return value instanceof Map<?, ?> nested && nested.get(field) instanceof String text ? text : null;
    }

    private static void git(Path root, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(output);
    }
}
