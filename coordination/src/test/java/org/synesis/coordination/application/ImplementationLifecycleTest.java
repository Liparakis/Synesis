package org.synesis.coordination.application;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.integration.ImplementationEventPayload;
import org.synesis.coordination.domain.integration.ImplementationRevisionRecord;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.integration.ValidationContextRecord;
import org.synesis.coordination.persistence.PredictionEventStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests Stage 2B Slice 2 capability lifecycle events in the projection and codec.
 */
class ImplementationLifecycleTest {

    @Test
    void implementationEventPayloadRoundTrip() throws Exception {
        CapabilityRequestHandle handle = CapabilityRequestHandle.parse("req_TEST1234567890ABCDEF");
        List<String> changedPaths = List.of("src/Foo.java", "src/Bar.java");
        List<String> failedTests = List.of("FooTest.shouldReturnBar");

        ImplementationEventPayload payload = new ImplementationEventPayload(
                handle, 1, "abc123", "def456",
                changedPaths, "Initial implementation",
                "revision_required", "Test failure", failedTests,
                "/tmp/validation-worktree");

        byte[] encoded = payload.encode();
        ImplementationEventPayload decoded = ImplementationEventPayload.decode(encoded);

        assertEquals(handle.value(), decoded.handle().value());
        assertEquals(1, decoded.revisionNumber());
        assertEquals("abc123", decoded.baseCommit());
        assertEquals("def456", decoded.commitSha());
        assertEquals(changedPaths, decoded.changedPaths());
        assertEquals("Initial implementation", decoded.summary());
        assertEquals("revision_required", decoded.validationResult());
        assertEquals("Test failure", decoded.validationReason());
        assertEquals(failedTests, decoded.failedAcceptanceTests());
        assertEquals("/tmp/validation-worktree", decoded.worktreePath());
    }

    @Test
    void fullLifecycleProjectionWithSlice2States(@TempDir Path tempDir) throws Exception {
        UUID projectId = UUID.randomUUID();
        NodeIdentity requesterIdentity = NodeIdentity.generate();
        NodeIdentity ownerIdentity = NodeIdentity.generate();

        PredictionEventStore store = new PredictionEventStore(tempDir, projectId);

        CapabilityRequestHandle handle = CapabilityRequestHandle.parse("req_TEST1234567890ABCDEF");
        CapabilityContract contract = new CapabilityContract("UUID id", "Optional<P>", List.of("behavior"), List.of("test"));

        // 1. Create request
        CapabilityRequestPayload created = new CapabilityRequestPayload(
                handle, "catalog.product-query",
                requesterIdentity.nodeId(), ownerIdentity.nodeId(),
                contract, CapabilityLifecycleState.AWAITING_OWNER, null);
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CREATED,
                requesterIdentity.nodeId(), created.encode(), requesterIdentity);

