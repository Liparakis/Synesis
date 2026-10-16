package org.synesis.coordination.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.persistence.ProposalOutcome;
import org.synesis.coordination.persistence.ReplicationCodec;
import org.synesis.coordination.persistence.ReplicationMessage;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;

/**
 * A leader that holds a session open but stops answering (a stalled process, a long pause) costs a member's proposals
 * one wait, not one wait for every send of every proposal while an agent's append holds its lock: the second
 * proposal is "unreachable" at once, and it reads like a dropped link to the agent, who tries again later.
 */
@Timeout(120)
final class SilentLeaderTest {

    private final UUID project = UUID.randomUUID();
    private PeerSession memberSession;
    private MemberReplication member;
    private Onboarding.PreparedHost host;
    private Onboarding.PreparedJoin join;

    @AfterEach
    void close() {
        if (memberSession != null) {
            memberSession.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
        }
        for (AutoCloseable resource : new AutoCloseable[] {member, join, host}) {
            try {
                if (resource != null) {
                    resource.close();
                }
            } catch (Exception ignored) {
                // Best effort.
            }
        }
    }

    @Test
    void aProposalAfterAnUnansweredOneIsUnreachableAtOnce(@TempDir Path temp) throws Exception {
        NodeIdentity leader = new IdentityBootstrap(temp.resolve("leader/profile")).loadOrCreate().identity();
        ReplicationSettings.member(leader.nodeId()).write(temp.resolve("member/coordination"));
        member = MemberReplication.open(temp.resolve("member/coordination"), project);
        host = new Onboarding(temp.resolve("leader/profile"), ignored -> {
        }).createCompactInvitation(null, (remote, payload) -> new CompletableFuture<>(), ignored -> {
        });
        CompletableFuture.supplyAsync(() -> {
            try {
                return host.awaitConnectionAndKeepAlive();
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
        join = new Onboarding(temp.resolve("member/profile"), ignored -> {
        }).importInvitation(host.invitationLink(), member.handler(), ignored -> {
        });
        memberSession = join.connectAndKeepAlive();
        member.attach(memberSession);
        byte[] proposal = ReplicationCodec.encode(new ReplicationMessage.Propose(project, new byte[] {1, 2, 3}));

        long started = System.nanoTime();
        ReplicationMessage first = ReplicationCodec.decode(member.relay(proposal));
        long firstSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
        started = System.nanoTime();
        ReplicationMessage second = ReplicationCodec.decode(member.relay(proposal));
        Duration secondTook = Duration.ofNanos(System.nanoTime() - started);

        assertInstanceOf(ProposalOutcome.NoAnswer.class, ((ReplicationMessage.Outcome) first).outcome());
        assertTrue(firstSeconds >= 3, "the first one waited for the leader: " + firstSeconds + " s");
        assertInstanceOf(ProposalOutcome.Unreachable.class, ((ReplicationMessage.Outcome) second).outcome());
        assertTrue(secondTook.compareTo(Duration.ofSeconds(1)) < 0, "the second one did not wait: " + secondTook);
        assertEquals(leader.nodeId(), memberSession.remoteNodeId());
    }
}
