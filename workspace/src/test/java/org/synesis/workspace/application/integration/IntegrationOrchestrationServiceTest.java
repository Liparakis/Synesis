package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.agent.AgentStatus;

/** Tests fail-closed integration metadata gates. */
class IntegrationOrchestrationServiceTest {
    @Test
    void unresolvedCoordinationRequestBlocksBeforeIntegration(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.email", "test@example.invalid");
        git(root, "config", "user.name", "Test");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID intentId = UUID.randomUUID();
        WorkIntent intent = new WorkIntent(intentId, location.projectId(), "agt_owner", "codex", UUID.randomUUID(),
                "owner", "acceptance", gitOutput(root, "rev-parse", "HEAD"),
                List.of(ResourceSelector.pathExact("src/claimed.py")), 1, WorkIntent.Status.ANNOUNCED);
        WorkIntentService owner = new WorkIntentService(store, identity);
        assertTrue(owner.announce(intent).acquired());
        new WorkIntentService(store, identity).request("agt_contender", intentId,
                org.synesis.coordination.domain.collaboration.CoordinationRequest.Kind.CONTRACT,
                "agree on API");

        TaskSnapshotRecord snapshot = new TaskSnapshotService().createSnapshot(UUID.randomUUID(), "node", "sup", "worker",
                "lane", root, root, "snapshot", java.util.Optional.empty(), List.of(), List.of());
        TaskSnapshotPayload payload = new TaskSnapshotPayload(snapshot.taskId(), snapshot.snapshotId(), snapshot.nodeId(),
                snapshot.supervisorId(), snapshot.workerId(), snapshot.providerSessionId(), snapshot.baseCommit(),
                snapshot.commitSha(), snapshot.changedPaths(), snapshot.capabilityDependencies(), snapshot.summary(),
                snapshot.provenance());
        store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), payload.encode(), identity);

        long eventCount = eventCount(store);
        var response = new IntegrationOrchestrationService().orchestrateIntegration(root, store, identity);
        assertEquals(AgentStatus.BLOCKED, response.status());
        assertEquals(eventCount, eventCount(store),
                "integration must not append an attempt while coordination is unresolved");
    }

    private static void git(Path root, String... args) throws Exception {
        gitOutput(root, args);
    }

    private static long eventCount(PredictionEventStore store) throws Exception {
        try (var files = Files.list(store.rootDirectory().resolve("events"))) {
            return files.count();
        }
    }

    private static String gitOutput(Path root, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output;
    }
}
