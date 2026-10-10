package org.synesis.workspace.application.task;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;

/**
 * Everything {@link TaskSnapshotService#createSnapshot} needs to capture one lane's snapshot.
 *
 * @param taskId             task identity
 * @param nodeId             publisher node identity
 * @param supervisorId       publisher supervisor identity
 * @param workerId           publisher worker identity
 * @param providerSessionId  exact provider session binding
 * @param workerWorktreePath assigned worker worktree
 * @param controlRoot        project control root
 * @param summary            immutable snapshot summary
 * @param existing           existing snapshot for this exact task revision, which is returned
 *                           unchanged instead of resnapshotting the mutable lane
 * @param activeCapabilities capability dependencies retained by the snapshot
 * @param claimScope         claims that decide which changed paths are materialized
 * @param lane               logical lane provenance recorded in the snapshot
 * @param reviewRequired     whether independent review gates integration
 * @param laneBaseCommit     server-recorded source baseline, or {@code null} to derive it
 * @param expectedTreeHash   projected Git tree hash to revalidate before capture, or {@code null};
 *                           a non-null value also materializes a clean lane so capability
 *                           publication gets an identity that includes the lane and claim epoch
 * @since 1.0
 */
public record SnapshotRequest(UUID taskId,
        String nodeId,
        String supervisorId,
        String workerId,
        String providerSessionId,
        Path workerWorktreePath,
        Path controlRoot,
        String summary,
        Optional<TaskSnapshotRecord> existing,
        List<CapabilityRequestRecord> activeCapabilities,
        ClaimScope claimScope,
        Lane lane,
        boolean reviewRequired,
        String laneBaseCommit,
        String expectedTreeHash) {

    /**
     * Validates the required fields.
     */
    public SnapshotRequest {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(supervisorId, "supervisorId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(providerSessionId, "providerSessionId");
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(activeCapabilities, "activeCapabilities");
        Objects.requireNonNull(claimScope, "claimScope");
        Objects.requireNonNull(lane, "lane");
    }

    /**
     * Describes a task that has no logical lane: the task is its own work group and lane, the
     * node is the participant, no claims apply, and nothing is fenced.
     *
     * @param taskId             task identity
     * @param nodeId             publisher node identity
     * @param supervisorId       publisher supervisor identity
     * @param workerId           publisher worker identity
     * @param providerSessionId  exact provider session binding
     * @param workerWorktreePath assigned worker worktree
     * @param controlRoot        project control root
     * @param summary            immutable snapshot summary
     * @param existing           existing snapshot for this task, if any
     * @param activeCapabilities capability dependencies retained by the snapshot
     * @return the request
     */
    public static SnapshotRequest forTask(UUID taskId, String nodeId, String supervisorId, String workerId,
            String providerSessionId, Path workerWorktreePath, Path controlRoot, String summary,
            Optional<TaskSnapshotRecord> existing, List<CapabilityRequestRecord> activeCapabilities) {
        return new SnapshotRequest(taskId, nodeId, supervisorId, workerId, providerSessionId, workerWorktreePath,
                controlRoot, summary, existing, activeCapabilities, ClaimScope.unscoped(List.of()),
                Lane.of(taskId, taskId, nodeId, providerSessionId, 1, List.of()), false, null, null);
    }

    /**
     * Claims deciding which changed source paths are materialized into the snapshot.
     *
     * <p>Foreign paths are preserved in the worker worktree for their lawful owners but are not
     * materialized into this lane's snapshot. When {@code scopeToClaims} is false, unknown source
     * paths still cause a fail-closed rejection.
     *
     * @param claims        source selectors owned by this lane
     * @param foreignClaims selectors owned by other active participants
     * @param scopeToClaims whether snapshot paths must be covered by this lane's claims
     */
    public record ClaimScope(List<ResourceSelector> claims, List<ResourceSelector> foreignClaims, boolean scopeToClaims) {

        /**
         * Validates the claim lists.
         */
        public ClaimScope {
            Objects.requireNonNull(claims, "claims");
            Objects.requireNonNull(foreignClaims, "foreignClaims");
        }

        /**
         * Records lane claims without scoping materialization to them and without foreign claims.
         *
         * @param claims source selectors owned by this lane
         * @return the claim scope
         */
        public static ClaimScope unscoped(List<ResourceSelector> claims) {
            return new ClaimScope(claims, List.of(), false);
        }
    }

    /**
     * Logical-lane provenance recorded in the snapshot.
     *
     * @param workGroupId        logical work-group identifier
     * @param laneId             active mutation-lane intent identifier
     * @param participant        exact participant handle
     * @param bindingIdentity    exact provider binding identity
     * @param claimEpoch         active intent epoch
     * @param authorityLineageId authority lineage of the active intent
     * @param handoffLineage     durable handoff references
     */
    public record Lane(UUID workGroupId,
            UUID laneId,
            String participant,
            String bindingIdentity,
            long claimEpoch,
            UUID authorityLineageId,
            List<String> handoffLineage) {

        /**
         * Creates a lane with the default authority lineage of its lane identifier.
         *
         * @param workGroupId     logical work-group identifier
         * @param laneId          active mutation-lane intent identifier
         * @param participant     exact participant handle
         * @param bindingIdentity exact provider binding identity
         * @param claimEpoch      active intent epoch
         * @param handoffLineage  durable handoff references
         * @return the lane
         */
        public static Lane of(UUID workGroupId, UUID laneId, String participant, String bindingIdentity, long claimEpoch,
                List<String> handoffLineage) {
            return new Lane(workGroupId, laneId, participant, bindingIdentity, claimEpoch,
                    WorkIntent.defaultAuthorityLineage(laneId), handoffLineage);
        }
    }
}
