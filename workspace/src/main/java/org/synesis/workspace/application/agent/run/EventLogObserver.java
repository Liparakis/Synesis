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
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.ConsumerAbsenceGuard;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.digest.Sha256;

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
        List<Participant> participants = store.collaborationProjection().participants();
        List<WorkGroup> groups = store.workGroupProjection().groups();
        boolean complete = WorkIntentService.hasHadWork(store) && !WorkIntentService.hasOpenWork(store);
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
        boolean integrationStopped = store.operatorRequestProjection().open(org.synesis.coordination.domain.operator.OperatorRequest.CONTROL_CHECKOUT_FOREIGN_CHANGES).isPresent();
        java.util.Set<String> heldProviders = !integrationStopped ? java.util.Set.of() : openLaneProviders.stream()
                .filter(provider -> store.collaborationProjection().activeIntents().stream().filter(intent -> intent.provider().equals(provider))
                        .allMatch(intent -> waitingLineages.contains(intent.authorityLineageId())))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new AgentRunController.Observation() {

            @Override
            public List<String> operatorRequests() {
                return operatorRequests;
            }

            @Override
            public boolean heldByOperator(String provider) {
                return heldProviders.contains(provider);
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
                    if (AgentRunController.isProgress(events.get(index).type())) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /** Returns the participant of the session behind one connection, or {@code null} when it has none. */
    private String participantOf(String provider, String connectionInstanceId) {
        if (provider == null || connectionInstanceId == null) {
            return null;
        }
        try {
            String fingerprint = Sha256.hex(connectionInstanceId);
            return new ProviderSessionBindingService().list(location, provider).stream()
                    .filter(binding -> fingerprint.equals(binding.providerInstanceFingerprint()))
                    .map(binding -> WorkspaceCollaborationService.participantHandle(binding.sessionId()))
                    .findFirst().orElse(null);
        } catch (Exception unavailable) {
            return null;
        }
    }
}
