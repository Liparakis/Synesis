package org.synesis.mcp.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;

/**
 * The authoritative, dependency-safe MCP wire and guidance catalog.
 *
 * <p>One immutable descriptor set drives wire compatibility, catalog content,
 * generated tools/list entries, and provider-facing guidance inputs. The
 * guidance artifact digest is intentionally computed by the renderer over its
 * output and is never included in either catalog identity.</p>
 *
 * @since 1.0
 */
public final class McpToolCatalog {

    /** MCP session establishment and lane binding tool. */
    public static final String ENSURE_SESSION = "ensure_session";
    /** MCP read tool. */
    public static final String READ_FILE = "read_file";
    /** MCP revision-checked mutation tool. */
    public static final String APPLY_PATCH = "apply_patch";
    /** MCP approved command-intent tool. */
    public static final String RUN_COMMAND = "run_command";
    /** MCP durable inbox/action discovery tool. */
    public static final String GET_NEXT_ACTION = "get_next_action";
    /** MCP coordination request tool. */
    public static final String REQUEST_COORDINATION = "request_coordination";
    /** MCP coordination response and validation tool. */
    public static final String RESPOND_COORDINATION = "respond_coordination";
    /** MCP capability implementation publication tool. */
    public static final String PUBLISH_CAPABILITY_IMPLEMENTATION = "publish_capability_implementation";
    /** MCP isolated lane completion tool. */
    public static final String FINISH_LANE = "finish_lane";
    /** MCP isolated lane cancellation tool. */
    public static final String CANCEL_LANE = "cancel_lane";

    /** Current descriptor schema version. */
    public static final int DESCRIPTOR_VERSION = 1;
    /** Supported MCP protocol range. */
    public static final String PROTOCOL_RANGE = "2025-06-18..2025-06-18";

    private static final Map<String, Object> GENERIC_OUTPUT = objectSchema(
            Map.of("status", property("string"), "result", Map.of("type", "object")), List.of());
    private static final List<Descriptor> DESCRIPTORS = buildDescriptors();
    private static final List<String> RAW_NAMES = DESCRIPTORS.stream().map(Descriptor::wireName).toList();
    private static final String WIRE_DIGEST = digest(canonical(DESCRIPTORS.stream()
            .sorted(Comparator.comparing(Descriptor::wireName))
            .map(Descriptor::wireSemantics)
            .toList()));
    private static final String CONTENT_DIGEST = digest(canonical(DESCRIPTORS.stream()
            .sorted(Comparator.comparingInt(Descriptor::displayOrder))
            .map(Descriptor::contentSemantics)
            .toList()));

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
     * @param artifactRole stable artifact role
     * @param provider provider identifier
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

