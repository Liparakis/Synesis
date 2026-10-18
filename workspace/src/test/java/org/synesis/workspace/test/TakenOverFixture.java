package org.synesis.workspace.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
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

/**
 * This computer's project with an agent session that owns a lane (with work in its worktree, committed, modified and
 * untracked), and another computer that can take the lane over, or whose log can be copied in. Used by the trash and
 * the notice tests (ADR-0098 step 7).
 */
public final class TakenOverFixture {

    public static final String PROVIDER = "codex";
    public static final String OWNER = "owner-conn";
    public static final String RESTORER = "restorer-conn";

    public final Path root;
    public final ProjectLocation location;
    public final NodeIdentity identity;
    public final NodeIdentity remote = generate();
    public final PredictionEventStore remoteStore;
    public final UUID groupId = UUID.randomUUID();
    public final WorkIntent lane;
    public final String ownerParticipant;
    public final WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();

    public TakenOverFixture(Path temp) throws Exception {
        root = Files.createDirectories(temp.resolve("project"));
        TestGit.run(root, "init");
        TestGit.run(root, "config", "user.name", "Test User");
        TestGit.run(root, "config", "user.email", "test@example.com");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Product.java"), "public class Product {}\n");
        Files.writeString(root.resolve("README.md"), "base\n");
        TestGit.run(root, "add", ".");
        TestGit.run(root, "commit", "-m", "Initial commit");
        location = new ProjectApplicationService().init(root).location();
        ProviderTestSupport.install(location, PROVIDER);
        session(OWNER);
        session(RESTORER);
        identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        ownerParticipant = participant(OWNER);
        collaboration.createWorkGroup(root, groupId, "Build a board", "CRUD", ReviewPolicy.none());
        lane = collaboration.announce(root, PROVIDER, OWNER, "Implement", "Publish", List.of(ResourceSelector.pathExact("src/Board.java")), groupId).intent();
        remoteStore = new PredictionEventStore(temp.resolve("remote"), location.projectId());
    }

    private static NodeIdentity generate() {
        try {
            return NodeIdentity.generate();
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void session(String connection) throws Exception {
        new AgentSessionService().ensureSession(new SessionResolutionRequest(root, PROVIDER, connection, null, false));
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var binding = bindings.find(location, PROVIDER, connection).orElseThrow();
        bindings.verifyWorkspaceTrust(location, PROVIDER, binding.sessionId(), Path.of(binding.worktreePath()));
    }

    public String participant(String connection) throws Exception {
        return WorkspaceCollaborationService.participantHandle(new ProviderSessionBindingService().find(location, PROVIDER, connection).orElseThrow().sessionId());
    }

    public Path worktree(String connection) throws Exception {
        return Path.of(new ProviderSessionBindingService().find(location, PROVIDER, connection).orElseThrow().worktreePath());
    }

    public PredictionEventStore store() throws Exception {
        return location.openCoordinationStore();
    }

    /** The owner's worktree gets a modified tracked file and two untracked ones, none committed. */
    public void dirtyOwnersWorktree() throws Exception {
        Path worktree = worktree(OWNER);
        Files.writeString(worktree.resolve("README.md"), "base\nowner edit\n");
        Files.writeString(worktree.resolve("src/Board.java"), "class Board {}\n");
        Files.createDirectories(worktree.resolve("src/notes"));
        Files.writeString(worktree.resolve("src/notes/deep.txt"), "deep\n");
    }

    /** The other computer copies this log, takes the owner's lane over from its base, and this computer takes its log back. */
    public WorkIntent otherComputerTakesTheLaneOver() throws Exception {
        copyHereToRemote();
        UUID targetId = UUID.randomUUID();
        WorkIntent target = new WorkIntent(targetId, location.projectId(), "agt_remote_taker", PROVIDER, lane.taskId(), lane.goal(), lane.acceptance(), lane.baseCommit(),
                lane.selectors(), lane.version() + 1, groupId, lane.authorityLineageId(), WorkIntent.Status.ANNOUNCED, lane.role(), lane.reviewTargetSelectors(),
                lane.knownDependencies());
        LaneContinuation continuation = new LaneContinuation(UUID.randomUUID(), lane.intentId(), target, lane.participant(), "agt_remote_taker", lane.version(),
                LaneContinuation.TAKEOVER_FROM_BASE + lane.baseCommit());
        new WorkIntentService(remoteStore, remote).continueEligibleFromRecovery(continuation);
        copyRemoteToHere();
        return target;
    }

    public void copyHereToRemote() throws Exception {
        remoteStore.placeCopies(identity.nodeId(), store().entriesAfter(PredictionEventStore.OWN_LOG, 0, 10_000, Integer.MAX_VALUE));
    }

    public void copyRemoteToHere() throws Exception {
        store().placeCopies(remote.nodeId(), remoteStore.entriesAfter(PredictionEventStore.OWN_LOG, 0, 10_000, Integer.MAX_VALUE));
    }

    /** One snapshot of the owner's lane, a commit adding src/Board.java on the base, published by this computer. */
    public String publishSnapshot(String snapshotId) throws Exception {
        String base = lane.baseCommit();
        String current = TestGit.output(root, "rev-parse", "--abbrev-ref", "HEAD").strip();
        TestGit.run(root, "checkout", "-q", "--detach", base);
        Files.writeString(root.resolve("src/Board.java"), "class Board { /* " + snapshotId + " */ }\n");
        TestGit.run(root, "add", "src/Board.java");
        TestGit.run(root, "commit", "-m", snapshotId);
        String commit = TestGit.output(root, "rev-parse", "HEAD").strip();
        TestGit.run(root, "checkout", "-q", current);
        String ref = "refs/synesis/snapshots/" + snapshotId;
        TestGit.run(root, "update-ref", ref, commit);
        SnapshotProvenance provenance = new SnapshotProvenance(groupId, lane.intentId(), lane.authorityLineageId(), lane.participant(), "binding", lane.version(),
                List.of(), List.of(), List.of("PATH_EXACT:src/Board.java"), ref, "integrity", "manifest");
        store().append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), new TaskSnapshotPayload(lane.taskId(), snapshotId, identity.nodeId(),
                "sup", "worker", "session", base, commit, List.of("src/Board.java"), List.of(), snapshotId, provenance, true).encode(), identity);
        return commit;
    }

    /** A reviewer of this computer rejects the snapshot on its own grant. */
    public void reject(String snapshotId) throws Exception {
        LaneGrant grant = new LaneGrant(UUID.randomUUID(), groupId, lane.intentId(), "agt_reviewer", lane.version(), true, snapshotId);
        PredictionEventStore store = store();
        new WorkGroupService(store, identity).issue(grant);
        new WorkGroupService(store, identity).consume(grant.grantId(), "agt_reviewer", lane.intentId(), lane.version());
        store.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, identity.nodeId(),
                new ReviewValidationPayload(grant.grantId(), groupId, lane.intentId(), "agt_reviewer", lane.version(), lane.taskId(), snapshotId, "REJECTED",
                        "needs work", lane.participant()).encode(), identity);
    }
}
