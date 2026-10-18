package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import org.synesis.coordination.domain.membership.MembershipEntries.JoinAnswered;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinPolicy;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinPolicySet;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinRequested;
import org.synesis.coordination.domain.membership.MembershipEntries.MemberCard;
import org.synesis.coordination.domain.membership.MembershipEntries.MemberRemoved;
import org.synesis.coordination.domain.membership.MembershipEntries.OwnerChanged;
import org.synesis.coordination.domain.membership.MembershipEntries.ProjectShared;
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.domain.membership.RecoveryKey;
import org.synesis.coordination.domain.identity.EventSequence;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.PredictionId;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.NodeIdentity;

/**
 * The membership rules through real stores: three computers, each with its own folder and log, exchanging entries by
 * hand the way a Link session does (ADR-0098 step 6).
 */
final class MembershipStoreTest {

    private final UUID project = UUID.randomUUID();
    private Path temp;
    private NodeIdentity a;
    private NodeIdentity b;
    private NodeIdentity c;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        temp = directory;
        a = NodeIdentity.generate();
        b = NodeIdentity.generate();
        c = NodeIdentity.generate();
    }

    private PredictionEventStore store(String name) throws Exception {
        return new PredictionEventStore(temp.resolve(name), project);
    }

    private static void put(PredictionEventStore store, NodeIdentity who, PredictionEventType type, byte[] payload) throws Exception {
        store.append(UUID.randomUUID(), type, who.nodeId(), payload, who);
    }

    private static String refusal(PredictionEventStore store, NodeIdentity who, PredictionEventType type, byte[] payload)
            throws Exception {
        long before = store.logHeads().get(PredictionEventStore.OWN_LOG);
        IOException failure = assertThrows(IOException.class, () -> put(store, who, type, payload));
        assertEquals(before, store.logHeads().get(PredictionEventStore.OWN_LOG), "a refused entry writes nothing");
        return failure.getMessage();
    }

    /** What a pull does: the entries of {@code writer}'s log that {@code to} lacks. */
    private static int pull(PredictionEventStore from, String fromLog, PredictionEventStore to, String writer) throws Exception {
        List<byte[]> entries = from.entriesAfter(fromLog, to.logHeads().getOrDefault(writer, 0L), 1000, Integer.MAX_VALUE);
        return to.placeCopies(writer, entries);
    }

    private static void sync(PredictionEventStore left, NodeIdentity leftId, PredictionEventStore right, NodeIdentity rightId)
            throws Exception {
        pull(left, PredictionEventStore.OWN_LOG, right, leftId.nodeId());
        pull(right, PredictionEventStore.OWN_LOG, left, rightId.nodeId());
    }

    private byte[] shared(NodeIdentity owner, byte[] recovery) {
        return new ProjectShared(owner.publicKeyEncoded(), recovery).encode();
    }

    private static byte[] request(UUID id, NodeIdentity joiner) {
        return new JoinRequested(id, joiner.nodeId()).encode();
    }

    private static byte[] answer(UUID id, NodeIdentity joiner, boolean accepted, long revision) {
        return new JoinAnswered(id, joiner.nodeId(), accepted, revision).encode();
    }

    private static byte[] ownerChange(NodeIdentity newOwner, byte[] recovery, long revision, byte[] signature) {
        return new OwnerChanged(newOwner.publicKeyEncoded(), recovery, revision, signature).encode();
    }

    /** A shares the project and admits B; both stores are in sync. */
    private PredictionEventStore[] sharedWithB(byte[] recovery) throws Exception {
        PredictionEventStore storeA = store("a");
        PredictionEventStore storeB = store("b");
        put(storeA, a, PredictionEventType.PROJECT_SHARED, shared(a, recovery));
        UUID id = UUID.randomUUID();
        put(storeA, a, PredictionEventType.JOIN_REQUESTED, request(id, b));
        put(storeA, a, PredictionEventType.JOIN_ANSWERED, answer(id, b, true, 0));
        sync(storeA, a, storeB, b);
        return new PredictionEventStore[] {storeA, storeB};
    }

    @Test
    void aProjectThatIsNotSharedRefusesNothing() throws Exception {
        PredictionEventStore storeA = store("a");
        MembershipProjection membership = storeA.membershipProjection();
        assertFalse(membership.shared());
        assertTrue(membership.isMember(UUID.randomUUID().toString()));
        assertTrue(membership.logCopied(c.nodeId()));
        assertTrue(membership.owner().isEmpty());
    }

    @Test
    void sharingNamesTheSignerAsOwnerOnceOnly() throws Exception {
        PredictionEventStore storeA = store("a");
        assertEquals("OWNER_KEY_NOT_THE_SIGNERS", refusal(storeA, a, PredictionEventType.PROJECT_SHARED, shared(b, new byte[0])));
        assertFalse(storeA.membershipProjection().shared());

        put(storeA, a, PredictionEventType.PROJECT_SHARED, shared(a, new byte[0]));
        MembershipProjection membership = storeA.membershipProjection();
        assertEquals(a.nodeId(), membership.owner().orElseThrow());
        assertEquals(Set.of(a.nodeId()), membership.members());
        assertEquals(0, membership.revision());
        assertFalse(membership.isMember(b.nodeId()));
        assertFalse(membership.logCopied(b.nodeId()));
        assertTrue(membership.logCopied(a.nodeId()));

        assertEquals("PROJECT_ALREADY_SHARED", refusal(storeA, a, PredictionEventType.PROJECT_SHARED, shared(a, new byte[0])));
    }

    @Test
    void joiningRunsThroughRequestAndTheOwnersAnswerAtTheRightRevision() throws Exception {
        PredictionEventStore storeA = store("a");
        PredictionEventStore storeB = store("b");
        put(storeA, a, PredictionEventType.PROJECT_SHARED, shared(a, new byte[0]));
        sync(storeA, a, storeB, b);

        // B is not a member yet, so it cannot ask on anyone's behalf.
        UUID id = UUID.randomUUID();
        assertEquals("NOT_A_MEMBER", refusal(storeB, b, PredictionEventType.JOIN_REQUESTED, request(id, c)));
        assertEquals("JOINER_NOT_A_NODE_ID", refusal(storeA, a, PredictionEventType.JOIN_REQUESTED,
                new JoinRequested(id, "not-a-node").encode()));
        assertEquals("ALREADY_A_MEMBER", refusal(storeA, a, PredictionEventType.JOIN_REQUESTED, request(id, a)));

        put(storeA, a, PredictionEventType.JOIN_REQUESTED, request(id, b));
        assertEquals(1, storeA.membershipProjection().openRequests().size());
        assertEquals(b.nodeId(), storeA.membershipProjection().openRequests().getFirst().joinerNodeId());
        assertEquals("JOIN_REQUEST_EXISTS", refusal(storeA, a, PredictionEventType.JOIN_REQUESTED, request(id, b)));
        assertEquals("JOIN_REQUEST_NOT_FOUND", refusal(storeA, a, PredictionEventType.JOIN_ANSWERED,
                answer(UUID.randomUUID(), b, true, 0)));
        assertEquals("JOIN_REQUEST_NOT_FOUND", refusal(storeA, a, PredictionEventType.JOIN_ANSWERED, answer(id, c, true, 0)));

        assertEquals("STATEMENT_REVISION_STALE", refusal(storeA, a, PredictionEventType.JOIN_ANSWERED, answer(id, b, true, 1)));
        assertFalse(storeA.membershipProjection().isMember(b.nodeId()));

        put(storeA, a, PredictionEventType.JOIN_ANSWERED, answer(id, b, true, 0));
        MembershipProjection membership = storeA.membershipProjection();
        assertTrue(membership.isMember(b.nodeId()));
        assertEquals(1, membership.revision());
        assertTrue(membership.openRequests().isEmpty());
        assertEquals("JOIN_ALREADY_ANSWERED", refusal(storeA, a, PredictionEventType.JOIN_ANSWERED, answer(id, b, true, 1)));
    }

    @Test
    void aDeclinedJoinerStaysOutAndTheRevisionStillAdvances() throws Exception {
        PredictionEventStore storeA = store("a");
        put(storeA, a, PredictionEventType.PROJECT_SHARED, shared(a, new byte[0]));
        UUID id = UUID.randomUUID();
        put(storeA, a, PredictionEventType.JOIN_REQUESTED, request(id, c));
        put(storeA, a, PredictionEventType.JOIN_ANSWERED, answer(id, c, false, 0));
        assertFalse(storeA.membershipProjection().isMember(c.nodeId()));
        assertFalse(storeA.membershipProjection().logCopied(c.nodeId()));
        assertEquals(1, storeA.membershipProjection().revision());
        assertEquals(1, storeA.membershipProjection().requests().size());
    }

    @Test
    void aMembersAnswerIsValidOnlyUnderAnyMemberAndOnlyAsAcceptance() throws Exception {
        PredictionEventStore[] stores = sharedWithB(new byte[0]);
        PredictionEventStore storeA = stores[0];
        PredictionEventStore storeB = stores[1];
        assertTrue(storeB.membershipProjection().isMember(b.nodeId()));

        UUID first = UUID.randomUUID();
        put(storeA, a, PredictionEventType.JOIN_REQUESTED, request(first, c));
        sync(storeA, a, storeB, b);
        assertEquals("JOIN_ANSWER_NOT_THE_OWNERS", refusal(storeB, b, PredictionEventType.JOIN_ANSWERED, answer(first, c, true, 1)));

        // A non-owner cannot change the policy.
        assertEquals("NOT_THE_OWNER", refusal(storeB, b, PredictionEventType.JOIN_POLICY_SET,
                new JoinPolicySet(JoinPolicy.ANY_MEMBER, 1).encode()));
        assertEquals("STATEMENT_REVISION_STALE", refusal(storeA, a, PredictionEventType.JOIN_POLICY_SET,
                new JoinPolicySet(JoinPolicy.ANY_MEMBER, 0).encode()));
        put(storeA, a, PredictionEventType.JOIN_POLICY_SET, new JoinPolicySet(JoinPolicy.ANY_MEMBER, 1).encode());
        assertEquals(JoinPolicy.ANY_MEMBER, storeA.membershipProjection().policy());
        assertEquals(2, storeA.membershipProjection().revision());
        sync(storeA, a, storeB, b);

        assertEquals("JOIN_ANSWER_NOT_THE_OWNERS", refusal(storeB, b, PredictionEventType.JOIN_ANSWERED, answer(first, c, false, 2)));
        put(storeB, b, PredictionEventType.JOIN_ANSWERED, answer(first, c, true, 2));
        assertTrue(storeB.membershipProjection().isMember(c.nodeId()));
        assertEquals(2, storeB.membershipProjection().revision(), "a member's acceptance is not an owner statement");

        sync(storeA, a, storeB, b);
        assertTrue(storeA.membershipProjection().isMember(c.nodeId()));
        assertEquals(2, storeA.membershipProjection().revision());
        assertTrue(storeA.problems().isEmpty(), storeA.problems().toString());
    }

    @Test
    void aMemberCardIsRecordedForAMemberAndRefusedForAnyoneElse() throws Exception {
        PredictionEventStore storeA = store("a");
        PredictionEventStore storeB = store("b");
        put(storeA, a, PredictionEventType.PROJECT_SHARED, shared(a, new byte[0]));
        sync(storeA, a, storeB, b);

        assertEquals("NOT_A_MEMBER", refusal(storeB, b, PredictionEventType.MEMBER_CARD, new MemberCard("invite-b").encode()));

        put(storeA, a, PredictionEventType.MEMBER_CARD, new MemberCard("invite-a").encode());
        assertEquals("invite-a", storeA.membershipProjection().cards().get(a.nodeId()));
        assertEquals(1, storeA.membershipProjection().cards().size());
    }

    @Test
    void aNonMembersCardArrivesAsAFactThatCountsForNothing() throws Exception {
        PredictionEventStore storeA = store("a");
        PredictionEventStore storeB = store("b");
        put(storeA, a, PredictionEventType.PROJECT_SHARED, shared(a, new byte[0]));
        sync(storeA, a, storeB, b);
        // B is not a member of A's project, but A's own copy of B's log is not yet restricted: write the entry on B
        // before it learns of the project.
        byte[] forged = PredictionEvent.create(new ProjectId(project), new PredictionId(UUID.randomUUID()), new EventSequence(1),
                PredictionEventType.MEMBER_CARD, new NodeId(b.nodeId()), new MemberCard("invite-b").encode(), new byte[32], b,
                System.currentTimeMillis()).encoded();
        assertEquals(1, storeA.placeCopies(b.nodeId(), List.of(forged)));
        assertTrue(storeA.membershipProjection().cards().isEmpty());
        assertEquals(1, storeA.problems().size(), storeA.problems().toString());
        assertTrue(storeA.problems().getFirst().contains("NOT_A_MEMBER"), storeA.problems().toString());
    }

    @Test
    void removalCutsTheRemovedLogAtItsHeadAndEndsItsLanes() throws Exception {
        PredictionEventStore[] stores = sharedWithB(new byte[0]);
        PredictionEventStore storeA = stores[0];
        PredictionEventStore storeB = stores[1];

        WorkIntent lane = lane("agt-b", "src/b.py");
        assertTrue(new WorkIntentService(storeB, b).announce(lane).acquired());
        pull(storeB, PredictionEventStore.OWN_LOG, storeA, b.nodeId());
        long head = storeA.logHeads().get(b.nodeId());
        assertTrue(head >= 2);
        assertEquals(1, storeA.collaborationProjection().activeIntents().size());

        // Not removable: the owner, a non-member.
        assertEquals("NOT_A_REMOVABLE_MEMBER", refusal(storeA, a, PredictionEventType.MEMBER_REMOVED,
                new MemberRemoved(a.nodeId(), 0, 1).encode()));
        assertEquals("NOT_A_REMOVABLE_MEMBER", refusal(storeA, a, PredictionEventType.MEMBER_REMOVED,
                new MemberRemoved(c.nodeId(), 0, 1).encode()));
        assertEquals("NOT_THE_OWNER", refusal(storeB, b, PredictionEventType.MEMBER_REMOVED,
                new MemberRemoved(b.nodeId(), head, 1).encode()));
        assertEquals("STATEMENT_REVISION_STALE", refusal(storeA, a, PredictionEventType.MEMBER_REMOVED,
                new MemberRemoved(b.nodeId(), head, 0).encode()));

        // B writes one more entry while apart: the removal has not reached it.
        put(storeB, b, PredictionEventType.MEMBER_CARD, new MemberCard("late").encode());
        put(storeA, a, PredictionEventType.MEMBER_REMOVED, new MemberRemoved(b.nodeId(), head, 1).encode());

        MembershipProjection membership = storeA.membershipProjection();
        assertFalse(membership.isMember(b.nodeId()));
        assertTrue(membership.logCopied(b.nodeId()));
        assertEquals(head, membership.removedHead(b.nodeId()).orElseThrow());
        assertEquals(2, membership.revision());
        assertTrue(storeA.collaborationProjection().activeIntents().isEmpty(), "the removed computer's lane ended");

        long before = storeA.events().size();
        pull(storeB, PredictionEventStore.OWN_LOG, storeA, b.nodeId());
        assertEquals(before, storeA.events().size(), "the entry after the head counts for nothing");
        assertEquals(1, storeA.problems().size(), storeA.problems().toString());
        assertTrue(storeA.problems().getFirst().contains("MEMBER_REMOVED"), storeA.problems().toString());
        assertTrue(membership.cards().isEmpty());

        // Once B holds the removal, its own append is refused too.
        pull(storeA, PredictionEventStore.OWN_LOG, storeB, a.nodeId());
        assertEquals("MEMBER_REMOVED", refusal(storeB, b, PredictionEventType.MEMBER_CARD, new MemberCard("again").encode()));
    }

    @Test
    void theRemovedComputersEntriesUpToTheHeadStillApply() throws Exception {
        PredictionEventStore[] stores = sharedWithB(new byte[0]);
        PredictionEventStore storeA = stores[0];
        PredictionEventStore storeB = stores[1];
        WorkIntent lane = lane("agt-b", "src/b.py");
        assertTrue(new WorkIntentService(storeB, b).announce(lane).acquired());
        put(storeB, b, PredictionEventType.MEMBER_CARD, new MemberCard("early").encode());
        pull(storeB, PredictionEventStore.OWN_LOG, storeA, b.nodeId());
        long head = storeA.logHeads().get(b.nodeId());
        long before = storeA.events().size();

        put(storeA, a, PredictionEventType.MEMBER_REMOVED, new MemberRemoved(b.nodeId(), head, 1).encode());
        assertEquals(before + 1, storeA.events().size());
        assertTrue(storeA.problems().isEmpty(), storeA.problems().toString());
        // Entries of the removed log that arrive late but sit within the head are fine: a third computer's copy.
        PredictionEventStore storeC = store("c");
        pull(storeA, PredictionEventStore.OWN_LOG, storeC, a.nodeId());
        pull(storeB, PredictionEventStore.OWN_LOG, storeC, b.nodeId());
        assertTrue(storeC.problems().isEmpty(), storeC.problems().toString());
        assertEquals(storeA.events().size(), storeC.events().size(), storeC.logHeads() + " vs " + storeA.logHeads());
        assertTrue(storeC.collaborationProjection().activeIntents().isEmpty());
    }

    @Test
    void ownershipMovesToAMemberOnTheOwnersStatement() throws Exception {
        PredictionEventStore[] stores = sharedWithB(new byte[0]);
        PredictionEventStore storeA = stores[0];
        PredictionEventStore storeB = stores[1];

        assertEquals("NOT_THE_OWNER", refusal(storeB, b, PredictionEventType.OWNER_CHANGED, ownerChange(b, new byte[0], 1, new byte[0])));
        assertEquals("NEW_OWNER_NOT_A_MEMBER", refusal(storeA, a, PredictionEventType.OWNER_CHANGED,
                ownerChange(c, new byte[0], 1, new byte[0])));
        assertEquals("STATEMENT_REVISION_STALE", refusal(storeA, a, PredictionEventType.OWNER_CHANGED,
                ownerChange(b, new byte[0], 0, new byte[0])));

        put(storeA, a, PredictionEventType.OWNER_CHANGED, ownerChange(b, new byte[0], 1, new byte[0]));
        assertEquals(b.nodeId(), storeA.membershipProjection().owner().orElseThrow());
        assertEquals(2, storeA.membershipProjection().revision());
        assertTrue(storeA.membershipProjection().isMember(a.nodeId()), "the former owner stays a member");

        sync(storeA, a, storeB, b);
        assertEquals(b.nodeId(), storeB.membershipProjection().owner().orElseThrow());
        assertEquals("NOT_THE_OWNER", refusal(storeA, a, PredictionEventType.OWNER_CHANGED, ownerChange(a, new byte[0], 2, new byte[0])));
        put(storeB, b, PredictionEventType.OWNER_CHANGED, ownerChange(a, new byte[0], 2, new byte[0]));
        assertEquals(3, storeB.membershipProjection().revision());
    }

    @Test
    void theRecoveryKeySpeaksForTheOwnerFromAnyMembersComputer() throws Exception {
        RecoveryKey recovery = RecoveryKey.generate();
        PredictionEventStore[] stores = sharedWithB(recovery.publicKey());
        PredictionEventStore storeA = stores[0];
        PredictionEventStore storeB = stores[1];
        assertArrayEquals(recovery.publicKey(), storeB.membershipProjection().recoveryPublicKey());

        byte[] newRecovery = new byte[0];
        byte[] statement = OwnerChanged.statement(project, b.publicKeyEncoded(), newRecovery, 1);
        byte[] good = recovery.identity().sign(statement);

        byte[] wrong = RecoveryKey.generate().identity().sign(statement);
        assertEquals("RECOVERY_SIGNATURE_INVALID", refusal(storeB, b, PredictionEventType.OWNER_CHANGED,
                ownerChange(b, newRecovery, 1, wrong)));
        assertEquals("RECOVERY_SIGNATURE_INVALID", refusal(storeB, b, PredictionEventType.OWNER_CHANGED,
                ownerChange(b, newRecovery, 1, new byte[] {1, 2, 3})));
        // A signature over another revision's statement does not carry over.
        assertEquals("RECOVERY_SIGNATURE_INVALID", refusal(storeB, b, PredictionEventType.OWNER_CHANGED,
                ownerChange(b, newRecovery, 0, good)));
        // A revision above the current one is a statement from the future.
        byte[] future = recovery.identity().sign(OwnerChanged.statement(project, b.publicKeyEncoded(), newRecovery, 9));
        assertEquals("RECOVERY_STATEMENT_INVALID", refusal(storeB, b, PredictionEventType.OWNER_CHANGED,
                ownerChange(b, newRecovery, 9, future)));
        // A non-member cannot carry it.
        PredictionEventStore storeC = store("c");
        sync(storeA, a, storeC, c);
        assertEquals("NOT_A_MEMBER", refusal(storeC, c, PredictionEventType.OWNER_CHANGED, ownerChange(b, newRecovery, 1, good)));

        put(storeB, b, PredictionEventType.OWNER_CHANGED, ownerChange(b, newRecovery, 1, good));
        assertEquals(b.nodeId(), storeB.membershipProjection().owner().orElseThrow());
        assertEquals(2, storeB.membershipProjection().revision());
        assertEquals(0, storeB.membershipProjection().recoveryPublicKey().length);

        sync(storeA, a, storeB, b);
        assertEquals(b.nodeId(), storeA.membershipProjection().owner().orElseThrow());
        assertTrue(storeA.problems().isEmpty(), storeA.problems().toString());
    }

    @Test
    void aProjectWithoutARecoveryKeyRefusesARecoverySignature() throws Exception {
        PredictionEventStore[] stores = sharedWithB(new byte[0]);
        RecoveryKey recovery = RecoveryKey.generate();
        byte[] signature = recovery.identity().sign(OwnerChanged.statement(project, b.publicKeyEncoded(), new byte[0], 1));
        assertEquals("RECOVERY_STATEMENT_INVALID", refusal(stores[1], b, PredictionEventType.OWNER_CHANGED,
                ownerChange(b, new byte[0], 1, signature)));
    }

    @Test
    void aRecoveryStatementCannotBeReplayedAfterTheChangeItAuthorised() throws Exception {
        // The statement names a revision and the project, but nothing marks it used: after ownership moves on, the
        // same entry still verifies, because its revision is not above the current one.
        RecoveryKey recovery = RecoveryKey.generate();
        PredictionEventStore[] stores = sharedWithB(recovery.publicKey());
        PredictionEventStore storeA = stores[0];
        PredictionEventStore storeB = stores[1];
        byte[] keepRecovery = recovery.publicKey();
        byte[] statement = OwnerChanged.statement(project, b.publicKeyEncoded(), keepRecovery, 1);
        byte[] signed = ownerChange(b, keepRecovery, 1, recovery.identity().sign(statement));
        put(storeB, b, PredictionEventType.OWNER_CHANGED, signed);
        sync(storeA, a, storeB, b);
        assertEquals(b.nodeId(), storeA.membershipProjection().owner().orElseThrow());

        // B hands ownership back to A with its own signature, then a replay of the old recovery entry.
        put(storeB, b, PredictionEventType.OWNER_CHANGED, ownerChange(a, keepRecovery, 2, new byte[0]));
        sync(storeA, a, storeB, b);
        assertEquals(a.nodeId(), storeA.membershipProjection().owner().orElseThrow());

        assertThrows(IOException.class, () -> put(storeB, b, PredictionEventType.OWNER_CHANGED, signed));
        assertEquals(a.nodeId(), storeB.membershipProjection().owner().orElseThrow());
    }

    @Test
    void aReopenedStoreHoldsTheSameMembership() throws Exception {
        RecoveryKey recovery = RecoveryKey.generate();
        PredictionEventStore[] stores = sharedWithB(recovery.publicKey());
        PredictionEventStore storeA = stores[0];
        PredictionEventStore storeB = stores[1];
        WorkIntent lane = lane("agt-b", "src/b.py");
        assertTrue(new WorkIntentService(storeB, b).announce(lane).acquired());
        put(storeA, a, PredictionEventType.MEMBER_CARD, new MemberCard("invite-a").encode());
        put(storeA, a, PredictionEventType.JOIN_POLICY_SET, new JoinPolicySet(JoinPolicy.ANY_MEMBER, 1).encode());
        pull(storeB, PredictionEventStore.OWN_LOG, storeA, b.nodeId());
        long head = storeA.logHeads().get(b.nodeId());
        put(storeA, a, PredictionEventType.MEMBER_REMOVED, new MemberRemoved(b.nodeId(), head, 2).encode());
        UUID open = UUID.randomUUID();
        put(storeA, a, PredictionEventType.JOIN_REQUESTED, request(open, c));

        PredictionEventStore reopened = store("a");
        MembershipProjection before = storeA.membershipProjection();
        MembershipProjection after = reopened.membershipProjection();
        assertEquals(before.owner(), after.owner());
        assertEquals(before.members(), after.members());
        assertEquals(before.revision(), after.revision());
        assertEquals(before.policy(), after.policy());
        assertEquals(before.cards(), after.cards());
        assertEquals(before.requests(), after.requests());
        assertEquals(before.removedHead(b.nodeId()), after.removedHead(b.nodeId()));
        assertEquals(before.logCopied(b.nodeId()), after.logCopied(b.nodeId()));
        assertArrayEquals(before.recoveryPublicKey(), after.recoveryPublicKey());
        assertEquals(1, after.openRequests().size());
        assertEquals(Set.of(a.nodeId()), after.members());
        assertTrue(reopened.collaborationProjection().activeIntents().isEmpty());
    }

    private WorkIntent lane(String participant, String path) {
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
                UUID.randomUUID(),
                WorkIntent.defaultAuthorityLineage(intentId),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                List.of());
    }
}
