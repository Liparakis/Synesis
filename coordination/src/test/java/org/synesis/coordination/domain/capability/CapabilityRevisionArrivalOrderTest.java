package org.synesis.coordination.domain.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.NodeIdentity;

/**
 * Designer item 18 case 3: a lane and its successor, on computers apart, each published revision 2 of one contract and
 * told the bound request so. The request kept whichever revision arrived last; it keeps the lower node's now, and a
 * higher revision over either. Entries of format 4 still decode, with no published revision.
 */
final class CapabilityRevisionArrivalOrderTest {

    private static final UUID PROJECT = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final CapabilityRequestHandle HANDLE = new CapabilityRequestHandle("req_71234567890123456789012345678901");
    private static final UUID LINEAGE = UUID.fromString("70000000-0000-4000-8000-000000000002");

    private final NodeIdentity nodeA = NodeIdentity.generate();
    private final NodeIdentity nodeB = NodeIdentity.generate();
    private long sequence;

    CapabilityRevisionArrivalOrderTest() throws Exception {
    }

    private static CapabilityContract contract(String purpose) {
        return new CapabilityContract(purpose, "id: UUID", "value: string", List.of("stable"), "not found", "none", "pure", "v1", "local",
                "serialized", List.of("lookup returns value"));
    }

    private static CapabilityRequestPayload payload(String purpose, CapabilityLifecycleState state, long published) {
        return new CapabilityRequestPayload(HANDLE, "catalog.lookup", "requester-node", "", "", "owner-node", "", "", LINEAGE, contract(purpose),
                state, null, 0, published);
    }

    private PredictionEvent event(NodeIdentity signer, PredictionEventType type, byte[] payload) throws Exception {
        sequence++;
        return PredictionEvent.create(PROJECT, UUID.randomUUID(), sequence, type, signer.nodeId(), payload, new byte[32], signer, sequence);
    }

    private static String purposeAfter(PredictionEvent... events) throws Exception {
        CapabilityRequestProjection projection = new CapabilityRequestProjection();
        for (PredictionEvent event : events) {
            projection.apply(event);
        }
        return projection.findByHandle(HANDLE).orElseThrow().contract().purpose();
    }

    @Test
    void ofTwoRevisionsOfOnePublishedNumberTheLowerNodesStandsInEitherOrder() throws Exception {
        NodeIdentity lower = nodeA.nodeId().compareTo(nodeB.nodeId()) < 0 ? nodeA : nodeB;
        NodeIdentity higher = lower == nodeA ? nodeB : nodeA;
        PredictionEvent created = event(nodeA, PredictionEventType.CAPABILITY_REQUEST_CREATED,
                payload("first", CapabilityLifecycleState.AWAITING_OWNER, 0).encode());
        PredictionEvent byLower = event(lower, PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED,
                payload("by the lower node", CapabilityLifecycleState.REVISION_REQUESTED, 2).encode());
        PredictionEvent byHigher = event(higher, PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED,
                payload("by the higher node", CapabilityLifecycleState.REVISION_REQUESTED, 2).encode());
        PredictionEvent third = event(higher, PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED,
                payload("revision 3", CapabilityLifecycleState.REVISION_REQUESTED, 3).encode());

        assertEquals("by the lower node", purposeAfter(created, byLower, byHigher));
        assertEquals("by the lower node", purposeAfter(created, byHigher, byLower));
        assertEquals("revision 3", purposeAfter(created, byLower, third, byHigher));
        assertEquals("revision 3", purposeAfter(created, third, byHigher, byLower));
    }

    @Test
    void anEntryOfFormatFourDecodesWithNoPublishedRevision() throws Exception {
        byte[] five = payload("first", CapabilityLifecycleState.AWAITING_OWNER, 0).encode();
        byte[] four = Arrays.copyOf(five, five.length - Long.BYTES);
        four[7] = 4; // the format number, a big-endian int after the four magic bytes

        CapabilityRequestPayload decoded = CapabilityRequestPayload.decode(four);

        assertEquals(0, decoded.publishedContractRevision());
        assertEquals("first", decoded.contract().purpose());
    }
}
