package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.domain.identity.LaneGrantId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.MissingReferenceException;

/**
 * Rebuilds logical work groups and single-use lane grants from signed events.
 */
public final class WorkGroupProjection {

    private final Map<WorkGroupId, WorkGroup> groups = new LinkedHashMap<>();
    private final Map<LaneGrantId, LaneGrant> grants = new LinkedHashMap<>();
    private final Set<LaneGrantId> consumedGrants = new HashSet<>();
    private final Set<LaneGrantId> continuationGrants = new HashSet<>();
    private final Set<LaneGrantId> supersededGrants = new HashSet<>();
    private final Set<LaneGrantId> revokedGrants = new HashSet<>();
    private final Map<LaneGrantId, ReviewValidationPayload> validations = new LinkedHashMap<>();
    /**
     * The node whose creation each group holds, or none for a group an announcement put here before any creation
     * (a log written before groups were created). Two computers apart may both create the first lane's group.
     */
    private final Map<WorkGroupId, String> creators = new LinkedHashMap<>();
    private final ApplyMode mode;

    /**
     * Creates an empty projection.
     */
    public WorkGroupProjection() {
        mode = ApplyMode.REPLAY;
    }

    private WorkGroupProjection(WorkGroupProjection source, ApplyMode mode) {
        this.mode = mode;
        groups.putAll(source.groups);
        grants.putAll(source.grants);
        consumedGrants.addAll(source.consumedGrants);
        continuationGrants.addAll(source.continuationGrants);
        supersededGrants.addAll(source.supersededGrants);
        revokedGrants.addAll(source.revokedGrants);
        validations.putAll(source.validations);
        creators.putAll(source.creators);
    }

    /**
     * Applies one group or grant event.
     *
     * @param event signed event
     * @throws IOException malformed transition
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        CoordinationNullException.requireNonNull(event, "event");
        switch (event.type()) {
            case WORK_GROUP_CREATED -> create(CollaborationCodec.decodeWorkGroup(event.payload()), event.actorNodeId());
            case LANE_GRANT_ISSUED -> issue(CollaborationCodec.decodeLaneGrant(event.payload()));
            case LANE_GRANT_CONSUMED -> consume(CollaborationCodec.decodeLaneGrant(event.payload()).identity());
            case LANE_REVOKED -> revoke(CollaborationCodec.decodeLaneGrant(event.payload()).identity());
            case WORK_GROUP_STATUS_CHANGED -> status(CollaborationCodec.decodeWorkGroup(event.payload()));
            case LANE_CONTINUATION_ACCEPTED -> {
                LaneContinuation continuation = CollaborationCodec.decodeContinuation(event.payload());
                migrateReviewGrants(continuation);
                LaneGrantId grantId = continuation.grant();
                if (!grants.containsKey(grantId)) {
                    WorkIntent target = continuation.targetIntent();
                    issue(new LaneGrant(grantId.value(),
                            target.workGroupId(),
                            target.intentId(),
                            continuation.targetParticipant(),
                            continuation.expectedEpoch(),
                            true));
                }
                continuationGrants.add(grantId);
                consume(grantId);
            }
            case WORK_INTENT_ANNOUNCED -> {
                WorkIntent intent = CollaborationCodec.decodeIntent(event.payload());
                if (mode == ApplyMode.ARRIVAL && !groups.containsKey(intent.group())) {
                    // The announcing computer creates a group it has not seen, before the announcement; a group
                    // missing here was created in another log that has not arrived.
                    throw new MissingReferenceException("WORK_GROUP_NOT_FOUND");
                }
                groups.putIfAbsent(intent.group(),
                        new WorkGroup(intent.workGroupId(),
                                intent.projectId(),
                                intent.goal(),
                                intent.acceptance(),
                                1,
                                WorkGroup.Status.ACTIVE));
            }
            case REVIEW_VALIDATION_RECORDED -> recordReview(ReviewValidationPayload.decode(event.payload()));
            default -> {
            }
        }
    }

    /**
     * Validates one event without mutating this projection.
     *
     * @param event event
     * @throws IOException invalid transition
     */
    public synchronized void validate(PredictionEvent event) throws IOException {
        validate(event, ApplyMode.CHECK);
    }

