package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * A finish whose argument was copied wrong is refused naming that argument, not as stale.
 *
 * <p>In the PC and VM run s1-run4 (ADR-0098 step 4) a Codex agent finished its tested lane with
 * {@code participant} {@code agt_57611847-333e-3bd7-b28c-04bba9c6f9dc} where the projection said
 * {@code ...-b28d-04bba9c6f9dc}. Every other argument matched the projection and the log had not moved, yet the
 * answer was {@code SNAPSHOT_COMPLETION_EVIDENCE_STALE}, whose recovery projects the same action again. The agent
 * retyped the same wrong value three times and stopped with its work unpublished.</p>
 */
final class McpFinishEvidenceMismatchTest {

    private String previousHome;

    @BeforeEach
    void isolateProviderManual() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("synesis-finish-mismatch-home-").toString());
    }

    @AfterEach
    void restoreProviderManualHome() {
        if (previousHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void aMistypedParticipantIsNamedAndTheCorrectedFinishPublishes(@TempDir Path temp) throws Exception {
        Lane lane = producerLaneReadyToFinish(temp);
        String participant = String.valueOf(lane.finishArguments().get("participant"));
        Map<String, Object> mistyped = new LinkedHashMap<>(lane.finishArguments());
        mistyped.put("participant", participant.substring(0, participant.length() - 1) + (participant.endsWith("c") ? "d" : "c"));

        Map<String, Object> refused = innerResult(lane.handler().handleMessage(toolCall("finish_lane", mistyped)));

        Map<String, Object> result = map(refused.get("result"));
        assertEquals("SNAPSHOT_COMPLETION_EVIDENCE_MISMATCH", result.get("reason"), refused.toString());
        assertEquals("participant", result.get("field"), refused.toString());
        assertEquals(participant, result.get("expected"), refused.toString());
        assertEquals("retry", refused.get("nextAction"), refused.toString());
        assertNotNull(result.get("instruction"), "the general refusal guidance tells the agent to correct the named field");
        assertTrue(lane.store().taskCompletionProjection().allSnapshots().isEmpty(), "nothing was published");

        Map<String, Object> published = innerResult(lane.handler().handleMessage(toolCall("finish_lane", lane.finishArguments())));

        assertEquals(1, lane.store().taskCompletionProjection().allSnapshots().size(), published.toString());
    }

    @Test
    void aMistypedWorkGroupIsNamed(@TempDir Path temp) throws Exception {
        Lane lane = producerLaneReadyToFinish(temp);
        Map<String, Object> mistyped = new LinkedHashMap<>(lane.finishArguments());
        mistyped.put("workGroupId", UUID.randomUUID().toString());

        Map<String, Object> refused = innerResult(lane.handler().handleMessage(toolCall("finish_lane", mistyped)));

        Map<String, Object> result = map(refused.get("result"));
        assertEquals("SNAPSHOT_COMPLETION_EVIDENCE_MISMATCH", result.get("reason"), refused.toString());
        assertEquals("workGroupId", result.get("field"), refused.toString());
        assertEquals(lane.finishArguments().get("workGroupId"), result.get("expected"), refused.toString());
        assertTrue(lane.store().taskCompletionProjection().allSnapshots().isEmpty(), "nothing was published");
    }

    private static Lane producerLaneReadyToFinish(Path temp) throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project);
        GitProcessRunner.run(project, "init");
        GitProcessRunner.run(project, "config", "user.name", "Finish Mismatch Test");
        GitProcessRunner.run(project, "config", "user.email", "finish-mismatch@example.test");
        Files.writeString(project.resolve("README.md"), "# Number toolkit\n");
        GitProcessRunner.run(project, "add", ".");
        GitProcessRunner.run(project, "commit", "-m", "baseline");
        ProjectLocation location = new ProjectApplicationService().init(project).location();
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        String connection = "finish-mismatch-producer";
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), project, "codex", connection);
        Map<String, Object> task = Map.of("goal", "Implement the primes package with tests", "acceptance", "primes/primes.py with is_prime", "claims",
                List.of(Map.of("kind", "path_subtree", "path", "primes")));
        Map<String, Object> assigned = innerResult(handler.handleMessage(toolCall("ensure_session", Map.of("task", task))));
        assertTrue(List.of("ready", "completed").contains(assigned.get("status")), assigned.toString());
        Path worktree = Path.of(new ProviderSessionBindingService().find(location, "codex", connection).orElseThrow().worktreePath());
        Files.createDirectories(worktree.resolve("primes"));
        Files.writeString(worktree.resolve("primes/primes.py"), "def is_prime(n):\n    return n > 1 and all(n % d for d in range(2, int(n ** 0.5) + 1))\n");
        Map<String, Object> publication = innerResult(handler.handleMessage(toolCall("get_next_action", Map.of("completionRequested", true))));
        Map<String, Object> workflow = map(map(publication.get("result")).get("workflow"));
        assertEquals("finish_lane", workflow.get("recommendedTool"), publication.toString());
        return new Lane(location, handler, map(workflow.get("arguments")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> innerResult(String response) {
        Map<String, Object> outer = (Map<String, Object>) ProviderJson.parse(response);
        Map<String, Object> rpcResult = (Map<String, Object>) outer.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) rpcResult.get("content");
        return (Map<String, Object>) ProviderJson.parse((String) content.getFirst().get("text"));
    }

    private static String toolCall(String name, Map<String, Object> arguments) {
        return ProviderJson.write(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", name, "arguments", arguments)));
    }

    /** One producer lane with work written, and the finish arguments its completion request projected. */
    private record Lane(ProjectLocation location, McpProtocolHandler handler, Map<String, Object> finishArguments) {

        private PredictionEventStore store() throws Exception {
            return new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        }
    }
}
