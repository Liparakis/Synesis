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

    /** Capability handoff does not fence the owner's already-authorized lane. */
    @Test
    void capabilityOwnerMayImplementAfterRequestAcceptance() {
        AgentWorkflowReducer reducer = new AgentWorkflowReducer();
        AgentNextActionService.NextActionRequest request = new AgentNextActionService.NextActionRequest(
                Path.of("."), "codex", "connection-1");

        AgentResponse ownerReview = reducer.decorate(request,
                new AgentResponse(AgentStatus.READY, null, AgentNextAction.RESPOND_COORDINATION,
                        Map.of("capabilityRequestHandle", "req_123456789012", "capability", "task-tracker")));
        Map<?, ?> reviewWorkflow = (Map<?, ?>) ((Map<?, ?>) ownerReview.result()).get("workflow");
        assertTrue(((java.util.List<?>) reviewWorkflow.get("permittedOperations")).contains("apply_patch"));

        AgentResponse implementation = reducer.decorate(request,
                new AgentResponse(AgentStatus.WAITING, AgentReason.IMPLEMENTATION_UNAVAILABLE,
                        AgentNextAction.WAIT, Map.of("capabilityRequestHandle", "req_123456789012")));
        Map<?, ?> implementationWorkflow = (Map<?, ?>) ((Map<?, ?>) implementation.result()).get("workflow");
        assertEquals("IMPLEMENT", implementationWorkflow.get("type"));
        assertTrue(((java.util.List<?>) implementationWorkflow.get("permittedOperations"))
                .contains("publish_capability_implementation"));
    }

    /** A publication-required response recommends the existing finish lane tool. */
    @Test
    void snapshotPublicationUsesExistingFinishLaneTool() {
        AgentWorkflowReducer reducer = new AgentWorkflowReducer();
        AgentNextActionService.NextActionRequest request = new AgentNextActionService.NextActionRequest(
                Path.of("."), "codex", "connection-1");

        AgentResponse publication = reducer.decorate(request,
                new AgentResponse(AgentStatus.READY, AgentReason.SNAPSHOT_PUBLICATION_REQUIRED,
                        AgentNextAction.FINISH_LANE,
                        Map.of("snapshotPublicationRequired", true, "workGroupId", "group-1",
                                "nextProtocolPayload", Map.of("summary", "Publish the completed immutable snapshot"))));
        Map<?, ?> result = (Map<?, ?>) publication.result();
        Map<?, ?> workflow = (Map<?, ?>) result.get("workflow");
        Map<?, ?> arguments = (Map<?, ?>) workflow.get("arguments");

        assertEquals("PUBLISH", workflow.get("type"));
        assertTrue(((java.util.List<?>) workflow.get("permittedOperations")).contains("finish_lane"));
        assertEquals("finish_lane", workflow.get("recommendedTool"));
        assertEquals("Publish the completed immutable snapshot", arguments.get("summary"));
    }
}
