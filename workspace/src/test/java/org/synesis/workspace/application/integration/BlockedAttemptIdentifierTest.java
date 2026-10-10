package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins the attempt and event identifiers recorded when integration blocks a candidate. */
class BlockedAttemptIdentifierTest {

  @Test
  void blockedAttemptIdPrefixesTheSnapshot() {
    assertEquals("blocked_snap-1", IntegrationOrchestrationService.blockedAttemptId("snap-1"));
  }

  @Test
  void blockedEventIdIsNameUuidOfTheAttemptId() {
    assertEquals(
        UUID.fromString("473adbb2-a83a-3185-ba66-93c4f363384c"),
        IntegrationOrchestrationService.blockedEventId("blocked_snap-1"));
  }
}
