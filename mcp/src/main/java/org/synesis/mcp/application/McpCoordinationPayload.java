package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpToolArguments.nullableStringList;
import static org.synesis.mcp.application.McpToolArguments.optionalLongArgument;
import static org.synesis.mcp.application.McpToolArguments.optionalUuidArgument;
import static org.synesis.mcp.application.McpToolArguments.strings;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
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

    /** The request kinds, as a refusal of an unknown kind names them. Each has a case in {@link #normalizeStrictCoordination}. */
    private static final List<String> REQUEST_KINDS = List.of("contract_publish", "contract_bind", "contract_change_request", "collaboration_status", "scope_revision", "handoff", "work_group_join",
            "work_group_create", "continuation");

    /** The response kinds, as a refusal of an unknown kind names them. Each has a case in {@link #normalizeStrictResponse}. */
    private static final List<String> RESPONSE_KINDS = List.of("contract_change_response", "coordination_response", "inbox_acknowledge", "inbox_resolve", "implementation_validation", "review_validation");

    /** The fields a capability contract has, all of them required and no others allowed. */
    private static final List<String> CONTRACT_FIELDS = List.of("purpose", "inputs", "output", "requiredBehavior", "errorSemantics", "sideEffects", "invariants", "compatibility", "performance", "concurrency",
            "acceptanceTests");

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
            case "contract_publish" -> List.of("paths", "contract", "answers");
            case "contract_bind" -> List.of("capability", "ownerPath", "revision");
            case "contract_change_request" -> List.of("ownerPath", "need");
            case "collaboration_status" -> List.of();
            case "scope_revision" -> List.of("intentId", "selectors", "proposal");
            case "handoff" -> List.of("intentId", "targetParticipant", "proposal", "artifact");
            case "work_group_join" -> List.of("workGroupId", "grantId", "intentId", "claimEpoch", "targetParticipant", "proposal", "snapshotId", "reviewedIntentId", "reviewedParticipantId", "reviewerParticipant");
            case "work_group_create" -> List.of("workGroupId", "goal", "acceptance", "reviewPolicy");
            case "continuation" -> payload.containsKey("grantId") ? List.of("grantId", "intentId", "claimEpoch") : List.of("intentId", "claimEpoch");
            default -> throw new RefusedPayloadException("UNKNOWN_COORDINATION_KIND", "allowedKinds", REQUEST_KINDS);
        };
        for (String key : payload.keySet()) {
            if (!allowed.contains(key)) {
                throw new RefusedPayloadException("COORDINATION_FIELD_NOT_ALLOWED:" + key, "allowedFields", allowed);
            }
        }
        List<String> required = switch (kind) {
            case "contract_publish" -> List.of("paths", "contract");
            case "contract_bind" -> List.of("capability", "ownerPath", "revision");
            case "contract_change_request" -> List.of("ownerPath", "need");
            case "scope_revision" -> List.of("intentId", "selectors", "proposal");
            case "handoff" -> List.of("intentId", "targetParticipant", "proposal");
            case "work_group_join" -> payload.containsKey("grantId") ? List.of("workGroupId", "grantId", "intentId", "claimEpoch", "targetParticipant") : List.of("workGroupId", "intentId", "proposal");
            case "work_group_create" -> List.of("workGroupId", "goal", "acceptance");
            case "continuation" -> payload.containsKey("grantId") ? List.of("grantId", "intentId", "claimEpoch") : List.of("intentId", "claimEpoch");
            default -> List.of();
        };
        requireFields(payload, required, "COORDINATION_FIELD_REQUIRED:");
        if ("contract_bind".equals(kind)) {
            requirePositiveRevision(payload.get("revision"), "revision");
        }
        if ("work_group_join".equals(kind)) {
            requireMatchingAlias(payload, "intentId", "reviewedIntentId");
            requireMatchingAlias(payload, "targetParticipant", "reviewerParticipant");
            requireOptionalText(payload, "reviewedParticipantId");
            requireOptionalText(payload, "reviewerParticipant");
        }
        if ("contract_bind".equals(kind) && (!(payload.get("capability") instanceof String capability) || capability.isBlank())) {
            throw new IllegalArgumentException("COORDINATION_FIELD_REQUIRED:capability");
        }
        if ("contract_change_request".equals(kind) && (!(payload.get("need") instanceof String need) || need.isBlank())) {
            throw new IllegalArgumentException("COORDINATION_FIELD_REQUIRED:need");
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
            case "contract_publish" -> {
                CapabilityContract contract = parseContract(payload.get("contract"));
                if (contract == null) {
                    throw new RefusedPayloadException("CAPABILITY_CONTRACT_INVALID", "contractFields", CONTRACT_FIELDS, contractDefect(payload.get("contract")));
                }
                yield new McpCoordinationRequest.PublishContract(contractPaths(payload.get("paths")), contract, uuids(payload.get("answers"), "answers"));
            }
            case "contract_bind" -> new McpCoordinationRequest.BindContract(String.valueOf(payload.get("capability")), ownerPath(payload.get("ownerPath")),
                    ((Number) payload.get("revision")).longValue());
            case "contract_change_request" -> new McpCoordinationRequest.RequestChange(ownerPath(payload.get("ownerPath")), String.valueOf(payload.get("need")));
            case "collaboration_status" -> new McpCoordinationRequest.Status();
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
            case "contract_change_response" -> List.of("changeRequest", "decision", "reason");
            case "coordination_response" -> List.of("coordinationRequest", "coordinationStatus", "proposal");
            case "inbox_acknowledge" -> List.of("inboxItemId");
            case "inbox_resolve" -> List.of("inboxItemId", "resolution", "proposal");
            case "implementation_validation" -> List.of("inboxItemId", "capabilityRequestHandle", "implementationRevision", "result", "reason", "contractField", "failedAcceptanceTests");
            case "review_validation" -> List.of("grantId", "snapshotId", "intentId", "claimEpoch", "result", "reason", "reviewedIntentId", "reviewedParticipantId", "reviewerParticipant");
            default -> throw new RefusedPayloadException("UNKNOWN_COORDINATION_RESPONSE_KIND", "allowedKinds", RESPONSE_KINDS);
        };
        for (String key : payload.keySet()) {
            if (!allowed.contains(key)) {
                throw new RefusedPayloadException("COORDINATION_RESPONSE_FIELD_NOT_ALLOWED:" + key, "allowedFields", allowed);
            }
        }
        List<String> required = switch (kind) {
            case "contract_change_response" -> List.of("changeRequest", "decision");
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
        if ("contract_change_response".equals(kind)) {
            Object decision = payload.get("decision");
            if (!(decision instanceof String value) || !("accepted".equals(value) || "declined".equals(value))) {
                throw new RefusedPayloadException("COORDINATION_RESPONSE_FIELD_INVALID:decision", "allowedValues", List.of("accepted", "declined"));
            }
            if ("declined".equals(decision) && (!(payload.get("reason") instanceof String reasonText) || reasonText.isBlank())) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_REASON_REQUIRED");
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
                // A verdict that sends the work back rests on the contract the consumer bound
                // to, and says on which of its fields (ADR-0092). A point the owner declined
                // to put into the contract is not grounds.
                if (!(payload.get("contractField") instanceof String field) || !CONTRACT_FIELDS.contains(field)) {
                    throw new RefusedPayloadException("COORDINATION_RESPONSE_FIELD_REQUIRED:contractField", "contractFields", CONTRACT_FIELDS,
                            "name the field of the contract that the implementation breaks");
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
        if ("implementation_validation".equals(kind) && payload.get("contractField") instanceof String field && reason != null) {
            reason = field + ": " + reason;
        }
        String verdictReason = reason;
        return switch (kind) {
            case "implementation_validation" -> new McpCoordinationResponse.ImplementationValidation(String.valueOf(payload.get("capabilityRequestHandle")),
                    String.valueOf(payload.get("result")),
                    verdictReason,
                    optionalLongArgument(payload, "implementationRevision", 1).intValue(),
                    strings(payload.get("failedAcceptanceTests")));
            case "review_validation" -> new McpCoordinationResponse.ReviewValidation(optionalUuidArgument(payload, "grantId"),
                    String.valueOf(payload.get("snapshotId")),
                    optionalUuidArgument(payload, "intentId"),
                    optionalLongArgument(payload, "claimEpoch", 1),
                    String.valueOf(payload.get("result")),
                    reason);
            case "contract_change_response" -> new McpCoordinationResponse.AnswerChange(optionalUuidArgument(payload, "changeRequest"), "accepted".equals(payload.get("decision")), verdictReason);
            case "coordination_response" -> new McpCoordinationResponse.RespondToRequest(optionalUuidArgument(payload, "coordinationRequest"),
                    (String) payload.get("coordinationRequest"),
                    status(payload, "coordinationStatus"),
                    String.valueOf(payload.getOrDefault("proposal", "")));
            case "inbox_acknowledge" -> new McpCoordinationResponse.AcknowledgeInbox(optionalUuidArgument(payload, "inboxItemId"));
            case "inbox_resolve" -> new McpCoordinationResponse.ResolveInbox(optionalUuidArgument(payload, "inboxItemId"),
                    status(payload, "resolution"),
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
            case "contract_change_response" -> requireUuid(payload, "changeRequest");
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

    /** Reads the files or subtrees a contract covers. A path ending in a slash names a subtree. */
    private static List<ResourceSelector> contractPaths(Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw new IllegalArgumentException("COORDINATION_FIELD_REQUIRED:paths");
        }
        List<ResourceSelector> paths = new java.util.ArrayList<>();
        for (Object item : items) {
            try {
                String path = (String) item;
                paths.add(path.endsWith("/") ? ResourceSelector.pathSubtree(path) : ResourceSelector.pathExact(path));
            } catch (ClassCastException | IllegalArgumentException notARepositoryPath) {
                throw new IllegalArgumentException("CONTRACT_PATH_MUST_BE_REPOSITORY_RELATIVE");
            }
        }
        return paths;
    }

    /** Reads an optional list of identifiers. */
    private static List<UUID> uuids(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        try {
            return ((List<?>) value).stream().map(item -> UUID.fromString((String) item)).toList();
        } catch (ClassCastException | IllegalArgumentException notIdentifiers) {
            throw new IllegalArgumentException("COORDINATION_FIELD_INVALID:" + field);
        }
    }

    /** Reads the file a consumer builds on, whose claimant is the owner. */
    private static ResourceSelector ownerPath(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("COORDINATION_FIELD_REQUIRED:ownerPath");
        }
        try {
            // The cast fails for a non-string as the selector fails for an escaping path: one refusal for both.
            return ResourceSelector.pathExact((String) value);
        } catch (ClassCastException | IllegalArgumentException notARepositoryFile) {
            throw new IllegalArgumentException("OWNER_PATH_MUST_BE_A_REPOSITORY_RELATIVE_FILE");
        }
    }

    /** Reads one sentence where a list is expected as a list of one: a model writes either. */
    private static Object textAsList(Object value) {
        return value instanceof String text ? List.of(text) : value;
    }

    /**
     * Says what is wrong with a contract that {@link #parseContract} refused. An agent told only
     * the field names sent nine contracts in a row with two of them as strings.
     *
     * @param contractObj the refused contract value
     * @return the first defect found
     */
    static String contractDefect(Object contractObj) {
        if (!(contractObj instanceof Map<?, ?> map)) {
            return "contract must be an object";
        }
        List<String> missing = CONTRACT_FIELDS.stream().filter(field -> !map.containsKey(field)).toList();
        if (!missing.isEmpty()) {
            return "contract lacks " + missing;
        }
        List<String> unknown = map.keySet().stream().map(String::valueOf).filter(field -> !CONTRACT_FIELDS.contains(field)).toList();
        if (!unknown.isEmpty()) {
            return "contract has fields it does not take: " + unknown;
        }
        for (String field : CONTRACT_FIELDS) {
            Object value = map.get(field);
            boolean list = "requiredBehavior".equals(field) || "acceptanceTests".equals(field);
            if (list && !(value instanceof String) && !(value instanceof List<?> items && items.stream().allMatch(String.class::isInstance))) {
                return field + " must be an array of strings";
            }
            if (!list && !(value instanceof String)) {
                return field + " must be a string";
            }
        }
        return "a contract field is blank or too long";
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
        if (map.size() != CONTRACT_FIELDS.size() || map.keySet().stream().anyMatch(key -> !(key instanceof String field) || !CONTRACT_FIELDS.contains(field))) {
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
                || !(textAsList(map.get("requiredBehavior")) instanceof List<?> rawBehavior) || !(textAsList(map.get("acceptanceTests")) instanceof List<?> rawTests)) {
            return null;
        }
        List<String> requiredBehavior;
        List<String> acceptanceTests;
        try {
            requiredBehavior = Objects.requireNonNullElse(nullableStringList(rawBehavior, "requiredBehavior"), List.of());
            acceptanceTests = Objects.requireNonNullElse(nullableStringList(rawTests, "acceptanceTests"), List.of());
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

    /**
     * A payload the schema refuses, with what it would have accepted in that place: the fields of
     * the kind, the kinds, the fields of a contract, the values of a status. An agent told only
     * what was wrong had to guess the rest.
     */
    static final class RefusedPayloadException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private final transient String acceptedName;
        private final transient List<String> accepted;
        private final transient String detail;

        RefusedPayloadException(String code, String acceptedName, List<String> accepted) {
            this(code, acceptedName, accepted, null);
        }

        RefusedPayloadException(String code, String acceptedName, List<String> accepted, String detail) {
            super(code);
            this.acceptedName = acceptedName;
            this.accepted = List.copyOf(accepted);
            this.detail = detail;
        }

        /** What exactly was wrong, when the code alone does not say; otherwise {@code null}. */
        String detail() {
            return detail;
        }

        /** Result key under which the accepted values are reported, such as {@code allowedFields}. */
        String acceptedName() {
            return acceptedName;
        }

        List<String> accepted() {
            return accepted;
        }
    }

    /** Reads a request status in any letter case; a model writes {@code accepted} as readily as {@code ACCEPTED}. */
    private static CoordinationRequest.Status status(Map<String, Object> payload, String key) {
        String given = String.valueOf(payload.get(key));
        for (CoordinationRequest.Status status : CoordinationRequest.Status.values()) {
            if (status.name().equalsIgnoreCase(given)) {
                return status;
            }
        }
        throw new RefusedPayloadException("COORDINATION_FIELD_INVALID:" + key, "allowedValues", java.util.Arrays.stream(CoordinationRequest.Status.values()).map(Enum::name).toList());
    }
}
