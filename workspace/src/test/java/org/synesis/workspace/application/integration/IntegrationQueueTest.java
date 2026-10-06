package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.ImplementationEventPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CompletionPreparedPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Verifies dependency-ready integration queue progression and structural fencing.
 */
class IntegrationQueueTest {

    private static void publishSnapshot(PredictionEventStore store,
            NodeIdentity identity,
            Path root,
            UUID taskId,
            String snapshotId,
            UUID laneId,
            UUID groupId,
            String commit,
            String participant,
            String changedPath,
            String claimSelector,
            UUID lineageLane,
            List<String> capabilityDependencies) throws Exception {
        String preparedRef = "refs/synesis/prepared/cmp_" + snapshotId;
        String snapshotRef = "refs/synesis/snapshots/" + snapshotId;
        git(root, "update-ref", preparedRef, commit);
        git(root, "update-ref", snapshotRef, commit);
        String tree = gitOutput(root, "rev-parse", commit + "^{tree}");
        SnapshotProvenance provenance = new SnapshotProvenance(groupId,
                laneId,
                WorkIntent.defaultAuthorityLineage(lineageLane),
                participant,
                "binding",
                1,
                List.of(),
                List.of(),
                List.of(claimSelector),
                snapshotRef,
                tree,
                "manifest");
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(taskId,
                snapshotId,
                identity.nodeId(),
                "sup",
                "worker",
                "session",
                gitOutput(root, "rev-parse", commit + "^"),
                commit,
                List.of(changedPath),
                capabilityDependencies,
                "snapshot",
                provenance);
        CompletionPreparedPayload prepared = new CompletionPreparedPayload(taskId, "cmp_" + snapshotId, laneId, 1, snapshot.baseCommit(), preparedRef, tree, snapshot.changedPaths());
        store.append(UUID.randomUUID(), PredictionEventType.COMPLETION_PREPARED, identity.nodeId(), prepared.encode(), identity);
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);
    }

    private static String commitFile(Path root, String base, String path, String content, String message) throws Exception {
        git(root, "reset", "--hard", base);
        Files.createDirectories(root.resolve(path).getParent());
        Files.writeString(root.resolve(path), content);
        git(root, "add", path);
        git(root, "commit", "-m", message);
        String commit = gitOutput(root, "rev-parse", "HEAD");
        git(root, "reset", "--hard", base);
        return commit;
    }

    private static void git(Path root, String... args) throws Exception {
        gitOutput(root, args);
    }

    private static String gitOutput(Path root, String... args) throws Exception {
        return org.synesis.workspace.test.TestGit.output(root, args);
    }

    @Test
    void blockedOldCandidateDoesNotPreventLaterEligibleSnapshot(@TempDir Path root) throws Exception {
        assertEligibleSnapshotIntegrates(root, false);
    }

    @Test
    void detachedHistoricalRequestDoesNotInvalidateLiveSnapshot(@TempDir Path root) throws Exception {
        assertEligibleSnapshotIntegrates(root, true);
    }

    private static void assertEligibleSnapshotIntegrates(Path root, boolean historicalRequest) throws Exception {
        git(root, "init");
        git(root, "config", "user.name", "Test");
        git(root, "config", "user.email", "test@example.invalid");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        String base = gitOutput(root, "rev-parse", "HEAD");

        String badCommit = commitFile(root, base, "src/bad.py", "bad\n", "bad");
        String goodCommit = commitFile(root, base, "src/good.py", "good\n", "good");

        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID group = UUID.randomUUID();
        UUID badLane = UUID.randomUUID();
        UUID goodLane = UUID.randomUUID();
        UUID badTask = UUID.randomUUID();
        UUID goodTask = UUID.randomUUID();
        new WorkIntentService(store, identity).announce(new WorkIntent(badLane,
                location.projectId(),
                "agt_bad",
                "codex",
                badTask,
                "bad",
                "blocked",
                base,
                List.of(ResourceSelector.pathExact("src/bad.py")),
                1,
                group,
                WorkIntent.Status.ANNOUNCED));
        store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkIntentService(store, identity).announce(new WorkIntent(goodLane,
                location.projectId(),
                "agt_good",
                "claude",
                goodTask,
                "good",
                "integrated",
                base,
                List.of(ResourceSelector.pathExact("src/good.py")),
                1,
                group,
                WorkIntent.Status.ANNOUNCED));
        store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());

        if (historicalRequest) {
            UUID historicalLane = UUID.randomUUID();
            WorkIntentService intents = new WorkIntentService(store, identity);
            assertTrue(intents.announce(new WorkIntent(historicalLane,
                    location.projectId(),
                    "agt_historical_owner",
                    "codex",
                    UUID.randomUUID(),
                    "historical",
                    "historical",
                    base,
                    List.of(ResourceSelector.pathExact("history/owner.py")),
                    1,
                    UUID.randomUUID(),
                    WorkIntent.Status.ANNOUNCED)).acquired());
            assertTrue(intents.announce(new WorkIntent(UUID.randomUUID(),
                    location.projectId(),
                    "agt_historical_requester",
                    "codex",
                    UUID.randomUUID(),
                    "historical",
                    "historical",
                    base,
                    List.of(ResourceSelector.pathExact("history/requester.py")),
                    1,
                    UUID.randomUUID(),
                    WorkIntent.Status.ANNOUNCED)).acquired());
            intents.request("agt_historical_requester", historicalLane, org.synesis.coordination.domain.collaboration.CoordinationRequest.Kind.CONTRACT, "historical API decision");
            intents.detach("agt_historical_owner");
            intents.detach("agt_historical_requester");
            store = new PredictionEventStore(store.rootDirectory(), store.projectId());
        }

        publishSnapshot(store, identity, root, badTask, "snap_000_bad", badLane, group, badCommit, "agt_bad", "src/bad.py", "PATH_EXACT:src/not-claimed.py", badLane, List.of());
        publishSnapshot(store, identity, root, goodTask, "snap_999_good", goodLane, group, goodCommit, "agt_good", "src/good.py", "PATH_EXACT:src/good.py", goodLane, List.of());

        AgentStatus status = new IntegrationOrchestrationService().orchestrateIntegration(root, store, identity).status();
        assertEquals(AgentStatus.COMPLETED, status);
        store = new PredictionEventStore(store.rootDirectory(), store.projectId());
        assertEquals(TaskCompletionState.INTEGRATION_BLOCKED, store.taskCompletionProjection().taskState(badTask));
        assertEquals(TaskCompletionState.INTEGRATED, store.taskCompletionProjection().taskState(goodTask));
        assertTrue(store.collaborationProjection().intent(badLane).isPresent(), "blocked scope remains reserved until explicit recovery");
        assertEquals("good", gitOutput(root, "show", "HEAD:src/good.py").trim());
        assertTrue(store.taskCompletionProjection().eligibleSnapshots().isEmpty());
        if (historicalRequest) {
            assertEquals(1, store.collaborationProjection().requests().size());
            assertEquals(org.synesis.coordination.domain.collaboration.CoordinationRequest.Status.PENDING, store.collaborationProjection().requests().getFirst().status());
        }
    }

    @Test
    void readySnapshotsFromOneWorkGroupShareTheIntegrationGate(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.name", "Test");
        git(root, "config", "user.email", "test@example.invalid");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        String base = gitOutput(root, "rev-parse", "HEAD");
        String modelCommit = commitFile(root, base, "src/model.py", "model\n", "model");
        String testCommit = commitFile(root, base, "tests/test_model.py", "test\n", "test");

        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID group = UUID.randomUUID();
        UUID modelLane = UUID.randomUUID();
        UUID testLane = UUID.randomUUID();
        UUID modelTask = UUID.randomUUID();
        UUID testTask = UUID.randomUUID();
        new WorkIntentService(store, identity).announce(new WorkIntent(modelLane,
                location.projectId(),
                "agt_model",
                "codex",
                modelTask,
                "model",
                "model and tests integrate",
                base,
                List.of(ResourceSelector.pathExact("src/model.py")),
                1,
                group,
                WorkIntent.Status.ANNOUNCED));
        store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkIntentService(store, identity).announce(new WorkIntent(testLane,
                location.projectId(),
                "agt_tests",
                "codex",
                testTask,
                "tests",
                "model and tests integrate",
                base,
                List.of(ResourceSelector.pathExact("tests/test_model.py")),
                1,
                group,
                WorkIntent.Status.ANNOUNCED));
        store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());

        publishSnapshot(store, identity, root, modelTask, "snap_group_model", modelLane, group, modelCommit, "agt_model", "src/model.py", "PATH_EXACT:src/model.py", modelLane, List.of());
        store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        publishSnapshot(store, identity, root, testTask, "snap_group_test", testLane, group, testCommit, "agt_tests", "tests/test_model.py", "PATH_EXACT:tests/test_model.py", testLane, List.of());
        store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());

        PredictionEventStore staleCaller = new PredictionEventStore(store.rootDirectory(), store.projectId());
        AgentStatus status = new IntegrationOrchestrationService().orchestrateIntegration(root, store, identity).status();

        assertEquals(AgentStatus.COMPLETED, status);
        store = new PredictionEventStore(store.rootDirectory(), store.projectId());
        assertEquals(TaskCompletionState.INTEGRATED, store.taskCompletionProjection().taskState(modelTask));
        assertEquals(TaskCompletionState.INTEGRATED, store.taskCompletionProjection().taskState(testTask));
        assertEquals("model", gitOutput(root, "show", "HEAD:src/model.py").trim());
        assertEquals("test", gitOutput(root, "show", "HEAD:tests/test_model.py").trim());
        long integratedEventCount = store.events().size();
        assertEquals(AgentStatus.READY, new IntegrationOrchestrationService().orchestrateIntegration(root, staleCaller, identity).status());
        var replayed = new PredictionEventStore(store.rootDirectory(), store.projectId());
        assertEquals(integratedEventCount, replayed.events().size());
        assertEquals(TaskCompletionState.INTEGRATED, replayed.taskCompletionProjection().taskState(modelTask));
        assertEquals(TaskCompletionState.INTEGRATED, replayed.taskCompletionProjection().taskState(testTask));
    }

    @Test
    void dependentSnapshotWaitsThenWakesAfterItsLineageOwnerIntegrates(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.name", "Test");
        git(root, "config", "user.email", "test@example.invalid");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        String base = gitOutput(root, "rev-parse", "HEAD");
        String ownerCommit = commitFile(root, base, "src/api.py", "api\n", "owner");
        String dependentCommit = commitFile(root, base, "tests/test_api.py", "test\n", "dependent");

        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID group = UUID.randomUUID();
        UUID ownerLane = UUID.randomUUID();
        UUID dependentLane = UUID.randomUUID();
        UUID ownerTask = UUID.randomUUID();
        UUID dependentTask = UUID.randomUUID();
        UUID ownerLineage = WorkIntent.defaultAuthorityLineage(ownerLane);
        new WorkIntentService(store, identity).announce(new WorkIntent(ownerLane,
                location.projectId(),
                "agt_owner",
                "codex",
                ownerTask,
                "owner",
                "owner integrated",
                base,
                List.of(ResourceSelector.pathExact("src/api.py")),
                1,
                group,
                WorkIntent.Status.ANNOUNCED));
        store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkIntentService(store, identity).announce(new WorkIntent(dependentLane,
                location.projectId(),
                "agt_dependent",
                "claude",
                dependentTask,
                "dependent",
                "dependent integrated",
                base,
                List.of(ResourceSelector.pathExact("tests/test_api.py")),
                1,
                group,
                WorkIntent.Status.ANNOUNCED));
        store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());

        CapabilityRequestHandle handle = CapabilityRequestHandle.parse("req_123456789012");
        CapabilityContract contract = new CapabilityContract("Task", "TaskTracker", List.of("API"), List.of("owner tests"));
        CapabilityRequestPayload request = new CapabilityRequestPayload(handle,
                "task-tracker-api",
                identity.nodeId(),
                "sup",
                "dependent",
                identity.nodeId(),
                "sup",
                "owner",
                ownerLineage,
                contract,
                CapabilityLifecycleState.ACCEPTED,
                null);
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CREATED, identity.nodeId(), request.encode(), identity);
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_ACCEPTED, identity.nodeId(), request.encode(), identity);
        ImplementationEventPayload implementation = new ImplementationEventPayload(handle, ownerLineage, 1, base, ownerCommit, List.of("src/api.py"), "owner", "", "", List.of(), "");
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED, identity.nodeId(), implementation.encode(), identity);
        ImplementationEventPayload validationStarted = new ImplementationEventPayload(handle, ownerLineage, 1, base, ownerCommit, List.of("src/api.py"), "owner", "", "", List.of(), "");
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_VALIDATION_STARTED, identity.nodeId(), validationStarted.encode(), identity);
        ImplementationEventPayload validationAccepted = new ImplementationEventPayload(handle, ownerLineage, 1, base, ownerCommit, List.of(), "validated", "accepted", "", List.of(), "");
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_VALIDATED, identity.nodeId(), validationAccepted.encode(), identity);

        publishSnapshot(store,
                identity,
                root,
                dependentTask,
                "snap_000_dependent",
                dependentLane,
                group,
                dependentCommit,
                "agt_dependent",
                "tests/test_api.py",
                "PATH_EXACT:tests/test_api.py",
                dependentLane,
                List.of(handle.value()));
        publishSnapshot(store, identity, root, ownerTask, "snap_999_owner", ownerLane, group, ownerCommit, "agt_owner", "src/api.py", "PATH_EXACT:src/api.py", ownerLane, List.of());

        IntegrationOrchestrationService service = new IntegrationOrchestrationService();
        assertEquals(AgentStatus.COMPLETED, service.orchestrateIntegration(root, store, identity).status());
        store = new PredictionEventStore(store.rootDirectory(), store.projectId());
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, store.taskCompletionProjection().taskState(dependentTask));
        assertEquals(TaskCompletionState.INTEGRATED, store.taskCompletionProjection().taskState(ownerTask));

        store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals(AgentStatus.COMPLETED, service.orchestrateIntegration(root, store, identity).status());
        store = new PredictionEventStore(store.rootDirectory(), store.projectId());
        assertEquals(TaskCompletionState.INTEGRATED, store.taskCompletionProjection().taskState(dependentTask));
    }

    @Test
    void publishedPredecessorSnapshotIntegratesAndReleasesSuccessorLane(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.name", "Test");
        git(root, "config", "user.email", "test@example.invalid");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        String base = gitOutput(root, "rev-parse", "HEAD");
        String ownerCommit = commitFile(root, base, "src/recovered.py", "recovered\n", "owner");

        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID group = UUID.randomUUID();
        UUID ownerLane = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        WorkIntent owner = new WorkIntent(ownerLane,
                location.projectId(),
                "agt_owner",
                "codex",
                task,
                "recover",
                "integrated",
                base,
                List.of(ResourceSelector.pathExact("src/recovered.py")),
                1,
                group,
                WorkIntent.Status.ANNOUNCED);
        WorkIntentService intentService = new WorkIntentService(store, identity);
        assertTrue(intentService.announce(owner).acquired());
        intentService.suspend(owner.participant());
        String recoveryReference = root.resolve("recovery").toAbsolutePath() + "#hash";
        intentService.holdRecovery(owner.participant(), recoveryReference);

        UUID successorLane = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        WorkIntent successor = new WorkIntent(successorLane,
                location.projectId(),
                "agt_successor",
                "codex",
                task,
                owner.goal(),
                owner.acceptance(),
                base,
                owner.selectors(),
                2,
                group,
                owner.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                owner.role(),
                owner.reviewTargetSelectors(),
                owner.knownDependencies());
        new WorkGroupService(new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()), identity).issue(new LaneGrant(grantId,
                group,
                successorLane,
                successor.participant(),
                owner.version(),
                true));
        intentService.continueFromRecovery(new LaneContinuation(grantId, ownerLane, successor, owner.participant(), successor.participant(), owner.version(), recoveryReference));

        store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        publishSnapshot(store, identity, root, task, "snap_predecessor", ownerLane, group, ownerCommit, owner.participant(), "src/recovered.py", "PATH_EXACT:src/recovered.py", ownerLane, List.of());

        var integration = new IntegrationOrchestrationService().orchestrateIntegration(root, new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()), identity);
        assertEquals(AgentStatus.COMPLETED, integration.status(), integration.toJson());
        PredictionEventStore completed = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals(TaskCompletionState.INTEGRATED, completed.taskCompletionProjection().taskState(task));
        assertTrue(completed.collaborationProjection().intent(successorLane).isEmpty());
        assertEquals(org.synesis.coordination.domain.collaboration.WorkGroup.Status.COMPLETED, completed.workGroupProjection().group(group).orElseThrow().status());
        assertEquals("recovered", gitOutput(root, "show", "HEAD:src/recovered.py").trim());
    }
}
