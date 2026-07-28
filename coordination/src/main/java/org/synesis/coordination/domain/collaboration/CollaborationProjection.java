package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;

/** Deterministic projection of active work intents and resource claims. */
public final class CollaborationProjection {
    private final Map<UUID, WorkIntent> intents = new LinkedHashMap<>();
    private boolean activated;

    /** Creates an empty collaboration projection. */
    public CollaborationProjection() {
    }

    /**
     * Applies one collaboration event.
     * @param event event
     * @throws IOException malformed transition
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
        switch (event.type()) {
            case WORK_INTENT_ANNOUNCED -> {
                activated = true;
                announce(CollaborationCodec.decodeIntent(event.payload()));
            }
            case WORK_INTENT_RELEASED -> release(CollaborationCodec.decodeRelease(event.payload()));
            default -> {
            }
        }
    }

    /**
     * Validates one collaboration event without mutation.
     * @param event event
     * @throws IOException invalid transition
     */
    public synchronized void validate(PredictionEvent event) throws IOException {
        CollaborationProjection candidate = new CollaborationProjection();
        candidate.intents.putAll(intents);
        candidate.activated = activated;
        candidate.apply(event);
    }

    /**
     * Returns an intent by identifier.
     * @param id intent ID
     * @return intent
     */
    public synchronized Optional<WorkIntent> intent(UUID id) {
        return Optional.ofNullable(intents.get(id));
    }

    /**
     * Returns all active intents.
     * @return immutable intents
     */
    public synchronized List<WorkIntent> activeIntents() {
        return List.copyOf(intents.values());
    }

    /** Returns whether this project has durable collaboration enforcement enabled. */
    public synchronized boolean activated() {
        return activated;
    }

    /**
     * Finds claims overlapping any requested selector.
     * @param selectors selectors
     * @return conflicts
     */
    public synchronized List<ClaimConflict> conflicts(List<ResourceSelector> selectors) {
        List<ClaimConflict> result = new ArrayList<>();
        for (WorkIntent intent : intents.values()) {
            for (ResourceSelector existing : intent.selectors()) {
                for (ResourceSelector requested : selectors) {
                    if (existing.overlaps(requested)) {
                        result.add(new ClaimConflict(intent.participant(), intent.intentId().toString(), existing));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private void announce(WorkIntent intent) throws IOException {
        if (intents.containsKey(intent.intentId()) || !conflicts(intent.selectors()).isEmpty()) {
            throw new IOException("OVERLAPPING_CLAIM");
        }
        intents.put(intent.intentId(), intent);
    }

    private void release(UUID id) throws IOException {
        if (intents.remove(id) == null) {
            throw new IOException("INTENT_NOT_FOUND");
        }
    }
}
