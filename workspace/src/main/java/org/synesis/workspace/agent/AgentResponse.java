package org.synesis.workspace.agent;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Shared provider-neutral agent response contract envelope.
 *
 * <p>All normal agent-facing outcomes serialize into this bounded envelope. Unused
     * fields (reason, nextAction, result) are omitted rather than serializing unnecessary
     * nulls. The assigned worktree is returned as Synesis coordination context; provider
     * hooks route native mutations there without requiring the agent to change directory.
 *
 * @param status     public operational status
 * @param reason     optional public reason code
 * @param nextAction optional public next action
 * @param result     optional bounded result payload
 * @since 1.0
 */
public record AgentResponse(
        AgentStatus status,
        AgentReason reason,
        AgentNextAction nextAction,
        Object result
) {

    /**
     * Maximum allowed size for a serialized normal agent response (1 MiB).
     * This accommodates two bounded 64 KiB raw output streams plus JSON
     * escaping and structured evidence metadata.
     */
    public static final int MAX_RESPONSE_BYTES = 1_048_576;

    /**
     * Validates required response fields.
     */
    public AgentResponse {
        Objects.requireNonNull(status, "status");
    }

    /** Creates a mutation success response carrying the updated file revision.
     * @param relativePath repository-relative target path
     * @param revision updated opaque file revision
     * @return completed response
     */
    public static AgentResponse completed(String relativePath, String revision) {
        return new AgentResponse(AgentStatus.COMPLETED, null, null, new AgentMutationResult(relativePath, revision, 1));
    }

    /**
     * Creates a ready response with workspace readiness status.
     *
     * @param workspace workspace state identifier (e.g. "isolated")
     * @param pending   pending item count
     * @return ready agent response
     */
    public static AgentResponse ready(String workspace, int pending) {
        return new AgentResponse(AgentStatus.READY, null, null, new AgentStatusResult(workspace, pending));
    }

    /**
     * Creates a readiness response with explicit assigned-worktree guidance.
     *
     * @param workspace workspace state identifier
     * @param pending pending coordination item count
     * @param worktree assigned worktree path
     * @return ready agent response
     */
    public static AgentResponse ready(String workspace, int pending, String worktree) {
        return new AgentResponse(AgentStatus.READY, null, null,
                new AgentStatusResult(workspace, pending, worktree,
                        "Keep the provider in its current project directory. Use Synesis MCP for all reads, writes, "
                                + "and commands; Synesis applies them internally in this assigned worktree. Do not "
                                + "switch branches, cd, or relaunch into the worktree. Native provider hooks are "
                                + "optional and may be unavailable in desktop harnesses. If native editing is attempted "
                                + "and Synesis reports workspace_mismatch, stop native mutations and verify with Synesis MCP."));
    }

    /**
     * Creates a blocked response with a public reason code.
     *
     * @param reason public reason code
     * @return blocked agent response
     */
    public static AgentResponse blocked(AgentReason reason) {
        return new AgentResponse(AgentStatus.BLOCKED, Objects.requireNonNull(reason, "reason"), null, null);
    }

    /**
     * Converts this response to a map omitting any null fields.
     *
     * @return map representation
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status.value());
        if (reason != null) {
            map.put("reason", reason.value());
        }
        if (nextAction != null) {
            map.put("nextAction", nextAction.value());
        }
        if (result != null) {
            if (result instanceof AgentMutationResult mut) {
                Map<String, Object> mutation = new LinkedHashMap<>();
                mutation.put("path", mut.path());
                if (mut.revision() != null && !mut.revision().isBlank()) {
                    mutation.put("revision", mut.revision());
                }
                if (mut.revision() != null && !mut.revision().isBlank()) {
                    mutation.put("changedFiles", mut.changedFiles());
                }
                map.put("result", mutation);
            } else if (result instanceof AgentCapabilityResult cap) {
                Map<String, Object> capMap = new LinkedHashMap<>();
                if (cap.capability() != null) {
                    capMap.put("capability", cap.capability());
                }
                if (cap.requiredFields() != null && !cap.requiredFields().isEmpty()) {
                    capMap.put("requiredFields", cap.requiredFields());
                }
                map.put("result", capMap);
            } else if (result instanceof AgentStatusResult stat) {
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("workspace", stat.workspace());
                status.put("pending", stat.pending());
                if (stat.worktree() != null) {
                    status.put("worktree", stat.worktree());
                }
                if (stat.instruction() != null) {
                    status.put("instruction", stat.instruction());
                }
                map.put("result", status);
            } else if (result instanceof AgentWorkspaceGuidance guidance) {
                map.put("result", Map.of(
                        "controlCheckout", guidance.controlCheckout(),
                        "assignedWorktree", guidance.assignedWorktree(),
                        "instruction", guidance.instruction()));
            } else if (result instanceof Map<?, ?> resMap) {
                map.put("result", resMap);
            } else {
                map.put("result", result);
            }
        }
        return map;
    }

    /** Reconstructs a bounded response previously produced by {@link #toMap()}.
     * @param map response map produced by {@link #toMap()}
     * @return reconstructed response
     */
    public static AgentResponse fromMap(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        Object rawStatus = map.get("status");
        if (!(rawStatus instanceof String status)) {
            throw new IllegalArgumentException("response status is required");
        }
        AgentReason reason = map.get("reason") instanceof String value
                ? AgentReason.fromValue(value) : null;
        AgentNextAction nextAction = map.get("nextAction") instanceof String value
                ? AgentNextAction.fromValue(value) : null;
        Object result = map.get("result");
        return new AgentResponse(AgentStatus.fromValue(status), reason, nextAction, result);
    }

    /**
     * Serializes this response to a compact JSON string, omitting null fields.
     *
     * @return compact JSON representation
     * @throws IllegalStateException if response exceeds size limits
     */
    public String toJson() {
        String json = ProviderJson.write(toMap());
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException("Agent response exceeds maximum size of "
                    + MAX_RESPONSE_BYTES + " bytes (actual: " + bytes.length + " bytes)");
        }
        return json;
    }
}
