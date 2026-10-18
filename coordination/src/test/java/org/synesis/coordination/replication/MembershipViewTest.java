package org.synesis.coordination.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationPeers;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.identity.NodeIdentity;

/**
 * Who a computer exchanges with: before the project is shared the computers it dialed and a step-5 file names, after
 * it the members the owner's log names, and nothing when the log cannot be read (ADR-0098 step 6, items 10 and 15).
 */
final class MembershipViewTest {

    private static final String LINK = "synesis://invitation";

    private final UUID project = UUID.randomUUID();
    private Path root;
    private NodeIdentity self;
    private NodeIdentity other;
    private NodeIdentity third;
    private MembershipView view;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        root = directory;
        self = NodeIdentity.generate();
        other = NodeIdentity.generate();
        third = NodeIdentity.generate();
        view = new MembershipView(root, project, self.nodeId());
    }

    private MembershipService service() throws Exception {
        return new MembershipService(new PredictionEventStore(root, project), self);
    }

    /** The view reads the log at most every half second. */
    private static void refreshWindow() throws InterruptedException {
        Thread.sleep(700);
    }

    @Test
    void aProjectNotSharedWithNothingDialedAndNoFileTrustsNoOne() {
        assertEquals(Set.of(), view.others());
        assertFalse(view.admitted(other.nodeId()));
        assertTrue(view.copied(other.nodeId()), "before sharing every log a trusted computer serves is copied");
        assertEquals(Map.of(), view.cards());
        assertTrue(view.owner().isEmpty());
    }

    @Test
    void beforeSharingTheComputersItDialedAreTrusted() throws Exception {
        ReplicationPeers.rememberDialed(root, other.nodeId(), LINK);

        assertEquals(Set.of(other.nodeId()), view.others());
        assertTrue(view.admitted(other.nodeId()));
        assertFalse(view.admitted(third.nodeId()));
    }

    @Test
    void beforeSharingAStepFiveMembersFileTrustsItsLeader() throws Exception {
        ReplicationSettings.member(other.nodeId()).write(root);

        assertEquals(Set.of(other.nodeId()), view.others());
        assertTrue(view.admitted(other.nodeId()));
    }

    @Test
    void beforeSharingAStepFiveLeadersFileTrustsItsMembers() throws Exception {
        ReplicationSettings.leader(Set.of(other.nodeId(), third.nodeId())).write(root);

        assertEquals(Set.of(other.nodeId(), third.nodeId()), view.others());
        assertTrue(view.admitted(third.nodeId()));
    }

    @Test
    void thisComputerIsNeverAmongTheOthersOrAdmitted() throws Exception {
        ReplicationPeers.rememberDialed(root, self.nodeId(), LINK);
        ReplicationSettings.leader(Set.of(self.nodeId(), other.nodeId())).write(root);

        assertEquals(Set.of(other.nodeId()), view.others());
        assertFalse(view.admitted(self.nodeId()));

        service().share(Optional.empty());
        refreshWindow();
        assertEquals(Set.of(), view.others());
        assertFalse(view.admitted(self.nodeId()), "the owner is a member, and still not one it exchanges with");
    }

    @Test
    void afterSharingOnlyTheMembersCountAndTheDialLinesAndTheStepFiveFileAreIgnored() throws Exception {
        service().share(Optional.empty());
        ReplicationPeers.rememberDialed(root, third.nodeId(), LINK);
        ReplicationSettings.leader(Set.of(third.nodeId())).write(root);

        assertEquals(Set.of(), view.others());
        assertFalse(view.admitted(third.nodeId()), "a computer a file names is not a member");
        assertFalse(view.copied(third.nodeId()), "and its log is not copied once the project is shared");
        assertEquals(Optional.of(self.nodeId()), view.owner());

        service().requestJoin(other.nodeId());
        service().answer(other.nodeId(), true);
        refreshWindow();

        assertEquals(Set.of(other.nodeId()), view.others());
        assertTrue(view.admitted(other.nodeId()));
        assertTrue(view.copied(other.nodeId()));
    }

    @Test
    void aRemovedMemberIsNotAdmittedButItsLogIsStillCopied() throws Exception {
        service().share(Optional.empty());
        service().requestJoin(other.nodeId());
        service().answer(other.nodeId(), true);
        assertTrue(view.admitted(other.nodeId()));

        service().remove(other.nodeId());
        refreshWindow();

        assertFalse(view.admitted(other.nodeId()));
        assertEquals(Set.of(), view.others());
        assertTrue(view.copied(other.nodeId()), "every copy of its log stays complete");
        assertFalse(view.copied(third.nodeId()), "a computer that never belonged is not copied");
    }

    @Test
    void membersCardsAreThoseTheLogPublishes() throws Exception {
        service().share(Optional.empty());
        assertTrue(service().publishCard("synesis://owner-card"));
        refreshWindow();

        assertEquals(Map.of(self.nodeId(), "synesis://owner-card"), view.cards());
    }

    @Test
    void anUnreadableLogTrustsNoOne() throws Exception {
        ReplicationPeers.rememberDialed(root, other.nodeId(), LINK);
        assertTrue(view.admitted(other.nodeId()));
        service().share(Optional.empty());
        Path own;
        try (var logs = Files.walk(root.resolve("events"))) {
            own = logs.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Files.writeString(own, "corrupted");
        refreshWindow();

        assertFalse(view.admitted(other.nodeId()));
        assertEquals(Set.of(), view.others());
        assertFalse(view.copied(other.nodeId()));
        assertEquals(Map.of(), view.cards());
        assertTrue(view.owner().isEmpty());
    }
}
