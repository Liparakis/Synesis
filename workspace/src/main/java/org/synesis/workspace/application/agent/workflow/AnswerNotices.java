package org.synesis.workspace.application.agent.workflow;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.lifecycle.lease.SessionSuccessions;

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
     * <p>A lane taken over, and work trashed, belong to the agent and not to one session of it: a session that replaced
     * the one that held the lane ({@link SessionSuccessions}) is told of both, or the agent that a stale workspace sends
     * to a new session would never learn to stop. A lineage that cannot be read tells nothing beyond the session itself;
     * the notice grants nothing, so a missing one only withholds information.</p>
     *
     * @param store       current projection
     * @param controlRoot this computer's checkout, where the session lineage is recorded
     * @param participant the caller
     * @return {@code reviewDispute}, {@code laneTakenOver} and {@code trashedWork} where they apply; empty when none does
     */
    static Map<String, Object> forParticipant(PredictionEventStore store, Path controlRoot, String participant) {
        Map<String, Object> notices = new LinkedHashMap<>();
        List<Map<String, Object>> disputes = DisputeNotice.forParticipant(store, participant);
        if (!disputes.isEmpty()) {
            notices.put("reviewDispute", disputes);
        }
        List<Map<String, Object>> trashed = new ArrayList<>();
        for (String session : sessionsUpTo(controlRoot, participant)) {
            if (!notices.containsKey("laneTakenOver")) {
                TrashNotice.laneTakenOver(store, session).ifPresent(notice -> notices.put("laneTakenOver", notice));
            }
            trashed.addAll(TrashNotice.trashedWork(store, session));
        }
        if (!trashed.isEmpty()) {
            notices.put("trashedWork", trashed);
        }
        return notices;
    }

    private static List<String> sessionsUpTo(Path controlRoot, String participant) {
        Map<String, String> replaced = new HashMap<>();
        SessionSuccessions.predecessors(controlRoot).ifPresent(lineage -> lineage.forEach((successor, predecessor) -> replaced.put(
                WorkspaceCollaborationService.participantHandle(successor), WorkspaceCollaborationService.participantHandle(predecessor))));
        List<String> sessions = new ArrayList<>();
        for (String session = participant; session != null && !sessions.contains(session); session = replaced.get(session)) {
            sessions.add(session);
        }
        return sessions;
    }
}
