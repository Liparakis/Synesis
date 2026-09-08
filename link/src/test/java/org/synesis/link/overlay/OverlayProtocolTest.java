package org.synesis.link.overlay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import org.synesis.link.identity.NodeIdentity;

/**
 * Exercises the signed overlay records, E2E binding, replay rejection, and
 * hop-budget enforcement without opening a physical socket.
 */
final class OverlayProtocolTest {

    @Test
    void membershipRoundTripAndTamperAreVisible() throws Exception {
        NodeIdentity authority = NodeIdentity.generate();
        NodeIdentity peer = NodeIdentity.generate();
        UUID projectId = UUID.randomUUID();
        OverlayMembershipSnapshot snapshot = membership(projectId, authority, peer);

        assertTrue(snapshot.verify());
        assertTrue(snapshot.allows(authority.nodeId()));
        assertTrue(snapshot.allows(peer.nodeId()));

        OverlayMembershipSnapshot decoded = OverlayMembershipSnapshot.decode(snapshot.encoded());
        assertArrayEquals(snapshot.encoded(), decoded.encoded());
        assertTrue(decoded.verify());

        byte[] tamperedBytes = snapshot.encoded();
        tamperedBytes[tamperedBytes.length - 1] ^= 1;
        OverlayMembershipSnapshot tampered = OverlayMembershipSnapshot.decode(tamperedBytes);
        assertFalse(tampered.verify());
    }

    @Test
    void membershipRefreshReplacesStaleTopologyAndAddsAuthorizedPeerBinding() throws Exception {
        NodeIdentity authority = NodeIdentity.generate();
        NodeIdentity origin = NodeIdentity.generate();
        NodeIdentity existingPeer = NodeIdentity.generate();
        NodeIdentity newcomer = NodeIdentity.generate();
        UUID projectId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        OverlayMembershipSnapshot initial = membership(projectId, 1, authority, origin, existingPeer);
        OverlayMembershipSnapshot refreshed = membership(projectId, 2, authority, origin, existingPeer, newcomer);
        OverlayMembershipView membershipView = new OverlayMembershipView(initial);
        assertEquals(OverlayMembershipView.Acceptance.ACCEPTED, membershipView.accept(refreshed, now));
        assertEquals(OverlayMembershipView.Acceptance.DUPLICATE, membershipView.accept(refreshed, now));
        assertEquals(OverlayMembershipView.Acceptance.STALE, membershipView.accept(initial, now));

        OverlayMembershipView authorityMembership = new OverlayMembershipView(initial);
        OverlayMembershipView originMembership = new OverlayMembershipView(initial);
        OverlayMembershipView existingPeerMembership = new OverlayMembershipView(initial);
        AtomicReference<OverlayMembershipPropagation> authorityPropagationRef = new AtomicReference<>();
        AtomicReference<OverlayMembershipPropagation> originPropagationRef = new AtomicReference<>();
        AtomicReference<OverlayMembershipPropagation> existingPeerPropagationRef = new AtomicReference<>();
        AtomicInteger existingPeerReceives = new AtomicInteger();
        OverlayPeerRegistry<OverlayMembershipPropagation.PeerTransport> authorityPeers = new OverlayPeerRegistry<>();
        OverlayPeerRegistry<OverlayMembershipPropagation.PeerTransport> originPeers = new OverlayPeerRegistry<>();
        OverlayPeerRegistry<OverlayMembershipPropagation.PeerTransport> existingPeerPeers = new OverlayPeerRegistry<>();
        authorityPeers.bind(origin.nodeId(), frame -> receive(originPropagationRef.get(), authority.nodeId(), frame,
                now));
        originPeers.bind(authority.nodeId(), frame -> receive(authorityPropagationRef.get(), origin.nodeId(), frame,
                now));
        originPeers.bind(existingPeer.nodeId(), frame -> {
            existingPeerReceives.incrementAndGet();
            return receive(existingPeerPropagationRef.get(), origin.nodeId(), frame, now);
        });
        existingPeerPeers.bind(origin.nodeId(), frame -> receive(originPropagationRef.get(), existingPeer.nodeId(),
                frame, now));
        OverlayMembershipPropagation authorityPropagation = new OverlayMembershipPropagation(authority.nodeId(),
                authorityMembership, authorityPeers);
        OverlayMembershipPropagation originPropagation = new OverlayMembershipPropagation(origin.nodeId(),
                originMembership, originPeers);
        OverlayMembershipPropagation existingPeerPropagation = new OverlayMembershipPropagation(existingPeer.nodeId(),
                existingPeerMembership, existingPeerPeers);
        authorityPropagationRef.set(authorityPropagation);
        originPropagationRef.set(originPropagation);
        existingPeerPropagationRef.set(existingPeerPropagation);
        OverlayMembershipPropagationFrame encodedPropagation = OverlayMembershipPropagationFrame.create(refreshed, 2);
        assertArrayEquals(encodedPropagation.encoded(),
                OverlayMembershipPropagationFrame.decode(encodedPropagation.encoded()).encoded());
        authorityPropagation.publish(refreshed, now).toCompletableFuture().join();
        assertEquals(2, authorityMembership.current().revision());
        assertEquals(2, originMembership.current().revision());
        assertEquals(2, existingPeerMembership.current().revision());
        assertTrue(existingPeerMembership.current().allows(newcomer.nodeId()));
        assertEquals(1, existingPeerReceives.get());
        originPropagation.receive(authority.nodeId(), encodedPropagation, now).toCompletableFuture().join();
        assertEquals(1, existingPeerReceives.get(), "duplicate membership snapshots must not reflood");

        OverlayTopologyView topology = new OverlayTopologyView();
        OverlayTopologyAdvertisement oldAdvertisement = OverlayTopologyAdvertisement.create(projectId, 1, 1,
                now.plusSeconds(60), List.of(existingPeer.nodeId()), origin);
        assertEquals(OverlayTopologyView.Acceptance.ACCEPTED, topology.accept(oldAdvertisement, initial, now));
        OverlayTopologyAdvertisement refreshedAdvertisement = OverlayTopologyAdvertisement.create(projectId, 2, 1,
                now.plusSeconds(60), List.of(newcomer.nodeId()), origin);
        assertEquals(OverlayTopologyView.Acceptance.ACCEPTED,
                topology.accept(refreshedAdvertisement, refreshed, now));
        assertEquals(List.of(newcomer.nodeId()), topology.adjacencyGraph(refreshed, now)
                .get(origin.nodeId()));

        OverlayPeerRegistry<String> peers = new OverlayPeerRegistry<>();
        peers.bind(existingPeer.nodeId(), "existing");
        peers.bind(newcomer.nodeId(), "new");
        assertEquals(Set.of(existingPeer.nodeId(), newcomer.nodeId()), peers.nodeIds());
        assertEquals("new", peers.unbind(newcomer.nodeId()));
        assertFalse(peers.contains(newcomer.nodeId()));
    }

