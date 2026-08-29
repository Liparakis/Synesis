package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.provider.ProviderManualService;

/**
 * Verifies the prerelease raw MCP contract and managed-manual gate.
 */
class McpSyn028ContractTest {

    private static void git(Path root, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream()
                .readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
    }

    @Test
    void toolsUseRawNamesAndDecoratedNamesAreRejected(@TempDir Path temp) throws Exception {
        String previous = System.getProperty("user.home");
        System.setProperty("user.home",
                temp.resolve("home")
                        .toString());
        try {
            Path project = temp.resolve("project");
            Files.createDirectories(project);
            git(project, "init");
            git(project, "config", "user.name", "Test User");
            git(project, "config", "user.email", "test@example.com");
            Files.writeString(project.resolve("README.md"), "base\n");
            git(project, "add", ".");
            git(project, "commit", "-m", "base");
            new ProjectApplicationService().init(project);
            new ProviderManualService().install("codex");
            new ProviderManualService().install("claude");
            new ProviderManualService().install("antigravity");
            McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), project, "codex", "syn028");
            String list = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
            assertEquals(10, list.split("\"name\":\"").length - 1);
            assertTrue(list.contains("request_coordination"));
            assertTrue(list.contains("respond_coordination"));
            assertTrue(list.contains("publish_capability_implementation"));
            assertTrue(list.contains("finish_lane"));
            String decorated = handler.handleMessage(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.ensure_session\",\"arguments\":{}}}");
            assertTrue(decorated.contains("raw MCP tool name required"));
        } finally {
            if (previous == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previous);
            }
        }
    }
}