    /**
     * Applies an event to a copy of this projection, judged as {@code mode} says (ADR-0100, "Every rule an arriving
     * entry can reach").
     *
     * @param event event to judge
     * @param mode  how the event is judged
     * @throws IOException when the event is refused, or names something not seen yet
     */
    public synchronized void validate(PredictionEvent event, ApplyMode mode) throws IOException {
        new WorkGroupProjection(this, mode).apply(event);
    }

    /**
     * Returns all logical groups.
     *
     * @return groups
     */
    public synchronized List<WorkGroup> groups() {
        return List.copyOf(groups.values());
    }

    /**
     * Returns one group.
     *
     * @param id group ID
     * @return group when present
     */
    public synchronized Optional<WorkGroup> group(UUID id) {
        return group(new WorkGroupId(CoordinationNullException.requireNonNull(id, "work-group ID")));
    }

    /**
     * Returns a group using its typed work-group identity.
     *
     * @param id typed identity to look up
     * @return a group using its typed work-group identity.
     */
    public synchronized Optional<WorkGroup> group(WorkGroupId id) {
        return Optional.ofNullable(groups.get(CoordinationNullException.requireNonNull(id, "work-group ID")));
    }

    /**
     * Returns all issued grants.
     *
     * @return grants
     */
    public synchronized List<LaneGrant> grants() {
        return List.copyOf(grants.values());
    }

    /**
     * Returns whether a grant remains consumable.
     *
     * @param id grant ID
     * @return true when active
     */
    public synchronized boolean grantAvailable(UUID id) {
        return grantAvailable(new LaneGrantId(CoordinationNullException.requireNonNull(id, "grant ID")));
    }

    /**
     * Returns whether the typed grant identity is available for consumption.
     *
     * @param id typed identity to look up
     * @return whether the typed grant identity is available for consumption.
     */
    public synchronized boolean grantAvailable(LaneGrantId id) {
        LaneGrantId grantId = CoordinationNullException.requireNonNull(id, "grant ID");
        return grants.containsKey(grantId) && !consumedGrants.contains(grantId) && !supersededGrants.contains(grantId)
                && !revokedGrants.contains(grantId);
    }

    /**
     * Returns whether a grant was replaced by a lawful continuation transition.
     *
     * @param id grant identity
     * @return true when the historical grant is no longer current
     */
    public synchronized boolean grantSuperseded(UUID id) {
        return grantSuperseded(new LaneGrantId(CoordinationNullException.requireNonNull(id, "grant ID")));
    }

    /**
     * Returns whether a grant was revoked: what a stalled computer writes for the review grant of another computer's
     * reviewer that is away, so that a reviewer here may be admitted (ADR-0098 step 7, designer item 2).
     *
     * @param id typed identity to look up
     * @return whether it was revoked
     */
    public synchronized boolean grantRevoked(LaneGrantId id) {
        return revokedGrants.contains(CoordinationNullException.requireNonNull(id, "grant ID"));
    }

    /**
     * Returns whether a typed grant identity has been superseded.
     *
     * @param id typed identity to look up
     * @return whether a typed grant identity has been superseded.
     */
    public synchronized boolean grantSuperseded(LaneGrantId id) {
        return supersededGrants.contains(CoordinationNullException.requireNonNull(id, "grant ID"));
    }

    /**
     * Returns whether a grant has been consumed.
     *
     * @param id grant ID
     * @return true when consumed
     */
    public synchronized boolean grantConsumed(UUID id) {
        return grantConsumed(new LaneGrantId(CoordinationNullException.requireNonNull(id, "grant ID")));
    }

