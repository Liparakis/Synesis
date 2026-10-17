package org.synesis.workspace.application.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.run.LaneWake;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * The Claude Code hook command answers a Stop event as well as a PreToolUse one (ADR-0099): it holds an agent of a
 * run that was told WAIT until something arrives for its lane, and lets every other agent stop at once.
 */
@Timeout(60)
class HookClaudeStopTest {

    @TempDir
    Path temp;

    private static String stop(Path cwd) {
        return ProviderJson.write(Map.of("hook_event_name", "Stop", "session_id", "s-1", "stop_hook_active", false, "cwd", cwd.toString()));
    }

    private static HookApplicationService.HookExecutionResult event(String json, String connection) {
        return new HookApplicationService().claudeCode(Path.of("profile"), json, connection, Duration.ofSeconds(30));
    }

    private static String bash(String command) {
        return ProviderJson.write(Map.of("hook_event_name", "PreToolUse", "tool_name", "Bash", "cwd", "/p", "tool_input", Map.of("command", command)));
    }

    @Test
    void aStopEventOfAnAgentNoRunStartedIsLetStopAtOnce() {
        HookApplicationService.HookExecutionResult result = event(stop(temp), null);
        assertEquals("STOP_ALLOWED", result.outcome());
        assertEquals("{}", result.responseJson());
    }

    @Test
    void theHookCommandRoutesAStopEventAndNeverDeniesIt() {
        // No connection identity in this test's environment, as in an interactive session.
        HookApplicationService.HookExecutionResult result = new HookApplicationService().claudeCode(temp.resolve("profile"),
                new ByteArrayInputStream(stop(temp).getBytes(StandardCharsets.UTF_8)));
        assertEquals("{}", result.responseJson());
    }

    @Test
    void aRunAgentIsRefusedAShellCommandThatOnlySleepsAndAPersonIsNot() {
        HookApplicationService.HookExecutionResult refused = event(bash("python -c \"import time; time.sleep(590)\""), "conn-writer");
        assertEquals("BLOCKED", refused.outcome());
        Map<?, ?> output = (Map<?, ?>) ((Map<?, ?>) ProviderJson.parse(refused.responseJson())).get("hookSpecificOutput");
        assertEquals("deny", output.get("permissionDecision"));
        assertTrue(String.valueOf(output.get("permissionDecisionReason")).contains("end your turn"));

        // A person waiting for their dev server is not told Synesis will wake them.
        assertEquals("UNSUPPORTED", event(bash("sleep 3"), null).outcome());
        assertEquals("UNSUPPORTED", event(bash("sleep 2 && python -m unittest"), "conn-writer").outcome());
    }

    @Test
    void aWaitingAgentIsHeldUntilARequestForItsLaneThenToldToCallGetNextAction() throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        var identity = new IdentityBootstrap(location.linkDirectory()).loadOrCreate().identity();
        UUID intentId = UUID.randomUUID();
        WorkIntent writer = new WorkIntent(intentId, location.projectId(), "agt-writer", "claude", UUID.randomUUID(), "Build slug.py", "It works", "base",
                List.of(ResourceSelector.pathExact("slug.py")), 1, UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of());
        assertTrue(new WorkIntentService(location.openCoordinationStore(), identity).announce(writer).acquired());
        LaneWake.noteWait(location, "conn-writer", "agt-writer", null, java.util.OptionalLong.empty());
        new WorkIntentService(location.openCoordinationStore(), identity).request("agt-reviewer", intentId, CoordinationRequest.Kind.CONTRACT, "May I review slug.py?");

        HookApplicationService.HookExecutionResult result = event(stop(root), "conn-writer");

        assertEquals("WOKEN", result.outcome());
        Map<?, ?> answer = (Map<?, ?>) ProviderJson.parse(result.responseJson());
        assertEquals("block", answer.get("decision"));
        assertTrue(String.valueOf(answer.get("reason")).contains("Call get_next_action"), result.responseJson());
    }
}
