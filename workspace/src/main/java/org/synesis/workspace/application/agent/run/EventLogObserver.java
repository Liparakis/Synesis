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
import org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper;
import org.synesis.workspace.application.agent.workflow.DisputeNotice;
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

    /** Keys a re-merge review granted to one of the agent's sessions apart from the same re-merge waiting for a grant. */
    private static final String GRANTED_OFFER = "granted:";

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
        boolean holdsEveryHead = org.synesis.workspace.application.control.MainMeeting.holdsEveryHead(location.root(), events);
        boolean complete = WorkIntentService.hasHadWork(store) && !WorkIntentService.hasOpenWork(store) && holdsEveryHead;
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
        List<org.synesis.coordination.domain.operator.OperatorRequest> openRequests = store.operatorRequestProjection().open();
        List<String> operatorRequests = openRequests.stream().filter(org.synesis.coordination.domain.operator.OperatorRequest::blocking).map(request -> request.id() + " CONDITION=" + request.condition()).toList();
        List<String> operatorNotes = openRequests.stream().filter(request -> !request.blocking()).map(request -> request.id() + " CONDITION=" + request.condition()).toList();
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
        OptionalLong otherComputerConnectedSince = otherComputerConnectedSince(coordination);
        boolean otherComputerKnown = !peersOfProject(coordination).isEmpty();
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
                return otherComputerConnectedSince.isPresent();
            }

            @Override
            public boolean otherComputerKnown() {
                return otherComputerKnown;
            }

            @Override
            public boolean mainMissesAnotherComputersHead() {
                return !holdsEveryHead;
            }

            @Override
            public OptionalLong otherComputerConnectedSince() {
                return otherComputerConnectedSince;
            }

            @Override
            public List<String> operatorRequests() {
                return operatorRequests;
            }

            @Override
            public List<String> operatorNotes() {
                return operatorNotes;
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
            public boolean laneAwaitsOnlyReview(String provider, String connectionInstanceId) {
                String participant = participantOf(provider, connectionInstanceId);
                return participant != null && WorkspaceCollaborationService.laneOf(store, participant)
                        .filter(lane -> lane.role() == org.synesis.coordination.domain.collaboration.WorkIntent.Role.PRODUCER)
                        .flatMap(lane -> store.taskCompletionProjection().findSnapshotForTaskRevision(lane.taskId(), lane.intentId(), lane.version()))
                        .flatMap(snapshot -> store.taskCompletionProjection().snapshotState(snapshot.snapshotId()))
                        .filter(state -> state == org.synesis.coordination.domain.task.TaskCompletionState.REVIEW_PENDING).isPresent();
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
                // A re-merge to review again is offered to its original reviewer, whatever it holds (ADR-0098 step 5), and
                // while no grant on it is held to an agent that may review it, whose session the pass cannot offer it to
                // before it has one (item 30).
                // A re-merge waiting and the same re-merge granted to a session are two offers (item 40): the controller resumes an
                // agent once for each offer, and the pass grants a waiting re-merge only to a session that exists, so the agent
                // resumed for the wait has stopped by the time the grant is made (s5-run27).
                // So is a combine lane, to the agents of this computer (designer item 22, item 40),
                // and a lane of an away computer to an agent here with no lane while this computer is stalled (item 10).
                return java.util.stream.Stream.of(CollaborationResponseMapper.openReviewSnapshots(store, sessions).stream(),
                        CollaborationResponseMapper.remergeReviewsGranted(store, sessions).stream().map(snapshot -> GRANTED_OFFER + snapshot),
                        CollaborationResponseMapper.remergeReviewsWaiting(store, sessions).stream(),
                        combinesOffered(store, location, sessions).stream(),
                        repairsOffered(store, location, sessions).stream(),
                        takeoversOffered(store, sessions).stream())
                        .flatMap(java.util.function.Function.identity()).distinct().toList();
            }

            @Override
            public OptionalLong newestAnswerFor(String provider, String connectionInstanceId) {
                String participant = participantOf(provider, connectionInstanceId);
                return participant == null ? OptionalLong.empty() : store.collaborationProjection().requests().stream().filter(request -> request.requester().equals(participant))
                        .map(request -> responses.get(request.requestId())).filter(Objects::nonNull).mapToLong(Long::longValue).max();
            }

            @Override
            public List<String> disputesFor(String provider, List<String> connectionInstanceIds) {
                return connectionInstanceIds.stream().map(connection -> participantOf(provider, connection)).filter(Objects::nonNull).distinct()
                        .flatMap(participant -> DisputeNotice.stateKeys(store, participant).stream()).distinct().toList();
            }

            @Override
            public boolean holdsRequestOrGrant(String provider, String connectionInstanceId) {
                String participant = participantOf(provider, connectionInstanceId);
                return participant != null && (ProviderSessionTerminalizationService.holdsRequestOrGrant(store, participant)
                        || DisputeNotice.verdictUnsettled(store, participant));
            }

            @Override
            public boolean holdsReviewOfAnotherComputer(String provider, String connectionInstanceId) {
                String participant = participantOf(provider, connectionInstanceId);
                return participant != null && thisNode().map(node -> ProviderSessionTerminalizationService.holdsReviewOfAnotherComputer(store, participant, node)).orElse(false);
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
     * When the newest connection began with a peer of the project that this computer's replication side records as
     * connected, or empty when none is. This is the record {@code AwayLanes} judges a computer away by
     * ({@code replication.members}, ADR-0098 step 6 item 22), so a run does not keep waiting for a computer that the
     * takeover already counts as away: the replication journal says only that a session is open, and a session behind a
     * silent path stays open. A record about a node that is no longer a peer of the project counts for nothing. A
     * missing or unreadable record reads as not connected: this only decides whether a run keeps waiting and whom it
     * resumes, and must not end the run.
     */
    private static OptionalLong otherComputerConnectedSince(java.nio.file.Path coordination) {
        java.util.Set<String> peers = peersOfProject(coordination);
        return org.synesis.coordination.replication.LeaderReplication.recordedMembers(coordination).orElse(java.util.Map.of()).entrySet().stream()
                .filter(member -> peers.contains(member.getKey()) && member.getValue().state() == org.synesis.coordination.replication.LeaderReplication.MemberConnection.State.CONNECTED)
                .mapToLong(member -> member.getValue().since().toEpochMilli()).max();
    }

    /**
     * The computers the replication side records connections for: the other members since ADR-0098 step 6, or the
     * leader. Empty for an unshared project and when the settings cannot be read, which fails closed to a run that does
     * not wait for another computer.
     */
    private static java.util.Set<String> peersOfProject(java.nio.file.Path coordination) {
        try {
            return ReplicationSettings.read(coordination).map(settings -> settings.isMember() ? java.util.Set.of(settings.leaderNodeId())
                    : settings.shared() ? org.synesis.coordination.replication.LeaderReplication.recordedMembers(coordination).map(java.util.Map::keySet)
                            .orElse(java.util.Set.of()) : settings.members()).orElse(java.util.Set.of());
        } catch (IOException unreadable) {
            return java.util.Set.of();
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

    /** The repair lanes these sessions' agent may take, by snapshot ID; none when this computer is unknown (ADR-0098 step 5 item 54). */
    private static List<String> repairsOffered(PredictionEventStore store, ProjectLocation location, List<String> sessions) {
        try {
            return org.synesis.workspace.application.collaboration.RepairLaneOffer.offeredTo(store, sessions, location.loadOrCreateIdentity().nodeId(),
                    org.synesis.workspace.application.collaboration.AwayLanes.away(location.coordinationDirectory(), location.loadOrCreateIdentity().nodeId()))
                    .stream().map(org.synesis.coordination.domain.task.TaskSnapshotRecord::snapshotId).toList();
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    /** The combine lanes these sessions' agent may take, by combine ID; none when this computer is unknown. */
    private static List<String> combinesOffered(PredictionEventStore store, ProjectLocation location, List<String> sessions) {
        try {
            return org.synesis.workspace.application.collaboration.CombineLaneJoin.offeredTo(store, sessions, location.loadOrCreateIdentity().nodeId())
                    .stream().map(org.synesis.coordination.domain.task.CombinePayload::combineId).toList();
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    /**
     * The lanes of computers that are away which these sessions' agent may take over now, by lane ID: the same offer
     * {@code get_next_action} makes, and made only to an agent none of whose sessions holds a lane (a session holds one
     * lane, so a taker with a lane is refused). Whether a computer is away is not an event, so nothing else resumes a
     * stopped agent when the record of member sessions turns (ADR-0099 fix 15).
     */
    private List<String> takeoversOffered(PredictionEventStore store, List<String> sessions) {
        if (sessions.isEmpty() || sessions.stream().anyMatch(session -> WorkspaceCollaborationService.laneOf(store, session).isPresent())) {
            return List.of();
        }
        return thisNode().map(node -> org.synesis.workspace.application.collaboration.AwayLanes.offered(store, location.coordinationDirectory(), node).stream()
                .map(lane -> lane.intentId().toString()).toList()).orElse(List.of());
    }

    /** This computer's node ID, or empty when its identity cannot be read: then no review counts as another computer's. */
    private java.util.Optional<String> thisNode() {
        try {
            return java.util.Optional.of(location.loadOrCreateIdentity().nodeId());
        } catch (Exception unreadable) {
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
