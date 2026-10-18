package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.EventSequence;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.PredictionId;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.membership.MembershipEntries;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.NodeIdentity;

/**
 * What {@code synesis log dump} reads: every log this computer holds in the order it applied them, without writing
 * anything, and without ever stopping at an entry it cannot read.
 */
final class LogDumpTest {

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

    private WorkIntent announce(PredictionEventStore store, NodeIdentity identity, String participant, String path)
            throws Exception {
        UUID intentId = UUID.randomUUID();
        WorkIntent lane = new WorkIntent(intentId, project, participant, "codex", UUID.randomUUID(), "Build a board",
                "CRUD and persistence", "base-commit", List.of(ResourceSelector.pathExact(path)), 1,
                UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of());
        assertTrue(new WorkIntentService(store, identity).announce(lane).acquired());
        return lane;
    }

    private static void pull(PredictionEventStore from, PredictionEventStore to, String writer) throws Exception {
        to.placeCopies(writer, from.entriesAfter(PredictionEventStore.OWN_LOG,
                to.logHeads().getOrDefault(writer, 0L), 1000, Integer.MAX_VALUE));
    }

    /** A computer A that wrote two entries, took two of B's, then wrote two more: A1 A2 B1 B2 A3 A4. */
    private WorkIntent interleaved() throws Exception {
        PredictionEventStore a = open(rootA);
        PredictionEventStore b = open(rootB);
        WorkIntent first = announce(a, nodeA, "agt-a", "src/a.py");
        announce(b, nodeB, "agt-b", "src/b.py");
        pull(b, a, nodeB.nodeId());
        announce(a, nodeA, "agt-c", "src/c.py");
        return first;
    }

    private Path ownFile(long sequence) {
        return rootA.resolve("events").resolve(String.format("%020d.sce", sequence));
    }

    private Path peerFile(long sequence) {
        return rootA.resolve("peers").resolve(nodeB.nodeId()).resolve("events")
                .resolve(String.format("%020d.sce", sequence));
    }

    private static String render(LogDump.Dump dump) {
        List<String> text = new ArrayList<>();
        dump.entries().forEach(entry -> text.add(entry.toString()));
        dump.problems().forEach(text::add);
        dump.summary().forEach(fact -> text.add(fact.key() + "=" + fact.value()));
        return String.join("\n", text);
    }

    @Test
    void everyLogIsPrintedInTheOrderThisComputerAppliedItsEntries() throws Exception {
        interleaved();

        LogDump.Dump dump = LogDump.read(rootA, project);

        List<String> order = Files.readAllLines(rootA.resolve("applied.order"));
        assertEquals(6, order.size());
        assertEquals(order.size(), dump.entries().size());
        for (int index = 0; index < order.size(); index++) {
            String[] line = order.get(index).split(" ");
            LogDump.Entry entry = dump.entries().get(index);
            assertEquals(index + 1L, entry.position());
            assertEquals(line[0], entry.log());
            assertEquals(Long.parseLong(line[1]), entry.sequence());
            assertEquals(line[0].equals("self") ? nodeA.nodeId() : nodeB.nodeId(), entry.writer());
        }
        List<String> writers = dump.entries().stream().map(LogDump.Entry::writer).collect(Collectors.toList());
        assertEquals(List.of(nodeA.nodeId(), nodeA.nodeId(), nodeB.nodeId(), nodeB.nodeId(), nodeA.nodeId(),
                nodeA.nodeId()), writers);
        assertTrue(dump.entries().stream().anyMatch(entry -> entry.type().equals("WORK_INTENT_ANNOUNCED")
                && entry.fields().get("participant").equals("agt-b")), render(dump));
        assertEquals(List.of(), dump.problems());
    }

    @Test
    void theSummaryComesFromTheProjectionsAndShowsEachLane() throws Exception {
        WorkIntent first = interleaved();
        PredictionEventStore a = open(rootA);
        a.append(first.intentId(), PredictionEventType.WORK_INTENT_RELEASED, nodeA.nodeId(),
                CollaborationCodec.encodeRelease(first.intentId()), nodeA);

        LogDump.Dump dump = LogDump.read(rootA, project);

        List<String> lanes = dump.summary().stream().filter(fact -> fact.key().equals("SUMMARY_LANE"))
                .map(LogDump.Fact::value).collect(Collectors.toList());
        assertEquals(3, lanes.size(), render(dump));
        assertTrue(lanes.stream().anyMatch(lane -> lane.startsWith(first.intentId() + " ") && lane.contains("ENDED")),
                lanes.toString());
        assertEquals(2, lanes.stream().filter(lane -> lane.contains("ACTIVE")).count(), lanes.toString());
        assertTrue(dump.entries().stream().anyMatch(entry -> entry.type().equals("WORK_INTENT_RELEASED")
                && entry.fields().get("lane").equals(first.intentId().toString())));
    }

    @Test
    void readingChangesNothingOnDisk() throws Exception {
        interleaved();
        List<String> before = listing(rootA);

        LogDump.read(rootA, project);
        LogDump.read(rootA, project);

        assertEquals(before, listing(rootA));
    }

