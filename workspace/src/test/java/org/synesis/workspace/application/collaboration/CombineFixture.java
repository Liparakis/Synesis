package org.synesis.workspace.application.collaboration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
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
import org.synesis.workspace.application.control.ControlBranchAdvancementService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.task.SnapshotRequest;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.test.ProviderTestSupport;
import org.synesis.workspace.test.TestGit;

/**
 * A project in which a re-merge of two copies of {@code main} met a conflict Git cannot resolve: an author's integrated
 * lane changed {@code src/f.txt} one way, {@code main} (the other copy) changed it another way, and the log holds the
 * combine's work group and {@code COMBINE_REQUIRED}, as {@code RemergeService.openCombine} writes them.
 */
public final class CombineFixture {

    /** The provider every session of the fixture runs under. */
    public static final String PROVIDER = "codex";
    /** The file both versions changed. */
    public static final String FILE = "src/f.txt";
    /** The connection of the combine's author. */
    public static final String AUTHOR = "combine-author";

    public final Path root;
    public final ProjectLocation location;
    public final NodeIdentity identity;
    public final WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
    /** The author's integrated snapshot, the "theirs" of the combine. */
    public final TaskSnapshotRecord authorSnapshot;
    /** The author's lane. */
    public final WorkIntent authorLane;
    /** The commit {@code main} moved to, the combine's base. */
    public final String base;
    public final CombinePayload combine;

    private CombineFixture(Path root, ProjectLocation location, NodeIdentity identity, TaskSnapshotRecord authorSnapshot,
            WorkIntent authorLane, String base, CombinePayload combine) {
        this.root = root;
        this.location = location;
        this.identity = identity;
        this.authorSnapshot = authorSnapshot;
        this.authorLane = authorLane;
        this.base = base;
        this.combine = combine;
    }

