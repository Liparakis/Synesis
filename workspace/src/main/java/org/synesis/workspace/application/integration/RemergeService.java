package org.synesis.workspace.application.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ReviewAdmissionRules;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CombinePayload;
import org.synesis.coordination.domain.task.RemergeSnapshotPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.collaboration.AwayLanes;
import org.synesis.workspace.application.control.ControlBranchAdvancementService;
import org.synesis.workspace.application.control.MainMeeting;
import org.synesis.workspace.application.project.execution.ExecutionRequest;
import org.synesis.workspace.application.project.execution.ExecutionResult;
import org.synesis.workspace.application.project.execution.ProjectCommandSpec;
import org.synesis.workspace.application.project.execution.ProjectProcessExecutor;
import org.synesis.workspace.application.task.SnapshotRefs;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Re-merges this computer's work onto another computer's copy of {@code main} when the two diverged (ADR-0098 step 5,
 * designer items 13 to 15). Of two copies that each hold work the other lacks, the computer with the higher node ID
 * re-merges; the other waits for the result and takes it by fast-forward, after its audit.
 *
 * <p>A re-merge is decided by the log alone: its own advances whose heads the other copy lacks, oldest first. Its ID
 * follows from the two heads, so a pass that finds it built already goes on to landing it. It is built once, in a
 * worktree at the other head, with one merge commit per advance and then this copy's old head, and the validation
 * command run on the result. Each lane whose original snapshot needed review gets a re-merge snapshot and a grant to
 * the reviewer who accepted the original. It lands when every one of them is accepted: this copy's branch moves to the
 * result, which holds its old head, so {@code main} only moves forward. The pass runs under the integration lock, so
 * no ordinary advance of this copy runs at the same time.</p>
 */
final class RemergeService {

    private static final System.Logger LOG = System.getLogger(RemergeService.class.getName());

    /** The ref that keeps a re-merge's result reachable until it lands. */
    private static final String RESULT_PREFIX = "refs/synesis/remerge/";

    /** The ref that keeps the commit a combine lane combines onto reachable. */
    static final String BASE_PREFIX = "refs/synesis/remerge-base/";

    /**
     * Re-merges that could not be built or will not land, in this process: Git could not merge this copy's old head, the
     * validation command failed, or a review rejected it. Each is reported once and not tried again until the heads
     * change. An advance Git cannot merge is not one of them: it opens a combine lane.
     */
    private static final Set<String> STOPPED = ConcurrentHashMap.newKeySet();

    private final ProjectApplicationService projectService;
    private final ProjectProcessExecutor processExecutor;
    private final LaneFinisher finisher;
    private final org.synesis.workspace.application.provider.ProviderSessionBindingService bindingService = new org.synesis.workspace.application.provider.ProviderSessionBindingService();

    /** Ends lanes whose snapshot was recorded integrated, as the merge pass does for its own lanes. */
    @FunctionalInterface
    interface LaneFinisher {

        /**
         * Releases the lanes of integrated snapshots.
         *
         * @param lock        the held append lock
         * @param controlRoot the control checkout
         * @param snapshots   the integrated snapshots
         * @param store       the log, read after the integration was recorded
         * @param identity    this computer
         * @throws Exception when a lane cannot be released
         */
        void finish(ProjectAppendLock lock, Path controlRoot, List<TaskSnapshotRecord> snapshots, PredictionEventStore store,
                NodeIdentity identity) throws Exception;
    }

    /** One lane of one re-merged advance: the original snapshot and the merge commit that carries it now. */
    private record Lane(TaskSnapshotRecord original, String base, String merged) {
    }

