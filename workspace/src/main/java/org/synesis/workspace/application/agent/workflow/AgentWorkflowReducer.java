package org.synesis.workspace.application.agent.workflow;

import static org.synesis.workspace.application.agent.workflow.NextActionSupport.putResult;

import java.nio.charset.StandardCharsets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Reduces a provider-neutral response into one durable lane workflow action.
 *
 * <p>The reducer is deliberately independent of MCP and CLI transports. It does
 * not consume inbox records; callers may retrieve the same action repeatedly until an explicit
 * acknowledgement or resolution changes durable state.
 */
public final class AgentWorkflowReducer {

    private static final int RETRY_AFTER_MS = 5000;
    private static final int MAX_UNCHANGED_POLLS = 3;

    /**
     * How often a wait for a peer may be polled unchanged: a minute at the retry interval. An agent
     * resumed to act takes half a minute to start and reach Synesis. With fifteen seconds of
     * patience the waiting side had always left by then, each was resumed for the other in turn,
     * and every resume gave its lane a new identity that the review grants of the last one did not
     * cover: a review ran sixteen resumes without a snapshot being submitted.
     */
    private static final int MAX_UNCHANGED_WAIT_POLLS = 12;
    private static final String LANE_RECOVER = "RECOVER";
    private static final String LANE_PUBLISH = "PUBLISH";
    private static final String LANE_REVIEW_CONTRACT = "REVIEW_CONTRACT";
    private static final String LANE_IMPLEMENT = "IMPLEMENT";
    private static final String LANE_REVISE_SCOPE = "REVISE_SCOPE";

    /**
     * Creates the stateless workflow reducer.
     */
    public AgentWorkflowReducer() {
    }

    private static Map<?, ?> firstIntent(Map<String, Object> result) {
        Object current = result.get(NextActionKeys.CURRENT_INTENT);
        if (current instanceof Map<?, ?> map) {
            return map;
        }
        Object intents = result.get(NextActionKeys.INTENTS);
        if (intents instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> map) {
            return map;
        }
        return Map.of();
    }

    private static String actionId(NextActionRequest request, AgentResponse response, Map<String, Object> result, LaneAction action) {
        StringBuilder seed = new StringBuilder(action.type()).append('|').append(response.status().value()).append('|').append(response.reason() == null ? "" : response.reason().value()).append('|')
                .append(request.provider()).append('|').append(request.connectionInstanceId());
        for (String key : List.of(NextActionKeys.INBOX_ITEM_ID,
                NextActionKeys.CAPABILITY_REQUEST_HANDLE,
                NextActionKeys.CAPABILITY,
                NextActionKeys.PENDING,
                NextActionKeys.WORK_GROUP_ID,
                NextActionKeys.ACTIONABLE_REVIEW_CANDIDATES,
                NextActionKeys.LANE_ID,
                NextActionKeys.CLAIM_EPOCH,
                NextActionKeys.SNAPSHOT_ID,
                NextActionKeys.LATEST_REJECTED_SNAPSHOT_ID,
                NextActionKeys.SOURCE_INTENT_ID,
                NextActionKeys.CONTRACT_REVISION,
                "revision",
                NextActionKeys.NEXT_PROTOCOL_PAYLOAD,
                NextActionKeys.VALIDATION_WORKSPACE_AVAILABLE,
                NextActionKeys.VALIDATION_EVIDENCE,
                NextActionKeys.REVIEW_DECISION,
                NextActionKeys.RECOVERY_DEADLINE_EPOCH_MILLIS,
                NextActionKeys.REASON,
                "detail")) {
            if (result.containsKey(key)) {
                seed.append('|').append(key).append('=').append(ProviderJson.write(actionEvidence(result.get(key))));
            }
        }
        return UUID.nameUUIDFromBytes(seed.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Map iteration order must not change the identity of replayed action evidence. */
    private static Object actionEvidence(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new java.util.TreeMap<>();
            map.forEach((key, entry) -> ordered.put(String.valueOf(key), actionEvidence(entry)));
            return ordered;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AgentWorkflowReducer::actionEvidence).toList();
        }
        return value;
    }

