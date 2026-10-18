package org.synesis.workspace.application.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.persistence.CodeTransferCodec;
import org.synesis.coordination.persistence.CodeTransferMessage;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationPeers;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.identity.NodeIdentity;

/**
 * Which computers bring their checkout up to {@code main} before a lane (ADR-0098 step 6): one that joined a shared
 * project and is not its owner, never the owner and never a single computer. The code side is a recording stand-in for
 * the coordination server, so only the decision is under test.
 */
class CodeExchangeFollowLeaderIfMemberTest {

    private static final String COMMIT = "a".repeat(40);

    @TempDir
    Path temp;

    private final UUID project = UUID.randomUUID();
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private Path coordination;
    private Path control;
    private NodeIdentity self;
    private NodeIdentity inviter;
    private boolean unavailable;

    @BeforeEach
    void setUp() throws Exception {
        coordination = temp.resolve("self/coordination");
        control = temp.resolve("self/control");
        self = NodeIdentity.generate();
        inviter = NodeIdentity.generate();
        CodeExchange.register(coordination, request -> {
            CodeTransferMessage message = CodeTransferCodec.decode(request);
            asked.add(((CodeTransferMessage.Fetch) message).wanted());
            return CodeTransferCodec.encode(unavailable ? new CodeTransferMessage.Unavailable("NOT_YET") : new CodeTransferMessage.Fetched(COMMIT));
        });
    }

    @AfterEach
    void unregister() throws Exception {
        CodeExchange.register(coordination, null);
    }

    private boolean follow() throws Exception {
        return CodeExchange.followLeaderIfMember(coordination, project, control, self.nodeId());
    }

    /** The inviter's log, shared by it with this computer accepted, copied here the way a Link exchange would. */
    private void ownerSharedWithThisComputer() throws Exception {
        PredictionEventStore inviterStore = new PredictionEventStore(temp.resolve("inviter"), project);
        MembershipService owner = new MembershipService(inviterStore, inviter);
        owner.share(Optional.empty());
        owner.requestJoin(self.nodeId());
        owner.answer(self.nodeId(), true);
        PredictionEventStore mine = new PredictionEventStore(coordination, project);
        List<byte[]> entries = new PredictionEventStore(temp.resolve("inviter"), project).entriesAfter(PredictionEventStore.OWN_LOG, 0, 1000, Integer.MAX_VALUE);
        mine.placeCopies(inviter.nodeId(), entries);
    }

    @Test
    void aSingleComputerWithNoSettingsFileFollowsNothing() throws Exception {
        assertFalse(follow());

        assertEquals(List.of(), asked);
    }

    @Test
    void theOwnerOfASharedProjectDoesNotFollowItself() throws Exception {
        ReplicationSettings.sharedProject(null).write(coordination);
        new MembershipService(new PredictionEventStore(coordination, project), self).share(Optional.empty());

        assertFalse(follow());

        assertEquals(List.of(), asked);
    }

    @Test
    void aSharedFileWithNoOwnerYetAndNoDialedInviterFollowsNothing() throws Exception {
        ReplicationSettings.sharedProject(null).write(coordination);

        assertFalse(follow());

        assertEquals(List.of(), asked);
    }

    @Test
    void beforeTheOwnersEntriesArriveAComputerThatDialedAnInviterFollowsMain() throws Exception {
        ReplicationSettings.sharedProject(null).write(coordination);
        ReplicationPeers.rememberDialed(coordination, inviter.nodeId(), "synesis://invitation");

        assertTrue(follow());

        assertEquals(List.of("HEAD"), asked, "main is fetched over Link once");
    }

    @Test
    void aComputerTheOwnerAcceptedFollowsMainEvenWithTheDialLineGone() throws Exception {
        ReplicationSettings.sharedProject(null).write(coordination);
        ownerSharedWithThisComputer();

        assertTrue(follow());

        assertEquals(List.of("HEAD"), asked);
    }

    @Test
    void aStepFiveMemberFileStillFollowsItsLeader() throws Exception {
        ReplicationSettings.member(inviter.nodeId()).write(coordination);

        assertTrue(follow());

        assertEquals(List.of("HEAD"), asked);
    }

    @Test
    void aStepFiveLeaderFileDoesNotFollowWithoutAnInviter() throws Exception {
        ReplicationSettings.leader(Set.of(inviter.nodeId())).write(coordination);

        assertFalse(follow());

        assertEquals(List.of(), asked);
    }

    @Test
    void whenMainCannotBeHadTheLaneIsRefusedNotStartedOnAStaleCheckout() throws Exception {
        ReplicationSettings.sharedProject(null).write(coordination);
        ReplicationPeers.rememberDialed(coordination, inviter.nodeId(), "synesis://invitation");
        unavailable = true;

        CodeRemote.Refused refused = assertThrows(CodeRemote.Refused.class, this::follow);

        assertEquals(CodeRemote.UNAVAILABLE, refused.code());
    }

    @Test
    void anUnreadableSettingsFileIsRefusedWithItsCode() throws Exception {
        java.nio.file.Files.createDirectories(coordination);
        java.nio.file.Files.writeString(coordination.resolve(ReplicationSettings.FILE), "role=shared\nleader=x\n");

        CodeRemote.Refused refused = assertThrows(CodeRemote.Refused.class, this::follow);

        assertEquals(ReplicationSettings.INVALID, refused.code());
        assertEquals(List.of(), asked);
    }

    @Test
    void anUnreadableLogOrDialRecordIsNotTakenAsHavingJoined() throws Exception {
        ReplicationSettings.sharedProject(null).write(coordination);
        java.nio.file.Files.writeString(coordination.resolve(ReplicationPeers.FILE), "garbage\n");

        assertFalse(follow(), "ambiguous state does not fetch from anyone");

        assertEquals(List.of(), asked);
    }
}
