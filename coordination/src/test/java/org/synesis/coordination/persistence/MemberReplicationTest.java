package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.link.identity.NodeIdentity;

/**
 * One leader computer and one member computer, each with its own folder and its own log, exchanging entries by hand
 * the way a Link session does (ADR-0098 step 4): a computer appends only to its own log, whether or not the other is
 * there, and holds the other's log as a byte-identical copy.
 */
final class MemberReplicationTest {

    private final UUID project = UUID.randomUUID();
    private Path leaderRoot;
    private Path memberRoot;
    private NodeIdentity leader;
    private NodeIdentity member;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        leaderRoot = temp.resolve("leader");
        memberRoot = temp.resolve("member");
        leader = NodeIdentity.generate();
        member = NodeIdentity.generate();
        ReplicationRole.register(memberRoot, ReplicationRole.memberOf(leader.nodeId()));
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
        return announceOnLeader(UUID.randomUUID(), participant, path);
    }

    private WorkIntent announceOnLeader(UUID group, String participant, String path) throws Exception {
        WorkIntent lane = lane(project, group, participant, path);
        assertTrue(new WorkIntentService(leaderStore(), leader).announce(lane).acquired());
        return lane;
    }

    private WorkIntent announceOnMember(UUID group, String participant, String path) throws Exception {
        WorkIntent lane = lane(project, group, participant, path);
        assertTrue(new WorkIntentService(memberStore(), member).announce(lane).acquired());
        return lane;
    }

    /** What a pull does: the entries of {@code log} that {@code to} lacks, placed as the copy of {@code writer}'s log. */
    private static int pull(PredictionEventStore from, String log, PredictionEventStore to, String writer)
            throws Exception {
        List<byte[]> entries = from.entriesAfter(log, to.logHeads().getOrDefault(writer, 0L), 1000, Integer.MAX_VALUE);
        return to.placeCopies(writer, entries);
    }

    private int leaderPullsMember() throws Exception {
        return pull(memberStore(), PredictionEventStore.OWN_LOG, leaderStore(), member.nodeId());
    }

    private int memberPullsLeader() throws Exception {
        return pull(leaderStore(), PredictionEventStore.OWN_LOG, memberStore(), leader.nodeId());
    }

    /** An entry signed by {@code signer} that follows {@code previous} in a log of its own. */
    private byte[] entry(NodeIdentity signer, long sequence, PredictionEvent previous, PredictionEventType type,
            UUID prediction, byte[] payload) throws Exception {
        return PredictionEvent.create(new ProjectId(project),
                new PredictionId(prediction),
                new EventSequence(sequence),
                type,
                new NodeId(signer.nodeId()),
                payload,
                previous == null ? new byte[32] : previous.digest(),
                signer,
                System.currentTimeMillis()).encoded();
    }

    private static List<String> encodedSet(PredictionEventStore store) {
        return store.events().stream().map(event -> java.util.HexFormat.of().formatHex(event.encoded())).sorted()
                .collect(Collectors.toList());
    }

    private static void assertSameFiles(Path left, Path right) throws IOException {
        try (var names = Files.list(left)) {
            List<String> sce = names.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".sce"))
                    .sorted().toList();
            assertFalse(sce.isEmpty());
            for (String name : sce) {
                assertArrayEquals(Files.readAllBytes(left.resolve(name)), Files.readAllBytes(right.resolve(name)), name);
            }
            try (var other = Files.list(right)) {
                assertEquals(sce, other.map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".sce")).sorted().toList());
            }
        }
    }

    @Test
    void aMembersClaimIsAppendedToItsOwnLogAndTheLeadersCopyHoldsTheSameBytes() throws Exception {
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        // The service is the one a leader uses; nothing in it knows it runs on a member, or that nobody else is there.
        assertTrue(new WorkIntentService(memberStore(), member).announce(lane).acquired());
        assertEquals(0, PredictionEventStore.recordedEvents(leaderRoot));
        assertEquals(2, memberStore().events().size());

        assertEquals(2, leaderPullsMember());

        PredictionEventStore onLeader = leaderStore();
        assertEquals(2, onLeader.events().size());
        assertTrue(onLeader.events().stream().allMatch(event -> event.actorNodeId().equals(member.nodeId())));
        assertEquals(PredictionEventType.WORK_INTENT_ANNOUNCED, onLeader.events().getLast().type());
        assertEquals(encodedSet(memberStore()), encodedSet(onLeader));
        assertSameFiles(memberRoot.resolve("events"), leaderRoot.resolve("peers").resolve(member.nodeId()).resolve("events"));
        assertEquals(0L, onLeader.logHeads().get(PredictionEventStore.OWN_LOG), "the leader's own log holds nothing of the member's");
        assertTrue(onLeader.events().stream().allMatch(event -> {
            try {
                return event.verify();
            } catch (java.security.GeneralSecurityException failure) {
                return false;
            }
        }));
    }

    @Test
    void aMemberBehindTheLeaderKeepsItsOwnSequencesAndBothEndWithTheSameEntries() throws Exception {
        announceOnLeader("agt-a", "src/a.py");
        announceOnMember(UUID.randomUUID(), "agt-b", "src/b.py");

        // The member never saw the leader's log: its entries are 1 and 2 of its own log, not 3 and 4 of the leader's.
        assertEquals(List.of(1L, 2L), memberStore().entriesAfter(0, 10, Integer.MAX_VALUE).stream()
                .map(encoded -> decode(encoded).sequence()).toList());
        assertEquals(2, memberStore().events().size());

        assertEquals(2, leaderPullsMember());
        assertEquals(2, memberPullsLeader());

        assertEquals(4, leaderStore().events().size());
        assertEquals(4, memberStore().events().size());
        assertEquals(encodedSet(leaderStore()), encodedSet(memberStore()));
        assertEquals(Map.of(PredictionEventStore.OWN_LOG, 2L, member.nodeId(), 2L), leaderStore().logHeads());
        assertEquals(Map.of(PredictionEventStore.OWN_LOG, 2L, leader.nodeId(), 2L), memberStore().logHeads());
        assertTrue(leaderStore().problems().isEmpty());
        assertTrue(memberStore().problems().isEmpty());
    }

    private static PredictionEvent decode(byte[] encoded) {
        try {
            return PredictionEvent.decode(encoded);
        } catch (IOException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    @Test
    void aMemberThatHasTheLeadersClaimRefusesAnOverlappingOneAndWritesNothing() throws Exception {
        WorkIntent held = announceOnLeader("agt-a", "src/shared.py");
        WorkIntent overlapping = lane(project, held.group().value(), "agt-b", "src/shared.py");
        assertEquals(2, memberPullsLeader());

        IOException refusal = assertThrows(IOException.class,
                () -> memberStore().append(overlapping.intentId(),
                        PredictionEventType.WORK_INTENT_ANNOUNCED,
                        member.nodeId(),
                        CollaborationCodec.encodeIntent(overlapping),
                        member));

        assertEquals("OVERLAPPING_CLAIM", refusal.getMessage());
        assertEquals(0L, memberStore().logHeads().get(PredictionEventStore.OWN_LOG));
        assertEquals(2, memberStore().events().size());
        assertEquals(2, PredictionEventStore.recordedEvents(memberRoot));
    }

    @Test
    void twoComputersThatClaimedTheSameThingWhileApartBothKeepTheirEntries() throws Exception {
        WorkIntent held = announceOnLeader("agt-a", "src/shared.py");
        // The member is behind, so its own check passes. Its entry is a fact in its log, and so is the leader's.
        announceOnMember(held.group().value(), "agt-b", "src/shared.py");

        leaderPullsMember();
        memberPullsLeader();

        assertEquals(encodedSet(leaderStore()), encodedSet(memberStore()));
        assertTrue(leaderStore().problems().isEmpty(), leaderStore().problems().toString());
        assertTrue(memberStore().problems().isEmpty(), memberStore().problems().toString());
        assertEquals(leaderStore().events().size(), memberStore().events().size());
    }

    static byte[] snapshotOf(WorkIntent lane, String snapshotId) {
        String path = lane.selectors().getFirst().value();
        return new TaskSnapshotPayload(lane.taskId(),
                snapshotId,
                "node",
                "supervisor",
                "worker",
                "session-" + lane.participant(),
                "base",
                "commit-" + snapshotId,
                List.of(path),
                List.of(),
                "work",
                new SnapshotProvenance(lane.workGroupId(),
                        lane.intentId(),
                        lane.authorityLineageId(),
                        lane.participant(),
                        "session-" + lane.participant(),
                        1,
                        List.of(),
                        List.of(),
                        List.of("PATH:" + path),
                        "refs/synesis/snapshots/" + snapshotId,
                        "integrity",
                        "artifacts"),
                false).encode();
    }

    @Test
    void anIntegrationEntryNamingAnotherComputersSnapshotIsRefusedOnTheWriterAndCountsForNothingOnArrival()
            throws Exception {
        WorkIntent held = announceOnLeader("agt-a", "src/a.py");
        byte[] snapshot = snapshotOf(held, "snap_a");
        leaderStore().append(held.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, leader.nodeId(), snapshot, leader);
        assertEquals(3, memberPullsLeader());
        long before = memberStore().events().size();

        IOException refusal = assertThrows(IOException.class, () -> memberStore().append(held.taskId(),
                PredictionEventType.TASK_INTEGRATED, member.nodeId(), snapshot, member));

        assertEquals("PARTICIPANT_NOT_OWNED_BY_SIGNER", refusal.getMessage());
        assertEquals(0L, memberStore().logHeads().get(PredictionEventStore.OWN_LOG), "nothing was written to the member's own log");
        assertEquals(before, memberStore().events().size());

        // The same entry in a stranger's log arrives as a fact that counts for nothing.
        NodeIdentity stranger = NodeIdentity.generate();
        byte[] forged = entry(stranger, 1, null, PredictionEventType.TASK_INTEGRATED, held.taskId(), snapshot);
        assertEquals(1, memberStore().placeCopies(stranger.nodeId(), List.of(forged)));
        assertEquals(List.of(stranger.nodeId() + " 1 PARTICIPANT_NOT_OWNED_BY_SIGNER"), memberStore().problems());
        assertEquals(before, memberStore().events().size());

        // The computer that introduced the lane integrates it.
        leaderStore().append(held.taskId(), PredictionEventType.TASK_INTEGRATED, leader.nodeId(), snapshot, leader);
        assertEquals(1, memberPullsLeader());
        assertEquals(before + 1, memberStore().events().size());
    }

    @Test
    void aMemberCannotActForAParticipantAnotherNodeIntroduced() throws Exception {
        WorkIntent held = announceOnLeader("agt-a", "src/a.py");
        assertEquals(2, memberPullsLeader());
        long before = memberStore().events().size();

        // The member's own store refuses it before anything is written.
        IOException local = assertThrows(IOException.class,
                () -> memberStore().append(held.intentId(),
                        PredictionEventType.WORK_INTENT_RELEASED,
                        member.nodeId(),
                        CollaborationCodec.encodeRelease(held.intentId()),
                        member));
        assertEquals("PARTICIPANT_NOT_OWNED_BY_SIGNER", local.getMessage());
        assertEquals(before, memberStore().events().size());

        // An entry of a stranger's log that does it anyway arrives as a fact that counts for nothing.
        NodeIdentity stranger = NodeIdentity.generate();
        byte[] forged = entry(stranger, 1, null, PredictionEventType.WORK_INTENT_RELEASED, held.intentId(),
                CollaborationCodec.encodeRelease(held.intentId()));
        assertEquals(1, memberStore().placeCopies(stranger.nodeId(), List.of(forged)));
        assertEquals(List.of(stranger.nodeId() + " 1 PARTICIPANT_NOT_OWNED_BY_SIGNER"), memberStore().problems());
        assertEquals(before, memberStore().events().size());
        assertEquals(1, memberStore().collaborationProjection().activeIntents().size());

        // The node that introduced the participant can still release it, and the member applies that.
        leaderStore().append(held.intentId(),
                PredictionEventType.WORK_INTENT_RELEASED,
                leader.nodeId(),
                CollaborationCodec.encodeRelease(held.intentId()),
                leader);
        assertEquals(1, memberPullsLeader());
        assertEquals(before + 1, memberStore().events().size());
        assertTrue(memberStore().collaborationProjection().activeIntents().isEmpty());
    }

    @Test
    void theOwnersComputerMayNotReleaseAMembersLaneButTheMemberMay() throws Exception {
        WorkIntent lane = announceOnMember(UUID.randomUUID(), "agt-b", "src/b.py");
        assertEquals(2, leaderPullsMember());
        long before = leaderStore().events().size();

        // No computer acts for another computer's participant, the owner's computer included (ADR-0098 step 5).
        IOException refusal = assertThrows(IOException.class, () -> leaderStore().append(lane.intentId(),
                PredictionEventType.WORK_INTENT_RELEASED,
                leader.nodeId(),
                CollaborationCodec.encodeRelease(lane.intentId()),
                leader));
        assertEquals("PARTICIPANT_NOT_OWNED_BY_SIGNER", refusal.getMessage());
        assertEquals(before, leaderStore().events().size());
        assertEquals(1, leaderStore().collaborationProjection().activeIntents().size());

        memberStore().append(lane.intentId(),
                PredictionEventType.WORK_INTENT_RELEASED,
                member.nodeId(),
                CollaborationCodec.encodeRelease(lane.intentId()),
                member);
        assertEquals(1, leaderPullsMember());
        assertEquals(before + 1, leaderStore().events().size());
        assertTrue(leaderStore().collaborationProjection().activeIntents().isEmpty());
    }

    @Test
    void placingTheSameEntriesAgainChangesNothing() throws Exception {
        announceOnMember(UUID.randomUUID(), "agt-b", "src/b.py");
        List<byte[]> entries = memberStore().entriesAfter(0, 100, Integer.MAX_VALUE);
        PredictionEventStore onLeader = leaderStore();
        assertEquals(2, onLeader.placeCopies(member.nodeId(), entries));
        long revision = onLeader.headSequence();
        long recorded = PredictionEventStore.recordedEvents(leaderRoot);

        assertEquals(0, onLeader.placeCopies(member.nodeId(), entries));
        assertEquals(0, onLeader.placeCopies(member.nodeId(), entries.subList(0, 1)));

        assertEquals(revision, onLeader.headSequence());
        assertEquals(recorded, PredictionEventStore.recordedEvents(leaderRoot));
        assertEquals(revision, leaderStore().headSequence());
    }

    @Test
    void aGapOrAChainOrASignerThatDoesNotFitStopsThePlacingLoudlyAndChangesNothing() throws Exception {
        announceOnLeader("agt-a", "src/a.py");
        announceOnLeader("agt-c", "src/c.py");
        List<byte[]> all = leaderStore().entriesAfter(0, 100, Integer.MAX_VALUE);
        assertEquals(4, all.size());
        PredictionEventStore onMember = memberStore();
        String writer = leader.nodeId();

        CoordinationException gap = assertThrows(CoordinationException.class,
                () -> onMember.placeCopies(writer, List.of(all.get(1), all.get(2))));
        assertEquals("LOG_COPY_DIVERGED", gap.getMessage());
        assertEquals(0, PredictionEventStore.recordedEvents(memberRoot));

        // The first entry fits; the second is another log's, so it does not chain to the first.
        Path otherRoot = leaderRoot.resolveSibling("other");
        PredictionEventStore other = new PredictionEventStore(otherRoot, project);
        new WorkIntentService(other, leader).announce(lane(project, UUID.randomUUID(), "agt-x", "src/x.py"));
        List<byte[]> foreign = other.entriesAfter(0, 100, Integer.MAX_VALUE);
        CoordinationException mismatch = assertThrows(CoordinationException.class,
                () -> onMember.placeCopies(writer, List.of(all.get(0), foreign.get(1))));
        assertEquals("LOG_COPY_DIVERGED", mismatch.getMessage());
        assertEquals(0, PredictionEventStore.recordedEvents(memberRoot));

        // A log signed by one node is not the copy of another node's log.
        CoordinationException otherSigner = assertThrows(CoordinationException.class,
                () -> onMember.placeCopies(member.nodeId(), all));
        assertEquals("LOG_COPY_DIVERGED", otherSigner.getMessage());
        assertEquals(0, PredictionEventStore.recordedEvents(memberRoot));

        assertEquals(4, onMember.placeCopies(writer, all));
        assertEquals(encodedSet(leaderStore()), encodedSet(onMember));

        // Once held, another entry at a held sequence is a different log, and the copy is not repaired.
        CoordinationException differing = assertThrows(CoordinationException.class,
                () -> onMember.placeCopies(writer, List.of(foreign.get(0))));
        assertEquals("LOG_COPY_DIVERGED", differing.getMessage());
        assertEquals(4, onMember.events().size());
    }

    @Test
    void aPullComesInBoundedPagesAndAnyComputerServesTheCopiesItHolds() throws Exception {
        announceOnLeader("agt-a", "src/a.py");
        announceOnLeader("agt-c", "src/c.py");
        PredictionEventStore onLeader = leaderStore();

        assertEquals(3, onLeader.entriesAfter(1, 3, Integer.MAX_VALUE).size());
        assertEquals(1, onLeader.entriesAfter(1, 100, 1).size());
        assertEquals(0, onLeader.entriesAfter(4, 100, Integer.MAX_VALUE).size());
        assertFalse(onLeader.entriesAfter(0, 100, Integer.MAX_VALUE).isEmpty());

        assertEquals(4, memberPullsLeader());
        assertEquals(encodedSet(onLeader), encodedSet(memberStore()));

        // The member serves the leader's log, byte for byte, to a third computer.
        List<byte[]> served = memberStore().entriesAfter(leader.nodeId(), 0, 100, Integer.MAX_VALUE);
        List<byte[]> original = onLeader.entriesAfter(0, 100, Integer.MAX_VALUE);
        assertEquals(original.size(), served.size());
        for (int index = 0; index < served.size(); index++) {
            assertArrayEquals(original.get(index), served.get(index));
        }
        assertTrue(memberStore().entriesAfter("sl1-nobody", 0, 100, Integer.MAX_VALUE).isEmpty());
        List<String> logs = new ArrayList<>(memberStore().logHeads().keySet());
        assertEquals(List.of(leader.nodeId(), PredictionEventStore.OWN_LOG).stream().sorted().toList(), logs);
    }
}
