package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
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

        String joinRequest = reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{"
                        + "\"workGroupId\":\"" + ids.groupId + "\","
                        + "\"intentId\":\"" + ids.intentId + "\","
                        + "\"proposal\":\"Review the published Todo snapshot\"}}"));
        assertFalse(joinRequest.contains("COORDINATION_FIELD_REQUIRED:grantId"), joinRequest);
        String requestId = nestedField(joinRequest, "request", "requestId");
        assertTrue(requestId != null, joinRequest);

        String accepted = owner.handleMessage(toolCall("respond_coordination",
                "{\"kind\":\"coordination_response\",\"payload\":{"
                        + "\"coordinationRequest\":\"" + requestId + "\","
                        + "\"coordinationStatus\":\"ACCEPTED\","
                        + "\"proposal\":\"admitted\"}}"));
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

    private record UUIDs(java.util.UUID groupId, java.util.UUID intentId) { }
}
