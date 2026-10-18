package org.synesis.cli.command.coordination;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.replication.MembershipView;

import org.synesis.workspace.transport.control.LinkRuntimeOwner;

/**
 * Keeps this computer linked to every other member it can reach (ADR-0098 step 6, "Links"; designer item 13): it
 * hosts its member card and publishes it in the log, and dials the card of each member it holds no session with.
 *
 * <p>Of two members, the one with the lower node ID dials first, so a pair does not open two sessions at once; the
 * other dials too once it has had no session with that member for {@value #FALLBACK_SECONDS} seconds, so a member
 * that cannot be reached in one direction is still linked in the other. A dial redials on its own afterwards, for as
 * long as the member is one.</p>
 */
final class MemberLinks implements AutoCloseable {

    static final long FALLBACK_SECONDS = 60;
    private static final long INTERVAL_SECONDS = 15;
    private static final System.Logger LOG = System.getLogger(MemberLinks.class.getName());

    private final LinkRuntimeOwner owner;
    private final MembershipView view;
    private final MembershipService membership;
    private final String self;
    private final Map<String, Long> missingSince = new ConcurrentHashMap<>();
    private final ScheduledExecutorService runner = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "synesis-member-links");
        thread.setDaemon(true);
        return thread;
    });

    MemberLinks(LinkRuntimeOwner owner, MembershipView view, MembershipService membership, String self) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.view = Objects.requireNonNull(view, "view");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.self = Objects.requireNonNull(self, "self");
    }

    /**
     * Starts looking now and every {@value #INTERVAL_SECONDS} seconds.
     *
     * @return this
     */
    MemberLinks start() {
        runner.scheduleWithFixedDelay(this::tick, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
        return this;
    }

    /** One look: publish this computer's card, dial the others'. Nothing here throws out. */
    void tick() {
        try {
            if (!view.belongs()) {
                return;
            }
            membership.publishCard(owner.hostCard());
            long now = System.nanoTime();
            view.cards().forEach((node, card) -> {
                if (node.equals(self) || owner.connectedTo(node)) {
                    missingSince.remove(node);
                    return;
                }
                long since = missingSince.computeIfAbsent(node, ignored -> now);
                if (self.compareTo(node) < 0 || now - since > Duration.ofSeconds(FALLBACK_SECONDS).toNanos()) {
                    owner.dialCard(node, card);
                }
            });
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.WARNING, "The members could not all be linked; the next look tries again.", failure);
        }
    }

    @Override
    public void close() {
        runner.shutdownNow();
    }
}
