package org.synesis.coordination;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests verifying worker-level authorization boundary matching in CapabilityRequestRecord.
 */
class WorkerAuthorizationBoundaryTest {

    @Test
    void matchesRequesterWorkerBoundaries() {
        CapabilityRequestHandle handle = CapabilityRequestHandle.parse("req_TEST1234567890ABCDEF");
        CapabilityContract contract = new CapabilityContract("in", "out", List.of(), List.of());

        CapabilityRequestRecord rec = new CapabilityRequestRecord(
                handle, "catalog.query",
                "node-1", "sup-1", "worker-1",
                "node-1", "sup-2", "worker-2",
                contract, CapabilityLifecycleState.AWAITING_OWNER, null,
                1000L, 1000L
        );

        // Same node, same supervisor, same worker -> match
        assertTrue(rec.matchesRequester("node-1", "sup-1", "worker-1"));

        // Same node, different worker -> deny
        assertFalse(rec.matchesRequester("node-1", "sup-1", "worker-99"));

        // Same node, different supervisor -> deny
        assertFalse(rec.matchesRequester("node-1", "sup-99", "worker-1"));

        // Different node -> deny
        assertFalse(rec.matchesRequester("node-99", "sup-1", "worker-1"));
    }

    @Test
    void matchesOwnerWorkerBoundaries() {
        CapabilityRequestHandle handle = CapabilityRequestHandle.parse("req_TEST1234567890ABCDEF");
        CapabilityContract contract = new CapabilityContract("in", "out", List.of(), List.of());

        CapabilityRequestRecord rec = new CapabilityRequestRecord(
                handle, "catalog.query",
                "node-1", "sup-1", "worker-1",
                "node-1", "sup-2", "worker-2",
                contract, CapabilityLifecycleState.AWAITING_OWNER, null,
                1000L, 1000L
        );

        // Same node, same supervisor, same worker -> match
        assertTrue(rec.matchesOwner("node-1", "sup-2", "worker-2"));

        // Same node, different worker -> deny
        assertFalse(rec.matchesOwner("node-1", "sup-2", "worker-1"));

        // Same node, different supervisor -> deny
        assertFalse(rec.matchesOwner("node-1", "sup-1", "worker-2"));

        // Different node -> deny
        assertFalse(rec.matchesOwner("node-99", "sup-2", "worker-2"));
    }

    @Test
    void v1FallbackMatching() {
        CapabilityRequestHandle handle = CapabilityRequestHandle.parse("req_TEST1234567890ABCDEF");
        CapabilityContract contract = new CapabilityContract("in", "out", List.of(), List.of());

        // V1 record without worker/supervisor fields (empty strings)
        CapabilityRequestRecord rec = new CapabilityRequestRecord(
                handle, "catalog.query",
                "node-1", "node-2", contract, CapabilityLifecycleState.AWAITING_OWNER, null, 1000L, 1000L
        );

        // Matches by node ID when record has empty worker/supervisor
        assertTrue(rec.matchesRequester("node-1", "sup-1", "worker-1"));
        assertFalse(rec.matchesRequester("node-99", "sup-1", "worker-1"));

        assertTrue(rec.matchesOwner("node-2", "sup-2", "worker-2"));
        assertFalse(rec.matchesOwner("node-99", "sup-2", "worker-2"));
    }
}
