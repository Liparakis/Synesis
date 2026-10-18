package org.synesis.workspace.application.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.run.AgentRunController;
import org.synesis.workspace.application.agent.run.EventLogObserver;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.test.ProviderTestSupport;
import org.synesis.workspace.test.TestGit;

/**
 * A party to a review dispute is told of it in every next-action answer, and the run controller sees it (ADR-0098
 * step 7, designer items 6 and 7). Reviewer "disp-reviewer" and the author "disp-author" are sessions here; the
 * other reviewer is a participant of another computer whose verdict reaches this log by copy.
 */
final class ReviewDisputeNextActionTest {

    private static final String SNAPSHOT = "snap_disputed";
    private static final String REMOTE = "agt-remote-reviewer";

    @TempDir
    Path temp;

    private Path project;
    private ProjectLocation location;
    private String author;
    private String reviewer;
    private LaneGrant reviewerGrant;

    private void session(String connection) throws Exception {
        new AgentSessionService().ensureSession(new SessionResolutionRequest(project, "codex", connection, null, false));
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var binding = bindings.list(location, "codex").getLast();
        bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
    }

    private String participant(String connection) throws Exception {
        return WorkspaceCollaborationService.participantHandle(new ProviderSessionBindingService().find(location, "codex", connection).orElseThrow().sessionId());
    }

