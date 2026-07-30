package org.synesis.workspace.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.agent.AgentNextActionService;
import org.synesis.workspace.application.agent.AgentWorkflowReducer;

/** Verifies stable, transport-neutral durable workflow action metadata. */
final class AgentWorkflowReducerTest {

    /** Verifies repeated retrieval derives the same action identity and policy. */
    @Test
    void derivesStableAtLeastOnceAction() {
        AgentWorkflowReducer reducer = new AgentWorkflowReducer();
        AgentNextActionService.NextActionRequest request = new AgentNextActionService.NextActionRequest(
                Path.of("."), "codex", "connection-1");
        AgentResponse input = new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING,
                AgentNextAction.WAIT, Map.of("pending", 1, "request", "request-1"));

        AgentResponse first = reducer.decorate(request, input);
        AgentResponse second = reducer.decorate(request, input);
        Map<?, ?> firstResult = (Map<?, ?>) first.result();
        Map<?, ?> secondResult = (Map<?, ?>) second.result();
        assertEquals(firstResult.get("actionId"), secondResult.get("actionId"));
        assertEquals("AT_LEAST_ONCE", firstResult.get("delivery"));
        assertEquals(Boolean.TRUE, firstResult.get("acknowledgementRequired"));
        Map<?, ?> workflow = (Map<?, ?>) firstResult.get("workflow");
        assertEquals("WAIT", workflow.get("type"));
        assertTrue((Boolean) workflow.get("retrySafe"));
    }

    /** Terminal and recovery lane states produce autonomous lifecycle actions. */
    @Test
    void derivesCloseAndRecoverLifecycleActions() {
        AgentWorkflowReducer reducer = new AgentWorkflowReducer();
        AgentNextActionService.NextActionRequest request = new AgentNextActionService.NextActionRequest(
                Path.of("."), "codex", "connection-1");
        AgentResponse terminal = reducer.decorate(request,
                new AgentResponse(AgentStatus.READY, null, null,
                        Map.of("intents", java.util.List.of(Map.of("status", "COMPLETED")))));
        AgentResponse recovery = reducer.decorate(request,
                new AgentResponse(AgentStatus.READY, null, null,
                        Map.of("intents", java.util.List.of(Map.of("status", "RECOVERY_HELD")))));
        assertEquals("CLOSE", ((Map<?, ?>) ((Map<?, ?>) terminal.result()).get("workflow")).get("type"));
        assertEquals("RECOVER", ((Map<?, ?>) ((Map<?, ?>) recovery.result()).get("workflow")).get("type"));
    }
}
