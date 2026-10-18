package org.synesis.workspace.application.agent.run;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.replication.ReplicationJournal;
import org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper;
import org.synesis.workspace.application.collaboration.ParticipantActivity;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.ConsumerAbsenceGuard;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.ProviderSessionTerminalizationService;

/**
 * Observes a project by replaying its durable coordination log. It only reads.
 */
public final class EventLogObserver implements AgentRunController.ProjectObserver {

    private static final AgentRunController.Observation NOTHING_RECORDED = new AgentRunController.Observation() {

        @Override
        public long headSequence() {
            return 0L;
        }

        @Override
        public boolean workAnnounced() {
            return false;
        }

        @Override
        public boolean complete() {
            return false;
        }

        @Override
        public boolean workFinished() {
            return false;
        }

        @Override
        public boolean hasOpenLane(String provider) {
            return false;
        }

        @Override
        public boolean holdsOpenLane(String provider, String connectionInstanceId) {
            return false;
        }

        @Override
        public boolean progressedSince(long sequence) {
            return false;
        }

        @Override
        public OptionalLong lastActivitySequence(String provider, String connectionInstanceId) {
            return OptionalLong.empty();
        }

        @Override
        public OptionalLong answerDeadline(String provider) {
            return OptionalLong.empty();
        }
    };

    private final ProjectLocation location;
    private final Map<String, java.util.Optional<String>> lastKnownParticipants = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Creates an observer for one initialized project.
     *
     * @param location project location
     */
    public EventLogObserver(ProjectLocation location) {
        this.location = Objects.requireNonNull(location, "location");
    }

