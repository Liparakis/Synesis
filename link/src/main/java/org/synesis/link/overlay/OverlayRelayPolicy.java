package org.synesis.link.overlay;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Local operator policy for one bounded organization relay.
 *
 * <p>The policy is deliberately an allowlist of project and node IDs. It is
 * not a SaaS account model and contains no provider credentials.
 */
public final class OverlayRelayPolicy {

    /** Default maximum live node connections. */
    public static final int DEFAULT_MAX_CONNECTIONS = 128;
    /** Default maximum in-flight frames per destination. */
    public static final int DEFAULT_MAX_QUEUED_FRAMES = 256;
    /** Default per-node frame rate limit. */
    public static final int DEFAULT_MAX_FRAMES_PER_SECOND = 256;

    private final Map<UUID, Set<String>> allowedNodesByProject;
    private final int maxConnections;
    private final int maxQueuedFrames;
    private final int maxFramesPerSecond;

    /**
     * Creates a policy with conservative bounded defaults.
     *
     * @param allowedNodesByProject project-to-node allowlist
     */
    public OverlayRelayPolicy(Map<UUID, Set<String>> allowedNodesByProject) {
        this(allowedNodesByProject, DEFAULT_MAX_CONNECTIONS, DEFAULT_MAX_QUEUED_FRAMES,
                DEFAULT_MAX_FRAMES_PER_SECOND);
    }

    /**
     * Creates a bounded operator policy.
     *
     * @param allowedNodesByProject project-to-node allowlist
     * @param maxConnections maximum live connections
     * @param maxQueuedFrames maximum pending frames per destination
     * @param maxFramesPerSecond maximum source frames per one-second window
     */
    public OverlayRelayPolicy(Map<UUID, Set<String>> allowedNodesByProject, int maxConnections,
            int maxQueuedFrames, int maxFramesPerSecond) {
        Objects.requireNonNull(allowedNodesByProject, "allowed nodes by project");
        if (maxConnections < 1 || maxQueuedFrames < 1 || maxFramesPerSecond < 1) {
            throw new IllegalArgumentException("relay limits must be positive");
        }
        Map<UUID, Set<String>> copy = new HashMap<>();
        allowedNodesByProject.forEach((project, nodes) -> {
            Objects.requireNonNull(project, "project ID");
            Objects.requireNonNull(nodes, "project node allowlist");
            if (nodes.isEmpty() || nodes.size() > OverlayMembershipSnapshot.MAX_MEMBERS) {
                throw new IllegalArgumentException("relay project allowlist exceeds member bound");
            }
            Set<String> nodeCopy = new HashSet<>();
            for (String node : nodes) {
                OverlayCodecSupport.requireNodeId(node);
                nodeCopy.add(node);
            }
            copy.put(project, Set.copyOf(nodeCopy));
        });
        this.allowedNodesByProject = Collections.unmodifiableMap(copy);
        this.maxConnections = maxConnections;
        this.maxQueuedFrames = maxQueuedFrames;
        this.maxFramesPerSecond = maxFramesPerSecond;
    }

    /**
     * Returns whether a node is authorized for a project.
     *
     * @param projectId project ID
     * @param nodeId node ID
     * @return true when allowed
     */
    public boolean allows(UUID projectId, String nodeId) {
        Objects.requireNonNull(projectId, "project ID");
        Objects.requireNonNull(nodeId, "node ID");
        return allowedNodesByProject.getOrDefault(projectId, Set.of()).contains(nodeId);
    }

    boolean allowsOnAnyProject(String nodeId) {
        return allowedNodesByProject.values().stream().anyMatch(nodes -> nodes.contains(nodeId));
    }

    /**
     * Returns the maximum live connection count.
     *
     * @return connection bound
     */
    public int maxConnections() {
        return maxConnections;
    }

    /**
     * Returns the maximum pending frames per destination.
     *
     * @return queue bound
     */
    public int maxQueuedFrames() {
        return maxQueuedFrames;
    }

    /**
     * Returns the maximum source frame rate.
     *
     * @return frames per second
     */
    public int maxFramesPerSecond() {
        return maxFramesPerSecond;
    }
}
