package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.project.ProjectCommandService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.test.PortableTestCommand;
import org.synesis.workspace.test.TestGit;

/**
 * Verifies direct argv execution remains lane-bound and provider-neutral.
 */
class ProjectCommandServiceTest {

    private Path controlRoot;

    private static void git(Path root, String... arguments) throws Exception {
        TestGit.run(root, arguments);
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

    private void ensureSession(String connection) {
        AgentResponse response = new AgentSessionService().ensureSession(
                new AgentSessionService.SessionResolutionRequest(controlRoot, "codex", connection, null, false));
        assertEquals(AgentStatus.READY, response.status(), response.toJson());
    }

    @Test
    void executesDirectGitArgvInAssignedWorktree() {
        ensureSession("conn-cmd-1");
        AgentResponse response = new ProjectCommandService().runCommand(new ProjectCommandService.CommandRequest(
                controlRoot, "codex", "conn-cmd-1", List.of("git", "status", "--porcelain")));
        assertEquals(AgentStatus.COMPLETED, response.status(), response.toJson());
        String json = response.toJson();
        assertTrue(json.contains("\"outcome\":\"completed\""));
        assertTrue(json.contains("stdoutBytesRead"));
        assertTrue(json.contains("stdoutBytesRetained"));
        assertTrue(json.contains("stdoutTruncated"));
        assertFalse(json.contains(controlRoot.toString()));
    }

    @Test
    void missingExecutableReturnsConcreteDiagnostic() {
        ensureSession("conn-cmd-2");
        AgentResponse response = new ProjectCommandService().runCommand(new ProjectCommandService.CommandRequest(
                controlRoot, "codex", "conn-cmd-2", List.of("synesis-command-that-does-not-exist")));
        assertEquals(AgentStatus.BLOCKED, response.status(), response.toJson());
        assertTrue(response.toJson()
                .contains("command_executable_not_found"), response.toJson());
    }

    @Test
    void preservesArgumentBoundariesWithoutImplicitShell() {
        ensureSession("conn-cmd-3");
        AgentResponse response = new ProjectCommandService().runCommand(new ProjectCommandService.CommandRequest(
                controlRoot, "codex", "conn-cmd-3",
                PortableTestCommand.stdout("hello world")));
        assertEquals(AgentStatus.COMPLETED, response.status(), response.toJson());
        assertTrue(response.toJson()
                .contains("hello world"), response.toJson());
    }

    @Test
    void commandCannotMutateControlCheckout() throws Exception {
        ensureSession("conn-cmd-4");
        var location = new ProjectApplicationService().locate(controlRoot);
        var binding = new ProviderSessionBindingService().list(location, "codex")
                .getLast();
        Path lane = Path.of(binding.worktreePath());
        Files.writeString(lane.resolve("src/Product.java"), "public class Product { int v = 2; }\n");

        AgentResponse response = new ProjectCommandService().runCommand(new ProjectCommandService.CommandRequest(
                controlRoot, "codex", "conn-cmd-4", List.of("git", "status", "--porcelain")));
        assertEquals(AgentStatus.COMPLETED, response.status(), response.toJson());
        assertEquals("public class Product {}\n", Files.readString(controlRoot.resolve("src/Product.java")));
    }
}
