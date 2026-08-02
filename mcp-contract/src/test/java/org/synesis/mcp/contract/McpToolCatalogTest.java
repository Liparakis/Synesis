package org.synesis.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies deterministic, non-circular MCP catalog identities. */
class McpToolCatalogTest {

    @Test
    void exposesTheAuthoritativeRawNames() {
        assertEquals(List.of(
                "ensure_session", "read_file", "apply_patch", "run_command", "get_next_action",
                "request_coordination", "respond_coordination", "publish_capability_implementation",
                "finish_lane", "cancel_lane"), McpToolCatalog.rawNames());
        assertEquals(McpToolCatalog.rawNames().size(), McpToolCatalog.toolsList().size());
    }

    @Test
    void identitiesAreDeterministicAndGuidanceDoesNotRecurse() {
        McpToolCatalog.Identity first = McpToolCatalog.identities(McpToolCatalog.descriptors());
        McpToolCatalog.Identity second = McpToolCatalog.identities(McpToolCatalog.descriptors());
        assertEquals(first, second);
        String rendererInput = McpToolCatalog.guidanceRendererInput("codex");
        assertTrue(rendererInput.contains(McpToolCatalog.wireCompatibilityDigest()));
        assertTrue(rendererInput.contains(McpToolCatalog.catalogContentDigest()));
        assertFalse(rendererInput.contains("guidanceArtifactDigest"));
    }

    @Test
    void descriptionAndGuidanceChangesDoNotChangeWireIdentity() {
        McpToolCatalog.Descriptor original = McpToolCatalog.descriptors().getFirst();
        List<McpToolCatalog.Descriptor> descriptionChange = new ArrayList<>(McpToolCatalog.descriptors());
        descriptionChange.set(0, copy(original, original.description() + " changed", original.inputSchema(),
                original.guidanceTemplates()));
        McpToolCatalog.Identity changedDescription = McpToolCatalog.identities(descriptionChange);
        assertEquals(McpToolCatalog.wireCompatibilityDigest(), changedDescription.wireCompatibilityDigest());
        assertNotEquals(McpToolCatalog.catalogContentDigest(), changedDescription.catalogContentDigest());

        List<McpToolCatalog.Descriptor> guidanceChange = new ArrayList<>(McpToolCatalog.descriptors());
        guidanceChange.set(0, copy(original, original.description(), original.inputSchema(), List.of("new guidance")));
        McpToolCatalog.Identity changedGuidance = McpToolCatalog.identities(guidanceChange);
        assertEquals(McpToolCatalog.wireCompatibilityDigest(), changedGuidance.wireCompatibilityDigest());
        assertNotEquals(McpToolCatalog.catalogContentDigest(), changedGuidance.catalogContentDigest());
    }

    @Test
    void protocolChangesAffectBothIdentities() {
        McpToolCatalog.Descriptor original = McpToolCatalog.descriptors().getFirst();
        Map<String, Object> changedSchema = Map.of("type", "object", "properties", Map.of("changed", Map.of("type", "string")));
        List<McpToolCatalog.Descriptor> changed = new ArrayList<>(McpToolCatalog.descriptors());
        changed.set(0, copy(original, original.description(), changedSchema, original.guidanceTemplates()));
        McpToolCatalog.Identity identities = McpToolCatalog.identities(changed);
        assertNotEquals(McpToolCatalog.wireCompatibilityDigest(), identities.wireCompatibilityDigest());
        assertNotEquals(McpToolCatalog.catalogContentDigest(), identities.catalogContentDigest());
    }

    @Test
    void artifactTamperingChangesOnlyArtifactIdentity() {
        byte[] content = "manual\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String first = McpToolCatalog.guidanceArtifactDigest("synesis-manual", "codex", content);
        String second = McpToolCatalog.guidanceArtifactDigest("synesis-manual", "codex", "tampered\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNotEquals(first, second);
        assertEquals(McpToolCatalog.wireCompatibilityDigest(), McpToolCatalog.wireCompatibilityDigest());
        assertEquals(McpToolCatalog.catalogContentDigest(), McpToolCatalog.catalogContentDigest());
    }

    private static McpToolCatalog.Descriptor copy(McpToolCatalog.Descriptor original, String description,
                                                   Map<String, Object> inputSchema, List<String> guidance) {
        return new McpToolCatalog.Descriptor(original.wireName(), original.descriptorVersion(), inputSchema,
                original.outputSchema(), original.stableErrorCodes(), original.errorRecoverability(),
                original.idempotencySemantics(), original.authorizationRequirements(),
                original.mutabilityClassification(), original.requiredCapabilities(), original.supportedProtocolRange(),
                original.handlerKey(), description, original.documentationMetadata(), original.displayOrder(), guidance,
                original.renderingRules(), original.behavioralInstructions());
    }
}
