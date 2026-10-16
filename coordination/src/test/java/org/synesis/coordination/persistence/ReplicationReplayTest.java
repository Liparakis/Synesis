package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.exception.CoordinationException;

/**
 * The replay memory of a restarted host is read strictly: a file that is not exactly what the guard wrote is an error.
 */
final class ReplicationReplayTest {

    @TempDir
    Path root;

    @Test
    void writesAndReadsBackInOrder() throws Exception {
        ReplicationReplay.write(root, List.of("aa", "bb"));

        assertEquals(Optional.of(new java.util.LinkedHashSet<>(List.of("aa", "bb"))), ReplicationReplay.read(root, 4));
    }

    @Test
    void aMissingFileIsAbsentAndAnEmptyOneIsEmpty() throws Exception {
        assertEquals(Optional.empty(), ReplicationReplay.read(root, 4));
        ReplicationReplay.write(root, List.of());
        assertTrue(ReplicationReplay.read(root, 4).orElseThrow().isEmpty());
    }

    @Test
    void aFileAtCapacityLoadsAndOneOverItIsInvalid() throws Exception {
        ReplicationReplay.write(root, List.of("a", "b"));

        assertEquals(2, ReplicationReplay.read(root, 2).orElseThrow().size());
        assertEquals(ReplicationReplay.INVALID, assertThrows(CoordinationException.class, () -> ReplicationReplay.read(root, 1)).code());
    }

    @Test
    void malformedFilesAreInvalid() throws Exception {
        for (String text : new String[] {"a", "a\n\n", "a\na\n", "a b\n", "\n", "a\r\n"}) {
            Files.writeString(root.resolve(ReplicationReplay.FILE), text);
            assertEquals(ReplicationReplay.INVALID, assertThrows(CoordinationException.class, () -> ReplicationReplay.read(root, 8), text).code(), text);
        }
    }
}
