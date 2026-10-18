package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinAnswered;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinPolicy;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinPolicySet;
import org.synesis.coordination.domain.membership.MembershipEntries.JoinRequested;
import org.synesis.coordination.domain.membership.MembershipEntries.MemberCard;
import org.synesis.coordination.domain.membership.MembershipEntries.MemberRemoved;
import org.synesis.coordination.domain.membership.MembershipEntries.OwnerChanged;
import org.synesis.coordination.domain.membership.MembershipEntries.ProjectShared;
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.domain.membership.MembershipProjection.JoinRequest;
import org.synesis.coordination.domain.membership.RecoveryKey;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Writes this computer's membership entries (ADR-0098 step 6, designer items 7 to 15). Each runs under the project
 * append lock on a log read under it, so the revision an owner statement carries is the one it is judged at, and two
 * writers on this computer never take the same one.
 */
public final class MembershipService {

    private final NodeIdentity signer;
    private final ProjectAppends appends;

    /**
     * Writes this computer's membership entries to its log.
     *
     * @param store  this computer's log
     * @param signer this computer
     */
    public MembershipService(PredictionEventStore store, NodeIdentity signer) {
        this.signer = CoordinationNullException.requireNonNull(signer, "signer");
        this.appends = new ProjectAppends(CoordinationNullException.requireNonNull(store, "store"));
    }

    /**
     * Shares the project with this computer as the owner, unless it is shared already.
     *
     * @param recovery the recovery key to name, or empty for none yet
     * @return whether {@code PROJECT_SHARED} was written now
     * @throws IOException              when the entry is refused
     * @throws GeneralSecurityException when signing fails
     */
    public boolean share(Optional<RecoveryKey> recovery) throws IOException, GeneralSecurityException {
        return appends.call(current -> {
            if (current.membershipProjection().shared()) {
                return false;
            }
            // A computer that joined another's project waits for that owner's entries: it never names itself the owner.
            if (joinedAnother(current)) {
                throw new CoordinationException("JOINED_ANOTHER_PROJECT");
            }
            byte[] recoveryKey = recovery.map(RecoveryKey::publicKey).orElse(new byte[0]);
            current.append(UUID.randomUUID(), PredictionEventType.PROJECT_SHARED, signer.nodeId(),
                    new ProjectShared(signer.publicKeyEncoded(), recoveryKey).encode(), signer);
            return true;
        });
    }

    /**
     * Names a new recovery key, keeping this computer as the owner: for a project shared with none, or to replace it.
     *
     * @param recovery the new recovery key
     * @throws IOException              when this computer is not the owner, or the entry is refused
     * @throws GeneralSecurityException when signing fails
     */
    public void replaceRecoveryKey(RecoveryKey recovery) throws IOException, GeneralSecurityException {
        appends.run(current -> {
            MembershipProjection membership = current.membershipProjection();
            current.append(UUID.randomUUID(), PredictionEventType.OWNER_CHANGED, signer.nodeId(),
                    new OwnerChanged(signer.publicKeyEncoded(), recovery.publicKey(), membership.revision(), new byte[0]).encode(), signer);
        });
    }

    /**
     * Records that this computer holds a joiner's session and asks for it to be let in, unless a request of that
     * joiner is still waiting or it is a member already.
     *
     * @param joinerNodeId the joining computer
     * @return the waiting request's ID, or empty when the joiner is a member already
     * @throws IOException              when the entry is refused, for example because this computer is not a member
     * @throws GeneralSecurityException when signing fails
     */
    public Optional<UUID> requestJoin(String joinerNodeId) throws IOException, GeneralSecurityException {
        return appends.call(current -> {
            MembershipProjection membership = current.membershipProjection();
            if (membership.shared() && membership.members().contains(joinerNodeId)) {
                return Optional.empty();
            }
            Optional<JoinRequest> waiting = waitingRequest(membership, joinerNodeId);
            if (waiting.isPresent()) {
                return Optional.of(waiting.get().requestId());
            }
            UUID requestId = UUID.randomUUID();
            current.append(requestId, PredictionEventType.JOIN_REQUESTED, signer.nodeId(), new JoinRequested(requestId, joinerNodeId).encode(), signer);
            return Optional.of(requestId);
        });
    }

    /**
     * Answers the waiting request of a joiner. Accepting a joiner that is a member already writes nothing, so an
     * acceptance repeated after a lost response or a half-done admission succeeds.
     *
     * @param joinerNodeId the joining computer
     * @param accept       whether to let it in
     * @throws IOException              {@code JOIN_REQUEST_UNKNOWN} when none waits; or the refusal of the entry
     * @throws GeneralSecurityException when signing fails
     */
    public void answer(String joinerNodeId, boolean accept) throws IOException, GeneralSecurityException {
        appends.run(current -> {
            MembershipProjection membership = current.membershipProjection();
            Optional<JoinRequest> waiting = waitingRequest(membership, joinerNodeId);
            if (waiting.isEmpty() && accept && membership.shared() && membership.members().contains(joinerNodeId)) {
                return;
            }
            JoinRequest request = waiting.orElseThrow(() -> new CoordinationException("JOIN_REQUEST_UNKNOWN"));
            current.append(UUID.randomUUID(), PredictionEventType.JOIN_ANSWERED, signer.nodeId(),
                    new JoinAnswered(request.requestId(), joinerNodeId, accept, membership.revision()).encode(), signer);
        });
    }

