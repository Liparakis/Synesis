package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.link.identity.NodeIdentity;

/**
 * One leader log and one member log on one computer, joined by an in-process channel (ADR-0098, step 2, slice 1).
 */
final class MemberReplicationTest {

    private final UUID project = UUID.randomUUID();
    private Path leaderRoot;
    private Path memberRoot;
    private NodeIdentity leader;
    private NodeIdentity member;
    private Channel channel;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        leaderRoot = temp.resolve("leader");
        memberRoot = temp.resolve("member");
        leader = NodeIdentity.generate();
        member = NodeIdentity.generate();
        channel = new Channel(new LeaderEndpoint(leaderRoot, project, Set.of(member.nodeId())), member.nodeId());
        ReplicationRole.register(memberRoot, ReplicationRole.memberOf(leader.nodeId(), channel));
    }

    @AfterEach
    void tearDown() throws Exception {
        ReplicationRole.register(memberRoot, ReplicationRole.leader());
    }

    private PredictionEventStore leaderStore() throws Exception {
        return new PredictionEventStore(leaderRoot, project);
    }

    private PredictionEventStore memberStore() throws Exception {
        return new PredictionEventStore(memberRoot, project);
    }

    private static WorkIntent lane(UUID project, UUID group, String participant, String path) {
        UUID intentId = UUID.randomUUID();
        return new WorkIntent(intentId,
                project,
                participant,
                "codex",
                UUID.randomUUID(),
                "Build a board",
                "CRUD and persistence",
                "base-commit",
                List.of(ResourceSelector.pathExact(path)),
                1,
                group,
                WorkIntent.defaultAuthorityLineage(intentId),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                List.of());
    }

    private WorkIntent announceOnLeader(String participant, String path) throws Exception {
        WorkIntent lane = lane(project, UUID.randomUUID(), participant, path);
        assertTrue(new WorkIntentService(leaderStore(), leader).announce(lane).acquired());
        return lane;
    }

    /** An entry signed by {@code signer} that follows the leader's log as it is now. */
    private byte[] proposalAtLeaderHead(PredictionEventType type, UUID prediction, byte[] payload, NodeIdentity signer)
            throws Exception {
        List<PredictionEvent> events = leaderStore().events();
        byte[] digest = events.isEmpty() ? new byte[32] : events.getLast().digest();
        return PredictionEvent.create(new ProjectId(project),
                new PredictionId(prediction),
                new EventSequence(events.size() + 1L),
                type,
                new NodeId(signer.nodeId()),
                payload,
                digest,
                signer,
                System.currentTimeMillis()).encoded();
    }

    private static void assertSameLog(PredictionEventStore left, PredictionEventStore right) {
        assertEquals(left.events().size(), right.events().size());
        for (int index = 0; index < left.events().size(); index++) {
            assertArrayEquals(left.events().get(index).encoded(), right.events().get(index).encoded());
        }
    }

    @Test
    void aMembersClaimLandsOnTheLeaderSignedByTheMember() throws Exception {
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        // The service is the one a leader uses; nothing in it knows it runs on a member.
        assertTrue(new WorkIntentService(memberStore(), member).announce(lane).acquired());

        PredictionEventStore onLeader = leaderStore();
        assertEquals(2, onLeader.events().size());
        assertTrue(onLeader.events().stream().allMatch(event -> event.actorNodeId().equals(member.nodeId())));
        assertEquals(PredictionEventType.WORK_INTENT_ANNOUNCED, onLeader.events().getLast().type());
        assertSameLog(onLeader, memberStore());
        for (long sequence = 1; sequence <= 2; sequence++) {
            String name = String.format("%020d.sce", sequence);
            assertArrayEquals(Files.readAllBytes(leaderRoot.resolve("events").resolve(name)),
                    Files.readAllBytes(memberRoot.resolve("events").resolve(name)));
        }
        assertTrue(onLeader.events().stream().allMatch(event -> {
            try {
                return event.verify();
            } catch (GeneralSecurityException failure) {
                return false;
            }
        }));
    }

    @Test
    void aMemberBehindTheLeaderPullsAndLandsAtTheRightSequence() throws Exception {
        announceOnLeader("agt-a", "src/a.py");
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        assertTrue(new WorkIntentService(memberStore(), member).announce(lane).acquired());

        PredictionEventStore onLeader = leaderStore();
        assertEquals(4, onLeader.events().size());
        assertEquals(member.nodeId(), onLeader.events().get(3).actorNodeId());
        assertEquals(4, onLeader.events().get(3).sequence());
        assertSameLog(onLeader, memberStore());
        // The first entry of the member met a moved head once, then landed; the second landed at once.
        assertEquals(3, channel.proposals);
    }

    @Test
    void aClaimOverlappingTheLeadersIsRefusedAndNothingIsPlacedOnTheMember() throws Exception {
        WorkIntent held = announceOnLeader("agt-a", "src/shared.py");
        WorkIntent overlapping = lane(project, held.group().value(), "agt-b", "src/shared.py");

        // The member is behind, so its own check passes; after pulling, its rules see the leader's claim.
        IOException refusal = assertThrows(IOException.class,
                () -> memberStore().append(overlapping.intentId(),
                        PredictionEventType.WORK_INTENT_ANNOUNCED,
                        member.nodeId(),
                        CollaborationCodec.encodeIntent(overlapping),
                        member));
        assertEquals("OVERLAPPING_CLAIM", refusal.getMessage());
        assertSameLog(leaderStore(), memberStore());
        assertEquals(2, leaderStore().events().size());

        // The leader decides the same when the entry reaches it at its own head.
        byte[] atHead = proposalAtLeaderHead(PredictionEventType.WORK_INTENT_ANNOUNCED,
                overlapping.intentId(),
                CollaborationCodec.encodeIntent(overlapping),
                member);
        assertEquals(new ProposalOutcome.Refused("OVERLAPPING_CLAIM"),
                new LeaderEndpoint(leaderRoot, project, Set.of(member.nodeId())).accept(atHead, member.nodeId()));
        assertEquals(2, leaderStore().events().size());
    }

    @Test
    void theLeaderRefusalReachesTheMemberWithItsCodeAndNothingIsPlaced() throws Exception {
        channel.endpoint = new LeaderEndpoint(leaderRoot, project, Set.of());
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        IOException refusal = assertThrows(IOException.class,
                () -> new WorkIntentService(memberStore(), member).announce(lane));

        assertEquals("PROPOSER_NOT_A_MEMBER", refusal.getMessage());
        assertEquals(0, PredictionEventStore.recordedEvents(memberRoot));
        assertEquals(0, PredictionEventStore.recordedEvents(leaderRoot));
    }

    @Test
    void theLeaderRefusesWhatIsNotAMembersOwnOrIsOnlyTheLeadersToWrite() throws Exception {
        LeaderEndpoint endpoint = new LeaderEndpoint(leaderRoot, project, Set.of(member.nodeId()));
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");
        byte[] payload = CollaborationCodec.encodeIntent(lane);
        NodeIdentity stranger = NodeIdentity.generate();

        byte[] fromStranger = proposalAtLeaderHead(PredictionEventType.WORK_INTENT_ANNOUNCED,
                lane.intentId(),
                payload,
                stranger);
        assertEquals(new ProposalOutcome.Refused("PROPOSER_NOT_A_MEMBER"),
                endpoint.accept(fromStranger, stranger.nodeId()));

        byte[] byMember = proposalAtLeaderHead(PredictionEventType.WORK_INTENT_ANNOUNCED,
                lane.intentId(),
                payload,
                member);
        assertEquals(new ProposalOutcome.Refused("PROPOSAL_SENDER_MISMATCH"),
                endpoint.accept(byMember, leader.nodeId()));

        byte[] integration = proposalAtLeaderHead(PredictionEventType.TASK_INTEGRATED,
                lane.intentId(),
                payload,
                member);
        assertEquals(new ProposalOutcome.Refused("EVENT_TYPE_LEADER_ONLY"),
                endpoint.accept(integration, member.nodeId()));

        assertEquals(new ProposalOutcome.Refused("PROPOSAL_MALFORMED"), endpoint.accept(new byte[] {1, 2, 3}, member.nodeId()));
        assertEquals(0, PredictionEventStore.recordedEvents(leaderRoot));
    }

    @Test
    void aMemberCannotActForAParticipantAnotherNodeIntroduced() throws Exception {
        WorkIntent held = announceOnLeader("agt-a", "src/a.py");
        LeaderEndpoint endpoint = new LeaderEndpoint(leaderRoot, project, Set.of(member.nodeId()));
        long before = leaderStore().events().size();

        byte[] release = proposalAtLeaderHead(PredictionEventType.WORK_INTENT_RELEASED,
                held.intentId(),
                CollaborationCodec.encodeRelease(held.intentId()),
                member);
        assertEquals(new ProposalOutcome.Refused("PARTICIPANT_NOT_OWNED_BY_SIGNER"),
                endpoint.accept(release, member.nodeId()));
        assertEquals(before, leaderStore().events().size());

        // The rule is in the log's admission, so it holds for every append, on any store.
        IOException local = assertThrows(IOException.class,
                () -> leaderStore().append(held.intentId(),
                        PredictionEventType.WORK_INTENT_RELEASED,
                        member.nodeId(),
                        CollaborationCodec.encodeRelease(held.intentId()),
                        member));
        assertEquals("PARTICIPANT_NOT_OWNED_BY_SIGNER", local.getMessage());

        // The node that introduced the participant can still release it.
        leaderStore().append(held.intentId(),
                PredictionEventType.WORK_INTENT_RELEASED,
                leader.nodeId(),
                CollaborationCodec.encodeRelease(held.intentId()),
                leader);
        assertEquals(before + 1, leaderStore().events().size());
    }

    @Test
    void sendingTheSameEntryAgainIsAnsweredLikeTheFirstTime() throws Exception {
        LeaderEndpoint endpoint = new LeaderEndpoint(leaderRoot, project, Set.of(member.nodeId()));
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");
        new WorkIntentService(memberStore(), member).announce(lane);
        long before = leaderStore().events().size();
        byte[] resent = leaderStore().events().getLast().encoded();

        assertEquals(new ProposalOutcome.Accepted(before), endpoint.accept(resent, member.nodeId()));
        assertEquals(new ProposalOutcome.Accepted(before), endpoint.accept(resent, member.nodeId()));
        assertEquals(before, leaderStore().events().size());
    }

    @Test
    void aLostAnswerIsSettledByResendingTheSameBytes() throws Exception {
        channel.lostAnswers = 1;
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        assertTrue(new WorkIntentService(memberStore(), member).announce(lane).acquired());

        assertEquals(2, leaderStore().events().size());
        assertSameLog(leaderStore(), memberStore());
        assertEquals(3, channel.proposals);
    }

    @Test
    void aLeaderThatCannotBeReachedPlacesNothingOnTheMember() throws Exception {
        channel.down = true;
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        IOException failure = assertThrows(IOException.class,
                () -> new WorkIntentService(memberStore(), member).announce(lane));

        assertEquals("LEADER_UNREACHABLE", failure.getMessage());
        assertEquals(0, PredictionEventStore.recordedEvents(memberRoot));
    }

    @Test
    void aLeaderThatNeverAnswersLeavesTheOutcomeUnknownAndPlacesNothingOnTheMember() throws Exception {
        channel.silent = true;
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        IOException failure = assertThrows(IOException.class,
                () -> new WorkIntentService(memberStore(), member).announce(lane));

        assertEquals("LEADER_OUTCOME_UNKNOWN", failure.getMessage());
        assertEquals(3, channel.proposals);
        assertEquals(0, PredictionEventStore.recordedEvents(memberRoot));
    }

    @Test
    void aGapOrAChainThatDoesNotFitStopsThePullLoudlyAndChangesNothing() throws Exception {
        announceOnLeader("agt-a", "src/a.py");
        announceOnLeader("agt-c", "src/c.py");
        List<byte[]> all = leaderStore().entriesAfter(0, 100, Integer.MAX_VALUE);
        assertEquals(4, all.size());
        PredictionEventStore onMember = memberStore();

        CoordinationException gap = assertThrows(CoordinationException.class,
                () -> onMember.applyCommitted(List.of(all.get(1), all.get(2))));
        assertEquals("LEADER_LOG_DIVERGED", gap.getMessage());
        assertEquals(0, PredictionEventStore.recordedEvents(memberRoot));

        // The first entry fits; the second is another log's, so it does not chain to the first.
        Path otherRoot = leaderRoot.resolveSibling("other");
        PredictionEventStore other = new PredictionEventStore(otherRoot, project);
        new WorkIntentService(other, leader).announce(lane(project, UUID.randomUUID(), "agt-x", "src/x.py"));
        List<byte[]> foreign = other.entriesAfter(0, 100, Integer.MAX_VALUE);
        CoordinationException mismatch = assertThrows(CoordinationException.class,
                () -> onMember.applyCommitted(List.of(all.get(0), foreign.get(1))));
        assertEquals("LEADER_LOG_DIVERGED", mismatch.getMessage());
        assertEquals(0, PredictionEventStore.recordedEvents(memberRoot));

        assertEquals(4, onMember.applyCommitted(all));
        assertSameLog(leaderStore(), onMember);
    }

    @Test
    void aPullComesInBoundedPages() throws Exception {
        announceOnLeader("agt-a", "src/a.py");
        announceOnLeader("agt-c", "src/c.py");
        PredictionEventStore onLeader = leaderStore();

        assertEquals(3, onLeader.entriesAfter(1, 3, Integer.MAX_VALUE).size());
        assertEquals(1, onLeader.entriesAfter(1, 100, 1).size());
        assertEquals(0, onLeader.entriesAfter(4, 100, Integer.MAX_VALUE).size());
        assertFalse(onLeader.entriesAfter(0, 100, Integer.MAX_VALUE).isEmpty());

        assertEquals(4, memberStore().pullFromLeader());
        assertSameLog(onLeader, memberStore());
    }

    /** Carries a member's calls to the leader's endpoint in the same process. */
    private static final class Channel implements LeaderChannel {

        LeaderEndpoint endpoint;
        final String sender;
        boolean down;
        boolean silent;
        int lostAnswers;
        int proposals;

        Channel(LeaderEndpoint endpoint, String sender) {
            this.endpoint = endpoint;
            this.sender = sender;
        }

        @Override
        public ProposalOutcome propose(byte[] encodedEvent) throws IOException {
            proposals++;
            if (down) {
                return new ProposalOutcome.Unreachable();
            }
            if (silent) {
                return new ProposalOutcome.NoAnswer();
            }
            try {
                ProposalOutcome outcome = endpoint.accept(encodedEvent, sender);
                if (lostAnswers > 0) {
                    lostAnswers--;
                    return new ProposalOutcome.NoAnswer();
                }
                return outcome;
            } catch (GeneralSecurityException failure) {
                throw new IOException(failure);
            }
        }

        @Override
        public List<byte[]> pull(long after, int maxEntries, int maxBytes) throws IOException {
            if (down) {
                throw new CoordinationException("LEADER_UNREACHABLE");
            }
            try {
                return endpoint.entriesAfter(after, maxEntries, maxBytes);
            } catch (GeneralSecurityException failure) {
                throw new IOException(failure);
            }
        }
    }
}
