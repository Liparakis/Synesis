package org.synesis.coordination.domain.membership;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinAnswered;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinPolicy;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinPolicySet;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinRequested;
import org.synesis.coordination.domain.membership.MembershipEntries.MemberCard;
import org.synesis.coordination.domain.membership.MembershipEntries.MemberRemoved;
import org.synesis.coordination.domain.membership.MembershipEntries.OwnerChanged;
import org.synesis.coordination.domain.membership.MembershipEntries.ProjectShared;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.link.identity.NodeIdentity;

/** The membership payload codecs: round trips, bounds, and malformed input (ADR-0098 step 6). */
final class MembershipEntriesTest {

    private static byte[] key() throws Exception {
        return NodeIdentity.generate().publicKeyEncoded();
    }

    @Test
    void projectSharedRoundTripsWithAndWithoutARecoveryKey() throws Exception {
        byte[] owner = key();
        byte[] recovery = key();
        ProjectShared with = ProjectShared.decode(new ProjectShared(owner, recovery).encode());
        assertArrayEquals(owner, with.ownerPublicKey());
        assertArrayEquals(recovery, with.recoveryPublicKey());

        ProjectShared without = ProjectShared.decode(new ProjectShared(owner, new byte[0]).encode());
        assertEquals(0, without.recoveryPublicKey().length);
    }

    @Test
    void projectSharedRefusesAnEmptyOwnerKeyAndAnOversizedKey() throws Exception {
        assertThrows(CoordinationArgumentException.class, () -> new ProjectShared(new byte[0], new byte[0]));
        assertThrows(CoordinationArgumentException.class, () -> new ProjectShared(new byte[257], new byte[0]));
        assertThrows(CoordinationArgumentException.class, () -> new ProjectShared(key(), new byte[257]));
        assertThrows(RuntimeException.class, () -> new ProjectShared(null, new byte[0]));
    }

    @Test
    void recordsCopyTheirKeys() throws Exception {
        byte[] owner = key();
        byte[] original = owner.clone();
        ProjectShared shared = new ProjectShared(owner, new byte[0]);
        Arrays.fill(owner, (byte) 0);
        assertArrayEquals(original, shared.ownerPublicKey());
    }

    @Test
    void joinRequestedRoundTrips() throws Exception {
        UUID request = UUID.randomUUID();
        String joiner = NodeIdentity.generate().nodeId();
        JoinRequested back = JoinRequested.decode(new JoinRequested(request, joiner).encode());
        assertEquals(new JoinRequested(request, joiner), back);
    }

    @Test
    void joinRequestedRefusesBlankNodeIdAndNullRequest() {
        assertThrows(CoordinationArgumentException.class, () -> new JoinRequested(UUID.randomUUID(), " "));
        assertThrows(CoordinationArgumentException.class, () -> new JoinRequested(UUID.randomUUID(), null));
        assertThrows(RuntimeException.class, () -> new JoinRequested(null, "n"));
    }

    @Test
    void joinAnsweredRoundTripsBothAnswers() {
        UUID request = UUID.randomUUID();
        for (boolean accepted : new boolean[] {true, false}) {
            JoinAnswered original = new JoinAnswered(request, "node", accepted, 7);
            assertEquals(original, decodeAnswered(original));
        }
        assertThrows(CoordinationArgumentException.class, () -> new JoinAnswered(request, "node", true, -1));
    }

