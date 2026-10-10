package org.synesis.workspace.application.task;

import static org.synesis.workspace.application.task.SnapshotGitCommands.runGitOutput;
import static org.synesis.workspace.application.task.SnapshotTreeMaterialization.integrity;
import static org.synesis.workspace.application.task.SnapshotTreeMaterialization.materializeSnapshot;
import static org.synesis.workspace.application.task.SnapshotTreeMaterialization.materializedPaths;
import static org.synesis.workspace.application.task.SnapshotTreeMaterialization.preparedTreeHash;
import static org.synesis.workspace.application.task.SnapshotTreeMaterialization.projectIdentity;
import static org.synesis.workspace.application.task.SnapshotTreeMaterialization.stableId;
import static org.synesis.workspace.application.task.WorktreeChangeInspection.inspectWorktreeChanges;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.workspace.application.task.WorktreeChangeInspection.WorktreeChanges;

/**
 * Service for creating and verifying immutable task snapshots from worker worktrees.
 *
 * <p>A completed task is represented by an immutable snapshot record referencing the
 * worker's verified Git commit SHA, base commit, changed-path manifest, and capability
 * dependencies.
 *
 * @since 1.0
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class TaskSnapshotService {

    private final SnapshotArtifactPolicy artifactPolicy;
    private final org.synesis.workspace.lifecycle.RepositoryPortabilityService portabilityService;

    /**
     * Creates a task snapshot service.
     */
    public TaskSnapshotService() {
        this.artifactPolicy = new SnapshotArtifactPolicy();
        this.portabilityService = new org.synesis.workspace.lifecycle.RepositoryPortabilityService();
    }

    /**
     * Computes the claimed source tree used by capability publication without changing HEAD,
     * the normal index, or the working files. Git tree objects may be written to capture it.
     *
     * @param worktree worker's assigned worktree
     * @param laneBaseCommit server-recorded lane baseline
     * @param claims selectors owned by the publishing lane
     * @param foreignClaims selectors owned by other active participants
     * @return Git tree hash for the source paths eligible for this lane's snapshot
     * @throws IOException if Git inspection or tree preparation fails
     */
    public String capabilityPublicationTreeHash(Path worktree, String laneBaseCommit,
            List<ResourceSelector> claims, List<ResourceSelector> foreignClaims) throws IOException {
        return SnapshotTreeMaterialization.capabilityPublicationTreeHash(artifactPolicy, worktree, laneBaseCommit, claims, foreignClaims);
    }

    /**
     * Resolves the diff base for an intent running in its exact bound worktree.
     *
     * <p>An active intent can outlive an integration that advances the control checkout. A clean
     * provider binding may then be reallocated at the new control commit while the durable intent
     * keeps its original admission base. Use that assigned-worktree base only when Git proves it is
     * descended from the intent base and the current worktree HEAD; otherwise preserve the original
     * intent base. This excludes already-integrated sibling changes from the next snapshot without
     * changing the intent, its claims, or its authority lineage.</p>
     *
     * @param workerWorktreePath exact assigned worktree
     * @param intentBaseCommit   immutable intent admission base
     * @param bindingBaseCommit  server-recorded base of the assigned worktree
     * @return the verified worktree base, or the original intent base
     * @throws IOException if Git inspection fails
     */
    public String snapshotBaseForBoundWorktree(Path workerWorktreePath, String intentBaseCommit, String bindingBaseCommit) throws IOException {
        return WorktreeChangeInspection.snapshotBaseForBoundWorktree(workerWorktreePath, intentBaseCommit, bindingBaseCommit);
    }

    /**
     * Checks whether the worker worktree contains claim-covered source changes relative to the
     * authoritative lane baseline.
     *
     * <p>This is the read-only precondition used by next-action projection before recommending
     * {@code finish_lane}. It mirrors the changed-path and artifact-policy portion of
     * {@link #createSnapshot} so a projected publication action cannot be emitted for an empty or
     * rejected snapshot. A recovered worktree may be based on a sibling's integrated commit; those
     * inherited source changes are not publication authority, so the claim boundary of snapshot
     * creation applies. The lane may contain several committed mutations as well as a current
     * working-tree mutation, so the declared baseline is used instead of {@code HEAD^}.
     *
     * @param workerWorktreePath       absolute worker worktree path
     * @param claims                   exact lane claims
     * @param laneBaseCommit           server-recorded lane baseline, or {@code null}
     * @param allowOtherClaimedChanges whether source changes outside this lane may be present
     * @return true when this lane has at least one publishable source change
     * @throws IOException if Git inspection fails
     */

    public boolean hasPublishableChanges(Path workerWorktreePath, List<ResourceSelector> claims, String laneBaseCommit, boolean allowOtherClaimedChanges) throws IOException {
        return PublishableChangeDetection.hasPublishableChanges(artifactPolicy, workerWorktreePath, claims, laneBaseCommit, allowOtherClaimedChanges);
    }

    /**
     * Checks one lane in a worktree that may contain durable content belonging to other active
     * claims.
     *
     * <p>Continuation deliberately restores the immutable recovery snapshot as a usable working
     * tree.  That snapshot can contain source paths claimed by another active lane, such as a
     * review report or a sibling producer's partial output.  Those paths must remain available to
     * the other lane, but they must not fence publication of the current lane or become part of its
     * immutable snapshot.  Unknown source paths remain fail-closed.</p>
     *
     * @param workerWorktreePath absolute worker worktree path
     * @param laneClaims         exact claims for the lane being published
     * @param participantClaims  union of active claims owned by the participant
     * @param foreignClaims      active claims owned by other participants whose durable content may be
     *                           present in the worktree
     * @param laneBaseCommit     server-recorded lane baseline
     * @return true when the lane has a claim-covered source change and all other source changes are
     *         covered by participant or foreign claims
     * @throws IOException if Git inspection fails
     */
    public boolean hasScopedPublishableChanges(Path workerWorktreePath, List<ResourceSelector> laneClaims, List<ResourceSelector> participantClaims, List<ResourceSelector> foreignClaims, String laneBaseCommit)
            throws IOException {
        return PublishableChangeDetection.hasScopedPublishableChanges(artifactPolicy, workerWorktreePath, laneClaims, participantClaims, foreignClaims, laneBaseCommit);
    }

    /**
     * Checks whether a successor has changed the claim-covered content of a rejected immutable
     * snapshot.
     *
     * <p>A recovery worktree intentionally starts with the rejected snapshot materialized as
     * working-tree content on the successor's own lane baseline. Comparing only with that lane
     * baseline would therefore mistake the recovered content for a new revision. This predicate
     * compares the current claim-scoped source paths with the rejected snapshot commit. The later
     * publication predicate remains responsible for the complete participant-claim and artifact
     * fence; this predicate only decides whether the owner has made a real correction.</p>
     *
     * @param workerWorktreePath successor worktree
     * @param laneClaims         exact successor lane claims
     * @param laneBaseCommit     server-recorded successor lane baseline
     * @param rejectedSnapshot   immutable rejected snapshot
     * @return {@code true} when a claim-covered source correction exists
     * @throws IOException when Git inspection or the rejected snapshot commit is unavailable
     */
    public boolean hasRevisionPublishableChanges(Path workerWorktreePath, List<ResourceSelector> laneClaims, String laneBaseCommit, TaskSnapshotRecord rejectedSnapshot) throws IOException {
        return PublishableChangeDetection.hasRevisionPublishableChanges(workerWorktreePath, laneClaims, laneBaseCommit, rejectedSnapshot);
    }

    /**
     * Checks the complete worker worktree for uncommitted or untracked files.
     *
     * <p>No-change completion uses this stricter predicate than snapshot
     * publication: even an artifact or untracked file is unpublished mutation and therefore keeps the
     * lane on the snapshot path.</p>
     *
     * @param workerWorktreePath absolute worker worktree path
     * @return {@code true} when Git reports no dirty or untracked paths
     * @throws IOException if Git inspection fails
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isCleanWorktree(Path workerWorktreePath) throws IOException {
        return WorktreeChangeInspection.isCleanWorktree(workerWorktreePath);
    }

    /**
     * Creates or recovers an immutable task snapshot record for a worker's task.
     *
     * <p>A non-null expected tree comes from the publication projection. The already captured tree
     * must match before a commit or ref is created. HEAD, the normal index, and working files remain
     * unchanged.
     *
     * @param request the lane, its worktree, claims, and provenance to record
     * @return immutable task snapshot record
     * @throws IOException if Git inspection fails or the projected source tree is stale
     */
    public TaskSnapshotRecord createSnapshot(SnapshotRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        Path workerWorktreePath = request.workerWorktreePath();
        Optional<TaskSnapshotRecord> existingOpt = request.existing();
        String expectedTreeHash = request.expectedTreeHash();
        List<ResourceSelector> claims = request.claimScope().claims();
        SnapshotRequest.Lane lane = request.lane();


        // Inspect the lane without requiring the harness to create a commit.
        if (existingOpt.isPresent()) {
            // A previously published snapshot is authoritative.  Do not inspect
            // or resnapshot a mutable lane during an idempotent retry.
            if (expectedTreeHash != null && !expectedTreeHash.equals(
                    runGitOutput(workerWorktreePath, "rev-parse", existingOpt.get().commitSha() + "^{tree}"))) {
                throw new IOException("CAPABILITY_PUBLICATION_TREE_STALE");
            }
            return existingOpt.get();
        }
        WorktreeChanges changes = inspectWorktreeChanges(workerWorktreePath, request.laneBaseCommit(), false);
        boolean dirty = changes.dirty();
        String headCommit = changes.headCommit();
        String baseCommit = changes.baseCommit();
        List<String> allChangedPaths = changes.changedPaths();
        SnapshotArtifactPolicy.Manifest artifactManifest = artifactPolicy.classify(allChangedPaths);
        List<String> materializedPaths = materializedPaths(artifactPolicy, allChangedPaths, claims, request.claimScope().scopeToClaims(), request.claimScope().foreignClaims());
        String treeHash = preparedTreeHash(workerWorktreePath, headCommit, materializedPaths);
        if (expectedTreeHash != null && !expectedTreeHash.equals(treeHash)) {
            throw new IOException("CAPABILITY_PUBLICATION_TREE_STALE");
        }
        String snapshotId = "snap_" + stableId(projectIdentity(request.controlRoot()), lane.laneId(), lane.claimEpoch(), baseCommit, treeHash);
        String commitSha;
        if (dirty || expectedTreeHash != null) {
            commitSha = materializeSnapshot(workerWorktreePath, headCommit, snapshotId, treeHash);
        } else {
            String ref = SnapshotRefs.snapshotRef(snapshotId);
            String existingCommit;
            try {
                existingCommit = runGitOutput(workerWorktreePath, "rev-parse", "--verify", ref);
            } catch (IOException missing) {
                existingCommit = null;
            }
            // Capability publication also materializes clean lanes. Preserve its
            // exact identity when ordinary lane completion follows publication.
            if (existingCommit != null && !existingCommit.equals(headCommit)) {
                commitSha = materializeSnapshot(workerWorktreePath, headCommit, snapshotId, treeHash);
            } else {
                commitSha = headCommit;
                runGitOutput(workerWorktreePath, "update-ref", ref, commitSha);
            }
        }
        org.synesis.workspace.lifecycle.RepositoryPortabilityService.Report portability = portabilityService.validateTree(workerWorktreePath, commitSha);
        if (!portability.portable()) {
            try {
                runGitOutput(workerWorktreePath, "update-ref", "-d", SnapshotRefs.snapshotRef(snapshotId));
            } catch (IOException ignored) {
                // The portability failure remains the authoritative result.
            }
            throw new IllegalStateException("REPOSITORY_NOT_PORTABLE:" + portability.findings());
        }
        List<String> capabilityDependencies = new ArrayList<>();
        for (CapabilityRequestRecord cap : request.activeCapabilities()) {
            capabilityDependencies.add(cap.handle().value());
        }

        SnapshotProvenance provenance = new SnapshotProvenance(lane.workGroupId(),
                lane.laneId(),
                lane.authorityLineageId(),
                lane.participant(),
                lane.bindingIdentity(),
                lane.claimEpoch(),
                capabilityDependencies,
                lane.handoffLineage(),
                claims.stream().map(selector -> selector.kind().name() + ":" + selector.value()).toList(),
                SnapshotRefs.snapshotRef(snapshotId),
                integrity(commitSha, materializedPaths),
                artifactManifest.digest());
        return new TaskSnapshotRecord(request.taskId(),
                snapshotId,
                request.nodeId(),
                request.supervisorId(),
                request.workerId(),
                request.providerSessionId(),
                baseCommit,
                commitSha,
                materializedPaths,
                List.copyOf(capabilityDependencies),
                request.summary(),
                System.currentTimeMillis(),
                provenance,
                request.reviewRequired());
    }

    /**
     * Pins an already materialized snapshot commit under a transaction-owned prepared ref and
     * verifies that the ref resolves to the expected commit.
     *
     * @param worktreePath lane worktree
     * @param snapshot     snapshot record
     * @param completionId durable completion transaction ID
     * @return prepared ref name
     * @throws IOException if the ref cannot be created or verified
     */
    public String pinPreparedRef(Path worktreePath, TaskSnapshotRecord snapshot, String completionId) throws IOException {
        return SnapshotRefLifecycle.pinPreparedRef(worktreePath, snapshot, completionId);
    }

    /**
     * Removes a provisional final snapshot ref before durable preparation.
     *
     * @param worktreePath lane worktree
     * @param snapshot     snapshot record
     * @throws IOException if Git cannot remove the ref
     */
    public void removeSnapshotRef(Path worktreePath, TaskSnapshotRecord snapshot) throws IOException {
        SnapshotRefLifecycle.removeSnapshotRef(worktreePath, snapshot);
    }

    /**
     * Removes a transaction-owned prepared reference during an authorized completion unwind.
     *
     * @param worktreePath lane worktree
     * @param preparedRef  prepared reference
     * @throws IOException if Git cannot remove the reference
     */
    public void removePreparedRef(Path worktreePath, String preparedRef) throws IOException {
        SnapshotRefLifecycle.removePreparedRef(worktreePath, preparedRef);
    }

    /**
     * Promotes a verified prepared ref to the immutable public snapshot ref.
     *
     * @param worktreePath   lane worktree
     * @param preparedRef    prepared ref
     * @param finalRef       final immutable ref
     * @param expectedCommit expected commit
     * @throws IOException if the object cannot be verified or promoted
     */
    public void promotePreparedRef(Path worktreePath, String preparedRef, String finalRef, String expectedCommit) throws IOException {
        SnapshotRefLifecycle.promotePreparedRef(worktreePath, preparedRef, finalRef, expectedCommit);
    }

    /**
     * Verifies a prepared ref still resolves to the expected immutable commit.
     *
     * @param worktreePath   repository worktree
     * @param preparedRef    prepared ref
     * @param expectedCommit expected commit
     * @throws IOException if verification fails
     */
    public void verifyPreparedRef(Path worktreePath, String preparedRef, String expectedCommit) throws IOException {
        SnapshotRefLifecycle.verifyPreparedRef(worktreePath, preparedRef, expectedCommit);
    }

    /**
     * Returns the immutable tree object reached by a commit.
     *
     * @param worktreePath repository worktree
     * @param commit       commit object
     * @return tree SHA
     * @throws IOException if Git cannot resolve the tree
     */
    public String treeHash(Path worktreePath, String commit) throws IOException {
        return SnapshotGitCommands.treeHash(worktreePath, commit);
    }
}
