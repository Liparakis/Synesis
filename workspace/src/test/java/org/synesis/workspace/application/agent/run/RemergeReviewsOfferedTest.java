package org.synesis.workspace.application.agent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.RemergeSnapshotPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.test.ProviderTestSupport;

/**
 * The run controller resumes a stopped agent for a re-merge snapshot its session is granted to review again, whether
 * or not the session holds a lane (ADR-0098 step 5, designer item 14).
 */
final class RemergeReviewsOfferedTest {

    private static final String ORIGINAL = "snap_original";
    private static final String REMERGE = "snap_remerge";

    private Path controlRoot;

    @BeforeEach
    void setUp() throws Exception {
        controlRoot = Files.createTempDirectory("synesis-remerge-offered-test-");
        org.synesis.workspace.test.TestGit.run(controlRoot, "init");
        org.synesis.workspace.test.TestGit.run(controlRoot, "config", "user.name", "Test User");
        org.synesis.workspace.test.TestGit.run(controlRoot, "config", "user.email", "test@example.com");
        Files.createDirectories(controlRoot.resolve("src"));
        Files.writeString(controlRoot.resolve("src/Product.java"), "public class Product {}\n");
        org.synesis.workspace.test.TestGit.run(controlRoot, "add", ".");
        org.synesis.workspace.test.TestGit.run(controlRoot, "commit", "-m", "Initial commit");
        ProjectLocation location = new ProjectApplicationService().init(controlRoot).location();
        ProviderTestSupport.install(location, "codex");
    }

    private void session(String connection) throws Exception {
        new AgentSessionService().ensureSession(new SessionResolutionRequest(controlRoot, "codex", connection, null, false));
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        var binding = bindings.list(location, "codex").getLast();
        bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
    }

    private static TaskSnapshotPayload snapshot(WorkIntent lane, ProviderSessionBindingService.Binding binding,
            String owner, String snapshotId, boolean reviewRequired) {
        return new TaskSnapshotPayload(lane.taskId(),
                snapshotId,
                "node",
                binding.supervisorId(),
                binding.workerId(),
                binding.sessionId(),
                binding.baseCommit(),
                "commit-" + snapshotId,
                List.of("src/BoardCreate.java"),
                List.of(),
                snapshotId,
                new SnapshotProvenance(lane.workGroupId(),
                        lane.intentId(),
                        lane.authorityLineageId(),
                        owner,
                        binding.sessionId(),
                        lane.version(),
                        List.of(),
                        List.of(),
                        List.of("PATH_EXACT:src/BoardCreate.java"),
                        "refs/synesis/snapshots/" + snapshotId,
                        "integrity-" + snapshotId,
                        "manifest-" + snapshotId),
                reviewRequired);
    }

    @Test
    void aSessionGrantedToReviewAReMergeAgainIsOfferedItAndOnlyUntilTheGrantIsConsumed() throws Exception {
        session("remerge-owner");
        session("remerge-reviewer");
        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var ownerBinding = bindings.find(location, "codex", "remerge-owner").orElseThrow();
        var reviewerBinding = bindings.find(location, "codex", "remerge-reviewer").orElseThrow();
        String owner = WorkspaceCollaborationService.participantHandle(ownerBinding.sessionId());
        String reviewer = WorkspaceCollaborationService.participantHandle(reviewerBinding.sessionId());

        UUID groupId = UUID.randomUUID();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.createWorkGroup(controlRoot, groupId, "Build a board", "CRUD and persistence",
                ReviewPolicy.mandatoryIndependent());
        WorkIntent lane = collaboration.announce(controlRoot, "codex", "remerge-owner", "Implement board creation",
                "Publish the board creation lane", List.of(ResourceSelector.pathExact("src/BoardCreate.java")), groupId)
                .intent();

        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"),
                location.projectId());
        TaskSnapshotPayload original = snapshot(lane, ownerBinding, owner, ORIGINAL, false);
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), original.encode(),
                identity);
        store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_STARTED, identity.nodeId(),
                new IntegrationAttemptPayload("att_original", location.projectId(), List.of(ORIGINAL),
                        ownerBinding.baseCommit(), "integration", "started", "").encode(),
                identity);
        store.append(UUID.randomUUID(), PredictionEventType.TASK_INTEGRATED, identity.nodeId(), original.encode(),
                identity);
        store.append(UUID.randomUUID(), PredictionEventType.REMERGE_SNAPSHOT_CREATED, identity.nodeId(),
                new RemergeSnapshotPayload("remerge-1", ORIGINAL, "other-head", "own-head", "result",
                        snapshot(lane, ownerBinding, owner, REMERGE, true)).encode(),
                identity);
        AgentRunController.Observation before = new EventLogObserver(location).observe();
        assertEquals(List.of(), before.reviewsOffered("codex", List.of("remerge-reviewer")),
                "nothing is offered before a grant names the re-merge snapshot");

        LaneGrant grant = new LaneGrant(UUID.randomUUID(), groupId, lane.intentId(), reviewer, lane.version(), true,
                REMERGE);
        store.append(UUID.randomUUID(), PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant), identity);

        AgentRunController.Observation granted = new EventLogObserver(location).observe();
        assertEquals(List.of(REMERGE), granted.reviewsOffered("codex", List.of("remerge-reviewer")));
        assertEquals(List.of(), granted.reviewsOffered("codex", List.of("remerge-owner")),
                "the grant names the reviewer, not the author");
        assertEquals(List.of(), granted.reviewsOffered("codex", List.of("never-connected")));

        store.append(UUID.randomUUID(), PredictionEventType.LANE_GRANT_CONSUMED, identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant), identity);
        AgentRunController.Observation consumed = new EventLogObserver(location).observe();
        assertEquals(List.of(), consumed.reviewsOffered("codex", List.of("remerge-reviewer")));
    }
}
