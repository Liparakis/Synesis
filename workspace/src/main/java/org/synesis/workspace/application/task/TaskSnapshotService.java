package org.synesis.workspace.application.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

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

    private record WorktreeChanges(boolean dirty, String headCommit, String baseCommit, List<String> changedPaths) {

    }

    private final SnapshotArtifactPolicy artifactPolicy;
    private final org.synesis.workspace.lifecycle.RepositoryPortabilityService portabilityService;

    /**
     * Creates a task snapshot service.
     */
    public TaskSnapshotService() {
        this(new SnapshotArtifactPolicy(), new org.synesis.workspace.lifecycle.RepositoryPortabilityService());
    }

    /**
     * Creates a service with an explicit artifact policy. *
     *
     * @param artifactPolicy snapshot artifact policy
     */
    public TaskSnapshotService(SnapshotArtifactPolicy artifactPolicy) {
        this(artifactPolicy, new org.synesis.workspace.lifecycle.RepositoryPortabilityService());
    }

    /**
     * Creates a service with explicit artifact and portability policies.
     *
     * @param artifactPolicy     snapshot artifact policy
     * @param portabilityService complete-tree portability validator
     */
    public TaskSnapshotService(SnapshotArtifactPolicy artifactPolicy, org.synesis.workspace.lifecycle.RepositoryPortabilityService portabilityService) {
        this.artifactPolicy = Objects.requireNonNull(artifactPolicy, "artifactPolicy");
        this.portabilityService = Objects.requireNonNull(portabilityService, "portabilityService");
    }

    private static String gitRevParse(Path workdir) throws IOException {
        return runGitOutput(workdir, "rev-parse", "HEAD");
    }

    private static String deriveBaseCommit(Path workdir) {
        try {
            return runGitOutput(workdir, "rev-parse", "HEAD^");
        } catch (IOException e) {
            try {
                return runGitOutput(workdir, "hash-object", "-t", "tree", "/dev/null");
            } catch (IOException e2) {
                return "";
            }
        }
    }

    private WorktreeChanges inspectWorktreeChanges(Path worktree, String laneBaseCommit, boolean includeAllUntracked) throws IOException {
        String status = includeAllUntracked ? runGitOutput(worktree, "status", "--porcelain", "--untracked-files=all") : runGitOutput(worktree, "status", "--porcelain");
        boolean dirty = !status.isBlank();
        String headCommit = gitRevParse(worktree);
        String baseCommit = laneBaseCommit == null || laneBaseCommit.isBlank() ? dirty ? headCommit : deriveBaseCommit(worktree) : laneBaseCommit;
        return new WorktreeChanges(dirty, headCommit, baseCommit, deriveChangedPaths(worktree, baseCommit, dirty));
    }

    private Optional<List<String>> publishableSourceChanges(List<String> changedPaths) {
        SnapshotArtifactPolicy.Manifest manifest = artifactPolicy.classify(changedPaths);
        if (!manifest.valid()) {
            return Optional.empty();
        }
        return Optional.of(changedPaths.stream().filter(path -> !manifest.allowedArtifacts().contains(path)).toList());
    }

    /** Inspects a worktree and applies the same artifact policy used by both publication gates. */
    private Optional<List<String>> publishableWorktreeSourceChanges(Path worktreePath, String laneBaseCommit) throws IOException {
        List<String> changedPaths = inspectWorktreeChanges(worktreePath, laneBaseCommit, true).changedPaths();
        return publishableSourceChanges(changedPaths);
    }

    private static List<String> deriveChangedPaths(Path workdir, String baseCommit, boolean includeWorkingTree) {
        if (baseCommit.isBlank()) {
            return List.of();
        }
        try {
            List<String> paths = new ArrayList<>();
            String committed = runGitOutput(workdir, "diff", "--name-only", baseCommit, "HEAD");
            String working = includeWorkingTree ? runGitOutput(workdir, "diff", "--name-only", "HEAD") : "";
            String untracked = includeWorkingTree ? runGitOutput(workdir, "ls-files", "--others", "--exclude-standard") : "";
            String output = committed + "\n" + working + "\n" + untracked;
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    if (!paths.contains(trimmed)) {
                        paths.add(trimmed);
                    }
                    if (paths.size() >= TaskSnapshotRecord.MAX_CHANGED_PATHS) {
                        break;
                    }
                }
            }
            return List.copyOf(paths);
        } catch (Exception ex) {
            throw new IllegalStateException("SNAPSHOT_DIFF_INSPECTION_FAILED", ex);
        }
    }

    private static String preparedTreeHash(Path workdir, String parent, List<String> paths) throws IOException {
        Path index = null;
        try {
            index = Files.createTempFile("synesis-tree-", ".index");
            Files.deleteIfExists(index);
            runGitWithIndex(workdir, index, "read-tree", parent);
            stageSourceIndex(workdir, index, paths);
            return runGitWithIndexOutput(workdir, index, "write-tree");
        } finally {
            if (index != null) {
                Files.deleteIfExists(index);
            }
        }
    }

    private static String stableId(String project, UUID laneId, long epoch, String base, String tree) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((project + "\n" + laneId + "\n" + epoch + "\n" + base + "\n" + tree).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception failure) {
            throw new IllegalStateException("SNAPSHOT_ID_DERIVATION_FAILED", failure);
        }
    }

    private static String projectIdentity(Path controlRoot) {
        Path metadata = controlRoot.resolve(".synesis/project.json");
        try {
            String content = Files.readString(metadata);
            int marker = content.indexOf("\"projectId\"");
            if (marker >= 0) {
                int colon = content.indexOf(':', marker);
                int first = content.indexOf('"', colon + 1);
                int second = content.indexOf('"', first + 1);
                if (first >= 0 && second > first) {
                    return content.substring(first + 1, second);
                }
            }
        } catch (IOException ignored) {
            // The canonical fallback remains deterministic for uninitialized
            // unit fixtures; production callers require project metadata.
        }
        return controlRoot.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String materializeSnapshot(Path workdir, String parent, String snapshotId, List<String> paths) throws IOException {
        Path index = Files.createTempFile("synesis-snapshot-", ".index");
        Files.deleteIfExists(index);
        try {
            runGitWithIndex(workdir, index, "read-tree", parent);
            // Provider/session metadata belongs to the lane runtime, never to a
            // published source snapshot.  Keep it at the parent tree value.
            stageSourceIndex(workdir, index, paths);
            String tree = runGitWithIndexOutput(workdir, index, "write-tree");
            String commit = runGitWithIndexOutput(workdir, index, "commit-tree", tree, "-p", parent, "-m", "Synesis immutable lane snapshot");
            runGitOutput(workdir, "update-ref", "refs/synesis/snapshots/" + snapshotId, commit);
            return commit;
        } finally {
            Files.deleteIfExists(index);
        }
    }

    private static void stageSourceIndex(Path workdir, Path index, List<String> paths) throws IOException {
        // Let Git's ignore rules omit provider/admin material first.  Reset
        // explicitly managed paths from the temporary index as well, covering
        // projects that accidentally track one of those files.
        if (paths.isEmpty()) {
            runGitWithIndex(workdir, index, "add", "-A", "--", ".");
        } else {
            List<String> add = new ArrayList<>();
            add.add("add");
            add.add("-A");
            add.add("--");
            add.addAll(paths);
            runGitWithIndex(workdir, index, add.toArray(String[]::new));
        }
        for (String managed : List.of(".synesis", ".codex", ".claude", "AGENTS.md", ".mcp.json", "__pycache__", ":(glob)**/__pycache__/**")) {
            try {
                runGitWithIndex(workdir, index, "reset", "--", managed);
            } catch (IOException ignored) {
                // The managed path may not exist or be tracked in this lane.
            }
        }
    }

    private static String integrity(String commit, List<String> paths) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((commit + "\n" + String.join("\n", paths)).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            return commit;
        }
    }

    private static String runGitWithIndexOutput(Path workdir, Path index, String... args) throws IOException {
        return GitProcessRunner.runWithIndex(workdir, index, args).trim();
    }

    private static void runGitWithIndex(Path workdir, Path index, String... args) throws IOException {
        GitProcessRunner.runWithIndex(workdir, index, args);
    }

    private static String runGitOutput(Path workdir, String... args) throws IOException {
        return GitProcessRunner.run(workdir, args).trim();
    }

    /**
     * Checks whether the worker worktree currently contains at least one publishable source change.
     *
     * <p>This is the read-only precondition used by next-action projection
     * before recommending {@code finish_lane}. It deliberately mirrors the changed-path and
     * artifact-policy portion of {@link #createSnapshot} so a projected publication action cannot be
     * emitted for an empty or rejected snapshot.
     *
     * @param workerWorktreePath absolute worker worktree path
     * @return {@code true} when snapshot creation can observe a valid source change; {@code false}
     *         for an empty, artifact-only, or rejected change set
     * @throws IOException if Git inspection fails
     */
    public boolean hasPublishableChanges(Path workerWorktreePath) throws IOException {
        return hasPublishableChanges(workerWorktreePath, List.of());
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
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        if (intentBaseCommit == null || intentBaseCommit.isBlank() || bindingBaseCommit == null || bindingBaseCommit.isBlank() || intentBaseCommit.equals(bindingBaseCommit)) {
            return intentBaseCommit;
        }
        String worktreeHead = gitRevParse(workerWorktreePath);
        if (commitIsAncestor(workerWorktreePath, intentBaseCommit, bindingBaseCommit) && commitIsAncestor(workerWorktreePath, bindingBaseCommit, worktreeHead)) {
            return bindingBaseCommit;
        }
        return intentBaseCommit;
    }

    private static boolean commitIsAncestor(Path worktree, String ancestor, String descendant) throws IOException {
        return GitProcessRunner.runResult(worktree, "merge-base", "--is-ancestor", ancestor, descendant).exitCode() == 0;
    }

    /**
     * Checks whether the worker worktree contains source changes covered by the current lane claims.
     *
     * <p>A recovered worktree may be based on a sibling's integrated commit.
     * Those inherited source changes are not publication authority for this lane.  Applying the same
     * claim boundary used by snapshot creation keeps a projected {@code finish_lane} executable
     * without expanding ownership.
     *
     * @param workerWorktreePath absolute worker worktree path
     * @param claims             current lane resource claims
     * @return {@code true} when at least one source change is covered and no source change falls
     *         outside the supplied claims
     * @throws IOException if Git inspection fails
     */
    public boolean hasPublishableChanges(Path workerWorktreePath, List<ResourceSelector> claims) throws IOException {
        return hasPublishableChanges(workerWorktreePath, claims, null);
    }

    /**
     * Checks whether a worker worktree contains publishable changes relative to the authoritative
     * lane baseline.
     *
     * <p>The lane may contain several committed mutations as well as a current
     * working-tree mutation. Using the declared baseline keeps all of those changes in the
     * publication decision; deriving {@code HEAD^} would inspect only the most recent commit and
     * could incorrectly turn an earlier substantive mutation into a no-change completion.</p>
     *
     * @param workerWorktreePath absolute worker worktree path
     * @param claims             current lane claims
     * @param laneBaseCommit     server-recorded lane baseline, or {@code null} when no lane baseline
     *                           is available
     * @return {@code true} when at least one claimed source change is present
     * @throws IOException if Git inspection fails
     */
    public boolean hasPublishableChanges(Path workerWorktreePath, List<ResourceSelector> claims, String laneBaseCommit) throws IOException {
        return hasPublishableChanges(workerWorktreePath, claims, laneBaseCommit, false);
    }

    /**
     * Checks whether this lane has claim-covered source changes while optionally allowing sibling
     * lanes owned by the same participant to have separate claim-covered changes in the same
     * worktree.
     *
     * @param workerWorktreePath       absolute worker worktree path
     * @param claims                   exact lane claims
     * @param laneBaseCommit           server-recorded lane baseline, or {@code null}
     * @param allowOtherClaimedChanges whether source changes outside this lane may be present
     * @return true when this lane has at least one publishable source change
     * @throws IOException if Git inspection fails
     */
    public boolean hasPublishableChanges(Path workerWorktreePath, List<ResourceSelector> claims, String laneBaseCommit, boolean allowOtherClaimedChanges) throws IOException {
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        Objects.requireNonNull(claims, "claims");
        Optional<List<String>> publishableChanges = publishableWorktreeSourceChanges(workerWorktreePath, laneBaseCommit);
        if (publishableChanges.isEmpty()) {
            return false;
        }
        List<String> sourceChanges = publishableChanges.orElseThrow();
        if (!claims.isEmpty()) {
            boolean laneChange = sourceChanges.stream().anyMatch(path -> claims.stream().anyMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))));
            if (!laneChange) {
                return false;
            }
            if (!allowOtherClaimedChanges && sourceChanges.stream().anyMatch(path -> claims.stream().noneMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))))) {
                return false;
            }
        }
        return !sourceChanges.isEmpty();
    }

    /**
     * Checks one lane in a shared worktree without allowing unowned source changes.
     *
     * @param workerWorktreePath absolute worker worktree path
     * @param laneClaims         claims for the lane being published
     * @param participantClaims  union of all active claims owned by the participant
     * @param laneBaseCommit     server-recorded lane baseline
     * @return true when lane changes exist and every source change belongs to a participant lane
     * @throws IOException if Git inspection fails
     */
    public boolean hasScopedPublishableChanges(Path workerWorktreePath, List<ResourceSelector> laneClaims, List<ResourceSelector> participantClaims, String laneBaseCommit) throws IOException {
        return hasScopedPublishableChanges(workerWorktreePath, laneClaims, participantClaims, List.of(), laneBaseCommit);
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
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        Objects.requireNonNull(laneClaims, "laneClaims");
        Objects.requireNonNull(participantClaims, "participantClaims");
        Objects.requireNonNull(foreignClaims, "foreignClaims");
        if (foreignClaims.isEmpty()) {
            return hasPublishableChanges(workerWorktreePath, laneClaims, laneBaseCommit, true) && hasPublishableChanges(workerWorktreePath, participantClaims, laneBaseCommit, false);
        }
        Optional<List<String>> publishableChanges = publishableWorktreeSourceChanges(workerWorktreePath, laneBaseCommit);
        if (publishableChanges.isEmpty()) {
            return false;
        }
        List<String> sourceChanges = publishableChanges.orElseThrow();
        boolean laneChange = sourceChanges.stream().anyMatch(path -> laneClaims.stream().anyMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))));
        if (!laneChange) {
            return false;
        }
        return sourceChanges.stream().allMatch(path -> participantClaims.stream().anyMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))) || foreignClaims.stream()
                .anyMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))));
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
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        Objects.requireNonNull(laneClaims, "laneClaims");
        Objects.requireNonNull(rejectedSnapshot, "rejectedSnapshot");
        String rejectedCommit = rejectedSnapshot.commitSha();
        if (rejectedCommit == null || rejectedCommit.isBlank()) {
            return false;
        }
        runGitOutput(workerWorktreePath, "cat-file", "-e", rejectedCommit + "^{commit}");
        List<String> currentPaths = inspectWorktreeChanges(workerWorktreePath, laneBaseCommit, true).changedPaths();
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>(rejectedSnapshot.changedPaths());
        candidates.addAll(currentPaths);
        for (String path : candidates) {
            if (laneClaims.stream().noneMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path)))) {
                continue;
            }
            if (differsFromCommit(workerWorktreePath, rejectedCommit, path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean differsFromCommit(Path workdir, String commit, String path) throws IOException {
        String normalized = path.replace('\\', '/');
        boolean presentInCommit;
        try {
            runGitOutput(workdir, "cat-file", "-e", commit + ":" + normalized);
            presentInCommit = true;
        } catch (IOException missingPath) {
            presentInCommit = false;
        }
        if (!presentInCommit) {
            return Files.exists(workdir.resolve(normalized));
        }
        Path currentPath = workdir.resolve(normalized);
        if (!Files.exists(currentPath)) {
            return true;
        }
        String committedHash = runGitOutput(workdir, "rev-parse", commit + ":" + normalized);
        String currentHash = runGitOutput(workdir, "hash-object", "--", normalized);
        return !committedHash.equals(currentHash);
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
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        return runGitOutput(workerWorktreePath, "status", "--porcelain", "--untracked-files=all").isBlank();
    }

    /**
     * Creates or recovers an immutable task snapshot record for a worker's task.
     *
     * @param taskId             task UUID
     * @param nodeId             worker node ID
     * @param supervisorId       worker supervisor ID
     * @param workerId           worker ID
     * @param providerSessionId  provider session ID
     * @param workerWorktreePath absolute path to worker worktree
     * @param controlRoot        absolute path to control project root
     * @param summary            task completion summary
     * @param existingOpt        existing snapshot record for this task, if any
     * @param activeCapabilities list of capability request records associated with this worker
     * @return immutable task snapshot record
     * @throws IOException if Git inspection fails
     */
    public TaskSnapshotRecord createSnapshot(UUID taskId,
            String nodeId,
            String supervisorId,
            String workerId,
            String providerSessionId,
            Path workerWorktreePath,
            Path controlRoot,
            String summary,
            Optional<TaskSnapshotRecord> existingOpt,
            List<CapabilityRequestRecord> activeCapabilities) throws IOException {
        return createSnapshot(taskId, nodeId, supervisorId, workerId, providerSessionId, workerWorktreePath, controlRoot, summary, existingOpt, activeCapabilities, List.of());
    }

    /**
     * Creates a snapshot while recording the lane's current resource claims.
     *
     * @param taskId             task ID
     * @param nodeId             node ID
     * @param supervisorId       supervisor ID
     * @param workerId           worker ID
     * @param providerSessionId  binding ID
     * @param workerWorktreePath lane worktree
     * @param controlRoot        project root
     * @param summary            summary
     * @param existingOpt        existing snapshot
     * @param activeCapabilities capability dependencies
     * @param claims             current exact-path/subtree claims
     * @return immutable snapshot
     * @throws IOException Git failure
     */
    public TaskSnapshotRecord createSnapshot(UUID taskId,
            String nodeId,
            String supervisorId,
            String workerId,
            String providerSessionId,
            Path workerWorktreePath,
            Path controlRoot,
            String summary,
            Optional<TaskSnapshotRecord> existingOpt,
            List<CapabilityRequestRecord> activeCapabilities,
            List<ResourceSelector> claims) throws IOException {
        return createSnapshot(taskId,
                nodeId,
                supervisorId,
                workerId,
                providerSessionId,
                workerWorktreePath,
                controlRoot,
                summary,
                existingOpt,
                activeCapabilities,
                claims,
                taskId,
                taskId,
                nodeId,
                providerSessionId,
                1,
                List.of());
    }

    /**
     * Creates a snapshot while recording explicit logical-lane provenance.
     *
     * @param taskId             task ID
     * @param nodeId             node ID
     * @param supervisorId       supervisor ID
     * @param workerId           worker ID
     * @param providerSessionId  binding ID
     * @param workerWorktreePath lane worktree
     * @param controlRoot        project root
     * @param summary            summary
     * @param existingOpt        existing snapshot
     * @param activeCapabilities capability dependencies
     * @param claims             current exact-path/subtree claims
     * @param workGroupId        logical work-group ID
     * @param laneId             mutation-lane intent ID
     * @param participant        opaque participant handle
     * @param bindingIdentity    exact binding identity
     * @param claimEpoch         current claim epoch
     * @param handoffLineage     handoff references
     * @return immutable snapshot
     * @throws IOException Git failure
     */
    public TaskSnapshotRecord createSnapshot(UUID taskId,
            String nodeId,
            String supervisorId,
            String workerId,
            String providerSessionId,
            Path workerWorktreePath,
            Path controlRoot,
            String summary,
            Optional<TaskSnapshotRecord> existingOpt,
            List<CapabilityRequestRecord> activeCapabilities,
            List<ResourceSelector> claims,
            UUID workGroupId,
            UUID laneId,
            String participant,
            String bindingIdentity,
            long claimEpoch,
            List<String> handoffLineage) throws IOException {
        return createSnapshot(taskId,
                nodeId,
                supervisorId,
                workerId,
                providerSessionId,
                workerWorktreePath,
                controlRoot,
                summary,
                existingOpt,
                activeCapabilities,
                claims,
                workGroupId,
                laneId,
                participant,
                bindingIdentity,
                claimEpoch,
                WorkIntent.defaultAuthorityLineage(laneId),
                handoffLineage);
    }

    /**
     * Creates a snapshot while recording an explicit authority lineage.
     *
     * @param taskId             task ID
     * @param nodeId             node ID
     * @param supervisorId       supervisor ID
     * @param workerId           worker ID
     * @param providerSessionId  provider session ID
     * @param workerWorktreePath lane worktree
     * @param controlRoot        control root
     * @param summary            completion summary
     * @param existingOpt        existing immutable snapshot
     * @param activeCapabilities capability records
     * @param claims             lane claims
     * @param workGroupId        work-group ID
     * @param laneId             lane ID
     * @param participant        participant
     * @param bindingIdentity    binding identity
     * @param claimEpoch         claim epoch
     * @param authorityLineageId authority lineage
     * @param handoffLineage     handoff lineage
     * @return immutable snapshot
     * @throws IOException Git failure
     */
    public TaskSnapshotRecord createSnapshot(UUID taskId,
            String nodeId,
            String supervisorId,
            String workerId,
            String providerSessionId,
            Path workerWorktreePath,
            Path controlRoot,
            String summary,
            Optional<TaskSnapshotRecord> existingOpt,
            List<CapabilityRequestRecord> activeCapabilities,
            List<ResourceSelector> claims,
            UUID workGroupId,
            UUID laneId,
            String participant,
            String bindingIdentity,
            long claimEpoch,
            UUID authorityLineageId,
            List<String> handoffLineage) throws IOException {
        return createSnapshot(taskId,
                nodeId,
                supervisorId,
                workerId,
                providerSessionId,
                workerWorktreePath,
                controlRoot,
                summary,
                existingOpt,
                activeCapabilities,
                claims,
                workGroupId,
                laneId,
                participant,
                bindingIdentity,
                claimEpoch,
                authorityLineageId,
                handoffLineage,
                false);
    }

    /**
     * Creates a snapshot while recording whether review acceptance gates integration.
     *
     * @param taskId             task ID
     * @param nodeId             node ID
     * @param supervisorId       supervisor ID
     * @param workerId           worker ID
     * @param providerSessionId  binding ID
     * @param workerWorktreePath lane worktree
     * @param controlRoot        control root
     * @param summary            completion summary
     * @param existingOpt        existing immutable snapshot for the exact revision
     * @param activeCapabilities capability records
     * @param claims             lane claims
     * @param workGroupId        work-group ID
     * @param laneId             lane ID
     * @param participant        participant
     * @param bindingIdentity    binding identity
     * @param claimEpoch         claim epoch
     * @param authorityLineageId authority lineage
     * @param handoffLineage     handoff lineage
     * @param reviewRequired     whether durable review acceptance is required
     * @return immutable snapshot
     * @throws IOException Git failure
     */
    public TaskSnapshotRecord createSnapshot(UUID taskId,
            String nodeId,
            String supervisorId,
            String workerId,
            String providerSessionId,
            Path workerWorktreePath,
            Path controlRoot,
            String summary,
            Optional<TaskSnapshotRecord> existingOpt,
            List<CapabilityRequestRecord> activeCapabilities,
            List<ResourceSelector> claims,
            UUID workGroupId,
            UUID laneId,
            String participant,
            String bindingIdentity,
            long claimEpoch,
            UUID authorityLineageId,
            List<String> handoffLineage,
            boolean reviewRequired) throws IOException {
        return createSnapshot(taskId,
                nodeId,
                supervisorId,
                workerId,
                providerSessionId,
                workerWorktreePath,
                controlRoot,
                summary,
                existingOpt,
                activeCapabilities,
                claims,
                workGroupId,
                laneId,
                participant,
                bindingIdentity,
                claimEpoch,
                authorityLineageId,
                handoffLineage,
                reviewRequired,
                null);
    }

    /**
     * Creates a snapshot relative to the server-recorded lane baseline.
     *
     * <p>This overload is used by the completion path after the lane intent has
     * been revalidated. It preserves every committed and uncommitted mutation made since admission
     * while retaining the existing snapshot materialization and portability gates.</p>
     *
     * @param taskId             task ID
     * @param nodeId             node ID
     * @param supervisorId       supervisor ID
     * @param workerId           worker ID
     * @param providerSessionId  provider session ID
     * @param workerWorktreePath absolute worker worktree path
     * @param controlRoot        control root
     * @param summary            completion summary
     * @param existingOpt        existing immutable snapshot for the exact revision
     * @param activeCapabilities capability records
     * @param claims             lane claims
     * @param workGroupId        logical work-group ID
     * @param laneId             lane intent ID
     * @param participant        participant handle
     * @param bindingIdentity    exact provider binding identity
     * @param claimEpoch         current claim epoch
     * @param authorityLineageId current authority lineage
     * @param handoffLineage     handoff references
     * @param reviewRequired     whether review acceptance gates integration
     * @param laneBaseCommit     server-recorded lane baseline
     * @return immutable snapshot
     * @throws IOException if Git inspection fails
     */
    public TaskSnapshotRecord createSnapshot(UUID taskId,
            String nodeId,
            String supervisorId,
            String workerId,
            String providerSessionId,
            Path workerWorktreePath,
            Path controlRoot,
            String summary,
            Optional<TaskSnapshotRecord> existingOpt,
            List<CapabilityRequestRecord> activeCapabilities,
            List<ResourceSelector> claims,
            UUID workGroupId,
            UUID laneId,
            String participant,
            String bindingIdentity,
            long claimEpoch,
            UUID authorityLineageId,
            List<String> handoffLineage,
            boolean reviewRequired,
            String laneBaseCommit) throws IOException {
        return createSnapshot(taskId,
                nodeId,
                supervisorId,
                workerId,
                providerSessionId,
                workerWorktreePath,
                controlRoot,
                summary,
                existingOpt,
                activeCapabilities,
                claims,
                workGroupId,
                laneId,
                participant,
                bindingIdentity,
                claimEpoch,
                authorityLineageId,
                handoffLineage,
                reviewRequired,
                laneBaseCommit,
                false);
    }

    /**
     * Creates a snapshot for one lane in a shared participant worktree.
     *
     * @param taskId             task ID
     * @param nodeId             node identity
     * @param supervisorId       supervisor identity
     * @param workerId           worker identity
     * @param providerSessionId  provider binding
     * @param workerWorktreePath lane worktree
     * @param controlRoot        control root
     * @param summary            completion summary
     * @param existingOpt        existing snapshot
     * @param activeCapabilities capability dependencies
     * @param claims             exact lane claims
     * @param workGroupId        work group
     * @param laneId             lane intent
     * @param participant        participant
     * @param bindingIdentity    binding identity
     * @param claimEpoch         claim epoch
     * @param authorityLineageId authority lineage
     * @param handoffLineage     handoff references
     * @param reviewRequired     whether review gates integration
     * @param laneBaseCommit     server-recorded baseline
     * @param scopeToClaims      whether to materialize only this lane's claim-covered changes
     * @return immutable snapshot
     * @throws IOException if Git inspection fails
     */
    public TaskSnapshotRecord createSnapshot(UUID taskId,
            String nodeId,
            String supervisorId,
            String workerId,
            String providerSessionId,
            Path workerWorktreePath,
            Path controlRoot,
            String summary,
            Optional<TaskSnapshotRecord> existingOpt,
            List<CapabilityRequestRecord> activeCapabilities,
            List<ResourceSelector> claims,
            UUID workGroupId,
            UUID laneId,
            String participant,
            String bindingIdentity,
            long claimEpoch,
            UUID authorityLineageId,
            List<String> handoffLineage,
            boolean reviewRequired,
            String laneBaseCommit,
            boolean scopeToClaims) throws IOException {
        return createSnapshot(taskId,
                nodeId,
                supervisorId,
                workerId,
                providerSessionId,
                workerWorktreePath,
                controlRoot,
                summary,
                existingOpt,
                activeCapabilities,
                claims,
                workGroupId,
                laneId,
                participant,
                bindingIdentity,
                claimEpoch,
                authorityLineageId,
                handoffLineage,
                reviewRequired,
                laneBaseCommit,
                scopeToClaims,
                List.of());
    }

    /**
     * Creates a snapshot for one lane while excluding source paths owned by other active claims.
     *
     * <p>Foreign paths are preserved in the worker worktree for their lawful owners, but are not
     * materialized into this lane's immutable snapshot.  When claim scoping is disabled, unknown
     * source paths still cause a fail-closed rejection.</p>
     *
     * @param taskId             task ID
     * @param nodeId             node identity
     * @param supervisorId       supervisor identity
     * @param workerId           worker identity
     * @param providerSessionId  provider binding
     * @param workerWorktreePath absolute worker worktree path
     * @param controlRoot        control root
     * @param summary            completion summary
     * @param existingOpt        existing immutable snapshot for the exact revision
     * @param activeCapabilities capability records
     * @param claims             exact claims for the lane being published
     * @param workGroupId        logical work-group ID
     * @param laneId             lane intent ID
     * @param participant        participant handle
     * @param bindingIdentity    exact provider binding identity
     * @param claimEpoch         current claim epoch
     * @param authorityLineageId current authority lineage
     * @param handoffLineage     handoff references
     * @param reviewRequired     whether review acceptance gates integration
     * @param laneBaseCommit     server-recorded lane baseline
     * @param scopeToClaims      whether to materialize only this lane's claim-covered changes
     * @param foreignClaims      active claims owned by other participants
     * @return immutable snapshot
     * @throws IOException if Git inspection fails
     */
    public TaskSnapshotRecord createSnapshot(UUID taskId,
            String nodeId,
            String supervisorId,
            String workerId,
            String providerSessionId,
            Path workerWorktreePath,
            Path controlRoot,
            String summary,
            Optional<TaskSnapshotRecord> existingOpt,
            List<CapabilityRequestRecord> activeCapabilities,
            List<ResourceSelector> claims,
            UUID workGroupId,
            UUID laneId,
            String participant,
            String bindingIdentity,
            long claimEpoch,
            UUID authorityLineageId,
            List<String> handoffLineage,
            boolean reviewRequired,
            String laneBaseCommit,
            boolean scopeToClaims,
            List<ResourceSelector> foreignClaims) throws IOException {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(supervisorId, "supervisorId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(providerSessionId, "providerSessionId");
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(activeCapabilities, "activeCapabilities");
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(foreignClaims, "foreignClaims");

        // Inspect the lane without requiring the harness to create a commit.
        if (existingOpt.isPresent()) {
            // A previously published snapshot is authoritative.  Do not inspect
            // or resnapshot a mutable lane during an idempotent retry.
            return existingOpt.get();
        }
        WorktreeChanges changes = inspectWorktreeChanges(workerWorktreePath, laneBaseCommit, false);
        boolean dirty = changes.dirty();
        String headCommit = changes.headCommit();
        String baseCommit = changes.baseCommit();
        List<String> allChangedPaths = changes.changedPaths();
        SnapshotArtifactPolicy.Manifest artifactManifest = artifactPolicy.classify(allChangedPaths);
        if (!artifactManifest.valid()) {
            throw new IllegalStateException("SNAPSHOT_ARTIFACT_POLICY:" + artifactManifest.rejectedArtifacts());
        }
        List<String> changedPaths = allChangedPaths.stream().filter(path -> !artifactManifest.allowedArtifacts().contains(path)).toList();
        List<String> nonForeignChangedPaths = changedPaths.stream().filter(path -> foreignClaims.stream().noneMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path)))).toList();
        if (!scopeToClaims && !claims.isEmpty() && nonForeignChangedPaths.stream().anyMatch(path -> claims.stream().noneMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))))) {
            throw new IllegalStateException(
                    "UNCLAIMED_SNAPSHOT_PATH:" + nonForeignChangedPaths.stream().filter(path -> claims.stream().noneMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path)))).findFirst().orElse("unknown"));
        }
        List<String> materializedPaths =
                scopeToClaims ? nonForeignChangedPaths.stream().filter(path -> claims.stream().anyMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path)))).toList() : nonForeignChangedPaths;
        String treeHash = preparedTreeHash(workerWorktreePath, headCommit, materializedPaths);
        String snapshotId = "snap_" + stableId(projectIdentity(controlRoot), laneId, claimEpoch, baseCommit, treeHash);
        String commitSha;
        if (dirty) {
            commitSha = materializeSnapshot(workerWorktreePath, headCommit, snapshotId, materializedPaths);
        } else {
            commitSha = headCommit;
            runGitOutput(workerWorktreePath, "update-ref", "refs/synesis/snapshots/" + snapshotId, commitSha);
        }
        org.synesis.workspace.lifecycle.RepositoryPortabilityService.Report portability = portabilityService.validateTree(workerWorktreePath, commitSha);
        if (!portability.portable()) {
            try {
                runGitOutput(workerWorktreePath, "update-ref", "-d", "refs/synesis/snapshots/" + snapshotId);
            } catch (IOException ignored) {
                // The portability failure remains the authoritative result.
            }
            throw new IllegalStateException("REPOSITORY_NOT_PORTABLE:" + portability.findings());
        }
        List<String> capabilityDependencies = new ArrayList<>();
        for (CapabilityRequestRecord cap : activeCapabilities) {
            capabilityDependencies.add(cap.handle().value());
        }

        SnapshotProvenance provenance = new SnapshotProvenance(workGroupId,
                laneId,
                authorityLineageId,
                participant,
                bindingIdentity,
                claimEpoch,
                capabilityDependencies,
                handoffLineage,
                claims.stream().map(selector -> selector.kind().name() + ":" + selector.value()).toList(),
                "refs/synesis/snapshots/" + snapshotId,
                integrity(commitSha, materializedPaths),
                artifactManifest.digest());
        return new TaskSnapshotRecord(taskId,
                snapshotId,
                nodeId,
                supervisorId,
                workerId,
                providerSessionId,
                baseCommit,
                commitSha,
                materializedPaths,
                List.copyOf(capabilityDependencies),
                summary,
                System.currentTimeMillis(),
                provenance,
                reviewRequired);
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
        Objects.requireNonNull(worktreePath, "worktreePath");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(completionId, "completionId");
        String ref = "refs/synesis/prepared/" + completionId;
        runGitOutput(worktreePath, "update-ref", ref, snapshot.commitSha());
        String resolved = runGitOutput(worktreePath, "rev-parse", ref);
        if (!resolved.equals(snapshot.commitSha())) {
            throw new IOException("PREPARED_OBJECT_MISMATCH");
        }
        return ref;
    }

    /**
     * Removes a provisional final snapshot ref before durable preparation.
     *
     * @param worktreePath lane worktree
     * @param snapshot     snapshot record
     * @throws IOException if Git cannot remove the ref
     */
    public void removeSnapshotRef(Path worktreePath, TaskSnapshotRecord snapshot) throws IOException {
        runGitOutput(worktreePath, "update-ref", "-d", snapshot.provenance().snapshotRef());
    }

    /**
     * Removes a transaction-owned prepared reference during an authorized completion unwind.
     *
     * @param worktreePath lane worktree
     * @param preparedRef  prepared reference
     * @throws IOException if Git cannot remove the reference
     */
    public void removePreparedRef(Path worktreePath, String preparedRef) throws IOException {
        Objects.requireNonNull(worktreePath, "worktreePath");
        Objects.requireNonNull(preparedRef, "preparedRef");
        if (!preparedRef.startsWith("refs/synesis/prepared/")) {
            throw new IOException("INVALID_PREPARED_REF");
        }
        runGitOutput(worktreePath, "update-ref", "-d", preparedRef);
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
        verifyPreparedRef(worktreePath, preparedRef, expectedCommit);
        runGitOutput(worktreePath, "update-ref", finalRef, expectedCommit);
        verifyPreparedRef(worktreePath, finalRef, expectedCommit);
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
        String resolved = runGitOutput(worktreePath, "rev-parse", preparedRef);
        if (!resolved.equals(expectedCommit)) {
            throw new IOException("PREPARED_OBJECT_MISMATCH");
        }
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
        return runGitOutput(worktreePath, "rev-parse", commit + "^{tree}");
    }
}
