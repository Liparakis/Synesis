package org.synesis.workspace.application.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * Pins the identifiers the agent workflow derives deterministically, because they are persisted or
 * replayed to agents and must be reproduced by every later run.
 */
class DerivedWorkflowIdentifierTest {

  @Test
  void completionIdIsCmpPlusNameUuidOfLaneEpochAndCommit() {
    assertEquals(
        "cmp_a8a026af-a24d-30a1-b88c-fe7bb780162b",
        AgentTaskCompletionService.completionId(
            UUID.fromString("00000000-0000-4000-8000-00000000000c"), 3L, "abc123"));
  }

  @Test
  void capabilityInboxItemIdIsNameUuidOfCapabilityHandle() {
    assertEquals(
        "c16203ac-e4bc-3854-a293-596823b34a07",
        CapabilityActionProjection.capabilityInboxItemId("cap_1"));
  }

  @Test
  void actionIdIsNameUuidOfTheActionSeed() {
    AgentResponse decorated =
        new AgentWorkflowReducer()
            .decorate(
                new NextActionRequest(Path.of("."), "codex", "conn-1", false),
                new AgentResponse(AgentStatus.READY, null, null, Map.of()));
    assertEquals(
        "b40e7dc8-2dfb-34a6-a14e-f43575b91e91",
        ((Map<?, ?>) decorated.result()).get(NextActionKeys.ACTION_ID));
  }
}