    @Test
    void anEntryOfAnUnknownTypePrintsAsUnknownAndTheOthersStillPrint() throws Exception {
        interleaved();
        byte[] bytes = Files.readAllBytes(ownFile(1));
        // Header: magic, version, three UUIDs, then the sequence and the wire code of the entry.
        ByteBuffer.wrap(bytes).putLong(56, 5L).putInt(64, 9999);
        Files.write(ownFile(5), bytes);

        LogDump.Dump dump = LogDump.read(rootA, project);

        LogDump.Entry unknown = dump.entries().stream().filter(entry -> entry.type().equals("UNKNOWN")).findFirst()
                .orElseThrow();
        assertEquals("9999", unknown.raw());
        assertEquals(5L, unknown.sequence());
        assertTrue(unknown.toString().contains("UNKNOWN type=9999"), unknown.toString());
        assertEquals(7, dump.entries().size(), render(dump));
        assertEquals(4, dump.entries().stream().filter(entry -> entry.log().equals("self")
                && !entry.type().equals("UNKNOWN")).count());
        assertTrue(dump.summary().stream().anyMatch(fact -> fact.key().equals("SUMMARY_UNAVAILABLE")), render(dump));
    }

    @Test
    void aTruncatedEntryIsAProblemLineAndTheDumpGoesOn() throws Exception {
        interleaved();
        byte[] whole = Files.readAllBytes(peerFile(2));
        Files.write(peerFile(2), java.util.Arrays.copyOf(whole, whole.length / 2));

        LogDump.Dump dump = LogDump.read(rootA, project);

        assertEquals(6, dump.entries().size(), render(dump));
        assertEquals("UNREADABLE", dump.entries().get(3).type());
        assertEquals(nodeB.nodeId(), dump.entries().get(3).log());
        assertEquals("self", dump.entries().get(4).log());
        assertEquals("WORK_INTENT_ANNOUNCED", dump.entries().get(5).type());
        assertEquals(1, dump.problems().size(), dump.problems().toString());
        assertTrue(dump.problems().getFirst().startsWith(nodeB.nodeId() + " 2 "), dump.problems().getFirst());
        assertTrue(dump.summary().stream().anyMatch(fact -> fact.key().equals("SUMMARY_UNAVAILABLE")), render(dump));
    }

    @Test
    void aMalformedLineOfTheAppliedOrderIsAProblemAndTheOwnEntriesStillPrint() throws Exception {
        interleaved();
        Files.writeString(rootA.resolve("applied.order"), "self 1\nnonsense\nself 2\n", StandardCharsets.UTF_8);

        LogDump.Dump dump = LogDump.read(rootA, project);

        assertTrue(dump.problems().stream().anyMatch(problem -> problem.contains("applied.order")),
                dump.problems().toString());
        assertTrue(dump.entries().size() >= 2, render(dump));
    }

    @Test
    void aProjectWithNoLogsPrintsNothingAndCreatesNothing() throws Exception {
        LogDump.Dump missing = LogDump.read(rootA, project);

        assertEquals(List.of(), missing.entries());
        assertEquals(List.of(), missing.problems());
        assertFalse(Files.exists(rootA));

        Files.createDirectories(rootA);
        LogDump.Dump empty = LogDump.read(rootA, project);

        assertEquals(List.of(), empty.entries());
        assertEquals(List.of(), empty.problems());
        assertEquals(List.of(), listing(rootA));
        assertTrue(empty.summary().stream().anyMatch(fact -> fact.key().equals("SUMMARY_LANES")
                && fact.value().equals("0")), render(empty));
    }

    @Test
    void noSecretIsPrinted() throws Exception {
        String invitation = "synesis-invite://SECRET-TOKEN-0123456789";
        byte[] ownerKey = nodeA.publicKeyEncoded();
        byte[] recoveryKey = nodeB.publicKeyEncoded();
        Files.createDirectories(rootA.resolve("events"));
        PredictionEvent previous = null;
        long sequence = 0;
        for (var entry : List.of(
                java.util.Map.entry(PredictionEventType.PROJECT_SHARED,
                        new MembershipEntries.ProjectShared(ownerKey, recoveryKey).encode()),
                java.util.Map.entry(PredictionEventType.MEMBER_CARD,
                        new MembershipEntries.MemberCard(invitation).encode()))) {
            previous = PredictionEvent.create(new ProjectId(project), new PredictionId(UUID.randomUUID()),
                    new EventSequence(++sequence), entry.getKey(), new NodeId(nodeA.nodeId()), entry.getValue(),
                    previous == null ? new byte[32] : previous.digest(), nodeA, System.currentTimeMillis());
            Files.write(ownFile(sequence), previous.encoded());
        }

        LogDump.Dump dump = LogDump.read(rootA, project);
        String text = render(dump);

        assertEquals(2, dump.entries().size(), text);
        assertEquals("PROJECT_SHARED", dump.entries().get(0).type());
        assertEquals("MEMBER_CARD", dump.entries().get(1).type());
        for (String secret : List.of(invitation, "SECRET-TOKEN", Base64.getEncoder().encodeToString(ownerKey),
                Base64.getEncoder().encodeToString(recoveryKey), HexFormat.of().formatHex(ownerKey),
                HexFormat.of().formatHex(recoveryKey))) {
            assertFalse(text.contains(secret), secret);
        }
        assertNotNull(dump.summary());
    }

    @Test
    void aFilterKeepsOnlyTheNamedTypesButNotTheSummary() throws Exception {
        interleaved();

        LogDump.Dump dump = LogDump.read(rootA, project);
        List<LogDump.Entry> announced = LogDump.only(dump.entries(), "work_intent_announced");

        assertEquals(3, announced.size());
        assertTrue(announced.stream().allMatch(entry -> entry.type().equals("WORK_INTENT_ANNOUNCED")));
        assertFalse(dump.summary().isEmpty());
    }

    private static List<String> listing(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).map(path -> root.relativize(path) + ":" + path.toFile().length()
                    + ":" + path.toFile().lastModified()).sorted().collect(Collectors.toList());
        }
    }
}
