package org.synesis.coordination.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationPeers;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.identity.NodeIdentity;

/**
 * A step-5 settings file becomes entries and a {@code role=shared} file once, when the server starts (ADR-0098 step 6,
 * designer item 14).
 */
final class SharedSettingsMigrationTest {

    private final UUID project = UUID.randomUUID();
    private Path root;
    private NodeIdentity self;
    private PredictionEventStore store;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        root = directory;
        self = NodeIdentity.generate();
        store = new PredictionEventStore(root, project);
    }

    private MembershipProjection membership() throws Exception {
        return new PredictionEventStore(root, project).membershipProjection();
    }

    @Test
    void aLeaderFileWithTwoMembersSharesTheProjectAndAcceptsBothAndIsRewrittenKeepingTheCodeRemote() throws Exception {
        String first = NodeIdentity.generate().nodeId();
        String second = NodeIdentity.generate().nodeId();
        ReplicationSettings.leader(Set.of(first, second)).withCodeRemote("origin").write(root);

        assertTrue(SharedSettingsMigration.migrate(root, store, self));

        MembershipProjection membership = membership();
        assertTrue(membership.shared());
        assertEquals(self.nodeId(), membership.owner().orElseThrow());
        assertEquals(Set.of(self.nodeId(), first, second), membership.members());
        assertEquals(2, membership.revision(), "one owner statement per accepted member");
        assertEquals(0, membership.recoveryPublicKey().length, "no recovery key is invented");
        assertEquals(0, membership.openRequests().size());
        assertEquals(ReplicationSettings.sharedProject("origin"), ReplicationSettings.read(root).orElseThrow());
        assertEquals("role=shared\ncode.remote=origin\n", Files.readString(root.resolve(ReplicationSettings.FILE)));
    }

    @Test
    void aLeaderFileWithNoMembersAndNoRemoteSharesTheProjectOnly() throws Exception {
        ReplicationSettings.leader(Set.of()).write(root);

        assertTrue(SharedSettingsMigration.migrate(root, store, self));

        assertEquals(Set.of(self.nodeId()), membership().members());
        assertEquals("role=shared\n", Files.readString(root.resolve(ReplicationSettings.FILE)));
    }

    @Test
    void aSecondRunChangesNothing() throws Exception {
        String member = NodeIdentity.generate().nodeId();
        ReplicationSettings.leader(Set.of(member)).write(root);
        assertTrue(SharedSettingsMigration.migrate(root, store, self));
        long head = new PredictionEventStore(root, project).logHeads().get(PredictionEventStore.OWN_LOG);
        String file = Files.readString(root.resolve(ReplicationSettings.FILE));

        assertFalse(SharedSettingsMigration.migrate(root, store, self));

        assertEquals(head, new PredictionEventStore(root, project).logHeads().get(PredictionEventStore.OWN_LOG), "no entry is written twice");
        assertEquals(file, Files.readString(root.resolve(ReplicationSettings.FILE)));
    }

    @Test
    void aMigrationThatDiedAfterTheEntriesButBeforeTheFileRunsAgainWithoutWritingThemTwice() throws Exception {
        String first = NodeIdentity.generate().nodeId();
        String second = NodeIdentity.generate().nodeId();
        ReplicationSettings.leader(Set.of(first, second)).write(root);
        new MembershipService(store, self).migrateOwner(Set.of(first));
        assertEquals(Set.of(self.nodeId(), first), membership().members());

        assertTrue(SharedSettingsMigration.migrate(root, store, self));

        assertEquals(Set.of(self.nodeId(), first, second), membership().members());
        assertEquals(2, membership().revision());
        assertEquals(ReplicationSettings.sharedProject(null), ReplicationSettings.read(root).orElseThrow());
    }

    @Test
    void aMemberFileWhoseLeaderItDialedBecomesSharedAndKeepsTheDialLine() throws Exception {
        String leader = NodeIdentity.generate().nodeId();
        ReplicationPeers.rememberDialed(root, leader, "synesis://invitation");
        ReplicationSettings.member(leader).withCodeRemote("origin").write(root);

        assertTrue(SharedSettingsMigration.migrate(root, store, self));

        assertEquals(ReplicationSettings.sharedProject("origin"), ReplicationSettings.read(root).orElseThrow());
        assertFalse(membership().shared(), "a member writes no entries: it waits for the owner's");
        assertEquals(Set.of(leader), ReplicationPeers.read(root).dialed().keySet());
        assertEquals(List.of(), List.copyOf(membership().members()));
    }

    @Test
    void aMemberFileWhoseLeaderItNeverDialedIsLeftAsItWas() throws Exception {
        String leader = NodeIdentity.generate().nodeId();
        String other = NodeIdentity.generate().nodeId();
        ReplicationPeers.rememberDialed(root, other, "synesis://invitation");
        ReplicationSettings.member(leader).write(root);
        String before = Files.readString(root.resolve(ReplicationSettings.FILE));

        assertFalse(SharedSettingsMigration.migrate(root, store, self));

        assertEquals(before, Files.readString(root.resolve(ReplicationSettings.FILE)));
        assertEquals(ReplicationSettings.member(leader), ReplicationSettings.read(root).orElseThrow());
        assertFalse(membership().shared());
    }

    @Test
    void aSharedFileOrNoFileIsLeftAlone() throws Exception {
        assertFalse(SharedSettingsMigration.migrate(root, store, self));
        assertTrue(ReplicationSettings.read(root).isEmpty());
        assertFalse(membership().shared());

        ReplicationSettings.sharedProject("origin").write(root);
        assertFalse(SharedSettingsMigration.migrate(root, store, self));
        assertFalse(membership().shared(), "a shared file is not shared again by the migration");
    }
}
