package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.persistence.ReplicationExchange;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.control.CodeExchange;
import org.synesis.workspace.application.control.CodeOverLink;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * A member computer cut off from every other computer of the project, as in a partition, still starts its agents'
 * lanes from its own copy of main (ADR-0098, step 3 item 12). Its coordination server's code side is the real one,
 * with no live Link session.
 */
@SuppressWarnings("unchecked")
class McpMemberCutOffLaneStartTest {

    private String previousUserHome;
    private Path root;
    private ProjectLocation location;
    private CodeOverLink codeSide;

    @BeforeEach
    void setUp() throws Exception {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("synesis-mcp-cut-off-home-").toString());
        root = Files.createTempDirectory("synesis-mcp-cut-off-");
        GitProcessRunner.run(root, "init");
        GitProcessRunner.run(root, "config", "user.name", "Test User");
        GitProcessRunner.run(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "# Test Repo\n");
        GitProcessRunner.run(root, "add", ".");
        GitProcessRunner.run(root, "commit", "-m", "Initial commit");
        location = new ProjectApplicationService().init(root).location();
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        String leader = new IdentityBootstrap(Files.createTempDirectory("synesis-mcp-cut-off-leader-")).loadOrCreate().identity().nodeId();
        ReplicationSettings.member(leader).write(location.coordinationDirectory());
        codeSide = new CodeOverLink(root, location.coordinationDirectory(), location.projectId(), new CodeOverLink.Peers() {
            @Override
            public Map<String, ReplicationExchange> live() {
                return Map.of();
            }

            @Override
            public boolean member() {
                return true;
            }
        });
        CodeExchange.register(location.coordinationDirectory(), codeSide::fetch);
    }

    @AfterEach
    void tearDown() throws Exception {
        CodeExchange.register(location.coordinationDirectory(), null);
        codeSide.close();
        if (previousUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousUserHome);
        }
    }

    private static Map<String, Object> call(McpProtocolHandler handler, String tool, Map<String, Object> arguments) {
        String frame = handler.handleMessage(ProviderJson.write(Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call", "params", Map.of("name", tool, "arguments", arguments))));
        Map<String, Object> result = (Map<String, Object>) ((Map<String, Object>) ProviderJson.parse(frame)).get("result");
        return (Map<String, Object>) ProviderJson.parse((String) ((Map<String, Object>) ((List<?>) result.get("content")).getFirst()).get("text"));
    }

    @Test
    void anAgentOfAMemberWithNoComputerConnectedGetsALaneOnItsOwnMainAndIsNotSentToAPerson() throws Exception {
        String ownMain = GitProcessRunner.run(root, "rev-parse", "HEAD");
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), root, "codex", "conn-cut-off");

        Map<String, Object> ensured = call(handler, "ensure_session", Map.of("task", Map.of("goal", "Write a.py", "acceptance", "a.py exists", "claims", List.of(Map.of("path", "a.py")))));

        assertEquals("ready", ensured.get("status"), ensured.toString());
        assertEquals(ownMain, new ProviderSessionBindingService().find(location, "codex", "conn-cut-off").orElseThrow().baseCommit(), "the lane starts from the member's own main");
        Map<String, Object> next = call(handler, "get_next_action", Map.of());
        Map<String, Object> workflow = (Map<String, Object>) ((Map<String, Object>) next.get("result")).get("workflow");
        assertNotEquals("request_human_help", next.get("nextAction"), next.toString());
        assertNotEquals("INTEGRATION_REPAIR", workflow.get("type"), next.toString());
        assertEquals(true, workflow.get("retrySafe"), next.toString());
    }
}
