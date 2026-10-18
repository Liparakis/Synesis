package org.synesis.coordination.domain.membership;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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
import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.MissingReferenceException;
import org.synesis.link.identity.NodeIdentity;

/**
 * Who belongs to a shared project, read from the membership entries (ADR-0098 step 6, "Membership and keys"; designer
 * items 7 to 12). The owner key is the owner computer's node key; the owner's statements carry the revision they were
 * written at, the number of owner statements before them, so an old one cannot be replayed. A project with no
 * {@code PROJECT_SHARED} is not shared, and nothing here refuses anything there.
 *
 * <p>Every rule is checked in {@link #validate}, on this computer's own append and on arrival alike; an arriving entry
 * that breaks one counts for nothing, as in step 4. An arriving entry that refers to what this computer has not
 * applied yet (a request in another member's log, an acceptance not arrived, an owner statement of a later revision)
 * waits for it, so every computer ends with the same membership whatever the order. One rule reaches every entry, not
 * only membership ones: an entry of a removed computer's log after the head its removal names counts for nothing.</p>
 */
public final class MembershipProjection {

    /**
     * One join request and its answer.
     *
     * @param requestId       the request
     * @param joinerNodeId    the joining computer
     * @param inviterNodeId   the member that holds its session and wrote the request
     * @param accepted        the answer applied, or empty while none is
     * @param answeredByOwner whether the owner gave that answer
     */
    public record JoinRequest(UUID requestId, String joinerNodeId, String inviterNodeId, Optional<Boolean> accepted,
                              boolean answeredByOwner) {
    }

    private boolean shared;
    private String ownerNodeId;
    private byte[] recoveryPublicKey = new byte[0];
    private long revision;
    private JoinPolicy policy = JoinPolicy.OWNER_ONLY;
    /** Each policy by the revision it took effect at, so a member's answer is judged by the policy it was written under. */
    private final java.util.TreeMap<Long, JoinPolicy> policies = new java.util.TreeMap<>();
    private final Set<String> members = new LinkedHashSet<>();
    private final Set<String> everMembers = new LinkedHashSet<>();
    private final Map<String, Long> removedHeads = new LinkedHashMap<>();
    private final Map<UUID, JoinRequest> requests = new LinkedHashMap<>();
    private final Map<String, String> cards = new LinkedHashMap<>();
    /** Recovery signatures already used: one authorises one change, never a second one later. */
    private final Set<String> usedRecoveryStatements = new LinkedHashSet<>();
    private final UUID projectId;

    /**
     * Creates an empty projection.
     *
     * @param projectId the project the recovery statements name
     */
    public MembershipProjection(UUID projectId) {
        this.projectId = projectId;
    }

    /**
     * Checks an entry against the membership rules without changing anything.
     *
     * @param event the entry
     * @param mode  how it is judged; a replay is not judged again
     * @throws IOException with the rule it breaks
     */
    public synchronized void validate(PredictionEvent event, ApplyMode mode) throws IOException {
        if (mode == ApplyMode.REPLAY) {
            return;
        }
        String signer = event.actorNodeId();
        Long head = removedHeads.get(signer);
        if (head != null && event.sequence() > head) {
            throw new CoordinationException("MEMBER_REMOVED");
        }
        // An entry of a removed computer up to its head was written while it belonged, and is judged so wherever it
        // arrives, before or after the removal (designer item 10).
        boolean writtenAsMember = members.contains(signer) || head != null;
        switch (event.type()) {
            case PROJECT_SHARED -> {
                ProjectShared payload = ProjectShared.decode(event.payload());
                if (shared) {
                    throw new CoordinationException("PROJECT_ALREADY_SHARED");
                }
                requireKeyOf(payload.ownerPublicKey(), signer, "OWNER_KEY_NOT_THE_SIGNERS");
            }
            case JOIN_REQUESTED -> {
                JoinRequested payload = JoinRequested.decode(event.payload());
                requireWrittenAsMember(signer, writtenAsMember, mode);
                if (!NodeIdentity.isNodeId(payload.joinerNodeId())) {
                    throw new CoordinationException("JOINER_NOT_A_NODE_ID");
                }
                if (members.contains(payload.joinerNodeId())) {
                    throw new CoordinationException("ALREADY_A_MEMBER");
                }
                if (requests.containsKey(payload.requestId())) {
                    throw new CoordinationException("JOIN_REQUEST_EXISTS");
                }
            }
            case JOIN_ANSWERED -> validateAnswer(JoinAnswered.decode(event.payload()), signer, writtenAsMember, mode);
            case JOIN_POLICY_SET -> requireOwnerStatement(signer, JoinPolicySet.decode(event.payload()).revision(), mode);
            case MEMBER_REMOVED -> {
                MemberRemoved payload = MemberRemoved.decode(event.payload());
                requireOwnerStatement(signer, payload.revision(), mode);
                if (!members.contains(payload.nodeId()) && !everMembers.contains(payload.nodeId())) {
                    // A member accepted by another member's entry this computer has not applied yet.
                    throw missing(mode, "NOT_A_REMOVABLE_MEMBER");
                }
                if (!members.contains(payload.nodeId()) || payload.nodeId().equals(ownerNodeId)) {
                    throw new CoordinationException("NOT_A_REMOVABLE_MEMBER");
                }
            }
            case MEMBER_CARD -> {
                MemberCard.decode(event.payload());
                requireWrittenAsMember(signer, writtenAsMember, mode);
            }
            case OWNER_CHANGED -> validateOwnerChange(OwnerChanged.decode(event.payload()), signer, writtenAsMember, mode);
            default -> {
            }
        }
    }

