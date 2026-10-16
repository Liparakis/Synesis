package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.exception.CoordinationException;

/**
 * What a restart may resume is read from a strict local file: a file that cannot be understood resumes nothing, and a
 * node that is forgotten is not resumed.
 */
final class ReplicationPeersTest {

    @TempDir
    Path root;

    @Test
    void recordsWhatTheComputerDialedAndHostedAndReadsItBack() throws Exception {
        ReplicationPeers.rememberDialed(root, "node-h", "synesis://join/SLB3-abc");
        ReplicationPeers.rememberHosted(root, 40000, "node-a");
        ReplicationPeers.rememberHosted(root, 40000, "node-b");
        ReplicationPeers.rememberHosted(root, 40001, "node-c");

        ReplicationPeers read = ReplicationPeers.read(root);

        assertEquals(Map.of("node-h", "synesis://join/SLB3-abc"), read.dialed());
        assertEquals(Map.of(40000, Set.of("node-a", "node-b"), 40001, Set.of("node-c")), read.hosted());
    }

    @Test
    void aMissingFileRecordsNothing() throws Exception {
        assertEquals(ReplicationPeers.none(), ReplicationPeers.read(root));
    }

    @Test
    void forgettingANodeDropsEveryRecordOfItAndTheFileWhenNothingIsLeft() throws Exception {
        ReplicationPeers.rememberDialed(root, "node-a", "link-a");
        ReplicationPeers.rememberHosted(root, 40000, "node-a");
        ReplicationPeers.rememberHosted(root, 40000, "node-b");

        ReplicationPeers.forget(root, "node-a");

        ReplicationPeers read = ReplicationPeers.read(root);
        assertTrue(read.dialed().isEmpty());
        assertEquals(Map.of(40000, Set.of("node-b")), read.hosted());
        ReplicationPeers.forget(root, "node-b");
        assertFalse(Files.exists(root.resolve(ReplicationPeers.FILE)));
    }

    @Test
    void retainingOnlyOneNodeDropsTheOthers() throws Exception {
        ReplicationPeers.rememberDialed(root, "node-a", "link-a");
        ReplicationPeers.rememberDialed(root, "node-b", "link-b");
        ReplicationPeers.rememberHosted(root, 40000, "node-c");

        ReplicationPeers.retainOnly(root, Set.of("node-b"));

        assertEquals(new ReplicationPeers(Map.of("node-b", "link-b"), Map.of()), ReplicationPeers.read(root));
    }

    @Test
    void aFileThatDoesNotFollowTheFormatIsAnError() throws Exception {
        for (String text : new String[] {"dial=node-a\n", "dial=node-a link extra\n", "host=0 node-a\n", "host=70000 node-a\n", "host=x node-a\n", "host=40000 node-a\nhost=40000 node-a\n",
                "dial=node-a l1\ndial=node-a l2\n", "grant=node-a\n", "dial=node-a link", "dial= link\n"}) {
            Files.writeString(root.resolve(ReplicationPeers.FILE), text);
            CoordinationException invalid = assertThrows(CoordinationException.class, () -> ReplicationPeers.read(root), text);
            assertEquals(ReplicationPeers.INVALID, invalid.code());
        }
    }

    @Test
    void aWriteReplacesAFileThatCannotBeRead() throws Exception {
        Files.writeString(root.resolve(ReplicationPeers.FILE), "garbage\n");

        ReplicationPeers.rememberHosted(root, 40000, "node-a");

        assertEquals(Map.of(40000, Set.of("node-a")), ReplicationPeers.read(root).hosted());
    }
}
