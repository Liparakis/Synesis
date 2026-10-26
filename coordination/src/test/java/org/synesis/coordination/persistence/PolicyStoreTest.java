package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.PolicyService;
import org.synesis.coordination.application.PolicyService.Decision;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinAnswered;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinRequested;
import org.synesis.coordination.domain.membership.MembershipEntries.ProjectShared;
import org.synesis.coordination.domain.policy.PolicyEntries.PolicyName;
import org.synesis.coordination.domain.policy.PolicyEntries.ProjectPolicySet;
import org.synesis.coordination.domain.policy.PolicyProjection;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.NodeIdentity;

/**
 * The owner's signed decisions through real stores (ADR-0104, Part A): who may write one, what the operator key adds,
 * and what a replay, a forgery or another computer's copy changes.
 */
final class PolicyStoreTest {

    private static final String COMMAND = "{\"argv\":[\"git\",\"status\"],\"workingDirectory\":\".\",\"timeoutSeconds\":120}";

    private final UUID project = UUID.randomUUID();
    private Path temp;
    private NodeIdentity a;
    private NodeIdentity b;
    private NodeIdentity operator;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        temp = directory;
        a = NodeIdentity.generate();
        b = NodeIdentity.generate();
        operator = NodeIdentity.generate();
    }

    private PredictionEventStore store(String name) throws Exception {
        return new PredictionEventStore(temp.resolve(name), project);
    }

    private static void put(PredictionEventStore store, NodeIdentity who, PredictionEventType type, byte[] payload) throws Exception {
        store.append(UUID.randomUUID(), type, who.nodeId(), payload, who);
    }

    private static String refusal(PredictionEventStore store, NodeIdentity who, byte[] payload) throws Exception {
        long before = store.logHeads().get(PredictionEventStore.OWN_LOG);
        IOException failure = assertThrows(IOException.class, () -> put(store, who, PredictionEventType.PROJECT_POLICY_SET, payload));
        assertEquals(before, store.logHeads().get(PredictionEventStore.OWN_LOG), "a refused entry writes nothing");
        return failure.getMessage();
    }

    private byte[] decision(NodeIdentity signsWith, long revision, PolicyName name, String value) throws Exception {
        byte[] statement = ProjectPolicySet.statement(project, revision, name, value);
        return new ProjectPolicySet(revision, name, value, signsWith.sign(statement)).encode();
    }

    private static String key(NodeIdentity identity) {
        return Base64.getEncoder().encodeToString(identity.publicKeyEncoded());
    }

    /** Decides through a fresh store, as the service does, then brings the test's long-lived store up to date. */
    private static PolicyService.Written decide(PredictionEventStore store, NodeIdentity who, NodeIdentity operatorKey, List<Decision> decisions) throws Exception {
        PolicyService.Written written = new PolicyService(store, who).decide(operatorKey, decisions);
        store.catchUp();
        return written;
    }

    private static int pull(PredictionEventStore from, PredictionEventStore to, String writer) throws Exception {
        List<byte[]> entries = from.entriesAfter(PredictionEventStore.OWN_LOG, to.logHeads().getOrDefault(writer, 0L), 1000, Integer.MAX_VALUE);
        return to.placeCopies(writer, entries);
    }

    @Test
    void theBaselineRegistersTheOperatorKeyAndTheDecisionsThatFollowSetThePolicy() throws Exception {
        PredictionEventStore store = store("a");
        PolicyProjection policy = store.policyProjection();
        assertFalse(policy.baselineApplied(), "a project with no baseline has no policy");

        PolicyService.Written written = decide(store, a, operator, List.of(
                new Decision(PolicyName.VALIDATION, COMMAND),
                new Decision(PolicyName.VALIDATION_ALSO_IN_LANE, "true"),
                new Decision(PolicyName.REVIEW, "MANDATORY")));

        assertEquals(4, written.written(), "the baseline and three decisions");
        assertEquals(4, written.revision());
        assertTrue(policy.baselineApplied());
        assertArrayEquals(operator.publicKeyEncoded(), policy.operatorKey().orElseThrow());
        assertEquals(a.nodeId(), policy.baselineSigner().orElseThrow());
        assertEquals(COMMAND, policy.validation());
        assertTrue(policy.validationAlsoInLane());
        assertTrue(policy.reviewMandatory());
        assertFalse(policy.unsandboxedAllowed(), "the sandbox allowance defaults to no");
        assertEquals(4, policy.statements());

        PredictionEventStore reopened = store("a");
        assertEquals(COMMAND, reopened.policyProjection().validation(), "the log alone gives the policy back");
    }

    @Test
    void decidingWhatIsAlreadyInForceWritesNothing() throws Exception {
        PredictionEventStore store = store("a");
        decide(store, a, operator, List.of(new Decision(PolicyName.VALIDATION, COMMAND)));

        PolicyService.Written again = decide(store, a, operator, List.of(new Decision(PolicyName.VALIDATION, COMMAND),
                new Decision(PolicyName.REVIEW, "NONE")));

        assertEquals(0, again.written());
        assertEquals(2, again.revision());
        assertEquals(2, store.policyProjection().statements());
    }

    @Test
    void aDecisionWithNoBaselineBeforeItIsRefused() throws Exception {
        PredictionEventStore store = store("a");

        assertEquals("POLICY_BASELINE_REQUIRED", refusal(store, a, decision(operator, 0, PolicyName.VALIDATION, COMMAND)));
        assertFalse(store.policyProjection().baselineApplied());
    }

    @Test
    void aDecisionSignedByAnotherKeyThanTheRegisteredOperatorKeyCountsForNothing() throws Exception {
        PredictionEventStore store = store("a");
        decide(store, a, operator, List.of());
        NodeIdentity thief = NodeIdentity.generate();

        // The node key signs the entry, as an agent that read it could; the payload still lacks the operator key.
        assertEquals("POLICY_SIGNATURE_INVALID", refusal(store, a, decision(thief, 1, PolicyName.VALIDATION, "")));
        assertEquals("POLICY_SIGNATURE_INVALID", refusal(store, a, decision(a, 1, PolicyName.REVIEW, "NONE")));
        assertEquals("", store.policyProjection().validation());
        assertEquals(1, store.policyProjection().statements());
    }

    @Test
    void aSecondBaselineCannotReplaceTheRegisteredKeyUnlessTheCurrentKeySignsIt() throws Exception {
        PredictionEventStore store = store("a");
        decide(store, a, operator, List.of());
        NodeIdentity next = NodeIdentity.generate();

        assertEquals("POLICY_BASELINE_EXISTS", refusal(store, a, decision(next, 1, PolicyName.OPERATOR_KEY, key(next))));
        assertArrayEquals(operator.publicKeyEncoded(), store.policyProjection().operatorKey().orElseThrow());

        put(store, a, PredictionEventType.PROJECT_POLICY_SET, decision(operator, 1, PolicyName.OPERATOR_KEY, key(next)));
        assertArrayEquals(next.publicKeyEncoded(), store.policyProjection().operatorKey().orElseThrow());
    }

    @Test
    void aReplayedOrReorderedStatementIsStale() throws Exception {
        PredictionEventStore store = store("a");
        decide(store, a, operator, List.of(new Decision(PolicyName.REVIEW, "MANDATORY")));
        byte[] replayed = decision(operator, 1, PolicyName.REVIEW, "MANDATORY");

        assertEquals("STATEMENT_REVISION_STALE", refusal(store, a, replayed));
        assertEquals("STATEMENT_REVISION_STALE", refusal(store, a, decision(operator, 5, PolicyName.REVIEW, "NONE")));
        assertTrue(store.policyProjection().reviewMandatory());
    }

    @Test
    void aStatementCannotBeMovedToAnotherProjectOrAnotherValue() throws Exception {
        PredictionEventStore store = store("a");
        decide(store, a, operator, List.of());
        byte[] signedForNone = new ProjectPolicySet(1, PolicyName.REVIEW, "NONE",
                operator.sign(ProjectPolicySet.statement(project, 1, PolicyName.REVIEW, "NONE"))).encode();
        // The same signature carried over a different value is no signature at all.
        byte[] swapped = new ProjectPolicySet(1, PolicyName.REVIEW, "MANDATORY", ProjectPolicySet.decode(signedForNone).operatorSignature()).encode();
        byte[] otherProject = new ProjectPolicySet(1, PolicyName.REVIEW, "MANDATORY",
                operator.sign(ProjectPolicySet.statement(UUID.randomUUID(), 1, PolicyName.REVIEW, "MANDATORY"))).encode();

        assertEquals("POLICY_SIGNATURE_INVALID", refusal(store, a, swapped));
        assertEquals("POLICY_SIGNATURE_INVALID", refusal(store, a, otherProject));
        assertFalse(store.policyProjection().reviewMandatory());
    }

    @Test
    void aNodeThatIsNotTheBaselineSignerCannotDecideForAnUnsharedProject() throws Exception {
        PredictionEventStore store = store("a");
        decide(store, a, operator, List.of());

        assertEquals("NOT_THE_OWNER", refusal(store, b, decision(operator, 1, PolicyName.REVIEW, "MANDATORY")));
    }

    @Test
    void anUnknownDecisionNameIsRefusedAndNothingIsApplied() throws Exception {
        PredictionEventStore store = store("a");
        decide(store, a, operator, List.of());
        byte[] unknown = org.synesis.coordination.domain.identity.WireFormat.encode(0x53505331, out -> {
            out.writeLong(1);
            out.writeInt(5);
            out.write("gates".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.writeInt(0);
            out.writeInt(64);
            out.write(new byte[64]);
        });

        assertEquals("POLICY_NAME_UNKNOWN", refusal(store, a, unknown));
    }

    @Test
    void sharingFromAnotherNodeThanTheBaselineSignerIsRefused() throws Exception {
        PredictionEventStore store = store("a");
        decide(store, a, operator, List.of());

        IOException refused = assertThrows(IOException.class,
                () -> put(store, b, PredictionEventType.PROJECT_SHARED, new ProjectShared(b.publicKeyEncoded(), new byte[0]).encode()));

        assertEquals("OWNER_NOT_THE_POLICY_BASELINE_SIGNER", refused.getMessage());
        put(store, a, PredictionEventType.PROJECT_SHARED, new ProjectShared(a.publicKeyEncoded(), new byte[0]).encode());
        assertEquals(a.nodeId(), store.membershipProjection().owner().orElseThrow(), "the baseline signer becomes the owner and nothing is re-keyed");
    }

    @Test
    void inASharedProjectOnlyTheOwnersComputerDecidesEvenWithTheOperatorKey() throws Exception {
        PredictionEventStore storeA = store("a");
        PredictionEventStore storeB = store("b");
        decide(storeA, a, operator, List.of(new Decision(PolicyName.VALIDATION, COMMAND)));
        put(storeA, a, PredictionEventType.PROJECT_SHARED, new ProjectShared(a.publicKeyEncoded(), new byte[0]).encode());
        UUID request = UUID.randomUUID();
        put(storeA, a, PredictionEventType.JOIN_REQUESTED, new JoinRequested(request, b.nodeId()).encode());
        long revision = storeA.membershipProjection().revision();
        put(storeA, a, PredictionEventType.JOIN_ANSWERED, new JoinAnswered(request, b.nodeId(), true, revision).encode());
        pull(storeA, storeB, a.nodeId());

        // B holds the operator key too (the worst case: it was copied) and still counts for nothing.
        long now = storeB.membershipProjection().revision();
        assertEquals("NOT_THE_OWNER", refusal(storeB, b, decision(operator, now, PolicyName.VALIDATION, "")));
        assertThrows(IOException.class, () -> decide(storeB, b, operator, List.of(new Decision(PolicyName.REVIEW, "MANDATORY"))));
        assertEquals(COMMAND, storeB.policyProjection().validation());
    }

    @Test
    void aMemberAppliesTheOwnersDecisionsFromTheOwnersLogAndEndsWithTheSamePolicy() throws Exception {
        PredictionEventStore storeA = store("a");
        PredictionEventStore storeB = store("b");
        decide(storeA, a, operator, List.of(new Decision(PolicyName.VALIDATION, COMMAND)));
        put(storeA, a, PredictionEventType.PROJECT_SHARED, new ProjectShared(a.publicKeyEncoded(), new byte[0]).encode());
        UUID request = UUID.randomUUID();
        put(storeA, a, PredictionEventType.JOIN_REQUESTED, new JoinRequested(request, b.nodeId()).encode());
        put(storeA, a, PredictionEventType.JOIN_ANSWERED, new JoinAnswered(request, b.nodeId(), true, storeA.membershipProjection().revision()).encode());
        assertFalse(storeB.policyProjection().baselineApplied(), "a member that has received nothing has no policy");

        pull(storeA, storeB, a.nodeId());
        assertEquals(COMMAND, storeB.policyProjection().validation());

        decide(storeA, a, operator, List.of(new Decision(PolicyName.REVIEW, "MANDATORY"), new Decision(PolicyName.VALIDATION, "")));
        pull(storeA, storeB, a.nodeId());

        for (PredictionEventStore store : List.of(storeA, storeB, store("b"))) {
            assertEquals("", store.policyProjection().validation());
            assertTrue(store.policyProjection().reviewMandatory());
            assertEquals(storeA.membershipProjection().revision(), store.membershipProjection().revision());
            assertArrayEquals(operator.publicKeyEncoded(), store.policyProjection().operatorKey().orElseThrow());
        }
    }

    @Test
    void aForgedEntryInAnotherComputersLogCountsForNothingWhereverItArrives() throws Exception {
        PredictionEventStore storeA = store("a");
        PredictionEventStore storeB = store("b");
        decide(storeA, a, operator, List.of(new Decision(PolicyName.VALIDATION, COMMAND)));
        put(storeA, a, PredictionEventType.PROJECT_SHARED, new ProjectShared(a.publicKeyEncoded(), new byte[0]).encode());
        // B's own log, written before it joined anything, holds a baseline of its own with its own operator key.
        NodeIdentity forged = NodeIdentity.generate();
        decide(storeB, b, forged, List.of(new Decision(PolicyName.VALIDATION, "")));

        pull(storeB, storeA, b.nodeId());

        assertEquals(COMMAND, storeA.policyProjection().validation(), "B is no owner here: its entries are copied and applied to nothing");
        assertArrayEquals(operator.publicKeyEncoded(), storeA.policyProjection().operatorKey().orElseThrow());
    }
}
