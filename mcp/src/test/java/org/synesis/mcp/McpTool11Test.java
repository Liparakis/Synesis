package org.synesis.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.AgentSessionService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpTool11Test {

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
    @SuppressWarnings("unchecked")
    void verifiesElevenMcpToolsRegisteredAndCancelTaskSchema(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("mcp-project");
        Files.createDirectories(projectRoot);
        git(projectRoot, "init");
        git(projectRoot, "config", "user.name", "Test User");
        git(projectRoot, "config", "user.email", "test@example.com");
        Files.writeString(projectRoot.resolve("README.md"), "# Test Repo\n");
        git(projectRoot, "add", ".");
        git(projectRoot, "commit", "-m", "Initial commit");
        new ProjectApplicationService().init(projectRoot);

        ProjectApplicationService projectService = new ProjectApplicationService();
        org.synesis.workspace.application.ProviderSessionBindingService bindingService = new org.synesis.workspace.application.ProviderSessionBindingService();
        var location = projectService.locate(projectRoot);
        bindingService.ensure(location, "codex", "conn-mcp-11");
        var bindings = bindingService.list(location, "codex");
        if (!bindings.isEmpty() && bindings.getLast().worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, "codex", bindings.getLast().sessionId(), Path.of(bindings.getLast().worktreePath()));
        }

        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, projectRoot, "codex", "conn-mcp-11");

        // 1. Initialize
        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + projectRoot.toUri() + "\"}}";
        String initResp = handler.handleMessage(initReq);
        assertNotNull(initResp);

        // 2. tools/list
        String listReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
        String listResp = handler.handleMessage(listReq);
        assertNotNull(listResp);

        Map<String, Object> respMap = (Map<String, Object>) ProviderJson.parse(listResp);
        Map<String, Object> result = (Map<String, Object>) respMap.get("result");
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");

        // Must equal exactly 11 tools
        assertEquals(11, tools.size());

        Map<String, Object> cancelTool = tools.stream().filter(t -> "synesis.cancel_task".equals(t.get("name"))).findFirst().orElseThrow();
        assertEquals("synesis.cancel_task", cancelTool.get("name"));
        assertNotNull(cancelTool.get("inputSchema"));

        // 3. Ensure session first
        String ensureReq = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.ensure_session\",\"arguments\":{}}}";
        handler.handleMessage(ensureReq);

        // 4. Call synesis.cancel_task
        String cancelCallReq = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.cancel_task\",\"arguments\":{\"reason\":\"Task no longer needed.\"}}}";
        String cancelCallResp = handler.handleMessage(cancelCallReq);
        assertNotNull(cancelCallResp);
        assertTrue(cancelCallResp.contains("cancelled") || cancelCallResp.contains("COMPLETED"));
    }
}
