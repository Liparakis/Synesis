package org.synesis.workspace.application.agent;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;

import org.synesis.workspace.application.ProjectApplicationService;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.agent.AgentResponse;

import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskCancellationTest {

    private static void git(Path root, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git failed");
        }
    }

    @Test
    void cancelsTaskAndPreservesWorktree(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("cancel-project");
        Files.createDirectories(projectRoot);
        git(projectRoot, "init");
        git(projectRoot, "config", "user.name", "Test User");
        git(projectRoot, "config", "user.email", "test@example.com");
        Files.writeString(projectRoot.resolve("README.md"), "# Test Repo\n");
        git(projectRoot, "add", ".");
        git(projectRoot, "commit", "-m", "Initial commit");
        new ProjectApplicationService().init(projectRoot);

        ProjectApplicationService projectService = new ProjectApplicationService();
        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        var location = projectService.locate(projectRoot);
        bindingService.ensure(location, "codex", "conn-cancel");
        var bindings = bindingService.list(location, "codex");
        if (!bindings.isEmpty() && bindings.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "codex", bindings.getLast().sessionId(), Path.of(bindings.getLast().worktreePath()));
        }

        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest resReq = new AgentSessionService.SessionResolutionRequest(
                projectRoot, "codex", "conn-cancel", null, false
        );
        AgentResponse sessionResp = sessionService.ensureSession(resReq);
        assertEquals(AgentStatus.READY, sessionResp.status());
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        assertTrue(collaboration.announce(projectRoot, "codex", "conn-cancel", "cancel lane", "release",
                List.of(ResourceSelector.pathExact("src/cancelled.py"))).acquired());

        AgentTaskCancellationService cancelService = new AgentTaskCancellationService();
        AgentTaskCancellationService.CancelTaskRequest cancelReq = new AgentTaskCancellationService.CancelTaskRequest(
                projectRoot, "codex", "conn-cancel", "Task is no longer required."
        );

        AgentResponse cancelResp = cancelService.cancelTask(cancelReq);
        assertEquals(AgentStatus.COMPLETED, cancelResp.status());
        assertNotNull(cancelResp.result());
        assertTrue(collaboration.status(projectRoot).intents().stream()
                .noneMatch(intent -> intent.participant().equals(
                        WorkspaceCollaborationService.participantHandle("conn-cancel"))));

        // Repeated cancellation must be idempotent
        AgentResponse repeatResp = cancelService.cancelTask(cancelReq);
        assertEquals(AgentStatus.COMPLETED, repeatResp.status());

        // Verify project root directory exists and remains intact
        assertTrue(Files.exists(projectRoot));
    }
}
