package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.link.identity.NodeIdentity;

/**
 * The role of a computer's log is persisted per coordination folder, so every process that opens the log resolves
 * the same one; a file that cannot be understood never turns the log into a leader's.
 */
final class ReplicationRoleFileTest {

    private final UUID project = UUID.randomUUID();
    private NodeIdentity self;

    @BeforeEach
    void setUp() throws Exception {
        self = NodeIdentity.generate();
    }

    private WorkIntent lane(String path) {
        UUID intentId = UUID.randomUUID();
        return new WorkIntent(intentId, project, "agt-1", "codex", UUID.randomUUID(), "goal", "acceptance",
                "base-commit", List.of(ResourceSelector.pathExact(path)), 1, UUID.randomUUID(),
                WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of());
    }

    private void announce(Path root) throws Exception {
        assertTrue(new WorkIntentService(new PredictionEventStore(root, project), self).announce(lane("src/a.py")).acquired());
    }

    @Test
    void aFolderWithNoSettingsIsALeaderAndAppendsLocallyAsBefore(@TempDir Path root) throws Exception {
        announce(root);

        assertEquals(2, PredictionEventStore.recordedEvents(root));
        assertFalse(Files.exists(root.resolve(ReplicationSettings.FILE)));
    }

    @Test
    void aLeaderFileKeepsTheLogDecidingItsOwnEntries(@TempDir Path root) throws Exception {
        ReplicationSettings.leader(Set.of("sl1-a", "sl1-b")).write(root);

        announce(root);

        assertEquals(2, PredictionEventStore.recordedEvents(root));
        assertEquals(Set.of("sl1-a", "sl1-b"), ReplicationSettings.read(root).orElseThrow().members());
    }

    @Test
    void aMemberFileMakesEveryOpenAMemberThatAppendsToItsOwnLogWithNoLeaderToReach(@TempDir Path root)
            throws Exception {
        ReplicationSettings.member("sl1-leader").write(root);

        announce(root);

        assertEquals(2, PredictionEventStore.recordedEvents(root));
        assertTrue(new PredictionEventStore(root, project).isReplicationMember());
        assertEquals(2, new PredictionEventStore(root, project).events().size());
    }

    @Test
    void aFileThatCannotBeUnderstoodRefusesEveryAppendAndIsNeverALeader(@TempDir Path root) throws Exception {
        for (String text : List.of("", "\n", "role=leader", "role=boss\n", "role=member\n", "role=member\nleader=\n",
                "role=member\nleader=a b\n", "role=member\nleader=a\nleader=b\n", "role=member\nleader=a\nmember=b\n",
                "role=leader\nleader=a\n", "role=leader\nmember=a\nmember=a\n", "leader=a\nrole=member\n",
                "role=member\r\nleader=a\r\n", "role = member\nleader=a\n", "role=member\nleader=a\nextra=1\n",
                "role=member\nleader=" + "x".repeat(129) + "\n", "{\"role\":\"member\"}\n")) {
            Files.writeString(root.resolve(ReplicationSettings.FILE), text);

            IOException refusal = assertThrows(IOException.class, () -> announce(root), text);

            assertEquals(ReplicationSettings.INVALID, refusal.getMessage(), text);
            assertEquals(0, PredictionEventStore.recordedEvents(root), text);
            assertTrue(new PredictionEventStore(root, project).isReplicationMember(), text);
            assertThrows(CoordinationException.class, () -> ReplicationSettings.read(root), text);
        }
    }

    @Test
    void aLogWithARefusingRoleCanStillBeRead(@TempDir Path root) throws Exception {
        announce(root);
        Files.writeString(root.resolve(ReplicationSettings.FILE), "garbage");

        assertEquals(2, new PredictionEventStore(root, project).events().size());
    }

    @Test
    void aSettingsFileThatIsTooLargeOrNotAFileIsInvalid(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(ReplicationSettings.FILE), "role=leader\n" + "member=x\n".repeat(10_000));
        assertEquals(ReplicationSettings.INVALID,
                assertThrows(CoordinationException.class, () -> ReplicationSettings.read(root)).code());
        Files.delete(root.resolve(ReplicationSettings.FILE));
        Files.createDirectory(root.resolve(ReplicationSettings.FILE));
        assertEquals(ReplicationSettings.INVALID,
                assertThrows(CoordinationException.class, () -> ReplicationSettings.read(root)).code());
    }

    @Test
    void settingsRoundTripAndReplaceAnEarlierFile(@TempDir Path root) throws Exception {
        ReplicationSettings.member("sl1-leader").write(root);
        assertEquals("sl1-leader", ReplicationSettings.read(root).orElseThrow().leaderNodeId());
        assertTrue(ReplicationSettings.read(root).orElseThrow().isMember());

        ReplicationSettings.leader(Set.of("sl1-m")).write(root);

        assertFalse(ReplicationSettings.read(root).orElseThrow().isMember());
        assertEquals(Set.of("sl1-m"), ReplicationSettings.read(root).orElseThrow().members());
        assertThrows(CoordinationException.class, () -> ReplicationSettings.member("two words").write(root));
        try (var files = Files.list(root)) {
            assertEquals(List.of(ReplicationSettings.FILE), files.map(path -> path.getFileName().toString()).toList());
        }
    }
}
