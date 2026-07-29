package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.link.identity.NodeIdentity;

/** Shared application service for durable work-group and lane-grant lifecycle. */
public final class WorkGroupService {
    private final PredictionEventStore store;
    private final NodeIdentity signer;

    /** Creates a service for one signed project log. @param store event store @param signer signer */
    public WorkGroupService(PredictionEventStore store, NodeIdentity signer) {
        this.store = Objects.requireNonNull(store, "store");
        this.signer = Objects.requireNonNull(signer, "signer");
    }

    /** Creates one logical group. @param group group @throws IOException persistence failure
     * @throws GeneralSecurityException signing failure */
    public void create(WorkGroup group) throws IOException, GeneralSecurityException {
        Objects.requireNonNull(group, "group");
        if (!store.projectId().equals(group.projectId())) throw new IllegalArgumentException("group project mismatch");
        withLock(current -> current.append(group.workGroupId(), PredictionEventType.WORK_GROUP_CREATED,
                signer.nodeId(), CollaborationCodec.encodeWorkGroup(group), signer));
    }

    /** Issues a targeted, optionally single-use lane continuation grant.
     * @param grant grant @throws IOException persistence failure @throws GeneralSecurityException signing failure */
    public void issue(LaneGrant grant) throws IOException, GeneralSecurityException {
        Objects.requireNonNull(grant, "grant");
        withLock(current -> current.append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED,
                signer.nodeId(), CollaborationCodec.encodeLaneGrant(grant), signer));
    }

    /** Consumes a grant for the exact target participant and expected epoch.
     * @param grantId grant ID @param participant target participant @param intentId target intent
     * @param expectedEpoch expected claim epoch @throws IOException invalid grant
     * @throws GeneralSecurityException signing failure */
    public void consume(UUID grantId, String participant, UUID intentId, long expectedEpoch)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(grantId, "grantId");
        withLock(current -> {
            LaneGrant grant = current.workGroupProjection().grants().stream()
                    .filter(candidate -> candidate.grantId().equals(grantId)).findFirst()
                    .orElseThrow(() -> new IOException("LANE_GRANT_NOT_FOUND"));
            if (!current.workGroupProjection().grantAvailable(grantId)) throw new IOException("LANE_GRANT_REPLAYED");
            if (!grant.targetParticipant().equals(participant) || !grant.targetIntentId().equals(intentId)) {
                throw new IOException("LANE_GRANT_TARGET_MISMATCH");
            }
            if (grant.claimEpoch() != expectedEpoch) throw new IOException("CLAIM_EPOCH_STALE");
            current.append(grantId, PredictionEventType.LANE_GRANT_CONSUMED,
                    signer.nodeId(), CollaborationCodec.encodeLaneGrant(grant), signer);
        });
    }

    /** Revokes a grant owner-independently. @param grantId grant ID @throws IOException invalid grant
     * @throws GeneralSecurityException signing failure */
    public void revoke(UUID grantId) throws IOException, GeneralSecurityException {
        Objects.requireNonNull(grantId, "grantId");
        withLock(current -> {
            LaneGrant grant = current.workGroupProjection().grants().stream()
                    .filter(candidate -> candidate.grantId().equals(grantId)).findFirst()
                    .orElseThrow(() -> new IOException("LANE_GRANT_NOT_FOUND"));
            current.append(grantId, PredictionEventType.LANE_REVOKED,
                    signer.nodeId(), CollaborationCodec.encodeLaneGrant(grant), signer);
        });
    }

    @FunctionalInterface
    private interface AppendAction { void append(PredictionEventStore current) throws IOException, GeneralSecurityException; }
    private void withLock(AppendAction action) throws IOException, GeneralSecurityException {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) throw new IOException("event append lock unavailable");
            action.append(new PredictionEventStore(store.rootDirectory(), store.projectId()));
        }
    }
}
