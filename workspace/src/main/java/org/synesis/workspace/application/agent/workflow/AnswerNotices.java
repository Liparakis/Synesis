package org.synesis.workspace.application.agent.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.synesis.coordination.persistence.PredictionEventStore;

/**
 * What every answer to an agent carries while it applies, whatever the answer is about (ADR-0098 step 7): a review
 * dispute it is a party to ({@link DisputeNotice}), its lane taken over by another computer, and its work in the trash
 * ({@link TrashNotice}). A reviewer whose lane has ended, and an agent whose lane was taken over, still ask for their
 * next action, so they learn it there.
 */
final class AnswerNotices {

    private AnswerNotices() {
    }

    /**
     * The notices for one participant, by the key each goes under in an answer.
     *
     * @param store       current projection
     * @param participant the caller
     * @return {@code reviewDispute}, {@code laneTakenOver} and {@code trashedWork} where they apply; empty when none does
     */
    static Map<String, Object> forParticipant(PredictionEventStore store, String participant) {
        Map<String, Object> notices = new LinkedHashMap<>();
        List<Map<String, Object>> disputes = DisputeNotice.forParticipant(store, participant);
        if (!disputes.isEmpty()) {
            notices.put("reviewDispute", disputes);
        }
        TrashNotice.laneTakenOver(store, participant).ifPresent(notice -> notices.put("laneTakenOver", notice));
        List<Map<String, Object>> trashed = TrashNotice.trashedWork(store, participant);
        if (!trashed.isEmpty()) {
            notices.put("trashedWork", trashed);
        }
        return notices;
    }
}
