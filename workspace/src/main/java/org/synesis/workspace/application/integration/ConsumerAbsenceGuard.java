package org.synesis.workspace.application.integration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.application.RequesterAnswerPolicy;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.diagnostics.LocalDiagnostics;

/**
 * Asks for an operator when a lane bound to another lane's contract has stopped acting while its
 * own lane is still open (ADR-0092).
 *
 * <p>The owner's work is integrated only when every bound consumer has checked it. A consumer
 * whose lane has ended no longer counts. One whose lane is open and whose agent does not return
 * would hold the owner for good, and the owner used to be let go after a fixed time with that
 * consumer silently dropped. The repository owner decided otherwise: an absent consumer is not
 * skipped, it is the operator's to resolve, by starting an agent that continues the lane or by
 * cancelling it.</p>
 *
 * <p>Absent is judged from what is recorded: the consumer owes an action, and neither the record
 * nor any verified call of the consumer's participant is newer than the stated wait.</p>
 */
final class ConsumerAbsenceGuard {

    private ConsumerAbsenceGuard() {
    }

    /**
     * Raises the request while a bound consumer is absent and closes it when none is. The caller
     * holds the project append lock.
     *
     * @param controlRoot absolute control project root path
     * @param store       project event store, current under the lock
     * @param identity    node signing identity
     * @param nowMillis   current time in epoch milliseconds
     */
    static void reconcile(Path controlRoot, PredictionEventStore store, NodeIdentity identity, long nowMillis) {
        Optional<OperatorRequest> open = store.operatorRequestProjection().open(OperatorRequest.BOUND_CONSUMER_ABSENT);
        List<String> absent;
        try {
            absent = absentConsumers(controlRoot, store, nowMillis);
        } catch (Exception unreadable) {
            // Who holds which lane could not be read, so nothing new is known. Raising on a
            // guess would stop a run that can continue.
            return;
        }
        if (absent.isEmpty() && open.isEmpty()) {
            return;
        }
        try {
            if (absent.isEmpty()) {
                store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_CLOSED, identity.nodeId(),
                        OperatorRequest.encodeClosed(open.get().id(), "Every bound lane has acted or has ended."), identity);
            } else if (open.isEmpty()) {
                OperatorRequest request = OperatorRequest.raised("opr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), OperatorRequest.BOUND_CONSUMER_ABSENT,
                        "A lane that builds on another lane's work has not checked that work, or has not bound again to its revised contract, and its agent has not returned. "
                                + "The owner's work is not integrated until every bound lane has. Start the agents again so that one continues the lane named here, or cancel that lane. "
                                + "This request closes when each such lane has acted or has ended.",
                        String.join("\n", absent));
                store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, identity.nodeId(), request.encodeRaised(), identity);
            }
        } catch (Exception notRecorded) {
            LocalDiagnostics.failurePoint(controlRoot, "integration", "record_absent_consumer", String.valueOf(notRecorded.getMessage()), notRecorded);
        }
    }

    /** Lists each binding whose consumer owes an action and has been silent past the stated wait, one line each. */
    private static List<String> absentConsumers(Path controlRoot, PredictionEventStore store, long nowMillis) throws Exception {
        List<CapabilityRequestRecord> owed = store.capabilityRequestProjection().records().values().stream()
                .filter(RequesterAnswerPolicy::consumerOwesAction)
                .filter(record -> nowMillis >= RequesterAnswerPolicy.answerDeadlineEpochMillis(record))
                .toList();
        List<String> absent = new ArrayList<>();
        if (owed.isEmpty()) {
            return absent;
        }
        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        List<Participant> participants = store.collaborationProjection().participants();
        for (CapabilityRequestRecord record : owed) {
            long wait = RequesterAnswerPolicy.answerDeadlineEpochMillis(record) - record.updatedAtEpochMillis();
            Optional<String> consumer = participants.stream().map(Participant::provider).distinct()
                    .flatMap(provider -> bindingsOf(bindings, location, provider).stream())
                    .filter(binding -> binding.workerId().equals(record.requesterWorkerId()) && binding.supervisorId().equals(record.requesterSupervisorId()))
                    .map(binding -> WorkspaceCollaborationService.participantHandle(binding.sessionId())).findFirst();
            Optional<WorkIntent> lane = consumer.flatMap(participant -> store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participant)).findFirst());
            if (lane.isEmpty()) {
                // No open lane stands behind this binding. Ending a lane closes its bindings, so
                // this is a record about to be closed, not a consumer to wait for.
                continue;
            }
            long lastCall = participants.stream().filter(participant -> participant.id().equals(consumer.get())).mapToLong(Participant::lastVerifiedActivity).max().orElse(0L);
            if (nowMillis < lastCall + wait) {
                continue;
            }
            absent.add(record.capability() + " binding=" + record.handle().value() + " state=" + record.state().value() + " lane=\"" + lane.get().goal() + "\"");
        }
        return absent;
    }

    private static List<ProviderSessionBindingService.Binding> bindingsOf(ProviderSessionBindingService bindings, ProjectLocation location, String provider) {
        try {
            return bindings.list(location, provider);
        } catch (Exception unreadable) {
            return List.of();
        }
    }
}
