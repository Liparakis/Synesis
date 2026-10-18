package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CombinePayload;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.control.ControlBranchAdvancementService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.task.SnapshotRequest;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Two agents ask for the same combine lane (ADR-0098 step 5, designer item 22): the one that loses the race is told to
 * look for other work, not to send for a person (s5-run8).
 */
@SuppressWarnings("unchecked")
class McpCombineTakenTest {

    private static final String FILE = "src/f.txt";

    private final String previousUserHome = System.getProperty("user.home");

    @AfterEach
    void restoreUserHome() {
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

    private static ProviderSessionBindingService.Binding session(ProjectLocation location, String connection) throws Exception {
        new AgentSessionService().ensureSession(new SessionResolutionRequest(location.root(), "codex", connection, null, false));
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        ProviderSessionBindingService.Binding binding = bindings.find(location, "codex", connection).orElseThrow();
        bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
        return bindings.find(location, "codex", connection).orElseThrow();
    }

    private static TaskSnapshotPayload payload(TaskSnapshotRecord snapshot) {
        return new TaskSnapshotPayload(snapshot.taskId(), snapshot.snapshotId(), snapshot.nodeId(), snapshot.supervisorId(), snapshot.workerId(), snapshot.providerSessionId(),
                snapshot.baseCommit(), snapshot.commitSha(), snapshot.changedPaths(), snapshot.capabilityDependencies(), snapshot.summary(), snapshot.provenance(), false);
    }

    @Test
    void theAgentThatLosesTheRaceForACombineIsSentToGetNextActionNotToAPerson(@TempDir Path temp) throws Exception {
        System.setProperty("user.home", Files.createDirectories(temp.resolve("home")).toString());
        Path root = Files.createDirectories(temp.resolve("project"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve(FILE), "orig\n");
        GitProcessRunner.run(root, "init");
        GitProcessRunner.run(root, "config", "user.name", "Combine Test");
        GitProcessRunner.run(root, "config", "user.email", "combine@example.test");
        GitProcessRunner.run(root, "add", ".");
        GitProcessRunner.run(root, "commit", "-m", "baseline");
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");

        // An author's lane changed the file one way and was integrated; the other copy of main changed it another way.
        ProviderSessionBindingService.Binding author = session(location, "combine-author");
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        List<ResourceSelector> selectors = List.of(ResourceSelector.pathExact(FILE));
        WorkIntent lane = collaboration.announce(root, "codex", "combine-author", "change f", "f changed", selectors).intent();
        Path authorWorktree = Path.of(author.worktreePath());
        Files.writeString(authorWorktree.resolve(FILE), "author\n");
        TaskSnapshotRecord snapshot = new TaskSnapshotService().createSnapshot(new SnapshotRequest(lane.taskId(), "node", author.supervisorId(), author.workerId(), author.sessionId(),
                authorWorktree, root, "author version", Optional.empty(), List.of(), SnapshotRequest.ClaimScope.unscoped(selectors),
                SnapshotRequest.Lane.of(lane.workGroupId(), lane.intentId(), WorkspaceCollaborationService.participantHandle(author.sessionId()), author.sessionId(), lane.version(), List.of()),
                false, null, null));
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.coordinationDirectory(), location.projectId());
        store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), payload(snapshot).encode(), identity);
        store.append(UUID.randomUUID(), PredictionEventType.TASK_INTEGRATED, identity.nodeId(), payload(snapshot).encode(), identity);
        ControlBranchAdvancementService.recordIntegrated(snapshot, new PredictionEventStore(location.coordinationDirectory(), location.projectId()), identity);
        collaboration.release(root, "codex", "combine-author");
        Files.writeString(root.resolve(FILE), "other\n");
        GitProcessRunner.run(root, "commit", "-am", "other copy of main");
        String base = GitProcessRunner.run(root, "rev-parse", "HEAD").trim();
        CombinePayload combine = new CombinePayload("cb-" + UUID.randomUUID(), "remerge-test", base, snapshot.commitSha(), base, snapshot.commitSha(), List.of(snapshot.snapshotId()));
        PredictionEventStore current = new PredictionEventStore(location.coordinationDirectory(), location.projectId());
        WorkGroup group = new WorkGroup(combine.workGroupId(), current.projectId(), "Combine " + snapshot.snapshotId(), "One change that holds both versions", 1, WorkGroup.Status.ACTIVE,
                ReviewPolicy.none());
        current.append(group.workGroupId(), PredictionEventType.WORK_GROUP_CREATED, identity.nodeId(), CollaborationCodec.encodeWorkGroup(group), identity);
        current.append(UUID.randomUUID(), PredictionEventType.COMBINE_REQUIRED, identity.nodeId(), combine.encode(), identity);
        Map<String, Object> join = Map.of("task", Map.of("repairIntentId", lane.intentId().toString(), "repairSnapshotId", snapshot.snapshotId()));

        session(location, "combine-first");
        session(location, "combine-late");
        Map<String, Object> first = call(new McpProtocolHandler(new AgentSessionService(), root, "codex", "combine-first"), "ensure_session", join);
        assertEquals(Boolean.TRUE, ((Map<String, Object>) first.get("result")).get("repairJoined"), first.toString());

        Map<String, Object> late = call(new McpProtocolHandler(new AgentSessionService(), root, "codex", "combine-late"), "ensure_session", join);

        assertEquals("blocked", late.get("status"), late.toString());
        Map<String, Object> refused = (Map<String, Object>) late.get("result");
        assertEquals("COMBINE_TAKEN_OR_CLOSED", refused.get("reason"), late.toString());
        assertEquals("retry", late.get("nextAction"), "s5-run8 told the losing agent request_human_help: " + late);
        assertTrue(String.valueOf(refused.get("instruction")).contains("call get_next_action"), late.toString());
    }
}
