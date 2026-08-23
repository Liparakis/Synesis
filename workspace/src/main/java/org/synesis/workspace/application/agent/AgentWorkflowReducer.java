package org.synesis.workspace.application.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Reduces a provider-neutral response into one durable lane workflow action.
 *
 * <p>The reducer is deliberately independent of MCP and CLI transports. It does
 * not consume inbox records; callers may retrieve the same action repeatedly
 * until an explicit acknowledgement or resolution changes durable state.
 */
public final class AgentWorkflowReducer {

    /** Creates the stateless workflow reducer. */
    public AgentWorkflowReducer() {
    }

    /**
     * Adds the stable workflow action envelope to a response.
     *
     * @param request exact lane request
     * @param response response derived by the lane service
     * @return response containing workflow action metadata
     */
    public AgentResponse decorate(AgentNextActionService.NextActionRequest request, AgentResponse response) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        Map<String, Object> result = new LinkedHashMap<>();
        if (response.result() instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        } else if (response.result() != null) {
            result.put("value", response.result());
        }

        LaneAction action = actionFor(response, result);
        String actionId = actionId(request, response, result, action);
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("actionId", actionId);
        workflow.put("type", action.type());
        workflow.put("payload", Collections.unmodifiableMap(new LinkedHashMap<>(result)));
        Map<?, ?> laneContext = firstIntent(result);
        if (laneContext.get("workGroupId") != null) {
            workflow.put("workGroupId", laneContext.get("workGroupId"));
        }
        if (laneContext.get("intentId") != null) {
            workflow.put("laneId", laneContext.get("intentId"));
        }
        workflow.put("blockers", action.blockers());
        workflow.put("permittedOperations", action.permittedOperations());
        workflow.put("retrySafe", action.retrySafe());
        workflow.put("delivery", "AT_LEAST_ONCE");
        workflow.put("acknowledgementRequired", true);
        Map<String, Object> executable = executableAction(response, result, actionId);
        if (executable != null) {
            workflow.put("recommendedTool", executable.get("tool"));
            workflow.put("arguments", executable.get("arguments"));
        }
        result.put("workflow", workflow);
        result.put("actionId", actionId);
        result.put("delivery", "AT_LEAST_ONCE");
        result.put("acknowledgementRequired", true);
        return new AgentResponse(response.status(), response.reason(), response.nextAction(), result);
    }

    private static Map<?, ?> firstIntent(Map<String, Object> result) {
        Object current = result.get("currentIntent");
        if (current instanceof Map<?, ?> map) {
            return map;
        }
        Object intents = result.get("intents");
        if (intents instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> map) {
            return map;
        }
        return Map.of();
    }

    private static String actionId(AgentNextActionService.NextActionRequest request, AgentResponse response,
            Map<String, Object> result, LaneAction action) {
        StringBuilder seed = new StringBuilder(action.type())
                .append('|').append(response.status().value())
                .append('|').append(response.reason() == null ? "" : response.reason().value())
                .append('|').append(request.provider())
                .append('|').append(request.connectionInstanceId());
        for (String key : List.of("inboxItemId", "capabilityRequestHandle", "capability", "pending", "workGroupId", "laneId")) {
            if (result.containsKey(key)) {
                seed.append('|').append(key).append('=').append(ProviderJson.write(result.get(key)));
            }
        }
        return UUID.nameUUIDFromBytes(seed.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static LaneAction actionFor(AgentResponse response, Map<String, Object> result) {
        String laneState = laneState(result);
        if ("COMPLETED".equals(laneState) || "CANCELLED".equals(laneState)
                || "DETACHED".equals(laneState) || "REVOKED".equals(laneState)) {
            return new LaneAction("CLOSE", List.of("lane_" + laneState.toLowerCase(java.util.Locale.ROOT)),
                    List.of("get_next_action", "ensure_session"), true);
        }
        if ("SUSPENDED".equals(laneState) || "RECOVERY_HELD".equals(laneState)) {
            return new LaneAction("RECOVER", List.of("lane_" + laneState.toLowerCase(java.util.Locale.ROOT)),
                    List.of("ensure_session", "get_next_action", "request_coordination"), true);
        }
        AgentNextAction next = response.nextAction();
        AgentReason reason = response.reason();
        if (next == null) {
            return new LaneAction("IMPLEMENT", List.of(), List.of("read_file", "apply_patch", "run_command"), true);
        }
        return switch (next) {
            case ENSURE_SESSION -> new LaneAction("RECOVER", List.of(reasonCode(reason)),
                    List.of("ensure_session"), true);
            case REQUEST_COORDINATION, REVISE_CAPABILITY_REQUEST ->
                    new LaneAction("REVISE_SCOPE",
                            List.of(reasonCode(reason)), List.of("request_coordination", "respond_coordination"), true);
            case RESPOND_COORDINATION -> {
                // A capability owner may need to accept a request before the
                // implementation is ready.  Keep the exact response handle
                // required, but do not fence the owner's already-authorized
                // lane: claim/revision checks on each mutation remain the
                // final authority boundary.
                boolean capabilityOwner = result.containsKey("capabilityRequestHandle")
                        && result.containsKey("capability");
                yield new LaneAction("REVIEW_CONTRACT", List.of(reasonCode(reason)),
                        capabilityOwner
                                ? List.of("request_coordination", "respond_coordination", "read_file",
                                        "apply_patch", "run_command", "get_next_action")
                                : List.of("request_coordination", "respond_coordination"), true);
            }
            case VALIDATE_IMPLEMENTATION, RESPOND_TO_VALIDATION_REVISION ->
                    new LaneAction("PUBLISH", List.of(reasonCode(reason)),
                            List.of("respond_coordination", "publish_capability_implementation"), true);
            case FINISH_LANE -> new LaneAction("PUBLISH", List.of(reasonCode(reason)),
                    List.of("finish_lane", "get_next_action"), true);
            case WAIT -> {
                boolean capabilityImplementation = reason == AgentReason.IMPLEMENTATION_UNAVAILABLE
                        && result.containsKey("capabilityRequestHandle");
                yield new LaneAction(capabilityImplementation ? "IMPLEMENT" : "WAIT",
                        List.of(reasonCode(reason)), capabilityImplementation
                                ? List.of("read_file", "apply_patch", "run_command",
                                        "publish_capability_implementation", "get_next_action")
                                : List.of("get_next_action"), true);
            }
            case RETRY -> new LaneAction("RECOVER", List.of(reasonCode(reason)), List.of("ensure_session", "get_next_action"), true);
            case REQUEST_HUMAN_HELP -> new LaneAction("INTEGRATION_REPAIR", List.of(reasonCode(reason)),
                    List.of("get_next_action", "request_coordination"), false);
        };
    }

    private static Map<String, Object> executableAction(AgentResponse response, Map<String, Object> result,
            String actionId) {
        AgentNextAction next = response.nextAction();
        if (next == null) {
            return null;
        }
        switch (next) {
            case VALIDATE_IMPLEMENTATION -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                Object serverItem = result.get("inboxItemId");
                if (serverItem == null && result.get("capabilityRequestHandle") instanceof String handle) {
                    serverItem = UUID.nameUUIDFromBytes(("capability:" + handle)
                            .getBytes(StandardCharsets.UTF_8)).toString();
                }
                if (serverItem == null) {
                    return null;
                }
                payload.put("inboxItemId", serverItem);
                payload.put("capabilityRequestHandle", result.get("capabilityRequestHandle"));
                payload.put("implementationRevision", result.get("revision"));
                return Map.of("tool", "respond_coordination", "arguments", Map.of(
                        "kind", "implementation_validation", "payload", payload));
            }
            case RESPOND_COORDINATION -> {
                Object kind = result.get("nextProtocolKind");
                Object payload = result.get("nextProtocolPayload");
                if (kind instanceof String && payload instanceof Map<?, ?>) {
                    return Map.of("tool", "respond_coordination", "arguments", Map.of(
                            "kind", kind, "payload", payload));
                }
                return Map.of("tool", "respond_coordination", "arguments", Map.of());
            }
            case FINISH_LANE -> {
                Map<String, Object> arguments = new LinkedHashMap<>();
                Object payload = result.get("nextProtocolPayload");
                if (payload instanceof Map<?, ?> payloadMap) {
                    payloadMap.forEach((key, value) -> arguments.put(String.valueOf(key), value));
                }
                return Map.of("tool", "finish_lane", "arguments", arguments);
            }
            case REQUEST_COORDINATION -> {
                return Map.of("tool", "request_coordination", "arguments", Map.of());
            }
            case ENSURE_SESSION -> {
                return Map.of("tool", "ensure_session", "arguments", Map.of());
            }
            case RETRY, WAIT -> {
                return Map.of("tool", "get_next_action", "arguments", Map.of());
            }
            default -> {
                return null;
            }
        }
    }

    private static String laneState(Map<String, Object> result) {
        Map<?, ?> intent = firstIntent(result);
        Object state = intent.get("status");
        if (state instanceof String value) {
            return value;
        }
        Object participants = result.get("participants");
        if (participants instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> participant && participant.get("state") instanceof String value) {
                    return value;
                }
            }
        }
        return "";
    }

    private static String reasonCode(AgentReason reason) {
        return reason == null ? "none" : reason.value();
    }

    private record LaneAction(String type, List<String> blockers, List<String> permittedOperations,
            boolean retrySafe) {
        private LaneAction {
            blockers = List.copyOf(new ArrayList<>(blockers));
            permittedOperations = List.copyOf(new ArrayList<>(permittedOperations));
        }
    }
}