    /**
     * Judges an answer so that every computer ends the same whatever order the answers arrive in: the owner's answer
     * decides and takes its revision, even over a member's acceptance applied before it; a member's acceptance counts
     * only while no answer is applied, under the policy of the revision it was written at.
     */
    private void validateAnswer(JoinAnswered payload, String signer, boolean writtenAsMember, ApplyMode mode) throws IOException {
        requireShared(mode);
        JoinRequest request = requests.get(payload.requestId());
        if (request == null) {
            // The request is in the inviting member's log, which may arrive after the answer.
            throw missing(mode, "JOIN_REQUEST_NOT_FOUND");
        }
        if (!request.joinerNodeId().equals(payload.joinerNodeId())) {
            throw new CoordinationException("JOIN_REQUEST_NOT_FOUND");
        }
        if (payload.revision() > revision) {
            // Written after owner statements this computer has not applied yet.
            throw missing(mode, "STATEMENT_REVISION_STALE");
        }
        if (signer.equals(ownerNodeId)) {
            requireRevision(payload.revision());
            if (request.answeredByOwner()) {
                throw new CoordinationException("JOIN_ALREADY_ANSWERED");
            }
            return;
        }
        if (request.accepted().isPresent()) {
            throw new CoordinationException("JOIN_ALREADY_ANSWERED");
        }
        if (policyAt(payload.revision()) != JoinPolicy.ANY_MEMBER || !payload.accepted() || !writtenAsMember) {
            throw new CoordinationException("JOIN_ANSWER_NOT_THE_OWNERS");
        }
    }

    private void validateOwnerChange(OwnerChanged payload, String signer, boolean writtenAsMember, ApplyMode mode) throws IOException {
        requireShared(mode);
        String newOwner = NodeIdentity.deriveNodeId(payload.ownerPublicKey());
        if (!members.contains(newOwner)) {
            throw everMembers.contains(newOwner) ? new CoordinationException("NEW_OWNER_NOT_A_MEMBER") : missing(mode, "NEW_OWNER_NOT_A_MEMBER");
        }
        if (payload.recoverySignature().length == 0) {
            requireOwnerStatement(signer, payload.revision(), mode);
            return;
        }
        // The recovery key speaks for the owner from any member's computer, at the revision it saw or an earlier one,
        // once: a used signature never authorises a later change back.
        requireWrittenAsMember(signer, writtenAsMember, mode);
        if (payload.revision() > revision) {
            throw missing(mode, "RECOVERY_STATEMENT_INVALID");
        }
        if (recoveryPublicKey.length == 0 || usedRecoveryStatements.contains(java.util.HexFormat.of().formatHex(payload.recoverySignature()))) {
            throw new CoordinationException("RECOVERY_STATEMENT_INVALID");
        }
        try {
            if (!NodeIdentity.verifySignature(recoveryPublicKey, payload.recoveryStatement(projectId), payload.recoverySignature())) {
                throw new CoordinationException("RECOVERY_SIGNATURE_INVALID");
            }
        } catch (GeneralSecurityException malformed) {
            throw new CoordinationException("RECOVERY_SIGNATURE_INVALID", malformed);
        }
    }

