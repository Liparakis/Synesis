package org.synesis.workspace.application.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.workspace.WorkspaceMutationBroker.Decision;
import org.synesis.workspace.application.workspace.WorkspaceMutationBroker.MutationResult;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentStatus;

class AgentOutcomeTranslatorTest {

    private AgentOutcomeTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new AgentOutcomeTranslator();
    }

    @Test
    void testTranslateAllowMutation() {
        MutationResult internalResult = new MutationResult(
                true,
                Decision.ALLOW,
                "ALLOWED",
                "Mutation successful",
                "dec-12345",
                "sha256-evidence-abc",
                Path.of("C:\\worktree\\src\\example.txt"),
                true
        );

        TranslatedOutcome outcome = translator.translateMutationResult(internalResult, "src/example.txt");

        assertEquals(AgentStatus.COMPLETED, outcome.publicResponse().status());
        assertNull(outcome.publicResponse().reason());
        assertNull(outcome.publicResponse().nextAction());
        assertNotNull(outcome.publicResponse().result());
        assertTrue(outcome.publicResponse().toJson().contains("\"path\":\"src/example.txt\""));

        // Verify diagnostic correlation retained internally
        assertEquals(Decision.ALLOW, outcome.internalDecision());
        assertEquals("dec-12345", outcome.decisionId());
        assertEquals("sha256-evidence-abc", outcome.evidenceHash());
        assertFalse(outcome.safeToRetry());
        assertFalse(outcome.waitRequired());
        assertFalse(outcome.humanInterventionRequired());

        // Verify public JSON hides internal diagnostic fields
        String json = outcome.publicResponse().toJson();
        assertFalse(json.contains("dec-12345"));
        assertFalse(json.contains("sha256-evidence-abc"));
        assertFalse(json.contains("worktree"));
        assertFalse(json.contains("ALLOW"));
    }

    @Test
    void testTranslateProtectedConfigurationTarget() {
        MutationResult internalResult = new MutationResult(
                false,
                Decision.DENY_POLICY,
                "PROTECTED_CONFIGURATION_TARGET",
                "Protected target",
                "dec-9999",
                "sha-evidence",
                null,
                true
        );

        TranslatedOutcome outcome = translator.translateMutationResult(internalResult, ".synesis/project.json");

        assertEquals(AgentStatus.BLOCKED, outcome.publicResponse().status());
        assertEquals(AgentReason.PROTECTED_CONFIGURATION, outcome.publicResponse().reason());
        assertNull(outcome.publicResponse().nextAction());
        assertNull(outcome.publicResponse().result());

        String json = outcome.publicResponse().toJson();
        assertTrue(json.contains("\"status\":\"blocked\""));
        assertTrue(json.contains("\"reason\":\"protected_configuration\""));
        assertFalse(json.contains("dec-9999"));
    }

    @Test
    void testTranslateGeneralPolicyDenial() {
        MutationResult internalResult = new MutationResult(
                false,
                Decision.DENY_POLICY,
                "POLICY_BLOCKED",
                "Blocked by guardrail policy",
                "dec-8888",
                "sha-evidence",
                null,
                true
        );

        TranslatedOutcome outcome = translator.translateMutationResult(internalResult, "src/Main.java");

        assertEquals(AgentStatus.BLOCKED, outcome.publicResponse().status());
        assertEquals(AgentReason.POLICY_DENIED, outcome.publicResponse().reason());
    }

    @Test
    void testTranslateRequestOwner() {
        MutationResult internalResult = new MutationResult(
                false,
                Decision.REQUEST_OWNER,
                "REQUEST_OWNER",
                "Capability owner required capability: catalog.product-query",
                "dec-7777",
                "sha-evidence",
                null,
                true
        );

        TranslatedOutcome outcome = translator.translateMutationResult(internalResult, "src/Catalog.java");

        assertEquals(AgentStatus.NEEDS_CAPABILITY, outcome.publicResponse().status());
        assertEquals(AgentReason.OWNER_REQUIRED, outcome.publicResponse().reason());
        assertEquals(AgentNextAction.DESCRIBE_REQUIRED_CAPABILITY, outcome.publicResponse().nextAction());
        assertTrue(outcome.waitRequired());

        String json = outcome.publicResponse().toJson();
        assertTrue(json.contains("\"capability\":\"catalog.product-query\""));
        assertFalse(json.contains("dec-7777"));
    }

    @Test
    void testTranslateWorkspaceUnverified() {
        MutationResult internalResult = new MutationResult(
                false,
                Decision.WORKSPACE_UNVERIFIED,
                "WORKSPACE_NOT_VERIFIED",
                "Workspace trust state is UNVERIFIED",
                "dec-6666",
                "sha-evidence",
                null,
                true
        );

        TranslatedOutcome outcome = translator.translateMutationResult(internalResult, "src/Main.java");

        assertEquals(AgentStatus.RETRY_REQUIRED, outcome.publicResponse().status());
        assertEquals(AgentReason.WORKSPACE_NOT_READY, outcome.publicResponse().reason());
        assertEquals(AgentNextAction.ENSURE_SESSION, outcome.publicResponse().nextAction());
        assertTrue(outcome.safeToRetry());
    }

    @Test
    void testTranslateSessionUnbound() {
        MutationResult internalResult = new MutationResult(
                false,
                Decision.SESSION_UNBOUND,
                "SESSION_NOT_BOUND",
                "Session is unbound",
                "dec-5555",
                "sha-evidence",
                null,
                true
        );

        TranslatedOutcome outcome = translator.translateMutationResult(internalResult, "src/Main.java");

        assertEquals(AgentStatus.RETRY_REQUIRED, outcome.publicResponse().status());
        assertEquals(AgentReason.SESSION_NOT_READY, outcome.publicResponse().reason());
        assertEquals(AgentNextAction.ENSURE_SESSION, outcome.publicResponse().nextAction());
        assertTrue(outcome.safeToRetry());
    }

    @Test
    void testTranslateStaleContext() {
        MutationResult internalResult = new MutationResult(
                false,
                Decision.STALE_CONTEXT,
                "STALE_CONTEXT",
                "Stale workspace context",
                "dec-4444",
                "sha-evidence",
                null,
                true
        );

        TranslatedOutcome outcome = translator.translateMutationResult(internalResult, "src/Main.java");

        assertEquals(AgentStatus.RETRY_REQUIRED, outcome.publicResponse().status());
        assertEquals(AgentReason.WORKSPACE_STALE, outcome.publicResponse().reason());
        assertEquals(AgentNextAction.ENSURE_SESSION, outcome.publicResponse().nextAction());
        assertTrue(outcome.safeToRetry());
    }

    @Test
    void testTranslateInvalidTarget() {
        MutationResult internalResult = new MutationResult(
                false,
                Decision.INVALID_TARGET,
                "PATH_TRAVERSAL_REJECTED",
                "Path traversal rejected",
                "dec-3333",
                "sha-evidence",
                null,
                true
        );

        TranslatedOutcome outcome = translator.translateMutationResult(internalResult, "../secret.txt");

        assertEquals(AgentStatus.BLOCKED, outcome.publicResponse().status());
        assertEquals(AgentReason.INVALID_PATH, outcome.publicResponse().reason());
    }

    @Test
    void testTranslateInterceptionMissing() {
        MutationResult internalResult = new MutationResult(
                false,
                Decision.INTERCEPTION_MISSING,
                "HOOK_NOT_INTERCEPTED",
                "Missing hook interception",
                "dec-2222",
                "sha-evidence",
                null,
                true
        );

        TranslatedOutcome outcome = translator.translateMutationResult(internalResult, "src/Main.java");

        assertEquals(AgentStatus.BLOCKED, outcome.publicResponse().status());
        assertEquals(AgentReason.INTERCEPTION_REQUIRED, outcome.publicResponse().reason());
    }

    @Test
    void testTranslatePendingOwner() {
        TranslatedOutcome outcome = translator.translatePendingOwner();

        assertEquals(AgentStatus.WAITING, outcome.publicResponse().status());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, outcome.publicResponse().reason());
        assertEquals(AgentNextAction.WAIT, outcome.publicResponse().nextAction());
        assertTrue(outcome.waitRequired());
    }

    @Test
    void testTranslateExceptionDoesNotLeakRawMessage() {
        Exception ex = new RuntimeException("Internal SQL syntax error at line 42");

        TranslatedOutcome outcome = translator.translateException(ex);

        assertEquals(AgentStatus.FAILED, outcome.publicResponse().status());
        assertEquals(AgentReason.INTERNAL_FAILURE, outcome.publicResponse().reason());
        assertEquals(AgentNextAction.REQUEST_HUMAN_HELP, outcome.publicResponse().nextAction());
        assertTrue(outcome.humanInterventionRequired());

        String json = outcome.publicResponse().toJson();
        assertFalse(json.contains("SQL syntax error"));
        assertFalse(json.contains("line 42"));
    }

    @Test
    void testTranslateNullMutationResultFailsSafely() {
        TranslatedOutcome outcome = translator.translateMutationResult(null, "src/Main.java");

        assertEquals(AgentStatus.FAILED, outcome.publicResponse().status());
        assertEquals(AgentReason.INTERNAL_FAILURE, outcome.publicResponse().reason());
    }
}
