package org.synesis.workspace.application.agent.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * Where a reviewer lane writes its review notes, and how it is told to end (ADR-0102 item 10).
 *
 * <p>A reviewer lane ends by publishing its notes, as every lane ends by publishing its files. Shown only the
 * ordinary implementation step once their verdict was recorded, reviewers would not invent a file nobody had named,
 * were refused {@code no_change}, and their work groups stayed open (s1-run5, s5-run3, s5-run5). So the path is named
 * when a review is offered, already in the claims of the offered {@code ensure_session}, and named again once the
 * verdict is recorded.</p>
 */
final class ReviewNotes {

    /** Result key of the path a reviewer writes its notes to. */
    static final String PATH = "reviewNotesPath";

    private ReviewNotes() {
    }

    /**
     * The notes path offered with a review of a snapshot.
     *
     * @param snapshotId the snapshot to review
     * @return {@code reviews/<snapshotId>.md}
     */
    static String offeredPath(String snapshotId) {
        return "reviews/" + snapshotId + ".md";
    }

    /**
     * Adds the notes guidance to the ordinary step of a reviewer that has recorded its verdict and owes no other
     * review, while its lane has published nothing.
     *
     * @param response    the resolved answer
     * @param store       current project projection
     * @param participant the caller's participant handle
     * @return the answer, naming the notes path, or as it was
     */
    static AgentResponse onceDecided(AgentResponse response, PredictionEventStore store, String participant) {
        if (response.status() != AgentStatus.READY || response.nextAction() != null || response.result() != null && !(response.result() instanceof Map<?, ?>)) {
            return response;
        }
        return guidance(store, participant).filter(guidance -> guidance.decided).map(guidance -> guided(response, guidance, null)).orElse(response);
    }

    /**
     * Adds the notes guidance to a refused {@code no_change} finish of a reviewer lane: it ends by publishing its notes.
     *
     * @param response    the answer of the {@code no_change} finish
     * @param store       project projection the finish was judged against
     * @param participant the caller's participant handle
     * @return the refusal, naming the notes path, or the answer as it was
     */
    static AgentResponse onNoChangeRefused(AgentResponse response, PredictionEventStore store, String participant) {
        if (response.status() == AgentStatus.COMPLETED || response.result() != null && !(response.result() instanceof Map<?, ?>)) {
            return response;
        }
        return guidance(store, participant).map(guidance -> guided(response, guidance, AgentNextAction.FINISH_LANE)).orElse(response);
    }

    private record Guidance(String path, boolean decided) {
    }

    private static Optional<Guidance> guidance(PredictionEventStore store, String participant) {
        Optional<WorkIntent> lane = store.collaborationProjection().activeIntents().stream()
                .filter(intent -> intent.participant().equals(participant) && intent.role() == WorkIntent.Role.REVIEWER).findFirst();
        if (lane.isEmpty() || store.taskCompletionProjection().findSnapshotForTaskRevision(lane.get().taskId(), lane.get().intentId(), lane.get().version()).isPresent()) {
            return Optional.empty();
        }
        var groups = store.workGroupProjection();
        Optional<String> reviewed = groups.grants().stream().filter(grant -> grant.workGroupId().equals(lane.get().workGroupId()) && grant.targetParticipant().equals(participant))
                .flatMap(grant -> groups.reviewValidationForGrant(grant.grantId()).stream()).map(decision -> decision.snapshotId()).reduce((older, newer) -> newer);
        boolean decided = reviewed.isPresent() && !AgentReviewActionProjection.hasUnresolvedReviewObligation(store, lane.get());
        return Optional.of(new Guidance(path(lane.get().selectors(), reviewed.orElse(null)), decided));
    }

    /**
     * The offered path when the lane claims it; otherwise a path the lane does claim, so the notes are never written
     * where the lane may not write.
     */
    private static String path(List<ResourceSelector> claims, String snapshotId) {
        String name = snapshotId == null ? "review" : snapshotId;
        String offered = offeredPath(name);
        if (claims.stream().anyMatch(claim -> claim.overlaps(ResourceSelector.pathExact(offered)))) {
            return offered;
        }
        return claims.stream().filter(claim -> claim.kind() == ResourceSelector.Kind.PATH_EXACT).map(ResourceSelector::value).findFirst()
                .or(() -> claims.stream().findFirst().map(subtree -> subtree.value() + "/" + name + ".md")).orElse(offered);
    }

    private static AgentResponse guided(AgentResponse response, Guidance guidance, AgentNextAction nextAction) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (response.result() instanceof Map<?, ?> given) {
            given.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        result.put(PATH, guidance.path);
        result.put("instruction", (guidance.decided ? "You have recorded your review verdict. " : "Record your review verdict first; get_next_action projects it. ")
                + "A reviewer lane ends by publishing its review notes, not with no_change: write them to " + guidance.path
                + ", then call get_next_action with completionRequested true and run the finish_lane it projects. "
                + "A one-line note is enough when you have nothing to add, for example that there was nothing for you to review and it is fine.");
        return new AgentResponse(response.status(), response.reason(), nextAction == null ? response.nextAction() : nextAction, result);
    }
}