    /**
     * Returns whether a typed grant identity has been consumed.
     *
     * @param id typed identity to look up
     * @return whether a typed grant identity has been consumed.
     */
    public synchronized boolean grantConsumed(LaneGrantId id) {
        return consumedGrants.contains(CoordinationNullException.requireNonNull(id, "grant ID"));
    }

    /**
     * Returns whether a consumed grant authorized a continuation transition.
     *
     * @param id grant identity
     * @return true for continuation grants
     */
    public synchronized boolean continuationGrant(UUID id) {
        return continuationGrant(new LaneGrantId(CoordinationNullException.requireNonNull(id, "grant ID")));
    }

    /**
     * Returns whether a typed grant authorized a continuation transition.
     *
     * @param id typed identity to look up
     * @return whether a typed grant authorized a continuation transition.
     */
    public synchronized boolean continuationGrant(LaneGrantId id) {
        return continuationGrants.contains(CoordinationNullException.requireNonNull(id, "grant ID"));
    }

    /**
     * Returns whether a grant still obliges its holder: it is available, or consumed without a review decision,
     * and it is neither superseded nor a continuation grant.
     *
     * @param id typed grant identity
     * @return whether the grant is unresolved
     */
    public synchronized boolean grantUnresolved(LaneGrantId id) {
        return !continuationGrant(id) && !grantSuperseded(id)
                && (grantAvailable(id) || (grantConsumed(id) && reviewValidationForGrant(id).isEmpty()));
    }

    /**
     * Returns all recorded review decisions.
     *
     * @return immutable decisions
     */
    public synchronized List<ReviewValidationPayload> reviewValidations() {
        return List.copyOf(validations.values());
    }

    /**
     * Returns the decision recorded for one grant, when present.
     *
     * @param grantId grant ID
     * @return recorded decision
     */
    public synchronized Optional<ReviewValidationPayload> reviewValidationForGrant(UUID grantId) {
        return reviewValidationForGrant(new LaneGrantId(CoordinationNullException.requireNonNull(grantId, "grant ID")));
    }

    /**
     * Returns recorded review evidence for a typed grant identity.
     *
     * @param grantId lane-grant identity to look up
     * @return recorded review evidence for a typed grant identity.
     */
    public synchronized Optional<ReviewValidationPayload> reviewValidationForGrant(LaneGrantId grantId) {
        return Optional.ofNullable(validations.get(CoordinationNullException.requireNonNull(grantId, "grant ID")));
    }

    /**
     * Returns the decision recorded for one snapshot, when present.
     *
     * @param snapshotId snapshot ID
     * @return recorded decision
     */
    public synchronized Optional<ReviewValidationPayload> reviewValidationForSnapshot(String snapshotId) {
        return snapshotId == null ? Optional.empty() : reviewValidationForSnapshot(new SnapshotId(snapshotId));
    }

    /**
     * Returns the decision recorded for a typed snapshot identity, when present.
     *
     * @param snapshotId immutable snapshot identity
     * @return recorded decision
     */
    public synchronized Optional<ReviewValidationPayload> reviewValidationForSnapshot(SnapshotId snapshotId) {
        String snapshotValue = CoordinationNullException.requireNonNull(snapshotId, "snapshot ID").value();
        return validations.values().stream().filter(value -> value.snapshotId().equals(snapshotValue)).findFirst();
    }