    @Test
    void keyAgreementProducesBidirectionalBoundOpaqueEnvelopesAndRejectsReplayAndTamper() throws Exception {
        NodeIdentity authority = NodeIdentity.generate();
        NodeIdentity destination = NodeIdentity.generate();
        NodeIdentity origin = NodeIdentity.generate();
        UUID projectId = UUID.randomUUID();
        OverlayMembershipSnapshot snapshot = membership(projectId, authority, destination, origin);
        UUID sessionId = UUID.randomUUID();

        OverlayKeyAgreement.Initiator initiator = OverlayKeyAgreement.start(projectId, destination.nodeId(),
                sessionId, snapshot.revision(), origin);
        OverlayKeyAgreement.InitRecord init = OverlayKeyAgreement.InitRecord.decode(initiator.init().encoded());
        assertDoesNotThrow(() -> init.verifyAgainst(snapshot));

        OverlayKeyAgreement.Responder responder = OverlayKeyAgreement.respond(init, destination, snapshot);
        OverlayKeyAgreement.ResponseRecord response = OverlayKeyAgreement.ResponseRecord.decode(
                responder.response().encoded());
        OverlayKeyAgreement.E2eSession originSession = OverlayKeyAgreement.finish(initiator, response, snapshot);
        OverlayKeyAgreement.E2eSession destinationSession = responder.session();
        UUID messageId = UUID.randomUUID();
        byte[] plaintext = "opaque A-to-C payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        OverlayEnvelope envelope = originSession.encrypt(messageId, plaintext);
        OverlayEnvelope decodedEnvelope = OverlayEnvelope.decode(envelope.encoded());
        assertArrayEquals(plaintext, destinationSession.decrypt(decodedEnvelope));
        assertThrows(GeneralSecurityException.class, () -> destinationSession.decrypt(decodedEnvelope));

        byte[] responsePlaintext = "opaque C-to-A payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OverlayEnvelope responseEnvelope = destinationSession.encrypt(UUID.randomUUID(), responsePlaintext);
        assertEquals(destination.nodeId(), responseEnvelope.originNodeId());
        assertEquals(origin.nodeId(), responseEnvelope.destinationNodeId());
        assertArrayEquals(responsePlaintext, originSession.decrypt(responseEnvelope));
        assertThrows(GeneralSecurityException.class, () -> destinationSession.decrypt(responseEnvelope));

        UUID sameMessageId = UUID.randomUUID();
        byte[] samePlaintext = "same plaintext, different directions".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        OverlayEnvelope forwardAtSameSequence = originSession.encrypt(sameMessageId, 7, samePlaintext);
        OverlayEnvelope reverseAtSameSequence = destinationSession.encrypt(sameMessageId, 7, samePlaintext);
        assertFalse(Arrays.equals(forwardAtSameSequence.ciphertext(), reverseAtSameSequence.ciphertext()));
        assertArrayEquals(samePlaintext, destinationSession.decrypt(forwardAtSameSequence));
        assertArrayEquals(samePlaintext, originSession.decrypt(reverseAtSameSequence));
        assertThrows(GeneralSecurityException.class,
                () -> originSession.encrypt(sameMessageId, 7, samePlaintext));

        byte[] tamperedBytes = envelope.encoded();
        tamperedBytes[tamperedBytes.length - 1] ^= 1;
        OverlayEnvelope tamperedEnvelope = OverlayEnvelope.decode(tamperedBytes);
        assertThrows(GeneralSecurityException.class, () -> destinationSession.decrypt(tamperedEnvelope));

        byte[] wrongDestinationBytes = initiator.init().encoded();
        int signatureOffset = wrongDestinationBytes.length - OverlayCodecSupport.SIGNATURE_BYTES - Short.BYTES;
        wrongDestinationBytes[signatureOffset + Short.BYTES] ^= 1;
        OverlayKeyAgreement.InitRecord tamperedInit = OverlayKeyAgreement.InitRecord.decode(wrongDestinationBytes);
        assertThrows(GeneralSecurityException.class, () -> OverlayKeyAgreement.respond(tamperedInit, destination,
                snapshot));

        originSession.close();
        destinationSession.close();
    }

    @Test
    void forwardingFrameIsOpaqueAndConsumesItsBudget() throws Exception {
        NodeIdentity authority = NodeIdentity.generate();
        NodeIdentity destination = NodeIdentity.generate();
        NodeIdentity origin = NodeIdentity.generate();
        UUID projectId = UUID.randomUUID();
        OverlayMembershipSnapshot snapshot = membership(projectId, authority, destination, origin);
        OverlayKeyAgreement.Initiator initiator = OverlayKeyAgreement.start(projectId, destination.nodeId(),
                UUID.randomUUID(), snapshot.revision(), origin);
        OverlayForwardingFrame frame = OverlayForwardingFrame.create(projectId, destination.nodeId(),
                UUID.randomUUID(), 3, initiator.init().encoded());

        OverlayForwardingFrame decoded = OverlayForwardingFrame.decode(frame.encoded());
        assertEquals(frame.innerRecord().length, decoded.innerRecord().length);
        assertArrayEquals(frame.innerRecord(), decoded.innerRecord());
        OverlayForwardingFrame next = decoded.forwardOneHop();
        assertEquals(2, next.remainingHops());
        assertEquals(1, decoded.forwardOneHop().forwardOneHop().remainingHops());
        assertEquals(0, decoded.forwardOneHop().forwardOneHop().forwardOneHop().remainingHops());
        assertThrows(IllegalStateException.class,
                () -> decoded.forwardOneHop().forwardOneHop().forwardOneHop().forwardOneHop());
    }

    @Test
    void topologyIsSignedSequencedAndRoutesAroundAForcedTransit() throws Exception {
        NodeIdentity authority = NodeIdentity.generate();
        NodeIdentity transit = NodeIdentity.generate();
        NodeIdentity origin = NodeIdentity.generate();
        UUID projectId = UUID.randomUUID();
        OverlayMembershipSnapshot snapshot = membership(projectId, authority, transit, origin);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        OverlayTopologyAdvertisement advertisement = OverlayTopologyAdvertisement.create(projectId,
                snapshot.revision(), 1, now.plusSeconds(60), List.of(authority.nodeId(), origin.nodeId()), transit);
        OverlayTopologyAdvertisement decoded = OverlayTopologyAdvertisement.decode(advertisement.encoded());
        decoded.verifyAgainst(snapshot, now);

        OverlayTopologyView view = new OverlayTopologyView();
        assertEquals(OverlayTopologyView.Acceptance.ACCEPTED, view.accept(decoded, snapshot, now));
        assertEquals(OverlayTopologyView.Acceptance.DUPLICATE, view.accept(decoded, snapshot, now));

        OverlayTopologyAdvertisement newer = OverlayTopologyAdvertisement.create(projectId, snapshot.revision(), 2,
                now.plusSeconds(60), List.of(authority.nodeId(), origin.nodeId()), transit);
        assertEquals(OverlayTopologyView.Acceptance.ACCEPTED, view.accept(newer, snapshot, now));
        assertEquals(OverlayTopologyView.Acceptance.STALE, view.accept(decoded, snapshot, now));

        byte[] tamperedBytes = advertisement.encoded();
        tamperedBytes[tamperedBytes.length - 1] ^= 1;
        OverlayTopologyAdvertisement tampered = OverlayTopologyAdvertisement.decode(tamperedBytes);
        assertThrows(GeneralSecurityException.class, () -> tampered.verifyAgainst(snapshot, now));

        OverlayTopologyView forcedTransit = new OverlayTopologyView();
        OverlayTopologyAdvertisement transitToBoth = OverlayTopologyAdvertisement.create(projectId,
                snapshot.revision(), 1, now.plusSeconds(60), List.of(authority.nodeId(), origin.nodeId()), transit);
        forcedTransit.accept(transitToBoth, snapshot, now);
        OverlayRoute forward = OverlayRouteSelector.select(authority.nodeId(), origin.nodeId(), snapshot,
                Set.of(transit.nodeId()), forcedTransit, now, null);
        assertEquals(OverlayRoute.Kind.PEER_TRANSIT, forward.kind());
        assertEquals(List.of(transit.nodeId(), origin.nodeId()), forward.path());
        OverlayRoute reverse = OverlayRouteSelector.select(origin.nodeId(), authority.nodeId(), snapshot,
                Set.of(transit.nodeId()), forcedTransit, now, null);
        assertEquals(List.of(transit.nodeId(), authority.nodeId()), reverse.path());
        assertEquals(OverlayRoute.Kind.DIRECT, OverlayRouteSelector.select(authority.nodeId(), origin.nodeId(),
                snapshot, Set.of(origin.nodeId()), forcedTransit, now, null).kind());
        assertEquals(OverlayRoute.Kind.ORGANIZATION_RELAY, OverlayRouteSelector.select(authority.nodeId(),
                origin.nodeId(), snapshot, Set.of(), new OverlayTopologyView(), now, "relay-1").kind());
    }

