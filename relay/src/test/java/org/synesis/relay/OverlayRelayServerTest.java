package org.synesis.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayEnvelope;
import org.synesis.link.overlay.OverlayForwardingFrame;
import org.synesis.link.overlay.OverlayKeyAgreement;
import org.synesis.link.overlay.OverlayMembershipSnapshot;
import org.synesis.link.overlay.OverlayRelay;
import org.synesis.link.overlay.OverlayRelayPolicy;

/**
 * Exercises the standalone relay over real localhost event-loop sockets.
 */
final class OverlayRelayServerTest {

    @Test
    void authenticatesAllowsLiveOpaqueBidirectionalPathAndRejectsUnauthorizedClient() throws Exception {
        NodeIdentity relayIdentity = NodeIdentity.generate();
        NodeIdentity authority = NodeIdentity.generate();
        NodeIdentity origin = NodeIdentity.generate();
        NodeIdentity destination = NodeIdentity.generate();
        NodeIdentity unauthorized = NodeIdentity.generate();
        UUID projectId = UUID.randomUUID();
        OverlayMembershipSnapshot membership = membership(projectId, authority, origin, destination, unauthorized);
        OverlayRelayPolicy policy = new OverlayRelayPolicy(Map.of(projectId,
                Set.of(origin.nodeId(), destination.nodeId())), 8, 8, 64);
        OverlayRelay core = new OverlayRelay(policy);
        try (OverlayRelayServer server = new OverlayRelayServer(new InetSocketAddress("127.0.0.1", 0),
                relayIdentity, core, 2)) {
            InetSocketAddress address = server.start();
            CompletableFuture<OverlayForwardingFrame> inbound = new CompletableFuture<>();
            try (OverlayRelayClient destinationClient = new OverlayRelayClient(destination, address,
                    relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), inbound::complete);
                    OverlayRelayClient originClient = new OverlayRelayClient(origin, address,
                            relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), ignored -> {
                            });
                    OverlayRelayClient unauthorizedClient = new OverlayRelayClient(unauthorized, address,
                            relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), ignored -> {
                            })) {
                destinationClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS);
                originClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertThrows(ExecutionException.class,
                        () -> unauthorizedClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS));

                OverlayKeyAgreement.Initiator agreement = OverlayKeyAgreement.start(projectId,
                        destination.nodeId(), UUID.randomUUID(), membership.revision(), origin);
                OverlayKeyAgreement.Responder response = OverlayKeyAgreement.respond(agreement.init(), destination,
                        membership);
                OverlayKeyAgreement.E2eSession sender = OverlayKeyAgreement.finish(agreement, response.response(),
                        membership);
                OverlayKeyAgreement.E2eSession receiver = response.session();
                byte[] plaintext = "localhost relay remains opaque".getBytes(StandardCharsets.UTF_8);
                OverlayEnvelope envelope = sender.encrypt(UUID.randomUUID(), plaintext);
                OverlayForwardingFrame frame = OverlayForwardingFrame.create(projectId, destination.nodeId(),
                        envelope.messageId(), 1, envelope.encoded());
                originClient.send(frame).toCompletableFuture().get(10, TimeUnit.SECONDS);
                OverlayForwardingFrame delivered = inbound.get(10, TimeUnit.SECONDS);
                assertEquals(0, delivered.remainingHops());
                assertFalse(java.util.Arrays.equals(plaintext, delivered.innerRecord()));
                assertArrayEquals(plaintext, receiver.decrypt(OverlayEnvelope.decode(delivered.innerRecord())));
                assertThrows(ExecutionException.class,
                        () -> originClient.send(frame).toCompletableFuture().get(10, TimeUnit.SECONDS));
                sender.close();
                receiver.close();
            }
        }
    }

    @Test
    void forwardsConcurrentFramesWithoutCrossRecipientConfusion() throws Exception {
        NodeIdentity relayIdentity = NodeIdentity.generate();
        NodeIdentity authority = NodeIdentity.generate();
        NodeIdentity origin = NodeIdentity.generate();
        NodeIdentity destination = NodeIdentity.generate();
        UUID projectId = UUID.randomUUID();
        OverlayMembershipSnapshot membership = membership(projectId, authority, origin, destination);
        OverlayRelay core = new OverlayRelay(new OverlayRelayPolicy(Map.of(projectId,
                Set.of(origin.nodeId(), destination.nodeId())), 8, 32, 128));
        try (OverlayRelayServer server = new OverlayRelayServer(new InetSocketAddress("127.0.0.1", 0),
                relayIdentity, core, 2)) {
            InetSocketAddress address = server.start();
            java.util.concurrent.ConcurrentLinkedQueue<OverlayForwardingFrame> frames =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();
            try (OverlayRelayClient destinationClient = new OverlayRelayClient(destination, address,
                    relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), frames::add);
                    OverlayRelayClient originClient = new OverlayRelayClient(origin, address,
                            relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), ignored -> {
                            })) {
                destinationClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS);
                originClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS);
                OverlayKeyAgreement.Initiator agreement = OverlayKeyAgreement.start(projectId,
                        destination.nodeId(), UUID.randomUUID(), membership.revision(), origin);
                OverlayKeyAgreement.Responder response = OverlayKeyAgreement.respond(agreement.init(), destination,
                        membership);
                OverlayKeyAgreement.E2eSession sender = OverlayKeyAgreement.finish(agreement, response.response(),
                        membership);
                OverlayKeyAgreement.E2eSession receiver = response.session();
                for (int index = 0; index < 8; index++) {
                    OverlayEnvelope envelope = sender.encrypt(UUID.randomUUID(),
                            ("frame-" + index).getBytes(StandardCharsets.UTF_8));
                    originClient.send(OverlayForwardingFrame.create(projectId, destination.nodeId(),
                            envelope.messageId(), 1, envelope.encoded())).toCompletableFuture().get(10,
                                    TimeUnit.SECONDS);
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (frames.size() < 8 && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertEquals(8, frames.size());
                for (OverlayForwardingFrame frame : frames) {
                    assertTrue(receiver.decrypt(OverlayEnvelope.decode(frame.innerRecord())).length > 0);
                }
                sender.close();
                receiver.close();
            }
        }
    }

    private static OverlayMembershipSnapshot membership(UUID projectId, NodeIdentity authority,
            NodeIdentity... additional) throws GeneralSecurityException {
        java.util.ArrayList<OverlayMembershipSnapshot.Member> members = new java.util.ArrayList<>();
        members.add(new OverlayMembershipSnapshot.Member(authority.nodeId(), OverlayMembershipSnapshot.STATUS_ACTIVE,
                authority.publicKeyEncoded()));
        for (NodeIdentity identity : additional) {
            members.add(new OverlayMembershipSnapshot.Member(identity.nodeId(),
                    OverlayMembershipSnapshot.STATUS_ACTIVE, identity.publicKeyEncoded()));
        }
        Instant issuedAt = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        return OverlayMembershipSnapshot.create(projectId, 1, issuedAt, issuedAt.plusSeconds(300), List.copyOf(members),
                authority);
    }
}
