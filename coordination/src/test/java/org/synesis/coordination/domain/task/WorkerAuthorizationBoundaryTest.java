package org.synesis.coordination.domain.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.SupervisorId;
import org.synesis.coordination.domain.identity.WorkerId;

/**
 * Unit tests verifying worker-level authorization boundary matching in CapabilityRequestRecord.
 */
class WorkerAuthorizationBoundaryTest {

    /**
     * Creates the worker-scoped request shared by requester and owner boundary cases.
     *
     * @return request with distinct requester and owner worker identities
     */
    private static CapabilityRequestRecord workerAwareRecord() {
        CapabilityRequestHandle handle = CapabilityRequestHandle.parse("req_TEST1234567890ABCDEF");
        CapabilityContract contract = new CapabilityContract("in", "out", List.of(), List.of());
        return new CapabilityRequestRecord(handle,
                "catalog.query",
                "node-1",
                "sup-1",
                "worker-1",
                "node-1",
                "sup-2",
                "worker-2",
                UUID.randomUUID(),
                contract,
                CapabilityLifecycleState.AWAITING_OWNER,
                null,
                1000L,
                1000L);
    }

    @Test
    void matchesRequesterWorkerBoundaries() {
        CapabilityRequestRecord rec = workerAwareRecord();

        // Same node, same supervisor, same worker -> match
        assertTrue(rec.matchesRequester("node-1", "sup-1", "worker-1"));
        assertTrue(rec.matchesRequester(new NodeId("node-1"), new SupervisorId("sup-1"), new WorkerId("worker-1")));

        // Same node, different worker -> deny
        assertFalse(rec.matchesRequester("node-1", "sup-1", "worker-99"));

        // Same node, different supervisor -> deny
        assertFalse(rec.matchesRequester("node-1", "sup-99", "worker-1"));

        // Different node -> deny
        assertFalse(rec.matchesRequester("node-99", "sup-1", "worker-1"));
    }

    @Test
    void matchesOwnerWorkerBoundaries() {
        CapabilityRequestRecord rec = workerAwareRecord();

        // Same node, same supervisor, same worker -> match
        assertTrue(rec.matchesOwner("node-1", "sup-2", "worker-2"));
        assertTrue(rec.matchesOwner(new NodeId("node-1"), new SupervisorId("sup-2"), new WorkerId("worker-2")));

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

        // Record without worker/supervisor fields (empty strings)
        CapabilityRequestRecord rec = new CapabilityRequestRecord(handle,
                "catalog.query",
                "node-1",
                "",
                "",
                "node-2",
                "",
                "",
                UUID.randomUUID(),
                contract,
                CapabilityLifecycleState.AWAITING_OWNER,
                null,
                1000L,
                1000L);

        // Matches by node ID when record has empty worker/supervisor
        assertTrue(rec.matchesRequester("node-1", "sup-1", "worker-1"));
        assertFalse(rec.matchesRequester("node-99", "sup-1", "worker-1"));

        assertTrue(rec.matchesOwner("node-2", "sup-2", "worker-2"));
        assertFalse(rec.matchesOwner("node-99", "sup-2", "worker-2"));
    }
}
