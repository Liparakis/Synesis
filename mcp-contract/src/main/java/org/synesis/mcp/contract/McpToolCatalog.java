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
 * identity. The catalog contains exactly eleven tools; adding or removing a descriptor is a protocol
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
     * MCP tool that sends text to other lanes or to the operator.
     */
    public static final String SEND_MESSAGE = "send_message";

    /**
     * Current descriptor schema version.
     */
    public static final int DESCRIPTOR_VERSION = 1;
    /**
     * Supported MCP protocol range.
     */
    public static final String PROTOCOL_RANGE = "2025-06-18..2025-06-18";

    private static final String REQUEST_HANDLE_PATTERN = "^req_[A-Za-z0-9]{12,64}$";
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
    static List<Descriptor> descriptors() {
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
     * exists so tests can prove that a content-only descriptor change does not masquerade as a wire
     * incompatibility.</p>
     *
     * @param descriptors authoritative descriptors
     * @return wire and complete catalog identities
     */
    static Identity identities(List<Descriptor> descriptors) {
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
    static String guidanceRendererInput(String provider) {
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
        return List.of(
                ensureSession(),
                readFile(),
                applyPatch(),
                runCommand(),
                getNextAction(),
                requestCoordination(),
                respondCoordination(),
                publishCapabilityImplementation(),
                finishLane(),
                cancelLane(),
                sendMessage());
    }

    private static Descriptor ensureSession() {
        return descriptor(ENSURE_SESSION,
                "Ensures an active, verified Synesis workspace session. Keep every task member inside task, including role, workGroupId, reviewTargets, intentId, claimEpoch, and claims; top-level task fields beside a task object are rejected. Before visible task mutation, include task.goal, task.acceptance, task.claims, and any knownDependencies as structured capability identifiers to announce intent and acquire the exact repository-relative ownership selectors; likelyScopes alone does not announce work or acquire claims. To extend a lane, follow the projected ensure_session continuation with its exact task.intentId and task.claimEpoch; existing claims and dependencies are retained.",
                objectSchema(Map.of("task", taskSchema(), "refresh", property("boolean")), List.of()),
                "ensure-session",
                "MUTATING",
                List.of("SESSION_BINDING"),
                1);
    }

    private static Map<String, Object> taskSchema() {
        Map<String, Object> claimSelector = objectSchema(Map.of("path", text("Repository-relative path"),
                "kind", enumOf(List.of("path_exact", "path_subtree"))), List.of("path"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("goal", text("Announced task goal"));
        properties.put("acceptance", text("Announced acceptance criteria"));
        properties.put("likelyScopes", stringArray("Descriptive planning hints only; these do not announce intent or acquire ownership"));
        properties.put("knownDependencies",
                stringArray("Explicit capability identifiers required by this task; prose and file changes do not create dependencies"));
        properties.put("workGroupId", uuid());
        properties.put("intentId", uuid("Exact current intent required to extend a lane"));
        properties.put("claimEpoch", positiveInteger("Exact current claim epoch required to extend a lane"));
        properties.put("role", enumOf(List.of("producer", "reviewer"), "Semantic review-routing role; it does not change ownership claims"));
        properties.put("reviewTargets", selectorArray("Non-ownership selectors identifying producer work this reviewer may review", claimSelector));
        properties.put("unwindCompletion", bool("Authorized unwind of this caller's prepared but unpublished completion"));
        properties.put("repairIntentId", uuid());
        properties.put("repairSnapshotId", property("string"));
        properties.put("claims", selectorArray("Intent and ownership selectors acquired by this session; keep them disjoint from other work", claimSelector));
        return objectSchema(properties, List.of());
    }

    private static Descriptor readFile() {
        return descriptor(READ_FILE,
                "Reads text file content from the assigned worktree or REVIEW snapshot. During capability validation, provide capabilityRequestHandle and implementationRevision from get_next_action to read that request's exact immutable validation revision.",
                objectSchema(Map.of("path", text("Repository-relative file path"),
                        "startLine", integer("1-based starting line number (default: 1)"),
                        "endLine", integer("1-based ending line number (default: EOF)"),
                        "maxBytes", integer("Maximum UTF-8 bytes to return (default: 65536)"),
                        "capabilityRequestHandle", validationHandle(),
                        "implementationRevision", validationRevision()), List.of("path")),
                "read-file",
                "READ_ONLY",
                List.of("SESSION_BINDING"),
                2);
    }

    private static Descriptor applyPatch() {
        Map<String, Object> editSchema = objectSchema(Map.of("find", property("string"), "replace", property("string"), "expectedOccurrences", property("integer")), List.of("find", "replace", "expectedOccurrences"));
        return descriptor(APPLY_PATCH,
                "Applies a structured file patch to the assigned worktree.",
                objectSchema(Map.of("path", text("Repository-relative file path"),
                        "create", bool("Set true for new file creation"),
                        "content", text("Full file content for creation mode"),
                        "expectedHash", text("SHA-256 hex string of existing contentHash returned by synesis.read_file (required for modification)"),
                        "edits", Map.of("type", "array", "items", editSchema, "description", "List of replacement edits (required for modification)")), List.of("path")),
                "apply-patch",
                "MUTATING",
                List.of("SESSION_BINDING", "CLAIM"),
                3);
    }

    private static Descriptor runCommand() {
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
        return descriptor(RUN_COMMAND,
                "Executes direct argv inside the assigned worktree or REVIEW snapshot. During capability validation, provide capabilityRequestHandle and implementationRevision from get_next_action to run tests against that request's exact immutable validation revision.",
                objectSchema(Map.of("argv",
                        Map.of("type", "array", "minItems", 1, "items", property("string"), "description", "Executable and arguments passed directly without a shell"),
                        "workingDirectory", text("Relative lane directory; default '.'"),
                        "timeoutSeconds",
                        Map.of("type", "integer", "minimum", 1, "maximum", 3600, "description", "Execution timeout in seconds; default 120"),
                        "capabilityRequestHandle", validationHandle(),
                        "implementationRevision", validationRevision()), List.of("argv")),
                commandResult,
                "run-command",
                "MUTATING",
                List.of("SESSION_BINDING"),
                4);
    }

    private static Descriptor getNextAction() {
        return descriptor(GET_NEXT_ACTION,
                "Retrieves the highest-priority actionable coordination item. Call without arguments to read the durable coordination inbox. The optional completionRequested boolean is a call-local request to evaluate completion; after implementation, tests, and acceptance criteria are complete, call get_next_action once with completionRequested:true to request the exact publication projection. Missing or false continues implementation and does not inherit a prior request. The optional integrationCheck input is a read-only compatibility check of explicitly supplied candidate facts; it never advances a lane or WorkGroup and must not replace empty-argument polling or be treated as lifecycle completion. If the response is workflow IMPLEMENT without a concrete recommendedTool and arguments, continue ordinary coding in the assigned worktree and do not inspect protected .synesis/** metadata. Execute each concrete recommendedTool with its exact arguments before another lifecycle action. When the response is WAIT, end your turn: do not sleep or poll on a timer; Synesis wakes you when something arrives for your lane. For repeated IMPLEMENT or blocked projections, compare workflow.actionId, honor workflow.retryAfterMs (default 5000 ms), and stop after workflow.maxUnchangedPolls unchanged responses (default 3). Reset the count when actionId changes. At the limit, report the stalled actionId and stop polling until a fresh state-change signal or human input; an active WorkGroup alone does not justify repeated unchanged calls.",
                objectSchema(Map.of("completionRequested", bool("Request completion projection for this call only; false or absent continues implementation"),
                                "integrationCheck",
                                Map.of("type", "object", "description", "Read-only compatibility facts; never advances lifecycle. Do not use as a substitute for get_next_action with empty arguments or to close a lane.")),
                        List.of()),
                "get-next-action",
                "READ_ONLY",
                List.of("SESSION_BINDING"),
                5);
    }

    private static Descriptor requestCoordination() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intentId", uuid());
        payload.put("selectors", stringArray());
        payload.put("revision", positiveInteger("Contract revision the caller read and accepts"));
        payload.put("targetParticipant", property("string"));
        payload.put("proposal", property("string"));
        payload.put("artifact", property("string"));
        payload.put("capability", text("The caller's own name for what it depends on, as listed in its task.knownDependencies"));
        payload.put("contract", contractSchema());
        payload.put("paths", stringArray("Repository-relative files the contract covers, all claimed by the caller; a path ending in / names a subtree"));
        payload.put("answers", stringArray("changeRequest identifiers this revision covers"));
        payload.put("ownerPath", text("Repository-relative file the caller builds on; the lane claiming it is the owner"));
        payload.put("need", text("What the caller needs from that file that its contract does not say"));
        payload.put("workGroupId", uuid());
        payload.put("goal", property("string"));
        payload.put("acceptance", property("string"));
        payload.put("snapshotId", property("string"));
        payload.put("reviewPolicy", objectSchema(Map.of("mandatory", property("boolean"), "independenceRequired", property("boolean")), List.of("mandatory", "independenceRequired")));
        payload.put("grantId", uuid());
        payload.put("claimEpoch", positiveInteger());
        Map<String, Object> schema = kindDiscriminatedSchema(
                List.of("contract_publish", "contract_bind", "contract_change_request", "collaboration_status", "scope_revision", "handoff", "work_group_join", "continuation", "work_group_create"),
                payload,
                List.of(new PayloadKind("contract_publish", List.of("paths", "contract", "answers"), List.of("paths", "contract")),
                        new PayloadKind("contract_bind", List.of("capability", "ownerPath", "revision"), List.of("capability", "ownerPath", "revision")),
                        new PayloadKind("contract_change_request", List.of("ownerPath", "need"), List.of("ownerPath", "need"))));
        return descriptor(REQUEST_COORDINATION,
                "Submits one strict contract or collaboration request. One public contract covers a claimed file: every agent can read it, and only the lane that claims the file publishes it. contract_publish publishes the complete contract over paths the caller claims, or its next revision, and may list in answers the change requests it covers. contract_bind accepts the exact revision of the contract over ownerPath for the dependency the caller names in capability; after the owner publishes a new revision, bind again. contract_change_request tells the lane claiming ownerPath what the caller needs, and asks for a contract where none exists yet. Only fields declared for the selected kind are allowed.",
                schema,
                "request-coordination",
                "MUTATING",
                List.of("SESSION_BINDING", "COORDINATION"),
                6);
    }

    private static Descriptor respondCoordination() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("capabilityRequestHandle", requestHandle());
        payload.put("changeRequest", uuid("Identifier of the contract change request being answered"));
        payload.put("decision", enumOf(List.of("accepted", "declined")));
        payload.put("contractField", enumOf(List.of("purpose", "inputs", "output", "requiredBehavior", "errorSemantics", "sideEffects", "invariants", "compatibility", "performance", "concurrency", "acceptanceTests"),
                "The field of the contract that the implementation breaks; required with result revision_required"));
        payload.put("reason", property("string"));
        payload.put("coordinationRequest", uuid());
        payload.put("coordinationStatus", enumOf(List.of("ACCEPTED", "REVISED", "REJECTED", "CANCELLED", "COMPLETED")));
        payload.put("proposal", property("string"));
        payload.put("inboxItemId", uuid());
        payload.put("resolution", enumOf(List.of("ACCEPTED", "REVISED", "REJECTED", "CANCELLED", "COMPLETED")));
        payload.put("grantId", uuid());
        payload.put("snapshotId", property("string"));
        payload.put("intentId", uuid());
        payload.put("claimEpoch", positiveInteger());
        payload.put("result", enumOf(List.of("inspect", "accepted", "revision_required", "rejected")));
        payload.put("implementationRevision", positiveInteger());
        payload.put("failedAcceptanceTests", stringArray());
        Map<String, Object> schema = kindDiscriminatedSchema(
                List.of("contract_change_response", "coordination_response", "inbox_acknowledge", "inbox_resolve", "implementation_validation", "review_validation"),
                payload,
                List.of(new PayloadKind("contract_change_response", List.of("changeRequest", "decision", "reason"), List.of("changeRequest", "decision")),
                        new PayloadKind("coordination_response", List.of("coordinationRequest", "coordinationStatus", "proposal"), List.of("coordinationRequest", "coordinationStatus")),
                        new PayloadKind("implementation_validation",
                                List.of("inboxItemId", "capabilityRequestHandle", "implementationRevision", "result", "reason", "contractField", "failedAcceptanceTests"),
                                List.of("inboxItemId", "capabilityRequestHandle", "implementationRevision", "result"))));
        return descriptor(RESPOND_COORDINATION,
                "Responds to a pending coordination item or validates an implementation. For implementation_validation, get_next_action first projects result=inspect; execute it before reading/running tests with the projected capabilityRequestHandle and implementationRevision. Only after that exact validation workspace is active may you choose accepted or revision_required; revision_required needs a reason and contractField, the field of the contract you bound to that the implementation breaks. contract_change_response answers one change request against a contract of the caller's lane: accepted when the current revision covers it, or declined with a reason. For kind coordination_response, payload requires the owned request UUID and status; proposal is optional. The request target chooses the status; Synesis does not choose a CONTRACT judgment.",
                schema,
                "respond-coordination",
                "MUTATING",
                List.of("SESSION_BINDING", "COORDINATION"),
                7);
    }

    private static Descriptor publishCapabilityImplementation() {
        return descriptor(PUBLISH_CAPABILITY_IMPLEMENTATION,
                "Publishes an immutable claimed-source snapshot for the accepted capability request and exact owner intent. Supply contractRevision, intentId, claimEpoch, and expectedTreeHash from the current get_next_action projection; stale evidence is rejected.",
                objectSchema(Map.of("capabilityRequestHandle", requestHandle("Server-issued capability request handle"),
                        "summary", text("Human-readable summary of this implementation"),
                        "contractRevision", positiveInteger("Exact accepted contract revision from the current projection"),
                        "intentId", uuid("Exact active owner intent ID from the current projection"),
                        "claimEpoch", positiveInteger("Exact active owner claim epoch from the current projection"),
                        "expectedTreeHash",
                        Map.of("type", "string", "pattern", "^[0-9a-f]{40}([0-9a-f]{24})?$", "description", "Projected Git tree hash for the claimed source snapshot")),
                        List.of("capabilityRequestHandle", "contractRevision", "intentId", "claimEpoch", "expectedTreeHash")),
                "publish-capability",
                "MUTATING",
                List.of("SESSION_BINDING", "CAPABILITY"),
                8);
    }

    private static Descriptor finishLane() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("outcome", enumOf(List.of("snapshot", "no_change"), "Terminal artifact outcome; no_change requires the server's clean-worktree validation"));
        properties.put("intentId", uuid("Server-issued active intent identifier from get_next_action"));
        properties.put("workGroupId", uuid("Server-issued work-group identifier from get_next_action"));
        properties.put("claimEpoch", positiveInteger("Current intent claim epoch from get_next_action"));
        properties.put("workGroupVersion", positiveInteger("Current work-group version from get_next_action"));
        properties.put("expectedRevision", Map.of("type", "integer", "minimum", 0, "description", "Current project event revision from get_next_action"));
        properties.put("participant", text("Exact participant handle from get_next_action"));
        properties.put("summary", text("Human-readable summary of completed task work"));
        properties.put("terminalSession", bool("Explicit opt-in: after this lane completes, request irreversible terminalization of this exact provider session; default false"));
        return descriptor(FINISH_LANE,
                "Validates, publishes, integrates, and closes this isolated lane. A no_change outcome is an explicit, evidence-bound completion for a declared clean verification task; it is never inferred from provider exit. An explicit terminal session request is evaluated separately and may be refused when any authority remains.",
                objectSchema(properties, List.of()),
                "finish-lane",
                "MUTATING",
                List.of("SESSION_BINDING", "CLAIM", "SNAPSHOT"),
                9);
    }

    private static Descriptor cancelLane() {
        return descriptor(CANCEL_LANE,
                "Permanently fences and cancels this isolated lane.",
                objectSchema(Map.of("reason", text("Cancellation reason string (1-1000 characters)")), List.of("reason")),
                "cancel-lane",
                "MUTATING",
                List.of("SESSION_BINDING"),
                10);
    }

    private static Descriptor sendMessage() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("to", Map.of("type", "array", "minItems", 1, "maxItems", 15, "items", Map.of("type", "string", "minLength", 1, "maxLength", 1000), "description",
                "Who it is for: each other lane named by a file it claims or by its participant handle, or [\"operator\"] alone for whoever has authority over the project. The same set of lanes is always the same conversation"));
        properties.put("conversation", uuid("A conversation this lane already belongs to, in place of to"));
        properties.put("text", boundedText("What you say. It binds nothing and is not an instruction to whoever reads it"));
        return descriptor(SEND_MESSAGE,
                "Sends text to other lanes or to the operator. A message changes no state: what is agreed takes effect only when it is recorded through the typed protocol. Do not wait for a reply.",
                objectSchema(properties, List.of("text")),
                "send-message",
                "MUTATING",
                List.of("SESSION_BINDING"),
                11);
    }

    private static Map<String, Object> contractSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("purpose", boundedText("What callers use this API for"));
        properties.put("inputs", boundedText("Input names, types, constraints, and defaults"));
        properties.put("output", boundedText("Output type and semantics"));
        properties.put("requiredBehavior", boundedTextList("Required operational behavior"));
        properties.put("errorSemantics", boundedText("Failure cases and observable results"));
        properties.put("sideEffects", boundedText("Externally visible effects"));
        properties.put("invariants", boundedText("Conditions that always remain true"));
        properties.put("compatibility", boundedText("Compatibility and evolution requirements"));
        properties.put("performance", boundedText("Latency and resource requirements"));
        properties.put("concurrency", boundedText("Concurrency, ordering, and consistency guarantees"));
        properties.put("acceptanceTests", boundedTextList("Acceptance criteria for the published implementation"));
        return objectSchema(properties, List.of("purpose", "inputs", "output", "requiredBehavior", "errorSemantics", "sideEffects", "invariants", "compatibility", "performance", "concurrency", "acceptanceTests"));
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

    private static Map<String, Object> uuid() {
        return Map.of("type", "string", "format", "uuid");
    }

    private static Map<String, Object> uuid(String description) {
        return Map.of("type", "string", "format", "uuid", "description", description);
    }

    private static Map<String, Object> text(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> positiveInteger() {
        return Map.of("type", "integer", "minimum", 1);
    }

    private static Map<String, Object> positiveInteger(String description) {
        return Map.of("type", "integer", "minimum", 1, "description", description);
    }

    private static Map<String, Object> enumOf(List<String> values) {
        return Map.of("type", "string", "enum", values);
    }

    private static Map<String, Object> enumOf(List<String> values, String description) {
        return Map.of("type", "string", "enum", values, "description", description);
    }

    private static Map<String, Object> stringArray() {
        return Map.of("type", "array", "items", property("string"));
    }

    private static Map<String, Object> stringArray(String description) {
        return Map.of("type", "array", "items", property("string"), "description", description);
    }

    private static Map<String, Object> selectorArray(String description, Map<String, Object> selector) {
        return Map.of("type", "array", "description", description, "items", selector);
    }

    private static Map<String, Object> requestHandle() {
        return Map.of("type", "string", "pattern", REQUEST_HANDLE_PATTERN);
    }

    private static Map<String, Object> requestHandle(String description) {
        return Map.of("type", "string", "pattern", REQUEST_HANDLE_PATTERN, "description", description);
    }

    private static Map<String, Object> validationHandle() {
        return requestHandle("Exact requester-owned capability handle shown for the current validation");
    }

    private static Map<String, Object> validationRevision() {
        return positiveInteger("Exact implementation revision shown for the current validation");
    }

    private static Map<String, Object> boundedText(String description) {
        return Map.of("type", "string", "minLength", 1, "maxLength", 2000, "description", description);
    }

    private static Map<String, Object> boundedTextList(String description) {
        return Map.of("type", "array", "maxItems", 16, "items", Map.of("type", "string", "minLength", 1, "maxLength", 1000), "description", description);
    }

    /**
     * One kind of a kind/payload request whose payload is restricted to exactly the fields it names.
     */
    private record PayloadKind(String kind, List<String> allowedFields, List<String> requiredFields) {
    }

    /**
     * Builds a {@code kind}/{@code payload} object schema. Each constrained kind gets its own strict
     * alternative, in the order given, followed by one alternative that admits the remaining kinds with
     * the unrestricted payload.
     */
    private static Map<String, Object> kindDiscriminatedSchema(List<String> allKinds, Map<String, Object> payloadProperties, List<PayloadKind> constrained) {
        Map<String, Object> payloadSchema = objectSchema(payloadProperties, List.of());
        Map<String, Object> schema = objectSchema(Map.of("kind", enumOf(allKinds), "payload", payloadSchema), List.of("kind", "payload"));
        List<Map<String, Object>> alternatives = new ArrayList<>();
        for (PayloadKind payloadKind : constrained) {
            Map<String, Object> selected = new LinkedHashMap<>();
            payloadKind.allowedFields().forEach(field -> selected.put(field, payloadProperties.get(field)));
            Map<String, Object> strictPayload = objectSchema(selected, payloadKind.requiredFields());
            strictPayload.put("additionalProperties", false);
            alternatives.add(objectSchema(Map.of("kind", enumOf(List.of(payloadKind.kind())), "payload", strictPayload), List.of("kind", "payload")));
        }
        List<String> constrainedKinds = constrained.stream().map(PayloadKind::kind).toList();
        List<String> otherKinds = allKinds.stream().filter(value -> !constrainedKinds.contains(value)).toList();
        if (!otherKinds.isEmpty()) {
            alternatives.add(objectSchema(Map.of("kind", enumOf(otherKinds), "payload", payloadSchema), List.of("kind", "payload")));
        }
        schema.put("oneOf", List.copyOf(alternatives));
        return schema;
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
    record Identity(String wireCompatibilityDigest, String catalogContentDigest) {

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
    record Descriptor(String wireName, int descriptorVersion, Map<String, Object> inputSchema, Map<String, Object> outputSchema, List<String> stableErrorCodes, Map<String, String> errorRecoverability,
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