    /**
     * Applies a judged entry.
     *
     * @param event the entry
     * @throws IOException when its payload cannot be read
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        String signer = event.actorNodeId();
        switch (event.type()) {
            case PROJECT_SHARED -> {
                ProjectShared payload = ProjectShared.decode(event.payload());
                if (shared) {
                    return;
                }
                shared = true;
                ownerNodeId = signer;
                recoveryPublicKey = payload.recoveryPublicKey();
                admit(signer);
            }
            case JOIN_REQUESTED -> {
                JoinRequested payload = JoinRequested.decode(event.payload());
                requests.putIfAbsent(payload.requestId(), new JoinRequest(payload.requestId(), payload.joinerNodeId(), signer, Optional.empty(), false));
            }
            case JOIN_ANSWERED -> applyAnswer(JoinAnswered.decode(event.payload()), signer);
            case JOIN_POLICY_SET -> {
                revision++;
                policy = JoinPolicySet.decode(event.payload()).policy();
                policies.put(revision, policy);
            }
            case MEMBER_REMOVED -> {
                MemberRemoved payload = MemberRemoved.decode(event.payload());
                members.remove(payload.nodeId());
                removedHeads.put(payload.nodeId(), payload.head());
                cards.remove(payload.nodeId());
                revision++;
            }
            case MEMBER_CARD -> {
                // A removed computer's card, written while it belonged, is not dialed any more.
                if (members.contains(signer)) {
                    cards.put(signer, MemberCard.decode(event.payload()).invitation());
                }
            }
            case OWNER_CHANGED -> {
                OwnerChanged payload = OwnerChanged.decode(event.payload());
                if (payload.recoverySignature().length > 0) {
                    usedRecoveryStatements.add(java.util.HexFormat.of().formatHex(payload.recoverySignature()));
                }
                ownerNodeId = NodeIdentity.deriveNodeId(payload.ownerPublicKey());
                recoveryPublicKey = payload.recoveryPublicKey();
                revision++;
            }
            default -> {
            }
        }
    }

    private void applyAnswer(JoinAnswered payload, String signer) {
        JoinRequest request = requests.get(payload.requestId());
        if (request == null) {
            return;
        }
        if (signer.equals(ownerNodeId)) {
            revision++;
            boolean wasAccepted = request.accepted().orElse(false);
            requests.put(request.requestId(), new JoinRequest(request.requestId(), request.joinerNodeId(), request.inviterNodeId(),
                    Optional.of(payload.accepted()), true));
            if (payload.accepted()) {
                admit(request.joinerNodeId());
            } else if (wasAccepted) {
                // The owner's decline decides over a member's acceptance that arrived first; the joiner's log stays copied.
                members.remove(request.joinerNodeId());
                cards.remove(request.joinerNodeId());
            }
            return;
        }
        if (request.accepted().isEmpty() && payload.accepted()) {
            requests.put(request.requestId(), new JoinRequest(request.requestId(), request.joinerNodeId(), request.inviterNodeId(),
                    Optional.of(true), false));
            admit(request.joinerNodeId());
        }
    }

    private void admit(String node) {
        members.add(node);
        everMembers.add(node);
        removedHeads.remove(node);
    }

    private JoinPolicy policyAt(long statementRevision) {
        Map.Entry<Long, JoinPolicy> set = policies.floorEntry(statementRevision);
        return set == null ? JoinPolicy.OWNER_ONLY : set.getValue();
    }

    /**
     * What an entry that refers to something this computer has not applied yet meets: on this computer's own append a
     * refusal, on arrival a wait, so the entry applies once what it refers to has arrived, the same on every computer.
     */
    private static CoordinationException missing(ApplyMode mode, String code) {
        return mode.checking() ? new CoordinationException(code) : new MissingReferenceException(code);
    }

