package org.synesis.workspace.application.agent.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;

/**
 * What a party to a review dispute is told (ADR-0098 step 7, designer items 6 and 7; ADR-0100 row 4): two reviewers'
 * verdicts on one snapshot differ, and the snapshot counts as rejected until they agree. No third agent decides: the
 * reviewers and the author settle it by messages, and a reviewer that changes its mind records a new verdict on its
 * grant, which replaces its own.
 */
public final class DisputeNotice {

    private DisputeNotice() {
    }

    /**
     * The disputes a participant is a party to now: snapshots whose reviewers disagree, that it reviewed or wrote.
     *
     * @param store       current projection
     * @param participant the participant handle
     * @return one description per dispute, empty when there is none
     */
    public static List<Map<String, Object>> forParticipant(PredictionEventStore store, String participant) {
        var completion = store.taskCompletionProjection();
        List<Map<String, Object>> disputes = new ArrayList<>();
        for (SnapshotId id : completion.disputedSnapshots()) {
            if (completion.snapshotState(id.value()).orElse(null) != TaskCompletionState.REVIEW_DISPUTED) {
                continue;
            }
            Optional<TaskSnapshotRecord> snapshot = completion.findSnapshotById(id.value());
            Map<String, String> verdicts = new java.util.TreeMap<>(completion.verdicts(id));
            if (snapshot.isEmpty()) {
                continue;
            }
            String author = snapshot.get().provenance().participant();
            boolean reviewer = verdicts.containsKey(participant);
            if (!reviewer && !author.equals(participant)) {
                continue;
            }
            Map<String, Object> dispute = new LinkedHashMap<>();
            dispute.put("snapshotId", id.value());
            dispute.put("author", author);
            dispute.put("verdicts", java.util.Collections.unmodifiableMap(verdicts));
            List<String> others = new ArrayList<>(verdicts.keySet());
            others.add(author);
            others.remove(participant);
            dispute.put("messageTo", others.stream().distinct().toList());
            if (reviewer) {
                dispute.put("yourVerdict", verdicts.get(participant));
                grantFor(store, participant, id.value()).ifPresent(grant -> dispute.put("newVerdict", Map.of("tool", "respond_coordination", "kind",
                        "review_validation", "payload", Map.of("grantId", grant.grantId().toString(), "snapshotId", id.value(), "intentId",
                                grant.targetIntentId().toString(), "claimEpoch", grant.claimEpoch(), "result", "accepted or rejected"))));
                // Messages are sent from a lane (ADR-0093); a reviewer without one cannot discuss, and its verdict still counts.
                String discuss = WorkspaceCollaborationService.laneOf(store, participant).isPresent()
                        ? "Discuss it with them and the author (send_message to messageTo). "
                        : "You hold no lane, so you cannot send messages; reconsider your verdict on your own. ";
                dispute.put("instruction", "Your verdict on snapshot " + id.value() + " differs from another reviewer's, so the snapshot counts as rejected "
                        + "until you agree. " + discuss + "If you change your mind, record your new verdict with newVerdict (a rejection needs a reason); "
                        + "it replaces yours. Nobody else decides.");
            } else {
                dispute.put("instruction", "The reviewers of your snapshot " + id.value() + " disagree, so it counts as rejected until they agree. "
                        + "You may explain your work to them (send_message to messageTo), or revise it and finish again.");
            }
            disputes.add(dispute);
        }
        return List.copyOf(disputes);
    }

    /**
     * Whether a participant recorded a verdict on a snapshot whose review is not settled yet: disputed, or under review
     * or accepted but not integrated while its work group is active. Another reviewer may still disagree with it, and
     * only this participant's session may then replace it, so a run keeps that session. An accepted snapshot of a
     * group no longer active will never be integrated, and keeps no session.
     *
     * @param store       current projection
     * @param participant the participant handle
     * @return whether such a verdict exists
     */
    public static boolean verdictUnsettled(PredictionEventStore store, String participant) {
        var completion = store.taskCompletionProjection();
        return store.workGroupProjection().grants().stream().filter(grant -> grant.targetParticipant().equals(participant))
                .map(grant -> store.workGroupProjection().reviewValidationForGrant(grant.grantId()))
                .flatMap(Optional::stream)
                .anyMatch(review -> {
                    TaskCompletionState state = completion.snapshotState(review.snapshotId()).orElse(null);
                    boolean groupActive = store.workGroupProjection().group(review.workGroupId())
                            .map(group -> group.status() == org.synesis.coordination.domain.collaboration.WorkGroup.Status.ACTIVE).orElse(false);
                    return state == TaskCompletionState.REVIEW_DISPUTED
                            || groupActive && (state == TaskCompletionState.REVIEW_PENDING || state == TaskCompletionState.REVIEW_ACCEPTED);
                });
    }

    /**
     * The disputes a participant is a party to, each as one key per snapshot and set of verdicts: a dispute whose
     * verdicts change is a new one to wake for.
     *
     * @param store       current projection
     * @param participant the participant handle
     * @return the keys
     */
    public static List<String> stateKeys(PredictionEventStore store, String participant) {
        var completion = store.taskCompletionProjection();
        return forParticipant(store, participant).stream().map(dispute -> (String) dispute.get("snapshotId"))
                .map(id -> id + " " + new java.util.TreeMap<>(completion.verdicts(new SnapshotId(id)))).toList();
    }

    private static Optional<LaneGrant> grantFor(PredictionEventStore store, String participant, String snapshotId) {
        var workGroups = store.workGroupProjection();
        return workGroups.grants().stream().filter(grant -> grant.targetParticipant().equals(participant))
                .filter(grant -> workGroups.reviewValidationForGrant(grant.grantId()).map(review -> review.snapshotId().equals(snapshotId)).orElse(false))
                .findFirst();
    }
}
