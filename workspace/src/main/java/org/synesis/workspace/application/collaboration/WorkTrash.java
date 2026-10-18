package org.synesis.workspace.application.collaboration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.application.TrashService;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.domain.trash.TrashEntries;
import org.synesis.coordination.domain.trash.TrashEntries.WorkTrashed;
import org.synesis.coordination.domain.trash.TrashProjection;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.task.SnapshotRefs;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Keeps work that went to the trash, and is the one place a trash ref is deleted (ADR-0098 step 7, designer items 12
 * to 16; the "nothing taken back" fitness function). Work is kept as a commit under
 * {@code refs/synesis/trash/<lane>/<epoch>} on the computer of the lane's agent, the ref first and then the entry, so
 * a crash between them leaves a ref the next sweep names.
 *
 * <p>The sweep runs in every integration pass, under its lock, so it needs no agent to call in. It trashes, on this
 * computer, and once each:</p>
 * <ul>
 *   <li>the worktree of a lane of this computer that another computer took over ({@code TAKEN_OVER});</li>
 *   <li>the newest snapshot of a lane of this computer, not integrated, in a work group that is no longer active: one
 *       still disputed or rejected was given up ({@code DISPUTE_GIVEN_UP}), any other came after the goal was met
 *       ({@code LATE_FOR_MET_GOAL});</li>
 * </ul>
 * <p>and it deletes this computer's copy of every emptied ref.</p>
 */
public final class WorkTrash {

    private static final System.Logger LOG = System.getLogger(WorkTrash.class.getName());

    private WorkTrash() {
    }

