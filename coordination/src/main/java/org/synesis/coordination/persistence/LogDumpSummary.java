package org.synesis.coordination.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.persistence.LogDump.Entry;
import org.synesis.coordination.persistence.LogDump.Fact;

/**
 * What the projections derive from the logs, for {@code synesis log dump}: lanes and their state, work groups,
 * snapshots with their verdicts, review grants, combines and membership. Every fact is read from the projections of
 * a read-only replay of the folder, the same replay a running computer makes; nothing here judges an entry.
 */
final class LogDumpSummary {

    private LogDumpSummary() {
    }

    /**
     * Derives the summary.
     *
     * @param root      the coordination folder
     * @param projectId the project
     * @param entries   the dump's entries, which say which lanes the logs name
     * @return the facts, or one {@code SUMMARY_UNAVAILABLE} fact with the reason the logs could not be replayed
     */
    static List<Fact> of(Path root, UUID projectId, List<Entry> entries) {
        PredictionEventStore store;
        try {
            store = PredictionEventStore.openReadOnly(root, projectId);
        } catch (Exception unreplayable) {
            String reason = unreplayable.getMessage() == null ? unreplayable.getClass().getSimpleName()
                    : unreplayable.getMessage();
            return List.of(new Fact("SUMMARY_UNAVAILABLE", LogDumpFields.clean(reason)));
        }
        List<Fact> facts = new ArrayList<>();
        lanes(store, entries, facts);
        groups(store, facts);
        snapshots(store, facts);
        grants(store, facts);
        combines(store, facts);
        membership(store, facts);
        return facts;
    }

    private static void lanes(PredictionEventStore store, List<Entry> entries, List<Fact> facts) {
        Set<String> ids = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry.type().equals("WORK_INTENT_ANNOUNCED") && entry.fields().containsKey("lane")) {
                ids.add(entry.fields().get("lane"));
            }
        }
        facts.add(new Fact("SUMMARY_LANES", String.valueOf(ids.size())));
        var collaboration = store.collaborationProjection();
        for (String id : ids) {
            UUID lane = UUID.fromString(id);
            boolean active = collaboration.intent(lane).isPresent();
            var seen = collaboration.intent(lane).or(() -> collaboration.lane(lane));
            StringBuilder text = new StringBuilder(id).append(" state=")
                    .append(seen.isEmpty() ? "UNKNOWN" : active ? "ACTIVE" : "ENDED");
            seen.ifPresent(intent -> text.append(" status=").append(intent.status()).append(" role=")
                    .append(intent.role()).append(" participant=").append(LogDumpFields.clean(intent.participant()))
                    .append(" group=").append(intent.workGroupId()).append(" task=").append(intent.taskId()));
            collaboration.successorIntent(lane).ifPresent(successor -> text.append(" successor=").append(successor));
            facts.add(new Fact("SUMMARY_LANE", text.toString()));
        }
    }

    private static void groups(PredictionEventStore store, List<Fact> facts) {
        var groups = store.workGroupProjection().groups();
        facts.add(new Fact("SUMMARY_GROUPS", String.valueOf(groups.size())));
        groups.forEach(group -> facts.add(new Fact("SUMMARY_GROUP", group.workGroupId() + " status=" + group.status()
                + " version=" + group.version())));
    }

    private static void snapshots(PredictionEventStore store, List<Fact> facts) {
        var completion = store.taskCompletionProjection();
        var snapshots = completion.allSnapshots();
        Set<SnapshotId> disputed = new LinkedHashSet<>(completion.disputedSnapshots());
        facts.add(new Fact("SUMMARY_SNAPSHOTS", String.valueOf(snapshots.size())));
        for (var snapshot : snapshots) {
            SnapshotId id = new SnapshotId(snapshot.snapshotId());
            StringBuilder text = new StringBuilder(LogDumpFields.clean(snapshot.snapshotId())).append(" task=")
                    .append(snapshot.taskId()).append(" state=")
                    .append(completion.snapshotState(snapshot.snapshotId()).map(Enum::name).orElse("UNKNOWN"));
            Map<String, String> verdicts = completion.verdicts(id);
            if (!verdicts.isEmpty()) {
                text.append(" verdicts=").append(verdicts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(verdict -> LogDumpFields.clean(verdict.getKey()) + ":" + LogDumpFields.clean(
                                verdict.getValue())).collect(Collectors.joining(",")));
            }
            if (disputed.contains(id)) {
                text.append(" disputed=true");
            }
            facts.add(new Fact("SUMMARY_SNAPSHOT", text.toString()));
        }
    }

    private static void grants(PredictionEventStore store, List<Fact> facts) {
        var groups = store.workGroupProjection();
        var grants = groups.grants();
        facts.add(new Fact("SUMMARY_GRANTS", String.valueOf(grants.size())));
        for (var grant : grants) {
            String state = groups.grantConsumed(grant.grantId()) ? "CONSUMED" : groups.grantRevoked(grant.identity())
                    ? "REVOKED" : groups.grantSuperseded(grant.grantId()) ? "SUPERSEDED"
                    : groups.grantAvailable(grant.grantId()) ? "AVAILABLE" : "UNRESOLVED";
            StringBuilder text = new StringBuilder().append(grant.grantId()).append(" state=").append(state)
                    .append(" group=").append(grant.workGroupId()).append(" lane=").append(grant.targetIntentId());
            if (grant.snapshotId() != null && !grant.snapshotId().isBlank()) {
                text.append(" snapshot=").append(LogDumpFields.clean(grant.snapshotId()));
            }
            facts.add(new Fact("SUMMARY_GRANT", text.toString()));
        }
        for (var review : groups.reviewValidations()) {
            facts.add(new Fact("SUMMARY_REVIEW", review.grantId() + " snapshot=" + LogDumpFields.clean(
                    review.snapshotId()) + " verdict=" + LogDumpFields.clean(review.result()) + " reviewer="
                    + LogDumpFields.clean(review.sourceParticipant())));
        }
    }

    private static void combines(PredictionEventStore store, List<Fact> facts) {
        var combines = store.taskCompletionProjection().combines();
        facts.add(new Fact("SUMMARY_COMBINES", String.valueOf(combines.size())));
        combines.forEach(combine -> facts.add(new Fact("SUMMARY_COMBINE", LogDumpFields.clean(combine.combineId())
                + " remerge=" + LogDumpFields.clean(combine.remergeId()) + " snapshots="
                + LogDumpFields.list(combine.snapshotIds()))));
    }

    private static void membership(PredictionEventStore store, List<Fact> facts) {
        var membership = store.membershipProjection();
        List<String> members = membership.members().stream().sorted().toList();
        facts.add(new Fact("SUMMARY_MEMBERSHIP", "shared=" + membership.shared() + " owner="
                + LogDumpFields.clean(membership.owner().orElse("")) + " policy=" + membership.policy() + " revision="
                + membership.revision() + " members=" + LogDumpFields.list(members)));
        membership.openRequests().forEach(request -> facts.add(new Fact("SUMMARY_JOIN_REQUEST", request.requestId()
                + " member=" + LogDumpFields.clean(request.joinerNodeId()) + " inviter="
                + LogDumpFields.clean(request.inviterNodeId()))));
    }
}
