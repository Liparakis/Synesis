package org.synesis.workspace.application.collaboration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;

/**
 * The repair lane of a snapshot that could not be merged, offered to an idle agent (ADR-0098 step 5 item 54).
 *
 * <p>The rules are those of the repair lane (ADR-0100): one source lane, so the first taker's {@code REPAIR_LANE_CREATED}
 * ends the source and a second taker is refused under the project append lock ({@code RepairLaneTransfer}); a taker
 * other than the author; and any computer may write the entry, which acts only for its own new participant
 * ({@code ParticipantAttribution}). The offer is limited to the computer of the author, which recorded the conflict
 * and merges its lanes, so one computer's agents race for it, not two.</p>
 */
public final class RepairLaneOffer {

    private RepairLaneOffer() {
    }

    /**
     * The snapshots an agent may take the repair of: conflicted, their source lane still open at the epoch that
     * published them, written by an agent of this computer other than this one.
     *
     * @param store        current projection
     * @param participants the agent's sessions' participant handles
     * @param node         this computer
     * @return the snapshots, oldest first; none for an agent that holds a lane (a session holds one lane)
     */
    public static List<TaskSnapshotRecord> offeredTo(PredictionEventStore store, Collection<String> participants, String node) {
        var collaboration = store.collaborationProjection();
        if (participants.isEmpty() || collaboration.activeIntents().stream().anyMatch(lane -> participants.contains(lane.participant()))) {
            return List.of();
        }
        var completion = store.taskCompletionProjection();
        return completion.allSnapshots().stream()
                .filter(snapshot -> completion.taskState(snapshot.taskId()) == TaskCompletionState.REPAIR_REQUIRED)
                .filter(snapshot -> !participants.contains(snapshot.provenance().participant()))
                .filter(snapshot -> collaboration.introducingNode(snapshot.provenance().participantIdentity()).map(node::equals).orElse(false))
                .filter(snapshot -> collaboration.intent(snapshot.provenance().laneId())
                        .filter(source -> source.status() == WorkIntent.Status.ANNOUNCED && source.version() == snapshot.provenance().claimEpoch()).isPresent())
                .toList();
    }

    /**
     * Describes a repair as an agent's next action offers it: the snapshot, its author, and the {@code ensure_session}
     * arguments that take the lane, the same call a combine lane is taken with.
     *
     * @param snapshot the conflicted snapshot
     * @return the description
     */
    public static Map<String, Object> describe(TaskSnapshotRecord snapshot) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("snapshotId", snapshot.snapshotId());
        described.put("author", snapshot.provenance().participant());
        described.put("files", snapshot.changedPaths());
        described.put("ensureSession", Map.of("task", Map.of("repairIntentId", snapshot.provenance().laneId().toString(), "repairSnapshotId", snapshot.snapshotId())));
        return described;
    }
}