    /**
     * Applies the create transition to the current coordination projection.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void create(WorkGroup group, String creator) throws IOException {
        WorkGroup existing = groups.get(group.identity());
        if (existing != null) {
            if (mode.checking()) {
                throw new CoordinationException("WORK_GROUP_EXISTS");
            }
            // Two computers apart created one group (ADR-0100, "Every rule an arriving entry can reach"): the
            // creation of the lower node gives its goal and policy, in either order, and what happened to the group
            // since stays.
            String held = creators.get(group.identity());
            if (held != null && held.compareTo(creator) <= 0) {
                return;
            }
            group = new WorkGroup(group.workGroupId(), group.projectId(), group.goal(), group.acceptance(),
                    existing.version(), existing.status(), group.reviewPolicy());
        }
        groups.put(group.identity(), group);
        creators.put(group.identity(), creator);
    }

    /**
     * Adds a lane grant after validating its unique identity and parent work group.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void issue(LaneGrant grant) throws IOException {
        if (grants.containsKey(grant.identity())) {
            throw new CoordinationException("LANE_GRANT_EXISTS");
        }
        if (!groups.containsKey(grant.group())) {
            throw new MissingReferenceException("WORK_GROUP_NOT_FOUND");
        }
        grants.put(grant.identity(), grant);
    }

    /**
     * Carries forward eligible unreviewed grants to a lawful successor lane: the reviews the source
     * owner held, and the snapshot-less grants on the source lane.
     *
     * <p>Both are carried whatever the lane's role. A producer may hold review grants (ADR-0089,
     * amended 2026-10-17), and carrying only a reviewer lane's left the grants of a producer that
     * died addressed to it, and the snapshots they named with nobody to review them.</p>
     *
     * @param continuation proposed successor continuation
     * @throws IOException when payload validation or durable I/O fails
     */
    private void migrateReviewGrants(LaneContinuation continuation) throws IOException {
        for (LaneGrant grant : List.copyOf(grants.values())) {
            if (!grant.group().equals(continuation.targetIntent().group())
                    || continuationGrants.contains(grant.identity()) || supersededGrants.contains(grant.identity())
                    || validations.containsKey(grant.identity())) {
                continue;
            }
            LaneGrant successor = reviewerSuccessor(grant, continuation);
            if (successor == null) {
                successor = producerSuccessor(grant, continuation);
            }
            if (successor == null) {
                continue;
            }
            supersededGrants.add(grant.identity());
            if (!grants.containsKey(successor.identity())) {
                issue(successor);
            }
        }
    }

    /**
     * Builds the successor of an unreviewed grant held by the source owner, now addressed to the target owner.
     *
     * @return successor grant, or {@code null} when the grant does not belong to the source owner
     */
    private static LaneGrant reviewerSuccessor(LaneGrant grant, LaneContinuation continuation) {
        if (!grant.targetParticipantIdentity().equals(continuation.sourceOwner())) {
            return null;
        }
        return new LaneGrant(successorGrantId(grant, continuation.targetOwner().value()),
                grant.group().value(),
                grant.targetIntent().value(),
                continuation.targetOwner().value(),
                grant.epoch().value(),
                grant.singleUse(),
                grant.snapshotId());
    }

    /**
     * Builds the successor of a snapshot-less grant on the source intent, now addressed to the target intent.
     *
     * @return successor grant, or {@code null} when the grant is for another intent or already names a snapshot
     */
    private static LaneGrant producerSuccessor(LaneGrant grant, LaneContinuation continuation) {
        if (!grant.targetIntent().equals(continuation.sourceIntent()) || grant.snapshotId() != null) {
            return null;
        }
        return new LaneGrant(successorGrantId(grant, continuation.targetIntent().identity().value()),
                grant.group().value(),
                continuation.targetIntent().identity().value(),
                grant.targetParticipantIdentity().value(),
                continuation.targetIntent().intentVersion().value(),
                grant.singleUse());
    }