    /**
     * Says who may answer a join besides the owner.
     *
     * @param policy the policy
     * @throws IOException              when this computer is not the owner
     * @throws GeneralSecurityException when signing fails
     */
    public void setPolicy(JoinPolicy policy) throws IOException, GeneralSecurityException {
        appends.run(current -> current.append(UUID.randomUUID(), PredictionEventType.JOIN_POLICY_SET, signer.nodeId(),
                new JoinPolicySet(policy, current.membershipProjection().revision()).encode(), signer));
    }

    /**
     * Removes a member, naming the head of its log this computer holds.
     *
     * @param nodeId the member
     * @throws IOException              when this computer is not the owner or the node is not a removable member
     * @throws GeneralSecurityException when signing fails
     */
    public void remove(String nodeId) throws IOException, GeneralSecurityException {
        appends.run(current -> {
            long head = current.logHeads().getOrDefault(nodeId, 0L);
            current.append(UUID.randomUUID(), PredictionEventType.MEMBER_REMOVED, signer.nodeId(),
                    new MemberRemoved(nodeId, head, current.membershipProjection().revision()).encode(), signer);
        });
    }

    /**
     * Publishes this member's retained invitation, unless it is published already.
     *
     * @param invitation the invitation link
     * @return whether a card was written now
     * @throws IOException              when this computer is not a member
     * @throws GeneralSecurityException when signing fails
     */
    public boolean publishCard(String invitation) throws IOException, GeneralSecurityException {
        return appends.call(current -> {
            if (invitation.equals(current.membershipProjection().cards().get(signer.nodeId()))) {
                return false;
            }
            current.append(UUID.randomUUID(), PredictionEventType.MEMBER_CARD, signer.nodeId(), new MemberCard(invitation).encode(), signer);
            return true;
        });
    }

    /**
     * Makes this computer the owner with the recovery key, from a member's computer (designer item 12).
     *
     * @param recovery    the recovery key, from its 24 words
     * @param newRecovery the recovery key to name from now on, or empty to keep none
     * @throws IOException              when the words are not the project's recovery key, or this computer is not a member
     * @throws GeneralSecurityException when signing fails
     */
    public void recover(RecoveryKey recovery, Optional<RecoveryKey> newRecovery) throws IOException, GeneralSecurityException {
        appends.run(current -> {
            byte[] next = newRecovery.map(RecoveryKey::publicKey).orElse(new byte[0]);
            long revision = current.membershipProjection().revision();
            byte[] statement = OwnerChanged.statement(current.projectId(), signer.publicKeyEncoded(), next, revision);
            byte[] signature = recovery.identity().sign(statement);
            current.append(UUID.randomUUID(), PredictionEventType.OWNER_CHANGED, signer.nodeId(),
                    new OwnerChanged(signer.publicKeyEncoded(), next, revision, signature).encode(), signer);
        });
    }

    /**
     * Writes what a step-5 owner's settings named: the project shared by this computer, with no recovery key, and each
     * member accepted (designer item 14). Nothing is written for a member already accepted.
     *
     * @param members the members the step-5 file named
     * @throws IOException              when an entry is refused
     * @throws GeneralSecurityException when signing fails
     */
    public void migrateOwner(Set<String> members) throws IOException, GeneralSecurityException {
        share(Optional.empty());
        for (String member : members) {
            // A name that is no node ID never identified a computer that could connect; it is left out, not fatal.
            if (NodeIdentity.isNodeId(member) && !member.equals(signer.nodeId()) && !appends.fresh().membershipProjection().members().contains(member)) {
                requestJoin(member);
                answer(member, true);
            }
        }
    }

    /**
     * Whether a computer belongs to the project now, as this computer's log says.
     *
     * @param nodeId the computer
     * @return whether it is a member of the shared project
     * @throws IOException              when the log cannot be read
     * @throws GeneralSecurityException when it cannot be verified
     */
    public boolean isMember(String nodeId) throws IOException, GeneralSecurityException {
        MembershipProjection membership = appends.fresh().membershipProjection();
        return membership.shared() && membership.members().contains(nodeId);
    }

    /**
     * Whether this computer joined another computer's project: it dialed an inviter, or holds a copy of another
     * computer's log. A step-5 owner's settings file says it shared its own (its copies are its members' logs).
     */
    private static boolean joinedAnother(PredictionEventStore current) throws IOException, GeneralSecurityException {
        Optional<org.synesis.coordination.persistence.ReplicationSettings> settings =
                org.synesis.coordination.persistence.ReplicationSettings.read(current.rootDirectory());
        if (settings.isPresent() && !settings.get().shared() && !settings.get().isMember()) {
            return false;
        }
        boolean dialed = !org.synesis.coordination.persistence.ReplicationPeers.read(current.rootDirectory()).dialed().isEmpty();
        boolean copies = current.logHeads().keySet().stream().anyMatch(log -> !log.equals(PredictionEventStore.OWN_LOG));
        return dialed || copies;
    }

    private static Optional<JoinRequest> waitingRequest(MembershipProjection membership, String joinerNodeId) {
        return membership.openRequests().stream().filter(request -> request.joinerNodeId().equals(joinerNodeId)).findFirst();
    }
}
