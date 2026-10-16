package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.AppendAdmission;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.LaneGrantId;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.identity.WorkGroupVersion;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Shared application service for durable work-group and lane-grant lifecycle.
 */
public final class WorkGroupService {

    private final PredictionEventStore store;
    private final NodeIdentity signer;
    private final ProjectAppends appends;

    /**
     * Creates a service for one signed project log.
     *
     * @param store  event store
     * @param signer signer
     */
    public WorkGroupService(PredictionEventStore store, NodeIdentity signer) {
        this.store = CoordinationNullException.requireNonNull(store, "store");
        this.signer = CoordinationNullException.requireNonNull(signer, "signer");
        this.appends = new ProjectAppends(store);
    }

    /**
     * Creates one logical group.
     *
     * @param group group
     * @throws IOException              persistence failure
     * @throws GeneralSecurityException signing failure
     */
    public void create(WorkGroup group) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(group, "group");
        if (!store.projectIdentity().equals(group.project())) {
            throw new CoordinationArgumentException("group project mismatch");
        }
        appends.run(current -> current.append(group.workGroupId(),
                PredictionEventType.WORK_GROUP_CREATED,
                signer.nodeId(),
                CollaborationCodec.encodeWorkGroup(group),
                signer));
    }

    /**
     * Issues a targeted, optionally single-use lane continuation grant.
     *
     * @param grant grant
     * @throws IOException              persistence failure
     * @throws GeneralSecurityException signing failure
     */
    public void issue(LaneGrant grant) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(grant, "grant");
        appends.run(current -> current.append(grant.grantId(),
                PredictionEventType.LANE_GRANT_ISSUED,
                signer.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant),
                signer));
    }

    /**
     * Consumes a grant for the exact target participant and expected epoch.
     *
     * @param grantId       grant ID
     * @param participant   target participant
     * @param intentId      target intent
     * @param expectedEpoch expected claim epoch
     * @throws IOException              invalid grant
     * @throws GeneralSecurityException signing failure
     */
    public void consume(UUID grantId, String participant, UUID intentId, long expectedEpoch)
            throws IOException, GeneralSecurityException {
        consume(new LaneGrantId(grantId),
                new ParticipantId(participant),
                new WorkIntentId(intentId),
                new ClaimEpoch(expectedEpoch));
    }

    /**
     * Consumes the grant only for its exact participant, intent, and claim epoch.
     *
     * @param grantIdentity       typed lane-grant identity
     * @param participantIdentity typed participant identity consuming the grant
     * @param intentIdentity      typed work-intent identity bound to the grant
     * @param expectedEpoch       expected claim epoch for stale-action fencing
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void consume(LaneGrantId grantIdentity,
            ParticipantId participantIdentity,
            WorkIntentId intentIdentity,
            ClaimEpoch expectedEpoch) throws IOException, GeneralSecurityException {
        LaneGrantId grantKey = CoordinationNullException.requireNonNull(grantIdentity, "grant ID");
        ParticipantId participant = CoordinationNullException.requireNonNull(participantIdentity, "participant");
        WorkIntentId intent = CoordinationNullException.requireNonNull(intentIdentity, "intent ID");
        ClaimEpoch epoch = CoordinationNullException.requireNonNull(expectedEpoch, "claim epoch");
        UUID grantId = grantKey.value();
        appends.run(current -> {
            if (current.collaborationProjection().isParticipantTerminal(participant)) {
                throw new CoordinationException("SESSION_TERMINAL");
            }
            LaneGrant grant = current.workGroupProjection().grants().stream()
                    .filter(candidate -> candidate.identity().equals(grantKey)).findFirst()
                    .orElseThrow(() -> new CoordinationException("LANE_GRANT_NOT_FOUND"));
            if (!current.workGroupProjection().grantAvailable(grantKey)) {
                throw new CoordinationException("LANE_GRANT_REPLAYED");
            }
            AppendAdmission.requireGrantTargetCurrent(current, grant);
            if (!grant.targetParticipantIdentity().equals(participant) || !grant.targetIntent().equals(intent)) {
                throw new CoordinationException("LANE_GRANT_TARGET_MISMATCH");
            }
            if (!grant.epoch().equals(epoch)) {
                throw new CoordinationException("CLAIM_EPOCH_STALE");
            }
            current.append(grantId,
                    PredictionEventType.LANE_GRANT_CONSUMED,
                    signer.nodeId(),
                    CollaborationCodec.encodeLaneGrant(grant),
                    signer);
        });
    }

    /**
     * Revokes a grant owner-independently.
     *
     * @param grantId grant ID
     * @throws IOException              invalid grant
     * @throws GeneralSecurityException signing failure
     */
    public void revoke(UUID grantId) throws IOException, GeneralSecurityException {
        revoke(new LaneGrantId(grantId));
    }

    /**
     * Revokes the exact lane grant identified by its domain type.
     *
     * @param grantIdentity typed lane-grant identity
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void revoke(LaneGrantId grantIdentity) throws IOException, GeneralSecurityException {
        LaneGrantId grantKey = CoordinationNullException.requireNonNull(grantIdentity, "grant ID");
        UUID grantId = grantKey.value();
        appends.run(current -> {
            LaneGrant grant = current.workGroupProjection().grants().stream()
                    .filter(candidate -> candidate.identity().equals(grantKey)).findFirst()
                    .orElseThrow(() -> new CoordinationException("LANE_GRANT_NOT_FOUND"));
            current.append(grantId,
                    PredictionEventType.LANE_REVOKED,
                    signer.nodeId(),
                    CollaborationCodec.encodeLaneGrant(grant),
                    signer);
        });
    }

    /**
     * Closes or cancels the logical group without releasing sibling lane claims.
     *
     * @param groupId         group ID
     * @param status          terminal status
     * @param expectedVersion current version
     * @throws IOException              invalid transition
     * @throws GeneralSecurityException signing failure
     */
    public void close(UUID groupId, WorkGroup.Status status, long expectedVersion)
            throws IOException, GeneralSecurityException {
        close(new WorkGroupId(groupId), status, new WorkGroupVersion(expectedVersion));
    }

    /**
     * Closes the group only at the supplied typed group version.
     *
     * @param groupIdentity   typed work-group identity to close
     * @param status          projected status
     * @param expectedVersion value used by this operation
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void close(WorkGroupId groupIdentity, WorkGroup.Status status, WorkGroupVersion expectedVersion)
            throws IOException, GeneralSecurityException {
        WorkGroupId group = CoordinationNullException.requireNonNull(groupIdentity, "work group ID");
        WorkGroupVersion version = CoordinationNullException.requireNonNull(expectedVersion, "work group version");
        WorkGroup.Status finalStatus = CoordinationNullException.requireNonNull(status, "group status");
        UUID groupId = group.value();
        appends.run(current -> {
            WorkGroup existing = current.workGroupProjection().group(group)
                    .orElseThrow(() -> new CoordinationException("WORK_GROUP_NOT_FOUND"));
            if (!existing.groupVersion().equals(version)) {
                throw new CoordinationException("WORK_GROUP_VERSION_STALE");
            }
            if (finalStatus == WorkGroup.Status.ACTIVE) {
                throw new CoordinationException("GROUP_STATUS_INVALID");
            }
            WorkGroup update = new WorkGroup(groupId,
                    existing.projectId(),
                    existing.goal(),
                    existing.acceptance(),
                    existing.version() + 1,
                    finalStatus,
                    existing.reviewPolicy());
            current.append(groupId,
                    PredictionEventType.WORK_GROUP_STATUS_CHANGED,
                    signer.nodeId(),
                    CollaborationCodec.encodeWorkGroup(update),
                    signer);
        });
    }
}
