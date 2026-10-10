package org.synesis.coordination.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.RepairLanePayload;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.CommitId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationMissingElementException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Admits a repair lane and appends the single event that transfers reserved scope from its source lane.
 *
 * <p>Kept apart from {@link WorkIntentService} because admission is a fixed sequence of fail-closed checks over one
 * projection followed by one append. The caller must already hold the project append lock: this class never acquires
 * it, so {@link WorkIntentService} still decides where the lock is taken and released.</p>
 */
final class RepairLaneTransfer {

    private final NodeIdentity signer;

    /**
     * Validates and initializes this repair lane transfer.
     *
     * @param signer identity used to sign the event
     */
    RepairLaneTransfer(NodeIdentity signer) {
        this.signer = CoordinationNullException.requireNonNull(signer, "signer");
    }

    /**
     * Validates repair authority and builds the sole durable ownership-transfer payload.
     */
    private static RepairLanePayload validateRepairLane(PredictionEventStore current,
            WorkIntent source,
            WorkIntent targetIntent,
            SnapshotId snapshotId,
            CommitId expectedControlHead) throws IOException {
        if (source.status() != WorkIntent.Status.ANNOUNCED) {
            throw new CoordinationException("REPAIR_SOURCE_NOT_ACTIVE");
        }
        if (!source.project().equals(targetIntent.project()) || !source.group().equals(targetIntent.group())) {
            throw new CoordinationException("REPAIR_WORK_GROUP_MISMATCH");
        }
        if (!source.lineage().equals(targetIntent.lineage())) {
            throw new CoordinationException("REPAIR_AUTHORITY_LINEAGE_MISMATCH");
        }
        if (source.identity().equals(targetIntent.identity()) || source.participantIdentity()
                .equals(targetIntent.participantIdentity())) {
            throw new CoordinationException("REPAIR_TARGET_MUST_BE_DISTINCT");
        }
        if (!source.selectors().equals(targetIntent.selectors())) {
            throw new CoordinationException("REPAIR_SCOPE_MISMATCH");
        }
        if (targetIntent.intentVersion().value() != source.intentVersion().value() + 1L) {
            throw new CoordinationException("REPAIR_EPOCH_MISMATCH");
        }
        if (current.collaborationProjection().activeIntents().stream()
                .anyMatch(intent -> intent.participantIdentity().equals(targetIntent.participantIdentity()))) {
            throw new CoordinationException("REPAIR_TARGET_ALREADY_ACTIVE");
        }
        return new RepairLanePayload(source.identity().value(),
                targetIntent,
                snapshotId.value(),
                expectedControlHead.value(),
                source.version(),
                targetIntent.version());
    }

    /**
     * Applies the create repair lane locked transition to the current coordination projection.
     *
     * @param current      fresh store opened while the project append lock is held
     * @param targetIntent proposed target intent
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    void createRepairLaneLocked(PredictionEventStore current,
            WorkIntentId sourceIntentId,
            SnapshotId snapshotId,
            CommitId expectedControlHead,
            WorkIntent targetIntent) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(sourceIntentId, "source intent");
        CoordinationNullException.requireNonNull(snapshotId, "snapshot ID");
        CoordinationNullException.requireNonNull(expectedControlHead, "expected control HEAD");
        CoordinationNullException.requireNonNull(targetIntent, "target intent");
        if (snapshotId.value().isBlank() || expectedControlHead.value().isBlank()) {
            throw new CoordinationException("REPAIR_METADATA_REQUIRED");
        }
        var existingTarget = current.collaborationProjection().intent(targetIntent.identity());
        if (existingTarget.isPresent()) {
            if (!existingTarget.orElseThrow(CoordinationMissingElementException::new).equals(targetIntent)) {
                throw new CoordinationException("REPAIR_TARGET_MISMATCH");
            }
            return;
        }
        WorkIntent source = current.collaborationProjection().intent(sourceIntentId)
                .orElseThrow(() -> new CoordinationException("REPAIR_SOURCE_NOT_FOUND"));
        RepairLanePayload payload = validateRepairLane(current, source, targetIntent, snapshotId, expectedControlHead);
        UUID eventId = UUID.nameUUIDFromBytes(("repair-lane:" + sourceIntentId.value() + ":" + targetIntent.identity()
                .value() + ":" + snapshotId.value()).getBytes(StandardCharsets.UTF_8));
        current.append(eventId, PredictionEventType.REPAIR_LANE_CREATED, signer.nodeId(), payload.encode(), signer);
    }
}
