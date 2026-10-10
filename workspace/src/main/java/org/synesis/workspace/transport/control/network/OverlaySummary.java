package org.synesis.workspace.transport.control.network;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static org.synesis.workspace.transport.control.network.NetworkSnapshot.MAX_ITEMS;

/**
 * Overlay membership summary.
 *
 * @param status          overlay status
 * @param authorityNodeId membership authority node
 * @param revision        membership revision
 * @param memberCount     member count
 * @param expiresAt       membership expiry timestamp
 * @param members         membership nodes
 * @param directEdges     physical adjacency edges
 * @param desiredEdges    desired topology edges
 */
public record OverlaySummary(String status, String authorityNodeId, long revision, int memberCount, String expiresAt, List<OverlayMember> members, List<OverlayEdge> directEdges, List<OverlayEdge> desiredEdges) {

    /**
     * Creates a summary without graph details.
     *
     * @param status          overlay status
     * @param authorityNodeId membership authority node
     * @param revision        membership revision
     * @param memberCount     member count
     * @param expiresAt       membership expiry timestamp
     */
    public OverlaySummary(String status, String authorityNodeId, long revision, int memberCount, String expiresAt) {
        this(status, authorityNodeId, revision, memberCount, expiresAt, List.of(), List.of(), List.of());
    }

    /**
     * Validates the overlay summary.
     */
    public OverlaySummary {
        Objects.requireNonNull(status, "status");
        authorityNodeId = authorityNodeId == null ? "" : authorityNodeId;
        expiresAt = expiresAt == null ? "" : expiresAt;
        members = Objects.requireNonNull(members, "members").stream().limit(MAX_ITEMS).toList();
        directEdges = Objects.requireNonNull(directEdges, "direct edges").stream().limit(MAX_ITEMS).toList();
        desiredEdges = Objects.requireNonNull(desiredEdges, "desired edges").stream().limit(MAX_ITEMS).toList();
    }

    Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("authorityNodeId", authorityNodeId);
        result.put("revision", revision);
        result.put("memberCount", memberCount);
        result.put("expiresAt", expiresAt);
        result.put("members", members.stream().map(OverlayMember::toMap).toList());
        result.put("directEdges", directEdges.stream().map(OverlayEdge::toMap).toList());
        result.put("desiredEdges", desiredEdges.stream().map(OverlayEdge::toMap).toList());
        return result;
    }
}
