package org.synesis.coordination.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.ImplementationEventPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;

/** Verifies that capability dependencies retain durable authority lineage. */
final class CapabilityLineageTest {
    @Test
    void intentCodecRetainsLineageAcrossReplay() throws Exception {
        UUID intentId = UUID.randomUUID();
        UUID lineage = UUID.randomUUID();
        WorkIntent intent = new WorkIntent(intentId, UUID.randomUUID(), "agt-owner", "codex",
                UUID.randomUUID(), "implement", "tests", "base", List.of(ResourceSelector.pathExact("src/a.py")),
                1, UUID.randomUUID(), lineage, WorkIntent.Status.ANNOUNCED);

        assertEquals(lineage, CollaborationCodec.decodeIntent(CollaborationCodec.encodeIntent(intent))
                .authorityLineageId());
    }

    @Test
    void capabilityRequestAndPublisherPayloadsRetainLineage() throws Exception {
        UUID lineage = UUID.randomUUID();
        CapabilityRequestHandle handle = CapabilityRequestHandle.parse("req_123456789012");
        CapabilityContract contract = new CapabilityContract("Task", "TaskTracker", List.of("CRUD"), List.of("test"));
        CapabilityRequestPayload request = new CapabilityRequestPayload(handle, "task_tracker", "node-a", "sup-a", "worker-a",
                "node-b", "sup-b", "worker-b", lineage, contract, CapabilityLifecycleState.ACCEPTED, null);
        assertEquals(lineage, CapabilityRequestPayload.decode(request.encode()).authorityLineageId());

        ImplementationEventPayload publication = new ImplementationEventPayload(handle, lineage, 1, "base", "commit",
                List.of("src/task_tracker.py"), "published", "", "", List.of(), "");
        assertEquals(lineage, ImplementationEventPayload.decode(publication.encode()).authorityLineageId());
    }

    @Test
    void snapshotProvenanceRetainsLineageForSuccessorResolution() throws Exception {
        UUID lineage = UUID.randomUUID();
        SnapshotProvenance provenance = new SnapshotProvenance(UUID.randomUUID(), UUID.randomUUID(), lineage,
                "agt-owner", "binding", 2, List.of("req_123456789012"), List.of(), List.of("PATH_EXACT:src/a.py"),
                "refs/synesis/snapshots/snap_1", "integrity", "artifacts");
        TaskSnapshotPayload payload = new TaskSnapshotPayload(UUID.randomUUID(), "snap_1", "node", "sup", "worker",
                "binding", "base", "commit", List.of("src/a.py"), List.of("req_123456789012"), "summary", provenance);

        assertEquals(lineage, TaskSnapshotPayload.decode(payload.encode()).provenance().authorityLineageId());
    }
}
