package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.agent.workflow.AgentNextActionService;
import org.synesis.workspace.application.agent.workflow.NextActionRequest;
import org.synesis.workspace.application.capability.ContractConsumerService;
import org.synesis.workspace.application.capability.ContractOwnerService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.task.SnapshotRequest;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.test.ProviderTestSupport;
import org.synesis.workspace.test.TestGit;

/**
 * Exercises capability publication fences and immutable capture of the owner's assigned worktree.
 */
class ImplementationPublicationServiceTest {

    @TempDir
    Path temporaryDirectory;

    private Path projectRoot;
    private ProjectLocation location;
    private ProviderSessionBindingService bindings;
    private String handle;
    private String ownerConnection;
    private String requesterConnection;
    private UUID ownerIntentId;
    private UUID workGroupId;
    private long claimEpoch;
    private long contractRevision;
    private Path ownerWorktree;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = temporaryDirectory.resolve("capability-publication");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.createDirectories(projectRoot.resolve("tests"));
        git(projectRoot, "init");
        git(projectRoot, "config", "user.name", "Test User");
        git(projectRoot, "config", "user.email", "test@example.invalid");
        Files.writeString(projectRoot.resolve("src/Product.java"), "public class Product {}\n");
        Files.writeString(projectRoot.resolve("tests/Consumer.java"), "public class Consumer {}\n");
        git(projectRoot, "add", ".");
        git(projectRoot, "commit", "-m", "Initial commit");

        ProjectApplicationService projectService = new ProjectApplicationService();
        location = projectService.init(projectRoot).location();
        ProviderTestSupport.install(location, "codex");
        bindings = new ProviderSessionBindingService();
        ownerConnection = "publication-owner";
        requesterConnection = "publication-requester";
        AgentSessionService sessions = new AgentSessionService();
        sessions.ensureSession(new SessionResolutionRequest(projectRoot, "codex", ownerConnection, null, false));
        sessions.ensureSession(new SessionResolutionRequest(projectRoot, "codex", requesterConnection, null, false));
        verifyTrusted("codex", ownerConnection);
        verifyTrusted("codex", requesterConnection);

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var requester = collaboration.announce(projectRoot,
                "codex",
                requesterConnection,
                "Implement a task-tracker consumer",
                "Use the published task-tracker capability",
                List.of(ResourceSelector.pathExact("tests/Consumer.java")),
                null,
                org.synesis.coordination.domain.collaboration.WorkIntent.Role.PRODUCER,
                List.of(),
                List.of("tasktracker.domain"));
        var owner = collaboration.announce(projectRoot,
                "codex",
                ownerConnection,
                "Implement the task-tracker domain",
                "Publish the task-tracker capability",
                List.of(ResourceSelector.pathSubtree("src")),
                requester.intent().workGroupId());
        ownerIntentId = owner.intent().intentId();
        workGroupId = owner.intent().workGroupId();
        claimEpoch = owner.intent().version();
        ownerWorktree = Path.of(bindings.find(location, "codex", ownerConnection).orElseThrow().worktreePath());

        CapabilityContract contract = new CapabilityContract("Task title and description",
                "Task and repository contract",
                List.of("Create TODO tasks", "Enforce valid status transitions"),
                List.of("Invalid input is rejected"));
        AgentResponse published = new ContractOwnerService().publish(new ContractOwnerService.PublishRequest(projectRoot,
                "codex",
                ownerConnection,
                List.of(ResourceSelector.pathExact("src/Product.java")),
                contract,
                List.of()));
        assertEquals(AgentStatus.COMPLETED, published.status(), published.toJson());
        AgentResponse bound = new ContractConsumerService().bind(new ContractConsumerService.BindRequest(projectRoot,
                "codex",
                requesterConnection,
                "tasktracker.domain",
                ResourceSelector.pathExact("src/Product.java"),
                1));
        assertEquals(AgentStatus.READY, bound.status(), bound.toJson());
        handle = (String) ((Map<?, ?>) bound.result()).get("capabilityRequestHandle");

