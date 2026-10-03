package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.task.TaskSnapshotService;

/**
 * Tests fail-closed integration metadata gates.
 */
class IntegrationOrchestrationServiceTest {

    private static void git(Path root, String... args) throws Exception {
        gitOutput(root, args);
    }

    private static long eventCount(PredictionEventStore store) throws Exception {
        try (var files = Files.list(store.rootDirectory().resolve("events"))) {
            return files.count();
        }
    }

    private static String gitOutput(Path root, String... args) throws Exception {
        return org.synesis.workspace.test.TestGit.output(root, args);
    }

    @Test
    void staleCallerCannotReintegrateAnAlreadyIntegratedSnapshot(@TempDir Path root) throws Exception {
        git(root, "init");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        var location = new ProjectApplicationService().init(root).location();
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(root.resolve(".synesis/coordination"), location.projectId());
        String head = gitOutput(root, "rev-parse", "HEAD");
        UUID task = UUID.randomUUID();
        var snapshot = new TaskSnapshotRecord(task, "snap_already_integrated", "node", "supervisor", "worker", "session", head, head, List.of("README.md"), List.of(), "snapshot", 1L);
        var payload = new TaskSnapshotPayload(task,
                snapshot.snapshotId(),
                snapshot.nodeId(),
                snapshot.supervisorId(),
                snapshot.workerId(),
                snapshot.providerSessionId(),
                head,
                head,
                snapshot.changedPaths(),
                List.of(),
                snapshot.summary(),
                snapshot.provenance());
        store.append(task, PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), payload.encode(), identity);
        var stale = new PredictionEventStore(store.rootDirectory(), location.projectId());
        var peer = new PredictionEventStore(store.rootDirectory(), location.projectId());
        peer.append(task, PredictionEventType.TASK_INTEGRATED, identity.nodeId(), payload.encode(), identity);
        long before = eventCount(peer);