    /**
     * Builds the fixture under {@code dir}.
     *
     * @param dir an empty directory
     * @return the fixture
     * @throws Exception when the project cannot be built
     */
    public static CombineFixture create(Path dir) throws Exception {
        Path root = dir.resolve("project");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve(FILE), "orig\n");
        TestGit.run(root, "init");
        TestGit.run(root, "config", "user.name", "Combine Test");
        TestGit.run(root, "config", "user.email", "combine@example.test");
        TestGit.run(root, "add", ".");
        TestGit.run(root, "commit", "-m", "baseline");
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        ProviderTestSupport.install(location, PROVIDER);
        ProviderSessionBindingService.Binding authorBinding = ensureSession(root, AUTHOR);
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        List<ResourceSelector> selectors = List.of(ResourceSelector.pathExact(FILE));
        WorkIntent lane = collaboration.announce(root, PROVIDER, AUTHOR, "change f", "f changed", selectors).intent();
        Path authorWorktree = Path.of(authorBinding.worktreePath());
        Files.writeString(authorWorktree.resolve(FILE), "author\n");
        TaskSnapshotRecord snapshot = new TaskSnapshotService().createSnapshot(new SnapshotRequest(lane.taskId(), "node", authorBinding.supervisorId(),
                authorBinding.workerId(), authorBinding.sessionId(), authorWorktree, root, "author version", java.util.Optional.empty(), List.of(),
                SnapshotRequest.ClaimScope.unscoped(selectors),
                SnapshotRequest.Lane.of(lane.workGroupId(), lane.intentId(), WorkspaceCollaborationService.participantHandle(authorBinding.sessionId()),
                        authorBinding.sessionId(), lane.version(), List.of()),
                false, null, null));
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), payload(snapshot, snapshot.reviewRequired()).encode(), identity);
        store.append(UUID.randomUUID(), PredictionEventType.TASK_INTEGRATED, identity.nodeId(), payload(snapshot, false).encode(), identity);
        ControlBranchAdvancementService.recordIntegrated(snapshot, new PredictionEventStore(store.rootDirectory(), store.projectId()), identity);
        // What finishing an integration does next: the lane is released, so its claims are free.
        collaboration.release(root, PROVIDER, AUTHOR);

        // The other copy of main: the same file, changed the other way.
        Files.writeString(root.resolve(FILE), "other\n");
        TestGit.run(root, "add", FILE);
        TestGit.run(root, "commit", "-m", "other copy of main");
        String base = TestGit.output(root, "rev-parse", "HEAD");

        CombinePayload combine = new CombinePayload("cb-" + UUID.randomUUID(), "remerge-test", base, snapshot.commitSha(), base, snapshot.commitSha(),
                List.of(snapshot.snapshotId()));
        PredictionEventStore current = new PredictionEventStore(store.rootDirectory(), store.projectId());
        WorkGroup group = new WorkGroup(combine.workGroupId(), current.projectId(), "Combine " + snapshot.snapshotId(), "One change that holds both versions", 1,
                WorkGroup.Status.ACTIVE, ReviewPolicy.none());
        current.append(group.workGroupId(), PredictionEventType.WORK_GROUP_CREATED, identity.nodeId(), CollaborationCodec.encodeWorkGroup(group), identity);
        current.append(UUID.randomUUID(), PredictionEventType.COMBINE_REQUIRED, identity.nodeId(), combine.encode(), identity);
        return new CombineFixture(root, location, identity, snapshot, lane, base, combine);
    }

    /**
     * Gives a connection a lane of its own on {@code file} whose snapshot waits for its review, held by a reviewer that
     * took the review and has recorded no verdict yet.
     *
     * @param connection connection instance
     * @param file       the file the lane changes
     * @param reviewer   connection instance of the reviewer
     * @return the snapshot awaiting review
     * @throws Exception when the lane or snapshot cannot be made
     */
    public TaskSnapshotRecord awaitingReview(String connection, String file, String reviewer) throws Exception {
        ProviderSessionBindingService.Binding binding = session(connection);
        List<ResourceSelector> selectors = List.of(ResourceSelector.pathExact(file));
        WorkIntent lane = collaboration.announce(root, PROVIDER, connection, "change " + file, file + " changed", selectors).intent();
        Path worktree = Path.of(binding.worktreePath());
        Files.createDirectories(worktree.resolve(file).getParent());
        Files.writeString(worktree.resolve(file), connection + "\n");
        TaskSnapshotRecord snapshot = new TaskSnapshotService().createSnapshot(new SnapshotRequest(lane.taskId(), "node", binding.supervisorId(),
                binding.workerId(), binding.sessionId(), worktree, root, file + " version", java.util.Optional.empty(), List.of(),
                SnapshotRequest.ClaimScope.unscoped(selectors),
                SnapshotRequest.Lane.of(lane.workGroupId(), lane.intentId(), WorkspaceCollaborationService.participantHandle(binding.sessionId()),
                        binding.sessionId(), lane.version(), List.of()),
                false, null, null));
        store().append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), payload(snapshot, true).encode(), identity);
        session(reviewer);
        LaneGrant grant = new LaneGrant(UUID.randomUUID(), lane.workGroupId(), lane.intentId(), participant(reviewer), lane.version(), true,
                snapshot.snapshotId());
        store().append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(), CollaborationCodec.encodeLaneGrant(grant), identity);
        store().append(UUID.randomUUID(), PredictionEventType.LANE_GRANT_CONSUMED, identity.nodeId(), CollaborationCodec.encodeLaneGrant(grant), identity);
        return snapshot;
    }

    private static TaskSnapshotPayload payload(TaskSnapshotRecord snapshot, boolean reviewRequired) {
        return new TaskSnapshotPayload(snapshot.taskId(), snapshot.snapshotId(), snapshot.nodeId(), snapshot.supervisorId(), snapshot.workerId(),
                snapshot.providerSessionId(), snapshot.baseCommit(), snapshot.commitSha(), snapshot.changedPaths(), snapshot.capabilityDependencies(),
                snapshot.summary(), snapshot.provenance(), reviewRequired);
    }

    /**
     * Ensures a trusted session with no lane.
     *
     * @param root       project root
     * @param connection connection instance
     * @return its binding
     * @throws Exception when the session cannot be made
     */
    public static ProviderSessionBindingService.Binding ensureSession(Path root, String connection) throws Exception {
        new AgentSessionService().ensureSession(new SessionResolutionRequest(root, PROVIDER, connection, null, false));
        ProjectLocation location = new ProjectApplicationService().locate(root);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        ProviderSessionBindingService.Binding binding = bindings.find(location, PROVIDER, connection).orElseThrow();
        bindings.verifyWorkspaceTrust(location, PROVIDER, binding.sessionId(), Path.of(binding.worktreePath()));
        return bindings.find(location, PROVIDER, connection).orElseThrow();
    }

    /**
     * The author's session as a real agent finds it once its lane was integrated: the published work is in main, so the
     * worktree holds nothing unpublished, and the session is refreshed onto this computer's main.
     *
     * @throws Exception when Git or the session fails
     */
    public void authorLaneIntegrated() throws Exception {
        TestGit.run(Path.of(session(AUTHOR).worktreePath()), "checkout", "--", ".");
        ensureSession(root, AUTHOR);
    }

    /**
     * Moves this computer's {@code main} off the combine's base, as a real re-merge finds it (s5-run8): the two copies
     * of {@code main} diverged, and the combine's base is the other copy's, not this computer's control head.
     *
     * @return this computer's new control head
     * @throws Exception when Git fails
     */
    public String divergeThisCopyOfMain() throws Exception {
        TestGit.run(root, "reset", "-q", "--hard", "HEAD~1");
        Files.writeString(root.resolve("src/own.txt"), "own\n");
        TestGit.run(root, "add", "src/own.txt");
        TestGit.run(root, "commit", "-q", "-m", "this copy of main");
        return TestGit.output(root, "rev-parse", "HEAD");
    }

    /**
     * Ensures a session of this project.
     *
     * @param connection connection instance
     * @return its binding
     * @throws Exception when the session cannot be made
     */
    public ProviderSessionBindingService.Binding session(String connection) throws Exception {
        return ensureSession(root, connection);
    }

    /**
     * Returns the binding of a connection.
     *
     * @param connection connection instance
     * @return its binding
     * @throws Exception when it has none
     */
    public ProviderSessionBindingService.Binding binding(String connection) throws Exception {
        return new ProviderSessionBindingService().find(location, PROVIDER, connection).orElseThrow();
    }

    /**
     * Returns the participant handle of a connection's session.
     *
     * @param connection connection instance
     * @return the handle
     * @throws Exception when it has no session
     */
    public String participant(String connection) throws Exception {
        return WorkspaceCollaborationService.participantHandle(binding(connection).sessionId());
    }

    /**
     * Opens the coordination log afresh.
     *
     * @return a store over the project's log
     * @throws Exception when it cannot be opened
     */
    public PredictionEventStore store() throws Exception {
        return new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
    }
}
