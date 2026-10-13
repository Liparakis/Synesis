package org.synesis.workspace.application.agent.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.workspace.application.project.ProjectLocation;

/**
 * Holds a waiting caller until the project has something new for it, or a bounded time passes.
 *
 * <p>An agent told to wait asked again every few seconds and gave up after a dozen unchanged
 * answers, about a minute, while the peer it waited for needed several. It then stopped and was
 * started again as a new session that had to take its own lane back; that churn is where a
 * reviewer was once left waiting for good. Holding the answer here lets one call wait as long
 * as a dozen did, without a model turn for each, so an agent can sit through a peer's work.</p>
 *
 * <p>Nothing is decided here. The hold ends on any recorded event that is not liveness
 * bookkeeping, and the caller's next action is then projected afresh by the code that always
 * projects it. A hold that ends early or late changes when an answer is given, never which.</p>
 */
public final class ProjectChangeWait {

    private static final long CHECK_EVERY_MILLIS = 400L;

    private ProjectChangeWait() {
    }

    /**
     * Returns how many events the project's log holds, read from its directory without replaying it.
     *
     * @param location project location
     * @return the number of recorded events; zero when nothing is recorded yet
     */
    public static long head(ProjectLocation location) {
        Path events = location.coordinationDirectory().resolve("events");
        if (!Files.isDirectory(events)) {
            return 0L;
        }
        try (Stream<Path> files = Files.list(events)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".sce")).count();
        } catch (IOException unreadable) {
            return 0L;
        }
    }

    /**
     * Waits until an event that could change the caller's next action, or carries a message, is
     * recorded after a known head, or the time is up.
     *
     * @param location  project location
     * @param knownHead number of events the caller's last answer was projected from
     * @param atMost    longest time to hold; zero or negative holds nothing
     * @return {@code true} when such an event was recorded, {@code false} when the time passed
     *         or the thread was interrupted
     */
    public static boolean awaitChange(ProjectLocation location, long knownHead, Duration atMost) {
        long deadline = System.nanoTime() + atMost.toNanos();
        long seen = knownHead;
        while (System.nanoTime() < deadline) {
            long head = head(location);
            if (head > seen) {
                if (changedSince(location, seen)) {
                    return true;
                }
                seen = head;
            }
            try {
                Thread.sleep(CHECK_EVERY_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** A heartbeat and a mark of delivery give a waiting caller nothing new; anything else may, a message included. */
    private static boolean changedSince(ProjectLocation location, long sequence) {
        try {
            List<PredictionEvent> events = location.openCoordinationStore().events();
            return events.stream().skip(Math.max(0L, sequence))
                    .anyMatch(event -> event.type() != PredictionEventType.PARTICIPANT_HEARTBEAT && event.type() != PredictionEventType.CONVERSATION_MESSAGES_DELIVERED);
        } catch (IOException | GeneralSecurityException unreadable) {
            // The log could not be read just now. Ending the hold lets the ordinary projection say so.
            return true;
        }
    }
}
