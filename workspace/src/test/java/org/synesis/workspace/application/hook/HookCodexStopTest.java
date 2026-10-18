package org.synesis.workspace.application.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The Codex hook command answers the Stop event of the hook a run controller gives its Codex agents as Claude Code's
 * does (ADR-0099, 2026-10-18): it holds an agent told WAIT until something arrives for its lane, and lets every other
 * agent stop at once. Codex sends the same event and reads the same answer.
 */
@Timeout(60)
class HookCodexStopTest {

    @TempDir
    Path temp;

    private static String stop(Path cwd) {
        return ProviderJson.write(Map.of("hook_event_name", "Stop", "session_id", "s-1", "turn_id", "t-1", "stop_hook_active", false, "cwd", cwd.toString(), "last_assistant_message", "WAIT"));
    }

    private static HookApplicationService.HookExecutionResult event(String json, String connection) {
        return new HookApplicationService().codex(json, connection, Duration.ofSeconds(30));
    }

    @Test
    void aStopEventOfACodexAgentNoRunStartedIsLetStopAtOnce() {
        HookApplicationService.HookExecutionResult result = event(stop(temp), null);
        assertEquals("STOP_ALLOWED", result.outcome());
        assertEquals("{}", result.responseJson());
    }

    @Test
    void aWaitingCodexAgentIsHeldUntilARequestForItsLaneThenToldToCallGetNextAction() throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        var identity = new IdentityBootstrap(location.linkDirectory()).loadOrCreate().identity();
        UUID intentId = UUID.randomUUID();
        WorkIntent writer = new WorkIntent(intentId, location.projectId(), "agt-writer", "codex", UUID.randomUUID(), "Build slug.py", "It works", "base",
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
