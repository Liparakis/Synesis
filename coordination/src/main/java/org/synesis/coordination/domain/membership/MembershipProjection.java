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
import org.synesis.link.identity.NodeIdentity;

/**
 * Who belongs to a shared project, read from the membership entries (ADR-0098 step 6, "Membership and keys"; designer
 * items 7 to 12). The owner key is the owner computer's node key; the owner's statements carry the revision they were
 * written at, the number of owner statements before them, so an old one cannot be replayed. A project with no
 * {@code PROJECT_SHARED} is not shared, and nothing here refuses anything there.
 *
 * <p>Every rule is checked in {@link #validate}, on this computer's own append and on arrival alike; an arriving entry
 * that breaks one counts for nothing, as in step 4. One rule reaches every entry, not only membership ones: an entry of
 * a removed computer's log after the head its removal names counts for nothing.</p>
 */
public final class MembershipProjection {

    /** One join request and its answer. */
    public record JoinRequest(UUID requestId, String joinerNodeId, String inviterNodeId, Optional<Boolean> accepted) {
    }

    private boolean shared;
    private String ownerNodeId;
    private byte[] recoveryPublicKey = new byte[0];
    private long revision;
    private JoinPolicy policy = JoinPolicy.OWNER_ONLY;
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
                requireWrittenAsMember(signer, writtenAsMember);
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
            case JOIN_ANSWERED -> {
                JoinAnswered payload = JoinAnswered.decode(event.payload());
                requireShared();
                JoinRequest request = requests.get(payload.requestId());
                if (request == null || !request.joinerNodeId().equals(payload.joinerNodeId())) {
                    throw new CoordinationException("JOIN_REQUEST_NOT_FOUND");
                }
                if (request.accepted().isPresent()) {
                    throw new CoordinationException("JOIN_ALREADY_ANSWERED");
                }
                if (signer.equals(ownerNodeId)) {
                    requireRevision(payload.revision());
                } else if (policy != JoinPolicy.ANY_MEMBER || !payload.accepted() || !writtenAsMember) {
                    throw new CoordinationException("JOIN_ANSWER_NOT_THE_OWNERS");
                }
            }
            case JOIN_POLICY_SET -> {
                JoinPolicySet payload = JoinPolicySet.decode(event.payload());
                requireOwner(signer);
                requireRevision(payload.revision());
            }
            case MEMBER_REMOVED -> {
                MemberRemoved payload = MemberRemoved.decode(event.payload());
                requireOwner(signer);
                requireRevision(payload.revision());
                if (!members.contains(payload.nodeId()) || payload.nodeId().equals(ownerNodeId)) {
                    throw new CoordinationException("NOT_A_REMOVABLE_MEMBER");
                }
            }
            case MEMBER_CARD -> {
                MemberCard.decode(event.payload());
                requireWrittenAsMember(signer, writtenAsMember);
            }
            case OWNER_CHANGED -> validateOwnerChange(OwnerChanged.decode(event.payload()), signer, writtenAsMember);
            default -> {
            }
        }
    }

    private void validateOwnerChange(OwnerChanged payload, String signer, boolean writtenAsMember) throws IOException {
        requireShared();
        String newOwner = NodeIdentity.deriveNodeId(payload.ownerPublicKey());
        if (!members.contains(newOwner)) {
            throw new CoordinationException("NEW_OWNER_NOT_A_MEMBER");
        }
        if (payload.recoverySignature().length == 0) {
            requireOwner(signer);
            requireRevision(payload.revision());
            return;
        }
        // The recovery key speaks for the owner from any member's computer, at the revision it saw or an earlier one,
        // once: a used signature never authorises a later change back.
        requireWrittenAsMember(signer, writtenAsMember);
        if (recoveryPublicKey.length == 0 || payload.revision() > revision
                || usedRecoveryStatements.contains(java.util.HexFormat.of().formatHex(payload.recoverySignature()))) {
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
                requests.putIfAbsent(payload.requestId(), new JoinRequest(payload.requestId(), payload.joinerNodeId(), signer, Optional.empty()));
            }
            case JOIN_ANSWERED -> {
                JoinAnswered payload = JoinAnswered.decode(event.payload());
                JoinRequest request = requests.get(payload.requestId());
                if (request == null || request.accepted().isPresent()) {
                    return;
                }
                requests.put(payload.requestId(), new JoinRequest(request.requestId(), request.joinerNodeId(), request.inviterNodeId(),
                        Optional.of(payload.accepted())));
                if (payload.accepted()) {
                    admit(payload.joinerNodeId());
                }
                if (signer.equals(ownerNodeId)) {
                    revision++;
                }
            }
            case JOIN_POLICY_SET -> {
                policy = JoinPolicySet.decode(event.payload()).policy();
                revision++;
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

    private void admit(String node) {
        members.add(node);
        everMembers.add(node);
        removedHeads.remove(node);
    }

    private void requireShared() throws CoordinationException {
        if (!shared) {
            throw new CoordinationException("PROJECT_NOT_SHARED");
        }
    }

    private void requireWrittenAsMember(String node, boolean writtenAsMember) throws CoordinationException {
        requireShared();
        if (!writtenAsMember) {
            throw new CoordinationException("NOT_A_MEMBER");
        }
    }

    private void requireOwner(String node) throws CoordinationException {
        requireShared();
        if (!node.equals(ownerNodeId)) {
            throw new CoordinationException("NOT_THE_OWNER");
        }
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

    /** @return whether the project has been shared */
    public synchronized boolean shared() {
        return shared;
    }

    /** @return the owner's computer, or empty before the project is shared */
    public synchronized Optional<String> owner() {
        return Optional.ofNullable(ownerNodeId);
    }

    /** @return the recovery key's public half, empty when the project has none */
    public synchronized byte[] recoveryPublicKey() {
        return recoveryPublicKey.clone();
    }

    /** @return the number of owner statements applied: the revision the next one is written at */
    public synchronized long revision() {
        return revision;
    }

    /** @return who may answer a join besides the owner */
    public synchronized JoinPolicy policy() {
        return policy;
    }

    /** @return the current members, the owner first */
    public synchronized Set<String> members() {
        return Set.copyOf(members);
    }

    /**
     * Whether a computer belongs to the project now. In a project that is not shared, every computer does: nothing
     * here decides anything there.
     *
     * @param node the computer
     * @return whether it is a member
     */
    public synchronized boolean isMember(String node) {
        return !shared || members.contains(node);
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

    /** @return the join requests, oldest first */
    public synchronized List<JoinRequest> requests() {
        return List.copyOf(requests.values());
    }

    /** @return the join requests no one has answered yet, oldest first */
    public synchronized List<JoinRequest> openRequests() {
        return requests.values().stream().filter(request -> request.accepted().isEmpty()).toList();
    }

    /** @return each member's retained invitation, by computer */
    public synchronized Map<String, String> cards() {
        return Map.copyOf(cards);
    }
}
