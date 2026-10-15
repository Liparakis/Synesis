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

import org.synesis.workspace.application.collaboration.ParticipantActivity;
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
public final class ConsumerAbsenceGuard {

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
            absent = owed(controlRoot, store).stream().filter(owed -> nowMillis >= owed.absentAtEpochMillis()).map(Owed::described).toList();
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
                                + "The owner's work is not integrated until every bound lane has. Start the agents again so that one continues the lane named here. "
                                + "This request closes when each such lane has acted or has ended.",
                        String.join("\n", absent));
                store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, identity.nodeId(), request.encodeRaised(), identity);
            }
        } catch (Exception notRecorded) {
            LocalDiagnostics.failurePoint(controlRoot, "integration", "record_absent_consumer", String.valueOf(notRecorded.getMessage()), notRecorded);
        }
    }

    /**
     * Returns the bindings whose consumer owes an action and whose consumer's lane is open, each
     * with the moment that consumer counts as absent.
     *
     * <p>This is the one account of absence. The guard raises the operator request from it, the
     * run controller resumes the owner at the moment it gives, and the owner's next action is
     * held on it. Read separately they disagreed: the owner was resumed before the guard would
     * raise anything, and was not resumed again.</p>
     *
     * @param controlRoot project root
     * @param store       coordination store
     * @return the owed bindings; a binding with no open lane behind it is not one
     * @throws Exception when the sessions holding the lanes cannot be read
     */
    public static List<Owed> owed(Path controlRoot, PredictionEventStore store) throws Exception {
        List<CapabilityRequestRecord> owing = store.capabilityRequestProjection().records().values().stream().filter(RequesterAnswerPolicy::consumerOwesAction).toList();
        List<Owed> owed = new ArrayList<>();
        if (owing.isEmpty()) {
            return owed;
        }
        ProjectLocation location = new ProjectApplicationService().locate(controlRoot);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        List<Participant> participants = ParticipantActivity.overlay(location.root(), store.collaborationProjection().participants());
        for (CapabilityRequestRecord record : owing) {
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
            owed.add(new Owed(record, lane.get(), RequesterAnswerPolicy.absentAtEpochMillis(record, lastCall)));
        }
        return owed;
    }

    /**
     * A binding whose consumer owes an action.
     *
     * @param binding             the binding
     * @param consumerLane        the open lane of the consumer
     * @param absentAtEpochMillis the moment the consumer counts as absent
     */
    public record Owed(CapabilityRequestRecord binding, WorkIntent consumerLane, long absentAtEpochMillis) {

        /**
         * Returns the line the operator and the waiting owner are shown for this binding.
         *
         * @return the dependency, the binding, its state and the consumer's goal
         */
        public String described() {
            return binding.capability() + " binding=" + binding.handle().value() + " state=" + binding.state().value() + " lane=\"" + consumerLane.goal() + "\"";
        }
    }

    private static List<ProviderSessionBindingService.Binding> bindingsOf(ProviderSessionBindingService bindings, ProjectLocation location, String provider) {
        try {
            return bindings.list(location, provider);
        } catch (Exception unreadable) {
            return List.of();
        }
    }
}
