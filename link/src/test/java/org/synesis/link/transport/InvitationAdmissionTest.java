package org.synesis.link.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.synesis.link.SynesisLink;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.session.HandshakeTranscript;

/** Verifies single-use invitation admission and pre-authentication release rules. */
final class InvitationAdmissionTest {
    @Test
    void acceptsOneMatchingAttemptAndConsumesAfterAuthentication() throws Exception {
        NodeIdentity initiator = NodeIdentity.generate();
        NodeIdentity responder = NodeIdentity.generate();
        UUID sessionId = UUID.randomUUID();
        byte[] capability = new byte[32];
        capability[0] = 9;
        HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1, SynesisLink.ALPN,
                sessionId, 1, 2, new byte[] {1}, new byte[] {2}, capability, initiator.nodeId(),
                initiator.publicKeyEncoded(), responder.nodeId(), responder.publicKeyEncoded());
        try (InvitationAdmission admission = new InvitationAdmission(sessionId, capability)) {
            assertTrue(admission.reserve(transcript));
            assertFalse(admission.reserve(transcript));
            admission.authenticated();
            assertFalse(admission.reserve(transcript));
        }
    }

    @Test
    void releasesOnlyAnUnauthenticatedReservation() throws Exception {
        NodeIdentity initiator = NodeIdentity.generate();
        NodeIdentity responder = NodeIdentity.generate();
        UUID sessionId = UUID.randomUUID();
        byte[] capability = new byte[32];
        HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1, SynesisLink.ALPN,
                sessionId, 1, 2, new byte[] {1}, new byte[] {2}, capability, initiator.nodeId(),
                initiator.publicKeyEncoded(), responder.nodeId(), responder.publicKeyEncoded());
        try (InvitationAdmission admission = new InvitationAdmission(sessionId, capability)) {
            assertTrue(admission.reserve(transcript));
            admission.releaseBeforeAuthentication();
            assertTrue(admission.reserve(transcript));
            admission.authenticated();
            admission.releaseBeforeAuthentication();
            assertFalse(admission.reserve(transcript));
        }
    }
}
