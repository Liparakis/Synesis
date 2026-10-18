package org.synesis.coordination.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * The owner's computer writing membership entries through its own log (ADR-0098 step 6).
 */
final class MembershipServiceTest {

    private final UUID project = UUID.randomUUID();
    private Path root;
    private NodeIdentity owner;
    private NodeIdentity joiner;
    private MembershipService service;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        root = directory;
        owner = NodeIdentity.generate();
        joiner = NodeIdentity.generate();
        service = new MembershipService(new PredictionEventStore(root, project), owner);
    }

    private MembershipProjection membership() throws Exception {
        return new PredictionEventStore(root, project).membershipProjection();
    }

    @Test
    void sharingIsOnceOnly() throws Exception {
        assertTrue(service.share(Optional.empty()));
        assertFalse(service.share(Optional.empty()), "an already shared project writes nothing");

        assertEquals(Set.of(owner.nodeId()), membership().members());
        assertEquals(owner.nodeId(), membership().owner().orElseThrow());
    }

    @Test
    void aJoinRequestIsWrittenOncePerWaitingJoiner() throws Exception {
        service.share(Optional.empty());

        UUID first = service.requestJoin(joiner.nodeId()).orElseThrow();
        UUID again = service.requestJoin(joiner.nodeId()).orElseThrow();

        assertEquals(first, again, "the waiting request is returned, not a second one");
        assertEquals(1, membership().requests().size());
        assertEquals(1, membership().openRequests().size());
        assertEquals(joiner.nodeId(), membership().openRequests().getFirst().joinerNodeId());
    }

    @Test
    void aJoinRequestAfterADeclineIsAFreshOne() throws Exception {
        service.share(Optional.empty());
        UUID first = service.requestJoin(joiner.nodeId()).orElseThrow();
        service.answer(joiner.nodeId(), false);

        UUID second = service.requestJoin(joiner.nodeId()).orElseThrow();

        assertFalse(first.equals(second));
        assertEquals(1, membership().openRequests().size());
        assertFalse(service.isMember(joiner.nodeId()));
    }

    @Test
    void anAnswerWithoutAWaitingRequestIsRefusedWithAStableCodeAndWritesNothing() throws Exception {
        service.share(Optional.empty());
        long head = new PredictionEventStore(root, project).logHeads().get(PredictionEventStore.OWN_LOG);

        IOException unknown = assertThrows(IOException.class, () -> service.answer(joiner.nodeId(), true));

        assertEquals("JOIN_REQUEST_UNKNOWN", unknown.getMessage());
        assertEquals(head, new PredictionEventStore(root, project).logHeads().get(PredictionEventStore.OWN_LOG));

        service.requestJoin(joiner.nodeId());
        service.answer(joiner.nodeId(), true);
        long accepted = new PredictionEventStore(root, project).logHeads().get(PredictionEventStore.OWN_LOG);
        // Accepting a member again writes nothing, so an acceptance repeated after a lost response succeeds.
        service.answer(joiner.nodeId(), true);
        assertEquals(accepted, new PredictionEventStore(root, project).logHeads().get(PredictionEventStore.OWN_LOG));
        assertEquals("JOIN_REQUEST_UNKNOWN", assertThrows(IOException.class, () -> service.answer(joiner.nodeId(), false)).getMessage(),
                "a request answered is no longer waiting, and a member is not declined");
    }

    @Test
    void anAcceptedJoinerIsAMemberAndARemovedOneIsNot() throws Exception {
        service.share(Optional.empty());
        service.requestJoin(joiner.nodeId());
        service.answer(joiner.nodeId(), true);
        assertTrue(service.isMember(joiner.nodeId()));
        assertTrue(service.isMember(owner.nodeId()));

        service.remove(joiner.nodeId());

        assertFalse(service.isMember(joiner.nodeId()));
        assertTrue(membership().logCopied(joiner.nodeId()));
    }

    @Test
    void aProjectNotSharedHasNoMembers() throws Exception {
        assertFalse(service.isMember(owner.nodeId()));
        assertFalse(service.isMember(joiner.nodeId()));
    }

    @Test
    void aComputerThatIsNotAMemberWritesNoRequest() throws Exception {
        service.share(Optional.empty());
        MembershipService stranger = new MembershipService(new PredictionEventStore(root, project), joiner);

        assertThrows(IOException.class, () -> stranger.requestJoin(NodeIdentity.generate().nodeId()));

        assertEquals(0, membership().requests().size());
    }

    @Test
    void migratingAnOwnerAcceptsEachMemberOnceAndSkipsItself() throws Exception {
        Set<String> members = Set.of(joiner.nodeId(), owner.nodeId());

        service.migrateOwner(members);
        service.migrateOwner(members);

        assertEquals(Set.of(owner.nodeId(), joiner.nodeId()), membership().members());
        assertEquals(1, membership().requests().size());
        assertEquals(1, membership().revision());
    }

    @Test
    void aJoinRequestForAMemberAlreadyWritesNothingAndIsNotAnError() throws Exception {
        service.share(Optional.empty());
        service.requestJoin(joiner.nodeId());
        service.answer(joiner.nodeId(), true);
        long head = new PredictionEventStore(root, project).logHeads().get(PredictionEventStore.OWN_LOG);

        service.requestJoin(joiner.nodeId());

        assertEquals(head, new PredictionEventStore(root, project).logHeads().get(PredictionEventStore.OWN_LOG));
    }
}
