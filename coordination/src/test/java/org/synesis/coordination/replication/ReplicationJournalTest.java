package org.synesis.coordination.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
