package org.synesis.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.GeneralSecurityException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayForwardingFrame;

/**
 * Verifies relay authentication and frame wire vectors without opening a
 * socket.
 */
final class RelayWireCodecTest {

    @Test
    void helloAckAndOpaqueFrameRoundTrip() throws Exception {
        NodeIdentity relay = NodeIdentity.generate();
        NodeIdentity client = NodeIdentity.generate();
        byte[] encodedHello = RelayWireCodec.encodeHello(client);
        RelayWireCodec.Hello hello = RelayWireCodec.decodeHello(encodedHello);
        RelayWireCodec.verifyHello(hello);
        byte[] encodedAck = RelayWireCodec.encodeHelloAck(relay, hello);
        RelayWireCodec.HelloAck ack = RelayWireCodec.decodeHelloAck(encodedAck);
        RelayWireCodec.verifyHelloAck(ack, hello, relay.nodeId(), relay.publicKeyEncoded());

        byte[] logicalRecord = logicalRecord(client, relay);
        OverlayForwardingFrame frame = OverlayForwardingFrame.create(UUID.randomUUID(), relay.nodeId(),
                UUID.randomUUID(), 2, logicalRecord);
        OverlayForwardingFrame decoded = OverlayForwardingFrame.decode(
                RelayWireCodec.decodeFrame(RelayWireCodec.encodeFrame(frame), 2));
        assertEquals(frame.projectId(), decoded.projectId());
        assertArrayEquals(frame.innerRecord(), decoded.innerRecord());
        RelayWireCodec.FrameAck frameAck = RelayWireCodec.decodeFrameAck(
                RelayWireCodec.encodeFrameAck(frame.messageId(), 0));
        assertEquals(frame.messageId(), frameAck.messageId);
        assertEquals(0, frameAck.status);
    }

    private static byte[] logicalRecord(NodeIdentity origin, NodeIdentity destination)
            throws GeneralSecurityException {
        return org.synesis.link.overlay.OverlayKeyAgreement.start(UUID.randomUUID(), destination.nodeId(),
                UUID.randomUUID(), 1, origin).init().encoded();
    }
}
