package org.synesis.workspace.application.integration;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.control.ControlBranchAdvancementService;

/**
 * Keeps the operator request for a control checkout holding changes Synesis did not make in step
 * with the checkout itself (ADR-0091).
 *
 * <p>The request is raised from what {@code git status} shows and closed from what it shows. No
 * one's statement that the checkout was cleaned is taken for it. Synesis does not move, adopt or
 * delete the files: it cannot tell an agent's debris from the user's work.</p>
 */
final class ControlCheckoutGuard {

    private ControlCheckoutGuard() {
    }

    /**
     * Raises the request when the checkout holds foreign changes and none is open, and closes it
     * when the checkout is clean. The caller holds the project append lock.
     *
     * @param controlRoot absolute control project root path
     * @param store       project event store, current under the lock
     * @param identity    node signing identity
     * @return the request that stops integration now, or empty when nothing does
     */
    static Optional<OperatorRequest> reconcile(Path controlRoot, PredictionEventStore store, NodeIdentity identity) {
        Optional<OperatorRequest> open = store.operatorRequestProjection().open(OperatorRequest.CONTROL_CHECKOUT_FOREIGN_CHANGES);
        List<String> foreign;
        try {
            foreign = ControlBranchAdvancementService.foreignChanges(controlRoot);
        } catch (Exception unreadable) {
            // The checkout cannot be read, so nothing new is known about it. Advancement makes
            // its own check and refuses on its own evidence.
            return open;
        }
        if (foreign.isEmpty() == open.isEmpty()) {
            return open;
        }
        try {
            if (open.isPresent()) {
                store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_CLOSED, identity.nodeId(),
                        OperatorRequest.encodeClosed(open.get().id(), "The control checkout holds no changes Synesis did not make."), identity);
                return Optional.empty();
            }
            OperatorRequest request = OperatorRequest.raised("opr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    OperatorRequest.CONTROL_CHECKOUT_FOREIGN_CHANGES,
                    "The project checkout at " + controlRoot + " holds changes Synesis did not make, so nothing can be integrated into it. "
                            + "Commit them or remove them there. This request closes when the checkout is clean, and the waiting work is integrated then.",
                    String.join("\n", foreign));
            store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, identity.nodeId(), request.encodeRaised(), identity);
            return Optional.of(request);
        } catch (Exception notRecorded) {
            // The log could not take the event. What stops integration is the checkout, not the
            // record, so the answer follows the checkout and the next pass records it.
            return foreign.isEmpty() ? Optional.empty() : open;
        }
    }

    /**
     * Answers a caller whose step needs integration while the request is open.
     *
     * @param request the open request
     * @return a {@code BLOCKED} response carrying the request
     */
    static AgentResponse blocked(OperatorRequest request) {
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("id", request.id());
        shown.put("condition", request.condition());
        shown.put("summary", request.summary());
        shown.put("evidence", request.evidence().lines().toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operatorRequest", shown);
        result.put("instruction", "Integration is stopped until an operator clears this request. Do not change, move or delete the files it names: they are in the project checkout and may be the user's own work. "
                + "Work you have published is kept and is integrated when the request closes. If this lane has nothing else to do, stop here and do not poll.");
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.OPERATOR_REQUIRED, AgentNextAction.REQUEST_HUMAN_HELP, result);
    }
}