    private static LaneAction actionFor(AgentResponse response, Map<String, Object> result) {
        if (NextActionProtocol.REASON_SESSION_TERMINAL_STATE_UNAVAILABLE.equals(result.get(NextActionKeys.REASON))) {
            return new LaneAction(LANE_RECOVER, List.of("session_terminal_state_unavailable"), List.of(), false);
        }
        String laneState = laneState(result);
        if (response.status() == AgentStatus.COMPLETED && response.nextAction() == null
                && (NextActionProtocol.STATE_COMPLETED.equals(result.get(NextActionKeys.STATE)) || NextActionProtocol.STATE_CANCELLED.equals(result.get(NextActionKeys.STATE))
                        || NextActionProtocol.STATE_NO_OPEN_WORK.equals(result.get(NextActionKeys.STATE)))) {
            // A closed lane's answer carries no intent or participant to read a state from.
            // Without this it would be described as open implementation work with write operations.
            laneState = (String) result.get(NextActionKeys.STATE);
        }
        if (NextActionProtocol.STATE_NO_OPEN_WORK.equals(laneState)) {
            return new LaneAction("CLOSE", List.of("no_open_work"), List.of(NextActionProtocol.TOOL_GET_NEXT_ACTION, NextActionProtocol.TOOL_ENSURE_SESSION), true);
        }
        if (response.nextAction() == null && (NextActionProtocol.STATE_COMPLETED.equals(laneState) || NextActionProtocol.STATE_CANCELLED.equals(laneState) || "DETACHED".equals(laneState) || "REVOKED".equals(laneState))) {
            return new LaneAction("CLOSE", List.of("lane_" + laneState.toLowerCase(java.util.Locale.ROOT)), List.of(NextActionProtocol.TOOL_GET_NEXT_ACTION, NextActionProtocol.TOOL_ENSURE_SESSION), true);
        }
        if ("SUSPENDED".equals(laneState) || "RECOVERY_HELD".equals(laneState)) {
            return new LaneAction(LANE_RECOVER, List.of("lane_" + laneState.toLowerCase(java.util.Locale.ROOT)), List.of(NextActionProtocol.TOOL_ENSURE_SESSION, NextActionProtocol.TOOL_GET_NEXT_ACTION, NextActionProtocol.ACTION_REQUEST_COORDINATION), true);
        }
        AgentNextAction next = response.nextAction();
        AgentReason reason = response.reason();
        if (next == null) {
            if (hasActionableReviewCandidates(result)) {
                return new LaneAction(LANE_REVIEW_CONTRACT, List.of("review_candidate_selection_required"), List.of(NextActionProtocol.ACTION_REQUEST_COORDINATION, NextActionProtocol.TOOL_GET_NEXT_ACTION), true);
            }
            if (ownerCompletionDue(result)) {
                // The lane stays writable; the owner may still need to tidy its claimed files
                // before asking for the finish projection.
                return new LaneAction(LANE_PUBLISH, List.of("owner_completion_required"), List.of(NextActionProtocol.TOOL_GET_NEXT_ACTION, NextActionProtocol.TOOL_READ_FILE, NextActionProtocol.TOOL_APPLY_PATCH, NextActionProtocol.TOOL_RUN_COMMAND), true);
            }
            if (Boolean.TRUE.equals(result.get(NextActionKeys.IMPLEMENTATION_PENDING))) {
                // The lane stays writable, and the envelope says who is held up by it.
                return new LaneAction(LANE_IMPLEMENT, List.of("requester_waiting_for_implementation"),
                        List.of(NextActionProtocol.TOOL_READ_FILE, NextActionProtocol.TOOL_APPLY_PATCH, NextActionProtocol.TOOL_RUN_COMMAND, NextActionProtocol.TOOL_GET_NEXT_ACTION), true);
            }
            if (Boolean.TRUE.equals(result.get(NextActionKeys.NOTHING_TO_PUBLISH))) {
                return new LaneAction(LANE_IMPLEMENT, List.of("nothing_to_publish"),
                        List.of(NextActionProtocol.TOOL_READ_FILE, NextActionProtocol.TOOL_APPLY_PATCH, NextActionProtocol.TOOL_RUN_COMMAND, NextActionProtocol.TOOL_CANCEL_LANE), true);
            }
            return new LaneAction(LANE_IMPLEMENT, List.of(), List.of(NextActionProtocol.TOOL_READ_FILE, NextActionProtocol.TOOL_APPLY_PATCH, NextActionProtocol.TOOL_RUN_COMMAND), true);
        }
        return switch (next) {
            case ENSURE_SESSION -> new LaneAction(LANE_RECOVER, List.of(reasonCode(reason)), claimsRequired(result)
                    ? List.of(NextActionProtocol.TOOL_ENSURE_SESSION, NextActionProtocol.TOOL_READ_FILE, NextActionProtocol.TOOL_GET_NEXT_ACTION)
                    : List.of(NextActionProtocol.TOOL_ENSURE_SESSION), true);
            case REQUEST_COORDINATION, REVISE_CAPABILITY_REQUEST -> {
                if (reason == AgentReason.CONTINUATION_AVAILABLE) {
                    yield new LaneAction(LANE_RECOVER, List.of(reasonCode(reason)), List.of(NextActionProtocol.ACTION_REQUEST_COORDINATION, NextActionProtocol.TOOL_GET_NEXT_ACTION), true);
                }
                yield new LaneAction(LANE_REVISE_SCOPE, List.of(reasonCode(reason)), List.of(NextActionProtocol.ACTION_REQUEST_COORDINATION, NextActionProtocol.ACTION_RESPOND_COORDINATION), true);
            }
            case RESPOND_COORDINATION -> {
                // A capability owner may need to accept a request before the
                // implementation is ready.  Keep the exact response handle
                // required, but do not fence the owner's already-authorized
                // lane: claim/revision checks on each mutation remain the
                // final authority boundary.
                boolean capabilityOwner = result.containsKey(NextActionKeys.CAPABILITY_REQUEST_HANDLE) && result.containsKey(NextActionKeys.CAPABILITY);
                yield new LaneAction(LANE_REVIEW_CONTRACT,
                        List.of(reasonCode(reason)),
                        capabilityOwner ? List.of(NextActionProtocol.ACTION_REQUEST_COORDINATION, NextActionProtocol.ACTION_RESPOND_COORDINATION, NextActionProtocol.TOOL_READ_FILE, NextActionProtocol.TOOL_APPLY_PATCH, NextActionProtocol.TOOL_RUN_COMMAND, NextActionProtocol.TOOL_GET_NEXT_ACTION) : List.of(NextActionProtocol.ACTION_REQUEST_COORDINATION, NextActionProtocol.ACTION_RESPOND_COORDINATION),
                        true);
            }
            case REVIEW_DECISION -> new LaneAction(LANE_REVIEW_CONTRACT, List.of(reasonCode(reason)), List.of(NextActionProtocol.ACTION_RESPOND_COORDINATION, NextActionProtocol.TOOL_GET_NEXT_ACTION), true);
            case VALIDATE_IMPLEMENTATION -> {
                boolean workspaceAvailable = Boolean.TRUE.equals(result.get(NextActionKeys.VALIDATION_WORKSPACE_AVAILABLE));
                yield new LaneAction("VALIDATE",
                        List.of(reasonCode(reason)),
                        workspaceAvailable ? List.of(NextActionProtocol.TOOL_READ_FILE, NextActionProtocol.TOOL_RUN_COMMAND, NextActionProtocol.ACTION_RESPOND_COORDINATION, NextActionProtocol.TOOL_GET_NEXT_ACTION) : List.of(NextActionProtocol.ACTION_RESPOND_COORDINATION, NextActionProtocol.TOOL_GET_NEXT_ACTION),
                        true);
            }
            case RESPOND_TO_VALIDATION_REVISION -> new LaneAction(LANE_REVISE_SCOPE, List.of(reasonCode(reason)), List.of(NextActionProtocol.TOOL_READ_FILE, NextActionProtocol.TOOL_APPLY_PATCH, NextActionProtocol.TOOL_RUN_COMMAND, NextActionProtocol.TOOL_GET_NEXT_ACTION), true);
            case PUBLISH_CAPABILITY_IMPLEMENTATION -> new LaneAction(LANE_PUBLISH, List.of(reasonCode(reason)), List.of("publish_capability_implementation", NextActionProtocol.TOOL_GET_NEXT_ACTION), true);
            case FINISH_LANE -> new LaneAction(LANE_PUBLISH, List.of(reasonCode(reason)), List.of(NextActionProtocol.ACTION_FINISH_LANE, NextActionProtocol.TOOL_GET_NEXT_ACTION), true);
            case WAIT -> {
                if (Boolean.TRUE.equals(result.get(NextActionKeys.REVIEWER_PENDING))) {
                    // A reviewer with no producer to wait for may give its lane up and produce.
                    yield new LaneAction("WAIT", List.of(reasonCode(reason)), List.of(NextActionProtocol.TOOL_GET_NEXT_ACTION, NextActionProtocol.TOOL_CANCEL_LANE), true);
                }
                boolean capabilityImplementation = reason == AgentReason.IMPLEMENTATION_UNAVAILABLE && result.containsKey(NextActionKeys.CAPABILITY_REQUEST_HANDLE);
                yield new LaneAction(capabilityImplementation ? LANE_IMPLEMENT : "WAIT",
                        List.of(reasonCode(reason)),
                        capabilityImplementation ? List.of(NextActionProtocol.TOOL_READ_FILE, NextActionProtocol.TOOL_APPLY_PATCH, NextActionProtocol.TOOL_RUN_COMMAND, NextActionProtocol.TOOL_GET_NEXT_ACTION) : List.of(NextActionProtocol.TOOL_GET_NEXT_ACTION),
                        true);
            }
            case RETRY -> new LaneAction(LANE_RECOVER, List.of(reasonCode(reason)), List.of(NextActionProtocol.TOOL_ENSURE_SESSION, NextActionProtocol.TOOL_GET_NEXT_ACTION), true);
            case REQUEST_HUMAN_HELP -> new LaneAction("INTEGRATION_REPAIR", List.of(reasonCode(reason)), List.of(NextActionProtocol.TOOL_GET_NEXT_ACTION, NextActionProtocol.ACTION_REQUEST_COORDINATION), false);
        };
    }

