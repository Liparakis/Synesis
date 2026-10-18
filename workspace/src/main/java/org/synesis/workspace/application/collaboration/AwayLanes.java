package org.synesis.workspace.application.collaboration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ReviewAdmissionRules;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.replication.LeaderReplication;
import org.synesis.coordination.replication.LeaderReplication.MemberConnection;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * The lanes of computers that are away, which this computer's agents may take over while it is stalled (ADR-0098
 * step 7, designer items 9 to 11 and 17). Each computer judges this for itself, from its own log and its own record of
 * its sessions; no entry says a computer is away, and no timer decides it.
 *
 * <p>This computer is stalled when none of its agents holds an open lane in an active work group ({@link #stalled}),
 * the one test the offer, the takeover and the release of reviews share. Another computer is away when this
 * computer's record of its member sessions ({@code replication.members}, written whole by its replication side, not
 * the rotating journal) says that member's session ended, or names no session of it since the record was written; a
 * redial within Link's retained time does not change it. With no readable record nobody is away: a takeover of a
 * computer that is in fact connected is the failure to avoid. A review offered to the caller comes before any
 * continuation, as for recovery candidates.</p>
 */
public final class AwayLanes {

    private static final System.Logger LOG = System.getLogger(AwayLanes.class.getName());

    private AwayLanes() {
    }

    /**
     * Whether this computer is stalled: none of its agents holds an open lane in an active work group.
     *
     * @param store    current projection
     * @param thisNode this computer's node ID
     * @return whether it is stalled
     */
    public static boolean stalled(PredictionEventStore store, String thisNode) {
        var collaboration = store.collaborationProjection();
        return collaboration.activeIntents().stream().filter(intent -> activeGroup(store, intent))
                .noneMatch(intent -> collaboration.introducingNode(intent.participantIdentity()).map(thisNode::equals).orElse(false));
    }

    /**
     * The other computers that are away now, by this computer's record of its member sessions; none when there is no
     * readable record.
     *
     * @param coordination this project's coordination folder
     * @param thisNode     this computer's node ID
     * @return a test of whether a node is away
     */
    public static Predicate<String> away(Path coordination, String thisNode) {
        Optional<Map<String, MemberConnection>> recorded = LeaderReplication.recordedMembers(coordination);
        if (recorded.isEmpty()) {
            return node -> false;
        }
        Map<String, MemberConnection> members = recorded.get();
        return node -> !node.equals(thisNode)
                && Optional.ofNullable(members.get(node)).map(connection -> connection.state() != MemberConnection.State.CONNECTED).orElse(true);
    }

    /**
     * The lanes this computer's lane-less agents may take over now: none unless it is stalled.
     *
     * @param store        current projection
     * @param coordination this project's coordination folder, which holds the record of member sessions
     * @param thisNode     this computer's node ID
     * @return the away lanes, in the order they are on the log
     */
    public static List<WorkIntent> offered(PredictionEventStore store, Path coordination, String thisNode) {
        if (!stalled(store, thisNode)) {
            return List.of();
        }
        Predicate<String> away = away(coordination, thisNode);
        var collaboration = store.collaborationProjection();
        return collaboration.activeIntents().stream().filter(intent -> activeGroup(store, intent))
                .filter(intent -> collaboration.introducingNode(intent.participantIdentity()).map(away::test).orElse(false)).toList();
    }

    /**
     * Releases the reviews that reviewers of away computers hold while this computer is stalled (ADR-0098 step 7,
     * designer item 17): a review grant on a snapshot still under review, with no verdict, whose lane-less reviewer
     * another computer put on the log, is revoked, so a reviewer here may be admitted. A reviewer that holds a lane
     * needs no release: taking its lane over carries its grants to the taker. Each grant is revoked at most once; the
     * revocation re-checks the grant under the append lock. It runs in the integration pass and never throws.
     *
     * @param store        current projection
     * @param coordination this project's coordination folder
     * @param identity     this computer
     */
    public static void releaseStalledReviews(PredictionEventStore store, Path coordination, NodeIdentity identity) {
        try {
            String thisNode = identity.nodeId();
            if (!stalled(store, thisNode)) {
                return;
            }
            Predicate<String> away = away(coordination, thisNode);
            var collaboration = store.collaborationProjection();
            var workGroups = store.workGroupProjection();
            var service = new WorkGroupService(store, identity);
            for (LaneGrant grant : workGroups.grants()) {
                boolean stalledReview = grant.snapshot().isPresent() && ReviewAdmissionRules.holdsReview(store, grant)
                        && workGroups.reviewValidationForGrant(grant.identity()).isEmpty()
                        && store.taskCompletionProjection().snapshotState(grant.snapshot().get()).orElse(null) == TaskCompletionState.REVIEW_PENDING
                        && collaboration.activeIntents().stream().noneMatch(intent -> intent.participant().equals(grant.targetParticipant()))
                        && collaboration.introducingNode(new ParticipantId(grant.targetParticipant())).map(away::test).orElse(false);
                if (stalledReview) {
                    service.revokeStalledReview(grant.identity());
                }
            }
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.WARNING, "stalled reviews not released; the next pass tries again", failure);
        }
    }

    private static boolean activeGroup(PredictionEventStore store, WorkIntent intent) {
        return store.workGroupProjection().group(intent.workGroupId()).map(group -> group.status() == WorkGroup.Status.ACTIVE).orElse(false);
    }

    /**
     * Whether another computer put this lane's owner on the log: its lane is continued by a takeover, never from a
     * recovery hold here.
     *
     * @param store    current projection
     * @param lane     the lane
     * @param thisNode this computer's node ID
     * @return whether the lane is another computer's
     */
    public static boolean anotherComputers(PredictionEventStore store, WorkIntent lane, String thisNode) {
        return store.collaborationProjection().introducingNode(lane.participantIdentity()).map(node -> !node.equals(thisNode)).orElse(false);
    }

    /**
     * The snapshot a takeover starts from: the lane's newest snapshot whose commit reached this computer, or empty when
     * none did and the takeover starts from the lane's base.
     *
     * @param store       current projection
     * @param controlRoot the project's checkout, whose repository holds what reached this computer
     * @param lane        the lane taken over
     * @return the snapshot, or empty
     */
    public static Optional<TaskSnapshotRecord> lastSnapshotHere(PredictionEventStore store, Path controlRoot, WorkIntent lane) {
        List<TaskSnapshotRecord> ofLane = store.taskCompletionProjection().allSnapshots().stream()
                .filter(snapshot -> snapshot.provenance().authorityLineageId().equals(lane.authorityLineageId())).toList();
        for (TaskSnapshotRecord snapshot : ofLane.reversed()) {
            if (present(controlRoot, snapshot.commitSha()) && present(controlRoot, snapshot.baseCommit())) {
                return Optional.of(snapshot);
            }
        }
        return Optional.empty();
    }

    /**
     * The recovery reference a takeover's entry carries: the snapshot it starts from, or the lane's base.
     *
     * @param snapshot the snapshot, when one reached this computer
     * @param lane     the lane taken over
     * @return the reference
     */
    public static String reference(Optional<TaskSnapshotRecord> snapshot, WorkIntent lane) {
        return snapshot.map(found -> LaneContinuation.TAKEOVER_FROM_SNAPSHOT + found.snapshotId())
                .orElse(LaneContinuation.TAKEOVER_FROM_BASE + lane.baseCommit());
    }

    private static boolean present(Path controlRoot, String commit) {
        try {
            return commit != null && GitProcessRunner.runResult(controlRoot, "cat-file", "-e", commit + "^{commit}").exitCode() == 0;
        } catch (IOException unreadable) {
            return false;
        }
    }
}
