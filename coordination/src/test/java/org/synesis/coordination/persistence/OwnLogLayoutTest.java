package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.EventSequence;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.PredictionId;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.link.identity.NodeIdentity;

/**
 * What one computer keeps on disk since ADR-0098 step 4: its own log in {@code events/}, byte-identical copies of the
 * other computers' logs in {@code peers/<node>/events/}, and {@code applied.order}, the order in which entries took
 * effect here, whose position is the local revision.
 */
final class OwnLogLayoutTest {

    private static final String ORDER = "applied.order";

    private final UUID project = UUID.randomUUID();
    private Path rootA;
    private Path rootB;
    private NodeIdentity nodeA;
    private NodeIdentity nodeB;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        rootA = temp.resolve("a");
        rootB = temp.resolve("b");
        nodeA = NodeIdentity.generate();
        nodeB = NodeIdentity.generate();
    }

    private PredictionEventStore open(Path root) throws Exception {
        return new PredictionEventStore(root, project);
    }

    private WorkIntent lane(UUID group, String participant, String path) {
        UUID intentId = UUID.randomUUID();
        return new WorkIntent(intentId, project, participant, "codex", UUID.randomUUID(), "Build a board",
                "CRUD and persistence", "base-commit", List.of(ResourceSelector.pathExact(path)), 1, group,
                WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED, WorkIntent.Role.PRODUCER,
                List.of());
    }

    private WorkIntent announce(PredictionEventStore store, NodeIdentity identity, UUID group, String participant,
            String path) throws Exception {
        WorkIntent lane = lane(group, participant, path);
        assertTrue(new WorkIntentService(store, identity).announce(lane).acquired());
        return lane;
    }

    private WorkIntent announce(Path root, NodeIdentity identity, String participant, String path) throws Exception {
        return announce(open(root), identity, UUID.randomUUID(), participant, path);
    }

    /** What a pull does: the entries of {@code log} that {@code to} lacks, placed as the copy of {@code writer}'s log. */
    private static int pull(PredictionEventStore from, String log, PredictionEventStore to, String writer)
            throws Exception {
        return to.placeCopies(writer, from.entriesAfter(log, to.logHeads().getOrDefault(writer, 0L), 1000,
                Integer.MAX_VALUE));
    }

    private static List<String> lines(Path root) throws IOException {
        return Files.readAllLines(root.resolve(ORDER), StandardCharsets.UTF_8);
    }

    private static long entryFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".sce")).count();
        }
    }

    private static List<String> encodedSet(PredictionEventStore store) {
        return store.events().stream().map(event -> HexFormat.of().formatHex(event.encoded())).sorted()
                .collect(Collectors.toList());
    }

    private static void assertIdenticalFiles(Path left, Path right) throws IOException {
        assertEquals(entryFiles(left), entryFiles(right));
        assertTrue(entryFiles(left) > 0);
        try (var files = Files.list(left)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".sce")).toList()) {
                assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(right.resolve(file.getFileName())),
                        file.getFileName().toString());
            }
        }
    }

    private byte[] handmade(NodeIdentity signer, long sequence, PredictionEvent previous, PredictionEventType type,
            byte[] payload) throws Exception {
        return PredictionEvent.create(new ProjectId(project), new PredictionId(UUID.randomUUID()),
                new EventSequence(sequence), type, new NodeId(signer.nodeId()), payload,
                previous == null ? new byte[32] : previous.digest(), signer, System.currentTimeMillis()).encoded();
    }

    @Test
    void anOwnAppendWritesTheOwnLogAndOneLineOfTheAppliedOrder() throws Exception {
        announce(open(rootA), nodeA, UUID.randomUUID(), "agt-a", "src/a.py");
        PredictionEventStore store = open(rootA);

        assertEquals(2, entryFiles(rootA.resolve("events")));
        assertEquals(List.of("self 1", "self 2"), lines(rootA));
        assertEquals(2, store.headSequence());
        assertEquals(2, store.events().size());
        assertFalse(Files.exists(rootA.resolve("peers")));
        assertEquals(List.of(), store.problems());
        assertEquals(2, PredictionEventStore.recordedEvents(rootA));
    }

    @Test
    void twoComputersEndWithByteIdenticalCopiesOfBothLogsAndTheRevisionIsThePositionInTheAppliedOrder()
            throws Exception {
        PredictionEventStore a = open(rootA);
        PredictionEventStore b = open(rootB);
        announce(a, nodeA, UUID.randomUUID(), "agt-a", "src/a.py");
        announce(b, nodeB, UUID.randomUUID(), "agt-b", "src/b.py");

        assertEquals(2, pull(b, PredictionEventStore.OWN_LOG, a, nodeB.nodeId()));
        assertEquals(2, pull(a, PredictionEventStore.OWN_LOG, b, nodeA.nodeId()));

        assertIdenticalFiles(rootA.resolve("events"), rootB.resolve("peers").resolve(nodeA.nodeId()).resolve("events"));
        assertIdenticalFiles(rootB.resolve("events"), rootA.resolve("peers").resolve(nodeB.nodeId()).resolve("events"));
        assertEquals(encodedSet(a), encodedSet(b));
        assertEquals(4, a.headSequence());
        assertEquals(4, b.headSequence());
        assertEquals(4, PredictionEventStore.recordedEvents(rootA));
        assertEquals(List.of("self 1", "self 2", nodeB.nodeId() + " 1", nodeB.nodeId() + " 2"), lines(rootA));
        assertEquals(List.of("self 1", "self 2", nodeA.nodeId() + " 1", nodeA.nodeId() + " 2"), lines(rootB));

        // The i-th line names the i-th entry that took effect: the revision is the position in the order.
        List<String> order = lines(rootA);
        for (int index = 0; index < order.size(); index++) {
            String[] line = order.get(index).split(" ");
            List<byte[]> log = a.entriesAfter(line[0].equals("self") ? PredictionEventStore.OWN_LOG : line[0],
                    Long.parseLong(line[1]) - 1, 1, Integer.MAX_VALUE);
            assertArrayEquals(log.getFirst(), a.events().get(index).encoded(), order.get(index));
        }
        assertEquals(a.events().subList(2, 4), a.eventsAfter(2));
    }

    @Test
    void aReopenedStoreReplaysToTheStateAndRevisionOfTheLiveOne() throws Exception {
        PredictionEventStore a = open(rootA);
        PredictionEventStore b = open(rootB);
        WorkIntent lane = announce(a, nodeA, UUID.randomUUID(), "agt-a", "src/a.py");
        announce(b, nodeB, UUID.randomUUID(), "agt-b", "src/b.py");
        pull(b, PredictionEventStore.OWN_LOG, a, nodeB.nodeId());
        a.append(lane.intentId(), PredictionEventType.WORK_INTENT_RELEASED, nodeA.nodeId(),
                org.synesis.coordination.domain.collaboration.CollaborationCodec.encodeRelease(lane.intentId()), nodeA);

        PredictionEventStore reopened = open(rootA);

        assertEquals(a.headSequence(), reopened.headSequence());
        assertEquals(5, reopened.headSequence());
        assertEquals(a.events().size(), reopened.events().size());
        for (int index = 0; index < a.events().size(); index++) {
            assertArrayEquals(a.events().get(index).encoded(), reopened.events().get(index).encoded());
        }
        assertEquals(a.collaborationProjection().activeIntents().size(),
                reopened.collaborationProjection().activeIntents().size());
        assertEquals(1, reopened.collaborationProjection().activeIntents().size());
        assertEquals(a.logHeads(), reopened.logHeads());
        assertEquals(a.problems(), reopened.problems());
    }

    @Test
    void anOwnEntryWithoutALineStillAppliesAndGetsItsLineBeforeTheNextOne() throws Exception {
        announce(open(rootA), nodeA, UUID.randomUUID(), "agt-a", "src/a.py");
        List<String> written = lines(rootA);
        // A writer of an earlier build, or one cut short between the entry and its line.
        Files.writeString(rootA.resolve(ORDER), written.getFirst() + "\n", StandardCharsets.UTF_8);

        PredictionEventStore reopened = open(rootA);

        assertEquals(2, reopened.events().size());
        assertEquals(2, reopened.headSequence());
        assertEquals(List.of("self 1"), lines(rootA), "a reader never writes the order");

        announce(reopened, nodeA, UUID.randomUUID(), "agt-c", "src/c.py");

        long own = reopened.logHeads().get(PredictionEventStore.OWN_LOG);
        assertTrue(own > 2);
        List<String> expected = new ArrayList<>();
        for (long sequence = 1; sequence <= own; sequence++) {
            expected.add("self " + sequence);
        }
        assertEquals(expected, lines(rootA));
        assertEquals(own, open(rootA).headSequence());
    }

    @Test
    void aPartialLastLineOfTheAppliedOrderIsIgnoredAndRemovedByTheNextWriter() throws Exception {
        announce(open(rootA), nodeA, UUID.randomUUID(), "agt-a", "src/a.py");
        Files.write(rootA.resolve(ORDER), "self 3".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        PredictionEventStore reopened = open(rootA);

        assertEquals(2, reopened.events().size());
        assertEquals(2, reopened.headSequence());
        assertTrue(Files.readString(rootA.resolve(ORDER)).endsWith("self 3"), "a reader does not repair it");

        announce(reopened, nodeA, UUID.randomUUID(), "agt-c", "src/c.py");

        long own = reopened.logHeads().get(PredictionEventStore.OWN_LOG);
        List<String> expected = new ArrayList<>();
        for (long sequence = 1; sequence <= own; sequence++) {
            expected.add("self " + sequence);
        }
        assertEquals(expected, lines(rootA));
        assertTrue(Files.readString(rootA.resolve(ORDER)).endsWith("\n"));
        assertEquals(own, open(rootA).headSequence());
    }

    @Test
    void anArrivingEntryThatNamesWhatHasNotArrivedWaitsAndAppliesOnceItsLogArrives() throws Exception {
        Path rootC = rootA.resolveSibling("c");
        PredictionEventStore a = open(rootA);
        PredictionEventStore b = open(rootB);
        PredictionEventStore c = open(rootC);
        UUID group = UUID.randomUUID();
        announce(a, nodeA, group, "agt-a", "src/a.py");
        pull(a, PredictionEventStore.OWN_LOG, b, nodeA.nodeId());
        // B joins the work group A made: its entry names a group that only A's log creates.
        announce(b, nodeB, group, "agt-b", "src/b.py");

        long heldBack = pull(b, PredictionEventStore.OWN_LOG, c, nodeB.nodeId());

        assertTrue(heldBack > 0);
        List<String> waiting = c.problems();
        assertEquals(1, waiting.size(), waiting.toString());
        assertTrue(waiting.getFirst().matches(java.util.regex.Pattern.quote(nodeB.nodeId()) + " \\d+ waiting [A-Z_]+"),
                waiting.getFirst());
        long appliedWhileWaiting = c.headSequence();
        assertTrue(appliedWhileWaiting < b.logHeads().get(PredictionEventStore.OWN_LOG));
        assertEquals(heldBack, PredictionEventStore.recordedEvents(rootC), "a held entry is placed and kept");

        pull(a, PredictionEventStore.OWN_LOG, c, nodeA.nodeId());

        assertEquals(List.of(), c.problems());
        assertEquals(a.logHeads().get(PredictionEventStore.OWN_LOG) + b.logHeads().get(PredictionEventStore.OWN_LOG),
                c.headSequence());
        assertEquals(2, c.collaborationProjection().activeIntents().size());
        // The wait ends the same way after a restart.
        assertEquals(c.headSequence(), open(rootC).headSequence());
        assertEquals(List.of(), open(rootC).problems());
    }

    @Test
    void aCopySignedByAnotherNodeIsRefusedAsDivergedAndNothingIsPlaced() throws Exception {
        PredictionEventStore a = open(rootA);
        PredictionEventStore b = open(rootB);
        announce(b, nodeB, UUID.randomUUID(), "agt-b", "src/b.py");
        List<byte[]> bsLog = b.entriesAfter(0, 100, Integer.MAX_VALUE);

        CoordinationException refusal = assertThrows(CoordinationException.class,
                () -> a.placeCopies(nodeA.nodeId() + "-other", bsLog));

        assertEquals("LOG_COPY_DIVERGED", refusal.code());
        assertEquals(0, PredictionEventStore.recordedEvents(rootA));
        assertEquals(0, a.headSequence());
        assertEquals(List.of(), a.problems());
        // The log of B is placed under B's own node.
        assertEquals(bsLog.size(), a.placeCopies(nodeB.nodeId(), bsLog));
    }

    @Test
    void aNodeNameThatCannotBeAFolderIsRefused() throws Exception {
        PredictionEventStore a = open(rootA);
        for (String bad : List.of("", "..", "../x", "a/b", "self", "a b")) {
            CoordinationException refusal = assertThrows(CoordinationException.class,
                    () -> a.placeCopies(bad, List.of()), bad);
            assertEquals("LOG_COPY_NODE_INVALID", refusal.code(), bad);
        }
        assertFalse(Files.exists(rootA.resolve("peers")));
    }

    @Test
    void anIntegrationEntryOfAnotherComputersCopyNamingALaneItDidNotIntroduceCountsForNothingAndTakesNoRevision()
            throws Exception {
        PredictionEventStore a = open(rootA);
        WorkIntent lane = announce(a, nodeA, UUID.randomUUID(), "agt-a", "src/a.py");
        byte[] snapshot = MemberReplicationTest.snapshotOf(lane, "snap_a");
        a.append(lane.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, nodeA.nodeId(), snapshot, nodeA);
        long held = a.headSequence();
        // B did not introduce agt-a, so B's integration entry for its snapshot acts for a participant it does not own.
        List<byte[]> entries = List.of(handmade(nodeB, 1, null, PredictionEventType.TASK_INTEGRATED, snapshot));

        assertEquals(entries.size(), a.placeCopies(nodeB.nodeId(), entries));

        String verdict = nodeB.nodeId() + " 1 PARTICIPANT_NOT_OWNED_BY_SIGNER";
        assertEquals(List.of(verdict), a.problems());
        assertEquals(held, a.headSequence());
        assertTrue(a.events().stream().noneMatch(event -> event.type() == PredictionEventType.TASK_INTEGRATED));
        assertTrue(lines(rootA).contains(verdict));
        assertEquals(held + 1, PredictionEventStore.recordedEvents(rootA), "the entry stays in the copy");

        // A restart reads the same verdict from the applied order; it is not judged again.
        PredictionEventStore reopened = open(rootA);
        assertEquals(a.problems(), reopened.problems());
        assertEquals(a.headSequence(), reopened.headSequence());
    }

    @Test
    void aSharedStepTwoLogIsMovedToAnArchiveOnceAndTheProjectStartsAFreshLog() throws Exception {
        announce(open(rootA), nodeA, UUID.randomUUID(), "agt-a", "src/a.py");
        // A step-2 folder: one shared log, a role file naming a member, and no applied order.
        Files.delete(rootA.resolve(ORDER));
        ReplicationSettings.leader(Set.of(nodeB.nodeId())).write(rootA);

        PredictionEventStore fresh = open(rootA);

        assertEquals(2, entryFiles(rootA.resolve(Step2Archive.ARCHIVE)));
        assertTrue(Files.exists(rootA.resolve(ReplicationSettings.FILE + ".step2")));
        assertFalse(Files.exists(rootA.resolve(ReplicationSettings.FILE)));
        assertEquals(0, entryFiles(rootA.resolve("events")));
        assertTrue(Files.exists(rootA.resolve(ORDER)));
        assertEquals(0, fresh.headSequence());
        assertEquals(List.of(), fresh.events());

        announce(fresh, nodeA, UUID.randomUUID(), "agt-c", "src/c.py");
        PredictionEventStore again = open(rootA);

        assertEquals(2, entryFiles(rootA.resolve(Step2Archive.ARCHIVE)), "the archive is made once");
        assertFalse(Files.exists(rootA.resolve(Step2Archive.ARCHIVE + "-2")));
        assertTrue(entryFiles(rootA.resolve("events")) >= 2);
        assertEquals(entryFiles(rootA.resolve("events")), again.headSequence());
    }

    @Test
    void aSharedStepTwoMemberLogIsArchivedToo() throws Exception {
        announce(open(rootA), nodeA, UUID.randomUUID(), "agt-a", "src/a.py");
        Files.delete(rootA.resolve(ORDER));
        ReplicationSettings.member(nodeB.nodeId()).write(rootA);

        PredictionEventStore fresh = open(rootA);

        assertEquals(2, entryFiles(rootA.resolve(Step2Archive.ARCHIVE)));
        assertTrue(Files.exists(rootA.resolve(ReplicationSettings.FILE + ".step2")));
        assertEquals(0, fresh.headSequence());
    }

    @Test
    void aLogOnOneComputerIsLeftAloneWhetherOrNotItHasAnOrderYet() throws Exception {
        announce(open(rootA), nodeA, UUID.randomUUID(), "agt-a", "src/a.py");
        Files.delete(rootA.resolve(ORDER));

        PredictionEventStore plain = open(rootA);

        assertFalse(Files.exists(rootA.resolve(Step2Archive.ARCHIVE)));
        assertEquals(2, entryFiles(rootA.resolve("events")));
        assertEquals(2, plain.headSequence());
        assertTrue(Files.exists(rootA.resolve(ORDER)));

        // A leader with no member yet is one computer as well.
        Files.delete(rootA.resolve(ORDER));
        ReplicationSettings.leader(Set.of()).write(rootA);

        PredictionEventStore alone = open(rootA);

        assertFalse(Files.exists(rootA.resolve(Step2Archive.ARCHIVE)));
        assertTrue(Files.exists(rootA.resolve(ReplicationSettings.FILE)));
        assertEquals(2, alone.headSequence());
    }
}
