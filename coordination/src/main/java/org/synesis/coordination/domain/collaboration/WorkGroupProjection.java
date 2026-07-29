package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;

/** Rebuilds logical work groups and single-use lane grants from signed events. */
public final class WorkGroupProjection {
    private final Map<UUID, WorkGroup> groups = new LinkedHashMap<>();
    private final Map<UUID, LaneGrant> grants = new LinkedHashMap<>();
    private final Set<UUID> consumedGrants = new HashSet<>();
    private final Set<UUID> revokedGrants = new HashSet<>();

    /** Creates an empty projection. */
    public WorkGroupProjection() { }

    /** Applies one group or grant event.
     * @param event signed event
     * @throws IOException malformed transition
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
        switch (event.type()) {
            case WORK_GROUP_CREATED -> create(CollaborationCodec.decodeWorkGroup(event.payload()));
            case LANE_GRANT_ISSUED -> issue(CollaborationCodec.decodeLaneGrant(event.payload()));
            case LANE_GRANT_CONSUMED -> consume(CollaborationCodec.decodeLaneGrant(event.payload()).grantId());
            case LANE_REVOKED -> revoke(CollaborationCodec.decodeLaneGrant(event.payload()).grantId());
            case WORK_GROUP_STATUS_CHANGED -> status(CollaborationCodec.decodeWorkGroup(event.payload()));
            case WORK_INTENT_ANNOUNCED -> {
                WorkIntent intent = CollaborationCodec.decodeIntent(event.payload());
                groups.putIfAbsent(intent.workGroupId(), new WorkGroup(intent.workGroupId(), intent.projectId(),
                        intent.goal(), intent.acceptance(), 1, WorkGroup.Status.ACTIVE));
            }
            default -> { }
        }
    }

    /** Validates one event without mutating this projection.
     * @param event event
     * @throws IOException invalid transition
     */
    public synchronized void validate(PredictionEvent event) throws IOException {
        WorkGroupProjection copy = new WorkGroupProjection();
        copy.groups.putAll(groups); copy.grants.putAll(grants);
        copy.consumedGrants.addAll(consumedGrants); copy.revokedGrants.addAll(revokedGrants);
        copy.apply(event);
    }

    /** Returns all logical groups.
     * @return groups */
    public synchronized List<WorkGroup> groups() { return List.copyOf(groups.values()); }
    /** Returns one group.
     * @param id group ID
     * @return group when present
     */
    public synchronized Optional<WorkGroup> group(UUID id) { return Optional.ofNullable(groups.get(id)); }
    /** Returns all issued grants.
     * @return grants */
    public synchronized List<LaneGrant> grants() { return List.copyOf(grants.values()); }
    /** Returns whether a grant remains consumable.
     * @param id grant ID
     * @return true when active
     */
    public synchronized boolean grantAvailable(UUID id) {
        return grants.containsKey(id) && !consumedGrants.contains(id) && !revokedGrants.contains(id);
    }

    private void create(WorkGroup group) throws IOException {
        if (groups.containsKey(group.workGroupId())) throw new IOException("WORK_GROUP_EXISTS");
        groups.put(group.workGroupId(), group);
    }
    private void issue(LaneGrant grant) throws IOException {
        if (grants.containsKey(grant.grantId())) throw new IOException("LANE_GRANT_EXISTS");
        if (!groups.containsKey(grant.workGroupId())) throw new IOException("WORK_GROUP_NOT_FOUND");
        grants.put(grant.grantId(), grant);
    }
    private void consume(UUID id) throws IOException {
        LaneGrant grant = grants.get(id);
        if (grant == null) throw new IOException("LANE_GRANT_NOT_FOUND");
        if (revokedGrants.contains(id) || (grant.singleUse() && consumedGrants.contains(id))) {
            throw new IOException("LANE_GRANT_REPLAYED");
        }
        consumedGrants.add(id);
    }
    private void revoke(UUID id) throws IOException {
        if (!grants.containsKey(id)) throw new IOException("LANE_GRANT_NOT_FOUND");
        revokedGrants.add(id);
    }
    private void status(WorkGroup update) throws IOException {
        WorkGroup current = groups.get(update.workGroupId());
        if (current == null) throw new IOException("WORK_GROUP_NOT_FOUND");
        if (update.version() != current.version() + 1) throw new IOException("WORK_GROUP_VERSION_STALE");
        groups.put(update.workGroupId(), update);
    }
}
