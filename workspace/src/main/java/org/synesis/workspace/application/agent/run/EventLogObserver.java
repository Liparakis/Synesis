package org.synesis.workspace.application.agent.run;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

import org.synesis.coordination.application.RequesterAnswerPolicy;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.application.project.ProjectLocation;

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
        public boolean hasOpenLane(String provider) {
            return false;
        }

        @Override
        public boolean progressedSince(long sequence) {
            return false;
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
        List<WorkGroup> groups = store.workGroupProjection().groups();
        boolean complete = WorkIntentService.hasHadWork(store) && !WorkIntentService.hasOpenWork(store);
        java.util.Set<String> openLaneProviders = store.collaborationProjection().activeIntents().stream().map(intent -> intent.provider()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        // A proposal belongs to the lane whose authority lineage it carries; that lane's provider
        // is the one whose agent has to come back when the answer period ends.
        Map<String, Long> answerDeadlines = new HashMap<>();
        for (CapabilityRequestRecord request : store.capabilityRequestProjection().records().values()) {
            if (!RequesterAnswerPolicy.awaitsRequesterAnswer(request)) {
                continue;
            }
            long deadline = RequesterAnswerPolicy.answerDeadlineEpochMillis(request);
            store.collaborationProjection().activeIntents().stream()
                    .filter(intent -> intent.authorityLineageId().equals(request.authorityLineageId()))
                    .forEach(intent -> answerDeadlines.merge(intent.provider(), deadline, Math::max));
        }
        return new AgentRunController.Observation() {

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
            public boolean hasOpenLane(String provider) {
                return openLaneProviders.contains(provider);
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
}