    /**
     * A validated capability owner whose lane is still open. Unlike {@code implementationPending},
     * where the completion request is only a later step, here it is the action due now.
     */
    private static boolean ownerCompletionDue(Map<String, Object> result) {
        return Boolean.TRUE.equals(result.get(NextActionKeys.IMPLEMENTATION_VALIDATED));
    }

    private static boolean hasActionableReviewCandidates(Map<String, Object> result) {
        Object candidates = result.get(NextActionKeys.ACTIONABLE_REVIEW_CANDIDATES);
        return candidates instanceof List<?> list && !list.isEmpty();
    }

    private static Map<String, Object> executableAction(AgentResponse response, Map<String, Object> result) {
        AgentNextAction next = response.nextAction();
        if (next == null) {
            if (ownerCompletionDue(result) && result.get(NextActionKeys.COMPLETION_REQUEST) instanceof Map<?, ?> completionRequest
                    && completionRequest.get(NextActionKeys.TOOL) instanceof String tool && completionRequest.get(NextActionKeys.ARGUMENTS) instanceof Map<?, ?> arguments) {
                return Map.of(NextActionKeys.TOOL, tool, NextActionKeys.ARGUMENTS, arguments);
            }
            return null;
        }
        switch (next) {
            case VALIDATE_IMPLEMENTATION -> {
                Object kind = result.get(NextActionKeys.NEXT_PROTOCOL_KIND);
                Object payload = result.get(NextActionKeys.NEXT_PROTOCOL_PAYLOAD);
                if (NextActionProtocol.KIND_IMPLEMENTATION_VALIDATION.equals(kind) && payload instanceof Map<?, ?> map && "inspect".equals(map.get("result"))) {
                    return Map.of(NextActionKeys.TOOL, NextActionProtocol.ACTION_RESPOND_COORDINATION, NextActionKeys.ARGUMENTS, Map.of(NextActionKeys.KIND, kind, NextActionKeys.PAYLOAD, payload));
                }
                // After inspection, accepted versus revision_required is the provider's decision.
                return null;
            }
            case RESPOND_COORDINATION -> {
                Object kind = result.get(NextActionKeys.NEXT_PROTOCOL_KIND);
                Object payload = result.get(NextActionKeys.NEXT_PROTOCOL_PAYLOAD);
                if (kind instanceof String && payload instanceof Map<?, ?>) {
                    if (NextActionProtocol.KIND_REVIEW_VALIDATION.equals(kind) && !isExecutableReviewValidationPayload((Map<?, ?>) payload)) {
                        return null;
                    }
                    return Map.of(NextActionKeys.TOOL, NextActionProtocol.ACTION_RESPOND_COORDINATION, NextActionKeys.ARGUMENTS, Map.of(NextActionKeys.KIND, kind, NextActionKeys.PAYLOAD, payload));
                }
                return null;
            }
            case FINISH_LANE, PUBLISH_CAPABILITY_IMPLEMENTATION -> {
                Map<String, Object> arguments = new LinkedHashMap<>();
                putResult(arguments, result.get(NextActionKeys.NEXT_PROTOCOL_PAYLOAD), null);
                return Map.of(NextActionKeys.TOOL, next.value(), NextActionKeys.ARGUMENTS, arguments);
            }
            case REQUEST_COORDINATION -> {
                Object kind = result.get(NextActionKeys.NEXT_PROTOCOL_KIND);
                Object payload = result.get(NextActionKeys.NEXT_PROTOCOL_PAYLOAD);
                if (kind instanceof String && payload instanceof Map<?, ?>) {
                    return Map.of(NextActionKeys.TOOL, NextActionProtocol.ACTION_REQUEST_COORDINATION, NextActionKeys.ARGUMENTS, Map.of(NextActionKeys.KIND, kind, NextActionKeys.PAYLOAD, payload));
                }
                // A dependency declaration does not contain enough semantic
                // information to manufacture a capability contract.  Leave
                // the choice with the provider until it supplies the complete
                // typed payload required by the strict MCP schema.
                return null;
            }
            case ENSURE_SESSION -> {
                // The task a session without a lane announces is its own to choose; empty
                // arguments here were taken as the exact call to make.
                return claimsRequired(result) ? Map.of(NextActionKeys.TOOL, NextActionProtocol.TOOL_ENSURE_SESSION)
                        : Map.of(NextActionKeys.TOOL, NextActionProtocol.TOOL_ENSURE_SESSION, NextActionKeys.ARGUMENTS, Map.of());
            }
            case RETRY -> {
                Map<String, Object> arguments = NextActionProtocol.REASON_SNAPSHOT_COMPLETION_EVIDENCE_STALE.equals(result.get(NextActionKeys.REASON)) ? Map.of(NextActionKeys.COMPLETION_REQUESTED, true) : Map.of();
                return Map.of(NextActionKeys.TOOL, NextActionProtocol.TOOL_GET_NEXT_ACTION, NextActionKeys.ARGUMENTS, arguments);
            }
            case WAIT -> {
                // An owner waiting out its proposal's answer period asked to finish. Repeating
                // that request is what turns the passed deadline into the finish action.
                Map<String, Object> arguments = result.get(LaneCompletionProjection.REQUESTER_ANSWER_DEADLINE) != null ? Map.of(NextActionKeys.COMPLETION_REQUESTED, true) : Map.of();
                return Map.of(NextActionKeys.TOOL, NextActionProtocol.TOOL_GET_NEXT_ACTION, NextActionKeys.ARGUMENTS, arguments);
            }
            default -> {
                return null;
            }
        }
    }

