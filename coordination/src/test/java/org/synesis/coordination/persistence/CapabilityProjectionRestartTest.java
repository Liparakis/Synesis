package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.identity.CapabilityId;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies capability projections survive event-store restart and replay.
 */
class CapabilityProjectionRestartTest {

    /**
     * Builds a request event payload for one stage of the restart scenario.
     *
     * @param handle    request identity
     * @param requester authenticated requester
     * @param owner     assigned owner
     * @param contract  capability contract for this stage
     * @param state     lifecycle state represented by the payload
     * @param reason    optional revision reason
     * @return encoded-event payload for the request stage
     */
    private static CapabilityRequestPayload capabilityRequestPayload(CapabilityRequestHandle handle,
            NodeIdentity requester,
            NodeIdentity owner,
            CapabilityContract contract,
            CapabilityLifecycleState state,
            String reason) {
        return new CapabilityRequestPayload(handle,
                "catalog.product-query",
                requester.nodeId(),
                owner.nodeId(),
                contract,
                state,
                reason);
    }

    @Test
    void replaysCapabilityEventsAndReconstructsProjection(@TempDir Path tempDir) throws Exception {
        UUID projectId = UUID.randomUUID();
        NodeIdentity requesterIdentity = NodeIdentity.generate();
        NodeIdentity ownerIdentity = NodeIdentity.generate();

        PredictionEventStore store1 = new PredictionEventStore(tempDir, projectId);

        CapabilityRequestHandle handle = CapabilityRequestHandle.parse("req_TEST1234567890ABCDEF");
        CapabilityContract contract = new CapabilityContract("UUID id",
                "Optional<P>",
                List.of("behavior"),
                List.of("test"));

        // 1. Create request
        CapabilityRequestPayload createdPayload = capabilityRequestPayload(handle,
                requesterIdentity,
                ownerIdentity,
                contract,
                CapabilityLifecycleState.AWAITING_OWNER,
                null);
        assertEquals(new CapabilityId("catalog.product-query"), createdPayload.capabilityIdentity());
        assertEquals(new NodeId(requesterIdentity.nodeId()), createdPayload.requesterNodeIdentity());
        store1.append(UUID.randomUUID(),
                PredictionEventType.CAPABILITY_REQUEST_CREATED,
                requesterIdentity.nodeId(),
                createdPayload.encode(),
                requesterIdentity);

        // 2. Owner revises contract
        CapabilityContract revisedContract = new CapabilityContract("UUID id",
                "Optional<P>",
                List.of("revised behavior"),
                List.of("test"));
        CapabilityRequestPayload revisedPayload = capabilityRequestPayload(handle,
                requesterIdentity,
                ownerIdentity,
                revisedContract,
                CapabilityLifecycleState.REVISION_REQUESTED,
                "Existing catalog API requires revised behavior");
        store1.append(UUID.randomUUID(),
                PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED,
                ownerIdentity.nodeId(),
                revisedPayload.encode(),
                ownerIdentity);

        // Verify pre-restart state
        CapabilityRequestRecord recordBefore = store1.capabilityRequestProjection().findByHandle(handle).orElseThrow();
        assertEquals(CapabilityLifecycleState.REVISION_REQUESTED, recordBefore.state());
        assertEquals("Existing catalog API requires revised behavior", recordBefore.reason());

        // 3. Simulate process restart by opening a new PredictionEventStore on the same directory
        PredictionEventStore store2 = new PredictionEventStore(tempDir, projectId);
        CapabilityRequestProjection projectionAfter = store2.capabilityRequestProjection();

        CapabilityRequestRecord recordAfter = projectionAfter.findByHandle(handle).orElseThrow();
        assertNotNull(recordAfter);
        assertEquals("req_TEST1234567890ABCDEF", recordAfter.handle().value());
        assertEquals("catalog.product-query", recordAfter.capability());
        assertEquals(requesterIdentity.nodeId(), recordAfter.requesterNodeId());
        assertEquals(ownerIdentity.nodeId(), recordAfter.ownerNodeId());
        assertEquals(new CapabilityId("catalog.product-query"), recordAfter.capabilityIdentity());
        assertEquals(new NodeId(requesterIdentity.nodeId()), recordAfter.requesterNodeIdentity());
        assertTrue(projectionAfter.recordsByHandle().containsKey(handle));
        assertEquals(List.of(recordAfter), projectionAfter.findAllForRequester(new NodeId(requesterIdentity.nodeId())));
        assertEquals(CapabilityLifecycleState.REVISION_REQUESTED, recordAfter.state());
        assertEquals("Existing catalog API requires revised behavior", recordAfter.reason());
        assertTrue(recordAfter.contract().isEquivalent(revisedContract));
    }
}
