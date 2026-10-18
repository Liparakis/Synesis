package org.synesis.workspace.application.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.RemergeSnapshotPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
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

    /**
     * Re-merges that could not be built or will not land, in this process: Git could not merge, the validation command
     * failed, or a review rejected it. Each is reported once and not tried again until the heads change; the combine
     * lane that would resolve them is NOT BUILT.
     */
    private static final Set<String> STOPPED = ConcurrentHashMap.newKeySet();

    private final ProjectApplicationService projectService;
    private final ProjectProcessExecutor processExecutor;

    /** One lane of one re-merged advance: the original snapshot and the merge commit that carries it now. */
    private record Lane(TaskSnapshotRecord original, String base, String merged) {
    }

    RemergeService(ProjectApplicationService projectService, ProjectProcessExecutor processExecutor) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.processExecutor = Objects.requireNonNull(processExecutor, "processExecutor");
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
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "The re-merge could not run; the next pass tries again.", failure);
        }
    }

    private void remerge(Path controlRoot, PredictionEventStore store, NodeIdentity identity, List<PredictionEvent> events,
            String remergeId, String own, String other) throws IOException, GeneralSecurityException {
        List<MainMeeting.Advance> missing = new ArrayList<>();
        for (MainMeeting.Advance advance : MainMeeting.advancesBy(events, identity.nodeId())) {
            if (!GitProcessRunner.isAncestor(controlRoot, advance.to(), other)) {
                missing.add(advance);
            }
        }
        List<RemergeSnapshotPayload> built = store.taskCompletionProjection().remerge(remergeId);
        if (built.isEmpty()) {
            build(controlRoot, store, identity, remergeId, own, other, missing);
        } else {
            land(controlRoot, store, identity, remergeId, built, other, missing);
        }
    }

    private void build(Path controlRoot, PredictionEventStore store, NodeIdentity identity, String remergeId, String own,
            String other, List<MainMeeting.Advance> missing) throws IOException, GeneralSecurityException {
        Path worktree = ExternalWorktrees.root(controlRoot, "remerge").resolve(remergeId).toAbsolutePath().normalize();
        // Left by a pass that died while building: its commits are named by nothing, so it is built again.
        ExternalWorktrees.removeBestEffort(worktree);
        Files.createDirectories(worktree.getParent());
        GitProcessRunner.run(controlRoot, "worktree", "add", "--detach", worktree.toString(), other);
        try {
            GitProcessRunner.run(worktree, "config", "user.name", "Synesis Integrator");
            GitProcessRunner.run(worktree, "config", "user.email", "integrator@synesis.org");
            List<Lane> lanes = new ArrayList<>();
            String base = other;
            for (MainMeeting.Advance advance : missing) {
                if (GitProcessRunner.isAncestor(worktree, advance.to(), "HEAD")) {
                    continue;
                }
                if (!merge(worktree, advance.to(), "Synesis re-merge of " + String.join(", ", advance.snapshotIds()), remergeId)) {
                    return;
                }
                String merged = GitProcessRunner.run(worktree, "rev-parse", "HEAD");
                for (String snapshotId : advance.snapshotIds()) {
                    Optional<TaskSnapshotRecord> original = store.taskCompletionProjection().findSnapshotById(snapshotId);
                    if (original.isEmpty()) {
                        stop(remergeId, "its advance names snapshot " + snapshotId + ", which this computer has not seen");
                        return;
                    }
                    lanes.add(new Lane(original.get(), base, merged));
                }
                base = merged;
            }
            if (!GitProcessRunner.isAncestor(worktree, own, "HEAD")
                    && !merge(worktree, own, "Synesis re-merge of this copy of main", remergeId)) {
                return;
            }
            String result = GitProcessRunner.run(worktree, "rev-parse", "HEAD");
            if (!validates(controlRoot, worktree, remergeId)) {
                return;
            }
            record(controlRoot, store, identity, remergeId, own, other, result, lanes);
        } finally {
            ExternalWorktrees.removeBestEffort(worktree);
        }
    }

    /** One merge commit; a merge Git cannot make stops the re-merge (the combine lane is NOT BUILT). */
    private static boolean merge(Path worktree, String commit, String message, String remergeId) throws IOException {
        try {
            GitProcessRunner.run(worktree, "merge", "--no-ff", "-m", message, commit);
            return true;
        } catch (IOException conflict) {
            stop(remergeId, "Git could not merge " + commit + ": " + conflict.getMessage());
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
     * Writes the re-merge: a snapshot and a grant to its original reviewer for each lane that needed review. One that
     * needed none lands at once.
     */
    private static void record(Path controlRoot, PredictionEventStore store, NodeIdentity identity, String remergeId,
            String own, String other, String result, List<Lane> lanes) throws IOException, GeneralSecurityException {
        GitProcessRunner.run(controlRoot, "update-ref", RESULT_PREFIX + remergeId, result);
        List<RemergeSnapshotPayload> reviewed = new ArrayList<>();
        for (Lane lane : lanes) {
            if (lane.original().reviewRequired()) {
                reviewed.add(remergeSnapshot(remergeId, own, other, result, lane));
            }
        }
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("the append lock is not held");
            }
            PredictionEventStore current = new PredictionEventStore(store.rootDirectory(), store.projectId());
            if (reviewed.isEmpty()) {
                advance(controlRoot, current, identity, remergeId, other, result,
                        lanes.stream().map(lane -> lane.original().snapshotId()).distinct().toList());
                return;
            }
            for (RemergeSnapshotPayload remerge : reviewed) {
                String snapshotId = remerge.snapshot().snapshotId();
                GitProcessRunner.run(controlRoot, "update-ref", SnapshotRefs.snapshotRef(snapshotId), remerge.snapshot().commitSha());
                current.append(nameUuid("remerge:" + snapshotId), PredictionEventType.REMERGE_SNAPSHOT_CREATED, identity.nodeId(),
                        remerge.encode(), identity);
            }
            for (RemergeSnapshotPayload remerge : reviewed) {
                offerReview(current, identity, remerge);
            }
        }
        LOG.log(System.Logger.Level.INFO, "Re-merge {0} built onto {1}; it lands once its {2} re-merge snapshot(s) are accepted.",
                remergeId, other, reviewed.size());
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
     * Offers the re-merge snapshot to the reviewer who accepted the original one, with a grant bound to it. Another
     * reviewer when that one cannot be reached (designer item 2) is NOT BUILT.
     */
    private static void offerReview(PredictionEventStore store, NodeIdentity identity, RemergeSnapshotPayload remerge)
            throws IOException, GeneralSecurityException {
        TaskSnapshotRecord original = store.taskCompletionProjection().findSnapshotById(remerge.originalSnapshotId()).orElseThrow();
        String producer = original.provenance().participant();
        Optional<String> reviewer = store.taskCompletionProjection().verdicts(original.snapshotIdentity()).entrySet().stream()
                .filter(verdict -> "ACCEPTED".equals(verdict.getValue()) && !verdict.getKey().equals(producer)).map(Map.Entry::getKey)
                .sorted().findFirst();
        if (reviewer.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Re-merge snapshot {0} has no reviewer to offer it to: no one other than its author accepted {1}.",
                    remerge.snapshot().snapshotId(), original.snapshotId());
            return;
        }
        SnapshotProvenance provenance = remerge.snapshot().provenance();
        LaneGrant grant = new LaneGrant(nameUuid("remerge-grant:" + remerge.snapshot().snapshotId()), provenance.workGroupId(),
                provenance.laneId(), reviewer.get(), provenance.claimEpoch(), true, remerge.snapshot().snapshotId());
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(), CollaborationCodec.encodeLaneGrant(grant), identity);
    }

    /**
     * Lands a built re-merge once every one of its snapshots is accepted. Its ID binds it to this copy's head and the
     * other head, so a re-merge reached here was built on the basis that holds now.
     */
    private static void land(Path controlRoot, PredictionEventStore store, NodeIdentity identity, String remergeId,
            List<RemergeSnapshotPayload> built, String other, List<MainMeeting.Advance> missing)
            throws IOException, GeneralSecurityException {
        var completion = store.taskCompletionProjection();
        for (RemergeSnapshotPayload remerge : built) {
            TaskCompletionState state = completion.snapshotState(remerge.snapshot().snapshotId()).orElse(TaskCompletionState.REVIEW_PENDING);
            if (state == TaskCompletionState.REVIEW_REJECTED || state == TaskCompletionState.REVIEW_DISPUTED) {
                stop(remergeId, "re-merge snapshot " + remerge.snapshot().snapshotId() + " was " + state.name());
                return;
            }
            if (state != TaskCompletionState.REVIEW_ACCEPTED) {
                return;
            }
        }
        // Every lane of the re-merged advances: its re-merge snapshot where review was required, its original otherwise.
        List<String> named = new ArrayList<>();
        for (MainMeeting.Advance advance : missing) {
            for (String snapshotId : advance.snapshotIds()) {
                Optional<RemergeSnapshotPayload> remerge = built.stream().filter(value -> value.originalSnapshotId().equals(snapshotId)).findFirst();
                boolean reviewRequired = completion.findSnapshotById(snapshotId).map(TaskSnapshotRecord::reviewRequired).orElse(true);
                if (remerge.isEmpty() && reviewRequired) {
                    stop(remergeId, "no re-merge snapshot was recorded for " + snapshotId + "; the build was interrupted");
                    return;
                }
                named.add(remerge.map(value -> value.snapshot().snapshotId()).orElse(snapshotId));
            }
        }
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("the append lock is not held");
            }
            advance(controlRoot, new PredictionEventStore(store.rootDirectory(), store.projectId()), identity, remergeId, other,
                    built.getFirst().result(), named.stream().distinct().toList());
        }
    }

    /**
     * Moves this copy's branch to the re-merge's result and records it as an ordinary advance from the other head, so
     * the other computer's audit follows it back to its own head.
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
        GitProcessRunner.run(controlRoot, "merge", "--ff-only", result);
        IntegrationAttemptPayload advanced = new IntegrationAttemptPayload(remergeId, store.projectId(), snapshotIds, other, result,
                "advanced", "");
        store.append(nameUuid("remerge-advanced:" + remergeId), PredictionEventType.CONTROL_BRANCH_ADVANCED, identity.nodeId(),
                advanced.encode(), identity);
        GitProcessRunner.run(controlRoot, "update-ref", "-d", RESULT_PREFIX + remergeId);
        LOG.log(System.Logger.Level.INFO, "Re-merge {0} landed: main moved to {1}.", remergeId, result);
    }

    private static void stop(String remergeId, String why) {
        if (STOPPED.add(remergeId)) {
            LOG.log(System.Logger.Level.WARNING, "Re-merge {0} stopped: {1}. Resolving it needs the combine lane, which is not built yet.",
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
