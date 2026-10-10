package org.synesis.workspace.application.agent.workflow;

import static org.synesis.workspace.application.agent.workflow.NextActionSupport.logSuppressed;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.openEventStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.digest.Sha256;

/**
 * Builds the provider-safe collaboration view embedded in next-action responses: participants,
 * intents, groups, grants, snapshots, pending coordination, review candidates and continuation
 * candidates.
 *
 * <p>Key order is part of the wire format. Separate because the review, completion, idle and
 * claim-gate responses all start from this view, and it must not depend on the order in which the
 * orchestrator evaluates its stages.</p>
 */
final class CollaborationView {

    /**
     * Wire value of the workspace marker and of the idle {@code ready} response. Package-private
     * because the local fallback reports the same marker.
     */
    static final String ISOLATED = "isolated";

    private CollaborationView() {
    }

    /**
     * Builds the JSON-safe collaboration discovery and pending-request projection for a request
     * that has no verified binding.
     *
     * <p>The caller's session is matched through the SHA-256 fingerprint of its connection instance
     * id. When no event store exists, or any lookup fails, an empty isolated-workspace projection is
     * returned instead.</p>
     *
     * @param bindingService looks up the caller's provider-session binding
     * @param location project location
     * @param request  the next-action request
     * @return a fresh, mutable discovery map; never {@code null}
     */
    static Map<String, Object> collaborationDetailsForRequest(ProviderSessionBindingService bindingService, ProjectLocation location, NextActionRequest request) {
        try {
            Optional<PredictionEventStore> store = openEventStore(location);
            if (store.isPresent()) {
                String fingerprint = Sha256.hex(request.connectionInstanceId());
                var binding = bindingService.list(location, request.provider()).stream().filter(candidate -> fingerprint.equals(candidate.providerInstanceFingerprint())).findFirst().orElse(null);
                return collaborationDetails(store.get(), binding != null ? binding.sessionId() : "");
            }
        } catch (Exception failure) {
            logSuppressed("collaboration discovery", failure);
        }
        Map<String, Object> result = collaborationDetailsBase(0, List.of(), List.of());
        result.put(NextActionKeys.PENDING_COORDINATION, List.of());
        return result;
    }

    /**
     * Converts collaboration records into a provider-safe next-action payload.
     *
     * @param store     current project projection
     * @param sessionId session of the caller, or blank for an unbound observer
     * @return a fresh, mutable discovery map
     */
    static Map<String, Object> collaborationDetails(PredictionEventStore store, String sessionId) {
        return collaborationDetails(store, sessionId, null, null);
    }

    /**
     * Converts collaboration records into a provider-safe next-action payload, optionally
     * restricting review actions to a set of work groups.
     *
     * @param store             current project projection
     * @param sessionId         session of the caller, or blank for an unbound observer
     * @param reviewGroupFilter work groups to which review actions are restricted, or {@code null}
     *                          for no restriction
     * @return a fresh, mutable discovery map whose key order is part of the wire format
     */
    static Map<String, Object> collaborationDetails(PredictionEventStore store, String sessionId, Set<UUID> reviewGroupFilter) {
        return collaborationDetails(store, sessionId, reviewGroupFilter, null);
    }

    /**
     * Builds the public collaboration view for one caller.
     *
     * @param callerProvider the caller's provider when its exact binding is known; {@code null}
     *                       leaves provider-specific continuation eligibility undecided
     */
    static Map<String, Object> collaborationDetails(PredictionEventStore store, String sessionId, Set<UUID> reviewGroupFilter, String callerProvider) {
        String participantId = sessionId == null || sessionId.isBlank() ? "" : WorkspaceCollaborationService.participantHandle(sessionId);
        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        var activeIntents = collaboration.activeIntents();

        List<Map<String, Object>> intents = activeIntents.stream().map(CollaborationResponseMapper::intentMap).toList();
        Map<String, Object> currentIntent = activeIntents.stream().filter(intent -> intent.participant().equals(participantId)).map(CollaborationResponseMapper::intentMap).findFirst().orElse(null);
        List<Map<String, Object>> participants = collaboration.participants().stream().map(CollaborationResponseMapper::participantMap).toList();
        List<Map<String, Object>> pending = collaboration.requests().stream().filter(request -> request.status() == CoordinationRequest.Status.PENDING)
                .filter(request -> !collaboration.inboxAcknowledged(request.requestId())).filter(request -> participantId.isBlank() || request.target().equals(participantId)).map(CollaborationResponseMapper::requestMap)
                .toList();
        List<Map<String, Object>> enrichedPending = pending.stream().map(request -> AgentReviewActionProjection.enrichPendingRequest(request, store)).toList();

        Map<String, Object> result = collaborationDetailsBase(enrichedPending.size(), participants, intents);
        result.put("groups", workGroups.groups().stream().map(CollaborationResponseMapper::workGroupMap).toList());
        result.put("grants", workGroups.grants().stream().map(CollaborationResponseMapper::laneGrantMap).toList());
        result.put("snapshots", store.taskCompletionProjection().allSnapshots().stream().map(snapshot -> CollaborationResponseMapper.snapshotMap(snapshot, store)).toList());
        result.put("currentParticipant", participantId);
        result.put("currentIntent", currentIntent);
        result.put(NextActionKeys.PENDING_COORDINATION, enrichedPending);
        result.put(NextActionKeys.CONTINUATION_CANDIDATES, RecoveryContinuationProjection.continuationCandidates(store, participantId, callerProvider));

        List<Map<String, Object>> reviewCandidates = AgentReviewActionProjection.reviewCandidates(store);
        result.put("reviewCandidates", reviewCandidates);
        result.put(NextActionKeys.REVIEW_CANDIDATE_SELECTION_REQUIRED, !reviewCandidates.isEmpty());
        result.put("actionableReviewCandidates", AgentReviewActionProjection.actionableReviewCandidates(store, participantId, reviewCandidates));
        if (!reviewCandidates.isEmpty()) {
            result.put("reviewCandidateSelectionInstruction", "Choose an exact candidate explicitly; Synesis does not select one for you.");
        }
        result.put("reviewActions", AgentReviewActionProjection.reviewActions(store, participantId, reviewGroupFilter));
        result.put("claimConflicts", List.of());
        return result;
    }

    /**
     * Creates the common discovery fields shared by populated and isolated workspaces.
     *
     * @param pending      number of pending coordination requests
     * @param participants projected participants
     * @param intents      projected active intents
     * @return a fresh, mutable map holding the four common fields in wire order
     */
    private static Map<String, Object> collaborationDetailsBase(int pending, List<Map<String, Object>> participants, List<Map<String, Object>> intents) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspace", ISOLATED);
        result.put(NextActionKeys.PENDING, pending);
        result.put("participants", participants);
        result.put("intents", intents);
        return result;
    }
}
