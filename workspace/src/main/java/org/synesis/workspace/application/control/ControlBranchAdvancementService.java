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
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Service for comparing, verifying, and fast-forwarding the control branch after integration gate
 * success.
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

    /**
     * Ignores only untracked Synesis/provider administration material that is intentionally created
     * beside the source checkout. Tracked changes and every unrelated path remain integration
     * blockers.
     *
     * @param status porcelain status output
     * @return the status lines of source or unrelated user changes that block advancement
     */
    private static List<String> blockingControlChanges(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        return status.lines().map(String::trim).filter(line -> !line.isBlank()).filter(line -> {
            if (!line.startsWith("?? ")) {
                return true;
            }
            String path = line.substring(3).replace('\\', '/');
            return !(path.equals("AGENTS.md") || path.equals(".synesis") || path.startsWith(".synesis/") || path.equals(".mcp.json") || path.equals(".codex") || path.startsWith(".codex/") || path.equals(".claude")
                    || path.startsWith(".claude/"));
        }).toList();
    }

    /**
     * Lists what the control checkout holds that Synesis did not put there, as {@code git status}
     * reports it. Integration refuses such a checkout, so this is also what an operator is shown.
     *
     * @param controlRoot absolute control project root path
     * @return the blocking status lines; empty when the checkout can be advanced
     * @throws Exception when the checkout's status cannot be read
     */
    public static List<String> foreignChanges(Path controlRoot) throws Exception {
        return blockingControlChanges(GitProcessRunner.run(controlRoot, "status", "--porcelain"));
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
    public AdvancementResult advanceControlBranch(Path controlRoot,
            String attemptId,
            String expectedControlHead,
            String integrationCommitSha,
            List<TaskSnapshotRecord> integratedSnapshots,
            PredictionEventStore store,
            NodeIdentity identity) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(expectedControlHead, "expectedControlHead");
        Objects.requireNonNull(integrationCommitSha, "integrationCommitSha");
        Objects.requireNonNull(integratedSnapshots, "integratedSnapshots");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(identity, "identity");

        try {
            // 1. Verify control checkout is clean
            List<String> foreign = foreignChanges(controlRoot);
            if (!foreign.isEmpty()) {
                return new AdvancementResult(false, false, true, "Control checkout working tree is dirty: " + String.join("\n", foreign));
            }

            // 2. Verify current control HEAD equals expectedControlHead
            String currentHead = GitProcessRunner.run(controlRoot, "rev-parse", "HEAD");
            if (!currentHead.equals(expectedControlHead)) {
                return new AdvancementResult(false, true, false, "Control branch HEAD moved (expected " + expectedControlHead + " but was " + currentHead + ")");
            }

            // 3. Fast-forward merge or update control branch
            GitProcessRunner.run(controlRoot, "merge", "--ff-only", integrationCommitSha);

            // 4. Append CONTROL_BRANCH_ADVANCED, then TASK_INTEGRATED, OWNERSHIP_RELEASED, and SESSION_FINALIZED per lane
            appendControlBranchAdvanced(attemptId, expectedControlHead, integrationCommitSha, integratedSnapshots, store, identity);
            for (TaskSnapshotRecord snap : integratedSnapshots) {
                appendLaneIntegrated(snap, store, identity);
            }

            return new AdvancementResult(true, false, false, "");

        } catch (Exception ex) {
            return new AdvancementResult(false, false, false, "Control branch advancement failed: " + ex.getMessage());
        }
    }

    /**
     * Completes durable integration bookkeeping after a crash that occurred after the control branch
     * advanced but before the event log was finalized. The method never changes Git state; it only
     * records the already observed exact advancement and idempotently finalizes the included lanes.
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
    public AdvancementResult recoverAdvancedControlBranch(Path controlRoot,
            String attemptId,
            String expectedControlHead,
            String integrationCommitSha,
            List<TaskSnapshotRecord> integratedSnapshots,
            PredictionEventStore store,
            NodeIdentity identity) {
        try {
            String currentHead = GitProcessRunner.run(controlRoot, "rev-parse", "HEAD");
            if (!currentHead.equals(integrationCommitSha)) {
                return new AdvancementResult(false, false, false, "CONTROL_HEAD_DOES_NOT_MATCH_INTEGRATION_COMMIT");
            }
            finalizeIntegrationEvents(attemptId, expectedControlHead, integrationCommitSha, integratedSnapshots, store, identity);
            return new AdvancementResult(true, false, false, "");
        } catch (Exception failure) {
            return new AdvancementResult(false, false, false, "RECOVERY_FINALIZATION_FAILED: " + failure.getMessage());
        }
    }

    private void finalizeIntegrationEvents(String attemptId, String expectedControlHead, String integrationCommitSha, List<TaskSnapshotRecord> snapshots, PredictionEventStore store, NodeIdentity identity)
            throws IOException, GeneralSecurityException {
        var projection = store.taskCompletionProjection();
        String recorded = projection.lastControlHeadAdvanced(identity.nodeId());
        boolean branchRecorded = recorded != null && recorded.equals(integrationCommitSha);
        if (!branchRecorded) {
            appendControlBranchAdvanced(attemptId, expectedControlHead, integrationCommitSha, snapshots, store, identity);
        }
        for (TaskSnapshotRecord snap : snapshots) {
            if (projection.taskState(snap.taskId()) != org.synesis.coordination.domain.task.TaskCompletionState.INTEGRATED) {
                appendLaneIntegrated(snap, store, identity);
            }
        }
    }

    private void appendControlBranchAdvanced(String attemptId, String expectedControlHead, String integrationCommitSha, List<TaskSnapshotRecord> snapshots, PredictionEventStore store, NodeIdentity identity)
            throws IOException, GeneralSecurityException {
        IntegrationAttemptPayload payload = new IntegrationAttemptPayload(attemptId,
                store.projectId(),
                snapshots.stream().map(TaskSnapshotRecord::snapshotId).toList(),
                expectedControlHead,
                integrationCommitSha,
                "advanced",
                "");
        store.append(UUID.randomUUID(), PredictionEventType.CONTROL_BRANCH_ADVANCED, identity.nodeId(), payload.encode(), identity);
    }

    /**
     * Records a snapshot integrated outside an ordinary advance: a combine lane's snapshot, once the re-merge that used
     * it is on this copy of {@code main} (ADR-0098 step 5, designer item 22). Writes what an advance writes for each of
     * its lanes: {@code TASK_INTEGRATED}, the lane's ownership released, and {@code SESSION_FINALIZED}.
     *
     * @param snapshot the snapshot
     * @param store    the log, under the caller's append lock
     * @param identity this computer
     * @throws IOException              when an entry is refused
     * @throws GeneralSecurityException when signing fails
     */
    public static void recordIntegrated(TaskSnapshotRecord snapshot, PredictionEventStore store, NodeIdentity identity)
            throws IOException, GeneralSecurityException {
        appendLaneIntegrated(snapshot, store, identity);
    }

    private static void appendLaneIntegrated(TaskSnapshotRecord snap, PredictionEventStore store, NodeIdentity identity) throws IOException, GeneralSecurityException {
        TaskSnapshotPayload payload = new TaskSnapshotPayload(snap.taskId(),
                snap.snapshotId(),
                snap.nodeId(),
                snap.supervisorId(),
                snap.workerId(),
                snap.providerSessionId(),
                snap.baseCommit(),
                snap.commitSha(),
                snap.changedPaths(),
                snap.capabilityDependencies(),
                snap.summary());
        store.append(UUID.randomUUID(), PredictionEventType.TASK_INTEGRATED, identity.nodeId(), payload.encode(), identity);
        releaseExactOwnership(snap, store, identity);
        store.append(UUID.randomUUID(), PredictionEventType.SESSION_FINALIZED, identity.nodeId(), payload.encode(), identity);
    }

    private static void releaseExactOwnership(TaskSnapshotRecord snap, PredictionEventStore store, NodeIdentity identity) throws IOException, GeneralSecurityException {
        for (var entry : store.coordinationProjection().ownerships().entrySet()) {
            OwnershipClaim claim = entry.getValue();
            if (claim.taskId().equals(snap.taskId()) && claim.ownerNodeId().equals(snap.nodeId())) {
                CoordinationCommand relCmd = CoordinationCommand.create(UUID.randomUUID(), store.projectId(), claim.taskId(), PredictionEventType.OWNERSHIP_RELEASED, identity.nodeId(), claim.encoded(), identity);
                store.append(claim.taskId(), PredictionEventType.OWNERSHIP_RELEASED, identity.nodeId(), relCmd.encoded(), identity);
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
    public record AdvancementResult(boolean advanced, boolean stale, boolean dirtyControl, String failureReason) {

        /**
         * Validates non-null failureReason.
         */
        public AdvancementResult {
            Objects.requireNonNull(failureReason, "failureReason");
        }
    }
}