    private void requireShared(ApplyMode mode) throws CoordinationException {
        if (!shared) {
            throw missing(mode, "PROJECT_NOT_SHARED");
        }
    }

    private void requireWrittenAsMember(String node, boolean writtenAsMember, ApplyMode mode) throws CoordinationException {
        requireShared(mode);
        if (!writtenAsMember) {
            // A computer whose acceptance has not arrived here yet, unless it is one this computer saw removed.
            throw everMembers.contains(node) ? new CoordinationException("NOT_A_MEMBER") : missing(mode, "NOT_A_MEMBER");
        }
    }

    /**
     * An owner statement: written by the owner at the revision it holds. One written at a later revision waits for the
     * statements before it, which may include the change that made its writer the owner.
     */
    private void requireOwnerStatement(String signer, long stated, ApplyMode mode) throws CoordinationException {
        requireShared(mode);
        if (stated > revision) {
            throw missing(mode, "STATEMENT_REVISION_STALE");
        }
        if (!signer.equals(ownerNodeId)) {
            throw new CoordinationException("NOT_THE_OWNER");
        }
        requireRevision(stated);
    }

    private void requireRevision(long stated) throws CoordinationException {
        if (stated != revision) {
            throw new CoordinationException("STATEMENT_REVISION_STALE");
        }
    }

    private static void requireKeyOf(byte[] key, String node, String code) throws CoordinationException {
        if (!NodeIdentity.deriveNodeId(key).equals(node)) {
            throw new CoordinationException(code);
        }
    }

    /**
     * Returns whether the project has been shared.
     *
     * @return whether the project has been shared
     */
    public synchronized boolean shared() {
        return shared;
    }

    /**
     * Returns the owner's computer, or empty before the project is shared.
     *
     * @return the owner's computer, or empty before the project is shared
     */
    public synchronized Optional<String> owner() {
        return Optional.ofNullable(ownerNodeId);
    }

    /**
     * Returns the recovery key's public half, empty when the project has none.
     *
     * @return the recovery key's public half, empty when the project has none
     */
    public synchronized byte[] recoveryPublicKey() {
        return recoveryPublicKey.clone();
    }

    /**
     * Returns the number of owner statements applied: the revision the next one is written at.
     *
     * @return the number of owner statements applied: the revision the next one is written at
     */
    public synchronized long revision() {
        return revision;
    }

    /**
     * Returns who may answer a join besides the owner.
     *
     * @return who may answer a join besides the owner
     */
    public synchronized JoinPolicy policy() {
        return policy;
    }

    /**
     * Returns the current members, the owner first.
     *
     * @return the current members, the owner first
     */
    public synchronized Set<String> members() {
        return Set.copyOf(members);
    }

    /**
     * Whether a computer belongs to the shared project now; in a project that is not shared, none does.
     *
     * @param node the computer
     * @return whether it is a member
     */
    public synchronized boolean isMember(String node) {
        return shared && members.contains(node);
    }

    /**
     * Whether a computer's log is copied: it belongs to the project, or it did and was removed, so that every copy of
     * its log stays complete.
     *
     * @param node the computer
     * @return whether its log is copied
     */
    public synchronized boolean logCopied(String node) {
        return !shared || everMembers.contains(node);
    }

    /**
     * The head of a removed computer's log its removal names.
     *
     * @param node the computer
     * @return the head, or empty for a computer not removed
     */
    public synchronized OptionalLong removedHead(String node) {
        Long head = removedHeads.get(node);
        return head == null ? OptionalLong.empty() : OptionalLong.of(head);
    }

    /**
     * Returns the join requests, oldest first.
     *
     * @return the join requests, oldest first
     */
    public synchronized List<JoinRequest> requests() {
        return List.copyOf(requests.values());
    }

    /**
     * Returns the join requests no one has answered yet, oldest first.
     *
     * @return the join requests no one has answered yet, oldest first
     */
    public synchronized List<JoinRequest> openRequests() {
        return requests.values().stream().filter(request -> request.accepted().isEmpty()).toList();
    }

    /**
     * Returns each member's retained invitation, by computer.
     *
     * @return each member's retained invitation, by computer
     */
    public synchronized Map<String, String> cards() {
        return Map.copyOf(cards);
    }
}
