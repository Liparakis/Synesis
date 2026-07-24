package org.synesis.workspace.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentResponseTest {

    @Test
    void testCompletedResponseSerializationOmitsNullFields() {
        AgentResponse response = AgentResponse.completed("src/example.txt");
        assertEquals(AgentStatus.COMPLETED, response.status());

        String json = response.toJson();
        assertNotNull(json);
        assertTrue(json.contains("\"status\":\"completed\""));
        assertTrue(json.contains("\"result\":{\"path\":\"src/example.txt\"}"));
        assertFalse(json.contains("\"reason\""));
        assertFalse(json.contains("\"nextAction\""));
        assertFalse(json.contains("projectId"));
        assertFalse(json.contains("sessionId"));
        assertFalse(json.contains("worktree"));
        assertFalse(json.contains("decisionId"));
        assertFalse(json.contains("evidence"));
    }

    @Test
    void testBlockedResponseSerializationOmitsResultAndNextAction() {
        AgentResponse response = AgentResponse.blocked(AgentReason.PROTECTED_CONFIGURATION);
        assertEquals(AgentStatus.BLOCKED, response.status());
        assertEquals(AgentReason.PROTECTED_CONFIGURATION, response.reason());

        String json = response.toJson();
        assertTrue(json.contains("\"status\":\"blocked\""));
        assertTrue(json.contains("\"reason\":\"protected_configuration\""));
        assertFalse(json.contains("\"nextAction\""));
        assertFalse(json.contains("\"result\""));
    }

    @Test
    void testNeedsCapabilityResponseSerialization() {
        AgentResponse response = new AgentResponse(
                AgentStatus.NEEDS_CAPABILITY,
                AgentReason.OWNER_REQUIRED,
                AgentNextAction.DESCRIBE_REQUIRED_CAPABILITY,
                new AgentCapabilityResult("catalog.product-query", List.of("inputs", "output", "behavior", "acceptanceTest"))
        );

        String json = response.toJson();
        assertTrue(json.contains("\"status\":\"needs_capability\""));
        assertTrue(json.contains("\"reason\":\"owner_required\""));
        assertTrue(json.contains("\"nextAction\":\"describe_required_capability\""));
        assertTrue(json.contains("\"capability\":\"catalog.product-query\""));
        assertTrue(json.contains("\"requiredFields\":[\"inputs\",\"output\",\"behavior\",\"acceptanceTest\"]"));
    }

    @Test
    void testRetryRequiredResponseSerialization() {
        AgentResponse response = new AgentResponse(
                AgentStatus.RETRY_REQUIRED,
                AgentReason.WORKSPACE_NOT_READY,
                AgentNextAction.ENSURE_SESSION,
                null
        );

        String json = response.toJson();
        assertTrue(json.contains("\"status\":\"retry_required\""));
        assertTrue(json.contains("\"reason\":\"workspace_not_ready\""));
        assertTrue(json.contains("\"nextAction\":\"ensure_session\""));
        assertFalse(json.contains("\"result\""));
    }

    @Test
    void testToMapOmitsNullKeys() {
        AgentResponse response = AgentResponse.completed("src/Product.java");
        Map<String, Object> map = response.toMap();
        assertEquals("completed", map.get("status"));
        assertFalse(map.containsKey("reason"));
        assertFalse(map.containsKey("nextAction"));
        assertTrue(map.containsKey("result"));
    }

    @Test
    void testStatusAndReasonParsing() {
        assertEquals(AgentStatus.READY, AgentStatus.fromValue("ready"));
        assertEquals(AgentReason.POLICY_DENIED, AgentReason.fromValue("policy_denied"));
        assertEquals(AgentNextAction.ENSURE_SESSION, AgentNextAction.fromValue("ensure_session"));

        assertThrows(IllegalArgumentException.class, () -> AgentStatus.fromValue("invalid_status"));
    }

    @Test
    void testRejectsAbsolutePathsInMutationResult() {
        assertThrows(IllegalArgumentException.class, () -> new AgentMutationResult("C:\\abs\\path.txt"));
        assertThrows(IllegalArgumentException.class, () -> new AgentMutationResult("/abs/path.txt"));
    }
}