    @Test
    void topologyPolicyStaysConnectedAndDuplicateGuardExpiresBoundedly() throws Exception {
        NodeIdentity authority = NodeIdentity.generate();
        List<NodeIdentity> identities = new ArrayList<>();
        identities.add(authority);
        for (int index = 0; index < 7; index++) {
            identities.add(NodeIdentity.generate());
        }
        List<OverlayMembershipSnapshot.Member> members = identities.stream().map(identity ->
                new OverlayMembershipSnapshot.Member(identity.nodeId(), OverlayMembershipSnapshot.STATUS_ACTIVE,
                        identity.publicKeyEncoded())).toList();
        UUID projectId = UUID.randomUUID();
        Instant issuedAt = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        OverlayMembershipSnapshot snapshot = OverlayMembershipSnapshot.create(projectId, 1, issuedAt,
                issuedAt.plusSeconds(300), members, authority);
        Map<String, List<String>> graph = new OverlayTopologyPolicy().desiredNeighbors(snapshot.members());
        assertEquals(identities.size(), graph.size());
        assertTrue(graph.values().stream().allMatch(neighbors -> neighbors.size() <= OverlayTopologyPolicy.MAX_DEGREE));
        assertTrue(graph.values().stream().allMatch(neighbors -> neighbors.size() >= 2));
        assertTrue(isConnected(graph));

        OverlayKeyAgreement.Initiator initiator = OverlayKeyAgreement.start(projectId, identities.get(2).nodeId(),
                UUID.randomUUID(), snapshot.revision(), identities.get(1));
        OverlayForwardingFrame frame = OverlayForwardingFrame.create(projectId, identities.get(2).nodeId(),
                UUID.randomUUID(), 4, initiator.init().encoded());
        OverlayDuplicateGuard guard = new OverlayDuplicateGuard(2, Duration.ofSeconds(1));
        assertTrue(guard.firstSeen(frame, issuedAt));
        assertFalse(guard.firstSeen(frame, issuedAt.plusMillis(100)));
        assertTrue(guard.firstSeen(frame, issuedAt.plusSeconds(2)));
    }

    @Test
    void signedTopologyPropagatesThroughDirectPeersWithBoundedFlooding() throws Exception {
        NodeIdentity authority = NodeIdentity.generate();
        NodeIdentity origin = NodeIdentity.generate();
        NodeIdentity transit = NodeIdentity.generate();
        NodeIdentity destination = NodeIdentity.generate();
        UUID projectId = UUID.randomUUID();
        OverlayMembershipSnapshot snapshot = membership(projectId, authority, origin, transit, destination);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        OverlayTopologyView originView = new OverlayTopologyView();
        OverlayTopologyView transitView = new OverlayTopologyView();
        OverlayTopologyView destinationView = new OverlayTopologyView();
        AtomicReference<OverlayTopologyPropagation> originRef = new AtomicReference<>();
        AtomicReference<OverlayTopologyPropagation> transitRef = new AtomicReference<>();
        AtomicReference<OverlayTopologyPropagation> destinationRef = new AtomicReference<>();
        AtomicInteger destinationSends = new AtomicInteger();
        AtomicInteger destinationBudget = new AtomicInteger(-1);

        OverlayTopologyPropagation originPropagation = new OverlayTopologyPropagation(origin.nodeId(), snapshot,
                originView, Map.of(transit.nodeId(), frame -> receive(transitRef.get(), origin.nodeId(), frame, now)));
        OverlayTopologyPropagation transitPropagation = new OverlayTopologyPropagation(transit.nodeId(), snapshot,
                transitView, Map.of(
                        origin.nodeId(), frame -> receive(originRef.get(), transit.nodeId(), frame, now),
                        destination.nodeId(), frame -> {
                            destinationSends.incrementAndGet();
                            destinationBudget.set(frame.remainingHops());
                            return receive(destinationRef.get(), transit.nodeId(), frame, now);
                        }));
        OverlayTopologyPropagation destinationPropagation = new OverlayTopologyPropagation(destination.nodeId(),
                snapshot, destinationView,
                Map.of(transit.nodeId(), frame -> receive(transitRef.get(), destination.nodeId(), frame, now)));
        originRef.set(originPropagation);
        transitRef.set(transitPropagation);
        destinationRef.set(destinationPropagation);

        OverlayTopologyAdvertisement advertisement = OverlayTopologyAdvertisement.create(projectId,
                snapshot.revision(), 1, now.plusSeconds(60), List.of(transit.nodeId()), origin);
        originPropagation.publish(advertisement, now).toCompletableFuture().join();

        assertEquals(1, destinationSends.get());
        assertEquals(7, destinationBudget.get());
        assertEquals(OverlayTopologyView.Acceptance.DUPLICATE,
                transitView.accept(OverlayTopologyAdvertisement.decode(advertisement.encoded()), snapshot, now));
        assertTrue(originView.advertisement(origin.nodeId()).isPresent());
        assertTrue(transitView.advertisement(origin.nodeId()).isPresent());
        assertTrue(destinationView.advertisement(origin.nodeId()).isPresent());

        OverlayTopologyPropagationFrame frame = OverlayTopologyPropagationFrame.create(advertisement, 2);
        assertArrayEquals(frame.encoded(), OverlayTopologyPropagationFrame.decode(frame.encoded()).encoded());
        transitPropagation.receive(origin.nodeId(), frame, now).toCompletableFuture().join();
        assertEquals(1, destinationSends.get(), "duplicate advertisements must not reflood");
    }