    @Override
    public AgentRunController.Observation observe() throws IOException {
        java.nio.file.Path coordination = location.coordinationDirectory();
        if (!java.nio.file.Files.isDirectory(coordination)) {
            // Nothing has been recorded yet, and opening a store would create the directory.
            return NOTHING_RECORDED;
        }
        PredictionEventStore store;
        try {
            store = location.openCoordinationStore();
        } catch (GeneralSecurityException failure) {
            throw new IOException("coordination log could not be verified", failure);
        }
        List<PredictionEvent> events = store.events();
        List<Participant> participants = ParticipantActivity.overlay(location.root(), store.collaborationProjection().participants());
        List<WorkGroup> groups = store.workGroupProjection().groups();
        // Done only once the work is on this copy's main too: a re-merge of two copies waits for its review with no lane
        // open (ADR-0098 step 5).
        boolean complete = WorkIntentService.hasHadWork(store) && !WorkIntentService.hasOpenWork(store)
                && org.synesis.workspace.application.control.MainMeeting.holdsEveryHead(location.root(), events);
        java.util.Set<String> openLaneProviders = store.collaborationProjection().activeIntents().stream().map(intent -> intent.provider()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        // A proposal belongs to the lane whose authority lineage it carries; that lane's provider
        // is the one whose agent has to come back when the answer period ends.
        Map<String, Long> answerDeadlines = new HashMap<>();
        // The moment a silent consumer counts as absent is not an event. The owner is resumed
        // once then, and its next read is what raises the operator request (ADR-0092).
        try {
            for (ConsumerAbsenceGuard.Owed owed : ConsumerAbsenceGuard.owed(location.root(), store)) {
                store.collaborationProjection().activeIntents().stream()
                        .filter(intent -> intent.authorityLineageId().equals(owed.binding().authorityLineageId()))
                        .forEach(intent -> answerDeadlines.merge(intent.provider(), owed.absentAtEpochMillis(), Math::max));
            }
        } catch (Exception unreadable) {
            // Who holds which lane could not be read. No owner is resumed for a moment nobody knows.
        }
        List<String> operatorRequests = store.operatorRequestProjection().open().stream().map(request -> request.id() + " CONDITION=" + request.condition()).toList();
        // A lane is held when the checkout cannot take integrated work and the lane's work is published
        // and waiting to be integrated. A provider is held when that is true of all its lanes.
        java.util.Set<java.util.UUID> waitingLineages = store.taskCompletionProjection().lineagesAwaitingIntegration();
        // Only a request that stops integration holds a lane. An absent consumer's own lane is
        // the one that has to come back for that request to close, and must not be held by it.
        // A validation command that fails with no lane left to answer for it stops integration
        // as a dirty checkout does: nothing integrates until the operator has acted.
        boolean integrationStopped = store.operatorRequestProjection().open(org.synesis.coordination.domain.operator.OperatorRequest.CONTROL_CHECKOUT_FOREIGN_CHANGES).isPresent()
                || store.operatorRequestProjection().open(org.synesis.coordination.domain.operator.OperatorRequest.INTEGRATION_VALIDATION_FAILING).isPresent();
        java.util.Set<String> heldProviders = !integrationStopped ? java.util.Set.of() : openLaneProviders.stream()
                .filter(provider -> store.collaborationProjection().activeIntents().stream().filter(intent -> intent.provider().equals(provider))
                        .allMatch(intent -> waitingLineages.contains(intent.authorityLineageId())))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        java.util.Set<String> laneRequests = store.operatorRequestProjection().all().stream().filter(request -> !request.raisedBySynesis())
                .map(org.synesis.coordination.domain.operator.OperatorRequest::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean otherComputerConnected = otherComputerConnected(coordination);
        // Where the operator closed each request, and where each coordination request was answered,
        // read once for every agent's question.
        Map<String, Long> operatorClosings = new HashMap<>();
        Map<java.util.UUID, Long> responses = new HashMap<>();
        for (int index = 0; index < events.size(); index++) {
            PredictionEvent event = events.get(index);
            java.util.Optional<String> closed = event.type() == org.synesis.coordination.domain.prediction.PredictionEventType.OPERATOR_REQUEST_CLOSED
                    ? closedRequest(event) : java.util.Optional.empty();
            if (closed.isPresent()) {
                operatorClosings.put(closed.get(), index + 1L);
            }
            long position = index + 1L;
            answeredRequest(event).ifPresent(request -> responses.put(request, position));
        }
        return new AgentRunController.Observation() {

            @Override
            public boolean otherComputerConnected() {
                return otherComputerConnected;
            }

            @Override
            public List<String> operatorRequests() {
                return operatorRequests;
            }

            @Override
            public boolean heldByOperator(String provider) {
                return heldProviders.contains(provider);
            }

            @Override
            public boolean holdsOpenLane(String provider, String connectionInstanceId) {
                String participant = participantOf(provider, connectionInstanceId);
                return participant != null && WorkspaceCollaborationService.laneOf(store, participant).isPresent();
            }

            @Override
            public boolean sessionFenced(String provider, String connectionInstanceId) {
                // The rule the MCP server's recovery fence applies to the same binding.
                String participant = participantOf(provider, connectionInstanceId);
                return participant != null && store.collaborationProjection().participantState(participant)
                        .filter(state -> state == Participant.State.SUSPENDED || state == Participant.State.RECOVERY_HELD).isPresent();
            }

            @Override
            public boolean foreignActiveLaneOpen(List<AgentRunController.Observation.Connection> managed) {
                java.util.Set<String> own = managed.stream().map(connection -> participantOf(connection.provider(), connection.connectionInstanceId()))
                        .filter(Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet());
                java.util.Set<String> active = participants.stream().filter(candidate -> candidate.state() == Participant.State.ACTIVE).map(Participant::id)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                return store.collaborationProjection().activeIntents().stream().anyMatch(intent -> !own.contains(intent.participant()) && active.contains(intent.participant()));
            }

            @Override
            public OptionalLong newestUnreadMessageFor(String provider, String connectionInstanceId) {
                String participant = participantOf(provider, connectionInstanceId);
                // The lane is the one the service hands messages for, so nobody is woken for a
                // message that would not be shown to it.
                return participant == null ? OptionalLong.empty() : WorkspaceCollaborationService.laneOf(store, participant)
                        .map(lane -> store.conversationProjection().unread(lane.authorityLineageId()).stream().mapToLong(message -> message.sequence()).max())
                        .orElse(OptionalLong.empty());
            }

            @Override
            public OptionalLong newestOperatorAnswerFor(String provider, String connectionInstanceId) {
                String participant = participantOf(provider, connectionInstanceId);
                if (participant == null) {
                    return OptionalLong.empty();
                }
                return WorkspaceCollaborationService.laneOf(store, participant)
                        .map(lane -> store.operatorRequestProjection().all().stream().filter(request -> request.raisedBy().equals(lane.authorityLineageId().toString()))
                                .map(request -> operatorClosings.get(request.id())).filter(Objects::nonNull).mapToLong(Long::longValue).max())
                        .orElse(OptionalLong.empty());
            }

            @Override
            public List<String> reviewsOffered(String provider, List<String> connectionInstanceIds) {
                List<String> sessions = connectionInstanceIds.stream().map(connection -> participantOf(provider, connection)).filter(Objects::nonNull).distinct().toList();
                // A re-merge to review again is offered to its original reviewer alone, whatever it holds (ADR-0098 step 5).
                return java.util.stream.Stream.concat(CollaborationResponseMapper.openReviewSnapshots(store, sessions).stream(),
                        CollaborationResponseMapper.remergeReviewsGranted(store, sessions).stream()).distinct().toList();
            }

            @Override
            public OptionalLong newestAnswerFor(String provider, String connectionInstanceId) {
                String participant = participantOf(provider, connectionInstanceId);
                return participant == null ? OptionalLong.empty() : store.collaborationProjection().requests().stream().filter(request -> request.requester().equals(participant))
                        .map(request -> responses.get(request.requestId())).filter(Objects::nonNull).mapToLong(Long::longValue).max();
            }

            @Override
            public boolean holdsRequestOrGrant(String provider, String connectionInstanceId) {
                String participant = participantOf(provider, connectionInstanceId);
                return participant != null && ProviderSessionTerminalizationService.holdsRequestOrGrant(store, participant);
            }

            @Override
            public OptionalLong answerDeadline(String provider) {
                Long deadline = answerDeadlines.get(provider);
                return deadline == null ? OptionalLong.empty() : OptionalLong.of(deadline);
            }

            @Override
            public long headSequence() {
                return events.size();
            }

            @Override
            public boolean workAnnounced() {
                return !groups.isEmpty();
            }

            @Override
            public boolean complete() {
                return complete;
            }

            @Override
            public boolean workFinished() {
                return groups.stream().anyMatch(group -> group.status() == WorkGroup.Status.COMPLETED);
            }

            @Override
            public boolean hasOpenLane(String provider) {
                return openLaneProviders.contains(provider);
            }

            @Override
            public OptionalLong lastActivitySequence(String provider, String connectionInstanceId) {
                // Every verified call of a session that holds a lane renews the activity time of its
                // participant, so that time marks the last look the agent took at the project.
                String participant = participantOf(provider, connectionInstanceId);
                OptionalLong lastCall = participants.stream().filter(candidate -> candidate.id().equals(participant)).mapToLong(Participant::lastVerifiedActivity).max();
                if (participant == null || lastCall.isEmpty()) {
                    return OptionalLong.empty();
                }
                return OptionalLong.of(events.stream().filter(event -> event.createdAtEpochMillis() <= lastCall.getAsLong()).count());
            }

            @Override
            public boolean progressedSince(long sequence) {
                if (sequence < 0) {
                    return !events.isEmpty();
                }
                for (int index = (int) Math.min(sequence, events.size()); index < events.size(); index++) {
                    if (AgentRunController.isProgress(events.get(index).type()) && !betweenALaneAndTheOperator(events.get(index), laneRequests)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /**
     * Whether a peer that this computer's role file names (the leader's members, a member's
     * leader) is connected according to the replication journal. A journal line about a node
     * that is no longer a peer of the project counts for nothing. An unreadable role file or
     * journal reads as not connected: this only decides whether a run keeps waiting, and must not
     * end the run.
     */
    private static boolean otherComputerConnected(java.nio.file.Path coordination) {
        try {
            java.util.Set<String> peers = ReplicationSettings.read(coordination).map(settings -> settings.isMember() ? java.util.Set.of(settings.leaderNodeId()) : settings.members())
                    .orElse(java.util.Set.of());
            return ReplicationJournal.connectedPeers(coordination).stream().anyMatch(peers::contains);
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * A lane putting something to the operator, and the operator closing it, are messages and
     * not work: neither resumes anybody (ADR-0093).
     */
    private static boolean betweenALaneAndTheOperator(PredictionEvent event, java.util.Set<String> laneRequests) {
        try {
            return switch (event.type()) {
                case OPERATOR_REQUEST_RAISED -> !org.synesis.coordination.domain.operator.OperatorRequest.decodeRaised(event.payload()).raisedBySynesis();
                case OPERATOR_REQUEST_CLOSED -> laneRequests.contains(org.synesis.coordination.domain.operator.OperatorRequest.closedRequestId(event.payload()));
                default -> false;
            };
        } catch (IOException malformed) {
            return false;
        }
    }

    /**
     * The coordination request a response answers, or empty for any other event. A response is
     * appended under the identity of the request it answers, as {@code CoordinationRequestService}
     * writes it.
     */
    private static java.util.Optional<java.util.UUID> answeredRequest(PredictionEvent event) {
        return event.type() == org.synesis.coordination.domain.prediction.PredictionEventType.COORDINATION_RESPONDED ? java.util.Optional.of(event.predictionId())
                : java.util.Optional.empty();
    }

    /** The request an operator closing names, or empty when the payload cannot be read. */
    private static java.util.Optional<String> closedRequest(PredictionEvent event) {
        try {
            return java.util.Optional.of(org.synesis.coordination.domain.operator.OperatorRequest.closedRequestId(event.payload()));
        } catch (IOException malformed) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Returns the participant of the session behind one connection now, or {@code null} when it has
     * none. It is read again on every call: a connection keeps its identity across the agent's
     * processes and starts a fresh session once its lane has ended (ADR-0099, amendment), so the
     * answer for one connection changes. A participant kept from an earlier look ended a kept
     * session whose agent had just taken its next lane, and that lane went to recovery.
     */
    private String participantOf(String provider, String connectionInstanceId) {
        if (provider == null || connectionInstanceId == null) {
            return null;
        }
        String key = provider + '|' + connectionInstanceId;
        try {
            String participant = new ProviderSessionBindingService().find(location, provider, connectionInstanceId).map(binding -> WorkspaceCollaborationService.participantHandle(binding.sessionId()))
                    .orElse(null);
            lastKnownParticipants.put(key, java.util.Optional.ofNullable(participant));
            return participant;
        } catch (Exception unavailable) {
            // An unreadable binding is not "no lane": the controller would end a session it can
            // still come back to. The answer of the last look stands until the file reads again.
            return lastKnownParticipants.getOrDefault(key, java.util.Optional.empty()).orElse(null);
        }
    }
}
