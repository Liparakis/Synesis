package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * The leader's side of a member's proposals (ADR-0098): decides whether an entry a member signed goes on the log,
 * and serves the entries a member is missing.
 *
 * <p>The leader never signs for a member. It checks that the entry is the authenticated sender's own and that the
 * sender is a member, that the type is not one only the leader writes, that the entry follows the leader's head,
 * and that the leader's own projections and admission rules accept it, exactly as for a local append. Then it
 * places the member's bytes unchanged, under the project append lock and a store opened after the lock was
 * taken. A member's own check is advisory (invariant 6); this one decides.</p>
 */
public final class LeaderEndpoint {

    private final Path root;
    private final UUID projectId;
    private final Supplier<Set<String>> members;

    /**
     * Serves one project's log.
     *
     * @param root      the leader's coordination folder
     * @param projectId the project
     * @param members   nodes allowed to propose; local state on the leader in step 2
     */
    public LeaderEndpoint(Path root, UUID projectId, Set<String> members) {
        this(root, projectId, fixed(CoordinationNullException.requireNonNull(members, "members")));
    }

    /**
     * Serves one project's log to the members the supplier names <em>at the time of each proposal</em>, so that a
     * member added to or removed from {@code replication.properties} needs no restart.
     *
     * @param root      the leader's coordination folder
     * @param projectId the project
     * @param members   current nodes allowed to propose; an empty set refuses everyone
     */
    public LeaderEndpoint(Path root, UUID projectId, Supplier<Set<String>> members) {
        this.root = CoordinationNullException.requireNonNull(root, "root");
        this.projectId = CoordinationNullException.requireNonNull(projectId, "project ID");
        this.members = CoordinationNullException.requireNonNull(members, "members");
    }

    private static Supplier<Set<String>> fixed(Set<String> members) {
        Set<String> copy = Set.copyOf(members);
        return () -> copy;
    }

    /**
     * Decides one proposal.
     *
     * @param proposal            the encoded entry exactly as the member signed it
     * @param authenticatedSender the node the transport authenticated as the sender
     * @return the outcome; a refusal carries the code a local refusal would
     * @throws IOException              when the project append lock is unavailable or durable I/O fails
     * @throws GeneralSecurityException when the signature cannot be checked
     */
    public ProposalOutcome accept(byte[] proposal, String authenticatedSender)
            throws IOException, GeneralSecurityException {
        PredictionEvent event;
        try {
            event = PredictionEvent.decode(proposal);
        } catch (CoordinationException malformed) {
            return new ProposalOutcome.Refused("PROPOSAL_MALFORMED");
        }
        if (!event.verify()) {
            return new ProposalOutcome.Refused("PROPOSAL_SIGNATURE_INVALID");
        }
        if (!event.actorNodeId().equals(authenticatedSender)) {
            return new ProposalOutcome.Refused("PROPOSAL_SENDER_MISMATCH");
        }
        if (!members.get().contains(authenticatedSender)) {
            return new ProposalOutcome.Refused("PROPOSER_NOT_A_MEMBER");
        }
        if (event.type().leaderOnly()) {
            return new ProposalOutcome.Refused("EVENT_TYPE_LEADER_ONLY");
        }
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(root)) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            return new PredictionEventStore(root, projectId).admit(event);
        }
    }

    /**
     * Returns the entries after a sequence, from the log as it is on disk now.
     *
     * @param sequence   the member's head sequence
     * @param maxEntries most entries to return
     * @param maxBytes   soft bound on the encoded size
     * @return encoded entries in order
     * @throws IOException              when the log cannot be read
     * @throws GeneralSecurityException when a signature cannot be checked
     */
    public List<byte[]> entriesAfter(long sequence, int maxEntries, int maxBytes)
            throws IOException, GeneralSecurityException {
        return new PredictionEventStore(root, projectId).entriesAfter(sequence, maxEntries, maxBytes);
    }
}