    @Test
    void eightMemberOverlayRoutesOpaqueMessagesInBothDirections() throws Exception {
        List<NodeIdentity> members = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            members.add(NodeIdentity.generate());
        }
        UUID projectId = UUID.randomUUID();
        OverlayMembershipSnapshot snapshot = membership(projectId, members.get(0),
                members.subList(1, members.size()).toArray(NodeIdentity[]::new));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Map<String, List<String>> desired = new OverlayTopologyPolicy().desiredNeighbors(snapshot.members());
        List<NodeIdentity> ordered = members.stream().sorted(Comparator.comparing(NodeIdentity::nodeId)).toList();

        Map<String, OverlayTopologyAdvertisement> advertisements = new HashMap<>();
        for (NodeIdentity member : members) {
            OverlayTopologyAdvertisement advertisement = OverlayTopologyAdvertisement.create(projectId,
                    snapshot.revision(), 1, now.plusSeconds(300), desired.get(member.nodeId()), member);
            advertisements.put(member.nodeId(), advertisement);
        }
        Map<String, OverlayTopologyView> views = new HashMap<>();
        for (NodeIdentity member : members) {
            OverlayTopologyView view = new OverlayTopologyView();
            for (OverlayTopologyAdvertisement advertisement : advertisements.values()) {
                view.accept(advertisement, snapshot, now);
            }
            views.put(member.nodeId(), view);
        }

        Map<String, AtomicReference<OverlayForwarder>> forwarderRefs = new HashMap<>();
        Map<String, AtomicReference<OverlayForwardingFrame>> deliveries = new HashMap<>();
        for (NodeIdentity member : members) {
            forwarderRefs.put(member.nodeId(), new AtomicReference<>());
            deliveries.put(member.nodeId(), new AtomicReference<>());
        }
        Map<String, OverlayForwarder> forwarders = new HashMap<>();
        for (NodeIdentity member : members) {
            Map<String, OverlayForwarder.PeerTransport> peers = new HashMap<>();
            for (String neighbor : desired.get(member.nodeId())) {
                peers.put(neighbor, frame -> forwarderRefs.get(neighbor).get()
                        .forwardFrom(member.nodeId(), frame, now));
            }
            AtomicReference<OverlayForwardingFrame> delivery = deliveries.get(member.nodeId());
            OverlayForwarder forwarder = new OverlayForwarder(member.nodeId(), snapshot,
                    views.get(member.nodeId()), new OverlayDuplicateGuard(128), peers, frame -> {
                        delivery.set(frame);
                        return CompletableFuture.completedFuture(null);
                    });
            forwarderRefs.get(member.nodeId()).set(forwarder);
            forwarders.put(member.nodeId(), forwarder);
        }

        NodeIdentity origin = ordered.get(0);
        NodeIdentity destination = ordered.get(4);
        OverlayRoute route = OverlayRouteSelector.select(origin.nodeId(), destination.nodeId(), snapshot,
                Set.copyOf(desired.get(origin.nodeId())), views.get(origin.nodeId()), now, null);
        assertEquals(OverlayRoute.Kind.PEER_TRANSIT, route.kind());
        assertTrue(route.path().size() >= 2);

        OverlayKeyAgreement.Initiator agreement = OverlayKeyAgreement.start(projectId, destination.nodeId(),
                UUID.randomUUID(), snapshot.revision(), origin);
        OverlayKeyAgreement.Responder response = OverlayKeyAgreement.respond(agreement.init(), destination,
                snapshot);
        OverlayKeyAgreement.E2eSession sender = OverlayKeyAgreement.finish(agreement, response.response(), snapshot);
        OverlayKeyAgreement.E2eSession receiver = response.session();
        byte[] forwardPlaintext = "eight-member A-to-E route".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OverlayEnvelope forwardEnvelope = sender.encrypt(UUID.randomUUID(), forwardPlaintext);
        OverlayForwardingFrame forwardFrame = OverlayForwardingFrame.create(projectId, destination.nodeId(),
                forwardEnvelope.messageId(), route.path().size(), forwardEnvelope.encoded());
        forwarders.get(origin.nodeId()).forward(forwardFrame, now).toCompletableFuture().join();
        OverlayForwardingFrame deliveredForward = deliveries.get(destination.nodeId()).get();
        assertTrue(deliveredForward != null);
        assertEquals(0, deliveredForward.remainingHops());
        assertArrayEquals(forwardPlaintext, receiver.decrypt(OverlayEnvelope.decode(deliveredForward.innerRecord())));