    /**
     * Trashes what this computer must and deletes the refs of emptied work. Nothing here fails the pass: what cannot
     * be done is logged and left to the next pass.
     *
     * @param location this project
     * @param store    this computer's logs
     * @param identity this computer
     */
    public static void sweep(ProjectLocation location, PredictionEventStore store, NodeIdentity identity) {
        try {
            String thisNode = identity.nodeId();
            TrashService trash = new TrashService(store, identity);
            // Whether or not the taker's lane is still open: a taker that finished before this computer came back.
            for (Map.Entry<WorkIntent, WorkIntent> takeover : store.collaborationProjection().takenOver()) {
                WorkIntent taken = takeover.getKey();
                if (introducedBy(store, taken, thisNode) && store.trashProjection().item(taken.intentId(), taken.version()).isEmpty()) {
                    keepWorktree(location, store, trash, taken);
                }
            }
            for (TaskSnapshotRecord snapshot : givenUpOrLate(store, thisNode)) {
                boolean cancelled = store.workGroupProjection().group(snapshot.provenance().workGroupId())
                        .map(group -> group.status() == WorkGroup.Status.CANCELLED).orElse(false);
                String reason = snapshotState(store, snapshot) == TaskCompletionState.REVIEW_DISPUTED
                        || snapshotState(store, snapshot) == TaskCompletionState.REVIEW_REJECTED ? TrashEntries.DISPUTE_GIVEN_UP
                        : cancelled ? TrashEntries.GROUP_CANCELLED : TrashEntries.LATE_FOR_MET_GOAL;
                keep(location.root(), trash, snapshot.provenance().laneId(), snapshot.provenance().claimEpoch(), snapshot.taskId(),
                        snapshot.provenance().workGroupId(), snapshot.commitSha(), reason);
            }
            for (TrashProjection.Item item : store.trashProjection().items()) {
                if (item.emptied()) {
                    deleteRef(location.root(), item.trashed().laneId(), item.trashed().epoch());
                }
            }
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.WARNING, "trash sweep failed; the next pass tries again", failure);
        }
    }

    /**
     * Brings trashed work back in a work group of its own; a lane announced in it starts with the work applied.
     *
     * @param location this project
     * @param identity this computer
     * @param laneId   the trashed lane
     * @param epoch    its epoch
     * @return the new work group
     * @throws Exception when the work is not in the trash, was emptied, or the log cannot be written
     */
    public static UUID restore(ProjectLocation location, NodeIdentity identity, UUID laneId, long epoch) throws Exception {
        return new TrashService(location.openCoordinationStore(), identity).restore(laneId, epoch);
    }

    /**
     * Empties trashed work for good: the entry first, then this computer's ref, so a crash between them leaves a ref
     * the next sweep deletes. The only deletion of work there is.
     *
     * @param location this project
     * @param identity this computer
     * @param laneId   the trashed lane
     * @param epoch    its epoch
     * @throws Exception when the work is not in the trash, was emptied already, or the log cannot be written
     */
    public static void empty(ProjectLocation location, NodeIdentity identity, UUID laneId, long epoch) throws Exception {
        new TrashService(location.openCoordinationStore(), identity).empty(laneId, epoch);
        deleteRef(location.root(), laneId, epoch);
    }

    /**
     * Gives a lane announced in a restore's work group the work it brings back, before its entry is written.
     *
     * @param location    this project
     * @param store       this computer's logs
     * @param workGroupId the group the lane is announced in
     * @param worktree    the lane's worktree
     * @return whether the group restores work, which was applied
     * @throws IOException when the trashed commit is not on this computer or cannot be applied
     */
    public static boolean applyRestored(ProjectLocation location, PredictionEventStore store, UUID workGroupId, Path worktree) throws IOException {
        Optional<TrashProjection.Item> restoring = store.trashProjection().restoredInto(workGroupId);
        if (restoring.isEmpty()) {
            return false;
        }
        WorkTrashed trashed = restoring.get().trashed();
        if (restoring.get().emptied()) {
            throw new IOException("TRASH_EMPTIED");
        }
        if (GitProcessRunner.runResult(location.root(), "cat-file", "-e", trashed.commit() + "^{commit}").exitCode() != 0) {
            // Trashed on another computer: the entry names the commit, so it is fetched over Link like a snapshot.
            try {
                org.synesis.workspace.application.control.CodeExchange.ensureCommit(location.coordinationDirectory(), location.projectId(), location.root(),
                        trashed.commit());
            } catch (org.synesis.workspace.application.control.CodeRemote.Refused refused) {
                throw new IOException("TRASH_COMMIT_NOT_HERE: " + refused.getMessage(), refused);
            }
        }
        String base = store.collaborationProjection().lane(trashed.laneId()).map(WorkIntent::baseCommit)
                .orElseThrow(() -> new IOException("TRASH_LANE_NOT_FOUND"));
        new org.synesis.workspace.application.integration.IntegrationWorkspaceService()
                .materializeRestore(worktree, trashed.laneId() + "/" + trashed.epoch(), base, trashed.commit());
        return true;
    }

    /**
     * Takes the restored work back out of a worktree whose lane was refused.
     *
     * @param store       this computer's logs
     * @param workGroupId the restore's work group
     * @param worktree    the worktree
     * @throws IOException when Git fails
     */
    public static void undoRestored(PredictionEventStore store, UUID workGroupId, Path worktree) throws IOException {
        Optional<TrashProjection.Item> restoring = store.trashProjection().restoredInto(workGroupId);
        if (restoring.isPresent()) {
            WorkTrashed trashed = restoring.get().trashed();
            String base = store.collaborationProjection().lane(trashed.laneId()).map(WorkIntent::baseCommit).orElseThrow(() -> new IOException("TRASH_LANE_NOT_FOUND"));
            new org.synesis.workspace.application.integration.IntegrationWorkspaceService().undoRestore(worktree, base, trashed.commit());
        }
    }

    /**
     * Describes the trash as a person sees it: each piece of work, why it is there, where it was brought back, and
     * whether it was emptied.
     *
     * @param trash the trash as the log says
     * @return one map per piece of work, oldest first
     */
    public static List<Map<String, Object>> describe(TrashProjection trash) {
        List<Map<String, Object>> described = new ArrayList<>();
        for (TrashProjection.Item item : trash.items()) {
            WorkTrashed trashed = item.trashed();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("laneId", trashed.laneId().toString());
            entry.put("epoch", trashed.epoch());
            entry.put("taskId", trashed.taskId().toString());
            entry.put("workGroupId", trashed.workGroupId().toString());
            entry.put("commit", trashed.commit());
            entry.put("reason", trashed.reason());
            entry.put("trashedBy", item.writerNodeId());
            entry.put("restoredInto", item.restoredInto().stream().map(UUID::toString).toList());
            entry.put("emptied", item.emptied());
            described.add(entry);
        }
        return List.copyOf(described);
    }

    /** The newest snapshot of each lane of this computer that a work group no longer active left behind. */
    private static List<TaskSnapshotRecord> givenUpOrLate(PredictionEventStore store, String thisNode) {
        Map<UUID, TaskSnapshotRecord> newestByLineage = new LinkedHashMap<>();
        for (TaskSnapshotRecord snapshot : store.taskCompletionProjection().allSnapshots()) {
            newestByLineage.put(snapshot.provenance().authorityLineageId(), snapshot);
        }
        return newestByLineage.values().stream()
                .filter(snapshot -> store.collaborationProjection().introducingNode(new ParticipantId(snapshot.provenance().participant()))
                        .map(thisNode::equals).orElse(false))
                .filter(snapshot -> store.workGroupProjection().group(snapshot.provenance().workGroupId())
                        .map(group -> group.status() != WorkGroup.Status.ACTIVE).orElse(false))
                .filter(snapshot -> snapshotState(store, snapshot) != TaskCompletionState.INTEGRATED)
                .filter(snapshot -> store.trashProjection().item(snapshot.provenance().laneId(), snapshot.provenance().claimEpoch()).isEmpty())
                .toList();
    }

    private static TaskCompletionState snapshotState(PredictionEventStore store, TaskSnapshotRecord snapshot) {
        return store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE);
    }

    private static boolean introducedBy(PredictionEventStore store, WorkIntent lane, String node) {
        return store.collaborationProjection().introducingNode(lane.participantIdentity()).map(node::equals).orElse(false);
    }

    /**
     * Keeps the worktree of a lane taken over: everything in it, committed or not, on top of what it had committed.
     * With no worktree left, its newest snapshot is kept; with neither, there is nothing to keep.
     */
    private static void keepWorktree(ProjectLocation location, PredictionEventStore store, TrashService trash, WorkIntent lane) throws Exception {
        Optional<Path> worktree = worktreeOf(location, lane);
        String commit;
        if (worktree.isPresent()) {
            commit = commitEverything(worktree.get(), "Synesis trash: lane " + lane.intentId() + " epoch " + lane.version() + " (" + TrashEntries.TAKEN_OVER + ")");
        } else {
            Optional<TaskSnapshotRecord> newest = store.taskCompletionProjection().allSnapshots().stream()
                    .filter(snapshot -> snapshot.provenance().authorityLineageId().equals(lane.authorityLineageId())).reduce((first, second) -> second);
            if (newest.isEmpty()) {
                return;
            }
            commit = newest.get().commitSha();
        }
        keep(location.root(), trash, lane.intentId(), lane.version(), lane.taskId(), lane.workGroupId(), commit, TrashEntries.TAKEN_OVER);
    }

    /** The ref first, created only where absent (a ref left by a crash names the commit kept then), then the entry. */
    private static void keep(Path controlRoot, TrashService trash, UUID laneId, long epoch, UUID taskId, UUID workGroupId, String commit, String reason)
            throws Exception {
        String ref = SnapshotRefs.trashRef(laneId, epoch);
        String kept = GitProcessRunner.runOptional(controlRoot, "rev-parse", "--verify", "-q", ref + "^{commit}");
        if (kept.isBlank()) {
            GitProcessRunner.run(controlRoot, "update-ref", ref, commit, "0".repeat(commit.length()));
            kept = commit;
        }
        trash.trash(new WorkTrashed(laneId, epoch, taskId, workGroupId, kept, reason));
    }

    private static String commitEverything(Path worktree, String message) throws IOException {
        Path index = Files.createTempFile("synesis-trash-index", "");
        // Git refuses an empty file as an index; read-tree writes a new one at this path.
        Files.delete(index);
        try {
            GitProcessRunner.runWithIndex(worktree, index, "read-tree", "HEAD");
            GitProcessRunner.runWithIndex(worktree, index, "add", "-A", "--", ".");
            String tree = GitProcessRunner.runWithIndex(worktree, index, "write-tree");
            return GitProcessRunner.run(worktree, "commit-tree", tree, "-p", "HEAD", "-m", message);
        } finally {
            Files.deleteIfExists(index);
        }
    }

    private static Optional<Path> worktreeOf(ProjectLocation location, WorkIntent lane) {
        try {
            return new ProviderSessionBindingService().list(location, lane.provider()).stream()
                    .filter(binding -> WorkspaceCollaborationService.participantHandle(binding.sessionId()).equals(lane.participant()))
                    .map(ProviderSessionBindingService.Binding::worktreePath).filter(java.util.Objects::nonNull).map(Path::of)
                    .filter(Files::isDirectory).findFirst();
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }

    /** The one place a trash ref is deleted: when a person emptied it, here or on another computer. */
    private static void deleteRef(Path controlRoot, UUID laneId, long epoch) throws IOException {
        String ref = SnapshotRefs.trashRef(laneId, epoch);
        if (!GitProcessRunner.runOptional(controlRoot, "rev-parse", "--verify", "-q", ref).isBlank()) {
            GitProcessRunner.run(controlRoot, "update-ref", "-d", ref);
        }
    }
}