    RemergeService(ProjectApplicationService projectService, ProjectProcessExecutor processExecutor, LaneFinisher finisher) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.processExecutor = Objects.requireNonNull(processExecutor, "processExecutor");
        this.finisher = Objects.requireNonNull(finisher, "finisher");
    }

    /** A service whose combine lanes are only released, with no provider session to complete. */
    RemergeService(ProjectApplicationService projectService, ProjectProcessExecutor processExecutor) {
        this(projectService, processExecutor, (lock, controlRoot, snapshots, store, identity) -> {
            for (TaskSnapshotRecord snapshot : snapshots) {
                new org.synesis.coordination.application.WorkIntentService(store, identity).releaseAfterIntegration(lock, snapshot);
            }
        });
    }

    /**
     * Builds or lands the re-merge onto another computer's copy of {@code main}, when this computer is the one that
     * re-merges. The caller holds the integration lock. Nothing here fails the pass: what cannot be done is logged and
     * left to the next pass.
     *
     * <p>Of the copies in the way, only the computer with the highest node ID re-merges (designer item 5, for any
     * number of computers). A copy is in the way when it diverged from this one and no other diverged copy holds it.
     * A copy this one is behind is taken by fast-forward first ({@code CodeOverLink}), so nothing is re-merged onto a
     * copy that is about to move. With three copies, letting every higher computer of each pair re-merge never
     * settled: the middle one re-merged onto the lowest while the highest re-merged onto the middle one, and each new
     * merge commit was a new divergence (the convergence property test, 2026-10-18).</p>
     *
     * @param controlRoot the control checkout
     * @param store       this computer's logs
     * @param identity    this computer
     */
    void run(Path controlRoot, PredictionEventStore store, NodeIdentity identity) {
        try {
            PredictionEventStore current = new PredictionEventStore(store.rootDirectory(), store.projectId());
            if (!current.taskCompletionProjection().combines().isEmpty()) {
                try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
                    if (lock.isHeld()) {
                        closeCombines(lock, controlRoot, new PredictionEventStore(store.rootDirectory(), store.projectId()), identity);
                    }
                }
            }
            List<PredictionEvent> events = current.events();
            Map<String, String> heads = new TreeMap<>(MainMeeting.lastHeads(events));
            heads.remove(identity.nodeId());
            if (heads.isEmpty()) {
                return;
            }
            String own = GitProcessRunner.run(controlRoot, "rev-parse", "HEAD");
            Map<String, String> diverged = new TreeMap<>();
            for (Map.Entry<String, String> last : heads.entrySet()) {
                if (!hasCommit(controlRoot, last.getValue())) {
                    continue;
                }
                MainMeeting.Relation relation = MainMeeting.relate(controlRoot, own, last.getValue());
                if (relation == MainMeeting.Relation.BEHIND) {
                    return;
                }
                if (relation == MainMeeting.Relation.DIVERGED) {
                    diverged.put(last.getKey(), last.getValue());
                }
            }
            Map<String, String> inTheWay = new TreeMap<>();
            for (Map.Entry<String, String> copy : diverged.entrySet()) {
                boolean heldByAnother = false;
                for (String other : diverged.values()) {
                    heldByAnother |= !other.equals(copy.getValue()) && GitProcessRunner.isAncestor(controlRoot, copy.getValue(), other);
                }
                if (!heldByAnother) {
                    inTheWay.put(copy.getKey(), copy.getValue());
                }
            }
            if (inTheWay.isEmpty() || inTheWay.keySet().stream().anyMatch(writer -> writer.compareTo(identity.nodeId()) > 0)) {
                return;
            }
            String other = inTheWay.values().iterator().next();
            String remergeId = remergeId(other, own);
            if (!STOPPED.contains(remergeId)) {
                remerge(controlRoot, current, identity, events, remergeId, own, other);
            }
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.WARNING, "The re-merge could not run; the next pass tries again.", failure);
        }
    }

    /**
     * Builds the re-merge once, then on every pass records what is not on the log yet and lands it when its reviews
     * allow. Each step can be repeated after a crash: the result is kept under its ref before anything is written, the
     * lanes are read back from the result's merge commits, an entry is written only when it is missing, and the
     * advance is written before the branch moves. An advance Git cannot merge opens a combine lane, and the build
     * waits for its result (designer item 22).
     */
    private void remerge(Path controlRoot, PredictionEventStore store, NodeIdentity identity, List<PredictionEvent> events,
            String remergeId, String own, String other) throws IOException, GeneralSecurityException {
        List<MainMeeting.Advance> missing = new ArrayList<>();
        for (MainMeeting.Advance advance : MainMeeting.advancesBy(events, identity.nodeId())) {
            if (!GitProcessRunner.isAncestor(controlRoot, advance.to(), other)) {
                missing.add(advance);
            }
        }
        String result = GitProcessRunner.runOptional(controlRoot, "rev-parse", "--verify", "-q", RESULT_PREFIX + remergeId + "^{commit}");
        if (result.isEmpty()) {
            Build built = build(controlRoot, store, remergeId, own, other, missing);
            if (built.conflicted() != null) {
                openCombine(controlRoot, store, identity, remergeId, own, other, built.conflicted(), built.base());
                return;
            }
            if (built.result() == null) {
                return;
            }
            result = built.result();
        }
        List<Lane> lanes = lanes(controlRoot, store, remergeId, other, result, missing);
        if (lanes == null) {
            return;
        }
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("the append lock is not held");
            }
            PredictionEventStore current = new PredictionEventStore(store.rootDirectory(), store.projectId());
            record(controlRoot, current, identity, remergeId, own, other, result, lanes);
            land(controlRoot, current, identity, remergeId, other, result, lanes);
        }
    }

    /**
     * What one build reached: the result, or the advance Git could not merge and the commit it was merged onto, or
     * neither when the build stopped or waits for a combine lane.
     */
    private record Build(String result, MainMeeting.Advance conflicted, String base) {

        static final Build NONE = new Build(null, null, null);
    }

    /**
     * Makes the re-merge's commits in a worktree and keeps the result under its ref. It starts from the other head, or
     * from the base of the last combine that has a result, which holds every step before it, and ends with the other head if it is not in yet. An advance a combine
     * lane combined is merged with the combine's tree.
     */
    private Build build(Path controlRoot, PredictionEventStore store, String remergeId, String own, String other,
            List<MainMeeting.Advance> missing) throws IOException {
        Map<String, CombinePayload> combines = combinesWithResult(controlRoot, store, missing);
        String start = missing.stream().map(advance -> combines.get(advance.to())).filter(Objects::nonNull).reduce((older, newer) -> newer)
                .map(CombinePayload::base).orElse(other);
        Path worktree = ExternalWorktrees.root(controlRoot, "remerge").resolve(remergeId).toAbsolutePath().normalize();
        // Left by a pass that died while building: its commits are named by nothing, so it is built again.
        ExternalWorktrees.removeBestEffort(worktree);
        Files.createDirectories(worktree.getParent());
        GitProcessRunner.run(controlRoot, "worktree", "add", "--detach", worktree.toString(), start);
        try {
            GitProcessRunner.run(worktree, "config", "user.name", "Synesis Integrator");
            GitProcessRunner.run(worktree, "config", "user.email", "integrator@synesis.org");
            for (MainMeeting.Advance advance : missing) {
                if (GitProcessRunner.isAncestor(worktree, advance.to(), "HEAD")) {
                    continue;
                }
                String base = GitProcessRunner.run(worktree, "rev-parse", "HEAD");
                CombinePayload combine = combines.get(advance.to());
                if (combine != null && combine.base().equals(base)) {
                    TaskSnapshotRecord combined = combined(controlRoot, store, combine).orElseThrow();
                    String tree = GitProcessRunner.run(worktree, "rev-parse", combined.commitSha() + "^{tree}");
                    String merged = GitProcessRunner.run(worktree, "commit-tree", tree, "-p", base, "-p", advance.to(), "-m",
                            "Synesis combine of " + String.join(", ", advance.snapshotIds()));
                    GitProcessRunner.run(worktree, "reset", "-q", "--hard", merged);
                    continue;
                }
                if (!merge(worktree, advance.to(), "Synesis re-merge of " + String.join(", ", advance.snapshotIds()))) {
                    return new Build(null, advance, base);
                }
            }
            if (!GitProcessRunner.isAncestor(worktree, own, "HEAD") && !merge(worktree, own, "Synesis re-merge of this copy of main")) {
                stop(remergeId, "Git could not merge this copy's old head " + own);
                return Build.NONE;
            }
            // A build that started from a combine's base lacks what the other copy took since: its result is merged with it.
            if (!GitProcessRunner.isAncestor(worktree, other, "HEAD") && !merge(worktree, other, "Synesis re-merge onto the other copy of main")) {
                stop(remergeId, "Git could not merge the other copy's head " + other + " into the combined result");
                return Build.NONE;
            }
            String result = GitProcessRunner.run(worktree, "rev-parse", "HEAD");
            if (!validates(controlRoot, worktree, remergeId)) {
                return Build.NONE;
            }
            GitProcessRunner.run(controlRoot, "update-ref", RESULT_PREFIX + remergeId, result);
            return new Build(result, null, null);
        } finally {
            ExternalWorktrees.removeBestEffort(worktree);
        }
    }

    /**
     * Opens the combine lane for an advance Git could not merge: keeps the commit it was merged onto under its ref,
     * creates the combine's own work group, and writes {@code COMBINE_REQUIRED}, each once.
     */
    private static void openCombine(Path controlRoot, PredictionEventStore store, NodeIdentity identity, String remergeId, String own,
            String other, MainMeeting.Advance advance, String base) throws IOException, GeneralSecurityException {
        CombinePayload combine = new CombinePayload("cb-" + digest(advance.to()), remergeId, other, own, base, advance.to(), advance.snapshotIds());
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("the append lock is not held");
            }
            PredictionEventStore current = new PredictionEventStore(store.rootDirectory(), store.projectId());
            if (current.taskCompletionProjection().combines().stream().anyMatch(known -> known.theirs().equals(advance.to()))) {
                return;
            }
            GitProcessRunner.run(controlRoot, "update-ref", BASE_PREFIX + combine.combineId(), base);
            if (current.workGroupProjection().group(combine.workGroupId()).isEmpty()) {
                WorkGroup group = new WorkGroup(combine.workGroupId(), current.projectId(),
                        "Combine " + String.join(", ", advance.snapshotIds()) + " with the other copy of main",
                        "One change that holds both versions: the other copy of main and the work of " + String.join(", ", advance.snapshotIds()),
                        1, WorkGroup.Status.ACTIVE, ReviewPolicy.none());
                current.append(group.workGroupId(), PredictionEventType.WORK_GROUP_CREATED, identity.nodeId(),
                        CollaborationCodec.encodeWorkGroup(group), identity);
            }
            current.append(nameUuid("combine:" + combine.combineId()), PredictionEventType.COMBINE_REQUIRED, identity.nodeId(),
                    combine.encode(), identity);
        }
        LOG.log(System.Logger.Level.WARNING, "Re-merge {0}: Git could not merge {1} onto {2}; combine lane {3} is open for an agent other than its authors.",
                remergeId, advance.to(), base, combine.combineId());
    }

    /**
     * The combine whose result is here, of each advance that has one, by the advance's head. A combine is about the
     * advance whose merge Git cannot make, not about the other copy's head or this copy's: both move while it waits (the
     * other copy goes on integrating, this copy takes an ordinary advance), and a re-merge built for the new heads must
     * use the combine again and not open another (s5-run18: two combines for one snapshot after this copy moved; s5-run19:
     * three after the other copy moved). Combines logged under older IDs are found the same way, by the advance. An advance
     * whose combine has no result is merged by Git against the newest heads: if the other copy took an equivalent change
     * meanwhile, the conflict is gone.
     */
    private static Map<String, CombinePayload> combinesWithResult(Path controlRoot, PredictionEventStore store, List<MainMeeting.Advance> missing) {
        Set<String> heads = missing.stream().map(MainMeeting.Advance::to).collect(Collectors.toSet());
        Map<String, CombinePayload> chosen = new java.util.HashMap<>();
        for (CombinePayload combine : store.taskCompletionProjection().combines()) {
            if (heads.contains(combine.theirs()) && combined(controlRoot, store, combine).isPresent()) {
                chosen.putIfAbsent(combine.theirs(), combine);
            }
        }
        return chosen;
    }

    /**
     * The combine lane's result for a combine: its task's latest snapshot made on the combine's base and not refused,
     * once its commit is here.
     */
    private static Optional<TaskSnapshotRecord> combined(Path controlRoot, PredictionEventStore store, CombinePayload combine) {
        var completion = store.taskCompletionProjection();
        List<TaskSnapshotRecord> made = completion.allSnapshots().stream()
                .filter(snapshot -> snapshot.taskId().equals(combine.taskId()) && snapshot.baseCommit().equals(combine.base()))
                .filter(snapshot -> !completion.snapshotState(snapshot.snapshotId()).map(TaskCompletionState::refused).orElse(false))
                .toList();
        return made.isEmpty() || !hasCommit(controlRoot, made.getLast().commitSha()) ? Optional.empty() : Optional.of(made.getLast());
    }

    /**
     * Records the combine lanes of this computer's agents integrated and releases them, once the re-merge that used
     * them is on this copy's {@code main}: both the base and the advance they combined are in it. A combine of this
     * computer's lanes that has no result and no conflict left, because no other copy diverges from this one any more
     * (the other side took an equivalent change and Git merged it), has its work group cancelled, so a lane in it ends
     * (item 33). The caller holds the append lock.
     */
    private void closeCombines(ProjectAppendLock lock, Path controlRoot, PredictionEventStore store, NodeIdentity identity)
            throws Exception {
        for (CombinePayload combine : store.taskCompletionProjection().combines()) {
            Optional<TaskSnapshotRecord> combined = combined(controlRoot, store, combine);
            if (combined.isEmpty()) {
                cancelWhenResolved(controlRoot, store, identity, combine);
                continue;
            }
            if (store.taskCompletionProjection().snapshotState(combined.get().snapshotId()).orElse(null) == TaskCompletionState.INTEGRATED
                    || !IntegrationOrchestrationService.ownLane(store, identity, combined.get())) {
                continue;
            }
            if (!GitProcessRunner.isAncestor(controlRoot, combine.base(), "HEAD") || !GitProcessRunner.isAncestor(controlRoot, combine.theirs(), "HEAD")) {
                releasePublishedLane(lock, controlRoot, store, identity, combined.get());
                continue;
            }
            ControlBranchAdvancementService.recordIntegrated(combined.get(), store, identity);
            finisher.finish(lock, controlRoot, List.of(combined.get()), new PredictionEventStore(store.rootDirectory(), store.projectId()), identity);
        }
    }

    /**
     * Releases the lane of a combine that has published its result, and completes its session, so its agent is free. The
     * result waits for the re-merge, which lands only when every combine has one; an agent that kept its lane until then
     * could not take the other combine, which may be one that only it may take (s5-run24, ADR-0098 step 5 item 39). The
     * combine is not offered again: it has a result ({@code CombineLaneJoin#waiting}). The caller holds the append lock.
     */
    private void releasePublishedLane(ProjectAppendLock lock, Path controlRoot, PredictionEventStore store, NodeIdentity identity, TaskSnapshotRecord result)
            throws Exception {
        WorkIntent lane = store.collaborationProjection().intent(result.provenance().laneIdentity()).orElse(null);
        if (lane == null || !new org.synesis.coordination.application.WorkIntentService(store, identity).releaseCombineResult(lock, result)) {
            return;
        }
        bindingService.completeBySessionId(projectService.locate(controlRoot), lane.provider(), result.providerSessionId(), lock);
    }

    /**
     * Cancels the work group of a combine this computer opened whose conflict is gone: every other copy of {@code main}
     * this computer knows is in this copy or ahead of it, so nothing is left to combine. A copy whose head is not here yet
     * is not judged, so the combine stays open. The caller holds the append lock.
     */
    private static void cancelWhenResolved(Path controlRoot, PredictionEventStore store, NodeIdentity identity, CombinePayload combine)
            throws IOException, GeneralSecurityException {
        var group = store.workGroupProjection().group(combine.workGroupId()).filter(known -> known.status() == WorkGroup.Status.ACTIVE);
        boolean own = combine.snapshotIds().stream().map(id -> store.taskCompletionProjection().findSnapshotById(id))
                .allMatch(snapshot -> snapshot.filter(known -> IntegrationOrchestrationService.ownLane(store, identity, known)).isPresent());
        if (group.isEmpty() || !own || MainMeeting.lastHeads(store.events()).keySet().stream().allMatch(identity.nodeId()::equals)) {
            return;
        }
        Map<String, String> heads = new TreeMap<>(MainMeeting.lastHeads(store.events()));
        heads.remove(identity.nodeId());
        String head = GitProcessRunner.run(controlRoot, "rev-parse", "HEAD");
        for (String other : heads.values()) {
            if (!hasCommit(controlRoot, other) || MainMeeting.relate(controlRoot, head, other) == MainMeeting.Relation.DIVERGED) {
                return;
            }
        }
        WorkGroup closed = group.get();
        store.append(closed.workGroupId(), PredictionEventType.WORK_GROUP_STATUS_CHANGED, identity.nodeId(), CollaborationCodec.encodeWorkGroup(
                new WorkGroup(closed.workGroupId(), closed.projectId(), closed.goal(), closed.acceptance(), closed.version() + 1, WorkGroup.Status.CANCELLED,
                        closed.reviewPolicy())), identity);
        LOG.log(System.Logger.Level.INFO, "Combine {0} is cancelled: no other copy of main diverges from this one any more.", combine.combineId());
    }

    /**
     * Reads the re-merge's lanes back from its result: the merge commit on the result's first-parent line whose other
     * parent is an advance's head carries that advance's lanes.
     *
     * @return the lanes, or {@code null} when an advance names a snapshot this computer has not seen
     */
    private static List<Lane> lanes(Path controlRoot, PredictionEventStore store, String remergeId, String other, String result,
            List<MainMeeting.Advance> missing) throws IOException {
        Map<String, String[]> mergeOf = new java.util.HashMap<>();
        for (String line : GitProcessRunner.run(controlRoot, "rev-list", "--first-parent", "--merges", "--parents", other + ".." + result).lines().toList()) {
            String[] ids = line.trim().split("\\s+");
            if (ids.length >= 3) {
                mergeOf.put(ids[2], ids);
            }
        }
        // One lane per original snapshot; the later merge commit holds the earlier one's work too.
        Map<String, Lane> lanes = new java.util.LinkedHashMap<>();
        for (MainMeeting.Advance advance : missing) {
            String[] merge = mergeOf.get(advance.to());
            if (merge == null) {
                continue;
            }
            for (String snapshotId : advance.snapshotIds()) {
                Optional<TaskSnapshotRecord> original = laneSnapshot(store, snapshotId);
                if (original.isEmpty()) {
                    stop(remergeId, "its advance names snapshot " + snapshotId + ", which this computer has not seen");
                    return null;
                }
                lanes.put(original.get().snapshotId(), new Lane(original.get(), merge[1], merge[0]));
            }
        }
        return List.copyOf(lanes.values());
    }

    /**
     * The snapshot a lane's work was integrated as. An advance this computer's earlier re-merge made names re-merge
     * snapshots, which are never integrated themselves; a re-merge snapshot of one of them is refused by the projection
     * ({@code REMERGE_SNAPSHOT_INVALID}), on every pass, so the copies never met. The lane is the one whose snapshot the
     * re-merge snapshot was made from.
     */
    private static Optional<TaskSnapshotRecord> laneSnapshot(PredictionEventStore store, String snapshotId) {
        var completion = store.taskCompletionProjection();
        Optional<TaskSnapshotRecord> snapshot = completion.findSnapshotById(snapshotId);
        while (snapshot.isPresent() && completion.remergeOf(snapshot.get().snapshotIdentity()).isPresent()) {
            snapshot = completion.findSnapshotById(completion.remergeOf(snapshot.get().snapshotIdentity()).orElseThrow().originalSnapshotId());
        }
        return snapshot;
    }

    /** One merge commit; a merge Git cannot make is undone, leaving the worktree at the base. */
    private static boolean merge(Path worktree, String commit, String message) throws IOException {
        try {
            GitProcessRunner.run(worktree, "merge", "--no-ff", "-m", message, commit);
            return true;
        } catch (IOException conflict) {
            GitProcessRunner.runOptional(worktree, "merge", "--abort");
            return false;
        }
    }

    private boolean validates(Path controlRoot, Path worktree, String remergeId) {
        ProjectCommandSpec command;
        ExecutionResult validation;
        try {
            command = projectService.locate(controlRoot).validation();
            if (command == null) {
                return true;
            }
            validation = processExecutor.execute(ExecutionRequest.from(command, worktree, controlRoot));
        } catch (Exception failure) {
            stop(remergeId, "the validation command could not run: " + failure.getMessage());
            return false;
        }
        if (validation.outcome() != ProjectProcessExecutor.Outcome.COMPLETED) {
            stop(remergeId, "the validation command ended as " + validation.outcome().value());
            return false;
        }
        return true;
    }

    /**
     * Writes what the log does not hold yet of the re-merge: a snapshot for each lane that needed review, and a grant
     * on it to its original reviewer. The caller holds the append lock. A lane that needed no review gets neither.
     */
    private static void record(Path controlRoot, PredictionEventStore store, NodeIdentity identity, String remergeId,
            String own, String other, String result, List<Lane> lanes) throws IOException, GeneralSecurityException {
        Set<String> carried = lanes.stream().map(lane -> lane.original().snapshotId()).collect(Collectors.toSet());
        for (Lane lane : lanes) {
            if (!lane.original().reviewRequired()) {
                continue;
            }
            RemergeSnapshotPayload remerge = remergeSnapshot(remergeId, own, other, result, lane);
            String snapshotId = remerge.snapshot().snapshotId();
            if (store.taskCompletionProjection().findSnapshotById(snapshotId).isEmpty()) {
                GitProcessRunner.run(controlRoot, "update-ref", SnapshotRefs.snapshotRef(snapshotId), remerge.snapshot().commitSha());
                store.append(nameUuid("remerge:" + snapshotId), PredictionEventType.REMERGE_SNAPSHOT_CREATED, identity.nodeId(),
                        remerge.encode(), identity);
                LOG.log(System.Logger.Level.INFO, "Re-merge {0} onto {1}: snapshot {2} waits for its review.", remergeId, other, snapshotId);
            }
            offerReview(controlRoot, store, identity, remerge, carried);
        }
    }

    private static RemergeSnapshotPayload remergeSnapshot(String remergeId, String own, String other, String result, Lane lane) {
        TaskSnapshotRecord original = lane.original();
        String snapshotId = "rms-" + digest(remergeId + "\n" + original.snapshotId());
        SnapshotProvenance was = original.provenance();
        SnapshotProvenance provenance = new SnapshotProvenance(was.workGroupId(), was.laneId(), was.authorityLineageId(), was.participant(),
                was.bindingIdentity(), was.claimEpoch(), was.contractRevisions(), was.handoffLineage(), was.claimSelectors(),
                SnapshotRefs.snapshotRef(snapshotId), was.integrityEvidence(), was.artifactManifestDigest());
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(original.taskId(), snapshotId, original.nodeId(), original.supervisorId(),
                original.workerId(), original.providerSessionId(), lane.base(), lane.merged(), original.changedPaths(),
                original.capabilityDependencies(), original.summary(), provenance, true);
        return new RemergeSnapshotPayload(remergeId, original.snapshotId(), other, own, result, snapshot);
    }

    /**
     * Offers the re-merge snapshot for review with a grant bound to it, unless a grant on it is on the log and not
     * released. The first goes to the reviewer who accepted the original snapshot. Once every grant on it was released
     * because its reviewer could not be reached (designer item 2, {@link AwayLanes#releaseUnreachableReviews}), the next
     * goes to {@link #anotherReviewer}; with none, the re-merge waits for a later pass.
     */
    private static void offerReview(Path controlRoot, PredictionEventStore store, NodeIdentity identity, RemergeSnapshotPayload remerge,
            Set<String> carried) throws IOException, GeneralSecurityException {
        String snapshotId = remerge.snapshot().snapshotId();
        var workGroups = store.workGroupProjection();
        List<LaneGrant> offered = workGroups.grants().stream().filter(grant -> snapshotId.equals(grant.snapshotId())).toList();
        if (offered.stream().anyMatch(grant -> !workGroups.grantRevoked(grant.identity()))) {
            return;
        }
        TaskSnapshotRecord original = store.taskCompletionProjection().findSnapshotById(remerge.originalSnapshotId()).orElseThrow();
        String producer = original.provenance().participant();
        Optional<String> accepted = store.taskCompletionProjection().verdicts(original.snapshotIdentity()).entrySet().stream()
                .filter(verdict -> "ACCEPTED".equals(verdict.getValue()) && !verdict.getKey().equals(producer)).map(Map.Entry::getKey).sorted().findFirst();
        // Only the reviewer's own computer can tell that its agent is gone, and it may have no run left to say so (a
        // connected computer whose agents all ended): a review owed to another computer's agent goes to a live agent
        // here when there is one, so it is never held for ever by an agent nobody can wake.
        boolean acceptedElsewhere = accepted.flatMap(handle -> store.collaborationProjection().introducingNode(new ParticipantId(handle)))
                .map(node -> !node.equals(identity.nodeId())).orElse(false);
        Optional<String> reviewer = offered.isEmpty()
                ? (acceptedElsewhere ? anotherReviewer(controlRoot, store, original, offered, carried).or(() -> accepted) : accepted)
                : anotherReviewer(controlRoot, store, original, offered, carried);
        if (reviewer.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, offered.isEmpty()
                    ? "Re-merge snapshot {0} has no reviewer to offer it to: no one other than its author accepted {1}."
                    : "Re-merge snapshot {0} waits for another reviewer: no agent of this computer that may review {1} is live.",
                    snapshotId, original.snapshotId());
            return;
        }
        // The first grant keeps the ID it always had; a later one is named by its reviewer, so each is written once.
        UUID grantId = nameUuid(offered.isEmpty() ? "remerge-grant:" + snapshotId : "remerge-grant:" + snapshotId + "\n" + reviewer.get());
        SnapshotProvenance provenance = remerge.snapshot().provenance();
        LaneGrant grant = new LaneGrant(grantId, provenance.workGroupId(), provenance.laneId(), reviewer.get(), provenance.claimEpoch(), true, snapshotId);
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(), CollaborationCodec.encodeLaneGrant(grant), identity);
    }

    /**
     * Another reviewer for a re-merge whose reviewers could not be reached (designer item 2): a live session of this
     * computer ({@link AwayLanes.Agents}), whether or not it has a lane on the log. The session a released reviewer's
     * connection went on to is chosen first, since it is the same agent and may review what that agent was owed; then the
     * lowest handle, so a repeated pass chooses the same one. Not chosen: a session released from this snapshot before; the
     * author, and every session of the author's agent, since a fresh session must not review its predecessor's work; one
     * that authored in the lineage; and the holder of an open lane of a combine of this re-merge, whose lane waits for the
     * re-merge to land and is shown no review before then. With the leases or the recorded replacements unreadable, no
     * one is chosen.
     */
    private static Optional<String> anotherReviewer(Path controlRoot, PredictionEventStore store, TaskSnapshotRecord original,
            List<LaneGrant> released, Set<String> carried) {
        Optional<AwayLanes.Agents> known = AwayLanes.Agents.of(controlRoot);
        if (known.isEmpty()) {
            return Optional.empty();
        }
        AwayLanes.Agents agents = known.get();
        Set<String> releasedFrom = released.stream().map(LaneGrant::targetParticipant).collect(Collectors.toSet());
        String author = original.provenance().participant();
        var collaboration = store.collaborationProjection();
        Set<String> waiting = store.taskCompletionProjection().combines().stream()
                .filter(combine -> combine.snapshotIds().stream().anyMatch(carried::contains))
                .flatMap(combine -> collaboration.activeIntents().stream().filter(intent -> intent.workGroupId().equals(combine.workGroupId())))
                .map(intent -> intent.participant()).collect(Collectors.toSet());
        return agents.live().stream()
                .filter(handle -> !releasedFrom.contains(handle) && !agents.sameAgent(handle, author))
                .filter(handle -> collaboration.participantState(handle).map(state -> state == Participant.State.ACTIVE).orElse(true))
                .filter(handle -> agents.sessionsUpTo(handle).stream()
                        .noneMatch(session -> ReviewAdmissionRules.authoredInLineage(store, new ParticipantId(session), original.provenance().lineageIdentity())))
                // The holder of an open combine lane of this re-merge is the last resort: it is shown the grant while its lane waits, and
                // with two agents on a computer it may be the only one that did not write the lane under review.
                .min(Comparator.comparing((String handle) -> waiting.stream().anyMatch(holder -> agents.sameAgent(handle, holder)))
                        .thenComparing((String handle) -> releasedFrom.stream().noneMatch(from -> agents.sameAgent(handle, from))).thenComparing(Comparator.naturalOrder()));
    }

    /**
     * Lands the re-merge once every one of its re-merge snapshots is accepted. Its ID binds it to this copy's head and
     * the other head, so a re-merge reached here was built on the basis that holds now. The caller holds the append
     * lock.
     */
    private static void land(Path controlRoot, PredictionEventStore store, NodeIdentity identity, String remergeId, String other,
            String result, List<Lane> lanes) throws IOException, GeneralSecurityException {
        var completion = store.taskCompletionProjection();
        // Every lane of the re-merged advances: its re-merge snapshot where review was required, its original otherwise.
        List<String> named = new ArrayList<>();
        for (Lane lane : lanes) {
            if (!lane.original().reviewRequired()) {
                named.add(lane.original().snapshotId());
                continue;
            }
            String snapshotId = "rms-" + digest(remergeId + "\n" + lane.original().snapshotId());
            TaskCompletionState state = completion.snapshotState(snapshotId).orElse(TaskCompletionState.REVIEW_PENDING);
            if (state == TaskCompletionState.REVIEW_REJECTED || state == TaskCompletionState.REVIEW_DISPUTED) {
                stop(remergeId, "re-merge snapshot " + snapshotId + " was " + state.name());
                return;
            }
            if (state != TaskCompletionState.REVIEW_ACCEPTED) {
                return;
            }
            named.add(snapshotId);
        }
        advance(controlRoot, store, identity, remergeId, other, result, named.stream().distinct().toList());
    }

    /**
     * Records the re-merge as an ordinary advance from the other head, so the other computer's audit follows it back
     * to its own head, and then moves this copy's branch to the result. The entry comes first: a crash between the two
     * leaves an advance this computer's next pass finds recorded and only moves the branch for.
     */
    private static void advance(Path controlRoot, PredictionEventStore store, NodeIdentity identity, String remergeId, String other,
            String result, List<String> snapshotIds) throws IOException, GeneralSecurityException {
        try {
            List<String> foreign = ControlBranchAdvancementService.foreignChanges(controlRoot);
            if (!foreign.isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "Re-merge {0} waits: the control checkout holds changes Synesis did not make.", remergeId);
                return;
            }
        } catch (Exception unreadable) {
            throw new IOException("the control checkout could not be read", unreadable);
        }
        if (!result.equals(store.taskCompletionProjection().lastControlHeadAdvanced(identity.nodeId()))) {
            IntegrationAttemptPayload advanced = new IntegrationAttemptPayload(remergeId, store.projectId(), snapshotIds, other, result,
                    "advanced", "");
            store.append(nameUuid("remerge-advanced:" + remergeId), PredictionEventType.CONTROL_BRANCH_ADVANCED, identity.nodeId(),
                    advanced.encode(), identity);
        }
        GitProcessRunner.run(controlRoot, "merge", "--ff-only", result);
        GitProcessRunner.run(controlRoot, "update-ref", "-d", RESULT_PREFIX + remergeId);
        LOG.log(System.Logger.Level.INFO, "Re-merge {0} landed: main moved to {1}.", remergeId, result);
    }

    private static void stop(String remergeId, String why) {
        if (STOPPED.add(remergeId)) {
            LOG.log(System.Logger.Level.WARNING, "Re-merge {0} stopped: {1}. It is tried again once either copy of main moves.",
                    remergeId, why);
        }
    }

    private static boolean hasCommit(Path repository, String commit) {
        try {
            GitProcessRunner.run(repository, "cat-file", "-e", commit + "^{commit}");
            return true;
        } catch (IOException absent) {
            return false;
        }
    }

    /** The re-merge onto one other head of this copy's head: the same pair always names the same re-merge. */
    static String remergeId(String other, String own) {
        return "rm-" + digest(other + "\n" + own);
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private static UUID nameUuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
