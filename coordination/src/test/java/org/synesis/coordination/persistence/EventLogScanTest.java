package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.identity.EventSequence;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.PredictionId;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies that opening a store and appending to a stale instance judge the durable log identically.
 */
class EventLogScanTest {

    private static final String INVALID_LOG = "invalid coordination event log";

    private static void appendRequest(PredictionEventStore store, NodeIdentity requester, NodeIdentity owner, String handle) throws Exception {
        CapabilityRequestPayload payload = new CapabilityRequestPayload(CapabilityRequestHandle.parse(handle),
                "catalog.product-query",
                requester.nodeId(),
                owner.nodeId(),
                UUID.nameUUIDFromBytes(handle.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new CapabilityContract("UUID id", "Optional<P>", List.of("behavior"), List.of("test")),
                CapabilityLifecycleState.AWAITING_OWNER,
                null,
                0L);
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CREATED, requester.nodeId(), payload.encode(), requester);
    }

    private static void writeEvent(Path root, UUID projectId, long fileSequence, long eventSequence, byte[] previousDigest) throws Exception {
        NodeIdentity signer = NodeIdentity.generate();
        PredictionEvent event = PredictionEvent.create(new ProjectId(projectId),
                new PredictionId(UUID.randomUUID()),
                new EventSequence(eventSequence),
                PredictionEventType.PARTICIPANT_HEARTBEAT,
                new NodeId(signer.nodeId()),
                new byte[0],
                previousDigest,
                signer,
                1L);
        Files.write(root.resolve("events").resolve(String.format("%020d.sce", fileSequence)), event.encoded());
    }

    private static void assertBothRejectBadLog(Path root, UUID projectId, long fileSequence, long eventSequence, byte[] previousDigest) throws Exception {
        PredictionEventStore open = new PredictionEventStore(root, projectId);
        writeEvent(root, projectId, fileSequence, eventSequence, previousDigest);
        NodeIdentity actor = NodeIdentity.generate();

        CoordinationException load = assertThrows(CoordinationException.class, () -> new PredictionEventStore(root, projectId));
        CoordinationException head = assertThrows(CoordinationException.class,
                () -> open.append(UUID.randomUUID(), PredictionEventType.PARTICIPANT_HEARTBEAT, actor.nodeId(), new byte[0], actor));

        assertEquals(INVALID_LOG, load.getMessage());
        assertEquals(INVALID_LOG, head.getMessage());
    }

    @Test
    void staleStoreAppendsAfterTheHeadAnotherInstanceReached(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        NodeIdentity requester = NodeIdentity.generate();
        NodeIdentity owner = NodeIdentity.generate();
        PredictionEventStore stale = new PredictionEventStore(root, projectId);
        PredictionEventStore other = new PredictionEventStore(root, projectId);

        appendRequest(other, requester, owner, "req_TEST1234567890ABCDE1");
        appendRequest(other, requester, owner, "req_TEST1234567890ABCDE2");
        appendRequest(stale, requester, owner, "req_TEST1234567890ABCDE3");

        PredictionEventStore reopened = new PredictionEventStore(root, projectId);
        assertEquals(3L, reopened.headSequence());
        assertEquals(3L, reopened.events().getLast().sequence());
    }

    @Test
    void sequenceGapIsRejectedByLoadAndByAppend(@TempDir Path root) throws Exception {
        assertBothRejectBadLog(root, UUID.randomUUID(), 1L, 2L, new byte[32]);
    }

    @Test
    void brokenHashChainIsRejectedByLoadAndByAppend(@TempDir Path root) throws Exception {
        byte[] wrongPrevious = new byte[32];
        Arrays.fill(wrongPrevious, (byte) 1);
        assertBothRejectBadLog(root, UUID.randomUUID(), 1L, 1L, wrongPrevious);
    }
}
