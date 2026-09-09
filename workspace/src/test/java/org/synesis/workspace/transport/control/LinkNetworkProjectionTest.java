package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayMembershipSnapshot;
import org.synesis.link.overlay.OverlayMembershipView;
import org.synesis.link.overlay.OverlayTopologyAdvertisement;
import org.synesis.link.overlay.OverlayTopologyView;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.session.HandshakeRole;
import org.synesis.link.session.HandshakeTranscript;
import org.synesis.link.session.LivenessListener;
import org.synesis.link.session.LivenessMetrics;
import org.synesis.link.session.LivenessState;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;
import org.synesis.link.session.SessionAuthenticator;
import org.synesis.link.session.SessionCloseReason;

/**
 * Proves that the control-plane network adapter consumes real Link overlay objects rather than
 * accepting only pre-shaped HTTP DTOs.
 */
class LinkNetworkProjectionTest {

  private static OverlayMembershipSnapshot.Member member(NodeIdentity identity) {
    return new OverlayMembershipSnapshot.Member(identity.nodeId(),
        OverlayMembershipSnapshot.STATUS_ACTIVE,
        identity.publicKeyEncoded());
  }

  private static PeerSession authenticatedLiveSession(NodeIdentity local, NodeIdentity remote,
      Instant now)
      throws Exception {
    HandshakeTranscript transcript = HandshakeTranscript.forIdentities(ProtocolVersion.V1,
        UUID.randomUUID(),
        1, 1, new byte[]{1}, new byte[]{2}, local, remote);
    HandshakeProof localProof = SessionAuthenticator.createProof(local, transcript,
        HandshakeRole.INITIATOR);
    HandshakeProof remoteProof = SessionAuthenticator.createProof(remote, transcript,
        HandshakeRole.RESPONDER);
    PeerSession session = SessionAuthenticator.establish(local, remote.nodeId(), transcript,
        localProof,
        remoteProof, new ReplayGuard(), now);
    session.attachControl(new PeerSession.ControlBinding() {
      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public CompletionStage<Void> closeGracefully(SessionCloseReason reason) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletionStage<Void> terminalCompletion() {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public SessionCloseReason closeReason() {
        return null;
      }

      @Override
      public LivenessState livenessState() {
        return LivenessState.LIVE;
      }

      @Override
      public LivenessMetrics livenessMetrics() {
        return new LivenessMetrics(0, 0, 0, 0, 0, Duration.ZERO, Duration.ZERO,
            0, 0, 0, 0, 0);
      }

      @Override
      public void addLivenessListener(LivenessListener listener) {
      }

      @Override
      public void removeLivenessListener(LivenessListener listener) {
      }
    });
    return session;
  }

  @Test
  void mapsAuthenticatedPeerMembershipTopologyAndSelectedRoutes() throws Exception {
    NodeIdentity local = NodeIdentity.generate();
    NodeIdentity transit = NodeIdentity.generate();
    NodeIdentity destination = NodeIdentity.generate();
    UUID projectId = UUID.randomUUID();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    OverlayMembershipSnapshot membership = OverlayMembershipSnapshot.create(projectId, 4,
        now.minusSeconds(1), now.plusSeconds(300), List.of(
            member(local), member(transit), member(destination)), local);
    OverlayTopologyView topology = new OverlayTopologyView();
    topology.accept(OverlayTopologyAdvertisement.create(projectId, membership.revision(), 1,
        now.plusSeconds(60), List.of(destination.nodeId()), transit), membership, now);
    PeerSession session = authenticatedLiveSession(local, transit, now);

    LinkNetworkProjection projection = new LinkNetworkProjection(
        () -> new LinkNetworkProjection.LinkState(
            local.nodeId(), List.of(session), Optional.of(new OverlayMembershipView(membership)),
            Optional.of(topology), Set.of(transit.nodeId()),
            new LinkNetworkProjection.RelayState("CONNECTED", "127.0.0.1:48124", 2,
                true, true, "relay-node", true, 0)));

    ControlPlaneReadModel.NetworkSnapshot result = projection.get();

    assertEquals("HEALTHY", result.status());
    assertEquals(List.of(transit.nodeId()), result.peers().stream()
        .map(ControlPlaneReadModel.NetworkPeer::nodeId).toList());
    assertEquals("DIRECT", result.overlay().directEdges().getFirst().status());
    assertTrue(result.overlay().desiredEdges().stream()
        .anyMatch(edge -> edge.fromNodeId().equals(local.nodeId())));
    ControlPlaneReadModel.NetworkRoute route = result.routes().stream()
        .filter(value -> value.destinationNodeId().equals(destination.nodeId()))
        .findFirst().orElseThrow();
    assertEquals("PEER_TRANSIT", route.kind());
    assertEquals(List.of(transit.nodeId(), destination.nodeId()), route.path());
    assertEquals("relay-node", result.relay().relayIdentity());
  }

  @Test
  void fallsBackToRelayWhenNoDirectOrAdvertisedPeerRouteExists() throws Exception {
    NodeIdentity local = NodeIdentity.generate();
    NodeIdentity destination = NodeIdentity.generate();
    UUID projectId = UUID.randomUUID();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    OverlayMembershipSnapshot membership = OverlayMembershipSnapshot.create(projectId, 1,
        now.minusSeconds(1), now.plusSeconds(300), List.of(member(local), member(destination)),
        local);
    LinkNetworkProjection projection = new LinkNetworkProjection(
        () -> new LinkNetworkProjection.LinkState(
            local.nodeId(), List.of(), Optional.of(new OverlayMembershipView(membership)),
            Optional.of(new OverlayTopologyView()), Set.of(),
            new LinkNetworkProjection.RelayState("CONNECTED", "127.0.0.1:48124", 1,
                true, true, "relay-node", true, 1)));

    ControlPlaneReadModel.NetworkRoute route = projection.get().routes().stream()
        .filter(value -> value.destinationNodeId().equals(destination.nodeId()))
        .findFirst().orElseThrow();

    assertEquals("ORGANIZATION_RELAY", route.kind());
    assertEquals("relay-node", route.relayNodeId());
  }
}