    /**
     * Derives the persisted successor grant identifier; the seed text is part of the durable event history.
     */
    private static UUID successorGrantId(LaneGrant grant, Object target) {
        return UUID.nameUUIDFromBytes(
                ("review-successor-grant:" + grant.grantId() + ":" + target).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Consumes a grant once, rejecting missing, revoked, or replayed single-use grants.
     *
     * @param id typed grant identity
     * @throws IOException when payload validation or durable I/O fails
     */
    private void consume(LaneGrantId id) throws IOException {
        LaneGrant grant = grants.get(id);
        if (grant == null) {
            throw new MissingReferenceException("LANE_GRANT_NOT_FOUND");
        }
        if (mode.checking() && (revokedGrants.contains(id) || (grant.singleUse() && consumedGrants.contains(id)))) {
            // Off this computer's own append, a consumption and a revocation from two computers are both kept.
            throw new CoordinationException("LANE_GRANT_REPLAYED");
        }
        consumedGrants.add(id);
    }

    /**
     * Marks an existing grant revoked so later consumption attempts fail.
     *
     * @param grantId typed grant identity
     * @throws IOException when payload validation or durable I/O fails
     */
    private void revoke(LaneGrantId grantId) throws IOException {
        if (!grants.containsKey(grantId)) {
            throw new MissingReferenceException("LANE_GRANT_NOT_FOUND");
        }
        revokedGrants.add(grantId);
    }

    /**
     * Applies the next work-group version without allowing its review policy to change.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void status(WorkGroup update) throws IOException {
        WorkGroup current = groups.get(update.identity());
        if (current == null) {
            throw new MissingReferenceException("WORK_GROUP_NOT_FOUND");
        }
        if (!mode.checking()) {
            // A newer version replaces an older one, and a second change of one version counts as the first, with
            // COMPLETED over any other status (ADR-0098 step 4, designer item 16). Only the version and the status
            // move: what the group is stays as its creation gave it.
            boolean newer = update.version() > current.version();
            boolean outranks = update.version() == current.version() && rank(update.status()) > rank(current.status());
            if (newer || outranks) {
                groups.put(update.identity(), new WorkGroup(current.workGroupId(), current.projectId(), current.goal(),
                        current.acceptance(), update.version(), update.status(), current.reviewPolicy()));
            }
            return;
        }
        if (update.version() != current.version() + 1) {
            throw new CoordinationException("WORK_GROUP_VERSION_STALE");
        }
        if (!update.reviewPolicy().equals(current.reviewPolicy())) {
            throw new CoordinationException("WORK_GROUP_REVIEW_POLICY_IMMUTABLE");
        }
        groups.put(update.identity(), update);
    }

    /** Which of two statuses written for one version stands: COMPLETED, then the later in declaration order. */
    private static int rank(WorkGroup.Status status) {
        return status == WorkGroup.Status.COMPLETED ? Integer.MAX_VALUE : status.ordinal();
    }

    /**
     * Records review evidence only for a consumed, current grant bound to the exact target.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void recordReview(ReviewValidationPayload payload) throws IOException {
        LaneGrantId grantId = payload.grantIdentity();
        LaneGrant grant = grants.get(grantId);
        if (grant == null) {
            throw new MissingReferenceException("LANE_GRANT_NOT_FOUND");
        }
        if (continuationGrants.contains(grantId)) {
            throw new CoordinationException("REVIEW_SELF_APPROVAL_FORBIDDEN");
        }
        if (!consumedGrants.contains(grantId)) {
            throw new CoordinationException("LANE_GRANT_NOT_CONSUMED");
        }
        if (mode.checking() && supersededGrants.contains(grantId)) {
            throw new CoordinationException("REVIEW_GRANT_SUPERSEDED");
        }
        if (!grant.group().equals(payload.workGroupIdentity()) || !grant.targetIntent()
                .equals(payload.targetIntentIdentity()) || !grant.targetParticipantIdentity()
                .equals(payload.targetParticipantIdentity()) || !grant.epoch().equals(payload.claimEpochIdentity()) || (
                grant.snapshot().isPresent() && !grant.snapshot().equals(Optional.of(payload.snapshotIdentity())))) {
            throw new CoordinationException("REVIEW_GRANT_BINDING_MISMATCH");
        }
        // A grant's verdict is its reviewer's alone (the binding above), so a second one on the same grant is that
        // reviewer replacing its own, which the task projection allows only while the snapshot is disputed (ADR-0098
        // step 7, designer item 7); the newest stands here.
        validations.put(grantId, payload);
    }
}
