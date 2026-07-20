package org.synesis.link.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.link.identity.NodeIdentity;

/** Verifies version and transcript-bound handshake proof behavior. */
final class HandshakeProofTest {

    @Test
    void bindsProofToVersionSessionChallengeAndIdentity() throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        UUID session = UUID.randomUUID();
        byte[] challenge = new byte[] {1, 2, 3, 4};
        HandshakeProof proof = HandshakeProof.create(identity, ProtocolVersion.V1, session, challenge);

        assertTrue(HandshakeProof.decode(proof.encoded()).verify(ProtocolVersion.V1, session, challenge,
                identity.nodeId()));
        assertFalse(proof.verify(ProtocolVersion.V1, UUID.randomUUID(), challenge, identity.nodeId()));
        assertFalse(proof.verify(ProtocolVersion.V1, session, new byte[] {9}, identity.nodeId()));
        assertFalse(proof.verify(new ProtocolVersion(1, 1), session, challenge, identity.nodeId()));
    }

    @Test
    void negotiationRejectsSilentDowngradeAndNoCommonVersion() {
        assertTrue(ProtocolVersion.negotiate(List.of(ProtocolVersion.V1), List.of(ProtocolVersion.V1))
                .equals(ProtocolVersion.V1));
        assertThrows(IllegalArgumentException.class, () -> ProtocolVersion.negotiate(
                List.of(ProtocolVersion.V1), List.of(new ProtocolVersion(2, 0))));
    }
}
