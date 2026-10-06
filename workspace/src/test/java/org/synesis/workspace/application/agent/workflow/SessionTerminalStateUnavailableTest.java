package org.synesis.workspace.application.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;

import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.ProviderSessionTerminalizationService;
import org.synesis.workspace.doctor.DoctorFindingCode;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.test.ProviderTestSupport;
import org.synesis.workspace.test.TestGit;

/**
 * Covers fail-closed reporting when the exact session terminal fence cannot be read.
 */
class SessionTerminalStateUnavailableTest {

    private static ProjectLocation project(Path root) throws Exception {
        Files.createDirectories(root);
        TestGit.run(root, "init");
        TestGit.run(root, "config", "user.name", "Synesis Test");
        TestGit.run(root, "config", "user.email", "synesis-test@example.invalid");
        Files.writeString(root.resolve("README.md"), "baseline\n");
        TestGit.run(root, "add", "README.md");
        TestGit.run(root, "commit", "-m", "baseline");
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        ProviderTestSupport.install(location, "codex");
        return location;
    }

    @Test
    void unreadableTerminalFenceIsNonRetryableAndDoesNotExposeInternalDetails(@TempDir Path tempDir) throws Exception {
        ProjectLocation location = project(tempDir.resolve("broken-log"));
        AgentSessionService sessions = new AgentSessionService();
        SessionResolutionRequest request = new SessionResolutionRequest(location.root(), "codex", "fresh-connection", null, false);

        assertEquals(AgentStatus.READY, sessions.ensureSession(request).status());
        Path events = location.root().resolve(".synesis/coordination/events");
        Files.createDirectories(events);
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        byte[] tamperedEvent = PredictionEvent.create(location.projectId(),
                UUID.randomUUID(),
                1L,
                PredictionEventType.DEPENDENCY_INVALIDATED,
                identity.nodeId(),
                new byte[0],
                new byte[32],
                identity,
                1L).encoded();
        tamperedEvent[tamperedEvent.length - 1] ^= 1;
        Files.write(events.resolve("00000000000000000001.sce"), tamperedEvent);
        DoctorFindingCode doctorCause = new DoctorService().diagnose(location.root()).findings().stream()
                .map(finding -> finding.code())
                .filter(code -> code == DoctorFindingCode.EVENT_LOG_VERIFICATION_FAILURE || code == DoctorFindingCode.DURABLE_STATE_AMBIGUOUS)
                .findFirst()
                .orElseThrow();
        assertEquals(DoctorFindingCode.EVENT_LOG_VERIFICATION_FAILURE, doctorCause);

        var action = new AgentNextActionService().getNextAction(new NextActionRequest(location.root(), "codex", "fresh-connection"));
        assertEquals(AgentStatus.FAILED, action.status());
        assertEquals(AgentReason.INTERNAL_FAILURE, action.reason());
        assertEquals(AgentNextAction.REQUEST_HUMAN_HELP, action.nextAction());
        Map<?, ?> actionResult = (Map<?, ?>) action.result();
        assertEquals("SESSION_TERMINAL_STATE_UNAVAILABLE", actionResult.get("reason"));
        assertNotNull(actionResult.get("causeType"));
        assertEquals(doctorCause.value(), actionResult.get("causeCode"));

        Map<?, ?> workflow = (Map<?, ?>) actionResult.get("workflow");
        assertEquals("RECOVER", workflow.get("type"));
        assertEquals(java.util.List.of("session_terminal_state_unavailable"), workflow.get("blockers"));
        assertEquals(java.util.List.of(), workflow.get("permittedOperations"));
        assertFalse((Boolean) workflow.get("retrySafe"));
        assertFalse(workflow.containsKey("recommendedTool"));
        assertFalse(workflow.containsKey("arguments"));
        assertFalse(action.toJson().contains(events.toString()));

        var retry = sessions.ensureSession(request);
        assertEquals(AgentStatus.FAILED, retry.status());
        Map<?, ?> retryResult = (Map<?, ?>) retry.result();
        assertEquals("SESSION_TERMINAL_STATE_UNAVAILABLE", retryResult.get("reason"));
        assertNotNull(retryResult.get("causeType"));
        assertEquals(doctorCause.value(), retryResult.get("causeCode"));
        assertEquals(AgentNextAction.REQUEST_HUMAN_HELP, retry.nextAction());
    }

    @Test
    void freshSessionDoesNotRequirePredecessorTerminalRecord(@TempDir Path tempDir) throws Exception {
        ProjectLocation location = project(tempDir.resolve("fresh-session"));
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        String predecessorConnection = "predecessor";
        ProviderSessionBindingService.Binding predecessor = bindings.ensure(location, "codex", predecessorConnection).binding();
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        new SessionLeaseService().createOrRenewLease(location.root(), location.projectId().toString(), "codex", predecessorConnection, identity.nodeId(), predecessor.sessionId(), new SessionLeasePolicy());
        assertEquals(ProviderSessionTerminalizationService.Outcome.SESSION_TERMINATED,
                new ProviderSessionTerminalizationService().seal(location, predecessor, predecessorConnection, identity, "explicit_terminal").outcome());

        SessionResolutionRequest freshRequest = new SessionResolutionRequest(location.root(), "codex", "successor", null, false);
        AgentSessionService sessions = new AgentSessionService();
        var freshContext = sessions.resolveSessionContext(freshRequest);
        var response = sessions.ensureSession(freshRequest);

        assertNotEquals(predecessor.sessionId(), freshContext.sessionId());
        assertEquals(AgentStatus.READY, response.status());
        var nextAction = new AgentNextActionService().getNextAction(new NextActionRequest(location.root(), "codex", "successor"));
        assertNotEquals(AgentStatus.FAILED, nextAction.status());
        if (nextAction.result() instanceof Map<?, ?> result) {
            assertFalse(result.containsKey("causeCode"));
        }
        var projection = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).collaborationProjection();
        assertTrue(projection.isSessionTerminal(predecessor.sessionId()));
        assertFalse(projection.isSessionTerminal(freshContext.sessionId()));
        assertTrue(Files.isRegularFile(location.root().resolve(".synesis/project.json")));
    }
}
