package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * A caller told to wait is held inside Synesis until the project changes or the hold passes, and
 * is then answered with the action the projection gives, never a different kind of answer.
 *
 * <p>The waiting caller is an owner whose finished work waits for a reviewer to take its grant;
 * the reviewer taking it is what changes the owner's next action.</p>
 */
@SuppressWarnings("unchecked")
class McpWaitHoldTest {

    private String previousUserHome;
    private McpProtocolHandler owner;
    private McpProtocolHandler reviewer;

    @BeforeEach
    void setUp() throws Exception {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("synesis-mcp-wait-home-").toString());
        Path project = Files.createTempDirectory("synesis-mcp-wait-");
        GitProcessRunner.run(project, "init");
        GitProcessRunner.run(project, "config", "user.name", "Test User");
        GitProcessRunner.run(project, "config", "user.email", "test@example.com");
        Files.writeString(project.resolve("todo.py"), "def add_todo(items, item):\n    return [*items, item]\n");
        GitProcessRunner.run(project, "add", ".");
        GitProcessRunner.run(project, "commit", "-m", "baseline");
        ProjectLocation location = new ProjectApplicationService().init(project).location();
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new SessionResolutionRequest(project, "codex", "wait-owner", null, false));
        sessions.ensureSession(new SessionResolutionRequest(project, "codex", "wait-reviewer", null, false));
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        for (var binding : bindings.list(location, "codex")) {
            if (binding.worktreePath() != null) {
                bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
            }
        }
        Path ownerWorktree = Path.of(bindings.find(location, "codex", "wait-owner").orElseThrow().worktreePath());
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claim = collaboration.announce(project, "codex", "wait-owner", "Implement Todo", "Review the completed Todo snapshot", List.of(ResourceSelector.pathExact("todo.py")));
        collaboration.announce(project, "codex", "wait-reviewer", "Review Todo", "Review the completed Todo snapshot", List.of(ResourceSelector.pathExact("test_todo.py")),
                claim.intent().workGroupId(), WorkIntent.Role.REVIEWER, List.of(ResourceSelector.pathExact("todo.py")));
        owner = new McpProtocolHandler(sessions, project, "codex", "wait-owner");
        reviewer = new McpProtocolHandler(sessions, project, "codex", "wait-reviewer");

        call(reviewer, "request_coordination", Map.of("kind", "work_group_join", "payload",
                Map.of("workGroupId", claim.intent().workGroupId().toString(), "intentId", claim.intent().intentId().toString(), "proposal", "Review the published Todo snapshot")));
        Map<String, Object> admission = (Map<String, Object>) ((Map<String, Object>) result(call(owner, "get_next_action", Map.of())).get("workflow")).get("arguments");
        assertEquals("completed", call(owner, "respond_coordination", admission).get("status"));
        Files.writeString(ownerWorktree.resolve("todo.py"), "def add_todo(items, item):\n    return [*items, item]\n\ndef complete_todo(items, item):\n    return [value for value in items if value != item]\n");
    }

    @AfterEach
    void restoreUserHome() {
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

    private static Map<String, Object> result(Map<String, Object> response) {
        return (Map<String, Object>) response.get("result");
    }

    /** The reviewer takes the grant the owner's finished work waits for. */
    private void reviewerTakesTheGrant() {
        Map<String, Object> status = result(call(reviewer, "request_coordination", Map.of("kind", "collaboration_status", "payload", Map.of())));
        Map<String, Object> grant = ((List<Map<String, Object>>) status.get("grants")).getFirst();
        Map<String, Object> consumed = call(reviewer, "request_coordination", Map.of("kind", "work_group_join", "payload", Map.of(
                "workGroupId", grant.get("workGroupId"), "grantId", grant.get("grantId"), "intentId", grant.get("targetIntentId"), "claimEpoch", grant.get("claimEpoch"),
                "targetParticipant", grant.get("targetParticipant"))));
        assertEquals("completed", consumed.get("status"), consumed.toString());
    }

    private static long millisSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    @Test
    void withoutAHoldAWaitIsAnsweredAtOnce() {
        long started = System.nanoTime();
        Map<String, Object> answer = call(owner, "get_next_action", Map.of());

        assertEquals("wait", answer.get("nextAction"), answer.toString());
        assertTrue(millisSince(started) < 3_000L, "answered at once: " + millisSince(started));
    }

    @Test
    void aCallerToldToWaitWithAnUnreadMessageIsAnsweredAtOnceWithTheMessage() {
        owner.holdWaitsFor(Duration.ofSeconds(8));
        assertEquals("completed", call(reviewer, "send_message", Map.of("to", List.of("todo.py"), "text", "is the snapshot ready?")).get("status"));
        long started = System.nanoTime();

        Map<String, Object> answer = call(owner, "get_next_action", Map.of());

        long took = millisSince(started);
        assertEquals("wait", answer.get("nextAction"), answer.toString());
        assertTrue(String.valueOf(result(answer).get("messages")).contains("is the snapshot ready?"), "the message came with the wait: " + answer);
        assertTrue(took < 4_000L, "not held for the eight seconds: " + took);
    }

    @Test
    void aCallThatIsNotGetNextActionIsNeverHeld() {
        owner.holdWaitsFor(Duration.ofSeconds(8));
        long started = System.nanoTime();

        Map<String, Object> status = call(owner, "request_coordination", Map.of("kind", "collaboration_status", "payload", Map.of()));

        long took = millisSince(started);
        assertEquals("completed", status.get("status"), status.toString());
        assertTrue(took < 4_000L, "not held for the eight seconds: " + took);
    }

    @Test
    void aWaitWhereNothingChangesIsAnsweredAfterTheHoldWithTheSameWaitItWouldGetWithoutOne() {
        Map<String, Object> unheld = call(owner, "get_next_action", Map.of());
        assertEquals("wait", unheld.get("nextAction"), unheld.toString());
        owner.holdWaitsFor(Duration.ofSeconds(2));
        long started = System.nanoTime();

        Map<String, Object> held = call(owner, "get_next_action", Map.of());

        long took = millisSince(started);
        assertTrue(took >= 1_800L, "held for about the two seconds: " + took);
        assertTrue(took < 6_000L, "and not for much longer: " + took);
        assertEquals(unheld.get("nextAction"), held.get("nextAction"));
        assertEquals(unheld.get("reason"), held.get("reason"));
        assertEquals(result(unheld).get("workflow"), result(held).get("workflow"));
    }

    @Test
    void whenTheProjectChangesDuringTheHoldTheCallerIsAnsweredEarlyWithTheNewActionNotTheWait() throws Exception {
        owner.holdWaitsFor(Duration.ofSeconds(10));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                Thread.sleep(600L);
                reviewerTakesTheGrant();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        long started = System.nanoTime();
        other.start();

        Map<String, Object> held = call(owner, "get_next_action", Map.of());

        long took = millisSince(started);
        other.join();
        assertEquals(null, failure.get(), String.valueOf(failure.get()));
        assertTrue(took < 8_000L, "answered long before the ten seconds: " + took);
        assertNotEquals("wait", held.get("nextAction"), held.toString());
        owner.holdWaitsFor(Duration.ZERO);
        Map<String, Object> now = call(owner, "get_next_action", Map.of());
        assertEquals(now.get("nextAction"), held.get("nextAction"), "the answer is the one the projection gives now");
        assertEquals(result(now).get("workflow"), result(held).get("workflow"), "projected after the change, not before it");
    }
}
