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
  void connectionUuidIsTheNameUuidOfTheConnectionInstance() {
    assertEquals(
        UUID.fromString("0c5c52b9-9b16-3b31-8d40-8dcd55006804"),
        LaneIdentities.connectionUuid("connection-1"));
  }

  @Test
  void sessionIntentIdIsNameUuidOfProviderColonSession() {
    assertEquals(
        UUID.fromString("2968f4a9-dc86-3785-b281-59b12e69b8d6"),
        LaneIdentities.sessionIntentId("codex", "session-1"));
  }

  @Test
  void defaultWorkGroupIdIsNameUuidOfTheProject() {
    assertEquals(
        UUID.fromString("1316e0a6-cf93-322a-bd78-21eb018b0ef4"),
        WorkIntentAnnouncement.defaultWorkGroupId(
            UUID.fromString("00000000-0000-4000-8000-00000000000a")));
  }

  @Test
  void repairJoinIntentIdIsNameUuidOfRepairIntentAndSession() {
    assertEquals(
        UUID.fromString("d484c4dd-4bd0-392b-bd44-60aff76084fe"),
        WorkIntentAnnouncement.repairJoinIntentId(
            UUID.fromString("00000000-0000-4000-8000-00000000000b"), "session-1"));
  }

  @Test
  void reviewValidationEventId() {
    assertEquals(
        UUID.fromString("47d642f9-a14b-3d46-ae0e-166e3e681ac6"),
        ReviewValidationService.validationEventId(
            UUID.fromString("00000000-0000-4000-8000-00000000000d"), "snap-1"));
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