        var response = new IntegrationOrchestrationService().orchestrateIntegration(root, stale, identity);
        assertEquals(AgentStatus.READY, response.status(), response.toString());
        assertEquals(before, eventCount(peer));
        var replayed = new PredictionEventStore(store.rootDirectory(), location.projectId());
        assertEquals(org.synesis.coordination.domain.task.TaskCompletionState.INTEGRATED, replayed.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElseThrow());
        assertEquals(head, gitOutput(root, "rev-parse", "HEAD"));
    }

    @Test
    void unresolvedCoordinationRequestBlocksBeforeIntegration(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.email", "test@example.invalid");
        git(root, "config", "user.name", "Test");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        String baselineCommit = gitOutput(root, "rev-parse", "HEAD");
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID intentId = UUID.randomUUID();
        WorkIntent intent = new WorkIntent(intentId,
                location.projectId(),
                "agt_owner",
                "codex",
                UUID.randomUUID(),
                "owner",
                "acceptance",
                gitOutput(root, "rev-parse", "HEAD"),
                List.of(ResourceSelector.pathExact("src/claimed.py")),
                1,
                WorkIntent.Status.ANNOUNCED);
        WorkIntentService owner = new WorkIntentService(store, identity);
        assertTrue(owner.announce(intent).acquired());
        new WorkIntentService(store, identity).request("agt_contender", intentId, org.synesis.coordination.domain.collaboration.CoordinationRequest.Kind.CONTRACT, "agree on API");

        Path laneParent = Files.createTempDirectory("synesis-integration-lane-parent-");
        Path lane = laneParent.resolve("lane");
        try {
            // Project initialization has already committed the canonical
            // managed baseline. The lane must start from that exact commit;
            // only the claimed source change belongs to the feature snapshot.
            git(root, "worktree", "add", "--detach", lane.toString(), baselineCommit);
            assertEquals(baselineCommit, gitOutput(lane, "rev-parse", "HEAD"));
            Files.createDirectories(lane.resolve("src"));
            Files.writeString(lane.resolve("src/claimed.py"), "claimed\n");

            TaskSnapshotRecord snapshot = new TaskSnapshotService().createSnapshot(UUID.randomUUID(), "node", "sup", "worker", "lane", lane, root, "snapshot", java.util.Optional.empty(), List.of(), List.of());
            assertEquals(baselineCommit, snapshot.baseCommit());
            assertEquals(List.of("src/claimed.py"), snapshot.changedPaths());
            TaskSnapshotPayload payload = new TaskSnapshotPayload(snapshot.taskId(),
                    snapshot.snapshotId(),
                    snapshot.nodeId(),
                    snapshot.supervisorId(),
                    snapshot.workerId(),
                    snapshot.providerSessionId(),
                    snapshot.baseCommit(),
                    snapshot.commitSha(),
                    snapshot.changedPaths(),
                    snapshot.capabilityDependencies(),
                    snapshot.summary(),
                    snapshot.provenance());
            store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), payload.encode(), identity);

            long eventCount = eventCount(store);
            var response = new IntegrationOrchestrationService().orchestrateIntegration(root, store, identity);
            assertEquals(AgentStatus.WAITING, response.status());
            assertEquals(AgentReason.INTEGRATION_PENDING, response.reason());
            assertEquals(AgentNextAction.WAIT, response.nextAction());
            assertEquals(1, ((java.util.Map<?, ?>) response.result()).get("pending"));
            assertFalse(((java.util.Map<?, ?>) response.result()).containsKey("request"), "a project-wide integration fence cannot select another participant's request");
            assertEquals(eventCount, eventCount(store), "integration must not append an attempt while coordination is unresolved");
        } finally {
            try {
                git(root, "worktree", "remove", "--force", lane.toString());
            } finally {
                Files.deleteIfExists(laneParent);
            }
        }
    }

    @Test
    void terminalOnlyCoordinationRequestDoesNotBlockIntegration(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.email", "test@example.invalid");
        git(root, "config", "user.name", "Test");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        String baselineCommit = gitOutput(root, "rev-parse", "HEAD");
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID taskId = UUID.randomUUID();
        UUID ownerIntentId = UUID.randomUUID();
        UUID contenderIntentId = UUID.randomUUID();
        WorkIntent ownerIntent = new WorkIntent(ownerIntentId,
                location.projectId(),
                "agt_owner",
                "codex",
                taskId,
                "owner",
                "acceptance",
                baselineCommit,
                List.of(ResourceSelector.pathExact("src/claimed.py")),
                1,
                UUID.randomUUID(),
                WorkIntent.Status.ANNOUNCED);
        WorkIntent contenderIntent = new WorkIntent(contenderIntentId,
                location.projectId(),
                "agt_contender",
                "codex",
                UUID.randomUUID(),
                "contender",
                "acceptance",
                baselineCommit,
                List.of(ResourceSelector.pathExact("src/other.py")),
                1,
                UUID.randomUUID(),
                WorkIntent.Status.ANNOUNCED);
        WorkIntentService owner = new WorkIntentService(store, identity);
        assertTrue(owner.announce(ownerIntent).acquired());
        assertTrue(new WorkIntentService(store, identity).announce(contenderIntent).acquired());
        new WorkIntentService(store, identity).request("agt_contender", ownerIntentId, org.synesis.coordination.domain.collaboration.CoordinationRequest.Kind.CONTRACT, "agree on API");
        new WorkIntentService(store, identity).detach("agt_contender");
        new WorkIntentService(store, identity).detach("agt_owner");

        Path laneParent = Files.createTempDirectory("synesis-integration-lane-parent-");
        Path lane = laneParent.resolve("lane");
        try {
            git(root, "worktree", "add", "--detach", lane.toString(), baselineCommit);
            Files.createDirectories(lane.resolve("src"));
            Files.writeString(lane.resolve("src/claimed.py"), "claimed\n");

            TaskSnapshotRecord snapshot = new TaskSnapshotService().createSnapshot(taskId,
                    "node",
                    "sup",
                    "worker",
                    "binding",
                    lane,
                    root,
                    "snapshot",
                    java.util.Optional.empty(),
                    List.of(),
                    ownerIntent.selectors(),
                    ownerIntent.workGroupId(),
                    ownerIntent.intentId(),
                    ownerIntent.participant(),
                    "binding",
                    ownerIntent.version(),
                    ownerIntent.authorityLineageId(),
                    List.of(),
                    false,
                    baselineCommit);
            TaskSnapshotPayload payload = new TaskSnapshotPayload(snapshot.taskId(),
                    snapshot.snapshotId(),
                    snapshot.nodeId(),
                    snapshot.supervisorId(),
                    snapshot.workerId(),
                    snapshot.providerSessionId(),
                    snapshot.baseCommit(),
                    snapshot.commitSha(),
                    snapshot.changedPaths(),
                    snapshot.capabilityDependencies(),
                    snapshot.summary(),
                    snapshot.provenance());
            store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), payload.encode(), identity);

            var response = new IntegrationOrchestrationService().orchestrateIntegration(root, store, identity);
            assertNotEquals(AgentReason.OWNER_REQUEST_PENDING, response.reason(), response.toString());
        } finally {
            try {
                git(root, "worktree", "remove", "--force", lane.toString());
            } finally {
                Files.deleteIfExists(laneParent);
            }
        }
    }
}
