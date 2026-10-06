package org.synesis.mcp.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The authoritative, dependency-safe MCP wire and guidance catalog.
 *
 * <p>One immutable descriptor set drives wire compatibility, catalog content,
 * generated tools/list entries, and provider-facing guidance inputs. The guidance artifact digest
 * is intentionally computed by the renderer over its output and is never included in either catalog
 * identity. The catalog contains exactly ten tools; adding or removing a descriptor is a protocol
 * surface change and must update the corresponding contract tests.</p>
 *
 * @since 1.0
 */
public final class McpToolCatalog {

    /**
     * MCP session establishment and lane binding tool.
     */
    public static final String ENSURE_SESSION = "ensure_session";
    /**
     * MCP read tool.
     */
    public static final String READ_FILE = "read_file";
    /**
     * MCP revision-checked mutation tool.
     */
    public static final String APPLY_PATCH = "apply_patch";
    /**
     * MCP direct-argv command execution tool.
     */
    public static final String RUN_COMMAND = "run_command";
    /**
     * MCP durable inbox/action discovery tool.
     */
    public static final String GET_NEXT_ACTION = "get_next_action";
    /**
     * MCP coordination request tool.
     */
    public static final String REQUEST_COORDINATION = "request_coordination";
    /**
     * MCP coordination response and validation tool.
     */
    public static final String RESPOND_COORDINATION = "respond_coordination";
    /**
     * MCP capability implementation publication tool.
     */
    public static final String PUBLISH_CAPABILITY_IMPLEMENTATION = "publish_capability_implementation";
    /**
     * MCP isolated lane completion tool.
     */
    public static final String FINISH_LANE = "finish_lane";
    /**
     * MCP isolated lane cancellation tool.
     */
    public static final String CANCEL_LANE = "cancel_lane";

    /**
     * Current descriptor schema version.
     */
    public static final int DESCRIPTOR_VERSION = 1;
    /**
     * Supported MCP protocol range.
     */
    public static final String PROTOCOL_RANGE = "2025-06-18..2025-06-18";

    private static final Map<String, Object> GENERIC_OUTPUT = objectSchema(Map.of("status", property("string"), "result", Map.of("type", "object")), List.of());
    private static final List<Descriptor> DESCRIPTORS = buildDescriptors();
    private static final List<String> RAW_NAMES = DESCRIPTORS.stream().map(Descriptor::wireName).toList();
    private static final Identity IDENTITIES = identities(DESCRIPTORS);
    private static final String WIRE_DIGEST = IDENTITIES.wireCompatibilityDigest();
    private static final String CONTENT_DIGEST = IDENTITIES.catalogContentDigest();

    private McpToolCatalog() {
        // Constants only.
    }

    /**
     * Returns the immutable descriptors in deterministic display order.
     *
     * @return descriptor list
     */
    public static List<Descriptor> descriptors() {
        return DESCRIPTORS;
    }

    /**
     * Returns the immutable raw wire names in tools/list order.
     *
     * @return raw names
     */
    public static List<String> rawNames() {
        return RAW_NAMES;
    }

    /**
     * Returns the safe-call protocol identity.
     *
     * @return SHA-256 wire compatibility digest
     */
    public static String wireCompatibilityDigest() {
        return WIRE_DIGEST;
    }

    /**
     * Returns the complete descriptor and renderer-input identity.
     *
     * @return SHA-256 catalog content digest
     */
    public static String catalogContentDigest() {
        return CONTENT_DIGEST;
    }

