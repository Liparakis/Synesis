package org.synesis.workspace.application.control;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Service for comparing, verifying, and fast-forwarding the control branch after integration gate success.
 *
 * <p>Requirements before control branch mutation:
 * <ol>
 *   <li>Control checkout working tree must be clean.</li>
 *   <li>Control branch HEAD must equal expected control HEAD SHA.</li>
 *   <li>Fast-forward update only (no forced checkout or non-fast-forward merge).</li>
 *   <li>Append durable {@code CONTROL_BRANCH_ADVANCED}, {@code TASK_INTEGRATED},
 *       {@code OWNERSHIP_RELEASED}, and {@code SESSION_FINALIZED} events.</li>
 * </ol>
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class ControlBranchAdvancementService {

    /**
     * Creates a control branch advancement service.
     */
    public ControlBranchAdvancementService() {
    }

    private static String runGitOutput(Path workdir, String... args) throws IOException {
        return org.synesis.workspace.lifecycle.GitProcessRunner.run(workdir, args)
                .trim();
    }

    private static void runGit(Path workdir, String... args) throws IOException {
        runGitOutput(workdir, args);
    }

    /**
     * Ignores only untracked Synesis/provider administration material that is
     * intentionally created beside the source checkout. Tracked changes and
     * every unrelated path remain integration blockers.
     *
     * @param status porcelain status output
     * @return whether source or unrelated user changes block advancement
     */
    private static boolean hasBlockingControlChanges(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return status.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .anyMatch(line -> {
                    if (!line.startsWith("?? ")) {
                        return true;
                    }
                    String path = line.substring(3)
                            .replace('\\', '/');
                    return !(path.equals("AGENTS.md") || path.equals(".synesis") || path.startsWith(".synesis/")
                            || path.equals(".mcp.json")
                            || path.equals(".codex") || path.startsWith(".codex/")
                            || path.equals(".claude") || path.startsWith(".claude/")
                            || path.equals(".agents") || path.startsWith(".agents/"));
                });
    }

    /**
     * Fast-forwards the control branch to the verified integration commit SHA.
     *
     * @param controlRoot          absolute control project root path
     * @param attemptId            integration attempt ID
     * @param expectedControlHead  expected control HEAD SHA before integration
     * @param integrationCommitSha verified integration commit SHA
     * @param integratedSnapshots  ordered list of task snapshots included in this integration
     * @param store                prediction event store
     * @param identity             node signing identity
     * @return advancement result
     */
    public AdvancementResult advanceControlBranch(
            Path controlRoot,
            String attemptId,
            String expectedControlHead,
            String integrationCommitSha,
            List<TaskSnapshotRecord> integratedSnapshots,
            PredictionEventStore store,
            NodeIdentity identity
    ) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(expectedControlHead, "expectedControlHead");
        Objects.requireNonNull(integrationCommitSha, "integrationCommitSha");
        Objects.requireNonNull(integratedSnapshots, "integratedSnapshots");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(identity, "identity");

        try {
            // 1. Verify control checkout is clean
            String status = runGitOutput(controlRoot, "status", "--porcelain");
            if (hasBlockingControlChanges(status)) {
                return new AdvancementResult(false, false, true, "Control checkout working tree is dirty: " + status);
            }

            // 2. Verify current control HEAD equals expectedControlHead
            String currentHead = runGitOutput(controlRoot, "rev-parse", "HEAD");
            if (!currentHead.equals(expectedControlHead)) {
                return new AdvancementResult(false, true, false,
                        "Control branch HEAD moved (expected " + expectedControlHead + " but was " + currentHead + ")");
            }

            // 3. Fast-forward merge or update control branch
            runGit(controlRoot, "merge", "--ff-only", integrationCommitSha);

            // 4. Append CONTROL_BRANCH_ADVANCED event
            IntegrationAttemptPayload advPayload = new IntegrationAttemptPayload(
                    attemptId, store.projectId(),
                    integratedSnapshots.stream()
                            .map(TaskSnapshotRecord::snapshotId)
                            .toList(),
                    expectedControlHead, integrationCommitSha, "advanced", "");
            store.append(UUID.randomUUID(), PredictionEventType.CONTROL_BRANCH_ADVANCED,
                    identity.nodeId(), advPayload.encode(), identity);

            // 5. Append TASK_INTEGRATED, OWNERSHIP_RELEASED, and SESSION_FINALIZED events
            for (TaskSnapshotRecord snap : integratedSnapshots) {
                TaskSnapshotPayload snapPayload = new TaskSnapshotPayload(
                        snap.taskId(), snap.snapshotId(), snap.nodeId(), snap.supervisorId(),
                        snap.workerId(), snap.providerSessionId(), snap.baseCommit(), snap.commitSha(),
                        snap.changedPaths(), snap.capabilityDependencies(), snap.summary());

                store.append(UUID.randomUUID(), PredictionEventType.TASK_INTEGRATED,
                        identity.nodeId(), snapPayload.encode(), identity);

                // Release semantic ownership if claim exists
                var ownerships = store.coordinationProjection()
                        .ownerships();
                for (var entry : ownerships.entrySet()) {
                    OwnershipClaim claim = entry.getValue();
                    if (claim.taskId()
                            .equals(snap.taskId()) && claim.ownerNodeId()
                            .equals(snap.nodeId())) {
                        CoordinationCommand relCmd = CoordinationCommand.create(
                                UUID.randomUUID(), store.projectId(), claim.taskId(),
                                PredictionEventType.OWNERSHIP_RELEASED, identity.nodeId(),
                                claim.encoded(), identity);
                        store.append(claim.taskId(), PredictionEventType.OWNERSHIP_RELEASED,
                                identity.nodeId(), relCmd.encoded(), identity);
                    }
                }

                store.append(UUID.randomUUID(), PredictionEventType.SESSION_FINALIZED,
                        identity.nodeId(), snapPayload.encode(), identity);
            }

            return new AdvancementResult(true, false, false, "");

        } catch (Exception ex) {
            return new AdvancementResult(false, false, false, "Control branch advancement failed: " + ex.getMessage());
        }
    }

    /**
     * Completes durable integration bookkeeping after a crash that occurred
     * after the control branch advanced but before the event log was finalized.
     * The method never changes Git state; it only records the already observed
     * exact advancement and idempotently finalizes the included lanes.
     *
     * @param controlRoot          control project root
     * @param attemptId            integration attempt identifier
     * @param expectedControlHead  head recorded before integration
     * @param integrationCommitSha commit already observed at control HEAD
     * @param integratedSnapshots  snapshots included in the attempt
     * @param store                prediction event store
     * @param identity             signing identity
     * @return advancement result
     */
    public AdvancementResult recoverAdvancedControlBranch(
            Path controlRoot,
            String attemptId,
            String expectedControlHead,
            String integrationCommitSha,
            List<TaskSnapshotRecord> integratedSnapshots,
            PredictionEventStore store,
            NodeIdentity identity) {
        try {
            String currentHead = runGitOutput(controlRoot, "rev-parse", "HEAD");
            if (!currentHead.equals(integrationCommitSha)) {
                return new AdvancementResult(false, false, false,
                        "CONTROL_HEAD_DOES_NOT_MATCH_INTEGRATION_COMMIT");
            }
            finalizeIntegrationEvents(attemptId, expectedControlHead, integrationCommitSha,
                    integratedSnapshots, store, identity);
            return new AdvancementResult(true, false, false, "");
        } catch (Exception failure) {
            return new AdvancementResult(false, false, false,
                    "RECOVERY_FINALIZATION_FAILED: " + failure.getMessage());
        }
    }

    private void finalizeIntegrationEvents(String attemptId, String expectedControlHead,
            String integrationCommitSha, List<TaskSnapshotRecord> snapshots,
            PredictionEventStore store, NodeIdentity identity) throws IOException, GeneralSecurityException {
        var projection = store.taskCompletionProjection();
        boolean branchRecorded = projection.lastControlHeadAdvanced() != null
                && projection.lastControlHeadAdvanced()
                .equals(integrationCommitSha);
        if (!branchRecorded) {
            IntegrationAttemptPayload payload = new IntegrationAttemptPayload(
                    attemptId,
                    store.projectId(),
                    snapshots.stream()
                            .map(TaskSnapshotRecord::snapshotId)
                            .toList(),
                    expectedControlHead,
                    integrationCommitSha,
                    "advanced",
                    "");
            store.append(UUID.randomUUID(), PredictionEventType.CONTROL_BRANCH_ADVANCED,
                    identity.nodeId(), payload.encode(), identity);
        }
        for (TaskSnapshotRecord snap : snapshots) {
            if (projection.taskState(snap.taskId())
                    != org.synesis.coordination.domain.task.TaskCompletionState.INTEGRATED) {
                TaskSnapshotPayload payload = new TaskSnapshotPayload(
                        snap.taskId(), snap.snapshotId(), snap.nodeId(), snap.supervisorId(), snap.workerId(),
                        snap.providerSessionId(), snap.baseCommit(), snap.commitSha(), snap.changedPaths(),
                        snap.capabilityDependencies(), snap.summary());
                store.append(UUID.randomUUID(), PredictionEventType.TASK_INTEGRATED,
                        identity.nodeId(), payload.encode(), identity);
                releaseExactOwnership(snap, store, identity);
                store.append(UUID.randomUUID(), PredictionEventType.SESSION_FINALIZED,
                        identity.nodeId(), payload.encode(), identity);
            }
        }
    }

    private void releaseExactOwnership(TaskSnapshotRecord snap, PredictionEventStore store,
            NodeIdentity identity) throws IOException, GeneralSecurityException {
        for (var entry : store.coordinationProjection()
                .ownerships()
                .entrySet()) {
            OwnershipClaim claim = entry.getValue();
            if (claim.taskId()
                    .equals(snap.taskId()) && claim.ownerNodeId()
                    .equals(snap.nodeId())) {
                CoordinationCommand relCmd = CoordinationCommand.create(
                        UUID.randomUUID(), store.projectId(), claim.taskId(),
                        PredictionEventType.OWNERSHIP_RELEASED, identity.nodeId(), claim.encoded(), identity);
                store.append(claim.taskId(), PredictionEventType.OWNERSHIP_RELEASED,
                        identity.nodeId(), relCmd.encoded(), identity);
            }
        }
    }

    /**
     * Result of control branch advancement operation.
     *
     * @param advanced      true if control branch fast-forwarded successfully
     * @param stale         true if control branch moved before advancement
     * @param dirtyControl  true if control checkout working tree is dirty
     * @param failureReason explanation when advanced is false
     */
    public record AdvancementResult(
            boolean advanced,
            boolean stale,
            boolean dirtyControl,
            String failureReason
    ) {

        /**
         * Validates non-null failureReason.
         */
        public AdvancementResult {
            Objects.requireNonNull(failureReason, "failureReason");
        }
    }
}