    private static JoinAnswered decodeAnswered(JoinAnswered answered) {
        try {
            return JoinAnswered.decode(answered.encode());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void joinPolicySetRoundTripsEveryPolicyAndRefusesAnUnknownOne() throws Exception {
        for (JoinPolicy policy : JoinPolicy.values()) {
            assertEquals(new JoinPolicySet(policy, 3), JoinPolicySet.decode(new JoinPolicySet(policy, 3).encode()));
        }
        byte[] encoded = new JoinPolicySet(JoinPolicy.OWNER_ONLY, 0).encode();
        int at = indexOf(encoded, JoinPolicy.OWNER_ONLY.name().getBytes(StandardCharsets.UTF_8));
        encoded[at] = 'X';
        assertThrows(IOException.class, () -> JoinPolicySet.decode(encoded));
    }

    @Test
    void memberRemovedRoundTripsAndRefusesNegativeFields() throws Exception {
        assertEquals(new MemberRemoved("node", 12, 4), MemberRemoved.decode(new MemberRemoved("node", 12, 4).encode()));
        assertEquals(new MemberRemoved("node", 0, 0), MemberRemoved.decode(new MemberRemoved("node", 0, 0).encode()));
        assertThrows(CoordinationArgumentException.class, () -> new MemberRemoved("node", -1, 0));
        assertThrows(CoordinationArgumentException.class, () -> new MemberRemoved("node", 0, -1));
        assertThrows(CoordinationArgumentException.class, () -> new MemberRemoved("", 0, 0));
    }

    @Test
    void memberCardRoundTripsAndRefusesBlankAndOversizedInvitations() throws Exception {
        assertEquals(new MemberCard("synesis://invite/abc"), MemberCard.decode(new MemberCard("synesis://invite/abc").encode()));
        assertThrows(CoordinationArgumentException.class, () -> new MemberCard(" "));
        assertThrows(CoordinationArgumentException.class, () -> new MemberCard("x".repeat(4097)));
        assertThrows(CoordinationArgumentException.class, () -> new MemberCard(null));
        assertEquals(4096, MemberCard.decode(new MemberCard("x".repeat(4096)).encode()).invitation().length());
    }

    @Test
    void ownerChangedRoundTripsWithAndWithoutARecoverySignature() throws Exception {
        byte[] owner = key();
        byte[] recovery = key();
        byte[] signature = new byte[64];
        Arrays.fill(signature, (byte) 7);
        OwnerChanged signed = OwnerChanged.decode(new OwnerChanged(owner, recovery, 5, signature).encode());
        assertArrayEquals(owner, signed.ownerPublicKey());
        assertArrayEquals(recovery, signed.recoveryPublicKey());
        assertEquals(5, signed.revision());
        assertArrayEquals(signature, signed.recoverySignature());

        OwnerChanged unsigned = OwnerChanged.decode(new OwnerChanged(owner, new byte[0], 0, new byte[0]).encode());
        assertEquals(0, unsigned.recoverySignature().length);
        assertEquals(0, unsigned.recoveryPublicKey().length);
        assertThrows(CoordinationArgumentException.class, () -> new OwnerChanged(owner, recovery, 0, new byte[129]));
        assertThrows(CoordinationArgumentException.class, () -> new OwnerChanged(owner, recovery, -1, new byte[0]));
        assertThrows(RuntimeException.class, () -> new OwnerChanged(owner, recovery, 0, null));
    }

    @Test
    void theRecoveryStatementBindsProjectKeysAndRevision() throws Exception {
        UUID project = UUID.randomUUID();
        byte[] owner = key();
        byte[] recovery = key();
        byte[] statement = OwnerChanged.statement(project, owner, recovery, 3);
        assertArrayEquals(statement, new OwnerChanged(owner, recovery, 3, new byte[0]).recoveryStatement(project));

        assertFalse(Arrays.equals(statement, OwnerChanged.statement(UUID.randomUUID(), owner, recovery, 3)));
        assertFalse(Arrays.equals(statement, OwnerChanged.statement(project, key(), recovery, 3)));
        assertFalse(Arrays.equals(statement, OwnerChanged.statement(project, owner, key(), 3)));
        assertFalse(Arrays.equals(statement, OwnerChanged.statement(project, owner, recovery, 4)));
        // The length prefixes keep a byte moved between the two keys from giving the same statement.
        assertFalse(Arrays.equals(OwnerChanged.statement(project, new byte[] {1, 2, 3}, new byte[] {4, 5}, 0),
                OwnerChanged.statement(project, new byte[] {1, 2, 3, 4}, new byte[] {5}, 0)));
    }

    @Test
    void anotherPayloadsBytesAreRefusedByMagic() {
        byte[] card = new MemberCard("x").encode();
        assertThrows(IOException.class, () -> JoinAnswered.decode(card));
        assertThrows(IOException.class, () -> ProjectShared.decode(card));
        assertThrows(IOException.class, () -> OwnerChanged.decode(card));
    }

    @Test
    void emptyBadMagicAndNullAreRefused() {
        byte[] good = new JoinAnswered(UUID.randomUUID(), "node", true, 1).encode();
        assertThrows(IOException.class, () -> JoinAnswered.decode(new byte[0]));
        assertThrows(IOException.class, () -> JoinAnswered.decode(new byte[] {1, 2}));
        byte[] badMagic = good.clone();
        badMagic[0] ^= 0x7f;
        assertThrows(IOException.class, () -> JoinAnswered.decode(badMagic));
        assertThrows(RuntimeException.class, () -> JoinAnswered.decode(null));
    }

    @Test
    void everyCodecRefusesTruncationAtEveryLengthAndTrailingBytes() throws Exception {
        byte[][] goods = {
            new ProjectShared(key(), key()).encode(),
            new JoinRequested(UUID.randomUUID(), "node").encode(),
            new JoinAnswered(UUID.randomUUID(), "node", true, 1).encode(),
            new JoinPolicySet(JoinPolicy.ANY_MEMBER, 2).encode(),
            new MemberRemoved("node", 1, 1).encode(),
            new MemberCard("link").encode(),
            new OwnerChanged(key(), key(), 1, new byte[64]).encode()
        };
        for (int kind = 0; kind < goods.length; kind++) {
            int which = kind;
            for (int cut = 0; cut < goods[kind].length; cut++) {
                byte[] truncated = Arrays.copyOf(goods[kind], cut);
                assertThrows(IOException.class, () -> decode(which, truncated), "kind " + which + " cut " + truncated.length);
            }
            byte[] trailing = Arrays.copyOf(goods[kind], goods[kind].length + 1);
            assertThrows(IOException.class, () -> decode(which, trailing), "kind " + which + " trailing");
        }
    }

    private static void decode(int kind, byte[] bytes) throws IOException {
        switch (kind) {
            case 0 -> ProjectShared.decode(bytes);
            case 1 -> JoinRequested.decode(bytes);
            case 2 -> JoinAnswered.decode(bytes);
            case 3 -> JoinPolicySet.decode(bytes);
            case 4 -> MemberRemoved.decode(bytes);
            case 5 -> MemberCard.decode(bytes);
            default -> OwnerChanged.decode(bytes);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(haystack, i, i + needle.length), needle)) {
                return i;
            }
        }
        throw new IllegalStateException("not found");
    }
}