    /**
     * Derives the two catalog identities from an authoritative descriptor set.
     *
     * <p>This operation intentionally excludes rendered guidance artifacts. It
     * is public so diagnostics and hermetic tests can prove that a content-only descriptor change
     * does not masquerade as a wire incompatibility.</p>
     *
     * @param descriptors authoritative descriptors
     * @return wire and complete catalog identities
     */
    public static Identity identities(List<Descriptor> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        List<Descriptor> copy = List.copyOf(descriptors);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("descriptor set must not be empty");
        }
        return new Identity(digest(canonical(copy.stream().sorted(Comparator.comparing(Descriptor::wireName)).map(Descriptor::wireSemantics).toList())),
                digest(canonical(copy.stream().sorted(Comparator.comparingInt(Descriptor::displayOrder)).map(Descriptor::contentSemantics).toList())));
    }

    /**
     * Returns the tools/list projection generated from the descriptors.
     *
     * @return immutable MCP tool maps
     */
    public static List<Map<String, Object>> toolsList() {
        return DESCRIPTORS.stream().map(Descriptor::toolsListEntry).toList();
    }

    /**
     * Returns deterministic inputs used by a guidance renderer.
     *
     * @param provider provider identifier
     * @return canonical renderer input
     */
    public static String guidanceRendererInput(String provider) {
        Objects.requireNonNull(provider, "provider");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("provider", provider);
        input.put("wireCompatibilityDigest", WIRE_DIGEST);
        input.put("catalogContentDigest", CONTENT_DIGEST);
        input.put("descriptors", DESCRIPTORS.stream().map(Descriptor::guidanceSemantics).toList());
        return canonical(input);
    }

    /**
     * Computes a guidance artifact digest over rendered bytes.
     *
     * @param artifactRole  stable artifact role
     * @param provider      provider identifier
     * @param renderedBytes rendered artifact bytes
     * @return SHA-256 artifact digest
     */
    public static String guidanceArtifactDigest(String artifactRole, String provider, byte[] renderedBytes) {
        Objects.requireNonNull(artifactRole, "artifactRole");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(renderedBytes, "renderedBytes");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("artifactRole", artifactRole);
        manifest.put("provider", provider);
        manifest.put("rendererVersion", DESCRIPTOR_VERSION);
        manifest.put("artifactContentHash", digest(renderedBytes));
        return digest(canonical(manifest));
    }

    private static List<Descriptor> buildDescriptors() {
        List<Descriptor> result = new ArrayList<>();
        Map<String, Object> claimSelector = objectSchema(Map.of("path",
                Map.of("type", "string", "description", "Repository-relative path"),
                "kind",
                Map.of("type", "string", "enum", List.of("path_exact", "path_subtree"))), List.of("path"));
        Map<String, Object> claimArray = Map.of("type", "array", "description", "Intent and ownership selectors acquired by this session; keep them disjoint from other work", "items", claimSelector);
        Map<String, Object> taskProperties = new LinkedHashMap<>();
        taskProperties.put("goal", Map.of("type", "string", "description", "Announced task goal"));
        taskProperties.put("acceptance", Map.of("type", "string", "description", "Announced acceptance criteria"));
        taskProperties.put("likelyScopes", Map.of("type", "array", "items", property("string"), "description", "Descriptive planning hints only; these do not announce intent or acquire ownership"));
        taskProperties.put("knownDependencies",
                Map.of("type", "array", "items", property("string"), "description", "Explicit capability identifiers required by this task; prose and file changes do not create dependencies"));
        taskProperties.put("workGroupId", Map.of("type", "string", "format", "uuid"));
        taskProperties.put("intentId", Map.of("type", "string", "format", "uuid", "description", "Exact current intent required to extend a lane"));
        taskProperties.put("claimEpoch", Map.of("type", "integer", "minimum", 1, "description", "Exact current claim epoch required to extend a lane"));
        taskProperties.put("role", Map.of("type", "string", "enum", List.of("producer", "reviewer"), "description", "Semantic review-routing role; it does not change ownership claims"));
        taskProperties.put("reviewTargets", Map.of("type", "array", "description", "Non-ownership selectors identifying producer work this reviewer may review", "items", claimSelector));
        taskProperties.put("unwindCompletion", Map.of("type", "boolean", "description", "Authorized unwind of this caller's prepared but unpublished completion"));
        taskProperties.put("repairIntentId", Map.of("type", "string", "format", "uuid"));
        taskProperties.put("repairSnapshotId", property("string"));
        taskProperties.put("claims", claimArray);
        Map<String, Object> taskSchema = objectSchema(taskProperties, List.of());
        result.add(descriptor(ENSURE_SESSION,
                "Ensures an active, verified Synesis workspace session. Keep every task member inside task, including role, workGroupId, reviewTargets, intentId, claimEpoch, and claims; top-level task fields beside a task object are rejected. Before visible task mutation, include task.goal, task.acceptance, task.claims, and any knownDependencies as structured capability identifiers to announce intent and acquire the exact repository-relative ownership selectors; likelyScopes alone does not announce work or acquire claims. To extend a lane, follow the projected ensure_session continuation with its exact task.intentId and task.claimEpoch; existing claims and dependencies are retained.",
                objectSchema(Map.of("task", taskSchema, "refresh", property("boolean")), List.of()),
                "ensure-session",
                "MUTATING",
                List.of("SESSION_BINDING"),
                1));
        result.add(descriptor(READ_FILE,
                "Reads text file content from the assigned worktree or REVIEW snapshot. During capability validation, provide capabilityRequestHandle and implementationRevision from get_next_action to read that request's exact immutable validation revision.",
                objectSchema(Map.of("path",
                        Map.of("type", "string", "description", "Repository-relative file path"),
                        "startLine",
                        Map.of("type", "integer", "description", "1-based starting line number (default: 1)"),
                        "endLine",
                        Map.of("type", "integer", "description", "1-based ending line number (default: EOF)"),
                        "maxBytes",
                        Map.of("type", "integer", "description", "Maximum UTF-8 bytes to return (default: 65536)"),
                        "capabilityRequestHandle",
                        Map.of("type", "string", "pattern", "^req_[A-Za-z0-9]{12,64}$", "description", "Exact requester-owned capability handle shown for the current validation"),
                        "implementationRevision",
                        Map.of("type", "integer", "minimum", 1, "description", "Exact implementation revision shown for the current validation")), List.of("path")),
                "read-file",
                "READ_ONLY",
                List.of("SESSION_BINDING"),
                2));
        Map<String, Object> editSchema = objectSchema(Map.of("find", property("string"), "replace", property("string"), "expectedOccurrences", property("integer")), List.of("find", "replace", "expectedOccurrences"));
        result.add(descriptor(APPLY_PATCH,
                "Applies a structured file patch to the assigned worktree.",
                objectSchema(Map.of("path",
                        Map.of("type", "string", "description", "Repository-relative file path"),
                        "create",
                        Map.of("type", "boolean", "description", "Set true for new file creation"),
                        "content",
                        Map.of("type", "string", "description", "Full file content for creation mode"),
                        "expectedHash",
                        Map.of("type", "string", "description", "SHA-256 hex string of existing contentHash returned by synesis.read_file (required for modification)"),
                        "edits",
                        Map.of("type", "array", "items", editSchema, "description", "List of replacement edits (required for modification)")), List.of("path")),
                "apply-patch",
                "MUTATING",
                List.of("SESSION_BINDING", "CLAIM"),
                3));
        Map<String, Object> commandEvidence = objectSchema(Map.of("outcome",
                property("string"),
                "exitCode",
                property("integer"),
                "stdout",
                property("string"),
                "stderr",
                property("string"),
                "stdoutTruncated",
                property("boolean"),
                "stderrTruncated",
                property("boolean"),
                "stdoutBytesRead",
                property("integer"),
                "stderrBytesRead",
                property("integer"),
                "stdoutBytesRetained",
                property("integer"),
                "stderrBytesRetained",
                property("integer")), List.of());
        Map<String, Object> commandResult = objectSchema(Map.of("status", property("string"), "result", commandEvidence), List.of());
        result.add(descriptor(RUN_COMMAND,
                "Executes direct argv inside the assigned worktree or REVIEW snapshot. During capability validation, provide capabilityRequestHandle and implementationRevision from get_next_action to run tests against that request's exact immutable validation revision.",
                objectSchema(Map.of("argv",
                        Map.of("type", "array", "minItems", 1, "items", property("string"), "description", "Executable and arguments passed directly without a shell"),
                        "workingDirectory",
                        Map.of("type", "string", "description", "Relative lane directory; default '.'"),
                        "timeoutSeconds",
                        Map.of("type", "integer", "minimum", 1, "maximum", 3600, "description", "Execution timeout in seconds; default 120"),
                        "capabilityRequestHandle",
                        Map.of("type", "string", "pattern", "^req_[A-Za-z0-9]{12,64}$", "description", "Exact requester-owned capability handle shown for the current validation"),
                        "implementationRevision",
                        Map.of("type", "integer", "minimum", 1, "description", "Exact implementation revision shown for the current validation")), List.of("argv")),
                commandResult,
                "run-command",
                "MUTATING",
                List.of("SESSION_BINDING"),
                4));
        result.add(descriptor(GET_NEXT_ACTION,
                "Retrieves the highest-priority actionable coordination item. Call without arguments to read the durable coordination inbox. The optional completionRequested boolean is a call-local request to evaluate completion; after implementation, tests, and acceptance criteria are complete, call get_next_action once with completionRequested:true to request the exact publication projection. Missing or false continues implementation and does not inherit a prior request. The optional integrationCheck input is a read-only compatibility check of explicitly supplied candidate facts; it never advances a lane or WorkGroup and must not replace empty-argument polling or be treated as lifecycle completion. If the response is workflow IMPLEMENT without a concrete recommendedTool and arguments, continue ordinary coding in the assigned worktree and do not inspect protected .synesis/** metadata. Execute each concrete recommendedTool with its exact arguments before another lifecycle action. For repeated WAIT (including recommendedTool=get_next_action), IMPLEMENT, or blocked projections, compare workflow.actionId, honor workflow.retryAfterMs (default 5000 ms), and stop after workflow.maxUnchangedPolls unchanged responses (default 3). Reset the count when actionId changes. At the limit, report the stalled actionId and stop polling until a fresh state-change signal or human input; an active WorkGroup alone does not justify repeated unchanged calls.",
                objectSchema(Map.of("completionRequested",
                                Map.of("type", "boolean", "description", "Request completion projection for this call only; false or absent continues implementation"),
                                "integrationCheck",
                                Map.of("type", "object", "description", "Read-only compatibility facts; never advances lifecycle. Do not use as a substitute for get_next_action with empty arguments or to close a lane.")),
                        List.of()),
                "get-next-action",
                "READ_ONLY",
                List.of("SESSION_BINDING"),
                5));
        Map<String, Object> contractProperties = new LinkedHashMap<>();
        contractProperties.put("purpose", Map.of("type", "string", "minLength", 1, "maxLength", 2000, "description", "What callers use this API for"));
        contractProperties.put("inputs", Map.of("type", "string", "minLength", 1, "maxLength", 2000, "description", "Input names, types, constraints, and defaults"));
        contractProperties.put("output", Map.of("type", "string", "minLength", 1, "maxLength", 2000, "description", "Output type and semantics"));
        contractProperties.put("requiredBehavior", Map.of("type", "array", "maxItems", 16, "items", Map.of("type", "string", "minLength", 1, "maxLength", 1000), "description", "Required operational behavior"));
        contractProperties.put("errorSemantics", Map.of("type", "string", "minLength", 1, "maxLength", 2000, "description", "Failure cases and observable results"));
        contractProperties.put("sideEffects", Map.of("type", "string", "minLength", 1, "maxLength", 2000, "description", "Externally visible effects"));
        contractProperties.put("invariants", Map.of("type", "string", "minLength", 1, "maxLength", 2000, "description", "Conditions that always remain true"));
        contractProperties.put("compatibility", Map.of("type", "string", "minLength", 1, "maxLength", 2000, "description", "Compatibility and evolution requirements"));
        contractProperties.put("performance", Map.of("type", "string", "minLength", 1, "maxLength", 2000, "description", "Latency and resource requirements"));
        contractProperties.put("concurrency", Map.of("type", "string", "minLength", 1, "maxLength", 2000, "description", "Concurrency, ordering, and consistency guarantees"));
        contractProperties.put("acceptanceTests", Map.of("type", "array", "maxItems", 16, "items", Map.of("type", "string", "minLength", 1, "maxLength", 1000), "description", "Acceptance criteria for the published implementation"));
        List<String> contractFields = List.of("purpose", "inputs", "output", "requiredBehavior", "errorSemantics", "sideEffects", "invariants", "compatibility", "performance", "concurrency", "acceptanceTests");
        Map<String, Object> contractSchema = objectSchema(contractProperties, contractFields);
        Map<String, Object> requestPayload = new LinkedHashMap<>();
        requestPayload.put("conflictingIntentId", Map.of("type", "string", "format", "uuid"));
        requestPayload.put("intentId", Map.of("type", "string", "format", "uuid"));
        requestPayload.put("contractId", Map.of("type", "string", "format", "uuid"));
        requestPayload.put("body", property("string"));
        requestPayload.put("selectors", Map.of("type", "array", "items", property("string")));
        requestPayload.put("revision", Map.of("type", "integer", "minimum", 1));
        requestPayload.put("targetParticipant", property("string"));
        requestPayload.put("proposal", property("string"));
        requestPayload.put("artifact", property("string"));
        requestPayload.put("capability", property("string"));
        requestPayload.put("contract", contractSchema);
        requestPayload.put("capabilityRequestHandle", Map.of("type", "string", "pattern", "^req_[A-Za-z0-9]{12,64}$"));
        requestPayload.put("revisionResponse", Map.of("type", "string", "enum", List.of("accept", "counter", "cancel")));
        requestPayload.put("contractRevision", Map.of("type", "integer", "minimum", 1, "description", "Exact contract revision shown in the current request projection"));
        requestPayload.put("ownerAuthorityLineageId", Map.of("type", "string", "format", "uuid", "description", "Durable authority lineage of the intended capability publisher"));
        requestPayload.put("workGroupId", Map.of("type", "string", "format", "uuid"));
        requestPayload.put("goal", property("string"));
        requestPayload.put("acceptance", property("string"));
        requestPayload.put("snapshotId", property("string"));
        requestPayload.put("reviewPolicy", objectSchema(Map.of("mandatory", Map.of("type", "boolean"), "independenceRequired", Map.of("type", "boolean")), List.of("mandatory", "independenceRequired")));
        requestPayload.put("grantId", Map.of("type", "string", "format", "uuid"));
        requestPayload.put("claimEpoch", Map.of("type", "integer", "minimum", 1));
        Map<String, Object> requestSchema = objectSchema(Map.of("kind",
                Map.of("type",
                        "string",
                        "enum",
                        List.of("capability_request", "collaboration_status", "contract_proposal", "contract_request", "scope_revision", "handoff", "work_group_join", "continuation", "work_group_create")),
                "payload",
                objectSchema(requestPayload, List.of())), List.of("kind", "payload"));
        constrainPayloadKind(requestSchema, "capability_request", List.of("capability", "contract", "capabilityRequestHandle", "revisionResponse", "contractRevision", "ownerAuthorityLineageId"), List.of("capability", "contract"));
        constrainPayloadKind(requestSchema, "contract_request", List.of("conflictingIntentId", "proposal", "contractId", "revision"), List.of("conflictingIntentId", "proposal"));
        result.add(descriptor(REQUEST_COORDINATION,
                "Submits one strict capability or collaboration request. A capability_request includes the complete API contract. To answer an owner revision, include capabilityRequestHandle, revisionResponse, and the exact contractRevision shown in get_next_action; acceptance must identify the current full contract. For kind contract_request, payload requires conflictingIntentId and proposal. Only fields declared for the selected kind are allowed.",
                requestSchema,
                "request-coordination",
                "MUTATING",
                List.of("SESSION_BINDING", "COORDINATION"),
                6));
        Map<String, Object> responsePayload = new LinkedHashMap<>();
        responsePayload.put("capabilityRequestHandle", Map.of("type", "string", "pattern", "^req_[A-Za-z0-9]{12,64}$"));
        responsePayload.put("response", Map.of("type", "string", "enum", List.of("accept", "revise", "reject")));
        responsePayload.put("contractRevision", Map.of("type", "integer", "minimum", 1, "description", "Exact contract revision shown in the owner inbox"));
        responsePayload.put("revision", contractSchema);
        responsePayload.put("reason", property("string"));
        responsePayload.put("coordinationRequest", Map.of("type", "string", "format", "uuid"));
        responsePayload.put("coordinationStatus", Map.of("type", "string", "enum", List.of("ACCEPTED", "REVISED", "REJECTED", "CANCELLED", "COMPLETED")));
        responsePayload.put("proposal", property("string"));
        responsePayload.put("inboxItemId", Map.of("type", "string", "format", "uuid"));
        responsePayload.put("resolution", Map.of("type", "string", "enum", List.of("ACCEPTED", "REVISED", "REJECTED", "CANCELLED", "COMPLETED")));
        responsePayload.put("grantId", Map.of("type", "string", "format", "uuid"));
        responsePayload.put("snapshotId", property("string"));
        responsePayload.put("intentId", Map.of("type", "string", "format", "uuid"));
        responsePayload.put("claimEpoch", Map.of("type", "integer", "minimum", 1));
        responsePayload.put("result", Map.of("type", "string", "enum", List.of("inspect", "accepted", "revision_required", "rejected")));
        responsePayload.put("implementationRevision", Map.of("type", "integer", "minimum", 1));
        responsePayload.put("failedAcceptanceTests", Map.of("type", "array", "items", property("string")));
        Map<String, Object> responseSchema = objectSchema(Map.of("kind",
                Map.of("type", "string", "enum", List.of("capability_response", "coordination_response", "inbox_acknowledge", "inbox_resolve", "implementation_validation", "review_validation")),
                "payload",
                objectSchema(responsePayload, List.of())), List.of("kind", "payload"));
        constrainPayloadKind(responseSchema, "capability_response", List.of("capabilityRequestHandle", "response", "contractRevision", "revision", "reason"), List.of("capabilityRequestHandle", "response", "contractRevision"));
        constrainPayloadKind(responseSchema, "coordination_response", List.of("coordinationRequest", "coordinationStatus", "proposal"), List.of("coordinationRequest", "coordinationStatus"));
        constrainPayloadKind(responseSchema,
                "implementation_validation",
                List.of("inboxItemId", "capabilityRequestHandle", "implementationRevision", "result", "reason", "failedAcceptanceTests"),
                List.of("inboxItemId", "capabilityRequestHandle", "implementationRevision", "result"));
        result.add(descriptor(RESPOND_COORDINATION,
                "Responds to a pending coordination item or validates an implementation. For implementation_validation, get_next_action first projects result=inspect; execute it before reading/running tests with the projected capabilityRequestHandle and implementationRevision. Only after that exact validation workspace is active may you choose accepted or revision_required. capability_response is fenced to the exact contractRevision shown in the owner inbox; revise also requires a complete replacement contract. For kind coordination_response, payload requires the owned request UUID and status; proposal is optional. The request target chooses the status; Synesis does not choose a CONTRACT judgment.",
                responseSchema,
                "respond-coordination",
                "MUTATING",
                List.of("SESSION_BINDING", "COORDINATION"),
                7));
        result.add(descriptor(PUBLISH_CAPABILITY_IMPLEMENTATION,
                "Publishes an immutable claimed-source snapshot for the accepted capability request and exact owner intent. Supply contractRevision, intentId, claimEpoch, and expectedTreeHash from the current get_next_action projection; stale evidence is rejected.",
                objectSchema(Map.of("capabilityRequestHandle",
                        Map.of("type", "string", "pattern", "^req_[A-Za-z0-9]{12,64}$", "description", "Server-issued capability request handle"),
                        "summary",
                        Map.of("type", "string", "description", "Human-readable summary of this implementation"),
                        "contractRevision",
                        Map.of("type", "integer", "minimum", 1, "description", "Exact accepted contract revision from the current projection"),
                        "intentId",
                        Map.of("type", "string", "format", "uuid", "description", "Exact active owner intent ID from the current projection"),
                        "claimEpoch",
                        Map.of("type", "integer", "minimum", 1, "description", "Exact active owner claim epoch from the current projection"),
                        "expectedTreeHash",
                        Map.of("type", "string", "pattern", "^[0-9a-f]{40}([0-9a-f]{24})?$", "description", "Projected Git tree hash for the claimed source snapshot")),
                        List.of("capabilityRequestHandle", "contractRevision", "intentId", "claimEpoch", "expectedTreeHash")),
                "publish-capability",
                "MUTATING",
                List.of("SESSION_BINDING", "CAPABILITY"),
                8));
        Map<String, Object> finishProperties = new LinkedHashMap<>();
        finishProperties.put("outcome", Map.of("type", "string", "enum", List.of("snapshot", "no_change"), "description", "Terminal artifact outcome; no_change requires the server's clean-worktree validation"));
        finishProperties.put("intentId", Map.of("type", "string", "format", "uuid", "description", "Server-issued active intent identifier from get_next_action"));
        finishProperties.put("workGroupId", Map.of("type", "string", "format", "uuid", "description", "Server-issued work-group identifier from get_next_action"));
        finishProperties.put("claimEpoch", Map.of("type", "integer", "minimum", 1, "description", "Current intent claim epoch from get_next_action"));
        finishProperties.put("workGroupVersion", Map.of("type", "integer", "minimum", 1, "description", "Current work-group version from get_next_action"));
        finishProperties.put("expectedRevision", Map.of("type", "integer", "minimum", 0, "description", "Current project event revision from get_next_action"));
        finishProperties.put("participant", Map.of("type", "string", "description", "Exact participant handle from get_next_action"));
        finishProperties.put("summary", Map.of("type", "string", "description", "Human-readable summary of completed task work"));
        finishProperties.put("terminalSession", Map.of("type", "boolean", "description", "Explicit opt-in: after this lane completes, request irreversible terminalization of this exact provider session; default false"));
        result.add(descriptor(FINISH_LANE,
                "Validates, publishes, integrates, and closes this isolated lane. A no_change outcome is an explicit, evidence-bound completion for a declared clean verification task; it is never inferred from provider exit. An explicit terminal session request is evaluated separately and may be refused when any authority remains.",
                objectSchema(finishProperties, List.of()),
                "finish-lane",
                "MUTATING",
                List.of("SESSION_BINDING", "CLAIM", "SNAPSHOT"),
                9));
        result.add(descriptor(CANCEL_LANE,
                "Permanently fences and cancels this isolated lane.",
                objectSchema(Map.of("reason", Map.of("type", "string", "description", "Cancellation reason string (1-1000 characters)")), List.of("reason")),
                "cancel-lane",
                "MUTATING",
                List.of("SESSION_BINDING"),
                10));
        return List.copyOf(result);
    }

    private static Descriptor descriptor(String name, String description, Map<String, Object> input, String handler, String mutability, List<String> capabilities, int order) {
        return descriptor(name, description, input, GENERIC_OUTPUT, handler, mutability, capabilities, order);
    }

    private static Descriptor descriptor(String name, String description, Map<String, Object> input, Map<String, Object> output, String handler, String mutability, List<String> capabilities, int order) {
        List<String> errors = List.of("INVALID_REQUEST", "STALE_AUTHORITY", "COORDINATION_BLOCKED", "RETRYABLE_FAILURE");
        Map<String, String> recoverability = Map.of("INVALID_REQUEST", "terminal", "STALE_AUTHORITY", "reestablish_session", "COORDINATION_BLOCKED", "inspect_inbox", "RETRYABLE_FAILURE", "bounded_retry");
        Map<String, String> idempotency = Map.of("default", "server-issued identity and exact-caller idempotent");
        return new Descriptor(name,
                DESCRIPTOR_VERSION,
                input,
                output,
                errors,
                recoverability,
                idempotency,
                List.of("EXACT_SESSION_BINDING"),
                mutability,
                capabilities,
                PROTOCOL_RANGE,
                handler,
                description,
                Map.of("source", "synesis-mcp-contract", "version", DESCRIPTOR_VERSION),
                order,
                List.of("follow the durable next action and typed arguments"),
                List.of("render raw tool names without provider namespace decoration"),
                List.of("never guess identifiers or retry failed mutations blindly"));
    }

    private static Map<String, Object> property(String type) {
        return Map.of("type", type);
    }

    @SuppressWarnings("unchecked")
    private static void constrainPayloadKind(Map<String, Object> schema, String kind, List<String> allowed, List<String> required) {
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kindSchema = (Map<String, Object>) properties.get("kind");
        Map<String, Object> payloadSchema = (Map<String, Object>) properties.get("payload");
        Map<String, Object> payloadProperties = (Map<String, Object>) payloadSchema.get("properties");
        List<String> allKinds = (List<String>) kindSchema.get("enum");
        Map<String, Map<String, Object>> constrainedKinds = new LinkedHashMap<>();
        Object previous = schema.get("oneOf");
        if (previous instanceof List<?> priorAlternatives) {
            for (Object prior : priorAlternatives) {
                if (!(prior instanceof Map<?, ?> alternative) || !(alternative.get("properties") instanceof Map<?, ?> priorProperties)
                        || !(priorProperties.get("kind") instanceof Map<?, ?> priorKind) || !(priorKind.get("enum") instanceof List<?> priorKindValues)
                        || priorKindValues.size() != 1 || !(priorProperties.get("payload") instanceof Map<?, ?> priorPayload)
                        || !Boolean.FALSE.equals(priorPayload.get("additionalProperties"))) {
                    continue;
                }
                String priorKindValue = String.valueOf(priorKindValues.getFirst());
                if (allKinds.contains(priorKindValue)) {
                    constrainedKinds.put(priorKindValue, (Map<String, Object>) alternative);
                }
            }
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        allowed.forEach(field -> selected.put(field, payloadProperties.get(field)));
        Map<String, Object> strictPayload = objectSchema(selected, required);
        strictPayload.put("additionalProperties", false);
        constrainedKinds.put(kind, objectSchema(Map.of("kind", Map.of("type", "string", "enum", List.of(kind)), "payload", strictPayload), List.of("kind", "payload")));
        List<Map<String, Object>> alternatives = new ArrayList<>(constrainedKinds.values());
        List<String> otherKinds = allKinds.stream().filter(value -> !constrainedKinds.containsKey(value)).toList();
        if (!otherKinds.isEmpty()) {
            alternatives.add(objectSchema(Map.of("kind", Map.of("type", "string", "enum", otherKinds), "payload", payloadSchema), List.of("kind", "payload")));
        }
        schema.put("oneOf", List.copyOf(alternatives));
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "object");
        value.put("properties", properties);
        if (!required.isEmpty()) {
            value.put("required", required);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> freeze(Map<String, Object> value) {
        return (Map<String, Object>) freezeObject(value);
    }

    private static Object freezeObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), freezeObject(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return List.copyOf(list.stream().map(McpToolCatalog::freezeObject).toList());
        }
        return value;
    }

    private static String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return quote(string);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(String::valueOf))).map(entry -> quote(String.valueOf(entry.getKey())) + ":" + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(McpToolCatalog::canonical).collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        throw new IllegalArgumentException("unsupported canonical value: " + value.getClass());
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder("\"");
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        builder.append(String.format("\\u%04x", codePoint));
                    } else {
                        builder.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return builder.append('"').toString();
    }

    private static String digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    /**
     * Pair of non-circular catalog identities.
     *
     * @param wireCompatibilityDigest protocol and execution identity
     * @param catalogContentDigest    complete descriptor-content identity
     */
    public record Identity(String wireCompatibilityDigest, String catalogContentDigest) {

        /**
         * Validates identity values.
         */
        public Identity {
            Objects.requireNonNull(wireCompatibilityDigest, "wireCompatibilityDigest");
            Objects.requireNonNull(catalogContentDigest, "catalogContentDigest");
        }
    }

    /**
     * Immutable MCP descriptor.
     *
     * @param wireName                  wire name
     * @param descriptorVersion         descriptor schema version
     * @param inputSchema               input schema
     * @param outputSchema              output schema
     * @param stableErrorCodes          stable error codes
     * @param errorRecoverability       error recoverability by code
     * @param idempotencySemantics      idempotency semantics
     * @param authorizationRequirements authorization requirements
     * @param mutabilityClassification  mutability classification
     * @param requiredCapabilities      required capabilities
     * @param supportedProtocolRange    supported protocol range
     * @param handlerKey                protocol handler identity
     * @param description               display description
     * @param documentationMetadata     documentation metadata
     * @param displayOrder              display order
     * @param guidanceTemplates         guidance templates
     * @param renderingRules            renderer rules
     * @param behavioralInstructions    agent-facing instructions
     */
    public record Descriptor(String wireName, int descriptorVersion, Map<String, Object> inputSchema, Map<String, Object> outputSchema, List<String> stableErrorCodes, Map<String, String> errorRecoverability,
                             Map<String, String> idempotencySemantics, List<String> authorizationRequirements, String mutabilityClassification, List<String> requiredCapabilities, String supportedProtocolRange,
                             String handlerKey, String description, Map<String, Object> documentationMetadata, int displayOrder, List<String> guidanceTemplates, List<String> renderingRules,
                             List<String> behavioralInstructions) {

        /**
         * Canonicalizes all descriptor collections.
         */
        public Descriptor {
            Objects.requireNonNull(wireName, "wireName");
            Objects.requireNonNull(inputSchema, "inputSchema");
            Objects.requireNonNull(outputSchema, "outputSchema");
            Objects.requireNonNull(stableErrorCodes, "stableErrorCodes");
            Objects.requireNonNull(errorRecoverability, "errorRecoverability");
            Objects.requireNonNull(idempotencySemantics, "idempotencySemantics");
            Objects.requireNonNull(authorizationRequirements, "authorizationRequirements");
            Objects.requireNonNull(mutabilityClassification, "mutabilityClassification");
            Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
            Objects.requireNonNull(supportedProtocolRange, "supportedProtocolRange");
            Objects.requireNonNull(handlerKey, "handlerKey");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(documentationMetadata, "documentationMetadata");
            Objects.requireNonNull(guidanceTemplates, "guidanceTemplates");
            Objects.requireNonNull(renderingRules, "renderingRules");
            Objects.requireNonNull(behavioralInstructions, "behavioralInstructions");
            inputSchema = freeze(inputSchema);
            outputSchema = freeze(outputSchema);
            stableErrorCodes = List.copyOf(stableErrorCodes);
            errorRecoverability = Map.copyOf(errorRecoverability);
            idempotencySemantics = Map.copyOf(idempotencySemantics);
            authorizationRequirements = List.copyOf(authorizationRequirements);
            requiredCapabilities = List.copyOf(requiredCapabilities);
            documentationMetadata = freeze(documentationMetadata);
            guidanceTemplates = List.copyOf(guidanceTemplates);
            renderingRules = List.copyOf(renderingRules);
            behavioralInstructions = List.copyOf(behavioralInstructions);
        }

        private Map<String, Object> wireSemantics() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("wireName", wireName);
            value.put("descriptorVersion", descriptorVersion);
            value.put("inputSchema", inputSchema);
            value.put("outputSchema", outputSchema);
            value.put("stableErrorCodes", stableErrorCodes);
            value.put("errorRecoverability", errorRecoverability);
            value.put("idempotencySemantics", idempotencySemantics);
            value.put("authorizationRequirements", authorizationRequirements);
            value.put("mutabilityClassification", mutabilityClassification);
            value.put("requiredCapabilities", requiredCapabilities);
            value.put("supportedProtocolRange", supportedProtocolRange);
            value.put("handlerKey", handlerKey);
            return value;
        }

        private Map<String, Object> contentSemantics() {
            Map<String, Object> value = new LinkedHashMap<>(wireSemantics());
            value.put("description", description);
            value.put("documentationMetadata", documentationMetadata);
            value.put("displayOrder", displayOrder);
            value.put("guidanceTemplates", guidanceTemplates);
            value.put("renderingRules", renderingRules);
            value.put("behavioralInstructions", behavioralInstructions);
            return value;
        }

        private Map<String, Object> guidanceSemantics() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("wireName", wireName);
            value.put("description", description);
            value.put("guidanceTemplates", guidanceTemplates);
            value.put("renderingRules", renderingRules);
            value.put("behavioralInstructions", behavioralInstructions);
            return value;
        }

        private Map<String, Object> toolsListEntry() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", wireName);
            value.put("description", description);
            value.put("inputSchema", inputSchema);
            return Collections.unmodifiableMap(value);
        }
    }
}
