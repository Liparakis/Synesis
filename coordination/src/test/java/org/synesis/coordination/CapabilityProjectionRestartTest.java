package org.synesis.coordination;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.link.identity.NodeIdentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityProjectionRestartTest {

    @Test
    void replaysCapabilityEventsAndReconstructsProjection(@TempDir Path tempDir) throws Exception {
        UUID projectId = UUID.randomUUID();
        NodeIdentity requesterIdentity = NodeIdentity.generate();
        NodeIdentity ownerIdentity = NodeIdentity.generate();

        PredictionEventStore store1 = new PredictionEventStore(tempDir, projectId);

        CapabilityRequestHandle handle = CapabilityRequestHandle.parse("req_TEST1234567890ABCDEF");
        CapabilityContract contract = new CapabilityContract("UUID id", "Optional<P>", List.of("behavior"), List.of("test"));

        // 1. Create request
        CapabilityRequestPayload createdPayload = new CapabilityRequestPayload(
                handle, "catalog.product-query", requesterIdentity.nodeId(), ownerIdentity.nodeId(),
                contract, CapabilityLifecycleState.AWAITING_OWNER, null);
        store1.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CREATED, requesterIdentity.nodeId(), createdPayload.encode(), requesterIdentity);

        // 2. Owner revises contract
        CapabilityContract revisedContract = new CapabilityContract("UUID id", "Optional<P>", List.of("revised behavior"), List.of("test"));
        CapabilityRequestPayload revisedPayload = new CapabilityRequestPayload(
                handle, "catalog.product-query", requesterIdentity.nodeId(), ownerIdentity.nodeId(),
                revisedContract, CapabilityLifecycleState.REVISION_REQUESTED, "Existing catalog API requires revised behavior");
        store1.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED, ownerIdentity.nodeId(), revisedPayload.encode(), ownerIdentity);

        // Verify pre-restart state
        CapabilityRequestRecord recordBefore = store1.capabilityRequestProjection().findByHandle(handle.value()).orElseThrow();
        assertEquals(CapabilityLifecycleState.REVISION_REQUESTED, recordBefore.state());
        assertEquals("Existing catalog API requires revised behavior", recordBefore.reason());

        // 3. Simulate process restart by opening a new PredictionEventStore on the same directory
        PredictionEventStore store2 = new PredictionEventStore(tempDir, projectId);
        CapabilityRequestProjection projectionAfter = store2.capabilityRequestProjection();

        CapabilityRequestRecord recordAfter = projectionAfter.findByHandle(handle.value()).orElseThrow();
        assertNotNull(recordAfter);
        assertEquals("req_TEST1234567890ABCDEF", recordAfter.handle().value());
        assertEquals("catalog.product-query", recordAfter.capability());
        assertEquals(requesterIdentity.nodeId(), recordAfter.requesterNodeId());
        assertEquals(ownerIdentity.nodeId(), recordAfter.ownerNodeId());
        assertEquals(CapabilityLifecycleState.REVISION_REQUESTED, recordAfter.state());
        assertEquals("Existing catalog API requires revised behavior", recordAfter.reason());
        assertTrue(recordAfter.contract().isEquivalent(revisedContract));
    }
}
