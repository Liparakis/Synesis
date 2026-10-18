package org.synesis.coordination.replication;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationPeers;
import org.synesis.coordination.persistence.ReplicationSettings;

/**
 * Which computers this one exchanges logs and code with, as the membership entries say (ADR-0098 step 6, designer
 * items 10 and 15). It reads the log afresh, at most every {@value #REFRESH_MILLIS} ms, so a computer accepted or
 * removed is served or refused from then on, with no restart.
 *
 * <p>Before this computer holds {@code PROJECT_SHARED} there are no members to go by: it trusts the computers it
 * dialed with an invitation, and those a step-5 settings file names (its leader, or its members), and copies every log
 * they serve. Once the owner's entries arrive, membership alone decides. Anything unreadable trusts no one.</p>
 */
public final class MembershipView {

    private static final long REFRESH_MILLIS = 500;
    private static final System.Logger LOGGER = System.getLogger(MembershipView.class.getName());

    private final Path root;
    private final UUID projectId;
    private final String self;
    private PredictionEventStore store;
    private long refreshedAt;

    /**
     * Reads the membership of one project on this computer.
     *
     * @param root      this computer's coordination folder
     * @param projectId the project
     * @param self      this computer's node, which is never among the computers it exchanges with
     */
    public MembershipView(Path root, UUID projectId, String self) {
        this.root = CoordinationNullException.requireNonNull(root, "root");
        this.projectId = CoordinationNullException.requireNonNull(projectId, "project ID");
        this.self = CoordinationNullException.requireNonNull(self, "self");
    }

    /**
     * The other computers this one exchanges with now: the other members, or before sharing the computers it trusts.
     *
     * @return their node IDs
     */
    public Set<String> others() {
        Optional<MembershipProjection> membership = membership();
        if (membership.isEmpty()) {
            return Set.of();
        }
        Set<String> others = new LinkedHashSet<>(membership.get().shared() ? membership.get().members() : trustedBeforeSharing());
        others.remove(self);
        return Set.copyOf(others);
    }

    /**
     * Whether a computer may pull from and be pulled by this one, and is served code.
     *
     * @param node the computer
     * @return whether it is admitted now
     */
    public boolean admitted(String node) {
        Optional<MembershipProjection> membership = membership();
        if (membership.isEmpty()) {
            return false;
        }
        return !node.equals(self) && (membership.get().shared() ? membership.get().members().contains(node) : trustedBeforeSharing().contains(node));
    }

    /**
     * Whether this computer keeps a copy of a computer's log: a member's, or a removed member's, so every copy stays
     * complete; before sharing, any log a trusted computer serves.
     *
     * @param node the computer whose log it is
     * @return whether its log is copied
     */
    public boolean copied(String node) {
        Optional<MembershipProjection> membership = membership();
        return membership.isPresent() && (!membership.get().shared() || membership.get().logCopied(node));
    }

    /**
     * The members' retained invitations, which this computer dials.
     *
     * @return each member's invitation by computer; empty before sharing
     */
    public Map<String, String> cards() {
        return membership().map(MembershipProjection::cards).orElse(Map.of());
    }

    /**
     * Whether this computer belongs to a shared project now: it then hosts a card and dials the others'.
     *
     * @return whether it is a member
     */
    public boolean belongs() {
        return membership().filter(MembershipProjection::shared).map(found -> found.members().contains(self)).orElse(false);
    }

    /**
     * The owner's computer, once the project is shared.
     *
     * @return its node ID, or empty
     */
    public Optional<String> owner() {
        return membership().flatMap(MembershipProjection::owner);
    }

    private synchronized Optional<MembershipProjection> membership() {
        try {
            if (store == null) {
                store = new PredictionEventStore(root, projectId);
                refreshedAt = System.nanoTime();
            } else if (System.nanoTime() - refreshedAt > REFRESH_MILLIS * 1_000_000L) {
                store.catchUp();
                refreshedAt = System.nanoTime();
            }
            return Optional.of(store.membershipProjection());
        } catch (IOException | GeneralSecurityException | RuntimeException unreadable) {
            LOGGER.log(System.Logger.Level.WARNING, "The membership could not be read; no computer is trusted until it can.", unreadable);
            store = null;
            return Optional.empty();
        }
    }

    private Set<String> trustedBeforeSharing() {
        Set<String> trusted = new LinkedHashSet<>();
        try {
            trusted.addAll(ReplicationPeers.read(root).dialed().keySet());
        } catch (CoordinationException unreadable) {
            // Restores nothing, as a restart does.
        }
        try {
            ReplicationSettings.read(root).ifPresent(settings -> {
                if (settings.leaderNodeId() != null) {
                    trusted.add(settings.leaderNodeId());
                }
                trusted.addAll(settings.members());
            });
        } catch (CoordinationException unreadable) {
            // An unreadable role trusts no one it names.
        }
        return trusted;
    }
}
