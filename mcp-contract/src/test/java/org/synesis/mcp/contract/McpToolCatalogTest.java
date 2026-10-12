package org.synesis.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies deterministic, non-circular MCP catalog identities.
 */
class McpToolCatalogTest {

  private static McpToolCatalog.Descriptor copy(McpToolCatalog.Descriptor original,
      String description,
      Map<String, Object> inputSchema, List<String> guidance) {
    return new McpToolCatalog.Descriptor(original.wireName(), original.descriptorVersion(),
        inputSchema,
        original.outputSchema(), original.stableErrorCodes(), original.errorRecoverability(),
        original.idempotencySemantics(), original.authorizationRequirements(),
        original.mutabilityClassification(), original.requiredCapabilities(),
        original.supportedProtocolRange(),
        original.handlerKey(), description, original.documentationMetadata(),
        original.displayOrder(), guidance,
        original.renderingRules(), original.behavioralInstructions());
  }

  @Test
  void exposesTheAuthoritativeRawNames() {
    assertEquals(List.of(
        "ensure_session", "read_file", "apply_patch", "run_command", "get_next_action",
        "request_coordination", "respond_coordination", "publish_capability_implementation",
        "finish_lane", "cancel_lane", "send_message"), McpToolCatalog.rawNames());
    assertEquals(McpToolCatalog.rawNames()
            .size(),
        McpToolCatalog.toolsList()
            .size());
  }

  @Test
  void runCommandUsesOnlyDirectArgvAndExposesCompleteEvidenceMetadata() {
    McpToolCatalog.Descriptor descriptor = McpToolCatalog.descriptors()
        .stream()
        .filter(candidate -> candidate.wireName()
            .equals(McpToolCatalog.RUN_COMMAND))
        .findFirst()
        .orElseThrow();
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
    Map<String, Object> envelopeProperties = (Map<String, Object>) descriptor.outputSchema()
        .get("properties");
    @SuppressWarnings("unchecked")
    Map<String, Object> outputProperties = (Map<String, Object>) ((Map<String, Object>) envelopeProperties.get(
        "result"))
        .get("properties");
    assertTrue(outputProperties.keySet()
        .containsAll(List.of(
            "stdout", "stderr", "stdoutBytesRead", "stderrBytesRead",
            "stdoutBytesRetained", "stderrBytesRetained", "stdoutTruncated", "stderrTruncated")));
  }

