package org.synesis.workspace.transport.control;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.synesis.link.overlay.OverlayMembershipSnapshot;
import org.synesis.link.overlay.OverlayMembershipView;
import org.synesis.link.overlay.OverlayRoute;
import org.synesis.link.overlay.OverlayRouteSelector;
import org.synesis.link.overlay.OverlayTopologyPolicy;
import org.synesis.link.overlay.OverlayTopologyView;
import org.synesis.link.session.LivenessState;
import org.synesis.link.session.PeerSession;

/**
 * Adapts authoritative Link and overlay objects into the public-safe control plane network read
 * model.
 *
 * <p>This class owns no Link session, membership, topology, route, or relay
 * lifecycle. A long-lived runtime supplies one bounded, thread-safe {@link LinkState} snapshot
 * through the source function. The adapter only applies the existing overlay route selector and
 * copies safe fields into control-plane DTOs; it never derives membership from an invitation, an
 * address, or an unsigned peer claim.</p>
 *
 * <p>The source is evaluated once per {@link #get()} call so one HTTP snapshot
 * cannot mix two runtime revisions. The source must not return mutable collections that it mutates
 * after returning.</p>
 *
 * @since 1.0
 */
public final class LinkNetworkProjection implements
    Supplier<ControlPlaneReadModel.NetworkSnapshot> {

  private static final int MAX_ITEMS = 256;

  private final Supplier<LinkState> source;
  private final Clock clock;
  private final OverlayTopologyPolicy topologyPolicy;

  /**
   * Creates a projection with the default topology policy and UTC clock.
   *
   * @param source authoritative Link/overlay state source
   */
  public LinkNetworkProjection(Supplier<LinkState> source) {
    this(source, Clock.systemUTC(), new OverlayTopologyPolicy());
  }

  /**
   * Creates an injectable projection for runtime and deterministic tests.
   *
   * @param source         authoritative Link/overlay state source
   * @param clock          time source used for membership and topology expiry
   * @param topologyPolicy desired-edge policy used for the public graph view
   */
  public LinkNetworkProjection(Supplier<LinkState> source, Clock clock,
      OverlayTopologyPolicy topologyPolicy) {
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.topologyPolicy = Objects.requireNonNull(topologyPolicy, "topology policy");
  }

  private static List<ControlPlaneReadModel.NetworkPeer> peers(List<PeerSession> sessions) {
    return sessions.stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(PeerSession::remoteNodeId))
        .limit(MAX_ITEMS)
        .map(LinkNetworkProjection::peer)
        .toList();
  }

  private static ControlPlaneReadModel.NetworkPeer peer(PeerSession session) {
    LivenessState liveness = session.livenessState();
    String health = switch (liveness) {
      case LIVE -> "HEALTHY";
      case SUSPECT -> "DEGRADED";
      case CONNECTING -> "CONNECTING";
      default -> "UNAVAILABLE";
    };
    return new ControlPlaneReadModel.NetworkPeer(session.remoteNodeId(),
        session.sessionId().toString(),
        liveness.name(), session.isUsable(), session.establishedAt().toString(), true, health);
  }

  private static List<ControlPlaneReadModel.OverlayEdge> directEdges(String localNodeId,
      Set<String> directPeerIds, OverlayMembershipSnapshot membership,
      List<ControlPlaneReadModel.NetworkPeer> peers) {
    Set<String> healthyPeers = peers.stream()
        .filter(ControlPlaneReadModel.NetworkPeer::usable)
        .map(ControlPlaneReadModel.NetworkPeer::nodeId)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    return directPeerIds.stream()
        .sorted()
        .limit(MAX_ITEMS)
        .map(nodeId -> new ControlPlaneReadModel.OverlayEdge(
            localNodeId, nodeId,
            !membership.allows(nodeId) ? "UNAUTHORIZED"
                : healthyPeers.contains(nodeId) ? "DIRECT" : "UNAVAILABLE"))
        .toList();
  }

  private static List<ControlPlaneReadModel.NetworkRoute> routes(LinkState state,
      OverlayMembershipSnapshot membership, OverlayTopologyView topology, Instant now,
      ControlPlaneReadModel.RelaySummary relay) {
    String relayNodeId = relay.connected() && relay.authorized() && !relay.relayIdentity().isBlank()
        ? relay.relayIdentity() : null;
    List<ControlPlaneReadModel.NetworkRoute> routes = new ArrayList<>();
    membership.members().stream()
        .filter(member -> member.status() == OverlayMembershipSnapshot.STATUS_ACTIVE)
        .map(OverlayMembershipSnapshot.Member::nodeId)
        .filter(nodeId -> !nodeId.equals(state.localNodeId()))
        .sorted()
        .limit(MAX_ITEMS)
        .forEach(destination -> {
          OverlayRoute route;
          try {
            route = OverlayRouteSelector.select(state.localNodeId(), destination, membership,
                state.directPeerIds(), topology, now, relayNodeId);
          } catch (GeneralSecurityException invalidState) {
            route = OverlayRoute.unreachable(destination);
          }
          routes.add(new ControlPlaneReadModel.NetworkRoute(route.kind().name(),
              route.destinationNodeId(), route.path(), route.nextHop().orElse(""),
              route.relayNodeId().orElse("")));
        });
    return List.copyOf(routes);
  }

  /**
   * Builds one bounded, safe network snapshot from the current Link state.
   *
   * @return control-plane network snapshot; malformed or unavailable optional overlay state is
   * reported as a degraded/unconfigured view rather than being fabricated as healthy
   */
  @Override
  public ControlPlaneReadModel.NetworkSnapshot get() {
    LinkState state = Objects.requireNonNull(source.get(), "Link state source returned null");
    Instant now = clock.instant();
    List<ControlPlaneReadModel.NetworkPeer> peers = peers(state.sessions());
    ControlPlaneReadModel.RelaySummary relay = state.relay().toSummary();
    Optional<OverlayMembershipSnapshot> membership = state.membership()
        .map(OverlayMembershipView::current);
    if (membership.isEmpty()) {
      return new ControlPlaneReadModel.NetworkSnapshot(
          peers.isEmpty() ? "UNCONFIGURED" : "DEGRADED", peers, List.of(),
          new ControlPlaneReadModel.OverlaySummary("UNCONFIGURED", "", 0, 0, ""), relay);
    }

    OverlayMembershipSnapshot current = membership.orElseThrow();
    boolean usable = current.isUsableAt(now) && current.allows(state.localNodeId());
    List<ControlPlaneReadModel.OverlayMember> members = current.members().stream()
        .map(member -> new ControlPlaneReadModel.OverlayMember(member.nodeId(),
            member.status() == OverlayMembershipSnapshot.STATUS_ACTIVE ? "ACTIVE" : "REVOKED"))
        .limit(MAX_ITEMS)
        .toList();
    List<ControlPlaneReadModel.OverlayEdge> directEdges = directEdges(state.localNodeId(),
        state.directPeerIds(), current, peers);
    List<ControlPlaneReadModel.OverlayEdge> desiredEdges = desiredEdges(current);
    List<ControlPlaneReadModel.NetworkRoute> routes = usable && state.topology().isPresent()
        ? routes(state, current, state.topology().orElseThrow(), now, relay)
        : List.of();
    String overlayStatus = usable ? "CURRENT" : "UNUSABLE";
    String networkStatus = usable ? "HEALTHY" : "DEGRADED";
    ControlPlaneReadModel.OverlaySummary overlay = new ControlPlaneReadModel.OverlaySummary(
        overlayStatus, current.authorityNodeId(), current.revision(), current.members().size(),
        current.expiresAt().toString(), members, directEdges, desiredEdges);
    return new ControlPlaneReadModel.NetworkSnapshot(networkStatus, peers, routes, overlay, relay);
  }

  private List<ControlPlaneReadModel.OverlayEdge> desiredEdges(
      OverlayMembershipSnapshot membership) {
    return topologyPolicy.desiredNeighbors(membership.members()).entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .flatMap(entry -> entry.getValue().stream().sorted()
            .map(neighbor -> new ControlPlaneReadModel.OverlayEdge(entry.getKey(), neighbor,
                "DESIRED")))
        .limit(MAX_ITEMS)
        .toList();
  }

  /**
   * One consistent source snapshot supplied by a long-lived runtime owner.
   *
   * @param localNodeId   local authenticated Link node ID
   * @param sessions      authenticated physical peer sessions
   * @param membership    current verified membership view, when configured
   * @param topology      current verified topology view, when configured
   * @param directPeerIds currently bound direct adjacency IDs
   * @param relay         safe relay-owner state
   */
  public record LinkState(String localNodeId, List<PeerSession> sessions,
                          Optional<OverlayMembershipView> membership,
                          Optional<OverlayTopologyView> topology, Set<String> directPeerIds,
                          RelayState relay) {

    /**
     * Validates and defensively copies the runtime view. The contained Link view objects remain
     * owned by the runtime source.
     */
    public LinkState {
      Objects.requireNonNull(localNodeId, "local node ID");
      if (localNodeId.isBlank()) {
        throw new IllegalArgumentException("local node ID must not be blank");
      }
      sessions = List.copyOf(Objects.requireNonNull(sessions, "sessions"));
      membership = Objects.requireNonNull(membership, "membership");
      topology = Objects.requireNonNull(topology, "topology");
      directPeerIds = Set.copyOf(Objects.requireNonNull(directPeerIds, "direct peer IDs"));
      relay = Objects.requireNonNull(relay, "relay");
    }
  }

  /**
   * Safe relay state supplied by the runtime owner.
   *
   * @param status            relay status
   * @param localAddress      safe relay address label
   * @param activeConnections current live relay connection count
   * @param configured        whether relay configuration exists
   * @param connected         whether this node has a live relay connection
   * @param relayIdentity     authenticated relay identity, or empty
   * @param authorized        whether this node is authorized by relay policy
   * @param activeRouteUsage  current logical routes using the relay
   */
  public record RelayState(String status, String localAddress, int activeConnections,
                           boolean configured, boolean connected, String relayIdentity,
                           boolean authorized, int activeRouteUsage) {

    /**
     * Validates safe relay state and normalizes optional labels.
     */
    public RelayState {
      Objects.requireNonNull(status, "status");
      localAddress = localAddress == null ? "" : localAddress;
      relayIdentity = relayIdentity == null ? "" : relayIdentity;
      if (activeConnections < 0 || activeRouteUsage < 0) {
        throw new IllegalArgumentException("relay counters cannot be negative");
      }
    }

    /**
     * Returns the conservative disabled relay state.
     *
     * @return disabled state
     */
    public static RelayState disabled() {
      return new RelayState("DISABLED", "", 0, false, false, "", false, 0);
    }

    private ControlPlaneReadModel.RelaySummary toSummary() {
      return new ControlPlaneReadModel.RelaySummary(status, localAddress, activeConnections,
          configured, connected, relayIdentity, authorized, activeRouteUsage);
    }
  }
}
