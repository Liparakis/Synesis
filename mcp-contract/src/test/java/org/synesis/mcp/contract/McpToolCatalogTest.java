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
    void runCommandUsesOnlyDirectArgvAndExposesCompleteEvidenceMetadata() {
        McpToolCatalog.Descriptor descriptor = McpToolCatalog.descriptors().stream()
                .filter(candidate -> candidate.wireName().equals(McpToolCatalog.RUN_COMMAND))
                .findFirst().orElseThrow();
        Map<String, Object> input = descriptor.inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) input.get("properties");
        assertTrue(properties.containsKey("argv"));
        assertTrue(properties.containsKey("workingDirectory"));
        assertTrue(properties.containsKey("timeoutSeconds"));
        assertFalse(properties.containsKey("type"));
        assertFalse(properties.containsKey("target"));
        assertFalse(properties.containsKey("arguments"));

        @SuppressWarnings("unchecked")
        Map<String, Object> envelopeProperties = (Map<String, Object>) descriptor.outputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> outputProperties = (Map<String, Object>) ((Map<String, Object>) envelopeProperties.get("result"))
                .get("properties");
        assertTrue(outputProperties.keySet().containsAll(List.of(
                "stdout", "stderr", "stdoutBytesRead", "stderrBytesRead",
                "stdoutBytesRetained", "stderrBytesRetained", "stdoutTruncated", "stderrTruncated")));
    }

    @Test
    void getNextActionExplainsOrdinaryImplementationWhenNoLifecycleActionExists() {
        McpToolCatalog.Descriptor descriptor = McpToolCatalog.descriptors().stream()
                .filter(candidate -> candidate.wireName().equals(McpToolCatalog.GET_NEXT_ACTION))
                .findFirst().orElseThrow();
        assertTrue(descriptor.description().contains("workflow IMPLEMENT"));
        assertTrue(descriptor.description().contains("recommendedTool and arguments"));
        assertTrue(descriptor.description().contains("ordinary coding"));
        assertTrue(descriptor.description().contains(".synesis/**"));
        assertTrue(descriptor.description().contains("exact tool with those exact arguments"));
        assertTrue(descriptor.description().contains("integrationCheck"));
        assertTrue(descriptor.description().contains("never advances a lane or WorkGroup"));
        assertTrue(descriptor.description().contains("When WAIT projects get_next_action"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) descriptor.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> integrationCheck = (Map<String, Object>) properties.get("integrationCheck");
        assertTrue(String.valueOf(integrationCheck.get("description")).contains("never advances lifecycle"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ensureSessionExplainsThatClaimsAnnounceIntentAndLikelyScopesDoNot() {
        McpToolCatalog.Descriptor descriptor = McpToolCatalog.descriptors().stream()
                .filter(candidate -> candidate.wireName().equals(McpToolCatalog.ENSURE_SESSION))
                .findFirst().orElseThrow();
        assertTrue(descriptor.description().contains("task.claims"));
        assertTrue(descriptor.description().contains("acquire"));
        assertTrue(descriptor.description().contains("likelyScopes alone does not announce"));
        Map<String, Object> task = (Map<String, Object>) ((Map<String, Object>) descriptor.inputSchema()
                .get("properties")).get("task");
        Map<String, Object> properties = (Map<String, Object>) task.get("properties");
        assertTrue(String.valueOf(properties.get("claims")).contains("Intent and ownership selectors"));
        assertTrue(String.valueOf(properties.get("likelyScopes")).contains("do not announce intent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void advertisesTheExistingReviewValidationKindAndPayloadFields() {
        McpToolCatalog.Descriptor descriptor = McpToolCatalog.descriptors().stream()
                .filter(candidate -> candidate.wireName().equals(McpToolCatalog.RESPOND_COORDINATION))
                .findFirst().orElseThrow();
        Map<String, Object> properties = (Map<String, Object>) descriptor.inputSchema().get("properties");
        Map<String, Object> kind = (Map<String, Object>) properties.get("kind");
        assertTrue(((List<String>) kind.get("enum")).contains("review_validation"));
        Map<String, Object> payload = (Map<String, Object>) properties.get("payload");
        Map<String, Object> payloadProperties = (Map<String, Object>) payload.get("properties");
        assertTrue(payloadProperties.keySet().containsAll(List.of(
                "grantId", "snapshotId", "intentId", "claimEpoch", "result")));
        Map<String, Object> result = (Map<String, Object>) payloadProperties.get("result");
        assertTrue(((List<String>) result.get("enum")).contains("rejected"));
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