        AgentResponse ownerInbox = new AgentNextActionService().getNextAction(new NextActionRequest(projectRoot, "codex", ownerConnection));
        contractRevision = ((Number) ((Map<?, ?>) ownerInbox.result()).get("contractRevision")).longValue();
    }

    @Test
    void publicationRejectsStaleRevisionAuthorityAndTreeBeforeCapturingDirtyClaimedSource() throws Exception {
        Files.writeString(ownerWorktree.resolve("src/Product.java"), "public class Product { int count() { return 2; } }\n");
        Files.writeString(ownerWorktree.resolve("src/Untracked.java"), "final class Untracked {}\n");

        AgentResponse readiness = completionReadiness();
        Map<?, ?> publicationArguments = publicationArguments(readiness);
        String initialTreeHash = (String) publicationArguments.get("expectedTreeHash");
        assertNotNull(initialTreeHash);

        ImplementationPublicationService service = new ImplementationPublicationService();
        long acceptedHead = durableHead();

        AgentResponse staleRevision = service.publishImplementation(request(publicationArguments,
                contractRevision + 1,
                ownerConnection));
        assertEquals(AgentStatus.BLOCKED, staleRevision.status(), staleRevision.toJson());
        assertEquals("CAPABILITY_CONTRACT_REVISION_STALE", responseReason(staleRevision));
        assertEquals(acceptedHead, durableHead(), "A stale contract revision must not append a publication");

        AgentResponse wrongAuthority = service.publishImplementation(request(publicationArguments,
                contractRevision,
                requesterConnection));
        assertEquals(AgentStatus.BLOCKED, wrongAuthority.status(), wrongAuthority.toJson());
        assertEquals("CAPABILITY_OWNER_MISMATCH", responseReason(wrongAuthority));
        assertEquals(acceptedHead, durableHead(), "The requester must not publish using the owner's fence");

        AgentResponse staleIntent = service.publishImplementation(new ImplementationPublicationService.PublishRequest(projectRoot,
                "codex",
                ownerConnection,
                handle,
                "Publish task-tracker implementation",
                contractRevision,
                UUID.randomUUID(),
                claimEpoch,
                initialTreeHash));
        assertEquals(AgentStatus.BLOCKED, staleIntent.status(), staleIntent.toJson());
        assertEquals("CAPABILITY_PUBLISHER_STALE", responseReason(staleIntent));
        assertEquals(acceptedHead, durableHead());

        AgentResponse staleEpoch = service.publishImplementation(new ImplementationPublicationService.PublishRequest(projectRoot,
                "codex",
                ownerConnection,
                handle,
                "Publish task-tracker implementation",
                contractRevision,
                ownerIntentId,
                claimEpoch + 1,
                initialTreeHash));
        assertEquals(AgentStatus.BLOCKED, staleEpoch.status(), staleEpoch.toJson());
        assertEquals("CAPABILITY_PUBLISHER_STALE", responseReason(staleEpoch));
        assertEquals(acceptedHead, durableHead());

        AgentResponse missingEvidence = service.publishImplementation(new ImplementationPublicationService.PublishRequest(projectRoot,
                "codex",
                ownerConnection,
                handle,
                "Publication without projected fence",
                contractRevision,
                ownerIntentId,
                claimEpoch,
                null));
        assertEquals(AgentStatus.BLOCKED, missingEvidence.status(), missingEvidence.toJson());
        assertEquals("CAPABILITY_PUBLICATION_EVIDENCE_REQUIRED", responseReason(missingEvidence));
        assertEquals(acceptedHead, durableHead());

        Files.writeString(ownerWorktree.resolve("src/Product.java"), "public class Product { int count() { return 3; } }\n");
        String originalHead = gitOutput(ownerWorktree, "rev-parse", "HEAD");
        String originalIndexTree = gitOutput(ownerWorktree, "write-tree");
        String originalStatus = gitOutput(ownerWorktree, "status", "--porcelain=v1");
        AgentResponse staleTree = service.publishImplementation(request(publicationArguments,
                contractRevision,
                ownerConnection));
        assertEquals(AgentStatus.BLOCKED, staleTree.status(), staleTree.toJson());
        assertEquals("CAPABILITY_PUBLICATION_TREE_STALE", responseReason(staleTree));
        assertEquals(acceptedHead, durableHead(), "A stale tree hash must not append a publication");

        AgentResponse refreshedReadiness = completionReadiness();
        Map<?, ?> refreshedArguments = publicationArguments(refreshedReadiness);
        assertNotEquals(initialTreeHash, refreshedArguments.get("expectedTreeHash"));
        ImplementationPublicationService.PublishRequest successfulRequest = request(refreshedArguments,
                contractRevision,
                ownerConnection);
        AgentResponse published = service.publishImplementation(successfulRequest);

        assertEquals(AgentStatus.WAITING, published.status(), published.toJson());
        assertEquals("VALIDATION_REQUIRED", published.reason().name());
        assertEquals(originalHead, gitOutput(ownerWorktree, "rev-parse", "HEAD"), "Publication must preserve the owner's HEAD");
        assertEquals(originalIndexTree, gitOutput(ownerWorktree, "write-tree"), "Publication must preserve the owner's index");
        assertEquals(originalStatus, gitOutput(ownerWorktree, "status", "--porcelain=v1"), "Publication must preserve dirty and untracked files");

        var projection = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).capabilityRequestProjection();
        var revision = projection.findLatestImplementation(handle).orElseThrow();
        assertEquals(1, revision.revisionNumber());
        assertEquals(List.of("src/Product.java", "src/Untracked.java"), revision.changedPaths());
        assertEquals(originalHead, gitOutput(ownerWorktree, "rev-parse", revision.commitSha() + "^"));
        assertEquals("public class Product { int count() { return 3; } }", gitOutput(ownerWorktree, "show", revision.commitSha() + ":src/Product.java").trim());
        assertEquals("final class Untracked {}", gitOutput(ownerWorktree, "show", revision.commitSha() + ":src/Untracked.java").trim());
        assertEquals(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE, projection.findByHandle(handle).orElseThrow().state());

        TaskSnapshotRecord completionSnapshot = finishSnapshot();
        assertEquals(revision.commitSha(), completionSnapshot.commitSha(), "Finishing the unchanged lane must reuse the capability publication commit");
        assertEquals("refs/synesis/snapshots/" + completionSnapshot.snapshotId(), completionSnapshot.provenance().snapshotRef());
        assertEquals(revision.commitSha(), gitOutput(ownerWorktree, "rev-parse", completionSnapshot.provenance().snapshotRef()));

        long availableHead = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).headSequence();
        AgentResponse replay = service.publishImplementation(successfulRequest);
        assertEquals(AgentStatus.WAITING, replay.status(), replay.toJson());
        assertEquals("VALIDATION_REQUIRED", replay.reason().name());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) replay.result()).get("replayed"));
        assertEquals(availableHead, new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).headSequence(),
                "An exact replay after the capability becomes available must not append a second revision");
        var replayed = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).capabilityRequestProjection().findLatestImplementation(handle).orElseThrow();
        assertEquals(revision.revisionNumber(), replayed.revisionNumber());
        assertEquals(revision.commitSha(), replayed.commitSha());
        assertTrue(gitOutput(ownerWorktree, "for-each-ref", "--format=%(objectname)", "refs/synesis/snapshots").lines().anyMatch(revision.commitSha()::equals));

        ImplementationValidationService validationService = new ImplementationValidationService();
        AgentResponse missingInspection = validationService.validateImplementation(new ImplementationValidationService.ValidateRequest(projectRoot,
                "codex",
                requesterConnection,
                handle,
                "accepted",
                null,
                revision.revisionNumber(),
                List.of()));
        assertEquals(AgentStatus.BLOCKED, missingInspection.status(), missingInspection.toJson());
        assertEquals("VALIDATION_INSPECTION_REQUIRED", ((Map<?, ?>) missingInspection.result()).get("reason"));
        var awaitingValidation = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE, awaitingValidation.capabilityRequestProjection().findByHandle(handle).orElseThrow().state());

        AgentResponse openedValidation = validationService.validateImplementation(new ImplementationValidationService.ValidateRequest(projectRoot,
                "codex",
                requesterConnection,
                handle,
                "inspect",
                null,
                revision.revisionNumber(),
                List.of()));
        assertEquals(AgentStatus.READY, openedValidation.status(), openedValidation.toJson());
        var validationAccess = new CapabilityValidationSnapshotAccessService().resolve(projectRoot,
                "codex",
                requesterConnection,
                handle,
                revision.revisionNumber());
        assertTrue(validationAccess.available(), String.valueOf(validationAccess.error()));
        assertEquals(revision.commitSha(), gitOutput(validationAccess.access().worktreePath(), "rev-parse", "HEAD"));
        assertEquals("public class Product { int count() { return 3; } }",
                gitOutput(validationAccess.access().worktreePath(), "show", "HEAD:src/Product.java").trim());
        var wrongValidationAccess = new CapabilityValidationSnapshotAccessService().resolve(projectRoot,
                "codex",
                ownerConnection,
                handle,
                revision.revisionNumber());
        assertFalse(wrongValidationAccess.available());

        AgentResponse validation = validationService.validateImplementation(new ImplementationValidationService.ValidateRequest(projectRoot,
                "codex",
                requesterConnection,
                handle,
                "accepted",
                null,
                revision.revisionNumber(),
                List.of()));
        assertEquals(AgentStatus.COMPLETED, validation.status(), validation.toJson());
        var validatedStore = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertEquals(CapabilityLifecycleState.VALIDATED, validatedStore.capabilityRequestProjection().findByHandle(handle).orElseThrow().state());
        long validatedHead = validatedStore.headSequence();

        AgentResponse validatedReplay = service.publishImplementation(successfulRequest);
        assertEquals(AgentStatus.COMPLETED, validatedReplay.status(), validatedReplay.toJson());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) validatedReplay.result()).get("replayed"));
        assertEquals(validatedHead, new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).headSequence(),
                "An exact replay after validation must not append another publication");
    }

    @Test
    void committedOwnerPublicationCommitIsAlsoReusedByLaterLaneCompletion() throws Exception {
        Files.writeString(ownerWorktree.resolve("src/Product.java"), "public class Product { int count() { return 4; } }\n");
        git(ownerWorktree, "add", "src/Product.java");
        git(ownerWorktree, "commit", "-m", "Implement committed task-tracker product");
        String ownerHead = gitOutput(ownerWorktree, "rev-parse", "HEAD");

        Map<?, ?> arguments = publicationArguments(completionReadiness());
        ImplementationPublicationService service = new ImplementationPublicationService();
        AgentResponse published = service.publishImplementation(request(arguments, contractRevision, ownerConnection));
        assertEquals(AgentStatus.WAITING, published.status(), published.toJson());
        assertEquals("VALIDATION_REQUIRED", published.reason().name());
        var revision = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).capabilityRequestProjection().findLatestImplementation(handle).orElseThrow();
        assertEquals(ownerHead, gitOutput(ownerWorktree, "rev-parse", revision.commitSha() + "^"));

        TaskSnapshotRecord completionSnapshot = finishSnapshot();
        assertEquals(revision.commitSha(), completionSnapshot.commitSha(), "Clean completion must resolve the capability's stable snapshot ref");
        assertEquals(revision.commitSha(), gitOutput(ownerWorktree, "rev-parse", completionSnapshot.provenance().snapshotRef()));
    }

    @Test
    void aRequesterWhoseComputerDoesNotHoldTheImplementationIsToldToRetryWhileNoComputerThatHasItAnswers() throws Exception {
        Files.writeString(ownerWorktree.resolve("src/Product.java"), "public class Product { int count() { return 5; } }\n");
        AgentResponse published = new ImplementationPublicationService().publishImplementation(request(publicationArguments(completionReadiness()), contractRevision, ownerConnection));
        assertEquals(AgentStatus.WAITING, published.status(), published.toJson());
        Path coordination = location.root().resolve(".synesis/coordination");
        var revision = new PredictionEventStore(coordination, location.projectId()).capabilityRequestProjection().findLatestImplementation(handle).orElseThrow();
        String commit = revision.commitSha();
        // The log names a commit that was made on another computer: this computer's repository does not hold it.
        for (String ref : gitOutput(projectRoot, "for-each-ref", "--format=%(refname) %(objectname)", "refs/synesis").lines().toList()) {
            if (ref.endsWith(" " + commit)) {
                git(projectRoot, "update-ref", "-d", ref.substring(0, ref.indexOf(' ')));
            }
        }
        Path object = projectRoot.resolve(".git/objects").resolve(commit.substring(0, 2)).resolve(commit.substring(2));
        assertTrue(object.toFile().setWritable(true), "git writes objects read-only");
        Files.delete(object);
        // This computer is a member whose leader and mirror are out of reach: no code server, no coordination server.
        org.synesis.coordination.persistence.ReplicationSettings.member("leader-node").write(coordination);

        AgentResponse inspected = new ImplementationValidationService().validateImplementation(new ImplementationValidationService.ValidateRequest(projectRoot,
                "codex", requesterConnection, handle, "inspect", null, revision.revisionNumber(), List.of()));

        assertEquals(AgentStatus.BLOCKED, inspected.status(), inspected.toJson());
        assertEquals("task_not_ready", inspected.toMap().get("reason"), inspected.toJson());
        assertEquals("retry", inspected.toMap().get("nextAction"), inspected.toJson());
        assertEquals("CODE_REMOTE_UNAVAILABLE", responseReason(inspected), inspected.toJson());
    }

    private AgentResponse completionReadiness() {
        return new AgentNextActionService().getNextAction(new NextActionRequest(projectRoot, "codex", ownerConnection, true));
    }

    private ImplementationPublicationService.PublishRequest request(Map<?, ?> arguments, long revision, String connection) {
        return new ImplementationPublicationService.PublishRequest(projectRoot,
                "codex",
                connection,
                handle,
                "Publish task-tracker implementation",
                revision,
                UUID.fromString((String) arguments.get("intentId")),
                ((Number) arguments.get("claimEpoch")).longValue(),
                (String) arguments.get("expectedTreeHash"));
    }

    private TaskSnapshotRecord finishSnapshot() throws Exception {
        var ownerBinding = bindings.find(location, "codex", ownerConnection).orElseThrow();
        var ownerIntent = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).collaborationProjection().intent(ownerIntentId).orElseThrow();
        return new TaskSnapshotService().createSnapshot(new SnapshotRequest(ownerIntent.taskId(), ownerBinding.nodeId(), ownerBinding.supervisorId(), ownerBinding.workerId(), ownerBinding.sessionId(), ownerWorktree, location.root(), "Complete the task-tracker lane", java.util.Optional.empty(), List.of(),
            new SnapshotRequest.ClaimScope(ownerIntent.selectors(), List.of(), true),
            new SnapshotRequest.Lane(workGroupId, ownerIntentId, WorkspaceCollaborationService.participantHandle(ownerBinding.sessionId()), ownerBinding.sessionId(), claimEpoch, ownerIntent.authorityLineageId(), List.of()),
            false, ownerBinding.baseCommit(), null));
    }

    private static String responseReason(AgentResponse response) {
        return (String) ((Map<?, ?>) response.result()).get("reason");
    }

    private long durableHead() throws Exception {
        return new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).headSequence();
    }

    private static Map<?, ?> publicationArguments(AgentResponse response) {
        Object workflow = ((Map<?, ?>) response.result()).get("workflow");
        assertTrue(workflow instanceof Map<?, ?>, response.toJson());
        Object arguments = ((Map<?, ?>) workflow).get("arguments");
        assertTrue(arguments instanceof Map<?, ?>, response.toJson());
        return (Map<?, ?>) arguments;
    }

    private void verifyTrusted(String provider, String connection) throws Exception {
        var binding = bindings.find(location, provider, connection).orElseThrow();
        bindings.verifyWorkspaceTrust(location, provider, binding.sessionId(), Path.of(binding.worktreePath()));
    }

    private static void git(Path root, String... arguments) throws Exception {
        TestGit.run(root, arguments);
    }

    private static String gitOutput(Path root, String... arguments) throws Exception {
        return TestGit.output(root, arguments);
    }
}