    @BeforeEach
    void setUp() throws Exception {
        project = Files.createDirectories(temp.resolve("project"));
        TestGit.run(project, "init");
        TestGit.run(project, "config", "user.name", "Test User");
        TestGit.run(project, "config", "user.email", "test@example.com");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/Product.java"), "public class Product {}\n");
        TestGit.run(project, "add", ".");
        TestGit.run(project, "commit", "-m", "Initial commit");
        location = new ProjectApplicationService().init(project).location();
        ProviderTestSupport.install(location, "codex");
        session("disp-author");
        session("disp-reviewer");
        session("disp-bystander");
        author = participant("disp-author");
        reviewer = participant("disp-reviewer");

        UUID groupId = UUID.randomUUID();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.createWorkGroup(project, groupId, "Build a board", "CRUD", ReviewPolicy.mandatoryIndependent());
        WorkIntent lane = collaboration.announce(project, "codex", "disp-author", "Implement", "Publish", List.of(ResourceSelector.pathExact("src/Board.java")),
                groupId).intent();
        var binding = new ProviderSessionBindingService().find(location, "codex", "disp-author").orElseThrow();
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        SnapshotProvenance provenance = new SnapshotProvenance(lane.workGroupId(), lane.intentId(), lane.authorityLineageId(), author, binding.sessionId(),
                lane.version(), List.of(), List.of(), List.of("PATH_EXACT:src/Board.java"), "refs/synesis/snapshots/" + SNAPSHOT, "integrity", "manifest");
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(),
                new TaskSnapshotPayload(lane.taskId(), SNAPSHOT, "node", binding.supervisorId(), binding.workerId(), binding.sessionId(), binding.baseCommit(),
                        "commit-" + SNAPSHOT, List.of("src/Board.java"), List.of(), SNAPSHOT, provenance, true).encode(), identity);
        reviewerGrant = new LaneGrant(UUID.randomUUID(), groupId, lane.intentId(), reviewer, lane.version(), true);
        LaneGrant remoteGrant = new LaneGrant(UUID.randomUUID(), groupId, lane.intentId(), REMOTE, lane.version(), true);
        for (LaneGrant grant : List.of(reviewerGrant, remoteGrant)) {
            for (PredictionEventType type : List.of(PredictionEventType.LANE_GRANT_ISSUED, PredictionEventType.LANE_GRANT_CONSUMED)) {
                store.append(grant.grantId(), type, identity.nodeId(), CollaborationCodec.encodeLaneGrant(grant), identity);
            }
        }
        NodeIdentity remote = NodeIdentity.generate();
        PredictionEventStore theirs = new PredictionEventStore(temp.resolve("theirs"), location.projectId());
        theirs.placeCopies(identity.nodeId(), store.entriesAfter(0, 10_000, Integer.MAX_VALUE));
        UUID remoteLane = UUID.randomUUID();
        WorkIntent introduced = new WorkIntent(remoteLane, location.projectId(), REMOTE, "codex", UUID.randomUUID(), "review", "acceptance", "base",
                List.of(ResourceSelector.pathExact("src/Other.java")), 1, UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(remoteLane),
                WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(theirs, remote).announce(introduced).acquired());
        theirs.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, remote.nodeId(),
                new ReviewValidationPayload(remoteGrant.grantId(), groupId, lane.intentId(), REMOTE, lane.version(), lane.taskId(), SNAPSHOT, "REJECTED",
                        "not good enough", author).encode(), remote);
        store.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, identity.nodeId(),
                new ReviewValidationPayload(reviewerGrant.grantId(), groupId, lane.intentId(), reviewer, lane.version(), lane.taskId(), SNAPSHOT,
                        "ACCEPTED", null, author).encode(), identity);
        store.placeCopies(remote.nodeId(), theirs.entriesAfter(0, 10_000, Integer.MAX_VALUE));
        assertEquals(TaskCompletionState.REVIEW_DISPUTED, store.taskCompletionProjection().snapshotState(SNAPSHOT).orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(AgentResponse response) {
        assertInstanceOf(Map.class, response.result(), response.toJson());
        return (Map<String, Object>) response.result();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dispute(String connection) {
        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(project, "codex", connection));
        Object notice = result(response).get("reviewDispute");
        assertInstanceOf(List.class, notice, response.toJson());
        return (List<Map<String, Object>>) notice;
    }

    @Test
    void theReviewerAndTheAuthorAreToldOfTheDisputeInTheirNextActionAnswer() throws Exception {
        List<Map<String, Object>> toReviewer = dispute("disp-reviewer");
        assertEquals(1, toReviewer.size(), toReviewer.toString());
        assertEquals(SNAPSHOT, toReviewer.getFirst().get("snapshotId"));
        assertEquals("ACCEPTED", toReviewer.getFirst().get("yourVerdict"));
        assertTrue(toReviewer.getFirst().containsKey("newVerdict"), toReviewer.toString());
        assertEquals(List.of(REMOTE, author), toReviewer.getFirst().get("messageTo"));

        List<Map<String, Object>> toAuthor = dispute("disp-author");
        assertEquals(SNAPSHOT, toAuthor.getFirst().get("snapshotId"));
        assertNull(toAuthor.getFirst().get("newVerdict"));
    }

    @Test
    void anUninvolvedSessionsAnswerCarriesNoDispute() throws Exception {
        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(project, "codex", "disp-bystander"));

        Object result = response.result();
        assertFalse(result instanceof Map<?, ?> map && map.containsKey("reviewDispute"), response.toJson());
    }

    @Test
    void theRunObserverSeesTheDisputeForItsPartiesAndKeepsTheReviewersSession() throws Exception {
        AgentRunController.Observation observation = new EventLogObserver(location).observe();

        List<String> reviewerKeys = observation.disputesFor("codex", List.of("disp-reviewer"));
        assertEquals(1, reviewerKeys.size(), reviewerKeys.toString());
        assertTrue(reviewerKeys.getFirst().startsWith(SNAPSHOT + " "), reviewerKeys.getFirst());
        assertEquals(reviewerKeys, observation.disputesFor("codex", List.of("never-connected", "disp-reviewer")));
        assertEquals(1, observation.disputesFor("codex", List.of("disp-author")).size());
        assertEquals(List.of(), observation.disputesFor("codex", List.of("disp-bystander")));
        assertEquals(List.of(), observation.disputesFor("codex", List.of("never-connected")));

        assertTrue(observation.holdsRequestOrGrant("codex", "disp-reviewer"), "its verdict may still be replaced, and only that session may");
        assertFalse(observation.holdsRequestOrGrant("codex", "disp-author"));
        assertFalse(observation.holdsRequestOrGrant("codex", "disp-bystander"));
    }
}
