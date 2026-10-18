package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
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

    /**
     * Another writer takes the sequence first. Since ADR-0098 step 4 every writer of this build holds the order lock
     * across the placement, so the only such writer is one of a build before the lock. Neither event may be lost:
     * before, the later durable move replaced the earlier writer's file, and an announced lane vanished from the log
     * although its agent had been told it was accepted.
     */
    @Test
    void appendThatLosesItsSequenceToAnotherWriterKeepsBothEvents(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity requester = NodeIdentity.generate();
        NodeIdentity owner = NodeIdentity.generate();
        Path older = temp.resolve("older-build");
        appendRequest(new PredictionEventStore(older, project), requester, owner, "req_TEST1234567890ABCDR2");
        Path olderEntry = older.resolve("events").resolve(String.format("%020d.sce", 1L));
        Path current = temp.resolve("current");
        PredictionEventStore first = new PredictionEventStore(current, project);
        boolean[] raced = {false};
        PredictionEventStore.setTestFaultInjector(reached -> {
            if (reached == PredictionEventStore.AppendBoundary.BEFORE_DURABLE_MOVE && !raced[0]) {
                raced[0] = true;
                Files.copy(olderEntry, current.resolve("events").resolve(olderEntry.getFileName()));
            }
        });
        try {
            appendRequest(first, requester, owner, "req_TEST1234567890ABCDR1");
        } finally {
            PredictionEventStore.clearTestFaultInjector();
        }

        PredictionEventStore replayed = new PredictionEventStore(current, project);
        assertEquals(2L, replayed.headSequence());
        assertEquals(2, replayed.capabilityRequestProjection().records().size());
        assertEquals(List.of("self 1", "self 2"), Files.readAllLines(current.resolve("applied.order")));
    }

    @Test
    void openingAStoreLeavesATemporaryFileAnotherWriterIsStillUsingAndRemovesAnAbandonedOne(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        new PredictionEventStore(temp, project);
        Path inFlight = Files.createFile(temp.resolve("events").resolve("00000000000000000001.sce.tmp-" + UUID.randomUUID()));
        Path abandoned = Files.createFile(temp.resolve("events").resolve("00000000000000000001.sce.tmp-" + UUID.randomUUID()));
        Files.setLastModifiedTime(abandoned, FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));

        PredictionEventStore reader = new PredictionEventStore(temp, project);

        assertEquals(0L, reader.headSequence());
        assertTrue(Files.exists(inFlight), "a reader must not delete the file another process is appending through");
        assertFalse(Files.exists(abandoned));
    }

    @Test
    void recordedEventsCountsTheEventsAStoreWouldReplayAndIsZeroWhereNothingIsRecorded(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity requester = NodeIdentity.generate();
        NodeIdentity owner = NodeIdentity.generate();
        assertEquals(0L, PredictionEventStore.recordedEvents(temp.resolve("never-created")));
        PredictionEventStore store = new PredictionEventStore(temp, project);
        assertEquals(0L, PredictionEventStore.recordedEvents(temp), "a store with nothing recorded");

        appendRequest(store, requester, owner, "req_TEST1234567890ABCDR1");
        appendRequest(store, requester, owner, "req_TEST1234567890ABCDR2");
        appendRequest(store, requester, owner, "req_TEST1234567890ABCDR3");

        assertEquals(3L, PredictionEventStore.recordedEvents(temp));
        assertEquals(store.events().size(), PredictionEventStore.recordedEvents(temp));
    }

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

    /** A store opened on a log it already verified reads only what was appended since, and still sees all of it. */
    @Test
    void storeOpenedAfterAnotherProcessAppendedSeesTheNewEventsOnTopOfTheRememberedLog(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        NodeIdentity actor = NodeIdentity.generate();
        PredictionEventStore first = new PredictionEventStore(root, projectId);
        appendRequest(first, actor, actor, "req_TEST1234567890ABC001");
        appendRequest(first, actor, actor, "req_TEST1234567890ABC002");
        new PredictionEventStore(root, projectId);
        appendRequest(new PredictionEventStore(root, projectId), actor, actor, "req_TEST1234567890ABC003");

        PredictionEventStore reopened = new PredictionEventStore(root, projectId);

        assertEquals(3L, reopened.headSequence());
        assertEquals(3, reopened.capabilityRequestProjection().records().size());
    }

    @Test
    void writersOnSeparateStoresKeepOneDenseChain(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        NodeIdentity actor = NodeIdentity.generate();
        PredictionEventStore a = new PredictionEventStore(root, projectId);
        PredictionEventStore b = new PredictionEventStore(root, projectId);
        int[] appendedByB = new int[1];
        Thread other = new Thread(() -> appendedByB[0] = appendMany(b, actor, "B"));
        other.start();
        int appendedByA = appendMany(a, actor, "A");
        other.join();

        PredictionEventStore reopened = new PredictionEventStore(root, projectId);

        // Writers without the project append lock may give up after losing every retry; no event they
        // reported as appended may be lost, and the reopened, fully verified chain must hold them all.
        assertEquals(appendedByA + appendedByB[0], reopened.headSequence());
        assertEquals(appendedByA + appendedByB[0], reopened.capabilityRequestProjection().records().size());
        assertTrue(appendedByA + appendedByB[0] >= 10);
    }

    private static int appendMany(PredictionEventStore store, NodeIdentity actor, String writer) {
        int appended = 0;
        try {
            for (int index = 0; index < 10; index++) {
                appendRequest(store, actor, actor, String.format("req_TEST1234567890ABC%s%02d", writer, index));
                appended++;
            }
        } catch (CoordinationException lostEveryRetry) {
            assertEquals("event append lost its sequence to other writers", lostEveryRetry.getMessage());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        return appended;
    }

    @Test
    void forkedChainAfterAVerifiedLogWasRememberedIsRejectedByLoadAndByAppend(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        NodeIdentity actor = NodeIdentity.generate();
        PredictionEventStore warm = new PredictionEventStore(root, projectId);
        appendRequest(warm, actor, actor, "req_TEST1234567890ABC001");
        appendRequest(warm, actor, actor, "req_TEST1234567890ABC002");
        byte[] wrongPrevious = new byte[32];
        Arrays.fill(wrongPrevious, (byte) 1);
        writeEvent(root, projectId, 3L, 3L, wrongPrevious);

        assertEquals(INVALID_LOG, assertThrows(CoordinationException.class, () -> new PredictionEventStore(root, projectId)).getMessage());
        assertEquals(INVALID_LOG, assertThrows(CoordinationException.class,
                () -> warm.append(UUID.randomUUID(), PredictionEventType.PARTICIPANT_HEARTBEAT, actor.nodeId(), new byte[0], actor)).getMessage());
    }

    @Test
    void remembersNothingItCannotRecheck(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        NodeIdentity actor = NodeIdentity.generate();
        PredictionEventStore warm = new PredictionEventStore(root, projectId);
        for (int index = 1; index <= 3; index++) {
            appendRequest(warm, actor, actor, "req_TEST1234567890ABC00" + index);
        }
        Path events = root.resolve("events");
        Path head = events.resolve(String.format("%020d.sce", 3L));
        byte[] headBytes = Files.readAllBytes(head);

        byte[] flipped = headBytes.clone();
        flipped[flipped.length / 2] ^= 1;
        Files.write(head, flipped);
        assertThrows(Exception.class, () -> new PredictionEventStore(root, projectId), "the head file changed");

        Files.write(head, headBytes);
        assertEquals(3L, new PredictionEventStore(root, projectId).headSequence(), "restoring the file restores the log");

        Path middle = events.resolve(String.format("%020d.sce", 2L));
        byte[] middleBytes = Files.readAllBytes(middle);
        Files.delete(middle);
        assertEquals(INVALID_LOG, assertThrows(CoordinationException.class, () -> new PredictionEventStore(root, projectId)).getMessage(), "a gap");
        Files.write(middle, middleBytes);

        // The applied order names the entry, so a log that lost its head is contradictory state, not a shorter log.
        Files.delete(head);
        assertEquals(INVALID_LOG, assertThrows(CoordinationException.class, () -> new PredictionEventStore(root, projectId)).getMessage(),
                "a log shorter than the applied order is not served from memory");

        // Without the lines naming it, a shorter log is just a shorter log, and nothing is served from memory.
        Files.write(root.resolve("applied.order"), "self 1\nself 2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(2L, new PredictionEventStore(root, projectId).headSequence(), "a shorter log is not served from memory");
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
