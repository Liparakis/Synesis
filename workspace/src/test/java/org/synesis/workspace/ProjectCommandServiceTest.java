package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentSessionService;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.ProjectCommandIntent;
import org.synesis.workspace.application.ProjectCommandService;
import org.synesis.workspace.application.ProviderSessionBindingService;

class ProjectCommandServiceTest {

    private Path controlRoot;

    private static void git(Path root, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git failed: " + output);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        controlRoot = Files.createTempDirectory("synesis-cmd-test-");
        git(controlRoot, "init");
        git(controlRoot, "config", "user.name", "Test User");
        git(controlRoot, "config", "user.email", "test@example.com");

        Files.createDirectories(controlRoot.resolve("src"));
        Files.writeString(controlRoot.resolve("src/Product.java"), "public class Product {}\n");

        git(controlRoot, "add", ".");
        git(controlRoot, "commit", "-m", "Initial commit");

        new ProjectApplicationService().init(controlRoot);
    }

    @Test
    void testExecutesGitStatusInAssignedWorktree() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest sessionReq = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-cmd-1", null, false);
        sessionService.ensureSession(sessionReq);

        // Verify workspace trust
        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        var bindings = bindingService.list(location, "codex");
        var binding = bindings.getLast();
        bindingService.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));

        ProjectCommandService commandService = new ProjectCommandService();
        ProjectCommandIntent intent = new ProjectCommandIntent("git_status", null, List.of());
        ProjectCommandService.CommandRequest request = new ProjectCommandService.CommandRequest(
                controlRoot, "codex", "conn-cmd-1", intent);

        AgentResponse response = commandService.runCommand(request);
        assertEquals(AgentStatus.COMPLETED, response.status());

        String json = response.toJson();
        assertTrue(json.contains("git_status"));
        assertFalse(json.contains(controlRoot.toString())); // No absolute paths leaked
    }

    @Test
    void testRejectsUnsupportedBuildSystem() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest sessionReq = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-cmd-2", null, false);
        sessionService.ensureSession(sessionReq);

        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        var bindings = bindingService.list(location, "codex");
        var binding = bindings.getLast();
        bindingService.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));

        ProjectCommandService commandService = new ProjectCommandService();
        // "build" in project without Gradle/Maven/npm/dotnet
        ProjectCommandIntent intent = new ProjectCommandIntent("build", null, List.of());
        ProjectCommandService.CommandRequest request = new ProjectCommandService.CommandRequest(
                controlRoot, "codex", "conn-cmd-2", intent);

        AgentResponse response = commandService.runCommand(request);
        assertEquals(AgentStatus.BLOCKED, response.status());
        assertTrue(response.toJson().contains("tool_unavailable"));
    }

    @Test
    void testEnforcesControlCheckoutProtection() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest sessionReq = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-cmd-3", null, false);
        sessionService.ensureSession(sessionReq);

        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        var bindings = bindingService.list(location, "codex");
        var binding = bindings.getLast();
        Path worktree = Path.of(binding.worktreePath());
        bindingService.verifyWorkspaceTrust(location, "codex", binding.sessionId(), worktree);

        // Control root contains Product.java
        assertTrue(Files.exists(controlRoot.resolve("src/Product.java")));
        // Modify file in worktree
        Files.writeString(worktree.resolve("src/Product.java"), "public class Product { int v = 2; }\n");

        ProjectCommandService commandService = new ProjectCommandService();
        ProjectCommandIntent intent = new ProjectCommandIntent("git_status", null, List.of());
        ProjectCommandService.CommandRequest request = new ProjectCommandService.CommandRequest(
                controlRoot, "codex", "conn-cmd-3", intent);

        AgentResponse response = commandService.runCommand(request);
        assertEquals(AgentStatus.COMPLETED, response.status());

        // Control checkout remains completely unchanged
        assertEquals("public class Product {}\n", Files.readString(controlRoot.resolve("src/Product.java")));
    }
}
