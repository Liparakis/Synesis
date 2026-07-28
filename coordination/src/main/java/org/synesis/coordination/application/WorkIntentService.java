package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ClaimConflict;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.link.identity.NodeIdentity;

/** Application service for authenticated work intent and resource claims. */
public final class WorkIntentService {
    private final PredictionEventStore store;
    private final NodeIdentity signer;

    /** Creates a service bound to one project event store and signing identity.
     * @param store project event store
     * @param signer authenticated signing identity
     */
    public WorkIntentService(PredictionEventStore store, NodeIdentity signer) {
        this.store = Objects.requireNonNull(store, "store");
        this.signer = Objects.requireNonNull(signer, "signer");
    }

    /** Announces an intent and atomically acquires all requested selectors.
     * @param intent intent declaration
     * @return acquisition result
     * @throws IOException when persistence or validation fails
     * @throws GeneralSecurityException when event signing fails
     */
    public synchronized ClaimResult announce(WorkIntent intent) throws IOException, GeneralSecurityException {
        Objects.requireNonNull(intent, "intent");
        if (!store.projectId().equals(intent.projectId())) {
            throw new IllegalArgumentException("intent project mismatch");
        }
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            List<ClaimConflict> conflicts = current.collaborationProjection().conflicts(intent.selectors());
            if (!conflicts.isEmpty()) {
                return new ClaimResult(false, intent, conflicts);
            }
            current.append(intent.intentId(), PredictionEventType.WORK_INTENT_ANNOUNCED,
                    signer.nodeId(), CollaborationCodec.encodeIntent(intent), signer);
            return new ClaimResult(true, intent, List.of());
        }
    }

    /** Releases an intent owned by the signing participant.
     * @param intentId intent identifier
     * @param participant participant handle
     * @throws IOException when the intent is missing or persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public synchronized void release(UUID intentId, String participant)
            throws IOException, GeneralSecurityException {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            WorkIntent intent = current.collaborationProjection().intent(intentId)
                    .orElseThrow(() -> new IOException("INTENT_NOT_FOUND"));
            if (!intent.participant().equals(participant)) {
                throw new IOException("INTENT_OWNER_MISMATCH");
            }
            current.append(intentId, PredictionEventType.WORK_INTENT_RELEASED,
                    signer.nodeId(), CollaborationCodec.encodeRelease(intentId), signer);
        }
    }

    /** Returns whether a participant owns a compatible selector.
     * @param participant participant handle
     * @param selector target selector
     * @return true when the participant owns an overlapping active claim
     */
    public boolean owns(String participant, ResourceSelector selector) {
        return freshIntents().stream()
                .filter(intent -> intent.participant().equals(participant))
                .anyMatch(intent -> intent.selectors().stream().anyMatch(selector::overlaps));
    }

    /** Returns the active intents for discovery.
     * @return active intent snapshot
     */
    public List<WorkIntent> activeIntents() {
        return freshIntents();
    }

    private List<WorkIntent> freshIntents() {
        try {
            return freshStore().collaborationProjection().activeIntents();
        } catch (Exception failure) {
            throw new IllegalStateException("COLLABORATION_STATE_UNAVAILABLE", failure);
        }
    }

    private PredictionEventStore freshStore() throws IOException, GeneralSecurityException {
        return new PredictionEventStore(store.rootDirectory(), store.projectId());
    }
}