    /**
     * Immutable MCP descriptor.
     *
     * @param wireName wire name
     * @param descriptorVersion descriptor schema version
     * @param inputSchema input schema
     * @param outputSchema output schema
     * @param stableErrorCodes stable error codes
     * @param errorRecoverability error recoverability by code
     * @param idempotencySemantics idempotency semantics
     * @param authorizationRequirements authorization requirements
     * @param mutabilityClassification mutability classification
     * @param requiredCapabilities required capabilities
     * @param supportedProtocolRange supported protocol range
     * @param handlerKey protocol handler identity
     * @param description display description
     * @param documentationMetadata documentation metadata
     * @param displayOrder display order
     * @param guidanceTemplates guidance templates
     * @param renderingRules renderer rules
     * @param behavioralInstructions agent-facing instructions
     */
    public record Descriptor(
            String wireName,
            int descriptorVersion,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            List<String> stableErrorCodes,
            Map<String, String> errorRecoverability,
            Map<String, String> idempotencySemantics,
            List<String> authorizationRequirements,
            String mutabilityClassification,
            List<String> requiredCapabilities,
            String supportedProtocolRange,
            String handlerKey,
            String description,
            Map<String, Object> documentationMetadata,
            int displayOrder,
            List<String> guidanceTemplates,
            List<String> renderingRules,
            List<String> behavioralInstructions) {

        /** Canonicalizes all descriptor collections. */
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

    private static List<Descriptor> buildDescriptors() {
        List<Descriptor> result = new ArrayList<>();
        result.add(descriptor(ENSURE_SESSION, "Ensures an active, verified Synesis workspace session.",
                objectSchema(Map.of("task", Map.of("type", "object"), "refresh", property("boolean")), List.of()),
                "ensure-session", "MUTATING", List.of("SESSION_BINDING"), 1));
        result.add(descriptor(READ_FILE, "Reads text file content from the assigned worktree.",
                objectSchema(Map.of("path", property("string"), "startLine", property("integer"),
                        "endLine", property("integer"), "maxBytes", property("integer")), List.of("path")),
                "read-file", "READ_ONLY", List.of("SESSION_BINDING"), 2));
        result.add(descriptor(APPLY_PATCH, "Applies a structured file patch to the assigned worktree.",
                objectSchema(Map.of("path", property("string"), "create", property("boolean"),
                        "content", property("string"), "expectedHash", property("string"), "edits", Map.of("type", "array")), List.of("path")),
                "apply-patch", "MUTATING", List.of("SESSION_BINDING", "CLAIM"), 3));
        result.add(descriptor(RUN_COMMAND, "Executes an approved project command intent inside the assigned worktree.",
                objectSchema(Map.of("type", property("string"), "target", property("string"), "arguments", Map.of("type", "array")), List.of("type")),
                "run-command", "MUTATING", List.of("SESSION_BINDING"), 4));
        result.add(descriptor(GET_NEXT_ACTION, "Retrieves the highest-priority actionable coordination item.",
                objectSchema(Map.of("integrationCheck", Map.of("type", "object")), List.of()),
                "get-next-action", "READ_ONLY", List.of("SESSION_BINDING"), 5));
        result.add(descriptor(REQUEST_COORDINATION, "Submits one strict capability or collaboration request.",
                objectSchema(Map.of("kind", property("string"), "payload", Map.of("type", "object")), List.of("kind", "payload")),
                "request-coordination", "MUTATING", List.of("SESSION_BINDING", "COORDINATION"), 6));
        result.add(descriptor(RESPOND_COORDINATION, "Responds to a pending coordination item or validates an implementation.",
                objectSchema(Map.of("kind", property("string"), "payload", Map.of("type", "object")), List.of("kind", "payload")),
                "respond-coordination", "MUTATING", List.of("SESSION_BINDING", "COORDINATION"), 7));
        result.add(descriptor(PUBLISH_CAPABILITY_IMPLEMENTATION, "Publishes an immutable implementation for an accepted capability request.",
                objectSchema(Map.of("capabilityRequestHandle", property("string"), "summary", property("string")), List.of("capabilityRequestHandle")),
                "publish-capability", "MUTATING", List.of("SESSION_BINDING", "CAPABILITY"), 8));
        result.add(descriptor(FINISH_LANE, "Validates, publishes, integrates, and closes this isolated lane.",
                objectSchema(Map.of("summary", property("string")), List.of()),
                "finish-lane", "MUTATING", List.of("SESSION_BINDING", "CLAIM", "SNAPSHOT"), 9));
        result.add(descriptor(CANCEL_LANE, "Permanently fences and cancels this isolated lane.",
                objectSchema(Map.of("reason", property("string")), List.of("reason")),
                "cancel-lane", "MUTATING", List.of("SESSION_BINDING"), 10));
        return List.copyOf(result);
    }

    private static Descriptor descriptor(String name, String description, Map<String, Object> input,
            String handler, String mutability, List<String> capabilities, int order) {
        List<String> errors = List.of("INVALID_REQUEST", "STALE_AUTHORITY", "COORDINATION_BLOCKED", "RETRYABLE_FAILURE");
        Map<String, String> recoverability = Map.of(
                "INVALID_REQUEST", "terminal",
                "STALE_AUTHORITY", "reestablish_session",
                "COORDINATION_BLOCKED", "inspect_inbox",
                "RETRYABLE_FAILURE", "bounded_retry");
        Map<String, String> idempotency = Map.of("default", "server-issued identity and exact-caller idempotent");
        return new Descriptor(name, DESCRIPTOR_VERSION, input, GENERIC_OUTPUT, errors, recoverability, idempotency,
                List.of("EXACT_SESSION_BINDING"), mutability, capabilities, PROTOCOL_RANGE, handler, description,
                Map.of("source", "synesis-mcp-contract", "version", DESCRIPTOR_VERSION), order,
                List.of("follow the durable next action and typed arguments"),
                List.of("render raw tool names without provider namespace decoration"),
                List.of("never guess identifiers or retry failed mutations blindly"));
    }

    private static Map<String, Object> property(String type) {
        return Map.of("type", type);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "object");
        value.put("properties", properties);
        if (!required.isEmpty()) value.put("required", required);
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
        if (value instanceof List<?> list) return List.copyOf(list.stream().map(McpToolCatalog::freezeObject).toList());
        return value;
    }

    private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof String string) return quote(string);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(String::valueOf)))
                    .map(entry -> quote(String.valueOf(entry.getKey())) + ":" + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) return list.stream().map(McpToolCatalog::canonical)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
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
                    if (codePoint < 0x20) builder.append(String.format("\\u%04x", codePoint));
                    else builder.appendCodePoint(codePoint);
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
}
