package org.synesis.workspace.application.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the identifiers {@link WorkspaceCollaborationService} derives deterministically, because
 * they are persisted in events and must be reproduced by every later run.
 */
class DerivedIdentifierTest {

  private static final UUID SOURCE_INTENT = UUID.fromString("00000000-0000-4000-8000-000000000001");

  @Test
  void participantHandleIsAgtPlusNameUuidOfTheConnectionInstance() {
    assertEquals(
        "agt_0c5c52b9-9b16-3b31-8d40-8dcd55006804",
        WorkspaceCollaborationService.participantHandle("connection-1"));
  }

  @Test
  void recoveryContinuationIntentId() {
    assertEquals(
        UUID.fromString("7761a2fa-b5ff-3884-b088-dd4dd993ac8e"),
        WorkspaceCollaborationService.recoveryContinuationIntentId(SOURCE_INTENT, "agt_target", 4L));
  }

  @Test
  void recoveryContinuationGrantId() {
    assertEquals(
        UUID.fromString("d286baa2-83a9-366e-b5e7-824dade0afec"),
        WorkspaceCollaborationService.recoveryContinuationGrantId(SOURCE_INTENT, "agt_target", 4L));
  }
}
