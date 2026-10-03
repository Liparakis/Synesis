package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.mcp.contract.McpToolCatalog;

import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Verifies the existing coordination tool durably bootstraps mandatory review policy.
 */
final class McpMandatoryReviewPolicyTest {

    @Test
    @SuppressWarnings("unchecked")
    void workGroupCreatePersistsMandatoryIndependentPolicyWithoutAddingTool(@TempDir Path temp) throws Exception {
        String previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", temp.resolve("home").toString());
        try {
            Path project = temp.resolve("mandatory-review-project");
            Files.createDirectories(project);
            git(project, "init");
            git(project, "config", "user.name", "Review Policy Test");
            git(project, "config", "user.email", "review-policy@example.test");
            Files.writeString(project.resolve("README.md"), "# Review policy\n");
            git(project, "add", ".");
            git(project, "commit", "-m", "baseline");
            ProjectLocation location = new ProjectApplicationService().init(project).location();
            McpProviderTestSupport.install(location, "codex");
            new ProviderManualService().install("codex");

            String connection = "mandatory-review-bootstrap";
            AgentSessionService sessions = new AgentSessionService();
            sessions.ensureSession(new SessionResolutionRequest(project, "codex", connection, null, false));
            var binding = new ProviderSessionBindingService().find(location, "codex", connection).orElseThrow();
            new ProviderSessionBindingService().verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));

            UUID groupId = UUID.randomUUID();
            Map<String, Object> arguments = Map.of("kind",
                    "work_group_create",
                    "payload",
                    Map.of("workGroupId",
                            groupId.toString(),
                            "goal",
                            "Build a local board",
                            "acceptance",
                            "CRUD, persistence, filtering, and tests",
                            "reviewPolicy",
                            Map.of("mandatory", true, "independenceRequired", true)));
            McpProtocolHandler handler = new McpProtocolHandler(sessions, project, "codex", connection);
            Map<String, Object> response = innerResult(handler.handleMessage(toolCall(arguments)));

            assertEquals("completed", response.get("status"), response.toString());
            assertEquals(10, McpToolCatalog.rawNames().size());
            assertEquals(ReviewPolicy.mandatoryIndependent(),
                    new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).workGroupProjection().group(groupId).orElseThrow().reviewPolicy());

            Map<String, Object> producerTask = Map.of("goal",
                    "Build a local board",
                    "acceptance",
                    "CRUD, persistence, filtering, and tests",
                    "workGroupId",
                    groupId.toString(),
                    "claims",
                    List.of(Map.of("kind", "path_exact", "path", "src/board.py")));
            Map<String, Object> assigned = innerResult(handler.handleMessage(toolCall("ensure_session", Map.of("task", producerTask))));
            assertTrue(List.of("ready", "completed").contains(assigned.get("status")), assigned.toString());

            var producerBinding = new ProviderSessionBindingService().find(location, "codex", connection).orElseThrow();
            Path implementation = Path.of(producerBinding.worktreePath()).resolve("src/board.py");
            Files.createDirectories(implementation.getParent());
            Files.writeString(implementation, "def add_issue(issues, issue):\n" + "    return [*issues, issue]\n");

            Map<String, Object> publication = innerResult(handler.handleMessage(toolCall("get_next_action", Map.of("completionRequested", true))));
            Map<String, Object> publicationResult = (Map<String, Object>) publication.get("result");
            Map<String, Object> workflow = (Map<String, Object>) publicationResult.get("workflow");
            assertEquals("finish_lane", workflow.get("recommendedTool"), publication.toString());
            Map<String, Object> completion = innerResult(handler.handleMessage(toolCall("finish_lane", (Map<String, Object>) workflow.get("arguments"))));
            Map<String, Object> completionResult = (Map<String, Object>) completion.get("result");
            assertEquals(Boolean.TRUE, completionResult.get("reviewRequired"), completion.toString());
            assertEquals("REVIEW_PENDING", completionResult.get("snapshotState"), completion.toString());
            String snapshotId = String.valueOf(completionResult.get("snapshotId"));
            assertTrue(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).taskCompletionProjection().findSnapshotById(snapshotId).orElseThrow().reviewRequired());
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> innerResult(String response) {
        Map<String, Object> outer = (Map<String, Object>) ProviderJson.parse(response);
        Map<String, Object> rpcResult = (Map<String, Object>) outer.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) rpcResult.get("content");
        return (Map<String, Object>) ProviderJson.parse((String) content.getFirst().get("text"));
    }

    private static String toolCall(Map<String, Object> arguments) {
        return toolCall("request_coordination", arguments);
    }

    private static String toolCall(String name, Map<String, Object> arguments) {
        return ProviderJson.write(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", name, "arguments", arguments)));
    }

    private static void git(Path root, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
    }
}