        OverlayRoute reverseRoute = OverlayRouteSelector.select(destination.nodeId(), origin.nodeId(), snapshot,
                Set.copyOf(desired.get(destination.nodeId())), views.get(destination.nodeId()), now, null);
        assertEquals(OverlayRoute.Kind.PEER_TRANSIT, reverseRoute.kind());
        byte[] reversePlaintext = "eight-member E-to-A route".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OverlayEnvelope reverseEnvelope = receiver.encrypt(UUID.randomUUID(), reversePlaintext);
        OverlayForwardingFrame reverseFrame = OverlayForwardingFrame.create(projectId, origin.nodeId(),
                reverseEnvelope.messageId(), reverseRoute.path().size(), reverseEnvelope.encoded());
        forwarders.get(destination.nodeId()).forward(reverseFrame, now).toCompletableFuture().join();
        OverlayForwardingFrame deliveredReverse = deliveries.get(origin.nodeId()).get();
        assertTrue(deliveredReverse != null);
        assertEquals(0, deliveredReverse.remainingHops());
        assertArrayEquals(reversePlaintext, sender.decrypt(OverlayEnvelope.decode(deliveredReverse.innerRecord())));
        sender.close();
        receiver.close();
    }

    @Test
    void threePeerTransitCarriesKeyAgreementAndBidirectionalMessages() throws Exception {
        NodeIdentity authority = NodeIdentity.generate();
        NodeIdentity origin = NodeIdentity.generate();
        NodeIdentity transit = NodeIdentity.generate();
        NodeIdentity destination = NodeIdentity.generate();
        UUID projectId = UUID.randomUUID();
        OverlayMembershipSnapshot snapshot = membership(projectId, authority, origin, transit, destination);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        OverlayTopologyAdvertisement transitAdvertisement = OverlayTopologyAdvertisement.create(projectId,
                snapshot.revision(), 1, now.plusSeconds(60), List.of(origin.nodeId(), destination.nodeId()), transit);
        OverlayTopologyView originTopology = new OverlayTopologyView();
        OverlayTopologyView destinationTopology = new OverlayTopologyView();
        originTopology.accept(transitAdvertisement, snapshot, now);
        destinationTopology.accept(transitAdvertisement, snapshot, now);
        OverlayRoute forwardRoute = OverlayRouteSelector.select(origin.nodeId(), destination.nodeId(), snapshot,
                Set.of(transit.nodeId()), originTopology, now, null);
        OverlayRoute reverseRoute = OverlayRouteSelector.select(destination.nodeId(), origin.nodeId(), snapshot,
                Set.of(transit.nodeId()), destinationTopology, now, null);
        assertEquals(OverlayRoute.Kind.PEER_TRANSIT, forwardRoute.kind());
        assertEquals(List.of(transit.nodeId(), destination.nodeId()), forwardRoute.path());
        assertEquals(OverlayRoute.Kind.PEER_TRANSIT, reverseRoute.kind());
        assertEquals(List.of(transit.nodeId(), origin.nodeId()), reverseRoute.path());

        AtomicReference<OverlayForwarder> originRef = new AtomicReference<>();
        AtomicReference<OverlayForwarder> transitRef = new AtomicReference<>();
        AtomicReference<OverlayForwarder> destinationRef = new AtomicReference<>();
        AtomicReference<OverlayKeyAgreement.Initiator> initiatorRef = new AtomicReference<>();
        AtomicReference<OverlayKeyAgreement.E2eSession> originSessionRef = new AtomicReference<>();
        AtomicReference<OverlayKeyAgreement.E2eSession> destinationSessionRef = new AtomicReference<>();
        AtomicReference<OverlayForwardingFrame> originDelivery = new AtomicReference<>();
        AtomicReference<OverlayForwardingFrame> transitDelivery = new AtomicReference<>();
        AtomicReference<OverlayForwardingFrame> destinationDelivery = new AtomicReference<>();

        OverlayForwarder originForwarder = new OverlayForwarder(origin.nodeId(), snapshot, originTopology,
                new OverlayDuplicateGuard(64), Map.of(transit.nodeId(), frame ->
                        transitRef.get().forwardFrom(origin.nodeId(), frame, now)), frame -> {
                            try {
                                if (hasMagic(frame.innerRecord(), 0x534C4B31)) {
                                    OverlayKeyAgreement.ResponseRecord response =
                                            OverlayKeyAgreement.ResponseRecord.decode(frame.innerRecord());
                                    originSessionRef.set(OverlayKeyAgreement.finish(initiatorRef.get(), response,
                                            snapshot));
                                } else if (hasMagic(frame.innerRecord(), 0x534C4531)) {
                                    originDelivery.set(frame);
                                } else {
                                    return CompletableFuture.failedFuture(
                                            new IllegalStateException("origin received unknown logical record"));
                                }
                                return CompletableFuture.completedFuture(null);
                            } catch (java.io.IOException | GeneralSecurityException failure) {
                                return CompletableFuture.failedFuture(failure);
                            }
                        });
        OverlayForwarder transitForwarder = new OverlayForwarder(transit.nodeId(), snapshot,
                new OverlayTopologyView(), new OverlayDuplicateGuard(64), Map.of(
                        origin.nodeId(), frame -> originRef.get().forwardFrom(transit.nodeId(), frame, now),
                        destination.nodeId(), frame -> destinationRef.get().forwardFrom(transit.nodeId(), frame, now)),
                frame -> {
                    if (!hasMagic(frame.innerRecord(), 0x534C4531)) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "transit received unknown logical record"));
                    }
                    transitDelivery.set(frame);
                    return CompletableFuture.completedFuture(null);
                });
        OverlayForwarder destinationForwarder = new OverlayForwarder(destination.nodeId(), snapshot,
                destinationTopology, new OverlayDuplicateGuard(64), Map.of(transit.nodeId(), frame ->
                        transitRef.get().forwardFrom(destination.nodeId(), frame, now)), frame -> {
                            try {
                                if (hasMagic(frame.innerRecord(), 0x534C4B31)) {
                                    OverlayKeyAgreement.InitRecord init =
                                            OverlayKeyAgreement.InitRecord.decode(frame.innerRecord());
                                    OverlayKeyAgreement.Responder response =
                                            OverlayKeyAgreement.respond(init, destination, snapshot);
                                    destinationSessionRef.set(response.session());
                                    OverlayForwardingFrame responseFrame = OverlayForwardingFrame.create(projectId,
                                            origin.nodeId(), response.response().sessionId(), reverseRoute.path().size(),
                                            response.response().encoded());
                                    return destinationRef.get().forward(responseFrame, now);
                                }
                                if (!hasMagic(frame.innerRecord(), 0x534C4531)) {
                                    return CompletableFuture.failedFuture(new IllegalStateException(
                                            "destination received unknown logical record"));
                                }
                                destinationDelivery.set(frame);
                                return CompletableFuture.completedFuture(null);
                            } catch (java.io.IOException | GeneralSecurityException failure) {
                                return CompletableFuture.failedFuture(failure);
                            }
                        });
        originRef.set(originForwarder);
        transitRef.set(transitForwarder);
        destinationRef.set(destinationForwarder);

        OverlayKeyAgreement.Initiator initiator = OverlayKeyAgreement.start(projectId, destination.nodeId(),
                UUID.randomUUID(), snapshot.revision(), origin);
        initiatorRef.set(initiator);
        OverlayForwardingFrame initFrame = OverlayForwardingFrame.create(projectId, destination.nodeId(),
                initiator.init().sessionId(), forwardRoute.path().size(), initiator.init().encoded());
        originForwarder.forward(initFrame, now).toCompletableFuture().join();
        assertTrue(originSessionRef.get() != null);
        assertTrue(destinationSessionRef.get() != null);

        OverlayKeyAgreement.E2eSession originSession = originSessionRef.get();
        OverlayKeyAgreement.E2eSession destinationSession = destinationSessionRef.get();
        byte[] forwardPlaintext = "transit-handshaken A-to-C".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OverlayEnvelope forwardEnvelope = originSession.encrypt(UUID.randomUUID(), forwardPlaintext);
        originForwarder.forward(OverlayForwardingFrame.create(projectId, destination.nodeId(),
                forwardEnvelope.messageId(), forwardRoute.path().size(), forwardEnvelope.encoded()), now)
                .toCompletableFuture().join();
        assertArrayEquals(forwardPlaintext,
                destinationSession.decrypt(OverlayEnvelope.decode(destinationDelivery.get().innerRecord())));

        byte[] reversePlaintext = "transit-handshaken C-to-A".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OverlayEnvelope reverseEnvelope = destinationSession.encrypt(UUID.randomUUID(), reversePlaintext);
        destinationForwarder.forward(OverlayForwardingFrame.create(projectId, origin.nodeId(),
                reverseEnvelope.messageId(), reverseRoute.path().size(), reverseEnvelope.encoded()), now)
                .toCompletableFuture().join();
        assertArrayEquals(reversePlaintext,
                originSession.decrypt(OverlayEnvelope.decode(originDelivery.get().innerRecord())));

        OverlayKeyAgreement.Initiator transitAgreement = OverlayKeyAgreement.start(projectId,
                destination.nodeId(), UUID.randomUUID(), snapshot.revision(), transit);
        OverlayKeyAgreement.Responder transitResponse = OverlayKeyAgreement.respond(transitAgreement.init(),
                destination, snapshot);
        OverlayKeyAgreement.E2eSession transitSession = OverlayKeyAgreement.finish(transitAgreement,
                transitResponse.response(), snapshot);
        assertThrows(GeneralSecurityException.class,
                () -> transitSession.decrypt(OverlayEnvelope.decode(destinationDelivery.get().innerRecord())));

        OverlayRoute directTransitToDestination = OverlayRouteSelector.select(transit.nodeId(),
                destination.nodeId(), snapshot, Set.of(destination.nodeId()), new OverlayTopologyView(), now, null);
        assertEquals(OverlayRoute.Kind.DIRECT, directTransitToDestination.kind());
        OverlayKeyAgreement.Initiator directForwardAgreement = OverlayKeyAgreement.start(projectId,
                destination.nodeId(), UUID.randomUUID(), snapshot.revision(), transit);
        OverlayKeyAgreement.Responder directForwardResponse = OverlayKeyAgreement.respond(
                directForwardAgreement.init(), destination, snapshot);
        OverlayKeyAgreement.E2eSession directTransitSession = OverlayKeyAgreement.finish(directForwardAgreement,
                directForwardResponse.response(), snapshot);
        OverlayKeyAgreement.E2eSession directDestinationSession = directForwardResponse.session();
        transitDelivery.set(null);
        byte[] directForwardPlaintext = "direct B-to-C".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OverlayEnvelope directForwardEnvelope = directTransitSession.encrypt(UUID.randomUUID(),
                directForwardPlaintext);
        transitForwarder.forward(OverlayForwardingFrame.create(projectId, destination.nodeId(),
                directForwardEnvelope.messageId(), 1, directForwardEnvelope.encoded()), now)
                .toCompletableFuture().join();
        assertArrayEquals(directForwardPlaintext,
                directDestinationSession.decrypt(OverlayEnvelope.decode(destinationDelivery.get().innerRecord())));
        byte[] directReversePlaintext = "direct C-to-B".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OverlayEnvelope directReverseEnvelope = directDestinationSession.encrypt(UUID.randomUUID(),
                directReversePlaintext);
        destinationForwarder.forward(OverlayForwardingFrame.create(projectId, transit.nodeId(),
                directReverseEnvelope.messageId(), 1, directReverseEnvelope.encoded()), now)
                .toCompletableFuture().join();
        assertArrayEquals(directReversePlaintext,
                directTransitSession.decrypt(OverlayEnvelope.decode(transitDelivery.get().innerRecord())));

        OverlayRoute directTransitToOrigin = OverlayRouteSelector.select(transit.nodeId(), origin.nodeId(), snapshot,
                Set.of(origin.nodeId()), new OverlayTopologyView(), now, null);
        assertEquals(OverlayRoute.Kind.DIRECT, directTransitToOrigin.kind());
        OverlayKeyAgreement.Initiator directBackAgreement = OverlayKeyAgreement.start(projectId, origin.nodeId(),
                UUID.randomUUID(), snapshot.revision(), transit);
        OverlayKeyAgreement.Responder directBackResponse = OverlayKeyAgreement.respond(directBackAgreement.init(),
                origin, snapshot);
        OverlayKeyAgreement.E2eSession directTransitBackSession = OverlayKeyAgreement.finish(directBackAgreement,
                directBackResponse.response(), snapshot);
        OverlayKeyAgreement.E2eSession directOriginSession = directBackResponse.session();
        originDelivery.set(null);
        transitDelivery.set(null);
        byte[] directBackPlaintext = "direct B-to-A".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OverlayEnvelope directBackEnvelope = directTransitBackSession.encrypt(UUID.randomUUID(), directBackPlaintext);
        transitForwarder.forward(OverlayForwardingFrame.create(projectId, origin.nodeId(),
                directBackEnvelope.messageId(), 1, directBackEnvelope.encoded()), now)
                .toCompletableFuture().join();
        assertArrayEquals(directBackPlaintext,
                directOriginSession.decrypt(OverlayEnvelope.decode(originDelivery.get().innerRecord())));
        byte[] directBackReversePlaintext = "direct A-to-B".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OverlayEnvelope directBackReverseEnvelope = directOriginSession.encrypt(UUID.randomUUID(),
                directBackReversePlaintext);
        originForwarder.forward(OverlayForwardingFrame.create(projectId, transit.nodeId(),
                directBackReverseEnvelope.messageId(), 1, directBackReverseEnvelope.encoded()), now)
                .toCompletableFuture().join();
        assertArrayEquals(directBackReversePlaintext,
                directTransitBackSession.decrypt(OverlayEnvelope.decode(transitDelivery.get().innerRecord())));
        originSession.close();
        destinationSession.close();
        transitSession.close();
        directTransitSession.close();
        directDestinationSession.close();
        directTransitBackSession.close();
        directOriginSession.close();
    }

    @Test
    void peerForwarderKeepsEnvelopeOpaqueAndSuppressesDuplicates() throws Exception {
        NodeIdentity authority = NodeIdentity.generate();
        NodeIdentity transit = NodeIdentity.generate();
        NodeIdentity destination = NodeIdentity.generate();
        UUID projectId = UUID.randomUUID();
        OverlayMembershipSnapshot snapshot = membership(projectId, authority, transit, destination);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        OverlayTopologyView authorityTopology = new OverlayTopologyView();
        authorityTopology.accept(OverlayTopologyAdvertisement.create(projectId, snapshot.revision(), 1,
                now.plusSeconds(60), List.of(destination.nodeId()), transit), snapshot, now);
        OverlayKeyAgreement.Initiator originAgreement = OverlayKeyAgreement.start(projectId, destination.nodeId(),
                UUID.randomUUID(), snapshot.revision(), authority);
        OverlayKeyAgreement.InitRecord init = OverlayKeyAgreement.InitRecord.decode(
                originAgreement.init().encoded());
        OverlayKeyAgreement.Responder destinationAgreement = OverlayKeyAgreement.respond(init, destination, snapshot);
        OverlayKeyAgreement.E2eSession originSession = OverlayKeyAgreement.finish(originAgreement,
                OverlayKeyAgreement.ResponseRecord.decode(destinationAgreement.response().encoded()), snapshot);
        OverlayKeyAgreement.E2eSession destinationSession = destinationAgreement.session();
        byte[] plaintext = "only A and C can read this".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OverlayEnvelope envelope = originSession.encrypt(UUID.randomUUID(), plaintext);
        AtomicReference<OverlayForwardingFrame> delivered = new AtomicReference<>();

        OverlayForwarder destinationForwarder = new OverlayForwarder(destination.nodeId(), snapshot,
                new OverlayTopologyView(), new OverlayDuplicateGuard(32), Map.of(), frame -> {
                    delivered.set(frame);
                    return CompletableFuture.completedFuture(null);
                });
        AtomicReference<OverlayForwarder> destinationRef = new AtomicReference<>(destinationForwarder);
        OverlayForwarder transitForwarder = new OverlayForwarder(transit.nodeId(), snapshot,
                new OverlayTopologyView(), new OverlayDuplicateGuard(32),
                Map.of(destination.nodeId(), frame -> destinationRef.get().forward(frame, now)), frame ->
                        CompletableFuture.failedFuture(new IllegalStateException("transit cannot deliver")));
        AtomicReference<OverlayForwarder> transitRef = new AtomicReference<>(transitForwarder);
        OverlayForwarder authorityForwarder = new OverlayForwarder(authority.nodeId(), snapshot,
                authorityTopology, new OverlayDuplicateGuard(32),
                Map.of(transit.nodeId(), frame -> transitRef.get().forward(frame, now)), frame ->
                        CompletableFuture.failedFuture(new IllegalStateException("origin is not destination")));

        OverlayForwardingFrame frame = OverlayForwardingFrame.create(projectId, destination.nodeId(),
                envelope.messageId(), 2, envelope.encoded());
        authorityForwarder.forward(frame, now).toCompletableFuture().join();
        OverlayForwardingFrame finalFrame = delivered.get();
        assertTrue(finalFrame != null);
        assertEquals(0, finalFrame.remainingHops());
        OverlayEnvelope deliveredEnvelope = OverlayEnvelope.decode(finalFrame.innerRecord());
        assertArrayEquals(plaintext, destinationSession.decrypt(deliveredEnvelope));

        OverlayKeyAgreement.Initiator transitAgreement = OverlayKeyAgreement.start(projectId,
                destination.nodeId(), UUID.randomUUID(), snapshot.revision(), transit);
        OverlayKeyAgreement.Responder transitResponse = OverlayKeyAgreement.respond(
                transitAgreement.init(), destination, snapshot);
        OverlayKeyAgreement.E2eSession transitSession = OverlayKeyAgreement.finish(transitAgreement,
                transitResponse.response(), snapshot);
        assertThrows(GeneralSecurityException.class, () -> transitSession.decrypt(deliveredEnvelope));
        assertThrows(CompletionException.class,
                () -> destinationForwarder.forward(finalFrame, now).toCompletableFuture().join());

        OverlayForwardingFrame tooShort = OverlayForwardingFrame.create(projectId, destination.nodeId(),
                UUID.randomUUID(), 1, envelope.encoded());
        CompletionException failure = assertThrows(CompletionException.class,
                () -> authorityForwarder.forward(tooShort, now).toCompletableFuture().join());
        assertEquals(OverlayForwardingException.Failure.HOP_LIMIT_EXCEEDED,
                ((OverlayForwardingException) failure.getCause()).failure());
        originSession.close();
        destinationSession.close();
        transitSession.close();
    }

    @Test
    void relayAuthorizesLiveNodesForwardsOpaqueFramesAndHasNoOfflineRoute() throws Exception {
        NodeIdentity authority = NodeIdentity.generate();
        NodeIdentity origin = NodeIdentity.generate();
        NodeIdentity transit = NodeIdentity.generate();
        NodeIdentity destination = NodeIdentity.generate();
        NodeIdentity unauthorized = NodeIdentity.generate();
        UUID projectId = UUID.randomUUID();
        OverlayMembershipSnapshot snapshot = membership(projectId, authority, origin, transit, destination,
                unauthorized);
        OverlayRelayPolicy policy = new OverlayRelayPolicy(Map.of(projectId,
                Set.of(authority.nodeId(), origin.nodeId(), transit.nodeId(), destination.nodeId())), 8, 8, 8);
        OverlayRelay relay = new OverlayRelay(policy);
        AtomicReference<OverlayForwardingFrame> delivered = new AtomicReference<>();
        OverlayRelay.Registration destinationRegistration = relay.register(destination,
                frame -> {
                    delivered.set(frame);
                    return CompletableFuture.completedFuture(null);
                });
        OverlayRelay.Registration originRegistration = relay.register(origin,
                frame -> CompletableFuture.completedFuture(null));
        assertEquals(2, relay.connectionCount());
        assertThrows(GeneralSecurityException.class,
                () -> relay.register(unauthorized, frame -> CompletableFuture.completedFuture(null)));

        OverlayKeyAgreement.Initiator originAgreement = OverlayKeyAgreement.start(projectId, destination.nodeId(),
                UUID.randomUUID(), snapshot.revision(), origin);
        OverlayKeyAgreement.Responder destinationAgreement = OverlayKeyAgreement.respond(originAgreement.init(),
                destination, snapshot);
        OverlayKeyAgreement.E2eSession originSession = OverlayKeyAgreement.finish(originAgreement,
                destinationAgreement.response(), snapshot);
        OverlayKeyAgreement.E2eSession destinationSession = destinationAgreement.session();
        byte[] plaintext = "relay keeps this opaque".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OverlayEnvelope envelope = originSession.encrypt(UUID.randomUUID(), plaintext);
        OverlayForwardingFrame frame = OverlayForwardingFrame.create(projectId, destination.nodeId(),
                envelope.messageId(), 2, envelope.encoded());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        originRegistration.send(frame, now).toCompletableFuture().join();
        OverlayForwardingFrame deliveredFrame = delivered.get();
        assertTrue(deliveredFrame != null);
        assertFalse(Arrays.equals(plaintext, deliveredFrame.innerRecord()));
        assertArrayEquals(plaintext, destinationSession.decrypt(OverlayEnvelope.decode(deliveredFrame.innerRecord())));
        CompletionException duplicate = assertThrows(CompletionException.class,
                () -> originRegistration.send(frame, now).toCompletableFuture().join());
        assertEquals(OverlayRelayException.Failure.DUPLICATE,
                ((OverlayRelayException) duplicate.getCause()).failure());

        OverlayKeyAgreement.Initiator transitAgreement = OverlayKeyAgreement.start(projectId,
                destination.nodeId(), UUID.randomUUID(), snapshot.revision(), transit);
        OverlayKeyAgreement.Responder transitResponse = OverlayKeyAgreement.respond(transitAgreement.init(),
                destination, snapshot);
        OverlayKeyAgreement.E2eSession transitSession = OverlayKeyAgreement.finish(transitAgreement,
                transitResponse.response(), snapshot);
        assertThrows(GeneralSecurityException.class,
                () -> transitSession.decrypt(OverlayEnvelope.decode(deliveredFrame.innerRecord())));

        destinationRegistration.close();
        CompletionException noRoute = assertThrows(CompletionException.class,
                () -> originRegistration.send(OverlayForwardingFrame.create(projectId, destination.nodeId(),
                        UUID.randomUUID(), 2, envelope.encoded()), now).toCompletableFuture().join());
        assertEquals(OverlayRelayException.Failure.NO_ROUTE,
                ((OverlayRelayException) noRoute.getCause()).failure());
        originRegistration.close();
        originSession.close();
        destinationSession.close();
        transitSession.close();
    }

    @Test
    void forwarderUsesConfiguredRelayFallbackForOpaqueDelivery() throws Exception {
        NodeIdentity authority = NodeIdentity.generate();
        NodeIdentity origin = NodeIdentity.generate();
        NodeIdentity destination = NodeIdentity.generate();
        NodeIdentity relayIdentity = NodeIdentity.generate();
        UUID projectId = UUID.randomUUID();
        OverlayMembershipSnapshot snapshot = membership(projectId, authority, origin, destination);
        OverlayRelay relay = new OverlayRelay(new OverlayRelayPolicy(Map.of(projectId,
                Set.of(origin.nodeId(), destination.nodeId()))));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AtomicReference<OverlayForwardingFrame> delivered = new AtomicReference<>();
        OverlayForwarder destinationForwarder = new OverlayForwarder(destination.nodeId(), snapshot,
                new OverlayTopologyView(), new OverlayDuplicateGuard(32), Map.of(), frame -> {
                    delivered.set(frame);
                    return CompletableFuture.completedFuture(null);
                }, relayIdentity.nodeId(), frame -> CompletableFuture.completedFuture(null));
        AtomicReference<OverlayForwarder> destinationRef = new AtomicReference<>(destinationForwarder);
        OverlayRelay.Registration destinationRegistration = relay.register(destination,
                frame -> destinationRef.get().forwardFrom(relayIdentity.nodeId(), frame, now));
        AtomicReference<OverlayRelay.Registration> originRegistration = new AtomicReference<>();
        OverlayRelay.Registration registration = relay.register(origin,
                frame -> CompletableFuture.completedFuture(null));
        originRegistration.set(registration);
        OverlayForwarder originForwarder = new OverlayForwarder(origin.nodeId(), snapshot,
                new OverlayTopologyView(), new OverlayDuplicateGuard(32), Map.of(), frame ->
                        CompletableFuture.failedFuture(new IllegalStateException("origin is not destination")),
                relayIdentity.nodeId(), frame -> originRegistration.get().send(frame, now));

        OverlayKeyAgreement.Initiator agreement = OverlayKeyAgreement.start(projectId, destination.nodeId(),
                UUID.randomUUID(), snapshot.revision(), origin);
        OverlayKeyAgreement.Responder response = OverlayKeyAgreement.respond(agreement.init(), destination,
                snapshot);
        OverlayKeyAgreement.E2eSession sender = OverlayKeyAgreement.finish(agreement, response.response(), snapshot);
        OverlayKeyAgreement.E2eSession receiver = response.session();
        byte[] plaintext = "relay fallback payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OverlayEnvelope envelope = sender.encrypt(UUID.randomUUID(), plaintext);
        OverlayForwardingFrame frame = OverlayForwardingFrame.create(projectId, destination.nodeId(),
                envelope.messageId(), 2, envelope.encoded());
        originForwarder.forward(frame, now).toCompletableFuture().join();
        assertEquals(0, delivered.get().remainingHops());
        assertArrayEquals(plaintext, receiver.decrypt(OverlayEnvelope.decode(delivered.get().innerRecord())));
        destinationRegistration.close();
        originRegistration.get().close();
        sender.close();
        receiver.close();
    }

    @Test
    void relayConnectionAndQueueBoundsFailClosedWithoutPoisoningRetry() throws Exception {
        NodeIdentity origin = NodeIdentity.generate();
        NodeIdentity destination = NodeIdentity.generate();
        UUID projectId = UUID.randomUUID();
        OverlayRelayPolicy policy = new OverlayRelayPolicy(Map.of(projectId,
                Set.of(origin.nodeId(), destination.nodeId())), 1, 1, 8);
        OverlayRelay limited = new OverlayRelay(policy);
        OverlayRelay.Registration only = limited.register(origin,
                frame -> CompletableFuture.completedFuture(null));
        OverlayRelayException connectionLimit = assertThrows(OverlayRelayException.class,
                () -> limited.register(destination, frame -> CompletableFuture.completedFuture(null)));
        assertEquals(OverlayRelayException.Failure.CONNECTION_LIMIT, connectionLimit.failure());
        only.close();

        OverlayRelay relay = new OverlayRelay(new OverlayRelayPolicy(Map.of(projectId,
                Set.of(origin.nodeId(), destination.nodeId())), 2, 1, 8));
        CompletableFuture<Void> firstDelivery = new CompletableFuture<>();
        OverlayRelay.Registration destinationRegistration = relay.register(destination,
                frame -> firstDelivery);
        OverlayRelay.Registration originRegistration = relay.register(origin,
                frame -> CompletableFuture.completedFuture(null));
        byte[] logicalRecord = OverlayKeyAgreement.start(projectId, destination.nodeId(), UUID.randomUUID(), 1,
                origin).init().encoded();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        OverlayForwardingFrame firstFrame = OverlayForwardingFrame.create(projectId, destination.nodeId(),
                UUID.randomUUID(), 1, logicalRecord);
        OverlayForwardingFrame secondFrame = OverlayForwardingFrame.create(projectId, destination.nodeId(),
                UUID.randomUUID(), 1, logicalRecord);
        CompletionStage<Void> firstCompletion = originRegistration.send(firstFrame, now);
        CompletionException queueFailure = assertThrows(CompletionException.class,
                () -> originRegistration.send(secondFrame, now).toCompletableFuture().join());
        assertEquals(OverlayRelayException.Failure.QUEUE_FULL,
                ((OverlayRelayException) queueFailure.getCause()).failure());
        firstDelivery.complete(null);
        firstCompletion.toCompletableFuture().join();
        originRegistration.send(secondFrame, now).toCompletableFuture().join();
        destinationRegistration.close();
        originRegistration.close();
    }

    private static OverlayMembershipSnapshot membership(UUID projectId, NodeIdentity authority,
            NodeIdentity... additionalMembers) throws GeneralSecurityException {
        return membership(projectId, 1, authority, additionalMembers);
    }

    private static OverlayMembershipSnapshot membership(UUID projectId, long revision, NodeIdentity authority,
            NodeIdentity... additionalMembers) throws GeneralSecurityException {
        List<OverlayMembershipSnapshot.Member> members = new java.util.ArrayList<>();
        members.add(new OverlayMembershipSnapshot.Member(authority.nodeId(), OverlayMembershipSnapshot.STATUS_ACTIVE,
                authority.publicKeyEncoded()));
        Arrays.stream(additionalMembers).map(identity -> new OverlayMembershipSnapshot.Member(identity.nodeId(),
                OverlayMembershipSnapshot.STATUS_ACTIVE, identity.publicKeyEncoded())).forEach(members::add);
        Instant issuedAt = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        return OverlayMembershipSnapshot.create(projectId, revision, issuedAt, issuedAt.plusSeconds(300), members,
                authority);
    }

    private static CompletionStage<Void> receive(OverlayTopologyPropagation propagation, String senderNodeId,
            OverlayTopologyPropagationFrame frame, Instant now) {
        try {
            return propagation.receive(senderNodeId, frame, now);
        } catch (GeneralSecurityException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static CompletionStage<Void> receive(OverlayMembershipPropagation propagation, String senderNodeId,
            OverlayMembershipPropagationFrame frame, Instant now) {
        try {
            return propagation.receive(senderNodeId, frame, now);
        } catch (GeneralSecurityException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static boolean hasMagic(byte[] bytes, int magic) {
        return bytes.length >= Integer.BYTES
                && ((bytes[0] & 0xff) << 24 | (bytes[1] & 0xff) << 16 | (bytes[2] & 0xff) << 8
                        | (bytes[3] & 0xff)) == magic;
    }

    private static boolean isConnected(Map<String, List<String>> graph) {
        String start = graph.keySet().iterator().next();
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            graph.getOrDefault(current, List.of()).forEach(pending::addLast);
        }
        return seen.size() == graph.size();
    }

}
