package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpToolArguments.nullableStringList;
import static org.synesis.mcp.application.McpToolArguments.optionalLongArgument;
import static org.synesis.mcp.application.McpToolArguments.optionalUuidArgument;
import static org.synesis.mcp.application.McpToolArguments.strings;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;

/**
 * Validates and normalizes the strict {@code request_coordination} and {@code respond_coordination}
 * payload schemas, and decodes the capability contract and review policy objects they carry.
 *
 * <p>Kept apart from {@link McpProtocolHandler} so the accepted kinds, the allowed and required
 * fields, and the rejection codes form one inspectable schema rather than part of tool dispatch.
 * Normalization only reads the payload into typed operations; authorization stays with the
 * application services.
 */
final class McpCoordinationPayload {

    /** Utility class; these rules are exposed through static operations. */
    private McpCoordinationPayload() {
    }

    /**
     * Validates the strict coordination request schema and reads it into the operation its kind dispatches to.
     *
     * @param arguments top-level request_coordination arguments
     * @return the typed operation the kind dispatches to
     * @throws IllegalArgumentException when the kind is unknown or the payload has a missing, extra, or inconsistent field
     */
    @SuppressWarnings({"unchecked"})
    static McpCoordinationRequest normalizeStrictCoordination(Map<String, Object> arguments) {
        Object kindValue = arguments.get("kind");
        Object payloadValue = arguments.get("payload");
        if (!(kindValue instanceof String kind) || !(payloadValue instanceof Map<?, ?> rawPayload)) {
            throw new IllegalArgumentException("COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD");
        }
        Map<String, Object> payload = (Map<String, Object>) rawPayload;
        List<String> allowed = switch (kind) {
            case "capability_request" -> List.of("capability", "contract", "capabilityRequestHandle", "revisionResponse", "contractRevision", "ownerAuthorityLineageId");
            case "collaboration_status" -> List.of();
            case "contract_proposal" -> List.of("contractId", "body", "selectors", "revision");
            case "contract_request" -> List.of("conflictingIntentId", "proposal", "contractId", "revision");
            case "scope_revision" -> List.of("intentId", "selectors", "proposal");
            case "handoff" -> List.of("intentId", "targetParticipant", "proposal", "artifact");
            case "work_group_join" -> List.of("workGroupId", "grantId", "intentId", "claimEpoch", "targetParticipant", "proposal", "snapshotId", "reviewedIntentId", "reviewedParticipantId", "reviewerParticipant");
            case "work_group_create" -> List.of("workGroupId", "goal", "acceptance", "reviewPolicy");
            case "continuation" -> payload.containsKey("grantId") ? List.of("grantId", "intentId", "claimEpoch") : List.of("intentId", "claimEpoch");
            default -> throw new IllegalArgumentException("UNKNOWN_COORDINATION_KIND");
        };
        for (String key : payload.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("COORDINATION_FIELD_NOT_ALLOWED:" + key);
            }
        }
        List<String> required = switch (kind) {
            case "capability_request" -> List.of("capability", "contract");
            case "contract_proposal" -> List.of("contractId", "body");
            case "contract_request" -> List.of("conflictingIntentId", "proposal");
            case "scope_revision" -> List.of("intentId", "selectors", "proposal");
            case "handoff" -> List.of("intentId", "targetParticipant", "proposal");
            case "work_group_join" -> payload.containsKey("grantId") ? List.of("workGroupId", "grantId", "intentId", "claimEpoch", "targetParticipant") : List.of("workGroupId", "intentId", "proposal");
            case "work_group_create" -> List.of("workGroupId", "goal", "acceptance");
            case "continuation" -> payload.containsKey("grantId") ? List.of("grantId", "intentId", "claimEpoch") : List.of("intentId", "claimEpoch");
            default -> List.of();
        };
        requireFields(payload, required, "COORDINATION_FIELD_REQUIRED:");
        if ("capability_request".equals(kind)) {
            boolean hasHandle = payload.containsKey("capabilityRequestHandle");
            boolean hasResponse = payload.containsKey("revisionResponse");
            if (hasHandle != hasResponse) {
                throw new IllegalArgumentException("CAPABILITY_REVISION_RESPONSE_REQUIRES_HANDLE_AND_RESPONSE");
            }
            if (hasResponse) {
                Object response = payload.get("revisionResponse");
                if (!(response instanceof String value) || !List.of("accept", "counter", "cancel").contains(value)) {
                    throw new IllegalArgumentException("CAPABILITY_REVISION_RESPONSE_INVALID");
                }
                requirePositiveRevision(payload.get("contractRevision"), "contractRevision");
            } else if (payload.containsKey("contractRevision")) {
                throw new IllegalArgumentException("CONTRACT_REVISION_REQUIRES_CAPABILITY_HANDLE");
            }
        }
        if ("work_group_join".equals(kind)) {
            requireMatchingAlias(payload, "intentId", "reviewedIntentId");
            requireMatchingAlias(payload, "targetParticipant", "reviewerParticipant");
            requireOptionalText(payload, "reviewedParticipantId");
            requireOptionalText(payload, "reviewerParticipant");
        }
        if ("capability_request".equals(kind) && (!(payload.get("capability") instanceof String capability) || capability.isBlank())) {
            throw new IllegalArgumentException("COORDINATION_FIELD_REQUIRED:capability");
        }
        if ("work_group_create".equals(kind)) {
            validateReviewPolicyPayload(payload.get("reviewPolicy"));
        }
        requireTypedRequestFields(kind, payload);
        return requestOf(kind, payload);
    }

    /**
     * Reads a validated request payload into the operation its kind dispatches to.
     *
     * @param kind    validated request kind
     * @param payload request payload that passed every schema check for {@code kind}
     * @return the typed operation
     * @throws IllegalArgumentException with {@code CAPABILITY_CONTRACT_INVALID} when a capability contract does not decode
     */
    private static McpCoordinationRequest requestOf(String kind, Map<String, Object> payload) {
        return switch (kind) {
            case "capability_request" -> {
                CapabilityContract contract = parseContract(payload.get("contract"));
                if (contract == null) {
                    throw new IllegalArgumentException("CAPABILITY_CONTRACT_INVALID");
                }
                yield new McpCoordinationRequest.DescribeCapability(String.valueOf(payload.get("capability")),
                        contract,
                        payload.get("capabilityRequestHandle") instanceof String value ? value : null,
                        payload.get("revisionResponse") instanceof String value ? value : null,
                        payload.get("ownerAuthorityLineageId") instanceof String value ? value : null,
                        payload.get("contractRevision") instanceof Number value ? value.longValue() : -1);
            }
            case "collaboration_status" -> new McpCoordinationRequest.Status();
            case "contract_proposal" -> new McpCoordinationRequest.PublishContract(optionalUuidArgument(payload, "contractId"),
                    String.valueOf(payload.get("body")),
                    strings(payload.get("selectors")));
            case "contract_request" -> new McpCoordinationRequest.OpenRequest(optionalUuidArgument(payload, "conflictingIntentId"),
                    CoordinationRequest.Kind.CONTRACT,
                    String.valueOf(payload.get("proposal")),
                    null);
            case "scope_revision" -> new McpCoordinationRequest.OpenRequest(optionalUuidArgument(payload, "intentId"),
                    CoordinationRequest.Kind.CONTRACT,
                    String.valueOf(payload.get("proposal")),
                    null);
            case "handoff" -> new McpCoordinationRequest.Handoff(optionalUuidArgument(payload, "intentId"),
                    String.valueOf(payload.get("targetParticipant")),
                    String.valueOf(payload.get("proposal")));
            case "work_group_join" -> payload.containsKey("grantId")
                    ? new McpCoordinationRequest.ConsumeLaneGrant(optionalUuidArgument(payload, "grantId"),
                            optionalUuidArgument(payload, "intentId"),
                            optionalLongArgument(payload, "claimEpoch", 1))
                    : new McpCoordinationRequest.OpenRequest(optionalUuidArgument(payload, "intentId"),
                            CoordinationRequest.Kind.REVIEW,
                            String.valueOf(payload.get("proposal")),
                            payload.get("snapshotId") instanceof String snapshotId ? snapshotId : null);
            case "work_group_create" -> new McpCoordinationRequest.CreateWorkGroup(optionalUuidArgument(payload, "workGroupId"),
                    String.valueOf(payload.get("goal")),
                    String.valueOf(payload.get("acceptance")),
                    payload.get("reviewPolicy"));
            case "continuation" -> new McpCoordinationRequest.ContinueLane(
                    payload.get("grantId") instanceof String blank && blank.isBlank() ? null : optionalUuidArgument(payload, "grantId"),
                    optionalUuidArgument(payload, "intentId"),
                    optionalLongArgument(payload, "claimEpoch", 1));
            default -> throw new IllegalStateException("unvalidated coordination kind: " + kind);
        };
    }

    /**
     * Validates the strict coordination-response schema before service invocation.
     *
     * @param arguments top-level respond_coordination arguments
     * @return the typed operation the kind dispatches to
     * @throws IllegalArgumentException when the kind is unknown or the payload fails validation
     */
    @SuppressWarnings({"unchecked", "ExtractMethodRecommender"})
    static McpCoordinationResponse normalizeStrictResponse(Map<String, Object> arguments) {
        Object kindValue = arguments.get("kind");
        Object payloadValue = arguments.get("payload");
        if (!(kindValue instanceof String kind) || !(payloadValue instanceof Map<?, ?> rawPayload)) {
            throw new IllegalArgumentException("COORDINATION_RESPONSE_REQUIRES_KIND_AND_PAYLOAD");
        }
        Map<String, Object> payload = (Map<String, Object>) rawPayload;
        List<String> allowed = switch (kind) {
            case "capability_response" -> List.of("capabilityRequestHandle", "response", "contractRevision", "revision", "reason");
            case "coordination_response" -> List.of("coordinationRequest", "coordinationStatus", "proposal");
            case "inbox_acknowledge" -> List.of("inboxItemId");
            case "inbox_resolve" -> List.of("inboxItemId", "resolution", "proposal");
            case "implementation_validation" -> List.of("inboxItemId", "capabilityRequestHandle", "implementationRevision", "result", "reason", "failedAcceptanceTests");
            case "review_validation" -> List.of("grantId", "snapshotId", "intentId", "claimEpoch", "result", "reason", "reviewedIntentId", "reviewedParticipantId", "reviewerParticipant");
            default -> throw new IllegalArgumentException("UNKNOWN_COORDINATION_RESPONSE_KIND");
        };
        for (String key : payload.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_FIELD_NOT_ALLOWED:" + key);
            }
        }
        List<String> required = switch (kind) {
            case "capability_response" -> List.of("capabilityRequestHandle", "response", "contractRevision");
            case "coordination_response" -> List.of("coordinationRequest", "coordinationStatus");
            case "inbox_acknowledge" -> List.of("inboxItemId");
            case "inbox_resolve" -> List.of("inboxItemId", "resolution");
            case "implementation_validation" -> List.of("inboxItemId", "capabilityRequestHandle", "implementationRevision", "result");
            case "review_validation" -> List.of("grantId", "snapshotId", "intentId", "claimEpoch", "result");
            default -> List.of();
        };
        requireFields(payload, required, "COORDINATION_RESPONSE_FIELD_REQUIRED:");
        if ("review_validation".equals(kind)) {
            requireMatchingAlias(payload, "intentId", "reviewedIntentId");
            requireOptionalText(payload, "reviewedParticipantId");
            requireOptionalText(payload, "reviewerParticipant");
        }
        if ("capability_response".equals(kind)) {
            Object response = payload.get("response");
            if (!(response instanceof String value) || !("accept".equals(value) || "revise".equals(value) || "reject".equals(value))) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_INVALID_RESPONSE");
            }
            requirePositiveRevision(payload.get("contractRevision"), "contractRevision");
            if ("revise".equals(response) && !(payload.get("revision") instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_REVISION_REQUIRED");
            }
        }
        if ("implementation_validation".equals(kind)) {
            Object result = payload.get("result");
            if (!(result instanceof String value) || !("inspect".equals(value) || "accepted".equals(value) || "revision_required".equals(value))) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_INVALID_RESULT");
            }
            if ("revision_required".equals(result)) {
                Object reason = payload.get("reason");
                if (!(reason instanceof String reasonText) || reasonText.isBlank()) {
                    throw new IllegalArgumentException("COORDINATION_RESPONSE_REASON_REQUIRED");
                }
            }
        }
        if ("review_validation".equals(kind)) {
            Object result = payload.get("result");
            if (!(result instanceof String reviewResult) || !("accept".equalsIgnoreCase(reviewResult) || "accepted".equalsIgnoreCase(reviewResult) || "reject".equalsIgnoreCase(reviewResult)
                    || "rejected".equalsIgnoreCase(reviewResult))) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_INVALID_RESULT");
            }
            if (("reject".equalsIgnoreCase(String.valueOf(result)) || "rejected".equalsIgnoreCase(String.valueOf(result))) && (!(payload.get("reason") instanceof String reason) || reason.isBlank())) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_REASON_REQUIRED");
            }
        }
        requireTypedResponseFields(kind, payload);
        return responseOf(kind, payload);
    }

    /**
     * Reads a validated response payload into the operation its kind dispatches to.
     *
     * @param kind    validated response kind
     * @param payload response payload that passed every schema check for {@code kind}
     * @return the typed operation
     * @throws IllegalArgumentException when {@code coordinationStatus} or {@code resolution} names no status
     */
    private static McpCoordinationResponse responseOf(String kind, Map<String, Object> payload) {
        String reason = payload.get("reason") == null ? null : String.valueOf(payload.get("reason"));
        return switch (kind) {
            case "implementation_validation" -> new McpCoordinationResponse.ImplementationValidation(String.valueOf(payload.get("capabilityRequestHandle")),
                    String.valueOf(payload.get("result")),
                    reason,
                    optionalLongArgument(payload, "implementationRevision", 1).intValue(),
                    strings(payload.get("failedAcceptanceTests")));
            case "review_validation" -> new McpCoordinationResponse.ReviewValidation(optionalUuidArgument(payload, "grantId"),
                    String.valueOf(payload.get("snapshotId")),
                    optionalUuidArgument(payload, "intentId"),
                    optionalLongArgument(payload, "claimEpoch", 1),
                    String.valueOf(payload.get("result")),
                    reason);
            case "capability_response" -> new McpCoordinationResponse.RespondToCapability(String.valueOf(payload.get("capabilityRequestHandle")),
                    String.valueOf(payload.get("response")),
                    parseContract(payload.get("revision")),
                    reason,
                    optionalLongArgument(payload, "contractRevision", 1));
            case "coordination_response" -> new McpCoordinationResponse.RespondToRequest(optionalUuidArgument(payload, "coordinationRequest"),
                    (String) payload.get("coordinationRequest"),
                    CoordinationRequest.Status.valueOf(String.valueOf(payload.get("coordinationStatus"))),
                    String.valueOf(payload.getOrDefault("proposal", "")));
            case "inbox_acknowledge" -> new McpCoordinationResponse.AcknowledgeInbox(optionalUuidArgument(payload, "inboxItemId"));
            case "inbox_resolve" -> new McpCoordinationResponse.ResolveInbox(optionalUuidArgument(payload, "inboxItemId"),
                    CoordinationRequest.Status.valueOf(String.valueOf(payload.get("resolution"))),
                    String.valueOf(payload.getOrDefault("proposal", "")));
            default -> throw new IllegalStateException("unvalidated coordination response kind: " + kind);
        };
    }

    /**
     * Requires every named payload key to be present and not JSON {@code null}.
     *
     * @param payload     request or response payload
     * @param required    keys that must carry a value
     * @param errorPrefix rejection code, completed with the first missing key
     * @throws IllegalArgumentException when a required key is missing or {@code null}
     */
    private static void requireFields(Map<String, Object> payload, List<String> required, String errorPrefix) {
        for (String key : required) {
            if (payload.get(key) == null) {
                throw new IllegalArgumentException(errorPrefix + key);
            }
        }
    }

    /**
     * Rejects a request field that dispatch reads as an identifier or number but that holds another type.
     *
     * @param kind    validated request kind
     * @param payload request payload whose required fields are present
     * @throws IllegalArgumentException with {@code COORDINATION_FIELD_INVALID:<key>} for the first bad field
     */
    private static void requireTypedRequestFields(String kind, Map<String, Object> payload) {
        switch (kind) {
            case "contract_proposal" -> requireUuid(payload, "contractId");
            case "contract_request" -> requireUuid(payload, "conflictingIntentId");
            case "scope_revision", "handoff" -> requireUuid(payload, "intentId");
            case "work_group_create" -> requireUuid(payload, "workGroupId");
            case "work_group_join" -> {
                requireUuid(payload, "intentId");
                if (payload.containsKey("grantId")) {
                    requireUuid(payload, "grantId");
                    requireInteger(payload, "claimEpoch");
                }
            }
            case "continuation" -> {
                // A blank grant is read as no grant.
                if (!(payload.get("grantId") instanceof String grantId && grantId.isBlank())) {
                    requireUuid(payload, "grantId");
                }
                requireUuid(payload, "intentId");
                requireInteger(payload, "claimEpoch");
            }
            default -> {
            }
        }
    }

    /**
     * Rejects a response field that dispatch reads as an identifier or number but that holds another type.
     *
     * @param kind    validated response kind
     * @param payload response payload whose required fields are present
     * @throws IllegalArgumentException with {@code COORDINATION_FIELD_INVALID:<key>} for the first bad field
     */
    private static void requireTypedResponseFields(String kind, Map<String, Object> payload) {
        switch (kind) {
            case "coordination_response" -> requireUuid(payload, "coordinationRequest");
            case "inbox_acknowledge", "inbox_resolve" -> requireUuid(payload, "inboxItemId");
            case "implementation_validation" -> requireInteger(payload, "implementationRevision");
            case "review_validation" -> {
                requireUuid(payload, "grantId");
                requireUuid(payload, "intentId");
                requireInteger(payload, "claimEpoch");
            }
            default -> {
            }
        }
    }

    private static void requireUuid(Map<String, Object> payload, String key) {
        try {
            optionalUuidArgument(payload, key);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("COORDINATION_FIELD_INVALID:" + key, invalid);
        }
    }

    private static void requireInteger(Map<String, Object> payload, String key) {
        try {
            optionalLongArgument(payload, key, 1);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("COORDINATION_FIELD_INVALID:" + key, invalid);
        }
    }

    private static void requirePositiveRevision(Object value, String field) {
        if (!(value instanceof Number revision) || revision.longValue() < 1 || revision.doubleValue() != revision.longValue()) {
            throw new IllegalArgumentException("COORDINATION_FIELD_INVALID:" + field);
        }
    }

    /**
     * Ensures a supplied provider-facing alias exactly matches its canonical field.
     *
     * @param payload   request payload
     * @param canonical canonical field name
     * @param alias     provider-facing alias
     * @throws IllegalArgumentException when both fields are present and differ or either is blank
     */
    private static void requireMatchingAlias(Map<String, Object> payload, String canonical, String alias) {
        if (!payload.containsKey(alias) || !payload.containsKey(canonical)) {
            return;
        }
        Object canonicalValue = payload.get(canonical);
        Object aliasValue = payload.get(alias);
        if (!(canonicalValue instanceof String canonicalText) || canonicalText.isBlank() || !(aliasValue instanceof String aliasText) || aliasText.isBlank() || !canonicalText.equals(aliasText)) {
            throw new IllegalArgumentException("COORDINATION_ALIAS_MISMATCH:" + alias);
        }
    }

    /**
     * Validates that an optional payload field, when present, is nonblank text.
     *
     * @param payload request payload
     * @param key     field name
     * @throws IllegalArgumentException when the field is present but not nonblank text
     */
    private static void requireOptionalText(Map<String, Object> payload, String key) {
        if (payload.containsKey(key) && (!(payload.get(key) instanceof String value) || value.isBlank())) {
            throw new IllegalArgumentException("COORDINATION_FIELD_INVALID:" + key);
        }
    }

    /**
     * Validates the bounded two-boolean review policy accepted by work-group creation.
     *
     * @param rawPolicy untrusted review policy value, possibly {@code null}
     * @throws IllegalArgumentException when the policy is not an object with exactly boolean {@code mandatory} and {@code independenceRequired}
     */
    private static void validateReviewPolicyPayload(Object rawPolicy) {
        if (rawPolicy == null) {
            return;
        }
        if (!(rawPolicy instanceof Map<?, ?> policy)) {
            throw new IllegalArgumentException("REVIEW_POLICY_SCHEMA_INVALID");
        }
        if (policy.keySet().stream().anyMatch(key -> !"mandatory".equals(key) && !"independenceRequired".equals(key))) {
            throw new IllegalArgumentException("REVIEW_POLICY_FIELD_NOT_ALLOWED");
        }
        if (!(policy.get("mandatory") instanceof Boolean) || !(policy.get("independenceRequired") instanceof Boolean)) {
            throw new IllegalArgumentException("REVIEW_POLICY_FIELDS_REQUIRED");
        }
    }

    /**
     * Converts a validated review policy object into the domain policy value.
     *
     * @param rawPolicy untrusted review policy value, possibly {@code null}
     * @return the parsed policy, or {@link ReviewPolicy#none()} when absent
     * @throws IllegalArgumentException when the payload is invalid or the policy is unsupported
     */
    static ReviewPolicy parseReviewPolicy(Object rawPolicy) {
        if (rawPolicy == null) {
            return ReviewPolicy.none();
        }
        validateReviewPolicyPayload(rawPolicy);
        Map<?, ?> policy = (Map<?, ?>) rawPolicy;
        try {
            return new ReviewPolicy((Boolean) policy.get("mandatory"), (Boolean) policy.get("independenceRequired"));
        } catch (IllegalArgumentException invalidPolicy) {
            throw new IllegalArgumentException("REVIEW_POLICY_UNSUPPORTED", invalidPolicy);
        }
    }

    /**
     * Decodes a capability contract from the JSON-compatible contract object.
     *
     * @param contractObj untrusted contract value
     * @return validated contract or {@code null} when required fields are absent or invalid
     */
    static CapabilityContract parseContract(Object contractObj) {
        if (!(contractObj instanceof Map<?, ?> map)) {
            return null;
        }
        List<String> contractFields = List.of("purpose", "inputs", "output", "requiredBehavior", "errorSemantics", "sideEffects", "invariants", "compatibility", "performance", "concurrency", "acceptanceTests");
        if (map.size() != contractFields.size() || map.keySet().stream().anyMatch(key -> !(key instanceof String field) || !contractFields.contains(field))) {
            return null;
        }
        Object rawPurpose = map.get("purpose");
        Object rawInputs = map.get("inputs");
        Object rawOutput = map.get("output");
        Object rawErrorSemantics = map.get("errorSemantics");
        Object rawSideEffects = map.get("sideEffects");
        Object rawInvariants = map.get("invariants");
        Object rawCompatibility = map.get("compatibility");
        Object rawPerformance = map.get("performance");
        Object rawConcurrency = map.get("concurrency");
        if (!(rawPurpose instanceof String purpose) || !(rawInputs instanceof String inputs) || !(rawOutput instanceof String output)
                || !(rawErrorSemantics instanceof String errorSemantics) || !(rawSideEffects instanceof String sideEffects)
                || !(rawInvariants instanceof String invariants) || !(rawCompatibility instanceof String compatibility)
                || !(rawPerformance instanceof String performance) || !(rawConcurrency instanceof String concurrency)
                || !(map.get("requiredBehavior") instanceof List<?>) || !(map.get("acceptanceTests") instanceof List<?>)) {
            return null;
        }
        List<String> requiredBehavior;
        List<String> acceptanceTests;
        try {
            requiredBehavior = Objects.requireNonNullElse(nullableStringList(map.get("requiredBehavior"), "requiredBehavior"), List.of());
            acceptanceTests = Objects.requireNonNullElse(nullableStringList(map.get("acceptanceTests"), "acceptanceTests"), List.of());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        try {
            return new CapabilityContract(purpose,
                    inputs,
                    output,
                    requiredBehavior,
                    errorSemantics,
                    sideEffects,
                    invariants,
                    compatibility,
                    performance,
                    concurrency,
                    acceptanceTests);
        } catch (Exception ex) {
            return null;
        }
    }
}
