package org.synesis.coordination.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The journal says why a session to a peer ended (2026-10-16: a link dropped about fifteen minutes into a real run
 * and nothing recorded the cause), and stays small.
 */
final class ReplicationJournalTest {

    @TempDir
    Path root;

    @Test
    void theLastDisconnectIsReadBackWithItsReasonAndTime() {
        ReplicationJournal journal = new ReplicationJournal(root);
        journal.record(Instant.parse("2026-10-16T14:00:00Z"), "CONNECTED peer=nodeA expiry=30s");
        journal.record(Instant.parse("2026-10-16T14:15:11Z"), "DISCONNECT peer=nodeA reason=LIVENESS_EXPIRED liveness=EXPIRED lasted=911s");
        journal.record(Instant.parse("2026-10-16T14:15:12Z"), "REDIAL_FAILED attempt=1 cause=ConnectTimeoutException");

        assertEquals(Optional.of("LIVENESS_EXPIRED 2026-10-16T14:15:11Z peer=nodeA reason=LIVENESS_EXPIRED liveness=EXPIRED lasted=911s"),
                ReplicationJournal.lastDisconnect(root));
        assertEquals(3, ReplicationJournal.read(root).size());
    }

    @Test
    void noSessionEndedMeansNoLastDisconnect() {
        new ReplicationJournal(root).record("CONNECTED peer=nodeA expiry=30s");

        assertEquals(Optional.empty(), ReplicationJournal.lastDisconnect(root));
        assertEquals(Optional.empty(), ReplicationJournal.lastDisconnect(root.resolve("absent")));
    }

    @Test
    void aPeerIsConnectedWhenItsNewestSessionLineSaysSoAndAReplacedSessionsEndDoesNotDisconnectIt() {
        ReplicationJournal journal = new ReplicationJournal(root);
        journal.record("CONNECTED peer=nodeA expiry=30s");
        journal.record("CONNECTED peer=nodeB expiry=30s");
        journal.record("DISCONNECT peer=nodeB reason=LIVENESS_EXPIRED liveness=EXPIRED lasted=840s");
        journal.record("REDIAL_FAILED attempt=1 cause=ConnectTimeoutException");
        journal.record("CONNECTED peer=nodeC expiry=30s");
        journal.record("CONNECTED peer=nodeC expiry=30s");
        journal.record("DISCONNECT peer=nodeC reason=TRANSPORT_CLOSED liveness=ALIVE lasted=3s replaced=true");

        assertEquals(Set.of("nodeA", "nodeC"), ReplicationJournal.connectedPeers(root));
        assertEquals(Set.of(), ReplicationJournal.connectedPeers(root.resolve("absent")));
    }

    /**
     * The leader's journal of the PC and VM run s1-run4: the VM's cable was cut for twenty minutes and it came back with
     * nothing new to send, so only this line says that it is reachable again.
     */
    @Test
    void eachConnectedPeerIsReadWithTheStartOfItsCurrentSession() throws Exception {
        ReplicationJournal journal = new ReplicationJournal(root);
        journal.record(Instant.parse("2026-10-18T07:56:38.278714200Z"), "CONNECTED peer=sl1-vm expiry=30s");
        journal.record(Instant.parse("2026-10-18T07:58:43.270483700Z"), "DISCONNECT peer=sl1-vm reason=LIVENESS_EXPIRED liveness=EXPIRED lasted=125s");
        assertEquals(Map.of(), ReplicationJournal.connectedSince(root), "cut off");
        journal.record(Instant.parse("2026-10-18T08:18:20.514915700Z"), "CONNECTED peer=sl1-vm expiry=30s");
        journal.record(Instant.parse("2026-10-18T08:18:21Z"), "CONNECTED peer=sl1-third expiry=30s");
        journal.record(Instant.parse("2026-10-18T08:18:22Z"), "DISCONNECT peer=sl1-third reason=TRANSPORT_CLOSED liveness=ALIVE lasted=1s replaced=true");

        assertEquals(Map.of("sl1-vm", Instant.parse("2026-10-18T08:18:20.514915700Z"), "sl1-third", Instant.parse("2026-10-18T08:18:21Z")), ReplicationJournal.connectedSince(root));
        assertEquals(Set.of("sl1-vm", "sl1-third"), ReplicationJournal.connectedPeers(root));

        Files.writeString(root.resolve(ReplicationJournal.FILE), "yesterday CONNECTED peer=sl1-vm expiry=30s\n");
        assertEquals(Map.of("sl1-vm", Instant.EPOCH), ReplicationJournal.connectedSince(root), "a start whose time is unreadable is the oldest, so it resumes nobody");
    }

    @Test
    void onlyTheLastLinesAreKeptAndAnEventCannotSpanLines() {
        ReplicationJournal journal = new ReplicationJournal(root);
        journal.record(Instant.parse("2026-10-16T14:00:00Z"), "DISCONNECT peer=old reason=TRANSPORT_CLOSED");
        for (int index = 0; index < ReplicationJournal.MAX_LINES + 20; index++) {
            journal.record("REDIAL_OK attempts=" + index);
        }
        journal.record("DISCONNECT peer=nodeB reason=PROTOCOL_ERROR\nREDIAL_OK forged=true");

        List<String> lines = ReplicationJournal.read(root);
        assertEquals(ReplicationJournal.MAX_LINES, lines.size());
        assertTrue(lines.getLast().endsWith("DISCONNECT peer=nodeB reason=PROTOCOL_ERROR REDIAL_OK forged=true"), lines.getLast());
        assertTrue(ReplicationJournal.lastDisconnect(root).orElseThrow().startsWith("PROTOCOL_ERROR "));
        assertTrue(lines.stream().noneMatch(line -> line.contains("peer=old")), "the oldest line was dropped");
    }
}
