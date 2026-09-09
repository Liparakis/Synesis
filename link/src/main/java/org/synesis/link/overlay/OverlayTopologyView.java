package org.synesis.link.overlay;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded local store of the newest verified topology advertisement per node.
 *
 * <p>The view is deliberately a local read model. It does not gossip by
 * itself, elect an authority, or turn an advertisement into a direct socket.
 */
public final class OverlayTopologyView {

  private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(10);
  private final int maxOrigins;
  private final Map<String, OverlayTopologyAdvertisement> advertisements = new LinkedHashMap<>();
  /**
   * Creates a view bounded to the current project-member limit.
   */
  public OverlayTopologyView() {
    this(OverlayMembershipSnapshot.MAX_MEMBERS);
  }

  /**
   * Creates a view with an explicit origin bound.
   *
   * @param maxOrigins maximum stored origin advertisements
   */
  public OverlayTopologyView(int maxOrigins) {
    if (maxOrigins < 1 || maxOrigins > OverlayMembershipSnapshot.MAX_MEMBERS) {
      throw new IllegalArgumentException("topology origin bound is outside project limits");
    }
    this.maxOrigins = maxOrigins;
  }

  /**
   * Returns the default advertisement lifetime used by callers that need a bounded policy value.
   *
   * @return default lifetime
   */
  public static Duration defaultLifetime() {
    return DEFAULT_LIFETIME;
  }

  /**
   * Accepts one verified advertisement if it advances the origin sequence.
   *
   * @param advertisement candidate advertisement
   * @param membership    current verified membership snapshot
   * @param now           current time
   * @return acceptance result
   * @throws GeneralSecurityException if the candidate is not authenticated
   */
  public synchronized Acceptance accept(OverlayTopologyAdvertisement advertisement,
      OverlayMembershipSnapshot membership, Instant now) throws GeneralSecurityException {
    Objects.requireNonNull(advertisement, "advertisement");
    Objects.requireNonNull(now, "now");
    pruneExpired(now);
    advertisement.verifyAgainst(membership, now);
    OverlayTopologyAdvertisement current = advertisements.get(advertisement.originNodeId());
    if (current == null && advertisements.size() >= maxOrigins) {
      return Acceptance.CAPACITY_REJECTED;
    }
    if (current != null) {
      if (advertisement.membershipRevision() < current.membershipRevision()) {
        return Acceptance.STALE;
      }
      if (advertisement.membershipRevision() > current.membershipRevision()) {
        advertisements.put(advertisement.originNodeId(), advertisement);
        return Acceptance.ACCEPTED;
      }
      if (advertisement.sequence() < current.sequence()) {
        return Acceptance.STALE;
      }
      if (advertisement.sequence() == current.sequence()) {
        if (Arrays.equals(advertisement.encoded(), current.encoded())) {
          return Acceptance.DUPLICATE;
        }
        throw new GeneralSecurityException("conflicting topology sequence");
      }
    }
    advertisements.put(advertisement.originNodeId(), advertisement);
    return Acceptance.ACCEPTED;
  }

  /**
   * Removes expired advertisements.
   *
   * @param now current time
   * @return number removed
   */
  public synchronized int pruneExpired(Instant now) {
    Objects.requireNonNull(now, "now");
    int before = advertisements.size();
    advertisements.values().removeIf(value -> value.isExpiredAt(now));
    return before - advertisements.size();
  }

  /**
   * Returns a stable copy of the stored advertisements.
   *
   * @return advertisements by origin
   */
  public synchronized Map<String, OverlayTopologyAdvertisement> advertisements() {
    return Map.copyOf(advertisements);
  }

  /**
   * Returns the current advertisement for one origin.
   *
   * @param originNodeId origin node ID
   * @return current advertisement, if present
   */
  public synchronized Optional<OverlayTopologyAdvertisement> advertisement(String originNodeId) {
    return Optional.ofNullable(
        advertisements.get(Objects.requireNonNull(originNodeId, "origin node ID")));
  }

  /**
   * Builds the currently advertised directed adjacency graph.
   *
   * @param now current time
   * @return immutable graph with sorted neighbor lists
   */
  public synchronized Map<String, List<String>> adjacencyGraph(Instant now) {
    Objects.requireNonNull(now, "now");
    pruneExpired(now);
    Map<String, List<String>> result = new LinkedHashMap<>();
    advertisements.values().stream().sorted(java.util.Comparator.comparing(
        OverlayTopologyAdvertisement::originNodeId)).forEach(advertisement ->
        result.put(advertisement.originNodeId(),
            List.copyOf(new ArrayList<>(advertisement.adjacentNodeIds()))));
    return Map.copyOf(result);
  }

  /**
   * Builds the graph for one current membership revision.
   *
   * @param membership current verified membership snapshot
   * @param now        current time
   * @return immutable graph with current-revision neighbor lists
   */
  public synchronized Map<String, List<String>> adjacencyGraph(OverlayMembershipSnapshot membership,
      Instant now) {
    Objects.requireNonNull(membership, "membership");
    Objects.requireNonNull(now, "now");
    pruneExpired(now);
    Map<String, List<String>> result = new LinkedHashMap<>();
    advertisements.values().stream()
        .filter(advertisement -> advertisement.membershipRevision() == membership.revision())
        .sorted(java.util.Comparator.comparing(OverlayTopologyAdvertisement::originNodeId))
        .forEach(advertisement -> result.put(advertisement.originNodeId(),
            List.copyOf(new ArrayList<>(advertisement.adjacentNodeIds()))));
    return Map.copyOf(result);
  }

  /**
   * Result when a newer advertisement replaces the stored one.
   */
  public enum Acceptance {
    /**
     * A newer valid advertisement was stored.
     */
    ACCEPTED,
    /**
     * The exact advertisement was already stored.
     */
    DUPLICATE,
    /**
     * The origin sequence was not newer than the stored value.
     */
    STALE,
    /**
     * The bounded store cannot admit another origin.
     */
    CAPACITY_REJECTED
  }
}
