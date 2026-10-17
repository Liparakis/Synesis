package org.synesis.cli.command.coordination;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.integration.IntegrationOrchestrationService;

/**
 * Runs the leader's integration pass shortly after a member's entry lands (ADR-0098, "Merging runs on A"), so the
 * leader computer needs no agent of its own to merge a member's work.
 *
 * <p>Requests are coalesced: any number of entries within the delay cause one pass, and an entry that arrives while a
 * pass runs causes one more after it. One thread runs the passes, so they never overlap in this process; the
 * project's integration lock keeps them from overlapping with a pass another process of the leader runs. The pass
 * needs no outcome: whatever it could not finish, the next entry or the next agent call tries again.</p>
 */
final class MergeDriver implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MergeDriver.class.getName());

    private final Runnable pass;
    private final long delayMillis;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ScheduledExecutorService runner = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "synesis-merge-driver");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Creates a driver that runs {@code pass} after each burst of member entries.
     *
     * @param pass        the integration pass
     * @param delayMillis how long after the first entry of a burst the pass runs
     */
    MergeDriver(Runnable pass, long delayMillis) {
        this.pass = pass;
        this.delayMillis = delayMillis;
    }

    /**
     * Creates the driver of one project's leader log.
     *
     * @param controlRoot  the project's control checkout
     * @param projectData  the project's coordination folder
     * @param projectId    the project
     * @param node         the leader's identity, which signs the pass's entries
     * @param delayMillis  how long after a member's entry the pass runs
     * @return the driver
     */
    static MergeDriver forProject(Path controlRoot, Path projectData, UUID projectId, NodeIdentity node, long delayMillis) {
        IntegrationOrchestrationService integration = new IntegrationOrchestrationService();
        return new MergeDriver(() -> {
            try {
                integration.orchestrateIntegration(controlRoot, new PredictionEventStore(projectData, projectId), node);
            } catch (Exception failure) {
                LOG.log(System.Logger.Level.WARNING, "merge pass failed; the next member entry or agent call retries it", failure);
            }
        }, delayMillis);
    }

    /**
     * Also asks for a pass at a fixed interval while the driver lives. A member's work can become
     * mergeable with no member entry after it: its snapshot was accepted by a reviewer on this
     * computer, and its author, woken, found nothing to record. With no agent here calling in, that
     * work waited for ever (real run across two computers, 2026-10-17); the next pass merges it.
     *
     * @param intervalMillis time between passes
     * @return this driver
     */
    MergeDriver repeatEvery(long intervalMillis) {
        runner.scheduleWithFixedDelay(this::onMemberEntry, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return this;
    }

    /**
     * Asks for a pass: called when a member's entry went on the log. Returns at once.
     */
    void onMemberEntry() {
        if (scheduled.compareAndSet(false, true)) {
            runner.schedule(() -> {
                // Cleared before the pass, so an entry that lands during it asks for the next one.
                scheduled.set(false);
                pass.run();
            }, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void close() {
        runner.shutdownNow();
    }
}
