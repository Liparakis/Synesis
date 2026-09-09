package org.synesis.link.overlay;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic route selector for direct, peer-transit, and relay paths.
 */
public final class OverlayRouteSelector {

  private OverlayRouteSelector() {
  }

  /**
   * Selects the preferred currently available route.
   *
   * @param localNodeId       local member node ID
   * @param destinationNodeId final destination node ID
   * @param membership        current verified membership
   * @param directNeighbors   currently usable direct peer IDs
   * @param topology          signed topology view
   * @param now               current time
   * @param relayNodeId       live configured relay identity or endpoint label
   * @return deterministic route result
   * @throws GeneralSecurityException if membership is not currently valid
   */
  public static OverlayRoute select(String localNodeId, String destinationNodeId,
      OverlayMembershipSnapshot membership, Set<String> directNeighbors,
      OverlayTopologyView topology,
      Instant now, String relayNodeId) throws GeneralSecurityException {
    Objects.requireNonNull(membership, "membership");
    Objects.requireNonNull(directNeighbors, "direct neighbors");
    Objects.requireNonNull(topology, "topology");
    Objects.requireNonNull(now, "now");
    OverlayCodecSupport.requireNodeId(localNodeId);
    OverlayCodecSupport.requireNodeId(destinationNodeId);
    if (!membership.isUsableAt(now) || !membership.allows(localNodeId)) {
      throw new GeneralSecurityException("local membership is not valid for route selection");
    }
    if (!membership.allows(destinationNodeId)) {
      return OverlayRoute.unreachable(destinationNodeId);
    }
    if (directNeighbors.stream().anyMatch(value -> !isAuthorizedNode(value, membership))) {
      throw new GeneralSecurityException("direct adjacency contains an unauthorized node");
    }
    if (directNeighbors.contains(destinationNodeId)) {
      return OverlayRoute.direct(destinationNodeId);
    }

    Map<String, List<String>> advertised = topology.adjacencyGraph(membership, now);
    List<String> peerPath = shortestPath(localNodeId, destinationNodeId, directNeighbors,
        advertised, membership);
    if (!peerPath.isEmpty()) {
      return OverlayRoute.peerTransit(peerPath);
    }
    if (relayNodeId != null && !relayNodeId.isBlank()) {
      return OverlayRoute.organizationRelay(destinationNodeId, relayNodeId);
    }
    return OverlayRoute.unreachable(destinationNodeId);
  }

  private static List<String> shortestPath(String localNodeId, String destinationNodeId,
      Set<String> directNeighbors, Map<String, List<String>> advertised,
      OverlayMembershipSnapshot membership) {
    Deque<List<String>> pending = new ArrayDeque<>();
    TreeSet<String> initial = new TreeSet<>(directNeighbors);
    for (String neighbor : initial) {
      if (membership.allows(neighbor)) {
        pending.addLast(List.of(neighbor));
      }
    }
    Set<String> visited = new HashSet<>();
    visited.add(localNodeId);
    while (!pending.isEmpty()) {
      List<String> path = pending.removeFirst();
      String current = path.get(path.size() - 1);
      if (!visited.add(current)) {
        continue;
      }
      if (current.equals(destinationNodeId)) {
        return path;
      }
      if (path.size() >= OverlayForwardingFrame.MAX_HOPS) {
        continue;
      }
      List<String> neighbors = new ArrayList<>(advertised.getOrDefault(current, List.of()));
      neighbors.sort(Comparator.naturalOrder());
      for (String neighbor : neighbors) {
        if (membership.allows(neighbor) && !visited.contains(neighbor)) {
          List<String> extended = new ArrayList<>(path);
          extended.add(neighbor);
          pending.addLast(List.copyOf(extended));
        }
      }
    }
    return List.of();
  }

  private static boolean isAuthorizedNode(String nodeId, OverlayMembershipSnapshot membership) {
    try {
      OverlayCodecSupport.requireNodeId(nodeId);
      return membership.allows(nodeId);
    } catch (RuntimeException invalid) {
      return false;
    }
  }
}