    private static boolean isExecutableReviewValidationPayload(Map<?, ?> payload) {
        Object result = payload.get("result");
        return result instanceof String value && ("accept".equalsIgnoreCase(value) || "accepted".equalsIgnoreCase(value) || "reject".equalsIgnoreCase(value) || "rejected".equalsIgnoreCase(value));
    }

    private static String laneState(Map<String, Object> result) {
        Map<?, ?> intent = firstIntent(result);
        Object state = intent.get(NextActionKeys.STATUS);
        if (state instanceof String value) {
            return value;
        }
        Object participants = result.get(NextActionKeys.PARTICIPANTS);
        if (participants instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> participant && participant.get(NextActionKeys.STATE) instanceof String value) {
                    return value;
                }
            }
        }
        return "";
    }

    private static boolean claimsRequired(Map<String, Object> result) {
        return Boolean.TRUE.equals(result.get(NextActionKeys.CLAIMS_REQUIRED));
    }

    private static String reasonCode(AgentReason reason) {
        return reason == null ? "none" : reason.value();
    }

    /**
     * Adds the stable workflow action envelope to a response.
     *
     * @param request  exact lane request
     * @param response response derived by the lane service
     * @return response containing workflow action metadata
     */
    public AgentResponse decorate(NextActionRequest request, AgentResponse response) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        Map<String, Object> result = new LinkedHashMap<>();
        putResult(result, response.result(), "value");

        LaneAction action = actionFor(response, result);
        String actionId = actionId(request, response, result, action);
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put(NextActionKeys.ACTION_ID, actionId);
        workflow.put("type", action.type());
        // The envelope describes the action; the facts it was derived from stay in the enclosing
        // result once. Only the recovery deadline is repeated, because agents are told to pace
        // recovery polling from the envelope alone.
        if (result.get(NextActionKeys.RECOVERY_DEADLINE_EPOCH_MILLIS) != null) {
            workflow.put(NextActionKeys.RECOVERY_DEADLINE_EPOCH_MILLIS, result.get(NextActionKeys.RECOVERY_DEADLINE_EPOCH_MILLIS));
        }
        if (result.get(LaneCompletionProjection.REQUESTER_ANSWER_DEADLINE) != null) {
            workflow.put(LaneCompletionProjection.REQUESTER_ANSWER_DEADLINE, result.get(LaneCompletionProjection.REQUESTER_ANSWER_DEADLINE));
        }
        // A session without a lane has no lane context; the first listed intent is someone else's.
        Map<?, ?> laneContext = claimsRequired(result) ? Map.of() : firstIntent(result);
        if (laneContext.get(NextActionKeys.WORK_GROUP_ID) != null) {
            workflow.put(NextActionKeys.WORK_GROUP_ID, laneContext.get(NextActionKeys.WORK_GROUP_ID));
        }
        if (laneContext.get(NextActionKeys.INTENT_ID) != null) {
            workflow.put(NextActionKeys.LANE_ID, laneContext.get(NextActionKeys.INTENT_ID));
        }
        workflow.put("blockers", action.blockers());
        workflow.put("permittedOperations", action.permittedOperations());
        workflow.put("retrySafe", action.retrySafe());
        workflow.put("retryAfterMs", RETRY_AFTER_MS);
        workflow.put("maxUnchangedPolls", "WAIT".equals(action.type()) ? MAX_UNCHANGED_WAIT_POLLS : MAX_UNCHANGED_POLLS);
        workflow.put(NextActionKeys.DELIVERY, NextActionProtocol.DELIVERY_AT_LEAST_ONCE);
        workflow.put(NextActionKeys.ACKNOWLEDGEMENT_REQUIRED, true);
        Map<String, Object> executable = executableAction(response, result);
        if (executable != null) {
            workflow.put("recommendedTool", executable.get(NextActionKeys.TOOL));
            if (executable.get(NextActionKeys.ARGUMENTS) != null) {
                workflow.put(NextActionKeys.ARGUMENTS, executable.get(NextActionKeys.ARGUMENTS));
            }
        }
        if (result.get(NextActionKeys.REVIEW_DECISION) instanceof Map<?, ?> decision) {
            workflow.put("decision", decision);
        }
        result.put("workflow", workflow);
        result.put(NextActionKeys.ACTION_ID, actionId);
        result.put(NextActionKeys.DELIVERY, NextActionProtocol.DELIVERY_AT_LEAST_ONCE);
        result.put(NextActionKeys.ACKNOWLEDGEMENT_REQUIRED, true);
        return new AgentResponse(response.status(), response.reason(), response.nextAction(), result);
    }

    /**
     * Holds the reducer's normalized action and its explicit permissions.
     */
    private record LaneAction(String type, List<String> blockers, List<String> permittedOperations, boolean retrySafe) {

        private LaneAction {
            blockers = List.copyOf(blockers);
            permittedOperations = List.copyOf(permittedOperations);
        }
    }
}
