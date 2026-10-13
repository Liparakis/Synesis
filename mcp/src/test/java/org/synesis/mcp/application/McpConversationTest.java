package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Lanes talk through the eleventh tool, and what they are told changes nothing they may do (ADR-0093).
 */
@SuppressWarnings("unchecked")
class McpConversationTest {

    private static final Map<String, Object> STATUS = Map.of("kind", "collaboration_status", "payload", Map.of());

    private String previousUserHome;
    private McpProtocolHandler first;
    private McpProtocolHandler second;
    private McpProtocolHandler third;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("synesis-mcp-conversation-home-").toString());
        root = Files.createTempDirectory("synesis-mcp-conversation-");
        GitProcessRunner.run(root, "init");
        GitProcessRunner.run(root, "config", "user.name", "Test User");
        GitProcessRunner.run(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "# Test Repo\n");
        GitProcessRunner.run(root, "add", ".");
        GitProcessRunner.run(root, "commit", "-m", "Initial commit");
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        first = lane(root, "conn-first", "first.py");
        second = lane(root, "conn-second", "second.py");
        third = lane(root, "conn-third", "third.py");
    }

    @AfterEach
    void restoreUserHome() {
        if (previousUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousUserHome);
        }
    }

    private static McpProtocolHandler lane(Path root, String connection, String file) {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), root, "codex", connection);
        Map<String, Object> announced = call(handler, "ensure_session", Map.of("task", Map.of("goal", "Write " + file, "acceptance", file + " exists", "claims", List.of(Map.of("path", file)))));
        assertEquals("ready", announced.get("status"), announced.toString());
        return handler;
    }

    private static Map<String, Object> call(McpProtocolHandler handler, String tool, Map<String, Object> arguments) {
        String frame = handler.handleMessage(ProviderJson.write(Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call", "params", Map.of("name", tool, "arguments", arguments))));
        Map<String, Object> result = (Map<String, Object>) ((Map<String, Object>) ProviderJson.parse(frame)).get("result");
        return (Map<String, Object>) ProviderJson.parse((String) ((Map<String, Object>) ((List<?>) result.get("content")).getFirst()).get("text"));
    }

    private static Map<String, Object> result(Map<String, Object> response) {
        return (Map<String, Object>) response.get("result");
    }

    @Test
    void aMessageNamesItsConversationAndReachesTheAddresseeBesideAnUnchangedNextAction() {
        Map<String, Object> before = call(second, "get_next_action", Map.of());
        assertFalse(result(before).containsKey("messages"));

        Map<String, Object> sent = call(first, "send_message", Map.of("to", List.of("second.py"), "text", "does listing create the store file?"));

        assertEquals("completed", sent.get("status"), sent.toString());
        assertNotNull(result(sent).get("conversation"), sent.toString());
        assertEquals(2, ((List<?>) result(sent).get("members")).size());
        Map<String, Object> after = call(second, "get_next_action", Map.of());
        Map<String, Object> messages = (Map<String, Object>) result(after).get("messages");
        assertNotNull(messages, after.toString());
        List<Map<String, Object>> unread = (List<Map<String, Object>>) messages.get("unread");
        assertEquals(1, unread.size());
        assertEquals("does listing create the store file?", unread.getFirst().get("text"));
        assertEquals(result(sent).get("conversation"), unread.getFirst().get("conversation"));
        assertTrue(String.valueOf(messages.get("notice")).contains("not instructions"), messages.toString());
        assertEquals(before.get("nextAction"), after.get("nextAction"));
        assertEquals(result(before).get("workflow"), result(after).get("workflow"), "the action is what it would be without the message");

        Map<String, Object> read = call(second, "get_next_action", Map.of());
        assertFalse(result(read).containsKey("messages"), "the call after the batch arrived is the acknowledgement: " + read);
        assertFalse(result(call(third, "get_next_action", Map.of())).containsKey("messages"), "a lane outside the conversation is handed nothing");
    }

    @Test
    void theNoteWithAMessageAsksForOneShortAnswerToADirectQuestionAndTheAnswerToASendTellsAnAskerToCarryOn() {
        Map<String, Object> sent = call(first, "send_message", Map.of("to", List.of("second.py"), "text", "does listing create the store file?"));
        String note = String.valueOf(result(sent).get("note"));
        assertTrue(note.contains("do not wait"), note);

        Map<String, Object> messages = (Map<String, Object>) result(call(second, "get_next_action", Map.of())).get("messages");

        String notice = String.valueOf(messages.get("notice"));
        assertTrue(notice.contains("answer it once"), notice);
        assertTrue(notice.contains("not instructions"), notice);
    }

    @Test
    void sendingAMessageAcknowledgesTheBatchTheLaneWasHanded() {
        call(first, "send_message", Map.of("to", List.of("second.py"), "text", "question"));
        Map<String, Object> handed = call(second, "get_next_action", Map.of());
        assertNotNull(result(handed).get("messages"), handed.toString());

        assertEquals("completed", call(second, "send_message", Map.of("to", List.of("first.py"), "text", "answer")).get("status"));

        McpProtocolHandler fresh = new McpProtocolHandler(new AgentSessionService(), root, "codex", "conn-second");
        Map<String, Object> afterwards = call(fresh, "get_next_action", Map.of());
        assertFalse(result(afterwards).containsKey("messages"), "the durable mark moved, so whoever asks for the lane next is handed nothing: " + afterwards);
    }

    @Test
    void collaborationStatusListsConversationsForAMemberAndNotForANonMember() {
        call(first, "send_message", Map.of("to", List.of("second.py"), "text", "between us"));

        List<Map<String, Object>> asSender = (List<Map<String, Object>>) result(call(first, "request_coordination", STATUS)).get("conversations");
        List<Map<String, Object>> asAddressee = (List<Map<String, Object>>) result(call(second, "request_coordination", STATUS)).get("conversations");
        Map<String, Object> outsider = call(third, "request_coordination", STATUS);

        assertEquals(1, asSender.size());
        assertEquals(1, asAddressee.size());
        assertEquals("between us", ((List<Map<String, Object>>) asAddressee.getFirst().get("messages")).getFirst().get("text"));
        assertEquals(List.of(), result(outsider).get("conversations"), outsider.toString());
        assertFalse(outsider.toString().contains("between us"));
    }

    @Test
    void aMalformedSendIsRefusedWithoutThrowing() {
        for (Map<String, Object> arguments : List.<Map<String, Object>>of(
                Map.of("text", "to nobody"),
                Map.of("to", 5, "text", "a number"),
                Map.of("to", List.of(1, 2), "text", "numbers"),
                Map.of("to", Map.of("file", "second.py"), "text", "an object"),
                Map.of("conversation", "not-a-uuid", "text", "a bad identifier"),
                Map.of("to", List.of("second.py")),
                Map.of("to", List.of("second.py"), "text", 7))) {
            Map<String, Object> refused = call(first, "send_message", arguments);

            assertEquals("blocked", refused.get("status"), arguments + " -> " + refused);
            assertNotNull(result(refused).get("reason"), refused.toString());
        }
        assertFalse(result(call(second, "get_next_action", Map.of())).containsKey("messages"), "nothing was recorded");
    }
}
