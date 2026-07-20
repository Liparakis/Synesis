package org.synesis.link.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.HandshakeEnvelope;
import org.synesis.link.protocol.ProtocolVersion;

/** Verifies identity-bound session establishment and replay rejection. */
final class SessionAuthenticatorTest {

    @Test
    void publishesSessionOnlyAfterBothProofsAndRejectsReplay() throws Exception {
        NodeIdentity initiator = NodeIdentity.generate();
        NodeIdentity responder = NodeIdentity.generate();
        HandshakeTranscript transcript = HandshakeTranscript.forIdentities(ProtocolVersion.V1,
                UUID.randomUUID(), 4, 9, new byte[] {1, 2, 3}, new byte[] {4, 5, 6}, initiator, responder);
        HandshakeProof initiatorProof = SessionAuthenticator.createProof(initiator, transcript,
                HandshakeRole.INITIATOR);
        HandshakeProof responderProof = SessionAuthenticator.createProof(responder, transcript,
                HandshakeRole.RESPONDER);
        ReplayGuard guard = new ReplayGuard();

        PeerSession session = SessionAuthenticator.establish(initiator, responder.nodeId(), transcript,
                initiatorProof, responderProof, guard, Instant.EPOCH);

        assertEquals(initiator.nodeId(), session.localNodeId());
        assertEquals(responder.nodeId(), session.remoteNodeId());
        assertEquals(4, session.localEpoch());
        assertEquals(9, session.remoteEpoch());
        assertThrows(IllegalStateException.class, () -> SessionAuthenticator.establish(initiator,
                responder.nodeId(), transcript, initiatorProof, responderProof, guard, Instant.EPOCH));
    }

    @Test
    void rejectsUnexpectedIdentityAndCrossConnectionProof() throws Exception {
        NodeIdentity initiator = NodeIdentity.generate();
        NodeIdentity responder = NodeIdentity.generate();
        NodeIdentity wrong = NodeIdentity.generate();
        HandshakeTranscript transcript = HandshakeTranscript.forIdentities(ProtocolVersion.V1,
                UUID.randomUUID(), 0, 0, new byte[] {1}, new byte[] {2}, initiator, responder);
        HandshakeProof initiatorProof = SessionAuthenticator.createProof(initiator, transcript,
                HandshakeRole.INITIATOR);
        HandshakeProof responderProof = SessionAuthenticator.createProof(responder, transcript,
                HandshakeRole.RESPONDER);
        assertThrows(IllegalArgumentException.class, () -> SessionAuthenticator.establish(initiator,
                wrong.nodeId(), transcript, initiatorProof, responderProof, new ReplayGuard(), Instant.EPOCH));

        HandshakeTranscript otherTranscript = HandshakeTranscript.forIdentities(ProtocolVersion.V1,
                UUID.randomUUID(), 0, 0, new byte[] {3}, new byte[] {4}, initiator, responder);
        assertThrows(IllegalArgumentException.class, () -> SessionAuthenticator.establish(initiator,
                responder.nodeId(), otherTranscript, initiatorProof, responderProof,
                new ReplayGuard(), Instant.EPOCH));
    }

    @Test
    void transcriptRoundTripsCanonicallyAndBindsRoleChallenges() throws Exception {
        NodeIdentity initiator = NodeIdentity.generate();
        NodeIdentity responder = NodeIdentity.generate();
        HandshakeTranscript transcript = HandshakeTranscript.forIdentities(ProtocolVersion.V1,
                UUID.randomUUID(), 1, 2, new byte[] {7, 8}, new byte[] {9, 10}, initiator, responder);
        HandshakeTranscript decoded = HandshakeTranscript.decode(transcript.encoded());

        assertArrayEquals(transcript.encoded(), decoded.encoded());
        assertFalse(java.util.Arrays.equals(transcript.proofChallenge(HandshakeRole.INITIATOR),
                transcript.proofChallenge(HandshakeRole.RESPONDER)));
    }

    @Test
    void envelopeRoundTripsWithBoundedNestedValues() throws Exception {
        NodeIdentity initiator = NodeIdentity.generate();
        NodeIdentity responder = NodeIdentity.generate();
        HandshakeTranscript transcript = HandshakeTranscript.forIdentities(ProtocolVersion.V1,
                UUID.randomUUID(), 1, 1, new byte[] {1}, new byte[] {2}, initiator, responder);
        HandshakeProof proof = SessionAuthenticator.createProof(initiator, transcript,
                HandshakeRole.INITIATOR);
        HandshakeEnvelope envelope = HandshakeEnvelope.create(HandshakeRole.INITIATOR, transcript, proof);
        HandshakeEnvelope decoded = HandshakeEnvelope.decode(envelope.encoded());

        assertEquals(HandshakeRole.INITIATOR, decoded.role());
        assertArrayEquals(envelope.encoded(), decoded.encoded());
    }

    @Test
    void rejectsSelectedVersionChangedAfterProofCreation() throws Exception {
        NodeIdentity initiator = NodeIdentity.generate();
        NodeIdentity responder = NodeIdentity.generate();
        HandshakeTranscript original = HandshakeTranscript.forIdentities(ProtocolVersion.V1,
                UUID.randomUUID(), 0, 0, new byte[] {1}, new byte[] {2}, initiator, responder);
        HandshakeProof initiatorProof = SessionAuthenticator.createProof(initiator, original,
                HandshakeRole.INITIATOR);
        HandshakeProof responderProof = SessionAuthenticator.createProof(responder, original,
                HandshakeRole.RESPONDER);
        HandshakeTranscript altered = HandshakeTranscript.create(new ProtocolVersion(2, 0), original.alpn(),
                original.sessionId(), 0, 0, new byte[] {1}, new byte[] {2}, initiator.nodeId(),
                initiator.publicKeyEncoded(), responder.nodeId(), responder.publicKeyEncoded());

        assertThrows(IllegalArgumentException.class, () -> SessionAuthenticator.establish(initiator,
                responder.nodeId(), altered, initiatorProof, responderProof, new ReplayGuard(), Instant.EPOCH));
    }
}