  @Test
  void getNextActionExplainsOrdinaryImplementationWhenNoLifecycleActionExists() {
    McpToolCatalog.Descriptor descriptor = McpToolCatalog.descriptors()
        .stream()
        .filter(candidate -> candidate.wireName()
            .equals(McpToolCatalog.GET_NEXT_ACTION))
        .findFirst()
        .orElseThrow();
    assertTrue(descriptor.description()
        .contains("workflow IMPLEMENT"));
    assertTrue(descriptor.description()
        .contains("recommendedTool and arguments"));
    assertTrue(descriptor.description()
        .contains("ordinary coding"));
    assertTrue(descriptor.description()
        .contains("completionRequested:true"));
    assertTrue(descriptor.description()
        .contains("Missing or false continues implementation"));
    assertTrue(descriptor.description()
        .contains("workflow.actionId"));
    assertTrue(descriptor.description()
        .contains("workflow.retryAfterMs (default 5000 ms)"));
    assertTrue(descriptor.description()
        .contains("workflow.maxUnchangedPolls unchanged responses (default 3)"));
    assertTrue(descriptor.description()
        .contains("stop after workflow.maxUnchangedPolls unchanged responses"));
    assertTrue(descriptor.description()
        .contains("Reset the count when actionId changes"));
    assertTrue(descriptor.description()
        .contains(".synesis/**"));
    assertTrue(descriptor.description()
        .contains("concrete recommendedTool with its exact arguments"));
    assertTrue(descriptor.description()
        .contains("integrationCheck"));
    assertTrue(descriptor.description()
        .contains("never advances a lane or WorkGroup"));
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) descriptor.inputSchema()
        .get("properties");
    assertTrue(properties.containsKey("completionRequested"));
    assertFalse(properties.containsKey("completionMode"));
    assertTrue(String.valueOf(properties.get("completionRequested"))
        .contains("call only"));
    @SuppressWarnings("unchecked")
    Map<String, Object> integrationCheck = (Map<String, Object>) properties.get("integrationCheck");
    assertTrue(String.valueOf(integrationCheck.get("description"))
        .contains("never advances lifecycle"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void ensureSessionExplainsThatClaimsAnnounceIntentAndLikelyScopesDoNot() {
    McpToolCatalog.Descriptor descriptor = McpToolCatalog.descriptors()
        .stream()
        .filter(candidate -> candidate.wireName()
            .equals(McpToolCatalog.ENSURE_SESSION))
        .findFirst()
        .orElseThrow();
    assertTrue(descriptor.description()
        .contains("task.claims"));
    assertTrue(descriptor.description()
        .contains("acquire"));
    assertTrue(descriptor.description()
        .contains("likelyScopes alone does not announce"));
    Map<String, Object> task = (Map<String, Object>) ((Map<String, Object>) descriptor.inputSchema()
        .get("properties")).get("task");
    Map<String, Object> properties = (Map<String, Object>) task.get("properties");
    assertTrue(String.valueOf(properties.get("claims"))
        .contains("Intent and ownership selectors"));
    assertTrue(String.valueOf(properties.get("knownDependencies"))
        .contains("Explicit capability identifiers"));
    assertTrue(String.valueOf(properties.get("likelyScopes"))
        .contains("do not announce intent"));
    assertTrue(properties.containsKey("intentId"));
    assertTrue(properties.containsKey("claimEpoch"));
    assertTrue(descriptor.description().contains("exact task.intentId and task.claimEpoch"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void advertisesDistinctCoordinationResponseAndReviewValidationChoices() {
    McpToolCatalog.Descriptor descriptor = McpToolCatalog.descriptors()
        .stream()
        .filter(candidate -> candidate.wireName()
            .equals(McpToolCatalog.RESPOND_COORDINATION))
        .findFirst()
        .orElseThrow();
    Map<String, Object> properties = (Map<String, Object>) descriptor.inputSchema()
        .get("properties");
    Map<String, Object> kind = (Map<String, Object>) properties.get("kind");
    assertEquals(List.of("kind", "payload"), descriptor.inputSchema().get("required"));
    assertTrue(((List<String>) kind.get("enum")).contains("coordination_response"));
    assertTrue(((List<String>) kind.get("enum")).contains("review_validation"));
    Map<String, Object> payload = (Map<String, Object>) properties.get("payload");
    Map<String, Object> payloadProperties = (Map<String, Object>) payload.get("properties");
    assertEquals("uuid", ((Map<?, ?>) payloadProperties.get("coordinationRequest")).get("format"));
    assertEquals(List.of("ACCEPTED", "REVISED", "REJECTED", "CANCELLED", "COMPLETED"),
        ((Map<?, ?>) payloadProperties.get("coordinationStatus")).get("enum"));
    assertTrue(payloadProperties.keySet()
        .containsAll(List.of(
            "grantId", "snapshotId", "intentId", "claimEpoch", "result")));
    Map<String, Object> result = (Map<String, Object>) payloadProperties.get("result");
    assertTrue(((List<String>) result.get("enum")).contains("rejected"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void requestCoordinationCarriesBootstrapReviewPolicyAndExactSnapshotChoice() {
    assertEquals(11, McpToolCatalog.rawNames().size());
    McpToolCatalog.Descriptor descriptor = McpToolCatalog.descriptors().stream()
        .filter(candidate -> candidate.wireName().equals(McpToolCatalog.REQUEST_COORDINATION))
        .findFirst().orElseThrow();
    Map<String, Object> properties = (Map<String, Object>) descriptor.inputSchema()
        .get("properties");
    Map<String, Object> kind = (Map<String, Object>) properties.get("kind");
    assertTrue(((List<String>) kind.get("enum")).contains("work_group_create"));
    Map<String, Object> payload = (Map<String, Object>) properties.get("payload");
    Map<String, Object> payloadProperties = (Map<String, Object>) payload.get("properties");
    assertTrue(payloadProperties.containsKey("snapshotId"));
    Map<String, Object> policy = (Map<String, Object>) payloadProperties.get("reviewPolicy");
    Map<String, Object> policyProperties = (Map<String, Object>) policy.get("properties");
    assertTrue(policyProperties.keySet().containsAll(List.of("mandatory", "independenceRequired")));
  }

  @Test
  void contractBindAdvertisesItsStrictPayloadInsteadOfOtherKindsFields() {
    Map<String, Object> schema = payloadForKind(McpToolCatalog.REQUEST_COORDINATION,
        "contract_bind");
    assertEquals(List.of("capability", "ownerPath", "revision"), schema.get("required"));
    assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
    assertEquals(java.util.Set.of("capability", "ownerPath", "revision"),
        ((Map<?, ?>) schema.get("properties")).keySet());
  }

  @Test
  void contractPublishAndChangeRequestAdvertiseTheirStrictPayloads() {
    Map<String, Object> publish = payloadForKind(McpToolCatalog.REQUEST_COORDINATION,
        "contract_publish");
    assertEquals(List.of("paths", "contract"), publish.get("required"));
    assertEquals(Boolean.FALSE, publish.get("additionalProperties"));
    assertEquals(java.util.Set.of("paths", "contract", "answers"),
        ((Map<?, ?>) publish.get("properties")).keySet());
    Map<String, Object> change = payloadForKind(McpToolCatalog.REQUEST_COORDINATION,
        "contract_change_request");
    assertEquals(List.of("ownerPath", "need"), change.get("required"));
    assertEquals(Boolean.FALSE, change.get("additionalProperties"));
    assertEquals(java.util.Set.of("ownerPath", "need"),
        ((Map<?, ?>) change.get("properties")).keySet());
  }

  @Test
  void contractChangeResponseAdvertisesTheDecisionAndTheValidationNamesTheContractField() {
    Map<String, Object> response = payloadForKind(McpToolCatalog.RESPOND_COORDINATION,
        "contract_change_response");
    assertEquals(List.of("changeRequest", "decision"), response.get("required"));
    assertEquals(Boolean.FALSE, response.get("additionalProperties"));
    assertEquals(java.util.Set.of("changeRequest", "decision", "reason"),
        ((Map<?, ?>) response.get("properties")).keySet());
    Map<String, Object> validation = payloadForKind(McpToolCatalog.RESPOND_COORDINATION,
        "implementation_validation");
    assertTrue(((Map<?, ?>) validation.get("properties")).containsKey("contractField"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void theRemovedNegotiationKindsAreNotAdvertised() {
    for (String tool : List.of(McpToolCatalog.REQUEST_COORDINATION, McpToolCatalog.RESPOND_COORDINATION)) {
      Map<String, Object> input = McpToolCatalog.descriptors().stream()
          .filter(descriptor -> descriptor.wireName().equals(tool)).findFirst().orElseThrow()
          .inputSchema();
      List<String> kinds = (List<String>) ((Map<String, Object>) ((Map<String, Object>) input
          .get("properties")).get("kind")).get("enum");
      for (String removed : List.of("capability_request", "contract_proposal", "contract_request",
          "capability_response")) {
        assertFalse(kinds.contains(removed), tool + " still advertises " + removed);
      }
    }
  }

  @Test
  void coordinationResponseAdvertisesOwnerDecisionFieldsWithoutChoosingAStatus() {
    Map<String, Object> schema = payloadForKind(McpToolCatalog.RESPOND_COORDINATION,
        "coordination_response");
    assertEquals(List.of("coordinationRequest", "coordinationStatus"), schema.get("required"));
    assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
    Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
    assertEquals(java.util.Set.of("coordinationRequest", "coordinationStatus", "proposal"),
        properties.keySet());
    Map<?, ?> status = (Map<?, ?>) properties.get("coordinationStatus");
    assertEquals(List.of("ACCEPTED", "REVISED", "REJECTED", "CANCELLED", "COMPLETED"),
        status.get("enum"));
    assertFalse(status.containsKey("default"));
  }

  @Test
  void implementationValidationRequiresExactInspectionTargetAndAllowsOnlySupportedStages() {
    Map<String, Object> payload = payloadForKind(McpToolCatalog.RESPOND_COORDINATION, "implementation_validation");
    assertEquals(List.of("inboxItemId", "capabilityRequestHandle", "implementationRevision", "result"), payload.get("required"));
    Map<?, ?> result = (Map<?, ?>) ((Map<?, ?>) payload.get("properties")).get("result");
    assertTrue(((List<?>) result.get("enum")).containsAll(List.of("inspect", "accepted", "revision_required")));
  }

  @Test
  void readAndCommandToolsAcceptExplicitValidationSelectionFields() {
    Map<String, Object> read = McpToolCatalog.descriptors().stream()
        .filter(descriptor -> descriptor.wireName().equals(McpToolCatalog.READ_FILE))
        .findFirst()
        .orElseThrow()
        .inputSchema();
    Map<String, Object> command = McpToolCatalog.descriptors().stream()
        .filter(descriptor -> descriptor.wireName().equals(McpToolCatalog.RUN_COMMAND))
        .findFirst()
        .orElseThrow()
        .inputSchema();
    for (Map<String, Object> schema : List.of(read, command)) {
      Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
      assertEquals("string", ((Map<?, ?>) properties.get("capabilityRequestHandle")).get("type"));
      assertEquals("integer", ((Map<?, ?>) properties.get("implementationRevision")).get("type"));
      assertFalse(((List<?>) schema.get("required")).contains("capabilityRequestHandle"));
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> payloadForKind(String tool, String kind) {
    Map<String, Object> input = McpToolCatalog.descriptors().stream()
        .filter(descriptor -> descriptor.wireName().equals(tool)).findFirst().orElseThrow()
        .inputSchema();
    List<Map<String, Object>> alternatives = (List<Map<String, Object>>) input.get("oneOf");
    assertTrue(alternatives != null, "the published schema must distinguish strict payload kinds");
    Map<String, Object> matching = alternatives.stream().filter(alternative -> {
      Map<?, ?> properties = (Map<?, ?>) alternative.get("properties");
      return List.of(kind).equals(((Map<?, ?>) properties.get("kind")).get("enum"));
    }).findFirst().orElseThrow();
    assertEquals(1, alternatives.stream().filter(alternative -> {
      Map<?, ?> properties = (Map<?, ?>) alternative.get("properties");
      return ((List<?>) ((Map<?, ?>) properties.get("kind")).get("enum")).contains(kind);
    }).count());
    return (Map<String, Object>) ((Map<?, ?>) matching.get("properties")).get("payload");
  }

  @Test
  @SuppressWarnings("unchecked")
  void finishLaneMakesTerminalSessionSealingExplicitAndOptIn() {
    McpToolCatalog.Descriptor descriptor = McpToolCatalog.descriptors()
        .stream()
        .filter(candidate -> candidate.wireName()
            .equals(McpToolCatalog.FINISH_LANE))
        .findFirst()
        .orElseThrow();
    Map<String, Object> properties = (Map<String, Object>) descriptor.inputSchema()
        .get("properties");
    assertTrue(properties.containsKey("terminalSession"));
    assertTrue(String.valueOf(properties.get("terminalSession"))
        .contains("opt-in"));
    assertTrue(descriptor.description()
        .contains("terminal session"));
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
    McpToolCatalog.Descriptor original = McpToolCatalog.descriptors()
        .getFirst();
    List<McpToolCatalog.Descriptor> descriptionChange = new ArrayList<>(
        McpToolCatalog.descriptors());
    descriptionChange.set(0,
        copy(original, original.description() + " changed", original.inputSchema(),
            original.guidanceTemplates()));
    McpToolCatalog.Identity changedDescription = McpToolCatalog.identities(descriptionChange);
    assertEquals(McpToolCatalog.wireCompatibilityDigest(),
        changedDescription.wireCompatibilityDigest());
    assertNotEquals(McpToolCatalog.catalogContentDigest(),
        changedDescription.catalogContentDigest());

    List<McpToolCatalog.Descriptor> guidanceChange = new ArrayList<>(McpToolCatalog.descriptors());
    guidanceChange.set(0,
        copy(original, original.description(), original.inputSchema(), List.of("new guidance")));
    McpToolCatalog.Identity changedGuidance = McpToolCatalog.identities(guidanceChange);
    assertEquals(McpToolCatalog.wireCompatibilityDigest(),
        changedGuidance.wireCompatibilityDigest());
    assertNotEquals(McpToolCatalog.catalogContentDigest(), changedGuidance.catalogContentDigest());
  }

  @Test
  void protocolChangesAffectBothIdentities() {
    McpToolCatalog.Descriptor original = McpToolCatalog.descriptors()
        .getFirst();
    Map<String, Object> changedSchema = Map.of("type",
        "object",
        "properties",
        Map.of("changed", Map.of("type", "string")));
    List<McpToolCatalog.Descriptor> changed = new ArrayList<>(McpToolCatalog.descriptors());
    changed.set(0,
        copy(original, original.description(), changedSchema, original.guidanceTemplates()));
    McpToolCatalog.Identity identities = McpToolCatalog.identities(changed);
    assertNotEquals(McpToolCatalog.wireCompatibilityDigest(), identities.wireCompatibilityDigest());
    assertNotEquals(McpToolCatalog.catalogContentDigest(), identities.catalogContentDigest());
  }

  @Test
  void artifactTamperingChangesOnlyArtifactIdentity() {
    byte[] content = "manual\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String first = McpToolCatalog.guidanceArtifactDigest("synesis-manual", "codex", content);
    String second = McpToolCatalog.guidanceArtifactDigest("synesis-manual",
        "codex",
        "tampered\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertNotEquals(first, second);
    assertFalse(McpToolCatalog.wireCompatibilityDigest()
        .isBlank());
    assertFalse(McpToolCatalog.catalogContentDigest()
        .isBlank());
  }
}
