package org.synesis.workspace.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.agent.AgentNextActionService;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseState;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;

/** Verifies exact-session terminal sealing and its fail-closed blockers. */
class ProviderSessionTerminalizationServiceTest {

    @Test
    void sealsAnAuthorityFreeSessionAndIsReplaySafe(@TempDir Path tempDir) throws Exception {
        ProjectApplicationService.ProjectLocation location = project(tempDir.resolve("terminal"));
        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        ProviderSessionBindingService.Binding binding = bindingService.ensure(location, "codex", "terminal-chat")
                .binding();
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        SessionLeaseService leaseService = new SessionLeaseService();
        leaseService.createOrRenewLease(location.root(), location.projectId().toString(), "codex",
                "terminal-chat", identity.nodeId(), binding.sessionId(), new SessionLeasePolicy());

        ProviderSessionTerminalizationService service = new ProviderSessionTerminalizationService();
        var first = service.seal(location, binding, "terminal-chat", identity, "explicit_terminal");
        var second = service.seal(location, binding, "terminal-chat", identity, "explicit_terminal");

        assertEquals(ProviderSessionTerminalizationService.Outcome.SESSION_TERMINATED, first.outcome());
        assertEquals(first, second);
        assertTrue(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId())
                .collaborationProjection().isSessionTerminal(binding.sessionId()));
        assertEquals(SessionLeaseState.TERMINAL_AUTHORITY_CONFIRMED,
                new SessionLeaseStore().load(location.root(), "terminal-chat").orElseThrow().leaseState());
        assertEquals("TERMINAL", bindingService.list(location, "codex").getFirst().status());

        var ensure = new AgentSessionService().ensureSession(new AgentSessionService.SessionResolutionRequest(
                location.root(), "codex", "terminal-chat", null, false));
        assertEquals(AgentStatus.COMPLETED, ensure.status());
        assertEquals("SESSION_TERMINAL", ((java.util.Map<?, ?>) ensure.result()).get("state"));
        assertThrows(IllegalStateException.class, () -> new SessionAuthorityResolver(bindingService)
                .resolve(location, "codex", "terminal-chat"));
        var next = new AgentNextActionService().getNextAction(new AgentNextActionService.NextActionRequest(
                location.root(), "codex", "terminal-chat"));
        assertEquals(AgentStatus.COMPLETED, next.status());
        assertThrows(java.io.IOException.class, () -> new WorkIntentService(
                new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()), identity)
                .heartbeat(WorkspaceCollaborationService.participantHandle(binding.sessionId())));

        new SessionLeaseService().markClosedCleanly(location.root(), "terminal-chat");
        assertEquals(SessionLeaseState.CLOSED_CLEANLY,
                new SessionLeaseStore().load(location.root(), "terminal-chat").orElseThrow().leaseState());
    }

    @Test
    void activeIntentAndClaimBlockTheExactSession(@TempDir Path tempDir) throws Exception {
        ProjectApplicationService.ProjectLocation location = project(tempDir.resolve("blocked"));
        ProviderSessionBindingService.Binding binding = new ProviderSessionBindingService()
                .ensure(location, "codex", "blocked-chat").binding();
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"),
                location.projectId());
        new WorkIntentService(store, identity).announce(new WorkIntent(UUID.randomUUID(), location.projectId(),
                WorkspaceCollaborationService.participantHandle(binding.sessionId()),
                "codex", UUID.randomUUID(), "implement", "verify", binding.baseCommit(),
                List.of(ResourceSelector.pathExact("src/a.txt")), 1, WorkIntent.Status.ANNOUNCED));

        var result = new ProviderSessionTerminalizationService().seal(location, binding, "blocked-chat", identity,
                "explicit_terminal");

        assertEquals(ProviderSessionTerminalizationService.Outcome.SESSION_TERMINATION_BLOCKED, result.outcome());
        assertTrue(result.blockers().contains("ACTIVE_INTENT"));
        assertTrue(result.blockers().contains("ACTIVE_CLAIM"));
    }

    private static ProjectApplicationService.ProjectLocation project(Path root) throws Exception {
        Files.createDirectories(root);
        org.synesis.workspace.test.TestGit.run(root, "init");
        Files.writeString(root.resolve("README.md"), "baseline\n");
        org.synesis.workspace.test.TestGit.run(root, "add", "README.md");
        org.synesis.workspace.test.TestGit.run(root, "config", "user.email", "synesis-test@example.invalid");
        org.synesis.workspace.test.TestGit.run(root, "config", "user.name", "Synesis Test");
        org.synesis.workspace.test.TestGit.run(root, "commit", "-m", "baseline");
        return new ProjectApplicationService().init(root).location();
    }
}
