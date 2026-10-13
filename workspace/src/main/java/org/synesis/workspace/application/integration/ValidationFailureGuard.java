package org.synesis.workspace.application.integration;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.infrastructure.diagnostics.LocalDiagnostics;

/**
 * Keeps the operator request for a validation command that fails with no lane left to answer for
 * it (ADR-0091).
 *
 * <p>A command that exits non-zero gives a verdict, and the lane whose snapshot was added last is
 * asked to repair it. A command that timed out or could not be started, or a pass that died
 * twice while running it, says nothing about anybody's work, and a lane that has used its
 * repairs is not asked again, nor is another lane asked in its place while its snapshot is in the
 * attempt: these are the operator's. While the request is open no attempt is started except by
 * the operator's recheck, since an attempt would only run the same command again on every poll.
 * The request closes when that is observed to have changed, not when someone says so.</p>
 */
final class ValidationFailureGuard {

    /** What every request of this condition tells the operator after naming the failure. */
    private static final String LEVERS = "Nothing was refused and nothing is integrated. Change the command with `synesis project validation set`, or change the project or end the lane, and then run `synesis operator recheck`. "
            + "This request closes when the command passes or refuses a snapshot on a recheck, when the project declares no command, or when no snapshot is waiting.";

    private ValidationFailureGuard() {
    }

    /**
     * Closes the open request when what it waits on is gone: the project no longer declares a
     * validation command, or no snapshot is waiting to be integrated. The caller holds the
     * project append lock.
     *
     * @param controlRoot  absolute control project root path
     * @param store        project event store, current under the lock
     * @param identity     node signing identity
     * @param workEligible whether any snapshot is eligible for integration
     * @return the request that stops integration now, or empty when none does
     */
    static Optional<OperatorRequest> reconcile(Path controlRoot, PredictionEventStore store, NodeIdentity identity, boolean workEligible) {
        Optional<OperatorRequest> open = store.operatorRequestProjection().open(OperatorRequest.INTEGRATION_VALIDATION_FAILING);
        if (open.isEmpty()) {
            return open;
        }
        boolean declared;
        try {
            declared = new ProjectApplicationService().locate(controlRoot).validation() != null;
        } catch (Exception unreadable) {
            // The project metadata could not be read, so nothing new is known about the
            // command. The request stands.
            return open;
        }
        if (declared && workEligible) {
            return open;
        }
        close(controlRoot, store, identity, declared ? "No snapshot is waiting to be integrated." : "The project declares no validation command.");
        return Optional.empty();
    }

    /**
     * Raises the request for a failure, unless the open request already reports that failure.
     * The caller holds the project append lock.
     *
     * <p>An open request that reports another failure is closed and a new one raised. Only the
     * answer of the pass that ran the command showed the newer output: the operator, reading the
     * recorded request, corrected a failure the command no longer had.</p>
     *
     * @param controlRoot absolute control project root path
     * @param store       project event store, current under the lock
     * @param identity    node signing identity
     * @param failure     what failed, as a sentence; what would clear it is added here
     * @param evidence    the end of what the command printed, or empty when it printed nothing
     * @param failed      the attempt, the snapshot added last and the outcome, for the failure point
     * @return the request that stops integration, as it is recorded
     */
    static OperatorRequest raise(Path controlRoot, PredictionEventStore store, NodeIdentity identity, String failure, String evidence, String failed) {
        Optional<OperatorRequest> open = store.operatorRequestProjection().open(OperatorRequest.INTEGRATION_VALIDATION_FAILING);
        OperatorRequest request = OperatorRequest.raised("opr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), OperatorRequest.INTEGRATION_VALIDATION_FAILING, failure + LEVERS, evidence);
        if (open.isPresent() && open.get().summary().equals(request.summary()) && open.get().evidence().equals(request.evidence())) {
            return open.get();
        }
        request = request.withFailurePoint(LocalDiagnostics.failurePoint(controlRoot, "integration", "validation", failed, null));
        try {
            if (open.isPresent()) {
                store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_CLOSED, identity.nodeId(), OperatorRequest.encodeClosed(open.get().id(), "Superseded by a newer failure of the validation command."), identity);
            }
            store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, identity.nodeId(), request.encodeRaised(), identity);
        } catch (Exception notRecorded) {
            // The log could not take the event. Nothing was integrated, and the answer still
            // says an operator is needed; the next pass runs the command and records it.
            LocalDiagnostics.failurePoint(controlRoot, "integration", "record_operator_request", String.valueOf(notRecorded.getMessage()), notRecorded);
        }
        return request;
    }

    /**
     * Closes the request when one is open. The caller holds the project append lock.
     *
     * @param controlRoot absolute control project root path
     * @param store       project event store, current under the lock
     * @param identity    node signing identity
     * @param because     what was observed
     */
    static void close(Path controlRoot, PredictionEventStore store, NodeIdentity identity, String because) {
        Optional<OperatorRequest> open = store.operatorRequestProjection().open(OperatorRequest.INTEGRATION_VALIDATION_FAILING);
        if (open.isEmpty()) {
            return;
        }
        try {
            store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_CLOSED, identity.nodeId(), OperatorRequest.encodeClosed(open.get().id(), because), identity);
        } catch (Exception notRecorded) {
            LocalDiagnostics.failurePoint(controlRoot, "integration", "record_operator_request", String.valueOf(notRecorded.getMessage()), notRecorded);
        }
    }

    /**
     * Answers a caller whose step needs integration while the request is open.
     *
     * @param request the open request
     * @return a {@code BLOCKED} response carrying the request
     */
    static AgentResponse blocked(OperatorRequest request) {
        return ControlCheckoutGuard.blocked(request, "Integration is stopped until an operator clears this request: the project's validation command fails on the combined work and no lane is asked to repair it. "
                + "Work you have published is kept and is integrated when the request closes. If this lane has nothing else to do, stop here and do not poll.");
    }
}
