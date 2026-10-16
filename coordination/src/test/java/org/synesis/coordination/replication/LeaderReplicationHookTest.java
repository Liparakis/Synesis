package org.synesis.coordination.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.EventSequence;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.PredictionId;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.ProposalOutcome;
import org.synesis.coordination.persistence.ReplicationCodec;
import org.synesis.coordination.persistence.ReplicationMessage;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;

/**
 * The leader tells the owner of its hook when a member's entry went on the log, and only then, so a merge pass can
 * follow it without any agent of the leader's calling in (ADR-0098, "Merging runs on A").
 */
final class LeaderReplicationHookTest {

    private final UUID project = UUID.randomUUID();

    private byte[] announce(NodeIdentity signer, long sequence, byte[] previousDigest) throws Exception {
        UUID intentId = UUID.randomUUID();
        WorkIntent lane = new WorkIntent(intentId, project, "agt-b", "codex", UUID.randomUUID(), "Build a board",
                "CRUD and persistence", "base-commit", List.of(ResourceSelector.pathExact("src/b.py")), 1,
                UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of());
        return PredictionEvent.create(new ProjectId(project), new PredictionId(intentId), new EventSequence(sequence),
                PredictionEventType.WORK_INTENT_ANNOUNCED, new NodeId(signer.nodeId()),
                CollaborationCodec.encodeIntent(lane), previousDigest, signer, System.currentTimeMillis()).encoded();
    }

    private ReplicationMessage ask(LeaderReplication leader, String sender, byte[] proposal) throws Exception {
        return ReplicationCodec.decode(leader.handler()
                .handle(sender, ReplicationCodec.encode(new ReplicationMessage.Propose(project, proposal)))
                .toCompletableFuture().get(30, TimeUnit.SECONDS));
    }

    @Test
    void theHookRunsAfterAnAcceptedProposalAndNotAfterARefusedOne(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("leader/coordination");
        NodeIdentity member = new IdentityBootstrap(temp.resolve("member/profile")).loadOrCreate().identity();
        ReplicationSettings.leader(Set.of(member.nodeId())).write(root);
        AtomicInteger calls = new AtomicInteger();
        try (LeaderReplication leader = LeaderReplication.open(root, project, calls::incrementAndGet)) {
            ReplicationMessage refused = ask(leader, "sl1-someone-else", announce(member, 1, new byte[32]));

            assertEquals(new ReplicationMessage.Outcome(new ProposalOutcome.Refused("PROPOSAL_SENDER_MISMATCH")), refused);
            assertEquals(0, calls.get(), "a refused proposal put nothing on the log");

            ReplicationMessage accepted = ask(leader, member.nodeId(), announce(member, 1, new byte[32]));

            assertTrue(accepted instanceof ReplicationMessage.Outcome(ProposalOutcome outcome)
                    && outcome instanceof ProposalOutcome.Accepted, accepted.toString());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void aHookThatFailsDoesNotTurnAnAcceptedProposalIntoNoAnswer(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("leader/coordination");
        NodeIdentity member = new IdentityBootstrap(temp.resolve("member/profile")).loadOrCreate().identity();
        ReplicationSettings.leader(Set.of(member.nodeId())).write(root);
        try (LeaderReplication leader = LeaderReplication.open(root, project, () -> {
            throw new IllegalStateException("the merge driver is down");
        })) {
            ReplicationMessage accepted = ask(leader, member.nodeId(), announce(member, 1, new byte[32]));

            assertTrue(accepted instanceof ReplicationMessage.Outcome(ProposalOutcome outcome)
                    && outcome instanceof ProposalOutcome.Accepted, accepted.toString());
        }
    }
}
