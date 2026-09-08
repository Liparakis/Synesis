package org.synesis.link.overlay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic bounded-degree desired-adjacency policy for one project.
 *
 * <p>Small projects use a full mesh. Larger projects use the two nearest
 * circular offsets in the stable node-ID ordering. The policy describes
 * desired direct neighbors; it does not claim that every desired socket is
 * currently reachable.
 */
public final class OverlayTopologyPolicy {

    /** Maximum desired direct degree in the default policy. */
    public static final int MAX_DEGREE = 4;
    /** Default full-mesh threshold. */
    public static final int DEFAULT_FULL_MESH_THRESHOLD = 3;
    /** Default circular shortcut distance. */
    public static final int DEFAULT_SHORTCUT_DISTANCE = 2;

    private final int fullMeshThreshold;
    private final int shortcutDistance;

    /**
     * Creates the default bounded ring-plus-shortcuts policy.
     */
    public OverlayTopologyPolicy() {
        this(DEFAULT_FULL_MESH_THRESHOLD, DEFAULT_SHORTCUT_DISTANCE);
    }

    /**
     * Creates a deterministic policy with bounded degree.
     *
     * @param fullMeshThreshold maximum member count using a full mesh
     * @param shortcutDistance positive circular offset for larger projects
     */
    public OverlayTopologyPolicy(int fullMeshThreshold, int shortcutDistance) {
        if (fullMeshThreshold < 2 || fullMeshThreshold > MAX_DEGREE + 1) {
            throw new IllegalArgumentException("full-mesh threshold exceeds degree bound");
        }
        if (shortcutDistance < 1 || shortcutDistance * 2 > MAX_DEGREE) {
            throw new IllegalArgumentException("shortcut distance exceeds degree bound");
        }
        this.fullMeshThreshold = fullMeshThreshold;
        this.shortcutDistance = shortcutDistance;
    }

    /**
     * Computes desired neighbors for every member in stable order.
     *
     * @param members signed-snapshot member entries
     * @return immutable node-to-neighbor mapping
     */
    public Map<String, List<String>> desiredNeighbors(List<OverlayMembershipSnapshot.Member> members) {
        List<String> nodeIds = normalizeNodeIds(members);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int index = 0; index < nodeIds.size(); index++) {
            Set<String> neighbors = new TreeSet<>();
            if (nodeIds.size() <= fullMeshThreshold) {
                neighbors.addAll(nodeIds);
                neighbors.remove(nodeIds.get(index));
            } else {
                for (int offset = 1; offset <= shortcutDistance; offset++) {
                    neighbors.add(nodeIds.get(Math.floorMod(index + offset, nodeIds.size())));
                    neighbors.add(nodeIds.get(Math.floorMod(index - offset, nodeIds.size())));
                }
            }
            result.put(nodeIds.get(index), List.copyOf(neighbors));
        }
        return Map.copyOf(result);
    }

    /**
     * Computes desired neighbors for one member.
     *
     * @param nodeId member node ID
     * @param members signed-snapshot member entries
     * @return immutable sorted neighbor IDs
     */
    public List<String> desiredNeighbors(String nodeId, List<OverlayMembershipSnapshot.Member> members) {
        Objects.requireNonNull(nodeId, "node ID");
        return desiredNeighbors(members).getOrDefault(nodeId, List.of());
    }

    /**
     * Returns the configured full-mesh threshold.
     *
     * @return threshold
     */
    public int fullMeshThreshold() {
        return fullMeshThreshold;
    }

    /**
     * Returns the configured circular shortcut distance.
     *
     * @return shortcut distance
     */
    public int shortcutDistance() {
        return shortcutDistance;
    }

    private static List<String> normalizeNodeIds(List<OverlayMembershipSnapshot.Member> members) {
        Objects.requireNonNull(members, "members");
        List<String> nodeIds = new ArrayList<>(members.stream()
                .map(member -> Objects.requireNonNull(member, "member").nodeId())
                .sorted(Comparator.naturalOrder())
                .toList());
        for (int index = 1; index < nodeIds.size(); index++) {
            if (nodeIds.get(index - 1).equals(nodeIds.get(index))) {
                throw new IllegalArgumentException("duplicate topology member");
            }
        }
        if (nodeIds.isEmpty()) {
            throw new IllegalArgumentException("topology requires at least one member");
        }
        return List.copyOf(nodeIds);
    }
}
