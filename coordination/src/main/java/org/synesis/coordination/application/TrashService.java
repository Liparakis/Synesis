package org.synesis.coordination.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.trash.TrashEntries.TrashEmptied;
import org.synesis.coordination.domain.trash.TrashEntries.TrashRestored;
import org.synesis.coordination.domain.trash.TrashEntries.WorkTrashed;
import org.synesis.coordination.domain.trash.TrashProjection;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Writes the trash entries (ADR-0098 step 7, designer items 12 to 16). The commits they name are kept, and deleted
 * when emptied, by the workspace; this writes only what the log says about them, each under the project's append lock
 * against the projection as it stands then.
 */
public final class TrashService {

    private final NodeIdentity signer;
    private final ProjectAppends appends;

    /**
     * Creates the service.
     *
     * @param store  this computer's logs
     * @param signer this computer
     */
    public TrashService(PredictionEventStore store, NodeIdentity signer) {
        this.signer = CoordinationNullException.requireNonNull(signer, "signer");
        this.appends = new ProjectAppends(CoordinationNullException.requireNonNull(store, "store"));
    }

    /**
     * Records trashed work once; work already in the trash is left as it is.
     *
     * @param trashed the work and the commit that holds it
     * @return whether this call recorded it
     * @throws IOException              when the lane is not this computer's
     * @throws GeneralSecurityException when signing fails
     */
    public boolean trash(WorkTrashed trashed) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(trashed, "trashed");
        return appends.call(current -> {
            if (current.trashProjection().item(trashed.laneId(), trashed.epoch()).isPresent()) {
                return false;
            }
            current.append(stream(trashed.laneId(), trashed.epoch()), PredictionEventType.WORK_TRASHED, signer.nodeId(), trashed.encode(), signer);
            return true;
        });
    }

    /**
     * Brings trashed work back: a work group of its own, with the original group's review policy, and the entry that
     * names it. The group's ID follows from the work and how often it was restored before, so a call repeated after a
     * crash between the two entries finds the group it made.
     *
     * @param laneId the trashed lane
     * @param epoch  its epoch
     * @return the new work group
     * @throws IOException              when the work is not in the trash or was emptied
     * @throws GeneralSecurityException when signing fails
     */
    public UUID restore(UUID laneId, long epoch) throws IOException, GeneralSecurityException {
        return appends.call(current -> {
            TrashProjection.Item item = current.trashProjection().item(laneId, epoch)
                    .orElseThrow(() -> new CoordinationException("TRASH_ITEM_NOT_FOUND"));
            if (item.emptied()) {
                throw new CoordinationException("TRASH_EMPTIED");
            }
            UUID groupId = UUID.nameUUIDFromBytes(("trash-restore:" + laneId + ":" + epoch + ":" + item.restoredInto().size())
                    .getBytes(StandardCharsets.UTF_8));
            if (current.workGroupProjection().group(groupId).isEmpty()) {
                WorkGroup original = current.workGroupProjection().group(item.trashed().workGroupId())
                        .orElseThrow(() -> new CoordinationException("WORK_GROUP_NOT_FOUND"));
                WorkGroup restoring = new WorkGroup(groupId, current.projectId(),
                        "Bring back the work of lane " + laneId + " (epoch " + epoch + "), trashed as " + item.trashed().reason()
                                + ", toward: " + original.goal(),
                        original.acceptance(), 1, WorkGroup.Status.ACTIVE, original.reviewPolicy());
                current.append(groupId, PredictionEventType.WORK_GROUP_CREATED, signer.nodeId(), CollaborationCodec.encodeWorkGroup(restoring), signer);
            }
            current.append(stream(laneId, epoch), PredictionEventType.TRASH_RESTORED, signer.nodeId(),
                    new TrashRestored(laneId, epoch, groupId).encode(), signer);
            return groupId;
        });
    }

    /**
     * Records that a person emptied trashed work for good. The caller deletes this computer's ref; every computer
     * deletes its own copy when it reads the entry.
     *
     * @param laneId the trashed lane
     * @param epoch  its epoch
     * @throws IOException              when the work is not in the trash or was emptied already
     * @throws GeneralSecurityException when signing fails
     */
    public void empty(UUID laneId, long epoch) throws IOException, GeneralSecurityException {
        appends.run(current -> current.append(stream(laneId, epoch), PredictionEventType.TRASH_EMPTIED, signer.nodeId(),
                new TrashEmptied(laneId, epoch).encode(), signer));
    }

    private static UUID stream(UUID laneId, long epoch) {
        return UUID.nameUUIDFromBytes(("trash:" + laneId + ":" + epoch).getBytes(StandardCharsets.UTF_8));
    }
}
