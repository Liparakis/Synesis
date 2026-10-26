package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationExchange;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.control.CodeExchange;
import org.synesis.workspace.application.control.CodeOverLink;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.task.SnapshotRequest;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * An agent of a member computer asks to join the repair of a snapshot whose commit was made on another computer that
 * does not answer now: it is told to ask again, and not to send a person (ADR-0098, step 3).
 */
@SuppressWarnings("unchecked")
class McpMemberRepairJoinTest {

    private String previousUserHome;
    private Path root;
    private ProjectLocation location;
    private CodeOverLink codeSide;

    @BeforeEach
    void setUp() throws Exception {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("synesis-mcp-repair-home-").toString());
        root = Files.createTempDirectory("synesis-mcp-repair-");
        GitProcessRunner.run(root, "init");
        GitProcessRunner.run(root, "config", "user.name", "Test User");
        GitProcessRunner.run(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "# Test Repo\n");
        GitProcessRunner.run(root, "add", ".");
        GitProcessRunner.run(root, "commit", "-m", "Initial commit");
        location = new ProjectApplicationService().init(root).location();
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
    }

    @AfterEach
    void tearDown() throws Exception {
        CodeExchange.register(location.coordinationDirectory(), null);
        if (codeSide != null) {
            codeSide.close();
        }
        if (previousUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousUserHome);
        }
    }

    private static Map<String, Object> call(McpProtocolHandler handler, String tool, Map<String, Object> arguments) {
        String frame = handler.handleMessage(ProviderJson.write(Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call", "params", Map.of("name", tool, "arguments", arguments))));
        Map<String, Object> result = (Map<String, Object>) ((Map<String, Object>) ProviderJson.parse(frame)).get("result");
        return (Map<String, Object>) ProviderJson.parse((String) ((Map<String, Object>) ((List<?>) result.get("content")).getFirst()).get("text"));
    }

    @Test
    void anAgentJoiningARepairWhoseSnapshotIsOnAComputerThatDoesNotAnswerIsToldToRetryAndNotToAskAPerson() throws Exception {
        // A conflicted snapshot, as the integration pass records it.
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var sourceBinding = bindings.ensure(location, "codex", "repair-source").binding();
        Path sourceWorktree = Path.of(sourceBinding.worktreePath());
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        List<ResourceSelector> selectors = List.of(ResourceSelector.pathExact("src/conflict.py"));
        var sourceClaim = collaboration.announce(root, "codex", "repair-source", "source", "repair", selectors);
        assertTrue(sourceClaim.acquired());
        UUID sourceIntentId = UUID.nameUUIDFromBytes(("codex:" + sourceBinding.sessionId()).getBytes(StandardCharsets.UTF_8));
        UUID group = sourceClaim.intent().workGroupId();
        Files.createDirectories(sourceWorktree.resolve("src"));
        Files.writeString(sourceWorktree.resolve("src/conflict.py"), "original\n");
        TaskSnapshotRecord snapshot = new TaskSnapshotService().createSnapshot(new SnapshotRequest(sourceClaim.intent().taskId(), "node", "sup", "worker", sourceBinding.sessionId(), sourceWorktree, root, "conflict",
                java.util.Optional.empty(), List.of(), SnapshotRequest.ClaimScope.unscoped(selectors),
                SnapshotRequest.Lane.of(group, sourceIntentId, WorkspaceCollaborationService.participantHandle(sourceBinding.sessionId()), sourceBinding.sessionId(), 1, List.of()), false, null, null));
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.coordinationDirectory(), location.projectId());
        TaskSnapshotPayload payload = new TaskSnapshotPayload(snapshot.taskId(), snapshot.snapshotId(), snapshot.nodeId(), snapshot.supervisorId(), snapshot.workerId(), snapshot.providerSessionId(),
                snapshot.baseCommit(), snapshot.commitSha(), snapshot.changedPaths(), snapshot.capabilityDependencies(), snapshot.summary(), snapshot.provenance(), false);
        store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), payload.encode(), identity);
        String head = GitProcessRunner.run(root, "rev-parse", "HEAD").trim();
        String attemptId = "repair-attempt-" + UUID.randomUUID();
        store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_STARTED, identity.nodeId(),
                new IntegrationAttemptPayload(attemptId, location.projectId(), List.of(snapshot.snapshotId()), head, "", "started", "").encode(), identity);
        IntegrationAttemptPayload conflict = new IntegrationAttemptPayload(attemptId, location.projectId(), List.of(snapshot.snapshotId()), head, "", "conflict", "merge conflict");
        store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_CONFLICTED, identity.nodeId(), conflict.encode(), identity);
        store.append(UUID.randomUUID(), PredictionEventType.REPAIR_REQUIRED, identity.nodeId(), conflict.encode(), identity);

        // The commit was made on another computer: this computer's repository does not hold it.
        for (String line : GitProcessRunner.run(root, "for-each-ref", "--format=%(refname) %(objectname)", "refs/synesis").lines().toList()) {
            if (line.endsWith(" " + snapshot.commitSha())) {
                GitProcessRunner.run(root, "update-ref", "-d", line.substring(0, line.indexOf(' ')));
            }
        }
        Path object = root.resolve(".git/objects").resolve(snapshot.commitSha().substring(0, 2)).resolve(snapshot.commitSha().substring(2));
        assertTrue(object.toFile().setWritable(true), "git writes objects read-only");
        Files.delete(object);

        // This computer is a member with no computer connected.
        String leader = new IdentityBootstrap(Files.createTempDirectory("synesis-mcp-repair-leader-")).loadOrCreate().identity().nodeId();
        ReplicationSettings.member(leader).write(location.coordinationDirectory());
        codeSide = new CodeOverLink(root, location.coordinationDirectory(), location.projectId(), new CodeOverLink.Peers() {
            @Override
            public Map<String, ReplicationExchange> live() {
                return Map.of();
            }

            @Override
            public boolean member() {
                return true;
            }
        });
        CodeExchange.register(location.coordinationDirectory(), codeSide::fetch);
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), root, "codex", "conn-repair-target");

        Map<String, Object> joined = call(handler, "ensure_session",
                Map.of("task", Map.of("repairIntentId", sourceIntentId.toString(), "repairSnapshotId", snapshot.snapshotId())));

        assertEquals("blocked", joined.get("status"), joined.toString());
        assertTrue(String.valueOf(((Map<String, Object>) joined.get("result")).get("reason")).startsWith("CODE_REMOTE_UNAVAILABLE"), joined.toString());
        assertEquals("retry", joined.get("nextAction"), joined.toString());
    }
}
