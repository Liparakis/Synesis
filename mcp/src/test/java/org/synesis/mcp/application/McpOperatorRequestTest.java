package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * A control checkout holding a file Synesis did not make (ADR-0091): the operator is asked, the
 * finished work waits instead of failing, only the lane waiting on integration is stopped, and
 * the work is integrated once the file is gone with nothing asked of the agent.
 */
class McpOperatorRequestTest {

    private String previousUserHome;
    private Path root;
    private ProjectLocation location;
    private McpProtocolHandler agent;

    @BeforeEach
    void setUp() throws Exception {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("synesis-mcp-operator-home-").toString());
        root = Files.createTempDirectory("synesis-mcp-operator-");
        GitProcessRunner.run(root, "init");
        GitProcessRunner.run(root, "config", "user.name", "Test User");
        GitProcessRunner.run(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "# Test Repo\n");
        GitProcessRunner.run(root, "add", ".");
        GitProcessRunner.run(root, "commit", "-m", "Initial commit");
        location = new ProjectApplicationService().init(root).location();
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        agent = new McpProtocolHandler(new AgentSessionService(), root, "codex", "conn-operator");
        assertEquals("ready", call("ensure_session", Map.of()).get("status"));
    }

    @AfterEach
    void restoreUserHome() {
        if (previousUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousUserHome);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String tool, Map<String, Object> arguments) {
        String frame = agent.handleMessage(ProviderJson.write(Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call", "params", Map.of("name", tool, "arguments", arguments))));
        Map<String, Object> result = (Map<String, Object>) ((Map<String, Object>) ProviderJson.parse(frame)).get("result");
        return (Map<String, Object>) ProviderJson.parse((String) ((Map<String, Object>) ((List<?>) result.get("content")).getFirst()).get("text"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> response) {
        return (Map<String, Object>) response.get("result");
    }

    private PredictionEventStore replayed() throws Exception {
        return new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
    }

    /** Announces a lane on one file, writes it, and finishes with the arguments Synesis projects. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> implementAndFinish() {
        Map<String, Object> announced = call("ensure_session", Map.of("task", Map.of("goal", "Add the module", "acceptance", "The module exists", "claims", List.of(Map.of("path", "app.py")))));
        assertEquals("ready", announced.get("status"), announced.toString());
        Map<String, Object> written = call("apply_patch", Map.of("path", "app.py", "create", true, "content", "VALUE = 1\n"));
        assertEquals("completed", written.get("status"), written.toString());
        Map<String, Object> projected = call("get_next_action", Map.of("completionRequested", true));
        assertEquals("finish_lane", projected.get("nextAction"), projected.toString());
        return call("finish_lane", (Map<String, Object>) result(projected).get("nextProtocolPayload"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStrayFileInTheControlCheckoutAsksForAnOperatorAndTheWorkIsIntegratedOnceItIsGone() throws Exception {
        Files.writeString(root.resolve("stray.py"), "left here by a provider's own file tool\n");

        Map<String, Object> finished = implementAndFinish();

        assertEquals("blocked", finished.get("status"), finished.toString());
        assertEquals("operator_required", finished.get("reason"), finished.toString());
        Map<String, Object> shown = (Map<String, Object>) result(finished).get("operatorRequest");
        assertEquals(List.of("?? stray.py"), shown.get("evidence"), "the operator is shown what git reports, unchanged: " + finished);
        assertTrue(String.valueOf(result(finished).get("instruction")).contains("Do not change, move or delete"), finished.toString());

        PredictionEventStore blocked = replayed();
        OperatorRequest request = blocked.operatorRequestProjection().open(OperatorRequest.CONTROL_CHECKOUT_FOREIGN_CHANGES).orElseThrow();
        assertEquals(shown.get("id"), request.id());
        var snapshot = blocked.taskCompletionProjection().allSnapshots().getFirst();
        TaskCompletionState waiting = blocked.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElseThrow();
        assertTrue(waiting.awaitsIntegration(), "a refusal by the checkout must not fail the work: " + waiting);
        assertFalse(Files.exists(root.resolve("app.py")));

        // The lane is told why it waits and to stop, not to go on implementing.
        Map<String, Object> next = call("get_next_action", Map.of());
        assertEquals("operator_required", next.get("reason"), next.toString());
        Map<String, Object> workflow = (Map<String, Object>) result(next).get("workflow");
        assertEquals("OPERATOR_REQUIRED", workflow.get("type"), next.toString());
        assertEquals(1, ((Number) workflow.get("maxUnchangedPolls")).intValue(), next.toString());
        assertEquals(List.of("get_next_action"), workflow.get("permittedOperations"), next.toString());
        assertEquals(1, replayed().operatorRequestProjection().all().size(), "one stray file is one request however often it is seen");

        Files.delete(root.resolve("stray.py"));
        Map<String, Object> after = call("get_next_action", Map.of());

        assertNotEquals("operator_required", after.get("reason"), after.toString());
        PredictionEventStore cleared = replayed();
        assertTrue(cleared.operatorRequestProjection().open().isEmpty(), "the request closes on what the checkout shows");
        assertEquals(OperatorRequest.State.CLOSED, cleared.operatorRequestProjection().all().getFirst().state());
        assertEquals(TaskCompletionState.INTEGRATED, cleared.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElseThrow());
        assertEquals("VALUE = 1\n", Files.readString(root.resolve("app.py")).replace("\r\n", "\n"));
    }

    /** The request stops integration, not every lane: one that still has work is not shown it. */
    @Test
    void aLaneThatStillHasWorkIsNotStoppedByTheRequest() throws Exception {
        Files.writeString(root.resolve("stray.py"), "left here\n");
        Map<String, Object> announced = call("ensure_session", Map.of("task", Map.of("goal", "Add the module", "acceptance", "The module exists", "claims", List.of(Map.of("path", "app.py")))));
        assertEquals("ready", announced.get("status"), announced.toString());

        Map<String, Object> next = call("get_next_action", Map.of());

        assertNotEquals("operator_required", next.get("reason"), next.toString());
        assertNotEquals("blocked", next.get("status"), next.toString());
        assertEquals(1, replayed().operatorRequestProjection().open().size(), "the operator is asked as soon as the file is seen");
        Map<String, Object> written = call("apply_patch", Map.of("path", "app.py", "create", true, "content", "VALUE = 1\n"));
        assertEquals("completed", written.get("status"), written.toString());
    }
}
