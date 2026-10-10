package org.synesis.link.onboarding;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.link.SynesisLink;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.session.HandshakeTranscript;

/**
 * Verifies single-use invitation admission and pre-authentication release rules.
 */
final class InvitationAdmissionTest {

  @Test
  void acceptsOneMatchingAttemptAndConsumesAfterAuthentication() throws Exception {
    NodeIdentity initiator = NodeIdentity.generate();
    NodeIdentity responder = NodeIdentity.generate();
    UUID sessionId = UUID.randomUUID();
    byte[] capability = new byte[32];
    capability[0] = 9;
    HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1,
        SynesisLink.ALPN,
        sessionId, 1, 2, new byte[]{1}, new byte[]{2}, capability, initiator.nodeId(),
        initiator.publicKeyEncoded(), responder.nodeId(), responder.publicKeyEncoded());
    try (InvitationAdmission admission = new InvitationAdmission(sessionId, capability)) {
      var reservation = admission.reserve(transcript).orElseThrow();
      assertTrue(admission.reserve(transcript).isEmpty());
      admission.authenticated(reservation);
      assertTrue(admission.reserve(transcript).isEmpty());
    }
  }

  @Test
  void releasesOnlyAnUnauthenticatedReservation() throws Exception {
    NodeIdentity initiator = NodeIdentity.generate();
    NodeIdentity responder = NodeIdentity.generate();
    UUID sessionId = UUID.randomUUID();
    byte[] capability = new byte[32];
    HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1,
        SynesisLink.ALPN,
        sessionId, 1, 2, new byte[]{1}, new byte[]{2}, capability, initiator.nodeId(),
        initiator.publicKeyEncoded(), responder.nodeId(), responder.publicKeyEncoded());
    try (InvitationAdmission admission = new InvitationAdmission(sessionId, capability)) {
      var first = admission.reserve(transcript).orElseThrow();
      admission.releaseBeforeAuthentication(first);
      var second = admission.reserve(transcript).orElseThrow();
      admission.authenticated(second);
      admission.releaseBeforeAuthentication(second);
      assertTrue(admission.reserve(transcript).isEmpty());
    }
  }

  @Test
  void staleReleaseDoesNotFreeAnotherAttemptsReservation() throws Exception {
    NodeIdentity initiator = NodeIdentity.generate();
    NodeIdentity responder = NodeIdentity.generate();
    UUID sessionId = UUID.randomUUID();
    byte[] capability = new byte[32];
    HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1,
        SynesisLink.ALPN,
        sessionId, 1, 2, new byte[]{1}, new byte[]{2}, capability, initiator.nodeId(),
        initiator.publicKeyEncoded(), responder.nodeId(), responder.publicKeyEncoded());
    try (InvitationAdmission admission = new InvitationAdmission(sessionId, capability)) {
      var first = admission.reserve(transcript).orElseThrow();
      admission.releaseBeforeAuthentication(first);
      var second = admission.reserve(transcript).orElseThrow();
      admission.releaseBeforeAuthentication(first);
      assertTrue(admission.reserve(transcript).isEmpty());
      admission.authenticated(second);
      assertTrue(admission.reserve(transcript).isEmpty());
    }
  }

  @Test
  void minimizedCapabilityRetainsSingleUseAdmissionSemantics() throws Exception {
    NodeIdentity initiator = NodeIdentity.generate();
    NodeIdentity responder = NodeIdentity.generate();
    UUID sessionId = UUID.randomUUID();
    byte[] capability = new byte[12];
    capability[0] = 8;
    HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1,
        SynesisLink.ALPN,
        sessionId, 1, 2, new byte[]{1}, new byte[]{2}, capability, initiator.nodeId(),
        initiator.publicKeyEncoded(), responder.nodeId(), responder.publicKeyEncoded());
    try (InvitationAdmission admission = new InvitationAdmission(sessionId, capability)) {
      admission.authenticated(admission.reserve(transcript).orElseThrow());
      assertTrue(admission.reserve(transcript).isEmpty());
    }
  }
}
