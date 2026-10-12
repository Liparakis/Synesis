package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;
import org.synesis.workspace.lifecycle.lease.SessionLeaseRecord;
import org.synesis.workspace.lifecycle.lease.SessionLeaseState;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;

/**
 * Verifies ordinary MCP admission triggers only the existing bounded recovery lifecycle.
 */
class McpAdmissionRecoveryTest {

    private Path tempRoot;
    private String previousUserHome;

    private static void git(Path root, String... arguments) throws Exception {
        GitProcessRunner.run(root, arguments);
    }

    @BeforeEach
    void setUp() throws Exception {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("synesis-mcp-recovery-home-").toString());
        tempRoot = Files.createTempDirectory("synesis-mcp-recovery-");
        git(tempRoot, "init");
        git(tempRoot, "config", "user.name", "Test User");
        git(tempRoot, "config", "user.email", "test@example.com");
        Files.writeString(tempRoot.resolve("README.md"), "# Recovery test\n");
        git(tempRoot, "add", ".");
        git(tempRoot, "commit", "-m", "Initial commit");

        ProjectLocation location = new ProjectApplicationService().init(tempRoot).location();
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
    }

    @AfterEach
    void restoreUserHome() {
        if (previousUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousUserHome);
        }
    }

    @Test
    void freshOrdinaryAdmissionFencesDeadParticipantWithoutAddingAnMcpTool() throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(tempRoot);
        ProviderSessionBindingService.Binding oldBinding = new ProviderSessionBindingService().ensure(location, "codex", "dead-ordinary-connection").binding();
        String oldParticipant = WorkspaceCollaborationService.participantHandle(oldBinding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore initial = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        WorkIntent oldIntent = new WorkIntent(UUID.randomUUID(),
                location.projectId(),
                oldParticipant,
                "codex",
                UUID.randomUUID(),
                "interrupted producer",
                "recover the lane",
                oldBinding.baseCommit(),
                List.of(ResourceSelector.pathExact("interrupted.txt")),
                1,
                WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(initial, identity).announce(oldIntent).acquired());
        Files.writeString(Path.of(oldBinding.worktreePath()).resolve("interrupted.txt"), "partial\n");

        long staleSince = System.currentTimeMillis() - 600_000L;
        new SessionLeaseStore().save(tempRoot,
                new SessionLeaseRecord(1,
                        location.projectId().toString(),
                        "codex",
                        "dead-ordinary-connection",
                        "worker-dead",
                        oldBinding.sessionId(),
                        new SessionProcessIdentity(99_998L, "java", "SynesisMcpServer", staleSince, "dead-ordinary"),
                        "0.1.0-SNAPSHOT",
                        staleSince,
                        staleSince,
                        SessionLeaseState.ACTIVE));

        McpProtocolHandler replacement = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", "replacement-ordinary-connection");
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        assertTrue(replacement.handleMessage(ensure).contains("ready"));

        PredictionEventStore recovered = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals(Participant.State.RECOVERY_HELD, recovered.collaborationProjection().participantState(oldParticipant).orElseThrow());
        assertEquals(11, toolCount(replacement.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")));

        long suspendedEvents = recovered.events().stream().filter(event -> event.type() == PredictionEventType.PARTICIPANT_SUSPENDED).count();
        long heldEvents = recovered.events().stream().filter(event -> event.type() == PredictionEventType.RECOVERY_SNAPSHOT_HELD).count();

        McpProtocolHandler secondReplacement = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", "replacement-ordinary-connection-2");
        assertTrue(secondReplacement.handleMessage(ensure.replace("\"id\":1", "\"id\":3")).contains("ready"));
        PredictionEventStore afterSecondAdmission = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals(suspendedEvents, afterSecondAdmission.events().stream().filter(event -> event.type() == PredictionEventType.PARTICIPANT_SUSPENDED).count());
        assertEquals(heldEvents, afterSecondAdmission.events().stream().filter(event -> event.type() == PredictionEventType.RECOVERY_SNAPSHOT_HELD).count());
    }

    @Test
    void recoveryHeldReviewerClaimCanReachContinuationProjectionBeforeClaimTransfer() throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(tempRoot);
        ProviderSessionBindingService.Binding oldBinding = new ProviderSessionBindingService().ensure(location, "codex", "dead-reviewer-connection").binding();
        String oldParticipant = WorkspaceCollaborationService.participantHandle(oldBinding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore initial = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID groupId = UUID.randomUUID();
        WorkIntentService service = new WorkIntentService(initial, identity);
        // A reviewer lane is admitted only beside an open producer lane of another participant in its group.
        ProviderSessionBindingService.Binding producerBinding = new ProviderSessionBindingService().ensure(location, "codex", "live-producer-connection").binding();
        WorkIntent producerIntent = new WorkIntent(UUID.randomUUID(),
                location.projectId(),
                WorkspaceCollaborationService.participantHandle(producerBinding.sessionId()),
                "codex",
                UUID.randomUUID(),
                "produce the product",
                "publish the product",
                producerBinding.baseCommit(),
                List.of(ResourceSelector.pathExact("product.md")),
                1,
                groupId,
                UUID.randomUUID(),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER);
        assertTrue(service.announce(producerIntent).acquired());
        WorkIntent oldIntent = new WorkIntent(UUID.randomUUID(),
                location.projectId(),
                oldParticipant,
                "codex",
                UUID.randomUUID(),
                "review the product",
                "publish an independent review",
                oldBinding.baseCommit(),
                List.of(ResourceSelector.pathExact("review-report.md")),
                1,
                groupId,
                UUID.randomUUID(),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.REVIEWER);
        assertTrue(service.announce(oldIntent).acquired());
        service.suspend(oldParticipant);
        service.holdRecovery(oldParticipant, "snapshot-root#review-snapshot");

        McpProtocolHandler replacement = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", "replacement-reviewer-connection");
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"ensure_session\",\"arguments\":{" + "\"task\":{\"role\":\"reviewer\",\"claims\":["
                + "{\"path\":\"review-report.md\",\"kind\":\"path_exact\"}]}}}}";
        String ensureResponse = replacement.handleMessage(ensure);
        assertTrue(ensureResponse.contains("\\\"status\\\":\\\"ready\\\""), ensureResponse);

        String next = replacement.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"get_next_action\",\"arguments\":{}}}");
        assertTrue(next.contains("continuation_available"), next);
        assertTrue(next.contains(oldIntent.intentId().toString()), next);
        assertTrue(next.contains("reviewer"), next);

        PredictionEventStore afterAdmission = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals(Participant.State.RECOVERY_HELD, afterAdmission.collaborationProjection().participantState(oldParticipant).orElseThrow());
        assertTrue(afterAdmission.collaborationProjection().activeIntents().stream().noneMatch(intent -> intent.participant().equals(WorkspaceCollaborationService.participantHandle("replacement-reviewer-connection"))));
        assertTrue(afterAdmission.events().stream().noneMatch(event -> event.type() == PredictionEventType.LANE_CONTINUATION_ACCEPTED));
    }

    private static int toolCount(String response) {
        return response.split("\\\"name\\\":\\\"").length - 1;
    }
}