        // 2. Owner accepts
        CapabilityRequestPayload accepted = new CapabilityRequestPayload(
                handle, "catalog.product-query",
                requesterIdentity.nodeId(), ownerIdentity.nodeId(),
                contract, CapabilityLifecycleState.ACCEPTED, null);
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_ACCEPTED,
                ownerIdentity.nodeId(), accepted.encode(), ownerIdentity);

        CapabilityRequestProjection proj = store.capabilityRequestProjection();
        assertEquals(CapabilityLifecycleState.ACCEPTED, proj.findByHandle(handle.value()).orElseThrow().state());

        // 3. Owner publishes implementation
        ImplementationEventPayload published = new ImplementationEventPayload(
                handle, 1, "abc0", "def1",
                List.of("src/Main.java"), "First revision",
                "", "", List.of(), "");
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED,
                ownerIdentity.nodeId(), published.encode(), ownerIdentity);

        proj = store.capabilityRequestProjection();
        assertEquals(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE, proj.findByHandle(handle.value()).orElseThrow().state());
        Optional<ImplementationRevisionRecord> impl = proj.findLatestImplementation(handle.value());
        assertTrue(impl.isPresent());
        assertEquals(1, impl.get().revisionNumber());
        assertEquals("def1", impl.get().commitSha());

        // 4. Requester starts validation
        ImplementationEventPayload validationStarted = new ImplementationEventPayload(
                handle, 1, "abc0", "def1",
                List.of("src/Main.java"), "First revision",
                "", "", List.of(), "/tmp/val-wt");
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_VALIDATION_STARTED,
                requesterIdentity.nodeId(), validationStarted.encode(), requesterIdentity);

        proj = store.capabilityRequestProjection();
        assertEquals(CapabilityLifecycleState.VALIDATING, proj.findByHandle(handle.value()).orElseThrow().state());
        Optional<ValidationContextRecord> ctx = proj.findValidationContext(handle.value());
        assertTrue(ctx.isPresent());
        assertEquals("/tmp/val-wt", ctx.get().worktreePath());

        // 5. Requester requests revision
        ImplementationEventPayload revRequired = new ImplementationEventPayload(
                handle, 1, "abc0", "def1",
                List.of("src/Main.java"), "First revision",
                "revision_required", "Missing test coverage", List.of("FooTest"), "");
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED,
                requesterIdentity.nodeId(), revRequired.encode(), requesterIdentity);

        proj = store.capabilityRequestProjection();
        assertEquals(CapabilityLifecycleState.IMPLEMENTING, proj.findByHandle(handle.value()).orElseThrow().state());
        assertFalse(proj.findValidationContext(handle.value()).isPresent());
        assertEquals("Missing test coverage", proj.findByHandle(handle.value()).orElseThrow().reason());

        // 6. Owner publishes revision 2
        ImplementationEventPayload published2 = new ImplementationEventPayload(
                handle, 2, "def1", "ghi2",
                List.of("src/Main.java", "src/MainTest.java"), "Second revision with tests",
                "", "", List.of(), "");
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED,
                ownerIdentity.nodeId(), published2.encode(), ownerIdentity);

        proj = store.capabilityRequestProjection();
        assertEquals(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE, proj.findByHandle(handle.value()).orElseThrow().state());
        assertEquals(2, proj.findLatestImplementation(handle.value()).orElseThrow().revisionNumber());

        // 7. Requester validates revision 2 (start + validate in one sequence)
        ImplementationEventPayload val2Started = new ImplementationEventPayload(
                handle, 2, "def1", "ghi2",
                List.of("src/Main.java", "src/MainTest.java"), "Second revision with tests",
                "", "", List.of(), "/tmp/val-wt-2");
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_VALIDATION_STARTED,
                requesterIdentity.nodeId(), val2Started.encode(), requesterIdentity);

        ImplementationEventPayload validatedPayload = new ImplementationEventPayload(
                handle, 2, "def1", "ghi2",
                List.of(), "done", "accepted", "", List.of(), "");
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_VALIDATED,
                requesterIdentity.nodeId(), validatedPayload.encode(), requesterIdentity);

        proj = store.capabilityRequestProjection();
        assertEquals(CapabilityLifecycleState.VALIDATED, proj.findByHandle(handle.value()).orElseThrow().state());
        assertFalse(proj.findValidationContext(handle.value()).isPresent());
    }

    @Test
    void sliceLifecycleStatesArePersistedAndReplayable(@TempDir Path tempDir) throws Exception {
        UUID projectId = UUID.randomUUID();
        NodeIdentity requesterIdentity = NodeIdentity.generate();
        NodeIdentity ownerIdentity = NodeIdentity.generate();

        PredictionEventStore store1 = new PredictionEventStore(tempDir, projectId);

        CapabilityRequestHandle handle = CapabilityRequestHandle.parse("req_TEST1234567890ABCDEF");
        CapabilityContract contract = new CapabilityContract("UUID id", "Optional<P>", List.of("behavior"), List.of("test"));

        CapabilityRequestPayload created = new CapabilityRequestPayload(
                handle, "catalog.product-query",
                requesterIdentity.nodeId(), ownerIdentity.nodeId(),
                contract, CapabilityLifecycleState.AWAITING_OWNER, null);
        store1.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CREATED,
                requesterIdentity.nodeId(), created.encode(), requesterIdentity);

        CapabilityRequestPayload accepted = new CapabilityRequestPayload(
                handle, "catalog.product-query",
                requesterIdentity.nodeId(), ownerIdentity.nodeId(),
                contract, CapabilityLifecycleState.ACCEPTED, null);
        store1.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_ACCEPTED,
                ownerIdentity.nodeId(), accepted.encode(), ownerIdentity);

        ImplementationEventPayload published = new ImplementationEventPayload(
                handle, 1, "abc0", "def1",
                List.of("src/Main.java"), "First revision",
                "", "", List.of(), "");
        store1.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED,
                ownerIdentity.nodeId(), published.encode(), ownerIdentity);

        // Simulate restart by opening a new store on the same directory
        PredictionEventStore store2 = new PredictionEventStore(tempDir, projectId);
        CapabilityRequestProjection proj = store2.capabilityRequestProjection();

        CapabilityRequestRecord record = proj.findByHandle(handle.value()).orElseThrow();
        assertNotNull(record);
        assertEquals(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE, record.state());
        assertEquals(1, proj.findLatestImplementation(handle.value()).orElseThrow().revisionNumber());
        assertEquals("def1", proj.findLatestImplementation(handle.value()).orElseThrow().commitSha());
    }
}
