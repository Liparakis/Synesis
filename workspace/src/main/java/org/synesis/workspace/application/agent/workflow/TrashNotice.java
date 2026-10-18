package org.synesis.workspace.application.agent.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.trash.TrashProjection;
import org.synesis.coordination.persistence.PredictionEventStore;

/**
 * What an agent is told when its work left its hands (ADR-0098 step 7, designer items 12 and 14): another computer
 * took its lane over while its computer was away ({@code laneTakenOver}), or its work went to the trash
 * ({@code trashedWork}). Its claims ended with the lane; what it had done is kept and a person may restore it.
 */
final class TrashNotice {

    private TrashNotice() {
    }

    /**
     * The lane of this participant that another computer's agent took over, if any.
     *
     * @param store       current projection
     * @param participant the caller
     * @return a description, or empty
     */
    static Optional<Map<String, Object>> laneTakenOver(PredictionEventStore store, String participant) {
        // Whether or not the taker's lane is still open: it may have finished before this computer came back.
        for (Map.Entry<WorkIntent, WorkIntent> takeover : store.collaborationProjection().takenOver()) {
            WorkIntent taken = takeover.getKey();
            WorkIntent successor = takeover.getValue();
            if (taken.participant().equals(participant)) {
                Map<String, Object> notice = new LinkedHashMap<>();
                notice.put("laneId", taken.intentId().toString());
                notice.put("claimEpoch", taken.version());
                notice.put("takenOverBy", successor.participant());
                notice.put("successorIntentId", successor.intentId().toString());
                notice.put("instruction", "Another computer's agent took over your lane while your computer was away. Stop working on it: its claims "
                        + "ended with it, and nothing you publish for it counts. Your worktree is kept in the trash, where a person may restore it. "
                        + "Close your session, or ask for new work with get_next_action.");
                return Optional.of(notice);
            }
        }
        return Optional.empty();
    }

    /**
     * The work of this participant's lanes that is in the trash and not emptied.
     *
     * @param store       current projection
     * @param participant the caller
     * @return one description per piece of work, oldest first
     */
    static List<Map<String, Object>> trashedWork(PredictionEventStore store, String participant) {
        List<Map<String, Object>> work = new ArrayList<>();
        for (TrashProjection.Item item : store.trashProjection().items()) {
            boolean own = store.collaborationProjection().lane(item.trashed().laneId()).map(lane -> lane.participant().equals(participant)).orElse(false);
            if (own && !item.emptied()) {
                Map<String, Object> described = new LinkedHashMap<>();
                described.put("laneId", item.trashed().laneId().toString());
                described.put("claimEpoch", item.trashed().epoch());
                described.put("reason", item.trashed().reason());
                described.put("commit", item.trashed().commit());
                described.put("instruction", "This work of yours is in the trash (" + item.trashed().reason() + "): it no longer counts toward its "
                        + "work group. Do not publish it again; a person may restore it in a work group of its own.");
                work.add(described);
            }
        }
        return List.copyOf(work);
    }
}
