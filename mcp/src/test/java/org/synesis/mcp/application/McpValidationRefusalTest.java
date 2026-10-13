package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.project.execution.ProjectCommandSpec;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.filesystem.TextFileDocument;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * A lane whose work the project's validation command refuses at integration is sent back through the MCP boundary
 * with the command's output, corrects its claimed file and finishes again.
 */
@SuppressWarnings("TextBlockMigration")
final class McpValidationRefusalTest {

    private static String toolCall(String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}";
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

    private static PredictionEventStore store(Path project) throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(project);
        return new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
    }

    private static Map<String, Object> finish(McpProtocolHandler handler) {
        Map<String, Object> publication = innerResult(handler.handleMessage(toolCall("get_next_action", "{\"completionRequested\":true}")));
        Map<String, Object> finishArguments = map(map(map(publication.get("result")).get("workflow")).get("arguments"));
        return innerResult(handler.handleMessage(toolCall("finish_lane", ProviderJson.write(finishArguments))));
    }

    private static Map<String, Object> replace(McpProtocolHandler handler, Path worktree, String find, String replacement) throws Exception {
        String revision = TextFileDocument.decode(Files.readAllBytes(worktree.resolve("todo.txt"))).revision();
        return innerResult(handler.handleMessage(toolCall("apply_patch", ProviderJson.write(Map.of("path",
                "todo.txt",
                "expectedHash",
                revision,
                "edits",
                List.of(Map.of("find", find, "replace", replacement, "expectedOccurrences", 1)))))));
    }

    @Test
    void aLaneRefusedByTheValidationCommandIsSentBackWithItsOutputCorrectsAndIntegrates(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("validation-refusal");
        Files.createDirectories(project);
        git(project, "init");
        git(project, "config", "user.name", "Validation Test");
        git(project, "config", "user.email", "validation@example.test");
        Files.writeString(project.resolve("todo.txt"), "pending\n");
        git(project, "add", ".");
        git(project, "commit", "-m", "baseline");

        ProjectApplicationService projectService = new ProjectApplicationService();
        ProjectLocation location = projectService.init(project).location();
        // Passes only while the combined tree holds the corrected word.
        projectService.setValidation(project, new ProjectCommandSpec(List.of("git", "grep", "-q", "good", "HEAD", "--", "todo.txt"), ".", 60), false);
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new SessionResolutionRequest(project, "codex", "validation-owner", null, false));
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        for (var binding : bindings.list(location, "codex")) {
            if (binding.worktreePath() != null) {
                bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
            }
        }
        Path worktree = Path.of(bindings.find(location, "codex", "validation-owner").orElseThrow().worktreePath());
        var claim = new WorkspaceCollaborationService().announce(project, "codex", "validation-owner", "Complete the todo", "todo.txt says good", List.of(ResourceSelector.pathExact("todo.txt")));
        McpProtocolHandler owner = new McpProtocolHandler(sessions, project, "codex", "validation-owner");
        Files.writeString(worktree.resolve("todo.txt"), "bad\n");

        Map<String, Object> firstFinish = finish(owner);

        PredictionEventStore store = store(project);
        var refused = store.taskCompletionProjection().allSnapshots().getFirst();
        assertEquals(TaskCompletionState.VALIDATION_REFUSED, store.taskCompletionProjection().snapshotState(refused.snapshotId()).orElseThrow(), firstFinish.toString());
        assertEquals(2L, store.collaborationProjection().intent(claim.intent().intentId()).orElseThrow().version());

        Map<String, Object> revision = innerResult(owner.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> revisionResult = map(revision.get("result"));
        assertEquals("revision_required", revision.get("reason"), revision.toString());
        assertEquals(refused.snapshotId(), revisionResult.get("latestRejectedSnapshotId"), revision.toString());
        Map<String, Object> failure = map(map(revisionResult.get("rejectedSnapshot")).get("validationFailure"));
        assertEquals("non_zero_exit", failure.get("outcome"), failure.toString());
        assertEquals(2L, ((Number) failure.get("repairsLeft")).longValue(), failure.toString());
        assertEquals(store.taskCompletionProjection().validationRefusal(refused.snapshotId()).orElseThrow().candidateCommit(), failure.get("candidateCommit"));
        assertTrue(failure.containsKey("output"), failure.toString());
        assertEquals("[\"git\",\"grep\",\"-q\",\"good\",\"HEAD\",\"--\",\"todo.txt\"]", failure.get("command").toString().replaceAll("\\s", ""), failure.toString());
        assertEquals(".", failure.get("workingDirectory"), failure.toString());
        assertTrue(revisionResult.get("instruction").toString().contains("validationFailure"), revisionResult.toString());

        assertEquals("completed", replace(owner, worktree, "bad\n", "worse\n").get("status"));
        Map<String, Object> secondRefusal = finish(owner);
        store = store(project);
        assertEquals(2, store.taskCompletionProjection().validationRefusals(claim.intent().taskId(), claim.intent().authorityLineageId()), secondRefusal.toString());
        Map<String, Object> secondRevision = map(innerResult(owner.handleMessage(toolCall("get_next_action", "{}"))).get("result"));
        Map<String, Object> secondFailure = map(map(secondRevision.get("rejectedSnapshot")).get("validationFailure"));
        assertEquals(1L, ((Number) secondFailure.get("repairsLeft")).longValue(), "the second repair is the last: " + secondFailure);
        assertTrue(secondFailure.containsKey("output"), secondFailure.toString());

        assertEquals("completed", replace(owner, worktree, "worse\n", "good\n").get("status"));

        Map<String, Object> secondFinish = finish(owner);

        assertEquals("completed", secondFinish.get("status"), secondFinish.toString());
        Map<String, Object> terminal = innerResult(owner.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("completed", terminal.get("status"), terminal.toString());
        store = store(project);
        var corrected = store.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.provenance().claimEpoch() == 3L).findFirst().orElseThrow();
        assertNotEquals(refused.snapshotId(), corrected.snapshotId());
        assertEquals(TaskCompletionState.INTEGRATED, store.taskCompletionProjection().snapshotState(corrected.snapshotId()).orElseThrow());
        assertEquals(TaskCompletionState.VALIDATION_REFUSED, store.taskCompletionProjection().snapshotState(refused.snapshotId()).orElseThrow(), "the refusal stays on record");
        assertFalse(store.events().stream().anyMatch(event -> event.type() == PredictionEventType.TASK_INTEGRATED && new String(event.payload()).contains(refused.snapshotId())),
                "the refused snapshot was never integrated");
        assertEquals("COMPLETED", store.workGroupProjection().group(claim.intent().workGroupId()).orElseThrow().status().name());
        assertEquals("good", Files.readString(project.resolve("todo.txt")).trim());
    }
}
