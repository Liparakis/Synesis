package org.synesis.coordination.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.CollaborationProjection;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.TaskCompletionProjection;
import org.synesis.coordination.domain.task.TaskCompletionState;

/**
 * Decides what a lane's declared dependency means once the peer that could have provided it is
 * finishing or has finished.
 *
 * <p>A capability request is a negotiation with a lane that is still open. When every other
 * producer lane of the project has prepared its completion there is nobody left to negotiate with,
 * and when such a lane's work is integrated the dependent lane already has that work in its base.
 * The rule looks across the whole project, not one work group: a lane announced after the others
 * finished starts a work group of its own, and is exactly the lane that most needs it. Without
 * this rule a dependent lane is asked forever to find an owner that cannot exist, and can never
 * finish. The rule grants no authority and records nothing: it only stops demanding a negotiation
 * that has become impossible, and only for dependencies the lane never obtained a request for.</p>
 */
public final class PeerDependencyPolicy {

    private PeerDependencyPolicy() {
    }

    /**
     * Returns the other lanes of the project that are still open and could own a capability.
     * Only producer lanes publish implementations, so a reviewer never counts as a possible owner.
     *
     * @param collaboration collaboration projection
     * @param intent        the dependent lane's intent
     * @return other participants' active producer intents
     */
    static List<WorkIntent> openPeers(CollaborationProjection collaboration, WorkIntent intent) {
        Objects.requireNonNull(collaboration, "collaboration");
        Objects.requireNonNull(intent, "intent");
        return collaboration.activeIntents().stream()
                .filter(candidate -> !candidate.participant().equals(intent.participant()))
                .filter(candidate -> candidate.role() == WorkIntent.Role.PRODUCER)
                .toList();
    }

    /**
     * Reports whether a lane has prepared its completion and can therefore no longer answer a new
     * capability request before it closes.
     *
     * @param completion task-completion projection
     * @param peer       the lane that would own the capability
     * @return {@code true} once the lane's completion is durably prepared
     */
    public static boolean closing(TaskCompletionProjection completion, WorkIntent peer) {
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(peer, "peer");
        return completion.findPrepared(peer.taskId(), peer.intentId(), peer.version()).isPresent();
    }

    /**
     * Reports whether every remaining peer is closing, so a dependent lane should wait for their
     * work to be integrated rather than look for an owner.
     *
     * @param collaboration collaboration projection
     * @param completion    task-completion projection
     * @param intent        the dependent lane's intent
     * @return {@code true} when at least one peer is still open and all open peers are closing
     */
    public static boolean awaitingPeerIntegration(CollaborationProjection collaboration, TaskCompletionProjection completion, WorkIntent intent) {
        List<WorkIntent> peers = openPeers(collaboration, intent);
        return !peers.isEmpty() && peers.stream().allMatch(peer -> closing(completion, peer));
    }

    /**
     * Returns the dependencies of a lane whose owner is known and has finished with its work
     * integrated: the lane holds a record for the dependency that was closed because the owner
     * finished. A record closed because the owner's lane ended with nothing integrated is not one.
     *
     * <p>Unlike {@link #satisfiedByIntegration}, this does not ask that no peer be open. That rule
     * exists because a dependency never bound names no owner, so any open peer might still be it.
     * Here the record names the owner. With a public contract the owner no longer waits for its
     * consumers before it can finish, so a consumer regularly arrives after the owner's work is
     * in, with other lanes still open; told to find an owner, it looked for one that no longer
     * existed until the run ended.</p>
     *
     * @param ownRequests every record the lane holds as requester, in any state
     * @return the dependency names those records carry
     */
    public static Set<String> satisfiedByIntegratedOwner(java.util.Collection<CapabilityRequestRecord> ownRequests) {
        Set<String> satisfied = new LinkedHashSet<>();
        for (CapabilityRequestRecord request : ownRequests) {
            // The reason is recorded only when the owner's lane is released after integration,
            // or when the lane binds to a contract frozen by it, so the record is the proof.
            if (request.ownerFinishedWithoutAnswer()) {
                satisfied.add(request.capability());
            }
        }
        return satisfied;
    }

    /**
     * Returns every declared dependency of a lane that integrated work satisfies, by either rule:
     * its owner is known and was integrated, or no owner was ever bound and no peer is left to be
     * one. The one place the two rules are combined.
     *
     * @param collaboration collaboration projection
     * @param completion    task-completion projection
     * @param intent        the dependent lane's intent
     * @param ownRequests   every record the lane holds as requester, in any state
     * @return dependencies to treat as satisfied; empty when none qualify
     */
    public static Set<String> satisfied(CollaborationProjection collaboration, TaskCompletionProjection completion, WorkIntent intent,
            java.util.Collection<CapabilityRequestRecord> ownRequests) {
        Set<String> standing = ownRequests.stream().filter(request -> !request.ownerLaneEnded()).map(CapabilityRequestRecord::capability).collect(java.util.stream.Collectors.toSet());
        Set<String> satisfied = new LinkedHashSet<>(satisfiedByIntegration(collaboration, completion, intent, standing));
        satisfied.addAll(satisfiedByIntegratedOwner(ownRequests));
        return satisfied;
    }

    /**
     * Returns the declared dependencies that integrated peer work now satisfies.
     *
     * <p>A dependency qualifies only when the lane holds no capability request for it, no peer
     * lane is open to own it, and at least one other participant's lane has been integrated. An
     * open peer, or a project in which nothing of another participant was integrated, leaves the
     * dependency unresolved: the first could still negotiate, the second offers nothing to build
     * on.</p>
     *
     * @param collaboration collaboration projection
     * @param completion    task-completion projection
     * @param intent        the dependent lane's intent
     * @param requested     capabilities this lane already holds a request for
     * @return dependencies to treat as satisfied, in declaration order; empty when none qualify
     */
    public static Set<String> satisfiedByIntegration(CollaborationProjection collaboration, TaskCompletionProjection completion, WorkIntent intent, Set<String> requested) {
        Objects.requireNonNull(requested, "requested");
        Set<String> unrequested = new LinkedHashSet<>(intent.knownDependencies());
        unrequested.removeAll(requested);
        if (unrequested.isEmpty() || !openPeers(collaboration, intent).isEmpty()) {
            return Set.of();
        }
        boolean peerWorkIntegrated = completion.allSnapshots().stream()
                .filter(snapshot -> !snapshot.provenance().participant().equals(intent.participant()))
                .anyMatch(snapshot -> completion.snapshotState(snapshot.snapshotId()).orElse(null) == TaskCompletionState.INTEGRATED);
        return peerWorkIntegrated ? unrequested : Set.of();
    }
}
