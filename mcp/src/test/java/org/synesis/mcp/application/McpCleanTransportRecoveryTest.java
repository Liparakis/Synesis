package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseState;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;

/** Verifies clean MCP EOF preserves unfinished task claims and their working tree. */
final class McpCleanTransportRecoveryTest {

    @Test
    void cleanEofSnapshotsUnfinishedLaneInsteadOfDetachingIt(@TempDir Path temp) throws Exception {
        String previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", temp.resolve("provider-home").toString());
        try {
            Path project = temp.resolve("clean-eof-project");
            Files.createDirectories(project);
            git(project, "init");
            git(project, "config", "user.name", "Recovery Test");
            git(project, "config", "user.email", "recovery@example.test");
            Files.writeString(project.resolve("README.md"), "# recovery\n");
            git(project, "add", ".");
            git(project, "commit", "-m", "baseline");
            new ProjectApplicationService().init(project);

            ProjectLocation location = new ProjectApplicationService().locate(project);
            McpProviderTestSupport.install(location, "codex");
            new org.synesis.workspace.application.provider.ProviderManualService().install("codex");
            String connection = "clean-eof-owner";
            AgentSessionService sessions = new AgentSessionService();
            sessions.ensureSession(new SessionResolutionRequest(project, "codex", connection, null, false));
            ProviderSessionBindingService bindings = new ProviderSessionBindingService();
            var binding = bindings.find(location, "codex", connection).orElseThrow();
            bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
            var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            new SessionLeaseService().createOrRenewLease(project,
                    location.projectId().toString(),
                    "codex",
                    connection,
                    identity.nodeId(),
                    binding.sessionId(),
                    new SessionLeasePolicy());
            McpProtocolHandler handler = new McpProtocolHandler(sessions, project, "codex", connection);
            String ensureResponse = handler.handleMessage(toolCall("ensure_session", "{}"));
            assertEquals("ready", innerResult(ensureResponse).get("status"), ensureResponse);

            Path worktree = Path.of(binding.worktreePath());
            Files.writeString(worktree.resolve("unfinished.txt"), "keep this work\n");
            var claim = new WorkspaceCollaborationService().announce(project,
                    "codex",
                    connection,
                    "Implement the task",
                    "Preserve unfinished work on disconnect",
                    List.of(ResourceSelector.pathExact("unfinished.txt")));
            handler.close();

            PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
            assertEquals(Participant.State.RECOVERY_HELD,
                    store.collaborationProjection().participantState(claim.intent().participant()).orElseThrow());
            assertTrue(store.collaborationProjection().recoverySnapshotReference(claim.intent().participant()).isPresent());
            assertEquals(0,
                    store.events().stream().filter(event -> event.type() == PredictionEventType.PARTICIPANT_DETACHED).count());
            String snapshot = store.collaborationProjection().recoverySnapshotReference(claim.intent().participant()).orElseThrow();
            assertTrue(Files.exists(Path.of(snapshot.substring(0, snapshot.lastIndexOf('#'))).resolve("unfinished.txt")));
            assertTrue(store.collaborationProjection().activeIntents().stream().anyMatch(intent -> intent.intentId().equals(claim.intent().intentId())));

            byte[] unfinishedBytes = Files.readAllBytes(worktree.resolve("unfinished.txt"));
            String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(unfinishedBytes));
            String staleMutation = handler.handleMessage(toolCall("apply_patch", "{\"path\":\"unfinished.txt\",\"expectedHash\":\"" + expectedHash
                    + "\",\"edits\":[{\"find\":\"keep this work\",\"replace\":\"stale edit\",\"expectedOccurrences\":1}]}"));
            Map<?, ?> blocked = innerResult(staleMutation);
            assertEquals("blocked", blocked.get("status"));
            assertEquals("SESSION_EPOCH_FENCED", ((Map<?, ?>) blocked.get("result")).get("reason"));
            assertEquals(SessionLeaseState.CLOSED_CLEANLY,
                    new SessionLeaseStore().load(project, connection).orElseThrow().leaseState());
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static void git(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
    }

    private static String toolCall(String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                + "\",\"arguments\":" + arguments + "}}";
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> innerResult(String response) {
        Map<String, Object> outer = (Map<String, Object>) ProviderJson.parse(response);
        Map<String, Object> rpcResult = (Map<String, Object>) outer.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) rpcResult.get("content");
        return (Map<?, ?>) ProviderJson.parse((String) content.getFirst().get("text"));
    }
}
